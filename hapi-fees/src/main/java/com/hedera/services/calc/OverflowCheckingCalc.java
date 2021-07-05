package com.hedera.services.calc;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.hedera.services.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeComponents;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.fee.FeeBuilder;
import com.hederahashgraph.fee.FeeObject;

import static com.hedera.services.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hederahashgraph.fee.FeeBuilder.FEE_DIVISOR_FACTOR;

/**
 * A specialized fee calculator that throws an exception if any step of the
 * fee calculation overflows.
 *
 * (Because all prices and usage estimates are known to be non-negative,
 * checking for an overflow means just checking for a number less than zero.)
 */
public class OverflowCheckingCalc {
	private static final String OVERFLOW_ERROR = "A fee calculation step overflowed; " +
			"the operation cannot be priced, and therefore cannot be performed";

	/**
	 * Returns the network, node, and services fees for an operation given four inputs.
	 *
	 * The first input to the calculation is a resource usage estimate in the form of
	 * an instance of {@link UsageAccumulator}. (See the Javadoc on hat class for a
	 * detailed description of resource types.)
	 *
	 * The second input is a {@code FeeData} instance that has the price of each
	 * resource in units of 1/1000th of a tinycent.
	 *
	 * The third input is the active exchange rate between ℏ and ¢ (equivalently,
	 * between tinybar and tinycent); and the final input is a multiplier that is
	 * almost always one, except in cases of extreme congestion pricing.
	 *
	 * @param usage the resources used by an operation
	 * @param prices the prices of those resources, in units of 1/1000th of a tinycent
	 * @param rate the exchange rate between ℏ and ¢
	 * @param multiplier a scale factor determined by congestion pricing
	 * @throws IllegalArgumentException if any step of the calculation overflows
	 */
	public FeeObject fees(UsageAccumulator usage, FeeData prices, ExchangeRate rate, long multiplier) {
		final long networkFeeTinycents = networkFeeInTinycents(usage, prices.getNetworkdata());
		final long nodeFeeTinycents = nodeFeeInTinycents(usage, prices.getNodedata());
		final long serviceFeeTinycents = serviceFeeInTinycents(usage, prices.getServicedata());

		final long networkFee = tinycentsToTinybars(networkFeeTinycents, rate) * multiplier;
		final long nodeFee = tinycentsToTinybars(nodeFeeTinycents, rate) * multiplier;
		final long serviceFee = tinycentsToTinybars(serviceFeeTinycents, rate) * multiplier;

		if (networkFee < 0 || nodeFee < 0 || serviceFee < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}

		return new FeeObject(nodeFee, networkFee, serviceFee);
	}

	long tinycentsToTinybars(long amount, ExchangeRate rate) {
		final var product = amount * rate.getHbarEquiv();
		if (product < 0) {
			return FeeBuilder.getTinybarsFromTinyCents(rate, amount);
		}
		return product / rate.getCentEquiv();
	}

	private long networkFeeInTinycents(UsageAccumulator usage, FeeComponents networkPrices) {
		final var nominal = safeAccumulateThree(networkPrices.getConstant(),
				usage.getUniversalBpt() * networkPrices.getBpt(),
				usage.getNetworkVpt() * networkPrices.getVpt(),
				usage.getNetworkRbh() * networkPrices.getRbh());
		return constrainedTinycentFee(nominal, networkPrices.getMin(), networkPrices.getMax());
	}

	private long nodeFeeInTinycents(UsageAccumulator usage, FeeComponents nodePrices) {
		final var nominal = safeAccumulateFour(nodePrices.getConstant(),
				usage.getUniversalBpt() * nodePrices.getBpt(),
				usage.getNodeBpr() * nodePrices.getBpr(),
				usage.getNodeSbpr() * nodePrices.getSbpr(),
				usage.getNodeVpt() * nodePrices.getVpt());
		return constrainedTinycentFee(nominal, nodePrices.getMin(), nodePrices.getMax());
	}

	private long serviceFeeInTinycents(UsageAccumulator usage, FeeComponents servicePrices) {
		final var nominal = safeAccumulateTwo(servicePrices.getConstant(),
				usage.getServiceRbh() * servicePrices.getRbh(),
				usage.getServiceSbh() * servicePrices.getSbh());
		return constrainedTinycentFee(nominal, servicePrices.getMin(), servicePrices.getMax());
	}

	/* Prices in file 0.0.111 are actually set in units of 1/1000th of a tinycent,
	* so here we constrain the nominal price by the max/min and then divide by
	* 1000 (the value of FEE_DIVISOR_FACTOR). */
	private long constrainedTinycentFee(long nominal, long min, long max) {
		if (nominal < min) {
			nominal = min;
		} else if (nominal > max) {
			nominal = max;
		}
		return ESTIMATOR_UTILS.nonDegenerateDiv(nominal, FEE_DIVISOR_FACTOR);
	}

	/* These verbose accumulators signatures are to avoid any performance hit from varargs */
	long safeAccumulateFour(long base, long a, long b, long c, long d) {
		if (base < 0 || a < 0 || b < 0 || c < 0 || d < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		var sum = safeAccumulateThree(base, a, b, c);
		sum += d;
		if (sum < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		return sum;
	}

	long safeAccumulateThree(long base, long a, long b, long c) {
		if (base < 0 || a < 0 || b < 0 || c < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		var sum = safeAccumulateTwo(base, a, b);
		sum += c;
		if (sum < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		return sum;
	}

	long safeAccumulateTwo(long base, long a, long b) {
		if (base < 0 || a < 0 || b < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		base += a;
		if (base < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		base += b;
		if (base < 0) {
			throw new IllegalArgumentException(OVERFLOW_ERROR);
		}
		return base;
	}

}
