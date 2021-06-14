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
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenSupplyType;
import com.hederahashgraph.api.proto.java.TokenType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getReceipt;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenNftInfo;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenFreeze;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_NFT_SERIAL_NUMBER;

public class UniqueTokenManagementSpecs extends HapiApiSuite {
	private static final org.apache.logging.log4j.Logger log = LogManager.getLogger(UniqueTokenManagementSpecs.class);
	private static final String NFT = "nft";
	private static final String FUNGIBLE_TOKEN = "fungible";

	public static void main(String... args) {
		new UniqueTokenManagementSpecs().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[]{
						sadPathWithRepeatedMetadata(),
						getTokenNftInfoWork(),
						sadPathWithFrozenToken(),
						uniqueTokenHappyPath(),
						happyPathOneMintFiveMetadata(),
						happyPathFiveMintOneMetadata(),
						distinctsSubTypes(),
				}
		);
	}

	private HapiApiSpec distinctsSubTypes() {
		return defaultHapiSpec("happyPathFiveMintOneMetadata")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY),
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
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))).via("mintNFT"),
						mintToken(FUNGIBLE_TOKEN, 100l).via("mintFungible")
				).then(
						UtilVerbs.withOpContext((spec, opLog) -> {
							var mintNFT = getTxnRecord("mintNFT");
							var mintFungible = getTxnRecord("mintFungible");
							allRunFor(spec, mintNFT, mintFungible);
							var nftFee = mintNFT.getResponseRecord().getTransactionFee();
							var fungibleFee = mintFungible.getResponseRecord().getTransactionFee();
							System.out.println(nftFee + " " + fungibleFee);
							Assert.assertNotEquals(
									"NFT Fee is equal to the Fungible Fee!",
									nftFee,
									fungibleFee);
						})
				);
	}

	private HapiApiSpec uniqueTokenHappyPath() {
		return defaultHapiSpec("UniqueTokenHappyPath")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo1"))).via("mintTxn")
				).then(

						getAccountBalance(TOKEN_TREASURY)
								.hasTokenBalance(NFT, 2),

						getReceipt("mintTxn")
								.hasPriorityStatus(ResponseCodeEnum.SUCCESS),

						getTokenNftInfo(NFT, 1)
								.hasSerialNum(1)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasTokenID(NFT),

						getTokenNftInfo(NFT, 2)
								.hasSerialNum(2)
								.hasMetadata(ByteString.copyFromUtf8("memo1"))
								.hasTokenID(NFT)
				);
	}

	private HapiApiSpec happyPathOneMintFiveMetadata() {
		return defaultHapiSpec("happyPathOneMintFiveMetadata")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY).balance(10000L),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.FINITE)
								.supplyKey("supplyKey")
								.initialSupply(0)
								.maxSupply(100000)
								.memo("memo")
								.treasury(TOKEN_TREASURY)
								.via("mintTxn")
				).when(
						mintToken(NFT, List.of(
								ByteString.copyFromUtf8("memo"),
								ByteString.copyFromUtf8("memo1"),
								ByteString.copyFromUtf8("memo2"),
								ByteString.copyFromUtf8("memo3"),
								ByteString.copyFromUtf8("memo4")
						))
				).then(
						getTokenNftInfo("token", 1).hasSerialNum(1).hasMetadata(ByteString.copyFromUtf8("memo")),
						getTokenNftInfo("token", 2).hasSerialNum(2).hasMetadata(ByteString.copyFromUtf8("memo1")),
						getTokenNftInfo("token", 3).hasSerialNum(3).hasMetadata(ByteString.copyFromUtf8("memo2")),
						getTokenNftInfo("token", 4).hasSerialNum(4).hasMetadata(ByteString.copyFromUtf8("memo3")),

						getTokenNftInfo("token", 5).hasSerialNum(5)
								.hasMetadata(ByteString.copyFromUtf8("memo4"))
								.hasTokenID(NFT)
								.hasAccountID(TOKEN_TREASURY)
				);
	}

	private HapiApiSpec happyPathFiveMintOneMetadata() {
		return defaultHapiSpec("happyPathFiveMintOneMetadata")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY)
				).when(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"))),
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo")))

				).then(
						getTokenNftInfo(NFT, 1).hasSerialNum(1).hasMetadata(ByteString.copyFromUtf8("memo")),
						getTokenNftInfo(NFT, 2).hasSerialNum(2).hasMetadata(ByteString.copyFromUtf8("memo")),
						getTokenNftInfo(NFT, 3).hasSerialNum(3).hasMetadata(ByteString.copyFromUtf8("memo")),
						getTokenNftInfo(NFT, 4).hasSerialNum(4).hasMetadata(ByteString.copyFromUtf8("memo")),
						getTokenNftInfo(NFT, 5).hasSerialNum(5).hasMetadata(ByteString.copyFromUtf8("memo"))
				);
	}

	private HapiApiSpec sadPathWithFrozenToken() {
		return defaultHapiSpec("sadPathWithFrozenToken")
				.given(
						newKeyNamed("supplyKey"),
						newKeyNamed("tokenFreezeKey"),
						cryptoCreate(TOKEN_TREASURY).balance(0L),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.freezeKey("tokenFreezeKey")
								.treasury(TOKEN_TREASURY)
				).when(
						tokenFreeze(NFT, TOKEN_TREASURY)
				).then(
						mintToken("token", List.of(ByteString.copyFromUtf8("memo"))).hasKnownStatus(ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN)
				);
	}

	private HapiApiSpec getTokenNftInfoWork() {

		return defaultHapiSpec("getTokenNftInfoWorks")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY).balance(0L)
				).when(
						tokenCreate("non-fungible-unique-finite")
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.FINITE)
								.supplyKey("supplyKey")
								.initialSupply(0)
								.maxSupply(100)
								.treasury(TOKEN_TREASURY),
						mintToken("non-fungible-unique-finite", List.of(ByteString.copyFromUtf8("memo")))
				).then(
						getTokenNftInfo("non-fungible-unique-finite", 0)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo("non-fungible-unique-finite", -1)
								.hasCostAnswerPrecheck(INVALID_TOKEN_NFT_SERIAL_NUMBER),
						getTokenNftInfo("non-fungible-unique-finite", 2)
								.hasCostAnswerPrecheck(INVALID_NFT_ID),
						getTokenNftInfo("non-fungible-unique-finite", 1)
								.hasTokenID("non-fungible-unique-finite")
								.hasAccountID(TOKEN_TREASURY)
								.hasMetadata(ByteString.copyFromUtf8("memo"))
								.hasSerialNum(1)
				);
	}

	private HapiApiSpec sadPathWithRepeatedMetadata() {
		return defaultHapiSpec("sadPathWithRepeatedMetadata")
				.given(
						newKeyNamed("supplyKey"),
						cryptoCreate(TOKEN_TREASURY),
						tokenCreate(NFT)
								.tokenType(TokenType.NON_FUNGIBLE_UNIQUE)
								.supplyType(TokenSupplyType.INFINITE)
								.initialSupply(0)
								.supplyKey("supplyKey")
								.treasury(TOKEN_TREASURY)
				).when(
				).then(
						mintToken(NFT, List.of(ByteString.copyFromUtf8("memo"), ByteString.copyFromUtf8("memo")))
								.hasKnownStatus(ResponseCodeEnum.INVALID_TRANSACTION_BODY)
				);
	}

	protected Logger getResultsLogger() {
		return log;
	}

}
