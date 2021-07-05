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

import com.hedera.services.legacy.exception.KeyPrefixMismatchException;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.swirlds.common.CommonUtils;

import java.util.Arrays;

/**
 * A source of cryptographic signatures backed by a {@link SignatureMap} instance.
 *
 * <p><b>IMPORTANT:</b> If a public key does not match any prefix in the backing
 * {@code SignatureMap}, we simply return an empty {@code byte[]} for its
 * cryptographic signature. It might seem that we should instead fail fast
 * (since an empty signature can never be {@code VALID}).
 *
 * However, this would be a mistake, since with e.g. Hedera threshold keys it is quite
 * possible for a Hedera key to be active even if some number of its constituent
 * simple keys lack a valid signature.
 */
public class PojoSigMapPubKeyToSigBytes implements PubKeyToSigBytes {
	private final PojoSigMap pojoSigMap;

	public PojoSigMapPubKeyToSigBytes(SignatureMap sigMap) {
		pojoSigMap = PojoSigMap.fromGrpc(sigMap);
	}

	@Override
	public byte[] sigBytesFor(byte[] pubKey) throws Exception {
		byte[] sigBytes = EMPTY_SIG;
		for (int i = 0, n = pojoSigMap.numSigsPairs(); i < n; i++) {
			final byte[] pubKeyPrefix = pojoSigMap.pubKeyPrefix(i);
			if (beginsWith(pubKey, pubKeyPrefix)) {
				if (sigBytes != EMPTY_SIG) {
					throw new KeyPrefixMismatchException(
							"Source signature map with prefix " + CommonUtils.hex(pubKeyPrefix) +
									" is ambiguous for given public key! (" + CommonUtils.hex(pubKey) + ")");
				}
				sigBytes = pojoSigMap.ed25519Signature(i);
			}
		}
		return sigBytes;
	}

	public static boolean beginsWith(byte[] pubKey, byte[] prefix) {
		int n = prefix.length;
		return Arrays.equals(prefix, 0, n, pubKey, 0, n);
	}
}
