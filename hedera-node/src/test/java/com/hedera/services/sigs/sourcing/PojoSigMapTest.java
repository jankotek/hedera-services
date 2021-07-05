package com.hedera.services.sigs.sourcing;

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

import com.google.protobuf.ByteString;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PojoSigMapTest {
	@Test
	void accessorsAsExpected() {
		// setup:
		final var fakePrefix = "a";
		final var secondFakePrefix = "ab";
		final var thirdFakePrefix = "abc";
		final var fakeSig = "012345678901234567890123456789012345678901234567";
		final var secondFakeSig = (fakeSig.substring(1) + fakeSig.substring(0, 1));
		final var thirdFakeSig = (fakeSig.substring(2) + fakeSig.substring(0, 2));
		// and:
		final byte[][][] expected = new byte[][][] {
				{ fakePrefix.getBytes(), fakeSig.getBytes() },
				{ secondFakePrefix.getBytes(), secondFakeSig.getBytes() },
				{ thirdFakePrefix.getBytes(), thirdFakeSig.getBytes() }
		};

		// given:
		final var grpc = SignatureMap.newBuilder()
				.addSigPair(SignaturePair.newBuilder()
								.setPubKeyPrefix(ByteString.copyFromUtf8(fakePrefix))
								.setEd25519(ByteString.copyFromUtf8(fakeSig)))
				.addSigPair(SignaturePair.newBuilder()
						.setPubKeyPrefix(ByteString.copyFromUtf8(secondFakePrefix))
						.setEd25519(ByteString.copyFromUtf8(secondFakeSig)))
				.addSigPair(SignaturePair.newBuilder()
						.setPubKeyPrefix(ByteString.copyFromUtf8(thirdFakePrefix))
						.setEd25519(ByteString.copyFromUtf8(thirdFakeSig)))
				.build();

		// when:
		final var subject = PojoSigMap.fromGrpc(grpc);

		// then:
		assertArrayEquals(expected[0][0], subject.pubKeyPrefix(0));
		assertArrayEquals(expected[0][1], subject.ed25519Signature(0));
		assertArrayEquals(expected[1][0], subject.pubKeyPrefix(1));
		assertArrayEquals(expected[1][1], subject.ed25519Signature(1));
		assertArrayEquals(expected[2][0], subject.pubKeyPrefix(2));
		assertArrayEquals(expected[2][1], subject.ed25519Signature(2));
		// and:
		assertEquals(3, subject.numSigsPairs());
	}
}
