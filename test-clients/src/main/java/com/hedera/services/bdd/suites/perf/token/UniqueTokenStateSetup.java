package com.hedera.services.bdd.suites.perf.token;

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
import com.hedera.services.bdd.spec.HapiSpecOperation;
import com.hedera.services.bdd.spec.infrastructure.OpProvider;
import com.hedera.services.bdd.suites.HapiApiSuite;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.mintToken;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.tokenCreate;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.inParallel;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.runWithProvider;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hederahashgraph.api.proto.java.TokenSupplyType.INFINITE;
import static com.hederahashgraph.api.proto.java.TokenType.NON_FUNGIBLE_UNIQUE;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A client that creates some number of NON_FUNGIBLE_UNIQUE tokens, and then
 * for each token mints some number of NFTs, each w/ the requested number of
 * bytes of metadata. All tokens are created using the dev treasury key as
 * the token supply key.
 *
 * The exact number of entities to create can be configured using the
 * constants at the top of the class definition.
 *
 * <b>IMPORTANT:</b> Please note the following two items:
 * <ol>
 *   <li>
 *     If creating a large number of NFTs, e.g. 1M+, it is essential to
 *     comment out the body of the
 *     {@link com.hedera.services.bdd.spec.transactions.token.HapiTokenMint#updateStateOf(HapiApiSpec)}
 *     method, since it adds the minted token's creation time to the registry
 *     and the client will run OOM fairly quickly with a 1GB heap.
 *   </li>
 *   <li>
 *     There is evidence of slower memory leaks hidden elsewhere in the
 *     EET infrastructure, so you should probably not try to create more
 *     than 10M NFTs using a single run of this client; if more NFTs are
 *     needed, then please run several instances in sequence.
 *   </li>
 * </ol>
 */
public class UniqueTokenStateSetup extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(UniqueTokenStateSetup.class);

	private static final long SECS_TO_RUN = 4050;

	private static final int MINT_TPS = 250;
	private static final int NUM_UNIQ_TOKENS = 10_000;
	private static final int UNIQ_TOKENS_BURST_SIZE = 1000;
	private static final int UNIQ_TOKENS_POST_BURST_PAUSE_MS = 2500;
	private static final int NFTS_PER_UNIQ_TOKEN = 1000;
	private static final int NEW_NFTS_PER_MINT_OP = 10;
	private static final int UNIQ_TOKENS_PER_TREASURY = 500;
	private static final int METADATA_SIZE = 100;

	final IntFunction<String> treasuryNameFn = i -> "treasury" + i;
	final IntFunction<String> uniqueTokenNameFn = i -> "uniqueToken" + i;
	private AtomicLong duration = new AtomicLong(SECS_TO_RUN);
	private AtomicInteger maxOpsPerSec = new AtomicInteger(MINT_TPS);
	private AtomicReference<TimeUnit> unit = new AtomicReference<>(SECONDS);

	private static final AtomicReference<String> firstCreatedId = new AtomicReference<>(null);
	private static final AtomicReference<String> lastCreatedId = new AtomicReference<>(null);

	public static void main(String... args) {
		UniqueTokenStateSetup suite = new UniqueTokenStateSetup();
		suite.runSuiteSync();
		System.out.println("Created unique tokens from " + firstCreatedId + " + to " + lastCreatedId);
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(new HapiApiSpec[] {
						createNfts(),
				}
		);
	}

	private HapiApiSpec createNfts() {
		return defaultHapiSpec("CreateNfts")
				.given().when().then(
						runWithProvider(nftFactory())
								.lasting(duration::get, unit::get)
								.maxOpsPerSec(maxOpsPerSec::get)
				);
	}

	private Function<HapiApiSpec, OpProvider> nftFactory() {
		final AtomicInteger uniqueTokensCreated = new AtomicInteger(0);
		final AtomicInteger nftsMintedForCurrentUniqueToken = new AtomicInteger(0);
		final AtomicBoolean done = new AtomicBoolean(false);
		final AtomicReference<String> currentUniqueToken = new AtomicReference<>(uniqueTokenNameFn.apply(0));

		return spec -> new OpProvider() {
			@Override
			public List<HapiSpecOperation> suggestedInitializers() {
				final var numTreasuries = NUM_UNIQ_TOKENS / UNIQ_TOKENS_PER_TREASURY
						+ Math.min(1, NUM_UNIQ_TOKENS % UNIQ_TOKENS_PER_TREASURY);

				final List<HapiSpecOperation> inits = new ArrayList<>();
				inits.add(
						inParallel(IntStream.range(0, numTreasuries)
								.mapToObj(i -> cryptoCreate(treasuryNameFn.apply(i))
										.payingWith(GENESIS)
										.balance(0L)
										.key(GENESIS)
										.deferStatusResolution())
								.toArray(HapiSpecOperation[]::new)));
				inits.add(sleepFor(5_000L));
				inits.addAll(burstedUniqCreations(
						UNIQ_TOKENS_BURST_SIZE, numTreasuries, UNIQ_TOKENS_POST_BURST_PAUSE_MS));
				return inits;
			}

			@Override
			public Optional<HapiSpecOperation> get() {
				if (done.get()) {
					return Optional.empty();
				}

				final var currentToken = currentUniqueToken.get();
				if (nftsMintedForCurrentUniqueToken.get() < NFTS_PER_UNIQ_TOKEN) {
					final List<ByteString> allMeta = new ArrayList<>();
					final int noMoreThan = NFTS_PER_UNIQ_TOKEN - nftsMintedForCurrentUniqueToken.get();
					for (int i = 0, n = Math.min(noMoreThan, NEW_NFTS_PER_MINT_OP); i < n; i++) {
						final var nextSerialNo = nftsMintedForCurrentUniqueToken.incrementAndGet();
						allMeta.add(metadataFor(currentToken, nextSerialNo));
					}
					final var op = mintToken(currentToken, allMeta)
							.payingWith(GENESIS)
							.deferStatusResolution()
							.fee(ONE_HBAR)
							.noLogging();
					return Optional.of(op);
				} else {
					nftsMintedForCurrentUniqueToken.set(0);
					final var nextUniqTokenNo = uniqueTokensCreated.incrementAndGet();
					currentUniqueToken.set(uniqueTokenNameFn.apply(nextUniqTokenNo));
					if (nextUniqTokenNo >= NUM_UNIQ_TOKENS) {
						System.out.println("Done creating " + nextUniqTokenNo
								+ " unique tokens w/ at least "
								+ (NFTS_PER_UNIQ_TOKEN * nextUniqTokenNo) + " NFTs");
						done.set(true);
					}
					return Optional.empty();
				}
			}
		};
	}

	private List<HapiSpecOperation> burstedUniqCreations(int perBurst, int numTreasuries, long pauseMs) {
		final var createdSoFar = new AtomicInteger(0);
		List<HapiSpecOperation> ans = new ArrayList<>();
		while (createdSoFar.get() < NUM_UNIQ_TOKENS) {
			var thisBurst = Math.min(NUM_UNIQ_TOKENS - createdSoFar.get(), perBurst);
			final var burst = inParallel(IntStream.range(0, thisBurst)
					.mapToObj(i -> tokenCreate(uniqueTokenNameFn.apply(i + createdSoFar.get()))
							.payingWith(GENESIS)
							.tokenType(NON_FUNGIBLE_UNIQUE)
							.deferStatusResolution()
							.noLogging()
							.supplyType(INFINITE)
							.initialSupply(0)
							.supplyKey(GENESIS)
							.treasury(treasuryNameFn.apply((i + createdSoFar.get()) % numTreasuries))
							.exposingCreatedIdTo(newId -> {
								final var newN = numFrom(newId);
								if (newN < numFrom(firstCreatedId.get())) {
									firstCreatedId.set(newId);
								} else if (lastCreatedId.get() == null || newN > numFrom(lastCreatedId.get())) {
									lastCreatedId.set(newId);
								}
								if (newN % 100 == 0) {
									System.out.println("Resolved creation for " + newId);
								}
							}))
					.toArray(HapiSpecOperation[]::new));
			ans.add(burst);
			ans.add(sleepFor(pauseMs));
			createdSoFar.addAndGet(thisBurst);
		}
		return ans;
	}

	private long numFrom(String id) {
		if (id == null) {
			return Long.MAX_VALUE;
		}
		return Long.parseLong(id.substring(id.lastIndexOf('.') + 1));
	}

	private ByteString metadataFor(String uniqToken, int nftNo) {
		final var base = new StringBuilder(uniqToken).append("-SN").append(nftNo);
		var padding = METADATA_SIZE - base.length();
		while (padding-- > 0) {
			base.append("_");
		}
		return ByteString.copyFromUtf8(base.toString());
	}

	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
