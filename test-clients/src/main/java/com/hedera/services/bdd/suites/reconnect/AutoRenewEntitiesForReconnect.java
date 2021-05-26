package com.hedera.services.bdd.suites.reconnect;

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
import com.hedera.services.bdd.spec.utilops.CustomSpecAssert;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withLiveNode;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class AutoRenewEntitiesForReconnect extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(AutoRenewEntitiesForReconnect.class);

	public static void main(String... args) {
		new AutoRenewEntitiesForReconnect().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				autoRenewAccountGetsDeletedOnReconnectingNodeAsWell()
		);
	}

	private HapiApiSpec autoRenewAccountGetsDeletedOnReconnectingNodeAsWell() {
		String autoDeleteAccount = "autoDeleteAccount";
		int autoRenewSecs = 10;
		return defaultHapiSpec("AutoRenewAccountGetsDeletedOnReconnectingNodeAsWell")
				.given(
						fileUpdate(APP_PROPERTIES).payingWith(GENESIS)
								.overridingProps(Map.of(
										"ledger.autoRenewPeriod.minDuration", String.valueOf(autoRenewSecs),
										"autorenew.gracePeriod", "0",
										"autorenew.numberOfEntitiesToScan", "100",
										"autorenew.maxNumberOfEntitiesToRenewOrDelete", "2"))
								.erasingProps(Set.of("minimumAutoRenewDuration")),
						cryptoCreate(autoDeleteAccount).autoRenewSecs(autoRenewSecs).balance(0L)
				)
				.when(
						// do some transfers so that we pass autoRenewSecs
						withOpContext((spec, ctxLog) -> {
							List<HapiSpecOperation> opsList = new ArrayList<HapiSpecOperation>();
							for (int i = 0; i < 25; i++) {
								opsList.add(cryptoTransfer(tinyBarsFromTo(GENESIS, NODE, 1L)).logged());
							}
							CustomSpecAssert.allRunFor(spec, opsList);
						}),

						withLiveNode("0.0.8")
								.within(60, TimeUnit.SECONDS)
								.loggingAvailabilityEvery(10)
								.sleepingBetweenRetriesFor(10)
				)
				.then(
						getAccountBalance(autoDeleteAccount)
								.setNode("0.0.8")
								.hasAnswerOnlyPrecheckFrom(INVALID_ACCOUNT_ID)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}

