package com.hedera.services.usage.token.meta;

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
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class FeeScheduleUpdateMeta {
	private final long effConsensusTime;
	private final int numBytesInNewFeeScheduleRepr;
	private final int numBytesInGrpcFeeScheduleRepr;

	public FeeScheduleUpdateMeta(
			long effConsensusTime,
			int numBytesInNewFeeScheduleRepr,
			int numBytesInGrpcFeeScheduleRepr
	) {
		this.effConsensusTime = effConsensusTime;
		this.numBytesInNewFeeScheduleRepr = numBytesInNewFeeScheduleRepr;
		this.numBytesInGrpcFeeScheduleRepr = numBytesInGrpcFeeScheduleRepr;
	}

	public long effConsensusTime() {
		return effConsensusTime;
	}

	public int numBytesInNewFeeScheduleRepr() {
		return numBytesInNewFeeScheduleRepr;
	}

	public int numBytesInGrpcFeeScheduleRepr() {
		return numBytesInGrpcFeeScheduleRepr;
	}

	@Override
	public boolean equals(Object obj) {
		return EqualsBuilder.reflectionEquals(this, obj);
	}

	@Override
	public int hashCode() {
		return HashCodeBuilder.reflectionHashCode(this);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("effConsensusTime", effConsensusTime)
				.add("numBytesInNewFeeScheduleRepr", numBytesInNewFeeScheduleRepr)
				.add("numBytesInGrpcFeeScheduleRepr", numBytesInGrpcFeeScheduleRepr)
				.toString();
	}
}
