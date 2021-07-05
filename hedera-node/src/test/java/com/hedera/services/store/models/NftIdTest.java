package com.hedera.services.store.models;

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

import com.hederahashgraph.api.proto.java.TokenID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NftIdTest {
	private long shard = 1;
	private long realm = 2;
	private long num = 3;
	private long serialNo = 4;
	private long bShard = 2;
	private long bRealm = 3;
	private long bNum = 4;
	private long bSerialNo = 5;

	@Test
	void objectContractWorks() {
		// given:
		final var subject = new NftId(shard, realm, num, serialNo);
		final var bSubject = new NftId(bShard, realm, num, serialNo);
		final var cSubject = new NftId(shard, bRealm, num, serialNo);
		final var dSubject = new NftId(shard, realm, bNum, serialNo);
		final var eSubject = new NftId(shard, realm, num, bSerialNo);
		final var rSubject = new NftId(shard, realm, num, serialNo);
		final var sSubject = subject;

		// expect:
		assertEquals(subject, rSubject);
		assertEquals(subject.hashCode(), rSubject.hashCode());
		assertEquals(subject, sSubject);
		assertNotEquals(subject, bSubject);
		assertNotEquals(subject.hashCode(), bSubject.hashCode());
		assertNotEquals(subject, cSubject);
		assertNotEquals(subject.hashCode(), cSubject.hashCode());
		assertNotEquals(subject, dSubject);
		assertNotEquals(subject.hashCode(), dSubject.hashCode());
		assertNotEquals(subject, eSubject);
		assertNotEquals(subject.hashCode(), eSubject.hashCode());
	}

	@Test
	void toStringWorks() {
		// setup:
		final var desired = "NftId{shard=1, realm=2, num=3, serialNo=4}";

		// given:
		final var subject = new NftId(shard, realm, num, serialNo);

		// expect:
		assertEquals(desired, subject.toString());
	}

	@Test
	void gettersWork() {
		// given:
		final var subject = new NftId(shard, realm, num, serialNo);
		TokenID expectedTokenId = TokenID.newBuilder()
				.setShardNum(shard)
				.setRealmNum(realm)
				.setTokenNum(num)
				.build();

		assertEquals(shard, subject.shard());
		assertEquals(realm, subject.realm());
		assertEquals(num, subject.num());
		assertEquals(serialNo, subject.serialNo());
		assertEquals(expectedTokenId, subject.tokenId());
	}

	@Test
	void nullEqualsWorks() {
		// given:
		final var subject = new NftId(shard, realm, num, serialNo);

		assertNotEquals(null, subject);
	}
}
