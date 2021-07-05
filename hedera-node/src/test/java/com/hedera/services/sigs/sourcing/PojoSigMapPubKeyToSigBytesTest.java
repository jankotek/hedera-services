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
import com.hedera.services.legacy.exception.KeyPrefixMismatchException;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hedera.test.factories.keys.KeyFactory;
import com.hedera.test.factories.keys.KeyTree;
import com.hedera.test.factories.keys.KeyTreeLeaf;
import com.hedera.test.factories.sigs.SigFactory;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.Transaction;
import com.swirlds.common.crypto.SignatureType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.hedera.test.factories.keys.NodeFactory.ed25519;
import static com.hedera.test.factories.keys.NodeFactory.list;
import static com.hedera.test.factories.txns.SystemDeleteFactory.newSignedSystemDelete;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PojoSigMapPubKeyToSigBytesTest {
	private final byte[] EMPTY_SIG = { };
	private final KeyTree payerKt =
			KeyTree.withRoot(list(ed25519(true), ed25519(true), ed25519(true), ed25519(true), ed25519(true)));
	private final KeyTree otherKt =
			KeyTree.withRoot(list(ed25519(true), ed25519(true), ed25519(true)));
	private final KeyFactory defaultFactory = KeyFactory.getDefaultInstance();

	@Test
	void getsExpectedSigBytesForOtherParties() throws Throwable {
		// given:
		Transaction signedTxn = newSignedSystemDelete()
				.payerKt(payerKt)
				.nonPayerKts(otherKt)
				.get();
		PubKeyToSigBytes subject = new PojoSigMapPubKeyToSigBytes(SignedTxnAccessor.uncheckedFrom(signedTxn).getSigMap());

		// expect:
		lookupsMatch(payerKt, defaultFactory, CommonUtils.extractTransactionBodyBytes(signedTxn), subject);
		lookupsMatch(otherKt, defaultFactory, CommonUtils.extractTransactionBodyBytes(signedTxn), subject);
	}

	@Test
	void rejectsNonUniqueSigBytes() {
		// given:
		String str = "TEST_STRING";
		byte[] pubKey = str.getBytes(StandardCharsets.UTF_8);
		SignaturePair sigPair = SignaturePair.newBuilder().setPubKeyPrefix(ByteString.copyFromUtf8(str)).build();
		SignatureMap sigMap = SignatureMap.newBuilder().addSigPair(sigPair).addSigPair(sigPair).build();
		PojoSigMapPubKeyToSigBytes sigMapPubKeyToSigBytes = new PojoSigMapPubKeyToSigBytes(sigMap);

		// expect:
		KeyPrefixMismatchException exception = assertThrows(KeyPrefixMismatchException.class, () -> {
			sigMapPubKeyToSigBytes.sigBytesFor(pubKey);
		});

		assertEquals(
				"Source signature map with prefix 544553545f535452494e47 is ambiguous for given public key! " +
						"(544553545f535452494e47)", exception.getMessage());
	}

	private void lookupsMatch(KeyTree kt, KeyFactory factory, byte[] data, PubKeyToSigBytes subject) throws Exception {
		AtomicReference<Exception> thrown = new AtomicReference<>();
		kt.traverseLeaves(leaf -> {
			byte[] pubKey = pubKeyFor(leaf, factory);
			byte[] sigBytes = EMPTY_SIG;
			byte[] expectedSigBytes = expectedSigFor(leaf, factory, data);
			try {
				sigBytes = subject.sigBytesFor(pubKey);
			} catch (Exception e) {
				thrown.set(e);
			}
			if (thrown.get() == null) {
				assertThat(List.of(sigBytes), contains(expectedSigBytes));
			}
		});
		if (thrown.get() != null) {
			throw thrown.get();
		}
	}

	private byte[] pubKeyFor(KeyTreeLeaf leaf, KeyFactory factory) {
		Key key = leaf.asKey(factory);
		if (key.getEd25519() != ByteString.EMPTY) {
			return key.getEd25519().toByteArray();
		} else if (key.getECDSA384() != ByteString.EMPTY) {
			return key.getECDSA384().toByteArray();
		} else if (key.getRSA3072() != ByteString.EMPTY) {
			return key.getRSA3072().toByteArray();
		}
		throw new AssertionError("Impossible leaf type!");
	}

	private byte[] expectedSigFor(KeyTreeLeaf leaf, KeyFactory factory, byte[] data) {
		if (!leaf.isUsedToSign()) {
			return EMPTY_SIG;
		} else {
			if (leaf.getSigType() == SignatureType.ED25519) {
				return SigFactory.signUnchecked(data, factory.lookupPrivateKey(leaf.asKey(factory)));
			} else if (leaf.getSigType() == SignatureType.RSA) {
				return SigFactory.NONSENSE_RSA_SIG;
			} else if (leaf.getSigType() == SignatureType.ECDSA) {
				return SigFactory.NONSENSE_ECDSA_SIG;
			}
			throw new AssertionError("Impossible leaf type!");
		}
	}
}
