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
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.*;

import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.assertions.AssertUtils;
import com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts;
import com.hedera.services.bdd.spec.keys.KeyFactory;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.LongStream;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.*;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.keyFromPem;
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
				new HapiApiSpec[] {
//						balancesChangeOnTransfer(),
//						balancesChangeOnTransferWithOverrides(),
//						someCreateRecordsSomeDont(),
//						info(),
						newTestnetKeys(),
				}
		);
	}

	private HapiApiSpec newTestnetKeys() {
		long[] systemAccounts = new long[] {
//				50, 55, 56, 57, 58, 59, 60
//				88, 90,
				90
		};

//		var key = "src/main/resource/StartUpAccount.txt";
//		var nodes = "localhost";
		var key = "src/main/resource/StableTestnetStartupAccount.txt";
		var nodes = "34.94.254.82";

		long ONE_HBAR = 100_000_000L;
		long AMOUNT = 1_000_000 * ONE_HBAR;

		return customHapiSpec("NewTestnetKeys")
				.withProperties(
						Map.of(
								"nodes", nodes,
								"default.payer", "0.0.2",
								"startupAccounts.path", key
						)
				).given(
						inParallel(LongStream.range(0, 1_000).mapToObj(i ->
								cryptoTransfer(tinyBarsFromTo(GENESIS, FUNDING, 1)))
								.toArray(n ->  new HapiSpecOperation[n]))
//						getAccountBalance("0.0.2").logged(),
//						cryptoTransfer(tinyBarsFromTo(GENESIS, SYNTHETICS, AMOUNT)),
//						getAccountBalance("0.0.88").logged(),
//						getAccountBalance("0.0.90").logged()
				).when(flattened(
//						Arrays.stream(systemAccounts)
//								.mapToObj(this::savedKeyFor)
//								.toArray(n -> new HapiSpecOperation[n][])
				)).then(flattened(
//						Arrays.stream(systemAccounts)
//								.mapToObj(this::keyConfirmationFor)
//								.toArray(n -> new HapiSpecOperation[n][])
				));
	}

	private HapiSpecOperation[] keyConfirmationFor(long num) {
		var pemLoc = String.format("newKeys/stableTestnet-account%d.pem", num);
		var passLoc = String.format("newKeys/stableTestnet-account%d.pass", num);
		var passphrase = "N/A";
		try {
			passphrase = Files.readString(Paths.get(passLoc));
			passphrase = passphrase.substring(0, passphrase.length() - 1);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}

		var account = "0.0." + num;
		var keyName = "test" + num;
		return new HapiSpecOperation[] {
				keyFromPem(pemLoc)
						.name(keyName)
						.passphrase(passphrase)
						.linkedTo(account),
				cryptoTransfer(tinyBarsFromTo(account, FUNDING, 1))
						.payingWith(account)
						.signedBy(keyName)
		};
	}

	private HapiSpecOperation[] savedKeyFor(long num) {
		var loc = Paths.get(String.format("newKeys/stableTestnet-account%d.pass", num));
		final var passphrase = randomPassphrase();
		final var keyName = "system" + num;
		try (BufferedWriter writer = Files.newBufferedWriter(loc)) {
			writer.write(passphrase + "\n");
			writer.flush();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		final var account = "0.0." + num;
		return new HapiSpecOperation[] {
				newKeyNamed(keyName).type(KeyFactory.KeyType.SIMPLE),
				withOpContext((spec, opLog) -> {
					KeyFactory.PEM_PASSPHRASE = passphrase;
					spec.keys().exportSimpleKey(String.format("newKeys/stableTestnet-account%d.pem", num), keyName);
				}),
				cryptoUpdate(account).key(keyName).signedBy(GENESIS, keyName),
				cryptoTransfer(tinyBarsFromTo(GENESIS, account, 1_000_000 * 100_000_000))
		};
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
		var password = randomPassphrase();
		return defaultHapiSpec("Info")
				.given(
						newKeyNamed("fm").type(KeyFactory.KeyType.SIMPLE),
						withOpContext((spec, opLog) -> {
							KeyFactory.PEM_PASSPHRASE = password;
							spec.keys().exportSimpleKey(String.format("stable-testnet-genesis.pem"), "fm");
						})
				).when().then(
						getAccountInfo("0.0.1001").logged(),
						getAccountInfo("0.0.1002").logged()
				);
	}

	private String randomPassphrase() {
		var r = new SplittableRandom();
		var sb = new StringBuilder();
		var choices = "abcdefghijklmnopqrstuvwxqyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXQYZ".toCharArray();
		for (int i = 0; i < 20; i++) {
			sb.append(choices[r.nextInt(choices.length)]);
		}
		return sb.toString();
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
