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

import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.usage.crypto.CryptoOpsUsage;
import com.hedera.services.usage.crypto.ExtantCryptoContext;
import com.hederahashgraph.api.proto.java.ExchangeRate;
import com.hederahashgraph.api.proto.java.FeeData;
import org.apache.commons.lang3.tuple.Triple;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;

import static com.hedera.services.utils.MiscUtils.asKeyUnchecked;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ACCOUNT_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.FEE_DIVISOR_FACTOR;
import static com.hederahashgraph.fee.FeeBuilder.HRS_DIVISOR;
import static com.hederahashgraph.fee.FeeBuilder.getTinybarsFromTinyCents;

public class AutoRenewCalcs {
	private static final Logger log = LogManager.getLogger(AutoRenewCalcs.class);

	private static final RenewAssessment NO_RENEWAL_POSSIBLE = new RenewAssessment(0L, 0L);

	private final CryptoOpsUsage cryptoOpsUsage;

	private Triple<FeeData, Instant, FeeData> cryptoAutoRenewPriceSeq = null;

	private long firstConstantCryptoAutoRenewFee = 0L;
	private long secondConstantCryptoAutoRenewFee = 0L;
	private long firstServiceRbhPrice = 0L;
	private long secondServiceRbhPrice = 0L;

	public AutoRenewCalcs(CryptoOpsUsage cryptoOpsUsage) {
		this.cryptoOpsUsage = cryptoOpsUsage;
	}

	public void setCryptoAutoRenewPriceSeq(Triple<FeeData, Instant, FeeData> cryptoAutoRenewPriceSeq) {
		this.cryptoAutoRenewPriceSeq = cryptoAutoRenewPriceSeq;

		if (cryptoAutoRenewPriceSeq.getLeft() == null) {
			log.warn("No prices known for CryptoAccountAutoRenew, will charge zero fees!");
		} else {
			this.firstConstantCryptoAutoRenewFee = constantFeeFrom(cryptoAutoRenewPriceSeq.getLeft());
			this.secondConstantCryptoAutoRenewFee = constantFeeFrom(cryptoAutoRenewPriceSeq.getRight());

			this.firstServiceRbhPrice = cryptoAutoRenewPriceSeq.getLeft().getServicedata().getRbh();
			this.secondServiceRbhPrice = cryptoAutoRenewPriceSeq.getRight().getServicedata().getRbh();
		}
	}

	public RenewAssessment maxRenewalAndFeeFor(
			MerkleAccount expiredAccount,
			long reqPeriod,
			Instant at,
			ExchangeRate rate
	) {
		if (cryptoAutoRenewPriceSeq == null) {
			throw new IllegalStateException("No crypto usage prices are set!");
		}

		final long balance = expiredAccount.getBalance();
		if (balance == 0L) {
			return NO_RENEWAL_POSSIBLE;
		}

		final boolean isBeforeSwitch = at.isBefore(cryptoAutoRenewPriceSeq.getMiddle());
		final long nominalFixed = isBeforeSwitch ? firstConstantCryptoAutoRenewFee : secondConstantCryptoAutoRenewFee;
		final long serviceRbhPrice = isBeforeSwitch ? firstServiceRbhPrice : secondServiceRbhPrice;

		final long fixedFee = inTinybars(nominalFixed, rate);
		final long rbUsage = rbUsedBy(expiredAccount);
		final long hourlyFee = inTinybars(serviceRbhPrice * rbUsage, rate);
		final long maxRenewableRbh = Math.max(1L, maxRenewableRbhGiven(fixedFee, hourlyFee, reqPeriod, balance));

		final long maxRenewablePeriod = maxRenewableRbh * HRS_DIVISOR;
		final long feeForMaxRenewal = Math.min(fixedFee + maxRenewableRbh * hourlyFee, balance);

		return new RenewAssessment(feeForMaxRenewal, Math.min(reqPeriod, maxRenewablePeriod));
	}

	private long maxRenewableRbhGiven(
			long fixedTinybarFee,
			long tinybarPerHour,
			long requestedPeriod,
			long balance
	) {
		final long remainingBalance = Math.max(0, balance - fixedTinybarFee);
		final long affordableHours = remainingBalance / tinybarPerHour;
		final long requestedHours = requestedPeriod / HRS_DIVISOR + (requestedPeriod % HRS_DIVISOR > 0 ? 1 : 0);
		return Math.min(affordableHours, requestedHours);
	}

	long inTinybars(long nominalFee, ExchangeRate rate) {
		return getTinybarsFromTinyCents(rate, nominalFee / FEE_DIVISOR_FACTOR);
	}

	long rbUsedBy(MerkleAccount account) {
		final var extantCtx = ExtantCryptoContext.newBuilder()
				.setCurrentExpiry(0L)
				.setCurrentKey(asKeyUnchecked(account.getKey()))
				.setCurrentlyHasProxy(account.getProxy() != null)
				.setCurrentMemo(account.getMemo())
				.setCurrentNumTokenRels(account.tokens().numAssociations())
				.build();
		return cryptoOpsUsage.cryptoAutoRenewRb(extantCtx);
	}

	private long constantFeeFrom(FeeData prices) {
		return prices.getNodedata().getConstant()
				+ prices.getNetworkdata().getConstant()
				+ prices.getServicedata().getConstant();
	}

	public static class RenewAssessment {
		private final long fee;
		private final long renewalPeriod;

		public RenewAssessment(long fee, long renewalPeriod) {
			this.fee = fee;
			this.renewalPeriod = renewalPeriod;
		}

		public long fee() {
			return fee;
		}

		public long renewalPeriod() {
			return renewalPeriod;
		}
	}
}
