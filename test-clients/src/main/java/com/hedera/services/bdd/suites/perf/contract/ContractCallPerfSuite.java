package com.hedera.services.bdd.suites.perf.contract;

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
import com.hedera.services.bdd.spec.assertions.ContractFnResultAsserts;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.spec.utilops.UtilVerbs;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asSolidityAddressHex;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenAssociate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.finishThroughputObs;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractCallPerfSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(ContractCallPerfSuite.class);

	public static void main(String... args) {
		ContractCallPerfSuite suite = new ContractCallPerfSuite();
		suite.setReportStats(true);
		suite.runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return Arrays.asList(
				htsTransferPerf()
//				contractCallManyLoads()
//				contractCallPerf()
//				manySStores()
		);
	}

	@Override
	public boolean leaksState() {
		return true;
	}

	@Override
	public boolean hasInterestingStats() {
		return true;
	}

	private HapiApiSpec htsTransferPerf() {
		final int NUM_CALLS = 4000;
		AtomicReference<TokenID> tokenId = new AtomicReference<>();
		AtomicReference<AccountID> recipient = new AtomicReference<>();

		final String RECIPIENT = "recipient";
		final String SENDER = "sender";
		final String TOKEN_NAME = "token";
		final int INITIAL_SUPPLY = 100_000;
		final int TRANSFER_AMOUNT = 1;

		return defaultHapiSpec("HTS TRANSFER PERF")
				.given(
						/* crypto create */
						cryptoCreate(SENDER)
								.balance(10 * ONE_HUNDRED_HBARS),
						cryptoCreate(RECIPIENT)
								.balance(10 * ONE_HUNDRED_HBARS),
						/* contract creation */
						fileCreate("bytecode")
								.path(ContractResources.HTS_TRANSFER_CONTRACT),
						contractCreate("htsTransferContract")
								.bytecode("bytecode")
								.via("creationTx"),
						/* token creation */
						tokenCreate("token")
								.treasury(SENDER)
								.decimals(1)
								.initialSupply(INITIAL_SUPPLY),
						/* token association */
						tokenAssociate(RECIPIENT, TOKEN_NAME),
						/* save actual recipient AccountID and token TokenID to pass them as args to contract call */
						withOpContext((spec, opLog) -> {
							recipient.set(spec.registry().getAccountID(RECIPIENT));
							tokenId.set(spec.registry().getTokenID(TOKEN_NAME));
						})
				)
				.when(
						UtilVerbs.startThroughputObs("contractCall").msToSaturateQueues(1500),
						UtilVerbs.inParallel(
								asOpArray(NUM_CALLS, i ->
										sourcing(() -> contractCall(
												"htsTransferContract",
												ContractResources.TOKEN_TRANSFER_ABI,
												asSolidityAddressHex(tokenId.get()),
												asSolidityAddressHex(recipient.get()),
												TRANSFER_AMOUNT
												)
														.payingWith(SENDER)
														.hasKnownStatusFrom(SUCCESS, OK)
														.deferStatusResolution()
										)
								)
						)
				).then(
						finishThroughputObs("contractCall").gatedByQuery(
								() ->
										getAccountBalance(RECIPIENT)
												.hasTokenBalance(TOKEN_NAME, NUM_CALLS)
						)
				);
	}

	private HapiApiSpec contractCallPerf() {
		final int NUM_CALLS = 6000;

		return defaultHapiSpec("ContractCallPerf")
				.given(
						fileCreate("contractBytecode").path(ContractResources.BENCHMARK_CONTRACT),
						contractCreate("perf").bytecode("contractBytecode")
				).when(
						UtilVerbs.startThroughputObs("contractCall").msToSaturateQueues(1500),
						UtilVerbs.inParallel(
								asOpArray(NUM_CALLS, i ->
										contractCall(
												"perf", ContractResources.TWO_SSTORES,
												Bytes.fromHexString("0xf2eeb729e636a8cb783be044acf6b7b1e2c5863735b60d6daae84c366ee87d97").toArray()
										)
												.hasKnownStatusFrom(SUCCESS, OK)
												.deferStatusResolution()
								)
						)
				).then(
						finishThroughputObs("contractCall").gatedByQuery(
								() ->
										contractCallLocal("perf", ContractResources.BENCHMARK_GET_COUNTER)
												.nodePayment(1_234_567)
												.has(
														ContractFnResultAsserts.resultWith()
																.resultThruAbi(
																		ContractResources.BENCHMARK_GET_COUNTER,
																		ContractFnResultAsserts.isLiteralResult(new Object[]{BigInteger.valueOf(NUM_CALLS)})
																)
												)
						));
	}

	private HapiApiSpec contractCallManyLoads() {
		final int NUM_CALLS = 6000;
		final int N = 1000;

		return defaultHapiSpec("ContractCallLoadTx")
				.given(
						fileCreate("contractBytecode").path(ContractResources.BENCHMARK_CONTRACT),
						contractCreate("perf").bytecode("contractBytecode")
				).when(
						UtilVerbs.startThroughputObs("contractCall").msToSaturateQueues(1500),
						UtilVerbs.inParallel(
								asOpArray(NUM_CALLS, i ->
										contractCall(
												"perf", ContractResources.TWO_SSTORES,
												N
										)
												.hasKnownStatusFrom(SUCCESS, OK)
												.deferStatusResolution()
								)
						)
				).then(
						finishThroughputObs("contractCall").gatedByQuery(
								() ->
										contractCallLocal("perf", ContractResources.BENCHMARK_GET_COUNTER)
												.nodePayment(1_234_567)
												.has(
														ContractFnResultAsserts.resultWith()
																.resultThruAbi(
																		ContractResources.BENCHMARK_GET_COUNTER,
																		ContractFnResultAsserts.isLiteralResult(new Object[]{BigInteger.valueOf(NUM_CALLS)})
																)
												)
						));
	}

	private HapiApiSpec manySStores() {
		final int NUM_CALLS = 5;

		return defaultHapiSpec("ContractCallPerfManySSTOREs")
				.given(
						fileCreate("contractBytecode").path(ContractResources.BENCHMARK_CONTRACT),
						contractCreate("perf").bytecode("contractBytecode")
				).when(
						UtilVerbs.startThroughputObs("contractCall").msToSaturateQueues(100),
						UtilVerbs.inParallel(
								asOpArray(NUM_CALLS, i ->
										contractCall(
												"perf", ContractResources.SSTORE_CREATE,
												5
										)
												.hasKnownStatusFrom(SUCCESS, OK)
												.deferStatusResolution()
								)
						)
				).then(
						finishThroughputObs("contractCall").gatedByQuery(
								() ->
										contractCallLocal("perf", ContractResources.BENCHMARK_GET_COUNTER)
												.nodePayment(1_234_567)
												.has(
														ContractFnResultAsserts.resultWith()
																.resultThruAbi(
																		ContractResources.BENCHMARK_GET_COUNTER,
																		ContractFnResultAsserts.isLiteralResult(new Object[]{BigInteger.valueOf(NUM_CALLS)})
																)
												)
						));
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}