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
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.virtual.ContractHashStore;
import com.hedera.services.state.merkle.virtual.ContractKey;
import com.hedera.services.state.merkle.virtual.ContractLeafStore;
import com.hedera.services.state.merkle.virtual.ContractPath;
import com.hedera.services.state.merkle.virtual.ContractUint256;
import com.hedera.services.state.merkle.virtual.persistence.FCVirtualMapHashStore;
import com.hedera.services.state.merkle.virtual.persistence.FCVirtualMapLeafStore;
import com.hedera.services.store.models.Id;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.fcmap.FCMap;
import com.swirlds.fcmap.VFCMap;
import javafx.util.Pair;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;
import org.hyperledger.besu.ethereum.worldstate.DefaultMutableWorldState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class ContractsStore implements AccountStateStore {

	private final HederaLedger ledger;
	private final BlobStorageSource blobStorageSource;
	private final Supplier<FCMap<MerkleEntityId, VFCMap<ContractUint256, ContractUint256>>> contractStorage;

	private final Map<Address, Bytes> provisionalCodeUpdates = new HashMap<>();
	private final Map<Address, EvmAccountImpl> provisionalAccountUpdates = new HashMap<>();
	private final Map<AccountID, Pair<AccountID, HederaAccountCustomizer>> provisionalAccountCreations = new HashMap<>();
	private final List<Address> provisionalAccountDeletes = new ArrayList<>();
	private final List<Address> provisionalAccountStorageClearings = new ArrayList<>();

	private final FCVirtualMapHashStore<ContractPath> hashStore;
	private final FCVirtualMapLeafStore<ContractKey, ContractPath, ContractUint256> leafStore;

	public ContractsStore(
			Supplier<FCMap<MerkleEntityId, VFCMap<ContractUint256, ContractUint256>>> contractStorage,
			BlobStorageSource blobStorageSource, HederaLedger ledger,
			FCVirtualMapHashStore<ContractPath> hashStore,
			FCVirtualMapLeafStore<ContractKey, ContractPath, ContractUint256> leafStore) {
		this.contractStorage = contractStorage;
		this.ledger = ledger;
		this.blobStorageSource = blobStorageSource;
		this.hashStore = hashStore;
		this.leafStore = leafStore;
	}

	/**
	 * Temporary workaround
	 * @param sponsor
	 * @param target
	 * @param customizer
	 */
	public void prepareAccountCreation(AccountID sponsor, AccountID target, HederaAccountCustomizer customizer) {
		provisionalAccountCreations.put(target, new Pair<>(sponsor, customizer));
	}

	@Override
	public Account get(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			var balance = ledger.getBalance(accId);
			if (ledger.isSmartContract(accId)) {
				var code = provisionalCodeUpdates.containsKey(address) ? provisionalCodeUpdates.get(address) : Bytes.of(blobStorageSource.get(address.toArray()));
				return new EvmAccountImpl(address, Wei.of(balance), code);
			}
			// TODO we must address nonces and mitigate all EVM related operations since Hedera does not have the concept of nonces
			return new EvmAccountImpl(address, Wei.of(balance));
		}

		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		final var accountId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		final var vfcMap = new VFCMap<>(
				new ContractLeafStore(new Id(accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum()), this.leafStore),
				new ContractHashStore(new Id(accountId.getShardNum(), accountId.getRealmNum(), accountId.getAccountNum()), this.hashStore)
		);
		return new AccountStorageMapImpl(vfcMap);
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
			var codeBytes = blobStorageSource.get(address.toArray());
			return codeBytes == null ? Bytes.EMPTY : Bytes.of(codeBytes);
		}
	}

	@Override
	public void remove(Address address) {
		provisionalAccountDeletes.add(address);
	}

	@Override
	public void clearStorage(Address address) {
		provisionalAccountStorageClearings.add(address);
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

		provisionalAccountDeletes.forEach(address -> ledger.destroy(EntityIdUtils.accountParsedFromSolidityAddress(address.toArray())));

		provisionalAccountStorageClearings.forEach(address -> {
			//todo somehow obtain the AccountStorageMap for that Address
			//todo for each entry (is it always 1?) in the map see how to clear ContractLeafStore and ContractHashStore -> release()?
			//todo is that enough?

			
		});

		/* Clear any provisional changes */
		provisionalCodeUpdates.clear();
		provisionalAccountUpdates.clear();
		provisionalAccountCreations.clear();
		provisionalAccountDeletes.clear();
		provisionalAccountStorageClearings.clear();
	}
}