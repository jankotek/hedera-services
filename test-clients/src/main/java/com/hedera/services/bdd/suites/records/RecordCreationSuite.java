package com.hedera.services.bdd.suites.records;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiSpecSetup;
import com.hedera.services.bdd.spec.infrastructure.meta.ContractResources;
import com.hedera.services.bdd.suites.HapiApiSuite;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ServicesConfigurationList;
import com.hederahashgraph.api.proto.java.Setting;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.fee.FeeObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.services.bdd.spec.HapiApiSpec.defaultHapiSpec;
import static com.hedera.services.bdd.spec.assertions.AccountInfoAsserts.changeFromSnapshot;
import static com.hedera.services.bdd.spec.assertions.AssertUtils.inOrder;
import static com.hedera.services.bdd.spec.assertions.TransactionRecordAsserts.recordWith;
import static com.hedera.services.bdd.spec.assertions.TransferListAsserts.includingDeduction;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountBalance;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getAccountRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getContractRecords;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getFileContents;
import static com.hedera.services.bdd.spec.queries.QueryVerbs.getTxnRecord;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCall;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.contractCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.createTopic;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.cryptoTransfer;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileCreate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.fileUpdate;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.submitMessageTo;
import static com.hedera.services.bdd.spec.transactions.TxnVerbs.uncheckedSubmit;
import static com.hedera.services.bdd.spec.transactions.crypto.HapiCryptoTransfer.tinyBarsFromTo;
import static com.hedera.services.bdd.spec.utilops.CustomSpecAssert.allRunFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.assertionsHold;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.balanceSnapshot;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sleepFor;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.sourcing;
import static com.hedera.services.bdd.spec.utilops.UtilVerbs.usableTxnIdNamed;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static org.junit.Assert.assertEquals;

public class RecordCreationSuite extends HapiApiSuite {
	private static final Logger log = LogManager.getLogger(RecordCreationSuite.class);

	private static final long SLEEP_MS = 1_000L;
	private static final String defaultRecordsTtl = HapiSpecSetup.getDefaultNodeProps().get("cache.records.ttl");

	public static void main(String... args) {
		new RecordCreationSuite().runSuiteSync();
	}

	@Override
	protected List<HapiApiSpec> getSpecsInSuite() {
		return List.of(
				new HapiApiSpec[] {
						payerRecordCreationSanityChecks(),
						newlyCreatedContractNoLongerGetsRecord(),
						accountsGetPayerRecordsIfSoConfigured(),
						calledContractNoLongerGetsRecord(),
						thresholdRecordsDontExistAnymore(),
						submittingNodeChargedNetworkFeeForLackOfDueDiligence(),
						submittingNodeChargedNetworkFeeForIgnoringPayerUnwillingness(),
						submittingNodeStillPaidIfServiceFeesOmitted(),

						/* This last spec requires sleeping for the default TTL (180s) so that the
						expiration queue will be purged of all entries for existing records.

						Especially since we are _very_ unlikely to make a dynamic change to
						cache.records.ttl in practice, this test is not worth running in CircleCI.

						However, it is a good sanity check to have available locally when making
						changes to record expiration.  */
//						recordsTtlChangesAsExpected(),
				}
		);
	}

	private HapiApiSpec submittingNodeStillPaidIfServiceFeesOmitted() {
		final String comfortingMemo = "This is ok, it's fine, it's whatever.";
		final AtomicReference<FeeObject> feeObs = new AtomicReference<>();

		return defaultHapiSpec("submittingNodeStillPaidIfServiceFeesOmitted")
				.given(
						cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.3", ONE_HBAR))
								.payingWith(GENESIS),
						cryptoCreate("payer"),
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1L)
						)
								.memo(comfortingMemo)
								.exposingFeesTo(feeObs)
								.payingWith("payer")
				).when(
						balanceSnapshot("before", "0.0.3"),
						balanceSnapshot("fundingBefore", "0.0.98"),
						sourcing(() ->
								cryptoTransfer(
										tinyBarsFromTo(GENESIS, FUNDING, 1L)
								)
										.memo(comfortingMemo)
										.fee(feeObs.get().getNetworkFee() + feeObs.get().getNodeFee())
										.payingWith("payer")
										.via("txnId")
										.hasKnownStatus(INSUFFICIENT_TX_FEE)
						)
				).then(
						sourcing(() ->
								getAccountBalance("0.0.3")
										.hasTinyBars(
												changeFromSnapshot("before", +feeObs.get().getNodeFee()))),
						sourcing(() ->
								getAccountBalance("0.0.98")
										.hasTinyBars(
												changeFromSnapshot("fundingBefore", +feeObs.get().getNetworkFee()))),
						sourcing(() ->
								getTxnRecord("txnId")
										.assertingNothingAboutHashes()
										.hasPriority(recordWith()
												.transfers(includingDeduction(
														"payer",
														feeObs.get().getNetworkFee() + feeObs.get().getNodeFee()))
												.status(INSUFFICIENT_TX_FEE))
										.logged())
				);
	}

	private HapiApiSpec submittingNodeChargedNetworkFeeForLackOfDueDiligence() {
		final String comfortingMemo = "This is ok, it's fine, it's whatever.";
		final String disquietingMemo = "\u0000his is ok, it's fine, it's whatever.";
		final AtomicReference<FeeObject> feeObs = new AtomicReference<>();

		return defaultHapiSpec("SubmittingNodeChargedNetworkFeeForLackOfDueDiligence")
				.given(
						cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.3", ONE_HBAR))
								.payingWith(GENESIS),
						cryptoCreate("payer"),
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1L)
						)
								.memo(comfortingMemo)
								.exposingFeesTo(feeObs)
								.payingWith("payer"),
						usableTxnIdNamed("txnId")
								.payerId("payer")
				).when(
						balanceSnapshot("before", "0.0.3"),
						balanceSnapshot("fundingBefore", "0.0.98"),
						uncheckedSubmit(
								cryptoTransfer(
										tinyBarsFromTo(GENESIS, FUNDING, 1L)
								)
										.memo(disquietingMemo)
										.payingWith("payer")
										.txnId("txnId")
						)
								.payingWith(GENESIS),
						sleepFor(SLEEP_MS)
				).then(
						sourcing(() ->
								getAccountBalance("0.0.3")
										.hasTinyBars(
												changeFromSnapshot("before", -feeObs.get().getNetworkFee()))),
						sourcing(() ->
								getAccountBalance("0.0.98")
										.hasTinyBars(
												changeFromSnapshot("fundingBefore", +feeObs.get().getNetworkFee()))),
						sourcing(() ->
								getTxnRecord("txnId")
										.assertingNothingAboutHashes()
										.hasPriority(recordWith()
												.transfers(includingDeduction(() -> 3L, feeObs.get().getNetworkFee()))
												.status(INVALID_ZERO_BYTE_IN_STRING))
										.logged())
				);
	}

	private HapiApiSpec submittingNodeChargedNetworkFeeForIgnoringPayerUnwillingness() {
		final String comfortingMemo = "This is ok, it's fine, it's whatever.";
		final AtomicReference<FeeObject> feeObs = new AtomicReference<>();

		return defaultHapiSpec("SubmittingNodeChargedNetworkFeeForIgnoringPayerUnwillingness")
				.given(
						cryptoTransfer(tinyBarsFromTo(GENESIS, "0.0.3", ONE_HBAR))
								.payingWith(GENESIS),
						cryptoCreate("payer"),
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1L)
						)
								.memo(comfortingMemo)
								.exposingFeesTo(feeObs)
								.payingWith("payer"),
						usableTxnIdNamed("txnId")
								.payerId("payer")
				).when(
						balanceSnapshot("before", "0.0.3"),
						balanceSnapshot("fundingBefore", "0.0.98"),
						sourcing(() ->
								uncheckedSubmit(
										cryptoTransfer(
												tinyBarsFromTo(GENESIS, FUNDING, 1L)
										)
												.memo(comfortingMemo)
												.fee(feeObs.get().getNetworkFee() - 1L)
												.payingWith("payer")
												.txnId("txnId")
								)
										.payingWith(GENESIS)
						),
						sleepFor(SLEEP_MS)
				).then(
						sourcing(() ->
								getAccountBalance("0.0.3")
										.hasTinyBars(
												changeFromSnapshot("before", -feeObs.get().getNetworkFee()))),
						sourcing(() ->
								getAccountBalance("0.0.98")
										.hasTinyBars(
												changeFromSnapshot("fundingBefore", +feeObs.get().getNetworkFee()))),
						sourcing(() ->
								getTxnRecord("txnId")
										.assertingNothingAboutHashes()
										.hasPriority(recordWith()
												.transfers(includingDeduction(() -> 3L, feeObs.get().getNetworkFee()))
												.status(INSUFFICIENT_TX_FEE))
										.logged())
				);
	}


	private HapiApiSpec payerRecordCreationSanityChecks() {
		return defaultHapiSpec("PayerRecordCreationSanityChecks")
				.given(
						cryptoCreate("payer")
				).when(
						createTopic("ofGeneralInterest").payingWith("payer"),
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1_000L)
						).payingWith("payer"),
						submitMessageTo("ofGeneralInterest")
								.message("I say!")
								.payingWith("payer")
				).then(
						assertionsHold((spec, opLog) -> {
							final var payerId = spec.registry().getAccountID("payer");
							final var subOp = getAccountRecords("payer").logged();
							allRunFor(spec, subOp);
							final var records = subOp.getResponse().getCryptoGetAccountRecords().getRecordsList();
							assertEquals(3, records.size());
							for (var record : records) {
								assertEquals(record.getTransactionFee(), -netChangeIn(record, payerId));
							}
						})
				);
	}

	private long netChangeIn(TransactionRecord record, AccountID id) {
		return record.getTransferList().getAccountAmountsList().stream()
				.filter(aa -> id.equals(aa.getAccountID()))
				.mapToLong(AccountAmount::getAmount)
				.sum();
	}

	private HapiApiSpec accountsGetPayerRecordsIfSoConfigured() {
		return defaultHapiSpec("AccountsGetPayerRecordsIfSoConfigured")
				.given(
						cryptoCreate("payer"),
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of("ledger.keepRecordsInState", "false"))
				).when(
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of("ledger.keepRecordsInState", "false")),
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1_000L)
						).payingWith("payer").via("firstXfer"),
						getAccountRecords("payer").has(inOrder()),
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of("ledger.keepRecordsInState", "true"))
				).then(
						cryptoTransfer(
								tinyBarsFromTo(GENESIS, FUNDING, 1_000L)
						).payingWith("payer").via("secondXfer"),
						getAccountRecords("payer").has(inOrder(recordWith().txnId("secondXfer")))
				);
	}

	private HapiApiSpec calledContractNoLongerGetsRecord() {
		return defaultHapiSpec("CalledContractNoLongerGetsRecord")
				.given(
						fileCreate("bytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH)
				).when(
						contractCreate("contract").bytecode("bytecode").via("createTxn"),
						contractCall("contract", ContractResources.DEPOSIT_ABI, 1_000L).via("callTxn").sending(1_000L)
				).then(
						getContractRecords("contract").has(inOrder())
				);
	}

	private HapiApiSpec newlyCreatedContractNoLongerGetsRecord() {
		return defaultHapiSpec("NewlyCreatedContractNoLongerGetsRecord")
				.given(
						fileCreate("bytecode").path(ContractResources.PAYABLE_CONTRACT_BYTECODE_PATH)
				).when(
						contractCreate("contract").bytecode("bytecode").via("createTxn")
				).then(
						getContractRecords("contract").has(inOrder())
				);
	}

	private HapiApiSpec thresholdRecordsDontExistAnymore() {
		return defaultHapiSpec("OnlyNetAdjustmentIsComparedToThresholdWhenCreating")
				.given(
						cryptoCreate("payer"),
						cryptoCreate("lowSendThreshold").sendThreshold(1L),
						cryptoCreate("lowReceiveThreshold").receiveThreshold(1L),
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of(
										"ledger.keepRecordsInState", "true"
								))
				).when(
						cryptoTransfer(
								tinyBarsFromTo(
										"lowSendThreshold",
										"lowReceiveThreshold",
										2L)
						).payingWith("payer").via("testTxn")
				).then(
						getAccountRecords("payer").has(inOrder(recordWith().txnId("testTxn"))),
						getAccountRecords("lowSendThreshold").has(inOrder()),
						getAccountRecords("lowReceiveThreshold").has(inOrder()),
						fileUpdate(APP_PROPERTIES)
								.payingWith(ADDRESS_BOOK_CONTROL)
								.overridingProps(Map.of(
										"ledger.keepRecordsInState", "false"
								))
				);
	}

	private HapiApiSpec recordsTtlChangesAsExpected() {
		final int abbrevCacheTtl = 3;
		final String brieflyAvailMemo = "I can't stay for long...";
		final AtomicReference<byte[]> origPropContents = new AtomicReference<>();

		return defaultHapiSpec("RecordsTtlChangesAsExpected")
				.given(
						getFileContents(APP_PROPERTIES)
								.consumedBy(origPropContents::set),
						sleepFor((Long.parseLong(defaultRecordsTtl) + 1) * 1_000L),
						sourcing(() ->
								fileUpdate(APP_PROPERTIES)
										.fee(ONE_HUNDRED_HBARS)
										.contents(rawConfigPlus(
												origPropContents.get(),
												"cache.records.ttl",
												"" + abbrevCacheTtl))
										.payingWith(GENESIS)
						),
						cryptoCreate("payer")
				).when(
						cryptoTransfer(tinyBarsFromTo("payer", ADDRESS_BOOK_CONTROL, 1L))
								.memo(brieflyAvailMemo)
								.payingWith("payer"),
						getAccountRecords("payer").has(inOrder(recordWith().memo(brieflyAvailMemo))),
						sleepFor(abbrevCacheTtl * 1_000L),
						cryptoTransfer(tinyBarsFromTo(GENESIS, ADDRESS_BOOK_CONTROL, 1L))
								.payingWith(GENESIS),
						getAccountRecords("payer").has(inOrder())
				).then(
						sourcing(() ->
								fileUpdate(APP_PROPERTIES)
										.contents(origPropContents.get()))
				);
	}

	private byte[] rawConfigPlus(byte[] rawBase, String extraName, String extraValue) {
		try {
			final var rawConfig = ServicesConfigurationList.parseFrom(rawBase);
			return rawConfig.toBuilder().addNameValue(Setting.newBuilder()
					.setName(extraName)
					.setValue(extraValue)
			).build().toByteArray();
		} catch (InvalidProtocolBufferException e) {
			throw new IllegalStateException("Existing 0.0.121 wasn't valid protobuf!", e);
		}
	}


	@Override
	protected Logger getResultsLogger() {
		return log;
	}
}
