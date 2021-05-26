package com.hedera.services.bdd.suites.autorenew;

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

import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import com.hedera.services.bdd.spec.queries.crypto.HapiGetAccountBalance;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.accountWith;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.suites.autorenew.AutoRenewConfigChoices.DEFAULT_HIGH_SPIN_SCAN_COUNT;
import static com.hedera.services.bdd.suites.autorenew.AutoRenewConfigChoices.DEFAULT_HIGH_TOUCH_COUNT;
import static com.hedera.services.bdd.suites.autorenew.AutoRenewConfigChoices.disablingAutoRenewWithDefaults;
import static com.hedera.services.bdd.suites.autorenew.AutoRenewConfigChoices.enablingAutoRenewWith;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class AccountAutoRenewalSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(AccountAutoRenewalSuite.class);

	public static void main(String... args) {
		new AccountAutoRenewalSuite().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						accountAutoRemoval(),
						accountAutoRenewal(),
						maxNumberOfEntitiesToRenewOrDeleteWorks(),
						numberOfEntitiesToScanWorks(),
						autoDeleteAfterGracePeriod(),

						accountAutoRenewalSuiteCleanup(),
				}
		);
	}

	private HapiApiSpec accountAutoRemoval() {
		String autoRemovedAccount = "autoRemovedAccount";
		return defaultHapiSpec("AccountAutoRemoval")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(enablingAutoRenewWith(1, 0)),
						cryptoCreate(autoRemovedAccount).autoRenewSecs(1).balance(0L),
						getAccountInfo(autoRemovedAccount).logged()
				).when(
						sleepFor(1_500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L))
								.via("triggeringTransaction")
				).then(
						getAccountBalance(autoRemovedAccount).hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID)
				);
	}

	private HapiApiSpec accountAutoRenewal() {
		final var briefAutoRenew = 3L;
		final var autoRenewedAccount = "autoRenewedAccount";

		long initialBalance = ONE_HUNDRED_HBARS;
		return defaultHapiSpec("AccountAutoRenewal")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(enablingAutoRenewWith(
										briefAutoRenew,
										0,
										DEFAULT_HIGH_SPIN_SCAN_COUNT,
										DEFAULT_HIGH_TOUCH_COUNT)),
						cryptoCreate(autoRenewedAccount)
								.autoRenewSecs(briefAutoRenew)
								.balance(initialBalance),
						getAccountInfo(autoRenewedAccount)
								.saveToRegistry(autoRenewedAccount)
				).when(
						sleepFor(briefAutoRenew * 1_000L + 500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L))
								.via("triggeringTransaction")
				).then(
						getAccountInfo(autoRenewedAccount)
								.has(accountWith()
										.expiry(autoRenewedAccount, briefAutoRenew)
										.balanceLessThan(initialBalance)),
						cryptoDelete(autoRenewedAccount)
				);
	}

	/**
	 * Mostly useful to run from a blank state where the only three accounts
	 * that exist are those created in the "given" clause of this spec.
	 *
	 * If run against a network that has existing funded accounts with very
	 * low auto-renew periods, this test is just a minimal sanity check.
	 */
	private HapiApiSpec maxNumberOfEntitiesToRenewOrDeleteWorks() {
		final var briefAutoRenew = 3L;
		final var firstTouchable = "a";
		final var secondTouchable = "b";
		final var untouchable = "c";

		return defaultHapiSpec("MaxNumberOfEntitiesToRenewOrDeleteWorks")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(enablingAutoRenewWith(
										briefAutoRenew, 0, DEFAULT_HIGH_SPIN_SCAN_COUNT, 2)),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L)),
						cryptoCreate(firstTouchable)
								.autoRenewSecs(briefAutoRenew).balance(0L),
						cryptoCreate(secondTouchable)
								.autoRenewSecs(briefAutoRenew).balance(0L),
						cryptoCreate(untouchable)
								.autoRenewSecs(briefAutoRenew).balance(0L)
				).when(
						sleepFor(briefAutoRenew * 1_000L + 500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L))
								.via("triggeringTransaction")
				).then(
						assertionsHold((spec, opLog) -> {
							var subOpA = getAccountBalance(firstTouchable)
									.hasAnswerOnlyPrecheckFrom(OK, INVALID_ACCOUNT_ID);
							var subOpB = getAccountBalance(secondTouchable)
									.hasAnswerOnlyPrecheckFrom(OK, INVALID_ACCOUNT_ID);
							var subOpC = getAccountBalance(untouchable)
									.hasAnswerOnlyPrecheckFrom(OK, INVALID_ACCOUNT_ID);
							allRunFor(spec, subOpA, subOpB, subOpC);
							final var aStatus = subOpA.getResponse().getCryptogetAccountBalance()
											.getHeader().getNodeTransactionPrecheckCode();
							final var bStatus = subOpB.getResponse().getCryptogetAccountBalance()
									.getHeader().getNodeTransactionPrecheckCode();
							final var cStatus = subOpC.getResponse().getCryptogetAccountBalance()
									.getHeader().getNodeTransactionPrecheckCode();
							opLog.info("Results: {}, {}, {}", aStatus, bStatus, cStatus);
							final long numRemoved = Stream.of(aStatus, bStatus, cStatus)
									.filter(INVALID_ACCOUNT_ID::equals)
									.count();
							Assert.assertTrue("More than 2 entities were touched!", numRemoved <= 2L);
						})
				);
	}

	/**
	 * Mostly useful to run from a blank state where the only accounts
	 * that exist are those created in the "given" clause of this spec.
	 *
	 * If run against a network that has existing funded accounts with very
	 * low auto-renew periods, this test is just a minimal sanity check.
	 */
	private HapiApiSpec numberOfEntitiesToScanWorks() {
		final var briefAutoRenew = 3L;
		final int abbrevMaxToScan = 10;
		final IntFunction<String> accountName = i -> "fastExpiring" + i;

		return defaultHapiSpec("NumberOfEntitiesToScanWorks")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(enablingAutoRenewWith(
										briefAutoRenew, 0, abbrevMaxToScan, DEFAULT_HIGH_TOUCH_COUNT)),
						inParallel(IntStream.range(0, abbrevMaxToScan + 1)
								.mapToObj(i -> cryptoCreate(accountName.apply(i))
										.autoRenewSecs(briefAutoRenew)
										.balance(0L))
								.toArray(HapiSpecOperation[]::new))
				).when(
						sleepFor(briefAutoRenew * 1_000L + 500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L))
				).then(
						assertionsHold((spec, opLog) -> {
							final HapiSpecOperation[] subOps = IntStream.range(0, abbrevMaxToScan + 1)
									.mapToObj(i -> getAccountBalance(accountName.apply(i))
											.hasAnswerOnlyPrecheckFrom(INVALID_ACCOUNT_ID, OK))
									.toArray(HapiSpecOperation[]::new);
							allRunFor(spec, subOps);
							final long numRemoved = Stream.of(subOps)
									.map(op -> ((HapiQueryOp<HapiGetAccountBalance>)op).getResponse()
											.getCryptogetAccountBalance().getHeader().getNodeTransactionPrecheckCode())
									.peek(opLog::info)
									.filter(INVALID_ACCOUNT_ID::equals)
									.count();
							Assert.assertTrue(
									"More than " + abbrevMaxToScan + " entities were touched!",
									numRemoved <= abbrevMaxToScan);
						})
				);
	}

	private HapiApiSpec autoDeleteAfterGracePeriod() {
		final var briefAutoRenew = 3L;
		String autoDeleteAccount = "autoDeleteAccount";
		return defaultHapiSpec("AutoDeleteAfterGracePeriod")
				.given(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(enablingAutoRenewWith(briefAutoRenew, 2 * briefAutoRenew)),
						cryptoCreate(autoDeleteAccount)
								.autoRenewSecs(briefAutoRenew)
								.balance(0L)
				).when(
						sleepFor(briefAutoRenew * 1_000L + 500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L)),
						getAccountBalance(autoDeleteAccount),
						sleepFor(2 * briefAutoRenew * 1_000L + 500L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L))
				).then(
						getAccountBalance(autoDeleteAccount)
								.hasAnswerOnlyPrecheck(INVALID_ACCOUNT_ID)
				);
	}

	private HapiApiSpec accountAutoRenewalSuiteCleanup() {
		return defaultHapiSpec("accountAutoRenewalSuiteCleanup")
				.given().when().then(
						fileUpdate(APP_PROPERTIES)
								.payingWith(GENESIS)
								.overridingProps(disablingAutoRenewWithDefaults())
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
