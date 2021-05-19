package com.hedera.services.store.tokens.unique;

/*
 * ‌
 * Hedera Services Node
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.utils.invertible_fchashmap.FCInvertibleHashMap;
import com.hedera.test.mocks.TestContextValidator;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hedera.services.ledger.properties.AccountProperty.IS_DELETED;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_FROZEN;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_KYC_GRANTED;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class UniqueTokenStoreTest {

	UniqueTokenStore store;

	EntityIdSource ids;
	GlobalDynamicProperties properties;

	FCMap<MerkleEntityId, MerkleToken> tokens;
	FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier> nfTokens;

	HederaLedger hederaLedger;
	TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger;
	TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger;

	MerkleUniqueTokenId nftId;
	MerkleUniqueToken nft;
	MerkleToken token;
	EntityId eId;
	TokenID tokenID = IdUtils.asToken("1.2.3");
	AccountID autoRenewAccount = IdUtils.asAccount("1.2.5");
	AccountID newAutoRenewAccount = IdUtils.asAccount("1.2.6");
	AccountID treasury = IdUtils.asAccount("1.2.3");
	AccountID sponsor = IdUtils.asAccount("1.2.666");
	Pair<AccountID, TokenID> sponsorMisc = asTokenRel(sponsor, tokenID);
	Pair<AccountID, TokenID> treasuryMisc = asTokenRel(treasury, tokenID);
	long treasuryBalance = 50_000, sponsorBalance = 1_000;


	@BeforeEach
	void setUp() {
		eId = mock(EntityId.class);
		nftId = mock(MerkleUniqueTokenId.class);//new MerkleUniqueTokenId(eId, 1);
		nft = mock(MerkleUniqueToken.class); //new MerkleUniqueToken(eId, "memo", RichInstant.fromJava(Instant.now()));
		token = mock(MerkleToken.class);

		properties = mock(GlobalDynamicProperties.class);

		ids = mock(EntityIdSource.class);

		hederaLedger = mock(HederaLedger.class);

		tokens = (FCMap<MerkleEntityId, MerkleToken>) mock(FCMap.class);
		given(tokens.containsKey(MerkleEntityId.fromTokenId(tokenID))).willReturn(true);
		given(tokens.getForModify(any())).willReturn(token);

		nfTokens =  mock(FCInvertibleHashMap.class);
		given(nfTokens.containsKey(nftId)).willReturn(true);
		given(nfTokens.get(nftId)).willReturn(nft);
		given(nfTokens.inverseGet(any())).willReturn(Collections.singletonList(nftId).iterator());
		given(nfTokens.inverseGet(any(), anyInt())).willReturn(Collections.singletonList(nftId).iterator());
		given(nfTokens.inverseGet(any(), anyInt(), anyInt())).willReturn(Collections.singletonList(nftId).iterator());

		accountsLedger = (TransactionalLedger<AccountID, AccountProperty, MerkleAccount>) mock(TransactionalLedger.class);
		given(accountsLedger.exists(treasury)).willReturn(true);
		given(accountsLedger.exists(autoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(newAutoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(accountsLedger.get(treasury, IS_DELETED)).willReturn(false);

		tokenRelsLedger = mock(TransactionalLedger.class);
		given(tokenRelsLedger.exists(sponsorMisc)).willReturn(true);
		given(tokenRelsLedger.get(sponsorMisc, TOKEN_BALANCE)).willReturn(sponsorBalance);
		given(tokenRelsLedger.get(sponsorMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(sponsorMisc, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.exists(treasuryMisc)).willReturn(true);
		given(tokenRelsLedger.get(treasuryMisc, TOKEN_BALANCE)).willReturn(treasuryBalance);
		given(tokenRelsLedger.get(treasuryMisc, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(treasuryMisc, IS_KYC_GRANTED)).willReturn(true);

		store = new UniqueTokenStore(ids, TestContextValidator.TEST_VALIDATOR, properties, ()->tokens,()-> nfTokens, tokenRelsLedger);
	}

	@Test
	void verifyInject(){
		assertNotNull(hederaLedger);
		assertNotNull(tokenRelsLedger);
	}

	@Test
	void mint() {
		store.setHederaLedger(hederaLedger);
		given(store.exists(tokenID)).willReturn(true);
		given(tokens.get(MerkleEntityId.fromTokenId(tokenID))).willReturn(token);
		given(token.isDeleted()).willReturn(false);
		given(token.hasSupplyKey()).willReturn(true);
		given(token.treasury()).willReturn(EntityId.fromGrpcAccountId(sponsor));
		given(token.totalSupply()).willReturn(2000L);


		var res = store.mint(tokenID, "memo", RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
	}

	@Test
	void getUnique() {
		given(nfTokens.get(new MerkleUniqueTokenId(eId, 0))).willReturn(nft);
//		nfTokens.put(nftId, nft);
		var res = store.getUnique(eId, 0);
		assertEquals(nft, res);
		res = store.getUnique(eId, 1);
		assertNull(res);
	}

	@Test
	void getByToken() {
		var res = store.getByToken(nft);
		assertNotNull(res);
		res.forEachRemaining(Assertions::assertNotNull);
	}

	@Test
	void getByTokenFromIdx() {
		given(nfTokens.size()).willReturn(10);
		var res = store.getByTokenFromIdx(nft, 5);
		assertNotNull(res);
	}

	@Test
	void getByTokenFromIdxToIdx() {
	}

	@Test
	void getByAccountFromIdxToIdx() {
		given(nfTokens.size()).willReturn(10);
		var res = store.getByAccountFromIdxToIdx(AccountID.getDefaultInstance(), 0, 0);
		assertNotNull(res);
		assertThrows(IllegalArgumentException.class, ()->{
			store.getByAccountFromIdxToIdx(AccountID.getDefaultInstance(), -1, -1);
		});
	}

	@Test
	void burn() {
	}

	@Test
	void wipe() {
	}

	@Test
	void dissociate() {
	}

	@Test
	void adjustBalance() {
	}
}