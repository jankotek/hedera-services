package com.hedera.services.bdd.suites.perf.file;

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
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.utilops.LoadTest;
import com.hedera.services.bdd.suites.perf.PerfTestLoadSettings;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileInfo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileAppend;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.logIt;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.withOpContext;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.UNKNOWN;

/**
 * Run mixed operations including FileCreate, FileAppend, FileUpdate
 */
public class MixedFileOpsLoadTest extends LoadTest {
	private static final Logger log = LogManager.getLogger(MixedFileOpsLoadTest.class);

	public static void main(String... args) {
		parseArgs(args);

		MixedFileOpsLoadTest suite = new MixedFileOpsLoadTest();
		suite.setReportStats(true);
		suite.runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				runMixedFileOps()
		);
	}

	@Override
	public boolean hasInterestingStats() {
		return true;
	}

	protected HapiApiSpec runMixedFileOps() {
		PerfTestLoadSettings settings = new PerfTestLoadSettings();
		final AtomicInteger submittedSoFar = new AtomicInteger(0);
		String initialContent = "The initial contents!";
		String targetFile = "targetFile";

		Supplier<HapiSpecOperation[]> mixedFileOpsBurst = () -> new HapiSpecOperation[] {
				fileCreate(targetFile + submittedSoFar.getAndIncrement())
						.contents(initialContent)
						.hasKnownStatusFrom(SUCCESS, UNKNOWN),
				fileUpdate(targetFile)
						.fee(ONE_HUNDRED_HBARS)
						.contents(TxnUtils.randomUtf8Bytes(TxnUtils.BYTES_4K))
						.noLogging()
						.payingWith(GENESIS)
						.hasAnyPrecheck()
						.hasKnownStatusFrom(SUCCESS, UNKNOWN)
						.deferStatusResolution(),
				fileAppend(targetFile)
						.content("dummy")
						.hasAnyPrecheck()
						.payingWith(GENESIS)
						.fee(ONE_HUNDRED_HBARS)
						.hasKnownStatusFrom(SUCCESS, UNKNOWN)
						.deferStatusResolution()
		};

		return defaultHapiSpec("runMixedFileOps")
				.given(
						withOpContext((spec, ignore) -> settings.setFrom(spec.setup().ciPropertiesMap())),
						logIt(ignore -> settings.toString())
				).when(
						fileCreate(targetFile)
								.contents(initialContent)
								.hasAnyPrecheck()
								.payingWith(GENESIS),
						getFileInfo(targetFile)
								.logging()
								.payingWith(GENESIS)
				).then(
						defaultLoadTest(mixedFileOpsBurst, settings)
				);
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
