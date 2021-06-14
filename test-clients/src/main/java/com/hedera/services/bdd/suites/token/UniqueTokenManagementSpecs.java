package com.hedera.services.bdd.suites.token;

/*-
 * ‌
 * Hedera Services Test Clients
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
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.queries.crypto.ExpectedTokenRel.relationshipWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

public class UniqueTokenManagementSpecs extends HapiApiSuite {
	private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(UniqueTokenManagementSpecs.class);
	private static final String SUPPLY_KEY = "supplyKey";
	private static final String NFT = "nft";
	private static final String FUNGIBLE_TOKEN = "fungible";

	public static void main(String... args) {
		new UniqueTokenManagementSpecs().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[]{
						getTokenNftInfoWorks(),
						uniqueTokenHappyPath(),
						tokenMintWorksWhenAccountsAreFrozenByDefault(),
						failsWithDeletedToken(),
						happyPathWithRepeatedMetadata(),
						failsGetTokenNftInfoWithNoNft(),
						distinguishesFeeSubTypes(),
				}
		);
	}

	private HapiApiSpec distinguishesFeeSubTypes() {
		return defaultHapiSpec("happyPathFiveMintOneMetadata")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY),
						cryptoCreate("customPayer"),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY),
						tokenCreate(FUNGIBLE_TOKEN)
								.tokenType(TokenType.FUNGIBLE_COMMON)
								.supplyType(TokenSupplyType.FINITE)
								.initialSupply(10)
								.maxSupply(1100)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))).payingWith("customPayer").signedBy("customPayer", "supplyKey").via("mintNFT"),
						mintToken(FUNGIBLE_TOKEN, 100L).payingWith("customPayer").signedBy("customPayer", "supplyKey").via("mintFungible")
				).then(
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("mintNFT");
							var mintFungible = getTxnRecord("mintFungible");
							allRunFor(spec, mintNFT, mintFungible);
							var nftFee = mintNFT.getResponseRecord().getTransactionFee();
							var fungibleFee = mintFungible.getResponseRecord().getTransactionFee();
							Assert.assertNotEquals(
									"NFT Fee should NOT equal to the Fungible Fee!",
									nftFee,
									fungibleFee);
						})
				);
	}

	private HapiApiSpec uniqueTokenHappyPath() {
		return defaultHapiSpec("UniqueTokenHappyPath")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyKey(SUPPLY_KEY)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo1"))).via("mintTxn")
				).then(

						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasMetadata(ByteString.copyFromUtf8("memo1"))
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 3)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),

						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(NFT, 2),

						getTokenInfo(NFT)
								.hasTreasury(TOKEN_TREASURY)
								.hasTotalSupply(2),

						getAccountInfo(TOKEN_TREASURY)
								.hasToken(relationshipWith(NFT))
				);
	}

	private HapiApiSpec tokenMintWorksWhenAccountsAreFrozenByDefault() {
		return defaultHapiSpec("happyPathWithFrozenToken")
				.given(
						newKeyNamed(SUPPLY_KEY),
						newKeyNamed("tokenFreezeKey"),
						cryptoCreate(TOKEN_TREASURY).balance(0L),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyKey(SUPPLY_KEY)
								.freezeKey("tokenFreezeKey")
								.freezeDefault(true)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo")))
								.via("mintTxn")
				).then(
						getTokenNftInfo(NFT, 1)
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasValidCreationTime()
				);
	}

	private HapiApiSpec failsWithDeletedToken() {
		return defaultHapiSpec("failsWithDeletedToken").given(
				newKeyNamed(SUPPLY_KEY),
				newKeyNamed("adminKey"),
				cryptoCreate(TOKEN_TREASURY),
				tokenCreate(NFT)
						.supplyKey(SUPPLY_KEY)
						.adminKey("adminKey")
						.treasury(TOKEN_TREASURY)
		).when(
				tokenDelete(NFT)
		).then(
				mintToken(NFT, List.of(ByteString.copyFromUtf8("memo")))
						.via("mintTxn")
						.hasKnownStatus(TOKEN_WAS_DELETED),

				getTokenNftInfo(NFT, 1)
						.hasCostAnswerPrecheck(INVALID_NFT_ID),

				getTokenInfo(NFT)
						.isDeleted()
		);
	}

	private HapiApiSpec failsGetTokenNftInfoWithNoNft() {
		return defaultHapiSpec("failsGetTokenNftInfoWithNoNft")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY)
				)
				.when(
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))).via("mintTxn")
				)
				.then(
						getTokenNftInfo(NFT, 0)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, -1)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, 2)
								.hasCostAnswerPrecheck(INVALID_NFT_ID)
				);
	}

	private HapiApiSpec getTokenNftInfoWorks() {
		return defaultHapiSpec("getTokenNftInfoWorks")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY)
				).when(
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))).via("mintTxn")
				).then(
						getTokenNftInfo(NFT, 0)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, -1)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo(NFT, 2)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo(NFT, 1)
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasSerialNum(1)
								.hasValidCreationTime()
				);
	}

	private HapiApiSpec happyPathWithRepeatedMetadata() {
		return defaultHapiSpec("happyPathWithRepeatedMetadata")
				.given(
						newKeyNamed(SUPPLY_KEY),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.supplyKey(SUPPLY_KEY)
								.initialSupply(0)
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo")))
								.via("mintTxn")
				).then(
						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasAccountID(TOKEN_TREASURY)
								.hasTokenID(NFT)
								.hasValidCreationTime(),

						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasAccountID(TOKEN_TREASURY)
								.hasTokenID(NFT)
								.hasValidCreationTime()
				);
	}

	protected Logger getResultsLogger() {
		return log;
	}

}
