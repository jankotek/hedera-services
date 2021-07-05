package com.hedera.services.txns.validation;

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

import com.hederahashgraph.api.proto.java.AccountAmountOrBuilder;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hederahashgraph.api.proto.java.TransferListOrBuilder;

import java.math.BigInteger;

import static java.math.BigInteger.ZERO;

/**
 * Offers a few static helpers to evaluate {@link TransferList} instances
 * presented by incoming gRPC transactions.
 *
 * @author Michael Tinker
 */
public class TransferListChecks {
	public static boolean isNetZeroAdjustment(TransferListOrBuilder wrapper) {
		var net = ZERO;
		for (AccountAmountOrBuilder adjustment : wrapper.getAccountAmountsOrBuilderList()) {
			net = net.add(BigInteger.valueOf(adjustment.getAmount()));
		}
		return net.equals(ZERO);
	}

	public static boolean hasRepeatedAccount(TransferList wrapper) {
		final int n = wrapper.getAccountAmountsCount();
		if (n < 2) {
			return false;
		}
		for (var i = 0; i < n - 1; i++) {
			for (var j = i + 1; j < n; j++) {
				if (wrapper.getAccountAmounts(i).getAccountID().equals(wrapper.getAccountAmounts(j).getAccountID())) {
					return true;
				}
			}
		}
		return false;
	}
}
