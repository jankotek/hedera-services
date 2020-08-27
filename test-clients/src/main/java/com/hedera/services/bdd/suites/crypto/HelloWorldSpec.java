package com.hedera.services.bdd.suites.crypto;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
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

import static com.hedera.services.bdd.spec.HapiApiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.*;

import com.hedera.services.bdd.spec.assertions.AssertUtils;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.*;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

public class HelloWorldSpec extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(HelloWorldSpec.class);

	public static void main(String... args) {
		new HelloWorldSpec().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[]{
//						balancesChangeOnTransfer(),
//						balancesChangeOnTransferWithOverrides(),
//						someCreateRecordsSomeDont(),
						info(),
				}
		);
	}

	private HapiApiSpec balancesChangeOnTransferWithOverrides() {
		return customHapiSpec("BalancesChangeOnTransfer")
				.withProperties(
						Map.of(
								"nodes", "35.182.80.176",
								"default.payer", "0.0.50",
								"startupAccounts.path", "src/main/resource/TestnetStartupAccount.txt"
						)
				).given(
						cryptoCreate("sponsor"),
						cryptoCreate("beneficiary"),
						balanceSnapshot("sponsorBefore", "sponsor"),
						balanceSnapshot("beneficiaryBefore", "beneficiary")
				).when(
						cryptoTransfer(tinyBarsFromTo("sponsor", "beneficiary", 1L))
								.payingWith(GENESIS)
								.memo("Hello World!")
				).then(
						getAccountBalance("sponsor")
								.hasTinyBars(changeFromSnapshot("sponsorBefore", -1L)),
						getAccountBalance("beneficiary")
								.hasTinyBars(changeFromSnapshot("beneficiaryBefore", +1L))
				);
	}

	private HapiApiSpec balancesChangeOnTransfer() {
		return defaultHapiSpec("BalancesChangeOnTransfer")
				.given(
						cryptoCreate("sponsor"),
						cryptoCreate("beneficiary"),
						balanceSnapshot("sponsorBefore", "sponsor"),
						balanceSnapshot("beneficiaryBefore", "beneficiary")
				).when(
						cryptoTransfer(tinyBarsFromTo("sponsor", "beneficiary", 1L))
								.payingWith(GENESIS)
								.memo("Hello World!")
				).then(
						getAccountBalance("sponsor")
								.hasTinyBars(changeFromSnapshot("sponsorBefore", -1L)),
						getAccountBalance("beneficiary")
								.hasTinyBars(changeFromSnapshot("beneficiaryBefore", +1L))
				);
	}

	private HapiApiSpec someCreateRecordsSomeDont() {
		var START = 1_000 * 100_000_000L;

		return defaultHapiSpec("SomeCreateRecordsSomeDont")
				.given(
						cryptoCreate("low").balance(START).sendThreshold(0L),
						cryptoCreate("high").balance(START).sendThreshold(Long.MAX_VALUE)
				).when(
						cryptoTransfer(tinyBarsFromTo("low", FUNDING, 1L))
								.payingWith("low")
								.memo("Hello World!"),
						cryptoTransfer(tinyBarsFromTo("low", FUNDING, 1L))
								.payingWith("low")
								.memo("Hello World!"),
						cryptoTransfer(tinyBarsFromTo("low", FUNDING, 1L))
								.payingWith("low")
								.memo("Hello World!"),
						cryptoTransfer(tinyBarsFromTo("high", FUNDING, 1L))
								.payingWith("high"),
						cryptoTransfer(tinyBarsFromTo("high", FUNDING, 1L))
								.payingWith("high")
				).then(
						getAccountRecords("low").has(inOrder(recordWith(), recordWith(), recordWith()))
				);
	}

	private HapiApiSpec info() {
		var START = 1_000 * 100_000_000L;
		var r = new SplittableRandom();
		var sb = new StringBuilder();
		var choices = "abcdefghijklmnopqrstuvwxqyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXQYZ".toCharArray();
		for (int i = 0; i < 20; i++) {
			sb.append(choices[r.nextInt(choices.length)]);
		}
		var password = sb.toString();
		System.out.println(password);
		return defaultHapiSpec("Info")
				.given(
						newKeyNamed("fm").type(KeyFactory.KeyType.SIMPLE),
						withOpContext((spec, opLog) -> {
							KeyFactory.PEM_PASSPHRASE = password;
							spec.keys().exportSimpleKey(String.format("stable-testnet-genesis.pem"), "fm");
						})
				).when( ).then(
						getAccountInfo("0.0.1001").logged(),
						getAccountInfo("0.0.1002").logged()
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
