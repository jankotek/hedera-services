package com.hedera.services.store;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.exceptions.NegativeAccountBalanceException;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.store.models.Account;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.Token;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AccountStoreTest {
	@Mock
	private OptionValidator validator;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private FCMap<MerkleEntityId, MerkleAccount> accounts;

	private AccountStore subject;

	@BeforeEach
	void setUp() {
		setupAccounts();

		subject = new AccountStore(validator, dynamicProperties, () -> accounts);
	}

	/* --- Account loading --- */
	@Test
	void failsLoadingMissingAccount() {
		assertMiscAccountLoadFailsWith(INVALID_ACCOUNT_ID);
	}

	@Test
	void failsLoadingDeleted() {
		setupWithAccount(miscMerkleId, miscMerkleAccount);
		miscMerkleAccount.setDeleted(true);

		assertMiscAccountLoadFailsWith(ACCOUNT_DELETED);
	}

	@Test
	void failsLoadingDetached() throws NegativeAccountBalanceException {
		setupWithAccount(miscMerkleId, miscMerkleAccount);
		given(validator.isAfterConsensusSecond(expiry)).willReturn(true);
		given(dynamicProperties.autoRenewEnabled()).willReturn(true);
		miscMerkleAccount.setBalance(0L);

		assertMiscAccountLoadFailsWith(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
	}

	@Test
	void canAlwaysLoadWithNonzeroBalance() {
		setupWithAccount(miscMerkleId, miscMerkleAccount);
		given(dynamicProperties.autoRenewEnabled()).willReturn(true);

		// when:
		final var actualAccount = subject.loadAccount(miscId);

		// then:
		assertEquals(miscAccount, actualAccount);
	}

	@Test
	void persistenceUpdatesTokens() {
		setupWithAccount(miscMerkleId, miscMerkleAccount);
		setupWithMutableAccount(miscMerkleId, miscMerkleAccount);
		// and:
		final var aThirdToken = new Token(new Id(0, 0, 888));
		// and:
		final var expectedReplacement = MerkleAccountFactory.newAccount()
				.balance(balance)
				.assocTokens(firstAssocTokenId, secondAssocTokenId, aThirdToken.getId())
				.expirationTime(expiry)
				.get();

		// given:
		final var model = subject.loadAccount(miscId);

		// when:
		model.associateWith(List.of(aThirdToken), Integer.MAX_VALUE);
		// and:
		subject.persistAccount(model);

		// then:
		assertEquals(expectedReplacement, miscMerkleAccount);
		verify(accounts, never()).replace(miscMerkleId, expectedReplacement);
		// and:
		assertNotSame(miscMerkleAccount.tokens().getIds(), model.getAssociatedTokens());
	}

	private void setupWithAccount(MerkleEntityId anId, MerkleAccount anAccount) {
		given(accounts.get(anId)).willReturn(anAccount);
	}

	private void setupWithMutableAccount(MerkleEntityId anId, MerkleAccount anAccount) {
		given(accounts.getForModify(anId)).willReturn(anAccount);
	}

	private void assertMiscAccountLoadFailsWith(ResponseCodeEnum status) {
		var ex = assertThrows(InvalidTransactionException.class, () -> subject.loadAccount(miscId));
		assertEquals(status, ex.getResponseCode());
	}

	private void setupAccounts() {
		miscMerkleAccount = MerkleAccountFactory.newAccount()
				.balance(balance)
				.assocTokens(firstAssocTokenId, secondAssocTokenId)
				.expirationTime(expiry)
				.get();

		miscAccount.setExpiry(expiry);
		miscAccount.initBalance(balance);
		miscAccount.setAssociatedTokens(miscMerkleAccount.tokens().getIds());
		autoRenewAccount.setExpiry(expiry);
		autoRenewAccount.initBalance(balance);
	}

	private final long expiry = 1_234_567L;
	private final long balance = 1_000L;
	private final long miscAccountNum = 1_234L;
	private final long autoRenewAccountNum = 3_234L;
	private final long firstAssocTokenNum = 666L;
	private final long secondAssocTokenNum = 777L;
	private final Id miscId = new Id(0, 0, miscAccountNum);
	private final Id autoRenewId = new Id(0, 0, autoRenewAccountNum);
	private final Id firstAssocTokenId = new Id(0, 0, firstAssocTokenNum);
	private final Id secondAssocTokenId = new Id(0, 0, secondAssocTokenNum);
	private final MerkleEntityId miscMerkleId = new MerkleEntityId(0, 0, miscAccountNum);
	private final Account miscAccount = new Account(miscId);
	private final Account autoRenewAccount = new Account(autoRenewId);

	private MerkleAccount miscMerkleAccount;
}
