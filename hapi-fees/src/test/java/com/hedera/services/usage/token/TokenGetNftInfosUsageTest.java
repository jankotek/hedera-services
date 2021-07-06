package com.hedera.services.usage.token;

/*
 * -
 *
 * Hedera Services Fees
 *
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.TokenGetNftInfosQuery;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.fee.FeeBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.hedera.services.usage.token.entities.NftEntitySizes.NFT_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_QUERY_RES_HEADER;
import static com.hederahashgraph.fee.FeeBuilder.INT_SIZE;
import static org.junit.Assert.assertEquals;

public class TokenGetNftInfosUsageTest {
	TokenGetNftInfosUsage subject;
	TokenID id;
	List<ByteString> metadata;

	@BeforeEach
	public void setup() {
		metadata = List.of(ByteString.copyFromUtf8("some metadata"));
		id = TokenID.newBuilder()
				.setShardNum(0)
				.setRealmNum(0)
				.setTokenNum(1)
				.build();
		subject = TokenGetNftInfosUsage.newEstimate(tokenNftInfosQuery());
	}

	@Test
	public void assessesEverything() {
		// given:
		subject.givenMetadata(metadata);

		// when:
		var usage = subject.get();
		int additionalRb = 0;
		for (ByteString m : metadata) {
			additionalRb += m.size();
		}
		var expectedBytes = BASIC_QUERY_RES_HEADER
				+ NFT_ENTITY_SIZES.fixedBytesInNftRepr() * metadata.size()
				+ additionalRb;

		// then:
		var node = usage.getNodedata();
		assertEquals(FeeBuilder.BASIC_QUERY_HEADER + BASIC_ENTITY_ID_SIZE + 2 * INT_SIZE, node.getBpt());
		assertEquals(expectedBytes, node.getBpr());
	}

	private Query tokenNftInfosQuery() {
		var op = TokenGetNftInfosQuery.newBuilder()
				.setTokenID(id)
				.build();
		return Query.newBuilder().setTokenGetNftInfos(op).build();
	}
}
