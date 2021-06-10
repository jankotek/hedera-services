package com.hedera.services.txns.token;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.ByteString;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.enums.TokenType;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.store.CreationResult;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.store.tokens.common.CommonTokenStore;
import com.hedera.services.store.tokens.unique.UniqueTokenStore;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

class TokenMintTransitionLogicTest {
	long amount = 123L;
	private TokenID id = IdUtils.asToken("1.2.3");

	private CommonTokenStore tokenStore;
	private UniqueTokenStore uniqueStore;
	private TransactionContext txnCtx;
	private PlatformTxnAccessor accessor;
	private MerkleToken token;

	private TransactionBody tokenMintTxn;
	private TokenMintTransitionLogic subject;

	@BeforeEach
	private void setup() {
		tokenStore = mock(CommonTokenStore.class);
		uniqueStore = mock(UniqueTokenStore.class);
		accessor = mock(PlatformTxnAccessor.class);
		token = mock(MerkleToken.class);

		txnCtx = mock(TransactionContext.class);

		subject = new TokenMintTransitionLogic(tokenStore, uniqueStore, txnCtx);
	}

	@Test
	public void capturesInvalidMint() {
		givenValidTxnCtx();
		// and:
		given(tokenStore.mint(id, amount)).willReturn(INVALID_TOKEN_MINT_AMOUNT);
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);
		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(INVALID_TOKEN_MINT_AMOUNT);
	}

	@Test
	public void rejectsBadRefForSafety() {
		givenValidTxnCtx();
		// and:
		given(tokenStore.resolve(id)).willReturn(TokenStore.MISSING_TOKEN);

		// when:
		subject.doStateTransition();

		// then:
		verify(tokenStore, never()).mint(id, amount);
		verify(txnCtx).setStatus(INVALID_TOKEN_ID);
	}

	@Test
	public void followsHappyPath() {
		givenValidTxnCtx();
		// and:
		given(tokenStore.mint(id, amount)).willReturn(OK);
		given(token.tokenType()).willReturn(TokenType.FUNGIBLE_COMMON);

		// when:
		subject.doStateTransition();

		// then:
		verify(tokenStore).mint(id, amount);
		verify(txnCtx).setStatus(SUCCESS);
		verify(txnCtx).setNewTotalSupply(amount);
	}

	@Test
	public void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(tokenMintTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	public void setsFailInvalidIfUnhandledException() {
		givenValidTxnCtx();
		// and:
		given(tokenStore.mint(any(), anyLong()))
				.willThrow(IllegalArgumentException.class);

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(FAIL_INVALID);
	}

	@Test
	public void acceptsValidTxn() {
		givenValidTxnCtx();

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	public void rejectsMissingToken() {
		givenMissingToken();

		// expect:
		assertEquals(INVALID_TOKEN_ID, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	public void rejectsInvalidNegativeAmount() {
		givenInvalidNegativeAmount();

		// expect:
		assertEquals(INVALID_TOKEN_MINT_AMOUNT, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	public void rejectsInvalidZeroAmount() {
		givenInvalidZeroAmount();

		// expect:
		assertEquals(INVALID_TOKEN_MINT_AMOUNT, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	public void followsHappyPathForUnique() {
		givenValidUniqueTxnCtx();
		subject.doStateTransition();
		verify(uniqueStore, times(1)).mint(tokenMintTxn.getTokenMint(), RichInstant.fromJava(txnCtx.consensusTime()));
		verify(txnCtx).setStatus(SUCCESS);
		verify(txnCtx).setCreated(any(List.class));
	}

	@Test
	public void followsSadPathForUnique() {
		givenValidCommonTxnCtx();
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		given(tokenStore.resolve(any())).willReturn(TokenID.getDefaultInstance());

		subject.doStateTransition();
		verify(uniqueStore, times(0)).mint(any(), any());
		verify(txnCtx).setStatus(INVALID_TOKEN_ID);
	}

	private void givenValidTxnCtx(){
		givenValidCommonTxnCtx();
	}

	private void givenValidCommonTxnCtx() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(id)
						.setAmount(amount)
				)
				.build();
		given(accessor.getTxn()).willReturn(tokenMintTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(tokenStore.resolve(id)).willReturn(id);
		given(tokenStore.get(id)).willReturn(token);
		given(token.totalSupply()).willReturn(amount);
	}


	private void givenValidUniqueTxnCtx() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(id)
						.addMetadata(ByteString.copyFromUtf8("meta"))
						.setAmount(0)
				)
				.build();
		given(accessor.getTxn()).willReturn(tokenMintTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(tokenStore.resolve(id)).willReturn(id);
		given(token.totalSupply()).willReturn(amount);
		given(tokenStore.exists(id)).willReturn(true);
		given(tokenStore.get(id)).willReturn(token);
		given(token.isDeleted()).willReturn(false);
		given(token.tokenType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);

		given(uniqueStore.mint(any(), any())).willReturn(CreationResult.success(List.of(1L, 2L)));

	}

	private void givenInvalidUniqueTxnCtx() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(id)
						.setAmount(amount))
				.build();
		given(accessor.getTxn()).willReturn(tokenMintTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(tokenStore.resolve(id)).willReturn(id);
		given(tokenStore.get(id)).willReturn(token);
		given(token.totalSupply()).willReturn(amount);
	}

	private void givenMissingToken() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.build()
				).build();
	}

	private void givenInvalidNegativeAmount() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.setToken(id)
								.setAmount(-1)
								.build()
				).build();
	}

	private void givenInvalidZeroAmount() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.setToken(id)
								.setAmount(0)
								.build()
				).build();
	}
}
