package com.hedera.services.store.tokens.unique;

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.ByteString;
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
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hedera.services.ledger.properties.AccountProperty.IS_DELETED;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_FROZEN;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_KYC_GRANTED;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
	AccountID treasury = IdUtils.asAccount("1.2.3");
	AccountID sponsor = IdUtils.asAccount("1.2.666");
	Pair<AccountID, TokenID> sponsorPair = asTokenRel(sponsor, tokenID);
	long sponsorBalance = 1_000;


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


		accountsLedger = (TransactionalLedger<AccountID, AccountProperty, MerkleAccount>) mock(TransactionalLedger.class);
		given(accountsLedger.exists(sponsor)).willReturn(true);
		given(accountsLedger.get(treasury, IS_DELETED)).willReturn(false);

		tokenRelsLedger = mock(TransactionalLedger.class);
		given(tokenRelsLedger.get(sponsorPair, TOKEN_BALANCE)).willReturn(sponsorBalance);
		given(tokenRelsLedger.get(sponsorPair, IS_FROZEN)).willReturn(false);
		given(tokenRelsLedger.get(sponsorPair, IS_KYC_GRANTED)).willReturn(true);

		store = new UniqueTokenStore(ids, TestContextValidator.TEST_VALIDATOR, properties, () -> tokens, () -> nfTokens, tokenRelsLedger);
		store.setHederaLedger(hederaLedger);
		store.setAccountsLedger(accountsLedger);

	}

	@Test
	void revertWorks() {
		var res = store.mintProvisional(singleTokenTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(res, ResponseCodeEnum.OK);
		verify(token, times(0)).incrementSerialNum();
		verify(nfTokens, times(0)).put(any(), any());

		given(token.hasSupplyKey()).willReturn(false);

		res = store.commitProvisional();
		verify(token, times(0)).incrementSerialNum();
		verify(nfTokens, times(0)).put(any(), any());
		assertNotEquals(res, ResponseCodeEnum.OK);
	}

	@Test
	void mintOne() {
		var res = store.mint(singleTokenTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
		verify(token, times(1)).incrementSerialNum();
	}

	@Test
	void mintWithSeparateOperations() {
		var res = store.mintProvisional(singleTokenTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
		res = store.commitProvisional();
		assertEquals(ResponseCodeEnum.OK, res);
	}

	@Test
	void mintMany() {
		var res = store.mint(multipleTokenTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
		verify(token, times(2)).incrementSerialNum();
	}

	@Test
	void mintManyFail() {
		var res = store.mint(multipleTokenFailTxBody(), RichInstant.fromJava(Instant.now()));
		assertNotEquals(res, ResponseCodeEnum.OK);
		verify(token, times(0)).incrementSerialNum();
		verify(nfTokens, times(0)).put(any(), any());
	}

	@Test
	void mintManyWithSeparateOps() {
		var res = store.mintProvisional(multipleTokenFailTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.OK, res);
		res = store.commitProvisional();
		assertEquals(ResponseCodeEnum.INVALID_TRANSACTION_BODY, res);
		verify(token, times(0)).incrementSerialNum();
	}

	@Test
	void mintFailsIfNoSupplyKey() {
		given(token.hasSupplyKey()).willReturn(false);
		var res = store.mint(singleTokenTxBody(), RichInstant.fromJava(Instant.now()));
		assertEquals(ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY, res);
		verify(token, times(0)).incrementSerialNum();
	}

	@Test
	void wipe() {
		var res = store.wipe(treasury, tokenID, 1, true);
		assertNull(res);
	}

	private TokenMintTransactionBody singleTokenTxBody() {
		return TokenMintTransactionBody.newBuilder()
				.addMetadata(ByteString.copyFromUtf8("memo"))
				.setAmount(123)
				.setToken(tokenID)
				.build();
	}

	private TokenMintTransactionBody multipleTokenTxBody() {
		return TokenMintTransactionBody.newBuilder()
				.setToken(tokenID)
				.setAmount(123)
				.addMetadata(ByteString.copyFromUtf8("memo1"))
				.addMetadata(ByteString.copyFromUtf8("memo2"))
				.build();
	}

	private TokenMintTransactionBody multipleTokenFailTxBody() {
		return TokenMintTransactionBody.newBuilder()
				.setToken(tokenID)
				.setAmount(123)
				.addMetadata(ByteString.copyFromUtf8("memo1"))
				.addMetadata(ByteString.copyFromUtf8("memo1"))
				.build();
	}


}