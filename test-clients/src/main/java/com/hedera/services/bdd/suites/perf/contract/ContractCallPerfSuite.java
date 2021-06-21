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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.contractCallLocal;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.finishThroughputObs;
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
		return Arrays.asList(contractCallPerf());
	}

	@Override
	public boolean leaksState() {
		return true;
	}

	@Override
	public boolean hasInterestingStats() {
		return true;
	}

	private HapiApiSpec contractCallPerf() {
		final int NUM_CALLS = 6000;

		return defaultHapiSpec("ContractCallPerf")
				.given(
						fileCreate("contractBytecode").path(ContractResources.BENCHMARK_CONTRACT),
						contractCreate("perf").bytecode("contractBytecode")
				).when(
						UtilVerbs.startThroughputObs("contractCall").msToSaturateQueues(1000),
						UtilVerbs.inParallel(
								asOpArray(NUM_CALLS, i ->
										contractCall(
												"perf", ContractResources.SINGLE_SSTORE,
												Bytes.fromHexString("0xf2eeb729e636a8cb783be044acf6b7b1e2c5863735b60d6daae84c366ee87d97").toArray()
										)
												.hasKnownStatusFrom(SUCCESS, OK)
												.deferStatusResolution()
								)
						)
				).then(
						finishThroughputObs("contractCall").gatedByQuery(
								() ->
										contractCallLocal("perf", ContractResources.BENCHMARK_I_GET)
												.nodePayment(1_234_567)
												.has(
														ContractFnResultAsserts.resultWith()
																.resultThruAbi(
																		ContractResources.BENCHMARK_I_GET,
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