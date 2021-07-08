package com.hedera.services.usage.token;

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

import com.hedera.services.usage.QueryUsage;
import com.hederahashgraph.api.proto.java.Query;

import static com.hedera.services.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.LONG_SIZE;

public class TokenGetNftInfoUsage extends QueryUsage {
	public TokenGetNftInfoUsage(Query query) {
		super(query.getTokenGetNftInfo().getHeader().getResponseType());
		addTb(BASIC_ENTITY_ID_SIZE);
		addTb(LONG_SIZE);
		addRb(NFT_ENTITY_SIZES.fixedBytesInNftRepr());
	}

	public static TokenGetNftInfoUsage newEstimate(Query query) {
		return new TokenGetNftInfoUsage(query);
	}

	public TokenGetNftInfoUsage givenMetadata(String memo) {
		addRb(memo.length());
		return this;
	}
}