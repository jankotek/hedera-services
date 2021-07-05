package com.hedera.services.bdd.suites.crypto;


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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.OptionalLong;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHbarFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fixedHtsFee;
import static com.hedera.services.bdd.spec.transactions.token.CustomFeeSpecs.fractionalFee;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.moving;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;

public class TransferWithCustomFees extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(TransferWithCustomFees.class);
	private final long hbarFee = 1_000L;
	private final long htsFee = 100L;
	private final long tokenTotal = 1_000L;
	private final long numerator = 1L;
	private final long denominator = 10L;
	private final long minHtsFee = 2L;
	private final long maxHtsFee = 10L;

	private final String token = "withCustomSchedules";
	private final String feeDenom = "denom";
	private final String hbarCollector = "hbarFee";
	private final String htsCollector = "denomFee";
	private final String tokenReceiver = "receiver";

	private final String tokenOwner = "tokenOwner";

	public static void main(String... args) {
		new TransferWithCustomFees().runSuiteAsync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
				transferWithFixedCustomFeeSchedule(),
				transferWithFractinalCustomFeeSchedule(),
				transferWithInsufficientCustomFees()
				}
		);
	}

	public HapiApiSpec transferWithFixedCustomFeeSchedule() {
		return defaultHapiSpec("transferWithFixedCustomFeeSchedule")
				.given(
						cryptoCreate(htsCollector),
						cryptoCreate(hbarCollector)
								.balance(0L),
						cryptoCreate(tokenReceiver),

						cryptoCreate(tokenOwner)
								.balance(ONE_MILLION_HBARS),

						tokenCreate(feeDenom)
								.treasury(tokenOwner)
								.initialSupply(tokenTotal),

						tokenAssociate(htsCollector, feeDenom),

						tokenCreate(token)
								.treasury(tokenOwner)
								.initialSupply(tokenTotal)
								.withCustom(fixedHbarFee(hbarFee, hbarCollector))
								.withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),

						tokenAssociate(tokenReceiver, token)
				).when(
						cryptoTransfer(moving(1, token).between(tokenOwner, tokenReceiver))
								.fee(ONE_HUNDRED_HBARS)
								.payingWith(tokenOwner)

				).then(
						getAccountBalance(tokenOwner)
								.hasTokenBalance(token, 999)
								.hasTokenBalance(feeDenom, 900),
						getAccountBalance(hbarCollector).hasTinyBars(hbarFee)
				);
	}

	public HapiApiSpec transferWithFractinalCustomFeeSchedule() {
		return defaultHapiSpec("transferWithCustomFeeScheduleHappyPath")
				.given(
						cryptoCreate(htsCollector),
						cryptoCreate(hbarCollector)
								.balance(0L),
						cryptoCreate(tokenReceiver),

						cryptoCreate(tokenOwner)
								.balance(ONE_MILLION_HBARS),

						tokenCreate(feeDenom)
								.treasury(tokenOwner)
								.initialSupply(tokenTotal),

						tokenAssociate(htsCollector, feeDenom),

						tokenCreate(token)
								.treasury(tokenOwner)
								.initialSupply(tokenTotal)
								.withCustom(fixedHbarFee(hbarFee, hbarCollector))
								.withCustom(fractionalFee(
										numerator, denominator,
										minHtsFee, OptionalLong.of(maxHtsFee),
										htsCollector)),

						tokenAssociate(tokenReceiver, token),
						tokenAssociate(htsCollector, token)
				).when(
						cryptoTransfer(moving(1, token).between(tokenOwner, tokenReceiver))
								.fee(ONE_HUNDRED_HBARS)
								.payingWith(tokenOwner)
				).then(
						getAccountBalance(tokenOwner)
								.hasTokenBalance(token, 997)
								.hasTokenBalance(feeDenom, tokenTotal),
						getAccountBalance(hbarCollector).hasTinyBars(hbarFee)
				);
	}


	public HapiApiSpec transferWithInsufficientCustomFees() {
		return defaultHapiSpec("transferWithFixedCustomFeeSchedule")
				.given(
						cryptoCreate(htsCollector),
						cryptoCreate(hbarCollector)
								.balance(0L),
						cryptoCreate(tokenReceiver),

						cryptoCreate(tokenOwner)
								.balance(ONE_MILLION_HBARS),

						tokenCreate(feeDenom)
								.treasury(tokenOwner)
								.initialSupply(10),

						tokenAssociate(htsCollector, feeDenom),

						tokenCreate(token)
								.treasury(tokenOwner)
								.initialSupply(tokenTotal)
								.withCustom(fixedHtsFee(htsFee, feeDenom, htsCollector)),

						tokenAssociate(tokenReceiver, token)
				).when().then(
						cryptoTransfer(moving(1, token).between(tokenOwner, tokenReceiver))
								.fee(ONE_HUNDRED_HBARS)
								.payingWith(tokenOwner)
								.hasKnownStatus(ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE_FOR_CUSTOM_FEE)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
