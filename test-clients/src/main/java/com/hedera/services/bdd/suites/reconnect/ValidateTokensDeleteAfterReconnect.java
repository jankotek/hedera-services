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
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.hedera.services.bdd.spec.HapiApiSpec.customHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTokenInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDelete;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenDissociate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.blockingOrder;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.newKeyNamed;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withLiveNode;
import static com.hedera.services.bdd.suites.reconnect.AutoRenewEntitiesForReconnect.runTransfersBeforeReconnect;

/**
 * A reconnect test in which  a few tokens are created while the node 0.0.8 is disconnected from the network. Once the
 * node is reconnected the state of tokens is verified on reconnected node and other node
 */
public class ValidateTokensDeleteAfterReconnect extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ValidateTokensDeleteAfterReconnect.class);
	public static final String reconnectingNode = "0.0.8";
	public static final String nonReconnectingNode = "0.0.3";
	private static final long TOKEN_INITIAL_SUPPLY = 500;

	public static void main(String... args) {
		new ValidateAppPropertiesStateAfterReconnect().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				runTransfersBeforeReconnect(),
				validateTokensAfterReconnect()
		);
	}

	private HapiApiSpec validateTokensAfterReconnect() {
		String token = "token";
		String account = "account";
		String adminKey = "admin";

		return customHapiSpec("ValidateTokensAfterReconnect")
				.withProperties(Map.of(
						"txn.start.offset.secs", "-5")
				)
				.given(
						sleepFor(Duration.ofSeconds(25).toMillis()),
						cryptoCreate(TOKEN_TREASURY).balance(ONE_MILLION_HBARS).logging(),
						cryptoCreate(account).balance(ONE_HUNDRED_HBARS).logging(),
						newKeyNamed(adminKey),
						tokenCreate(token)
								.initialSupply(TOKEN_INITIAL_SUPPLY)
								.treasury(TOKEN_TREASURY)
								.adminKey(adminKey)
								.logging()
				)
				.when(
						getAccountBalance(GENESIS)
								.setNode(reconnectingNode)
								.unavailableNode(),

						tokenDelete(token).logging(),

						blockingOrder(
								IntStream.range(0, 500).mapToObj(i ->
										getTokenInfo(token))
										.toArray(HapiSpecOperation[]::new)
						)
				)
				.then(
						withLiveNode(reconnectingNode)
								.within(5 * 60, TimeUnit.SECONDS)
								.loggingAvailabilityEvery(30)
								.sleepingBetweenRetriesFor(10),

						/*
						Check that the reconnected node knows it's ok to dissociate the
						treasury from a deleted token. -> https://github.com/hashgraph/hedera-services/issues/1678
						*/
						tokenDissociate(TOKEN_TREASURY, token).setNode(reconnectingNode)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
