package com.hedera.services.state.expiry.renewal;

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

import com.hedera.services.config.HederaNumbers;
import com.hedera.services.config.MockGlobalDynamicProps;
import com.hedera.services.config.MockHederaNumbers;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleAccountTokens;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.swirlds.fcmap.FCMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static com.hedera.services.state.expiry.renewal.ExpiredEntityClassification.DETACHED_ACCOUNT;
import static com.hedera.services.state.expiry.renewal.ExpiredEntityClassification.DETACHED_ACCOUNT_GRACE_PERIOD_OVER;
import static com.hedera.services.state.expiry.renewal.ExpiredEntityClassification.DETACHED_TREASURY_GRACE_PERIOD_OVER_BEFORE_TOKEN;
import static com.hedera.services.state.expiry.renewal.ExpiredEntityClassification.EXPIRED_ACCOUNT_READY_TO_RENEW;
import static com.hedera.services.state.expiry.renewal.ExpiredEntityClassification.OTHER;
import static com.hedera.services.state.expiry.renewal.RenewalRecordsHelperTest.adjustmentsFrom;
import static com.hedera.services.state.expiry.renewal.RenewalRecordsHelperTest.tokensFrom;
import static com.hedera.services.state.merkle.MerkleEntityAssociation.fromAccountTokenRel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RenewalHelperTest {
	private final long tokenBalance = 1_234L;
	private final long now = 1_234_567L;
	private final long nonZeroBalance = 1L;
	private final MockGlobalDynamicProps dynamicProps = new MockGlobalDynamicProps();

	private final MerkleAccount nonExpiredAccount = MerkleAccountFactory.newAccount()
			.balance(0).expirationTime(now + 1)
			.get();
	private final MerkleAccount expiredAccountZeroBalance = MerkleAccountFactory.newAccount()
			.balance(0).expirationTime(now - 1)
			.get();
	private final MerkleAccount expiredDeletedAccount = MerkleAccountFactory.newAccount()
			.balance(0)
			.deleted(true)
			.expirationTime(now - 1)
			.get();
	private final MerkleAccount expiredAccountNonZeroBalance = MerkleAccountFactory.newAccount()
			.balance(nonZeroBalance).expirationTime(now - 1)
			.get();
	private final MerkleAccount fundingAccount = MerkleAccountFactory.newAccount()
			.balance(0)
			.get();
	private final MerkleAccount contractAccount = MerkleAccountFactory.newAccount()
			.isSmartContract(true)
			.balance(0).expirationTime(now - 1)
			.get();
	private final long nonExpiredAccountNum = 1L, brokeExpiredAccountNum = 2L, fundedExpiredAccountNum = 3L;
	private final EntityId expiredTreasuryId = new EntityId(0, 0, brokeExpiredAccountNum);
	private final EntityId treasuryId = new EntityId(0, 0, 666L);
	private final AccountID treasuryGrpcId = treasuryId.toGrpcAccountId();
	private final MerkleToken deletedToken = new MerkleToken(
			Long.MAX_VALUE, 1L, 0,
			"GONE", "Long lost dream",
			true, true, expiredTreasuryId);
	private final MerkleToken longLivedToken = new MerkleToken(
			Long.MAX_VALUE, 1L, 0,
			"HERE", "Dreams never die",
			true, true, treasuryId);
	private final long deletedTokenNum = 1234L, survivedTokenNum = 4321L;
	private final MerkleEntityId deletedTokenId = new MerkleEntityId(0, 0, deletedTokenNum);
	private final MerkleEntityId survivedTokenId = new MerkleEntityId(0, 0, survivedTokenNum);
	private final TokenID deletedTokenGrpcId = deletedTokenId.toTokenId();
	private final TokenID survivedTokenGrpcId = survivedTokenId.toTokenId();
	private final TokenID missingTokenGrpcId = TokenID.newBuilder().setTokenNum(5678L).build();

	private final HederaNumbers nums = new MockHederaNumbers();

	{
		deletedToken.setDeleted(true);
		final var associations = new MerkleAccountTokens();
		associations.associateAll(Set.of(deletedTokenGrpcId, survivedTokenGrpcId, missingTokenGrpcId));
		expiredAccountZeroBalance.setTokens(associations);
	}

	@Mock
	private FCMap<MerkleEntityId, MerkleToken> tokens;
	@Mock
	private FCMap<MerkleEntityId, MerkleAccount> accounts;
	@Mock
	private FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenRels;
	@Mock
	private TokenStore tokenStore;

	private RenewalHelper subject;

	@BeforeEach
	void setUp() {
		subject = new RenewalHelper(tokenStore, nums, dynamicProps, () -> tokens, () -> accounts, () -> tokenRels);
	}

	@Test
	void classifiesNonAccount() {
		// expect:
		assertEquals(OTHER, subject.classify(4L, now));
	}

	@Test
	void classifiesNonExpiredAccount() {
		givenPresent(nonExpiredAccountNum, nonExpiredAccount);

		// expect:
		assertEquals(OTHER, subject.classify(nonExpiredAccountNum, now));
	}

	@Test
	void classifiesContractAccount() {
		givenPresent(nonExpiredAccountNum, contractAccount);

		// expect:
		assertEquals(OTHER, subject.classify(nonExpiredAccountNum, now));
	}

	@Test
	void classifiesDeletedAccountAfterExpiration() {
		givenPresent(brokeExpiredAccountNum, expiredDeletedAccount);

		// expect:
		assertEquals(
				DETACHED_ACCOUNT_GRACE_PERIOD_OVER,
				subject.classify(brokeExpiredAccountNum, now));
	}

	@Test
	void classifiesDetachedAccountAfterGracePeriod() {
		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);

		// expect:
		assertEquals(
				DETACHED_ACCOUNT_GRACE_PERIOD_OVER,
				subject.classify(brokeExpiredAccountNum, now + dynamicProps.autoRenewGracePeriod()));
	}

	@Test
	void classifiesDetachedAccountAfterGracePeriodAsOtherIfTokenNotYetRemoved() {
		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);
		given(tokenStore.isKnownTreasury(grpcIdWith(brokeExpiredAccountNum))).willReturn(true);

		// expect:
		assertEquals(
				DETACHED_TREASURY_GRACE_PERIOD_OVER_BEFORE_TOKEN,
				subject.classify(brokeExpiredAccountNum, now + dynamicProps.autoRenewGracePeriod()));
	}

	@Test
	void classifiesDetachedAccount() {
		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);

		// expect:
		assertEquals(
				DETACHED_ACCOUNT,
				subject.classify(brokeExpiredAccountNum, now));
	}

	@Test
	void classifiesFundedExpiredAccount() {
		givenPresent(fundedExpiredAccountNum, expiredAccountNonZeroBalance);

		// expect:
		assertEquals(EXPIRED_ACCOUNT_READY_TO_RENEW, subject.classify(fundedExpiredAccountNum, now));
		// and:
		assertEquals(expiredAccountNonZeroBalance, subject.getLastClassifiedAccount());
	}

	@Test
	void throwsOnRemovingIfLastClassifiedHadNonzeroBalance() {
		givenPresent(fundedExpiredAccountNum, expiredAccountNonZeroBalance);

		// when:
		subject.classify(fundedExpiredAccountNum, now);

		// expect:
		assertThrows(IllegalStateException.class, () -> subject.removeLastClassifiedAccount());
	}

	@Test
	void throwsOnRemovingIfNoLastClassified() {
		// expect:
		assertThrows(IllegalStateException.class, () -> subject.removeLastClassifiedAccount());
	}

	@Test
	void shortCircuitsToJustRemovingRelIfZeroBalance() {
		// setup:
		final var expiredKey = new MerkleEntityId(0, 0, brokeExpiredAccountNum);

		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);
		givenTokenPresent(deletedTokenId, deletedToken);
		givenTokenPresent(survivedTokenId, longLivedToken);
		givenRelPresent(expiredKey, deletedTokenId, 0);
		givenRelPresent(expiredKey, survivedTokenId, 0);
		givenRelPresent(expiredKey, MerkleEntityId.fromTokenId(missingTokenGrpcId), 0);

		// when:
		subject.classify(brokeExpiredAccountNum, now);
		// and:
		var displacedTokens = subject.removeLastClassifiedAccount();

		// then:
		verify(accounts).remove(expiredKey);
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), deletedTokenGrpcId));
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), survivedTokenGrpcId));
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), missingTokenGrpcId));
		// and:
		assertTrue(displacedTokens.getLeft().isEmpty());
	}

	@Test
	void removesLastClassifiedIfAppropriate() {
		// setup:
		final var expiredKey = new MerkleEntityId(0, 0, brokeExpiredAccountNum);

		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);
		givenTokenPresent(deletedTokenId, deletedToken);
		givenTokenPresent(survivedTokenId, longLivedToken);
		givenRelPresent(expiredKey, deletedTokenId, Long.MAX_VALUE);
		givenRelPresent(expiredKey, survivedTokenId, tokenBalance);
		givenRelPresent(expiredKey, MerkleEntityId.fromTokenId(missingTokenGrpcId), 0);
		givenModifiableRelPresent(MerkleEntityId.fromAccountId(treasuryGrpcId), survivedTokenId, 0L);

		// when:
		subject.classify(brokeExpiredAccountNum, now);
		// and:
		var displacedTokens = subject.removeLastClassifiedAccount();

		// then:
		verify(accounts).remove(expiredKey);
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), deletedTokenGrpcId));
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), survivedTokenGrpcId));
		verify(tokenRels).remove(fromAccountTokenRel(grpcIdWith(brokeExpiredAccountNum), survivedTokenGrpcId));
		// and:
		final var ttls = List.of(
				ttlOf(survivedTokenGrpcId, grpcIdWith(brokeExpiredAccountNum), treasuryGrpcId, tokenBalance));
		assertEquals(tokensFrom(ttls), displacedTokens.getLeft());
		assertEquals(adjustmentsFrom(ttls), displacedTokens.getRight());
	}

	@Test
	void renewsLastClassifiedAsRequested() {
		// setup:
		var key = new MerkleEntityId(0, 0, fundedExpiredAccountNum);
		var fundingKey = new MerkleEntityId(0, 0, 98);

		givenPresent(fundedExpiredAccountNum, expiredAccountNonZeroBalance, true);
		givenPresent(98, fundingAccount, true);

		// when:
		subject.classify(fundedExpiredAccountNum, now);
		// and:
		subject.renewLastClassifiedWith(nonZeroBalance, 3600L);

		// then:
		verify(accounts).getForModify(key);
		verify(accounts).getForModify(fundingKey);
	}

	@Test
	void cannotRenewIfNoLastClassified() {
		// expect:
		assertThrows(IllegalStateException.class,
				() -> subject.renewLastClassifiedWith(nonZeroBalance, 3600L));
	}

	@Test
	void rejectsAsIseIfFeeIsUnaffordable() {
		givenPresent(brokeExpiredAccountNum, expiredAccountZeroBalance);

		// when:
		subject.classify(brokeExpiredAccountNum, now);
		// expect:
		assertThrows(IllegalStateException.class,
				() -> subject.renewLastClassifiedWith(nonZeroBalance, 3600L));
	}

	private MerkleEntityAssociation assoc(MerkleEntityId a, MerkleEntityId b) {
		return fromAccountTokenRel(a.toAccountId(), b.toTokenId());
	}

	private AccountID grpcIdWith(long num) {
		return AccountID.newBuilder().setAccountNum(num).build();
	}

	private void givenPresent(long num, MerkleAccount account) {
		givenPresent(num, account,false);
	}

	private void givenTokenPresent(MerkleEntityId id, MerkleToken token) {
		given(tokens.containsKey(id)).willReturn(true);
		given(tokens.get(id)).willReturn(token);
	}

	private void givenRelPresent(MerkleEntityId account, MerkleEntityId token, long balance) {
		var rel = assoc(account, token);
		given(tokenRels.get(rel)).willReturn(new MerkleTokenRelStatus(balance, false, false));
	}

	private void givenModifiableRelPresent(MerkleEntityId account, MerkleEntityId token, long balance) {
		var rel = assoc(account, token);
		given(tokenRels.getForModify(rel)).willReturn(new MerkleTokenRelStatus(balance, false, false));
	}

	private void givenPresent(long num, MerkleAccount account, boolean modifiable) {
		var key = new MerkleEntityId(0, 0, num);
		if (num != 98) {
			given(accounts.containsKey(key)).willReturn(true);
			given(accounts.get(key)).willReturn(account);
		}
		if (modifiable) {
			given(accounts.getForModify(key)).willReturn(account);
		}
	}

	static TokenTransferList ttlOf(TokenID scope, AccountID src, AccountID dest, long amount) {
		return TokenTransferList.newBuilder()
				.setToken(scope)
				.addTransfers(aaOf(src, -amount))
				.addTransfers(aaOf(dest, +amount))
				.build();
	}

	static AccountAmount aaOf(AccountID id, long amount) {
		return AccountAmount.newBuilder()
				.setAccountID(id)
				.setAmount(amount)
				.build();
	}
}
