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

import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.fcmap.FCMap;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;

public class BackingAccounts implements BackingStore<AccountID, MerkleAccount> {
	Set<AccountID> existingAccounts = new HashSet<>();

	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> delegate;

	public BackingAccounts(Supplier<FCMap<MerkleEntityId, MerkleAccount>> delegate) {
		this.delegate = delegate;
		rebuildFromSources();
	}

	@Override
	public void rebuildFromSources() {
		existingAccounts.clear();
		delegate.get().keySet().stream()
				.map(MerkleEntityId::toAccountId)
				.forEach(existingAccounts::add);
	}

	@Override
	public MerkleAccount getRef(AccountID id) {
		return delegate.get().getForModify(fromAccountId(id));
	}

	@Override
	public void put(AccountID id, MerkleAccount account) {
		if (!existingAccounts.contains(id)) {
			delegate.get().put(fromAccountId(id), account);
			existingAccounts.add(id);
		}
	}

	@Override
	public boolean contains(AccountID id) {
		return existingAccounts.contains(id);
	}

	@Override
	public void remove(AccountID id) {
		existingAccounts.remove(id);
		delegate.get().remove(fromAccountId(id));
	}

	@Override
	public Set<AccountID> idSet() {
		return existingAccounts;
	}

	@Override
	public MerkleAccount getImmutableRef(AccountID id) {
		return delegate.get().get(fromAccountId(id));
	}
}
