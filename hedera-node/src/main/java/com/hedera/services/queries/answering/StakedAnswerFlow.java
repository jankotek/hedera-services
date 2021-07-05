package com.hedera.services.queries.answering;

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

import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.queries.AnswerFlow;
import com.hedera.services.queries.AnswerService;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.throttling.FunctionalityThrottling;
import com.hedera.services.txns.submission.PlatformSubmissionManager;
import com.hedera.services.txns.submission.SystemPrecheck;
import com.hedera.services.txns.submission.TransactionPrecheck;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.Response;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.fee.FeeObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

import static com.hedera.services.utils.MiscUtils.asTimestamp;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BUSY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;

public class StakedAnswerFlow implements AnswerFlow {
	private static final Logger log = LogManager.getLogger(StakedAnswerFlow.class);

	private LongPredicate isThrottleExempt = SystemPrecheck.IS_THROTTLE_EXEMPT;
	private Supplier<Instant> now = Instant::now;

	private final FeeCalculator fees;
	private final QueryFeeCheck queryFeeCheck;
	private final HapiOpPermissions hapiOpPermissions;
	private final Supplier<StateView> stateViews;
	private final UsagePricesProvider resourceCosts;
	private final QueryHeaderValidity queryHeaderValidity;
	private final TransactionPrecheck transactionPrecheck;
	private final FunctionalityThrottling throttles;
	private final PlatformSubmissionManager submissionManager;

	public StakedAnswerFlow(
			FeeCalculator fees,
			Supplier<StateView> stateViews,
			UsagePricesProvider resourceCosts,
			FunctionalityThrottling throttles,
			PlatformSubmissionManager submissionManager,
			QueryHeaderValidity queryHeaderValidity,
			TransactionPrecheck transactionPrecheck,
			HapiOpPermissions hapiOpPermissions,
			QueryFeeCheck queryFeeCheck
	) {
		this.fees = fees;
		this.queryFeeCheck = queryFeeCheck;
		this.throttles = throttles;
		this.stateViews = stateViews;
		this.resourceCosts = resourceCosts;
		this.submissionManager = submissionManager;
		this.hapiOpPermissions = hapiOpPermissions;
		this.queryHeaderValidity = queryHeaderValidity;
		this.transactionPrecheck = transactionPrecheck;
	}

	@Override
	public Response satisfyUsing(AnswerService service, Query query) {
		final var view = stateViews.get();
		final var headerStatus = queryHeaderValidity.checkHeader(query);
		if (headerStatus != OK) {
			return service.responseGiven(query, view, headerStatus);
		}

		Optional<SignedTxnAccessor> optionalPayment = Optional.empty();
		final var allegedPayment = service.extractPaymentFrom(query);
		final var isPaymentRequired = service.requiresNodePayment(query);
		if (isPaymentRequired && allegedPayment.isPresent()) {
			final var signedTxn = allegedPayment.get().getSignedTxnWrapper();
			final var paymentCheck = transactionPrecheck.performForQueryPayment(signedTxn);
			final var paymentStatus = paymentCheck.getLeft().getValidity();
			if (paymentStatus != OK) {
				return service.responseGiven(query, view, paymentStatus);
			} else {
				optionalPayment = paymentCheck.getRight();
			}
		}

		final var hygieneStatus = hygieneCheck(query, view, service, optionalPayment);
		if (hygieneStatus != OK) {
			return service.responseGiven(query, view, hygieneStatus);
		}

		final var bestGuessNow = optionalPayment
				.map(a -> a.getTxnId().getTransactionValidStart())
				.orElse(asTimestamp(now.get()));
		final var usagePrices = resourceCosts.pricesGiven(service.canonicalFunction(), bestGuessNow);

		long fee = 0L;
		final Map<String, Object> queryCtx = new HashMap<>();
		if (isPaymentRequired) {
			/* The hygiene check would have aborted if we were missing a payment. */
			final var payment = optionalPayment.get();
			fee = totalOf(fees.computePayment(query, usagePrices, view, bestGuessNow, queryCtx));
			final var paymentStatus = tryToPay(payment, fee);
			if (paymentStatus != OK) {
				return service.responseGiven(query, view, paymentStatus, fee);
			}
		}

		if (service.needsAnswerOnlyCost(query)) {
			fee = totalOf(fees.estimatePayment(query, usagePrices, view, bestGuessNow, ANSWER_ONLY));
		}

		return service.responseGiven(query, view, OK, fee, queryCtx);
	}

	private ResponseCodeEnum tryToPay(SignedTxnAccessor payment, long fee) {
		final var xfers = payment.getTxn().getCryptoTransfer().getTransfers().getAccountAmountsList();
		final var feeStatus = queryFeeCheck.nodePaymentValidity(xfers, fee, payment.getTxn().getNodeAccountID());
		if (feeStatus != OK) {
			return feeStatus;
		}
		return submissionManager.trySubmission(payment);
	}

	private ResponseCodeEnum hygieneCheck(
			Query query,
			StateView view,
			AnswerService service,
			Optional<SignedTxnAccessor> optionalPayment
	) {
		final var isPaymentRequired = service.requiresNodePayment(query);
		if (isPaymentRequired && optionalPayment.isEmpty()) {
			return INSUFFICIENT_TX_FEE;
		}

		final var screenStatus = systemScreen(service.canonicalFunction(), optionalPayment);
		if (screenStatus != OK) {
			return screenStatus;
		}

		return service.checkValidity(query, view);
	}

	private ResponseCodeEnum systemScreen(HederaFunctionality function, Optional<SignedTxnAccessor> payment) {
		AccountID payer = null;
		if (payment.isPresent()) {
			payer = payment.get().getPayer();
			final var permissionStatus = hapiOpPermissions.permissibilityOf(function, payer);
			if (permissionStatus != OK) {
				return permissionStatus;
			}
		}

		if (payer == null || !isThrottleExempt.test(payer.getAccountNum())) {
			return throttles.shouldThrottle(function) ? BUSY : OK;
		} else {
			return OK;
		}
	}

	private long totalOf(FeeObject costs) {
		return costs.getNetworkFee() + costs.getServiceFee() + costs.getNodeFee();
	}

	void setIsThrottleExempt(LongPredicate isThrottleExempt) {
		this.isThrottleExempt = isThrottleExempt;
	}

	public void setNow(Supplier<Instant> now) {
		this.now = now;
	}
}
