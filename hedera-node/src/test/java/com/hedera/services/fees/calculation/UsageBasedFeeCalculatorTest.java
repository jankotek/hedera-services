package com.hedera.services.fees.calculation;

/*-
 * ‌
 * Hedera Services Node
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

import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeMultiplierSource;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.calculation.utils.PricedUsageCalculator;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.usage.state.UsageAccumulator;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.factories.keys.KeyTree;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.exception.InvalidTxBodyException;
import com.hederahashgraph.fee.FeeBuilder;
import com.hederahashgraph.fee.FeeObject;
import com.hederahashgraph.fee.SigValueObj;
import org.apache.commons.lang3.tuple.Triple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatcher;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static com.hedera.services.fees.calculation.AwareFcfsUsagePrices.DEFAULT_USAGE_PRICES;
import static com.hedera.test.factories.txns.ContractCallFactory.newSignedContractCall;
import static com.hedera.test.factories.txns.ContractCreateFactory.newSignedContractCreate;
import static com.hedera.test.factories.txns.CryptoCreateFactory.newSignedCryptoCreate;
import static com.hedera.test.factories.txns.CryptoTransferFactory.newSignedCryptoTransfer;
import static com.hedera.test.factories.txns.FileCreateFactory.newSignedFileCreate;
import static com.hedera.test.factories.txns.TinyBarsFromTo.tinyBarsFromTo;
import static com.hedera.test.utils.IdUtils.asAccountString;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoAccountAutoRenew;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;
import static com.hederahashgraph.fee.FeeBuilder.FEE_DIVISOR_FACTOR;
import static com.hederahashgraph.fee.FeeBuilder.HRS_DIVISOR;
import static com.hederahashgraph.fee.FeeBuilder.getTinybarsFromTinyCents;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.willThrow;

public class UsageBasedFeeCalculatorTest {
	private FeeComponents mockFees = FeeComponents.newBuilder()
			.setMax(1_234_567L)
			.setGas(5_000_000L)
			.setBpr(1_000_000L)
			.setBpt(2_000_000L)
			.setRbh(3_000_000L)
			.setSbh(4_000_000L).build();
	private FeeData mockFeeData = FeeData.newBuilder()
			.setNetworkdata(mockFees).setNodedata(mockFees).setServicedata(mockFees).setSubType(SubType.DEFAULT).build();
	private Map<SubType, FeeData> currentPrices = Map.of(SubType.DEFAULT, mockFeeData);
	private FeeData defaultCurrentPrices = mockFeeData;
	private FeeData resourceUsage = mockFeeData;
	private ExchangeRate currentRate = ExchangeRate.newBuilder().setCentEquiv(22).setHbarEquiv(1).build();
	private Query query;
	private StateView view;
	private Timestamp at = Timestamp.newBuilder().setSeconds(1_234_567L).build();
	private HbarCentExchange exchange;
	private UsagePricesProvider usagePrices;
	private TxnResourceUsageEstimator correctOpEstimator;
	private TxnResourceUsageEstimator incorrectOpEstimator;
	private QueryResourceUsageEstimator correctQueryEstimator;
	private QueryResourceUsageEstimator incorrectQueryEstimator;
	private Function<HederaFunctionality, List<TxnResourceUsageEstimator>> txnUsageEstimators;
	private long balance = 1_234_567L;
	private AccountID payer = IdUtils.asAccount("0.0.75231");
	private AccountID receiver = IdUtils.asAccount("0.0.86342");

	/* Has nine simple keys. */
	private KeyTree complexKey = TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
	private JKey payerKey;
	private Transaction signedTxn;
	private SignedTxnAccessor accessor;
	private AutoRenewCalcs autoRenewCalcs;
	private PricedUsageCalculator pricedUsageCalculator;

	private AtomicLong suggestedMultiplier = new AtomicLong(1L);

	private UsageBasedFeeCalculator subject;

	@BeforeEach
	private void setup() throws Throwable {
		view = mock(StateView.class);
		query = mock(Query.class);
		payerKey = complexKey.asJKey();
		exchange = mock(HbarCentExchange.class);
		signedTxn = newSignedCryptoCreate()
				.balance(balance)
				.payerKt(complexKey)
				.txnValidStart(at)
				.get();
		accessor = new SignedTxnAccessor(signedTxn);
		usagePrices = mock(UsagePricesProvider.class);
		given(usagePrices.activePrices()).willReturn(currentPrices);
		correctOpEstimator = mock(TxnResourceUsageEstimator.class);
		incorrectOpEstimator = mock(TxnResourceUsageEstimator.class);
		correctQueryEstimator = mock(QueryResourceUsageEstimator.class);
		incorrectQueryEstimator = mock(QueryResourceUsageEstimator.class);
		autoRenewCalcs = mock(AutoRenewCalcs.class);
		pricedUsageCalculator = mock(PricedUsageCalculator.class);

		txnUsageEstimators = (Function<HederaFunctionality, List<TxnResourceUsageEstimator>>) mock(Function.class);

		subject = new UsageBasedFeeCalculator(
				autoRenewCalcs,
				exchange,
				usagePrices,
				new NestedMultiplierSource(),
				pricedUsageCalculator,
				List.of(incorrectQueryEstimator, correctQueryEstimator),
				txnUsageEstimators);
	}

	@Test
	void delegatesAutoRenewCalcs() {
		// setup:
		final var expected = new AutoRenewCalcs.RenewAssessment(456L, 123L);

		given(autoRenewCalcs.maxRenewalAndFeeFor(any(), anyLong(), any(), any())).willReturn(expected);

		// when:
		var actual = subject.assessCryptoAutoRenewal(new MerkleAccount(), 1L, Instant.ofEpochSecond(2L));

		// then:
		assertSame(expected, actual);
	}

	@Test
	void estimatesContractCallPayerBalanceChanges() throws Throwable {
		// setup:
		long gas = 1_234L, sent = 5_432L;
		signedTxn = newSignedContractCall()
				.payer(asAccountString(payer))
				.gas(gas)
				.sending(sent)
				.txnValidStart(at)
				.get();
		accessor = new SignedTxnAccessor(signedTxn);

		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.defaultPricesGiven(ContractCall, at)).willReturn(defaultCurrentPrices);
		// and:
		long expectedGasPrice =
				getTinybarsFromTinyCents(currentRate, mockFees.getGas() / FEE_DIVISOR_FACTOR);

		// expect:
		assertEquals(-(gas * expectedGasPrice + sent), subject.estimatedNonFeePayerAdjustments(accessor, at));
	}

	@Test
	void estimatesCryptoCreatePayerBalanceChanges() throws Throwable {
		// expect:
		assertEquals(-balance, subject.estimatedNonFeePayerAdjustments(accessor, at));
	}

	@Test
	void estimatesContractCreatePayerBalanceChanges() throws Throwable {
		// setup:
		long gas = 1_234L, initialBalance = 5_432L;
		signedTxn = newSignedContractCreate()
				.payer(asAccountString(payer))
				.gas(gas)
				.initialBalance(initialBalance)
				.txnValidStart(at)
				.get();
		accessor = new SignedTxnAccessor(signedTxn);

		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.pricesGiven(ContractCreate, at)).willReturn(currentPrices);
		given(usagePrices.defaultPricesGiven(ContractCreate, at)).willReturn(defaultCurrentPrices);
		// and:
		long expectedGasPrice =
				getTinybarsFromTinyCents(currentRate, mockFees.getGas() / FEE_DIVISOR_FACTOR);

		// expect:
		assertEquals(-(gas * expectedGasPrice + initialBalance), subject.estimatedNonFeePayerAdjustments(accessor, at));
	}

	@Test
	void estimatesMiscNoNetChange() throws Throwable {
		// setup:
		signedTxn = newSignedFileCreate()
				.payer(asAccountString(payer))
				.txnValidStart(at)
				.get();
		accessor = new SignedTxnAccessor(signedTxn);

		// expect:
		assertEquals(0L, subject.estimatedNonFeePayerAdjustments(accessor, at));
	}

	@Test
	void estimatesCryptoTransferPayerBalanceChanges() throws Throwable {
		// setup:
		long sent = 1_234L;
		signedTxn = newSignedCryptoTransfer()
				.payer(asAccountString(payer))
				.transfers(tinyBarsFromTo(asAccountString(payer), asAccountString(receiver), sent))
				.txnValidStart(at)
				.get();
		accessor = new SignedTxnAccessor(signedTxn);

		// expect:
		assertEquals(-sent, subject.estimatedNonFeePayerAdjustments(accessor, at));
	}

	@Test
	void estimatesFutureGasPriceInTinybars() {
		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.pricesGiven(CryptoCreate, at)).willReturn(currentPrices);
		given(usagePrices.defaultPricesGiven(CryptoCreate, at)).willReturn(defaultCurrentPrices);
		// and:
		long expected = getTinybarsFromTinyCents(currentRate, mockFees.getGas() / FEE_DIVISOR_FACTOR);

		// when:
		long actual = subject.estimatedGasPriceInTinybars(CryptoCreate, at);

		// then:
		assertEquals(expected, actual);
	}

	@Test
	void computesActiveGasPriceInTinybars() {
		given(exchange.activeRate()).willReturn(currentRate);
		given(usagePrices.defaultActivePrices()).willReturn(defaultCurrentPrices);
		// and:
		long expected = getTinybarsFromTinyCents(currentRate, mockFees.getGas() / FEE_DIVISOR_FACTOR);

		// when:
		long actual = subject.activeGasPriceInTinybars();

		// then:
		assertEquals(expected, actual);
	}

	@Test
	void loadPriceSchedulesOnInit() {
		// setup:
		final var seq = Triple.of(Map.of(SubType.DEFAULT, FeeData.getDefaultInstance()), Instant.now(), Map.of(SubType.DEFAULT, FeeData.getDefaultInstance()));

		given(usagePrices.activePricingSequence(CryptoAccountAutoRenew)).willReturn(seq);

		// when:
		subject.init();

		// expect:
		verify(usagePrices).loadPriceSchedules();
		verify(autoRenewCalcs).setCryptoAutoRenewPriceSeq(seq);
	}

	@Test
	void throwsIseOnBadScheduleInFcfs() {
		willThrow(IllegalStateException.class).given(usagePrices).loadPriceSchedules();

		// expect:
		assertThrows(IllegalStateException.class, () -> subject.init());
	}

	@Test
	void failsWithIseGivenApplicableButUnusableCalculator() throws InvalidTxBodyException {
		// setup:
		SigValueObj expectedSigUsage = new SigValueObj(
				FeeBuilder.getSignatureCount(signedTxn),
				9,
				FeeBuilder.getSignatureSize(signedTxn));

		given(correctOpEstimator.applicableTo(accessor.getTxn())).willReturn(true);
		given(txnUsageEstimators.apply(CryptoCreate)).willReturn(List.of(correctOpEstimator));
		given(correctOpEstimator.usageGiven(
				argThat(accessor.getTxn()::equals),
				argThat(factory.apply(expectedSigUsage)),
				argThat(view::equals))).willThrow(InvalidTxBodyException.class);

		// when:
		assertThrows(IllegalArgumentException.class, () -> subject.computeFee(accessor, payerKey, view));
	}

	@Test
	void failsWithNseeSansApplicableUsageCalculator() {
		// expect:
		assertThrows(NoSuchElementException.class, () -> subject.computeFee(accessor, payerKey, view));
		assertThrows(NoSuchElementException.class,
				() -> subject.computePayment(query, currentPrices.get(SubType.DEFAULT), view, at, Collections.emptyMap()));
	}

	@Test
	void invokesQueryDelegateAsExpected() {
		// setup:
		FeeObject expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(correctQueryEstimator.applicableTo(query)).willReturn(true);
		given(incorrectQueryEstimator.applicableTo(query)).willReturn(false);
		given(correctQueryEstimator.usageGiven(
				argThat(query::equals),
				argThat(view::equals),
				any())).willReturn(resourceUsage);
		given(incorrectQueryEstimator.usageGiven(any(), any())).willThrow(RuntimeException.class);
		given(exchange.rate(at)).willReturn(currentRate);

		// when:
		FeeObject fees = subject.computePayment(query, currentPrices.get(SubType.DEFAULT), view, at, Collections.emptyMap());

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void invokesQueryDelegateByTypeAsExpected() {
		// setup:
		FeeObject expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(correctQueryEstimator.applicableTo(query)).willReturn(true);
		given(incorrectQueryEstimator.applicableTo(query)).willReturn(false);
		given(correctQueryEstimator.usageGivenType(query, view, ANSWER_ONLY)).willReturn(resourceUsage);
		given(incorrectQueryEstimator.usageGivenType(any(), any(), any())).willThrow(RuntimeException.class);
		given(exchange.rate(at)).willReturn(currentRate);

		// when:
		FeeObject fees = subject.estimatePayment(query, currentPrices.get(SubType.DEFAULT), view, at, ANSWER_ONLY);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void usesMultiplierAsExpected() throws Exception {
		// setup:
		long multiplier = 5L;
		SigValueObj expectedSigUsage = new SigValueObj(
				FeeBuilder.getSignatureCount(signedTxn),
				9,
				FeeBuilder.getSignatureSize(signedTxn));
		FeeObject expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate, multiplier);
		suggestedMultiplier.set(multiplier);

		given(correctOpEstimator.applicableTo(accessor.getTxn())).willReturn(true);
		given(txnUsageEstimators.apply(CryptoCreate)).willReturn(List.of(correctOpEstimator));
		given(correctOpEstimator.usageGiven(
				argThat(accessor.getTxn()::equals),
				argThat(factory.apply(expectedSigUsage)),
				argThat(view::equals))).willReturn(resourceUsage);
		given(exchange.activeRate()).willReturn(currentRate);

		// when:
		FeeObject fees = subject.computeFee(accessor, payerKey, view);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void invokesOpDelegateAsExpectedWithOneOption() throws Exception {
		// setup:
		SigValueObj expectedSigUsage = new SigValueObj(
				FeeBuilder.getSignatureCount(signedTxn),
				9,
				FeeBuilder.getSignatureSize(signedTxn));
		FeeObject expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(correctOpEstimator.applicableTo(accessor.getTxn())).willReturn(true);
		given(txnUsageEstimators.apply(CryptoCreate)).willReturn(List.of(correctOpEstimator));
		given(correctOpEstimator.usageGiven(
				argThat(accessor.getTxn()::equals),
				argThat(factory.apply(expectedSigUsage)),
				argThat(view::equals))).willReturn(resourceUsage);
		given(exchange.activeRate()).willReturn(currentRate);

		// when:
		FeeObject fees = subject.computeFee(accessor, payerKey, view);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void invokesAccessorBasedUsagesForCryptoTransferOutsideHandleWithNewAccumulator() throws Throwable {
		// setup:
		long sent = 1_234L;
		signedTxn = newSignedCryptoTransfer()
				.payer(asAccountString(payer))
				.transfers(tinyBarsFromTo(asAccountString(payer), asAccountString(receiver), sent))
				.txnValidStart(at)
				.get();
		accessor = SignedTxnAccessor.uncheckedFrom(signedTxn);
		// and:
		final var expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(pricedUsageCalculator.supports(CryptoTransfer)).willReturn(true);
		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.pricesGiven(CryptoTransfer, at)).willReturn(currentPrices);
		given(pricedUsageCalculator.extraHandleFees(
				accessor,
				currentPrices.get(SubType.DEFAULT),
				currentRate,
				payerKey
		)).willReturn(expectedFees);

		// when:
		FeeObject fees = subject.estimateFee(accessor, payerKey, view, at);

		// then:
		assertNotNull(fees);
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void invokesAccessorBasedUsagesForCryptoTransferInHandleWithReusedAccumulator() throws Throwable {
		// setup:
		long sent = 1_234L;
		signedTxn = newSignedCryptoTransfer()
				.payer(asAccountString(payer))
				.transfers(tinyBarsFromTo(asAccountString(payer), asAccountString(receiver), sent))
				.txnValidStart(at)
				.get();
		accessor = SignedTxnAccessor.uncheckedFrom(signedTxn);
		// and:
		final var expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(pricedUsageCalculator.supports(CryptoTransfer)).willReturn(true);
		given(exchange.activeRate()).willReturn(currentRate);
		given(pricedUsageCalculator.inHandleFees(
				accessor,
				currentPrices.get(SubType.DEFAULT),
				currentRate,
				payerKey
		)).willReturn(expectedFees);

		// when:
		FeeObject fees = subject.computeFee(accessor, payerKey, view);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	@Test
	void invokesOpDelegateAsExpectedWithTwoOptions() throws Exception {
		// setup:
		SigValueObj expectedSigUsage = new SigValueObj(
				FeeBuilder.getSignatureCount(signedTxn),
				9,
				FeeBuilder.getSignatureSize(signedTxn));
		FeeObject expectedFees = FeeBuilder.getFeeObject(currentPrices.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(correctOpEstimator.applicableTo(accessor.getTxn())).willReturn(true);
		given(incorrectOpEstimator.applicableTo(accessor.getTxn())).willReturn(false);
		given(txnUsageEstimators.apply(CryptoCreate)).willReturn(List.of(incorrectOpEstimator, correctOpEstimator));
		given(correctOpEstimator.usageGiven(
				argThat(accessor.getTxn()::equals),
				argThat(factory.apply(expectedSigUsage)),
				argThat(view::equals))).willReturn(resourceUsage);
		given(incorrectOpEstimator.usageGiven(any(), any(), any())).willThrow(RuntimeException.class);
		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.activePrices()).willThrow(RuntimeException.class);
		given(usagePrices.pricesGiven(CryptoCreate, at)).willReturn(currentPrices);

		// when:
		FeeObject fees = subject.estimateFee(accessor, payerKey, view, at);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}


	@Test
	void invokesOpDelegateAsExpectedForEstimateOfUnrecognizable() throws Exception {
		// setup:
		SigValueObj expectedSigUsage = new SigValueObj(
				FeeBuilder.getSignatureCount(signedTxn),
				9,
				FeeBuilder.getSignatureSize(signedTxn));
		FeeObject expectedFees = FeeBuilder.getFeeObject(DEFAULT_USAGE_PRICES.get(SubType.DEFAULT), resourceUsage, currentRate);

		given(txnUsageEstimators.apply(CryptoCreate)).willReturn(List.of(correctOpEstimator));
		given(correctOpEstimator.applicableTo(accessor.getTxn())).willReturn(true);
		given(correctOpEstimator.usageGiven(
				argThat(accessor.getTxn()::equals),
				argThat(factory.apply(expectedSigUsage)),
				argThat(view::equals))).willReturn(resourceUsage);
		given(exchange.rate(at)).willReturn(currentRate);
		given(usagePrices.pricesGiven(CryptoCreate, at)).willThrow(RuntimeException.class);

		// when:
		FeeObject fees = subject.estimateFee(accessor, payerKey, view, at);

		// then:
		assertEquals(fees.getNodeFee(), expectedFees.getNodeFee());
		assertEquals(fees.getNetworkFee(), expectedFees.getNetworkFee());
		assertEquals(fees.getServiceFee(), expectedFees.getServiceFee());
	}

	private Function<SigValueObj, ArgumentMatcher<SigValueObj>> factory = expectedSigUsage -> sigUsage ->
			expectedSigUsage.getSignatureSize() == sigUsage.getSignatureSize()
					&& expectedSigUsage.getPayerAcctSigCount() == sigUsage.getPayerAcctSigCount()
					&& expectedSigUsage.getSignatureSize() == sigUsage.getSignatureSize();

	private class NestedMultiplierSource implements FeeMultiplierSource {
		@Override
		public long currentMultiplier() {
			return suggestedMultiplier.get();
		}

		@Override
		public void resetExpectations() {
			/* No-op */
		}

		@Override
		public void updateMultiplier(Instant consensusNow) {
			/* No-op. */
		}

		@Override
		public void resetCongestionLevelStarts(Instant[] savedStartTimes) {
			/* No-op. */
		}

		@Override
		public Instant[] congestionLevelStarts() {
			return new Instant[0];
		}
	}

	public static void copyData(FeeData feeData, UsageAccumulator into) {
		into.setNumPayerKeys(feeData.getNodedata().getVpt());
		into.addVpt(feeData.getNetworkdata().getVpt());
		into.addBpt(feeData.getNetworkdata().getBpt());
		into.addBpr(feeData.getNodedata().getBpr());
		into.addSbpr(feeData.getNodedata().getSbpr());
		into.addNetworkRbs(feeData.getNetworkdata().getRbh() * HRS_DIVISOR);
		into.addRbs(feeData.getServicedata().getRbh() * HRS_DIVISOR);
		into.addSbs(feeData.getServicedata().getSbh() * HRS_DIVISOR);
	}
}
