package com.hedera.services.usage.state;

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

import com.google.common.base.MoreObjects;
import com.hedera.services.usage.BaseTransactionMeta;
import com.hedera.services.usage.SigUsage;

import static com.hedera.services.usage.SingletonEstimatorUtils.ESTIMATOR_UTILS;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ACCOUNT_AMT_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_RECEIPT_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_TX_BODY_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_TX_RECORD_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.HRS_DIVISOR;
import static com.hederahashgraph.fee.FeeBuilder.INT_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.RECEIPT_STORAGE_TIME_SEC;

/**
 * Accumulates an estimate of the resources used by a HAPI operation.
 *
 * Resources are consumed by three service providers,
 * <ol>
 * <li>The network when providing gossip, consensus, and short-term storage of receipts.</li>
 * <li>The node when communicating with the client, performing prechecks, and submitting to the network.</li>
 * <li>The network when performing the logical service itself.</li>
 * </ol>
 *
 * The key fact is that the estimated resource usage for all three service providers
 * is a pure function of the <b>same</b> base usage estimates, for eight types
 * of resources:
 * <ol>
 * <li>Network capacity needed to submit an operation to the network.
 * Units are {@code bpt} (“bytes per transaction”).</li>
 * <li>Network capacity needed to return information from memory in response to an operation.
 * Units are {@code bpr} (“bytes per response).</li>
 * <li>Network capacity needed to return information from disk in response to an operation.
 * Units are {@code sbpr} (“storage bytes per response”).</li>
 * <li>RAM needed to persist an operation’s effects on consensus state, for as long as such effects are visible.
 * Units are {@code rbh} (“RAM byte-hours”).</li>
 * <li>Disk space needed to persist the operation’s effect on consensus state, for as long as such effects are visible.
 * Units are {@code sbh} (“storage byte-hours”).</li>
 * <li>Computation needed to verify a Ed25519 cryptographic signature.
 * Units are {@code vpt} (“verifications per transaction”).</li>
 * <li>Computation needed for incremental execution of a Solidity smart contract.
 * Units are {@code gas}.</li>
 * </ol>
 */
public class UsageAccumulator {
	private static final long LONG_BASIC_TX_BODY_SIZE = BASIC_TX_BODY_SIZE;

	/* Captures how much signature verification work was done exclusively by the submitting node. */
	private long numPayerKeys;

	private long bpt;
	private long bpr;
	private long sbpr;
	private long vpt;
	private long gas;
	/* For storage resources, we use a finer-grained estimate in
	 * units of seconds rather than hours, since expiration times
	 * are given in seconds since the (consensus) epoch. */
	private long rbs;
	private long sbs;
	private long networkRbs;

	public void resetForTransaction(BaseTransactionMeta baseMeta, SigUsage sigUsage) {
		final int memoBytes = baseMeta.getMemoUtf8Bytes();
		final int numTransfers = baseMeta.getNumExplicitTransfers();

		gas = sbs = sbpr = 0;

		bpr = INT_SIZE;
		vpt = sigUsage.numSigs();
		bpt = LONG_BASIC_TX_BODY_SIZE + memoBytes + sigUsage.sigsSize();
		rbs = RECEIPT_STORAGE_TIME_SEC * (BASIC_TX_RECORD_SIZE + memoBytes + BASIC_ACCOUNT_AMT_SIZE * numTransfers);

		networkRbs = RECEIPT_STORAGE_TIME_SEC * BASIC_RECEIPT_SIZE;
		numPayerKeys = sigUsage.numPayerKeys();
	}

	/* Resource accumulator methods */
	public void addBpt(long amount) {
		bpt += amount;
	}

	public void addBpr(long amount) {
		bpr += amount;
	}

	public void addSbpr(long amount) {
		sbpr += amount;
	}

	public void addVpt(long amount) {
		vpt += amount;
	}

	public void addGas(long amount) {
		gas += amount;
	}

	public void addRbs(long amount) {
		rbs += amount;
	}

	public void addSbs(long amount) {
		sbs += amount;
	}

	public void addNetworkRbs(long amount) {
		networkRbs += amount;
	}

	/* Provider-scoped usage estimates (pure functions of the total resource usage) */
	/* -- NETWORK & NODE -- */
	public long getUniversalBpt() {
		return bpt;
	}

	/* -- NETWORK -- */
	public long getNetworkVpt() {
		return vpt;
	}

	public long getNetworkRbh() {
		return ESTIMATOR_UTILS.nonDegenerateDiv(networkRbs, HRS_DIVISOR);
	}

	/* -- NODE -- */
	public long getNodeBpr() {
		return bpr;
	}

	public long getNodeSbpr() {
		return sbpr;
	}

	public long getNodeVpt() {
		return numPayerKeys;
	}

	/* -- SERVICE -- */
	public long getServiceRbh() {
		return ESTIMATOR_UTILS.nonDegenerateDiv(rbs, HRS_DIVISOR);
	}

	public long getServiceSbh() {
		return ESTIMATOR_UTILS.nonDegenerateDiv(sbs, HRS_DIVISOR);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("universalBpt", getUniversalBpt())
				.add("networkVpt", getNetworkVpt())
				.add("networkRbh", getNetworkRbh())
				.add("nodeBpr", getNodeBpr())
				.add("nodeSbpr", getNodeSbpr())
				.add("nodeVpt", getNodeVpt())
				.add("serviceSbh", getServiceSbh())
				.add("serviceRbh", getServiceRbh())
				.toString();
	}

	/* Helpers for test coverage */
	long getBpt() {
		return bpt;
	}

	long getBpr() {
		return bpr;
	}

	long getSbpr() {
		return sbpr;
	}

	long getVpt() {
		return vpt;
	}

	long getGas() {
		return gas;
	}

	long getRbs() {
		return rbs;
	}

	long getSbs() {
		return sbs;
	}

	long getNetworkRbs() {
		return networkRbs;
	}

	long getNumPayerKeys() {
		return numPayerKeys;
	}

	public void setNumPayerKeys(long numPayerKeys) {
		this.numPayerKeys = numPayerKeys;
	}
}
