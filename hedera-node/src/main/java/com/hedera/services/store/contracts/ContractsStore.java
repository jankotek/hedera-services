package com.hedera.services.store.contracts;/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.virtual.VirtualMap;
import com.hedera.services.state.merkle.virtual.persistence.mmap.MemMapDataSource;
import com.hedera.services.state.merkle.virtual.persistence.mmap.VirtualMapDataStore;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import javafx.util.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;

import java.util.HashMap;
import java.util.Map;

public class ContractsStore implements AccountStateStore {
	private final VirtualMapDataStore dataStore;
	private final HederaLedger ledger;
	private final BlobStorageSource blobStorageSource;
	private Map<Address, Bytes> provisionalCodeUpdates = new HashMap<>();
	private Map<Address, EvmAccountImpl> provisionalAccountUpdates = new HashMap<>();
	private Map<AccountID, Pair<AccountID, HederaAccountCustomizer>> provisionalAccountCreations = new HashMap<>();

	public ContractsStore(
			BlobStorageSource blobStorageSource,
			VirtualMapDataStore dataStore, HederaLedger ledger) {
		this.ledger = ledger;
		this.dataStore = dataStore;
		this.blobStorageSource = blobStorageSource;
	}

	public void prepareAccountCreation(AccountID sponsor, AccountID target, HederaAccountCustomizer customizer) {
		provisionalAccountCreations.put(target, new Pair<>(sponsor, customizer));
	}

	// The EVM is executing this call everytime it needs to access a contract/address. F.e getting recipient address multiple times during 1 contract executions
	@Override
	public Account get(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			var balance = ledger.getBalance(accId);
			if (ledger.isSmartContract(accId)) {
				var code = provisionalCodeUpdates.containsKey(address) ? provisionalCodeUpdates.get(address) : Bytes.of(blobStorageSource.get(address.toArray()));
				return new EvmAccountImpl(address, Wei.of(balance), code);
			}
			// TODO what we do with nonces?
			return new EvmAccountImpl(address, Wei.of(balance));
		}

		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		var merkleAccount = ledger.get(accId);
		if (merkleAccount.map() == null) {
			merkleAccount.setVirtualMap(new VirtualMap(new MemMapDataSource(dataStore, new com.hedera.services.state.merkle.virtual.Account(accId.getShardNum(), accId.getRealmNum(), accId.getAccountNum()))));
		}
		return merkleAccount.map();
	}

	@Override
	public void put(Address address, long nonce, Wei balance) {
		provisionalAccountUpdates.put(address, new EvmAccountImpl(address, balance));
	}

	@Override
	public void putCode(Address address, Bytes code) {
		provisionalCodeUpdates.put(address, code);
	}

	@Override
	public Bytes getCode(Address address) {
		if (provisionalCodeUpdates.containsKey(address)) {
			return provisionalCodeUpdates.get(address);
		} else {
			return Bytes.of(blobStorageSource.get(address.toArray()));
		}
	}

	@Override
	public void remove(Address address) {
		// TODO: set somewhere provisionally
	}

	@Override
	public void clearStorage(Address address) {
		// TODO: set somewhere provisionally
	}

	@Override
	public void commit() {
		provisionalAccountUpdates.forEach((address, evmAccount) -> {
			final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
			if (ledger.exists(accId)) {
				final var account = ledger.get(accId);
				ledger.adjustBalance(accId, evmAccount.getBalance().toLong() - account.getBalance());
			} else {
				var pair = this.provisionalAccountCreations.get(accId);
				ledger.create(pair.getKey(), accId, evmAccount.getBalance().toLong(), pair.getValue());
			}
		});


		/* Commit code updates for each updated address */
		provisionalCodeUpdates.forEach((address, code) -> {
			blobStorageSource.put(address.toArray(), code.toArray());
		});

		/* Clear any provisional changes */
		provisionalCodeUpdates.clear();
		provisionalAccountUpdates.clear();
		provisionalAccountCreations.clear();
	}
}