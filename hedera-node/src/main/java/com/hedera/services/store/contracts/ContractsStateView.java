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

import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.state.merkle.MerkleAccount;
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
import com.swirlds.fcmap.FCMap;
import com.swirlds.fcmap.VFCMap;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;

public class ContractsStateView implements AccountStateStore {

	private final BlobStorageSource blobStorageSource;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts;
	private final Supplier<FCMap<MerkleEntityId, VFCMap<ContractUint256, ContractUint256>>> contractStorage;

	private FCVirtualMapHashStore<ContractPath> hashStore;
	private FCVirtualMapLeafStore<ContractKey, ContractPath, ContractUint256> leafStore;

	public ContractsStateView(
			BlobStorageSource blobStorageSource,
			Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts,
			Supplier<FCMap<MerkleEntityId, VFCMap<ContractUint256, ContractUint256>>> contractStorage,
			FCVirtualMapHashStore<ContractPath> hashStore,
			FCVirtualMapLeafStore<ContractKey, ContractPath, ContractUint256> leafStore
	) {
		this.blobStorageSource = blobStorageSource;
		this.accounts = accounts;
		this.contractStorage = contractStorage;

		this.hashStore = hashStore;
		this.leafStore = leafStore;
	}

	@Override
	public Account get(Address address) {
		final var accId = parseMerkleAccountId(address);
		if (accounts.get().containsKey(accId)) {
			// Use accounts() directly
			var merkleAccount = accounts.get().get(accId);
			if (merkleAccount.isSmartContract()) {
				var code = Bytes.of(blobStorageSource.get(address.toArray()));
				return new EvmAccountImpl(address, Wei.of(merkleAccount.getBalance()), code);
			}
			return new EvmAccountImpl(address, Wei.of(merkleAccount.getBalance()));
		}

		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		final var accId = parseMerkleAccountId(address);
//		if (!contractStorage.get().containsKey(accId)) {
//			contractStorage.get().put(accId, new VFCMap<>());
//		}
//		final var vfcMap = contractStorage.get().get(accId);
		final var vfcMap = new VFCMap<>(
				new ContractLeafStore(new Id(accId.getShard(), accId.getRealm(), accId.getNum()), this.leafStore),
				new ContractHashStore(new Id(accId.getShard(), accId.getRealm(), accId.getNum()), this.hashStore)
		);
		return new AccountStorageMapImpl(vfcMap);
	}

	@Override
	public void put(Address address, long nonce, Wei balance) {
		throw new IllegalStateException("Tried to update state of immutable Account!");
	}

	@Override
	public void putCode(Address address, Bytes code) {
		throw new IllegalStateException("Tried to update code of immutable Account!");
	}

	@Override
	public Bytes getCode(Address address) {
		return Bytes.of(blobStorageSource.get(address.toArray()));
	}

	@Override
	public void remove(Address address) {
		throw new IllegalStateException("Tried to remove immutable Account!");
	}

	@Override
	public void clearStorage(Address address) {
		throw new IllegalStateException("Tried to clear storage of immutable Account!");
	}

	@Override
	public void commit() {
		throw new UnsupportedOperationException("Tried to commit state changes on view only state");
	}

	@NotNull
	private MerkleEntityId parseMerkleAccountId(Address address) {
		return fromAccountId(EntityIdUtils.accountParsedFromSolidityAddress(address.toArray()));
	}
}
