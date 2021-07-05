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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.store.models.TokenRelationship;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenAssociateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TokenAssociateTransitionLogicTest {
	private AccountID account = IdUtils.asAccount("0.0.2");
	private TokenID firstToken = IdUtils.asToken("1.2.3");
	private TokenID secondToken = IdUtils.asToken("2.3.4");
	private Id accountId = new Id(0, 0, 2);
	private Id firstTokenId = new Id(1, 2, 3);
	private Id secondTokenId = new Id(2, 3, 4);

	@Mock
	private Account modelAccount;
	@Mock
	private Token firstModelToken;
	@Mock
	private Token secondModelToken;
	@Mock
	private TokenRelationship firstModelTokenRel;
	@Mock
	private TokenRelationship secondModelTokenRel;
	@Mock
	private TypedTokenStore tokenStore;
	@Mock
	private AccountStore accountStore;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private PlatformTxnAccessor accessor;
	@Mock
	private GlobalDynamicProperties dynamicProperties;

	private TransactionBody tokenAssociateTxn;
	private TokenAssociateTransitionLogic subject;

	@BeforeEach
	private void setup() {
		subject = new TokenAssociateTransitionLogic(accountStore, tokenStore, txnCtx, dynamicProperties);
	}

	@Test
	void appliesExpectedTransition() {
		// setup:
		InOrder inOrder = Mockito.inOrder(modelAccount, accountStore, tokenStore);

		givenValidTxnCtx();
		given(accessor.getTxn()).willReturn(tokenAssociateTxn);
		given(txnCtx.accessor()).willReturn(accessor);
		given(accountStore.loadAccount(accountId)).willReturn(modelAccount);
		given(tokenStore.loadToken(firstTokenId)).willReturn(firstModelToken);
		given(tokenStore.loadToken(secondTokenId)).willReturn(secondModelToken);
		given(firstModelToken.newRelationshipWith(modelAccount)).willReturn(firstModelTokenRel);
		given(secondModelToken.newRelationshipWith(modelAccount)).willReturn(secondModelTokenRel);
		given(dynamicProperties.maxTokensPerAccount()).willReturn(123);
		// and:
		List<Token> tokens = List.of(firstModelToken, secondModelToken);

		// when:
		subject.doStateTransition();

		// then:
		inOrder.verify(modelAccount).associateWith(tokens, 123);
		inOrder.verify(accountStore).persistAccount(modelAccount);
		inOrder.verify(tokenStore).persistTokenRelationship(firstModelTokenRel);
		inOrder.verify(tokenStore).persistTokenRelationship(secondModelTokenRel);
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(tokenAssociateTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void acceptsValidTxn() {
		givenValidTxnCtx();

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(tokenAssociateTxn));
	}

	@Test
	void rejectsMissingAccount() {
		givenMissingAccount();

		// expect:
		assertEquals(INVALID_ACCOUNT_ID, subject.semanticCheck().apply(tokenAssociateTxn));
	}

	@Test
	void rejectsDuplicateTokens() {
		givenDuplicateTokens();

		// expect:
		assertEquals(TOKEN_ID_REPEATED_IN_TOKEN_LIST, subject.semanticCheck().apply(tokenAssociateTxn));
	}

	private void givenValidTxnCtx() {
		tokenAssociateTxn = TransactionBody.newBuilder()
				.setTokenAssociate(TokenAssociateTransactionBody.newBuilder()
						.setAccount(account)
						.addAllTokens(List.of(firstToken, secondToken)))
				.build();
	}

	private void givenMissingAccount() {
		tokenAssociateTxn = TransactionBody.newBuilder()
				.setTokenAssociate(TokenAssociateTransactionBody.newBuilder())
				.build();
	}

	private void givenDuplicateTokens() {
		tokenAssociateTxn = TransactionBody.newBuilder()
				.setTokenAssociate(TokenAssociateTransactionBody.newBuilder()
						.setAccount(account)
						.addTokens(firstToken)
						.addTokens(firstToken))
				.build();
	}
}
