package com.hedera.services.ledger.accounts;

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

import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.store.models.NftId;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class HashMapBackingNfts implements BackingStore<NftId, MerkleUniqueToken> {
	private Map<NftId, MerkleUniqueToken> nfts = new HashMap<>();

	@Override
	public MerkleUniqueToken getRef(NftId id) {
		return nfts.get(id);
	}

	@Override
	public MerkleUniqueToken getImmutableRef(NftId id) {
		return nfts.get(id);
	}

	@Override
	public void put(NftId id, MerkleUniqueToken nft) {
		nfts.put(id, nft);
	}

	@Override
	public void remove(NftId id) {
		nfts.remove(id);
	}

	@Override
	public boolean contains(NftId id) {
		return nfts.containsKey(id);
	}

	@Override
	public Set<NftId> idSet() {
		return nfts.keySet();
	}
}
