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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
	Pair<AccountID, TokenID> sponsorPair = asTokenRel(sponsor, tokenID);
	Pair<AccountID, TokenID> treasuryPair = asTokenRel(treasury, tokenID);
	long treasuryBalance = 50_000, sponsorBalance = 1_000;


	@BeforeEach
	void setUp() {
		eId = mock(EntityId.class);
		nftId = mock(MerkleUniqueTokenId.class);
		nft = mock(MerkleUniqueToken.class);
		token = mock(MerkleToken.class);
		given(token.isDeleted()).willReturn(false);
		given(token.treasury()).willReturn(EntityId.fromGrpcAccountId(sponsor));
		given(token.totalSupply()).willReturn(2000L);
		given(token.hasSupplyKey()).willReturn(true);

		properties = mock(GlobalDynamicProperties.class);

		ids = mock(EntityIdSource.class);

		hederaLedger = mock(HederaLedger.class);

		tokens = (FCMap<MerkleEntityId, MerkleToken>) mock(FCMap.class);
		given(tokens.containsKey(MerkleEntityId.fromTokenId(tokenID))).willReturn(true);
		given(tokens.get(MerkleEntityId.fromTokenId(tokenID))).willReturn(token);
		given(tokens.getForModify(any())).willReturn(token);

		nfTokens = mock(FCInvertibleHashMap.class);
		given(nfTokens.containsKey(nftId)).willReturn(true);
		given(nfTokens.get(nftId)).willReturn(nft);
		given(nfTokens.inverseGet(any())).willReturn(Collections.singletonList(nftId).iterator());
		given(nfTokens.inverseGet(any(), anyInt())).willReturn(Collections.singletonList(nftId).iterator());
		given(nfTokens.inverseGet(any(), anyInt(), anyInt())).willReturn(Collections.singletonList(nftId).iterator());
		given(nfTokens.size()).willReturn(10);


		accountsLedger = (TransactionalLedger<AccountID, AccountProperty, MerkleAccount>) mock(TransactionalLedger.class);
		given(accountsLedger.exists(treasury)).willReturn(true);
		given(accountsLedger.exists(autoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(newAutoRenewAccount)).willReturn(true);
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(accountsLedger.get(treasury, IS_DELETED)).willReturn(false);

		tokenRelsLedger = mock(TransactionalLedger.class);
		given(tokenRelsLedger.exists(sponsorPair)).willReturn(true);
		given(tokenRelsLedger.exists(treasuryPair)).willReturn(true);
		given(tokenRelsLedger.get(sponsorPair, TOKEN_BALANCE)).willReturn(sponsorBalance);
		given(tokenRelsLedger.get(sponsorPair, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(sponsorPair, IS_KYC_GRANTED)).willReturn(true);
		given(tokenRelsLedger.get(treasuryPair, TOKEN_BALANCE)).willReturn(treasuryBalance);
		given(tokenRelsLedger.get(treasuryPair, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(treasuryPair, IS_KYC_GRANTED)).willReturn(true);

		store = new UniqueTokenStore(ids, TestContextValidator.TEST_VALIDATOR, properties, () -> tokens, () -> nfTokens, tokenRelsLedger);
		store.setHederaLedger(hederaLedger);
		store.setAccountsLedger(accountsLedger);
		given(store.get(tokenID)).willReturn(token);

	}

	@Test
	void mint() {
		var res = store.mint(tokenID, "memo", RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
		verify(token).incrementSerialNum();
	}

	@Test
	void wipe() {
		var res = store.wipe(treasury, tokenID, 1, true);
		assertNull(res);
	}

	@Test
	void resolve() {
		var res = store.resolve(tokenID);
		assertEquals(tokenID, res);
	}
}