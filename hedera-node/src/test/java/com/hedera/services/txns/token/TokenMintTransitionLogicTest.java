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
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.OwnershipTracker;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_MINT_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TRANSACTION_BODY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.METADATA_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;

@ExtendWith(MockitoExtension.class)
class TokenMintTransitionLogicTest {
	private final long amount = 123L;
	private final TokenID grpcId = IdUtils.asToken("1.2.3");
	private final Id id = new Id(1, 2, 3);
	private final Id treasuryId = new Id(2, 4, 6);
	private final Account treasury = new Account(treasuryId);

	@Mock
	private Token token;
	@Mock
	private TypedTokenStore store;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private PlatformTxnAccessor accessor;
	@Mock
	private OptionValidator validator;
	@Mock
	private AccountStore accountStore;

	private TokenRelationship treasuryRel;
	private TransactionBody tokenMintTxn;

	private TokenMintTransitionLogic subject;

	@BeforeEach
	private void setup() {
		subject = new TokenMintTransitionLogic(validator, accountStore, store, txnCtx);
	}

	@Test
	void followsHappyPath() {
		// setup:
		treasuryRel = new TokenRelationship(token, treasury);

		givenValidTxnCtx();
		given(accessor.getTxn()).willReturn(tokenMintTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(store.loadToken(id)).willReturn(token);
		given(token.getTreasury()).willReturn(treasury);
		given(store.loadTokenRelationship(token, treasury)).willReturn(treasuryRel);
		given(token.getType()).willReturn(TokenType.FUNGIBLE_COMMON);
		// when:
		subject.doStateTransition();

		// then:
		verify(token).mint(treasuryRel, amount);
		verify(store).persistToken(token);
		verify(store).persistTokenRelationship(treasuryRel);
	}

	@Test
	void followsUniqueHappyPath() {
		// setup:
		treasuryRel = new TokenRelationship(token, treasury);

		givenValidUniqueTxnCtx();
		given(accessor.getTxn()).willReturn(tokenMintTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(store.loadToken(id)).willReturn(token);
		given(token.getTreasury()).willReturn(treasury);
		given(store.loadTokenRelationship(token, treasury)).willReturn(treasuryRel);
		given(token.getType()).willReturn(TokenType.NON_FUNGIBLE_UNIQUE);
		given(txnCtx.consensusTime()).willReturn(Instant.now());
		// when:
		subject.doStateTransition();

		// then:
		verify(token).mint(any(OwnershipTracker.class), eq(treasuryRel), any(List.class), any(RichInstant.class));
		verify(store).persistToken(token);
		verify(store).persistTokenRelationship(treasuryRel);
		verify(store).persistTrackers(any(OwnershipTracker.class));
		verify(accountStore).persistAccount(any(Account.class));
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(tokenMintTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void acceptsValidTxn() {
		givenValidTxnCtx();

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void rejectsMissingToken() {
		givenMissingToken();

		// expect:
		assertEquals(INVALID_TOKEN_ID, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void rejectsInvalidNegativeAmount() {
		givenInvalidNegativeAmount();

		// expect:
		assertEquals(INVALID_TOKEN_MINT_AMOUNT, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void rejectsInvalidZeroAmount() {
		givenInvalidZeroAmount();

		// expect:
		assertEquals(INVALID_TOKEN_MINT_AMOUNT, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void rejectsInvalidTxnBody(){
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(grpcId)
						.setAmount(amount)
						.addAllMetadata(List.of(ByteString.copyFromUtf8("memo"))))
				.build();

		assertEquals(INVALID_TRANSACTION_BODY, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void rejectsInvalidTxnBodyWithNoProps(){
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.setToken(grpcId))
				.build();


		assertEquals(INVALID_TOKEN_MINT_AMOUNT, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void propagatesErrorOnBadMetadata(){
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.addAllMetadata(List.of(ByteString.copyFromUtf8(""), ByteString.EMPTY))
								.setToken(grpcId))
				.build();
		given(validator.maxBatchSizeMintCheck(tokenMintTxn.getTokenMint().getMetadataCount())).willReturn(OK);
		given(validator.nftMetadataCheck(any())).willReturn(METADATA_TOO_LONG);
		assertEquals(METADATA_TOO_LONG, subject.semanticCheck().apply(tokenMintTxn));
	}

	@Test
	void propagatesErrorOnMaxBatchSizeReached() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.addAllMetadata(List.of(ByteString.copyFromUtf8("")))
								.setToken(grpcId))
				.build();

		given(validator.maxBatchSizeMintCheck(tokenMintTxn.getTokenMint().getMetadataCount())).willReturn(BATCH_SIZE_LIMIT_EXCEEDED);
		assertEquals(BATCH_SIZE_LIMIT_EXCEEDED, subject.semanticCheck().apply(tokenMintTxn));
	}

	private void givenValidTxnCtx() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(grpcId)
						.setAmount(amount))
				.build();
	}

	private void givenValidUniqueTxnCtx() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(TokenMintTransactionBody.newBuilder()
						.setToken(grpcId)
						.addAllMetadata(List.of(ByteString.copyFromUtf8("memo"))))
				.build();
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
								.setToken(grpcId)
								.setAmount(-1)
								.build()
				).build();
	}

	private void givenInvalidZeroAmount() {
		tokenMintTxn = TransactionBody.newBuilder()
				.setTokenMint(
						TokenMintTransactionBody.newBuilder()
								.setToken(grpcId)
								.setAmount(0)
								.build()
				).build();
	}
}
