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
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.virtual.VirtualMap;
import com.hedera.services.state.merkle.virtual.persistence.mmap.MemMapDataSource;
import com.hedera.services.state.merkle.virtual.persistence.mmap.VirtualMapDataStore;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

public class ContractsStore implements AccountStateStore {
	private final VirtualMapDataStore dataStore;
	private final HederaLedger ledger;
	private Map<AccountID, VirtualMap> maps;
	private final BlobStorageSource blobStorageSource;
	private Map<AccountID, MerkleAccount> provisionalAccounts;

	public ContractsStore(
			BlobStorageSource blobStorageSource,
			VirtualMapDataStore dataStore, HederaLedger ledger) {
		this.ledger = ledger;
		this.dataStore = dataStore;
		this.blobStorageSource = blobStorageSource;
		this.maps = new HashMap<>();
		this.provisionalAccounts = new HashMap<>();
	}

	@Override
	public Account get(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			var account = ledger.get(accId);
			// TODO what we do with nonces?
			BigInteger bigInt = BigInteger.valueOf(account.getBalance());
			bigInt = bigInt.multiply(BigInteger.valueOf(10).pow(10)); // TODO: find more adequate way
			return new EvmAccountImpl(address, Wei.of(bigInt));
		}

		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		var map = ledger.get(accId).map();
		if (!map.isInitialised()) {
			map.init(
					new MemMapDataSource(dataStore, new com.hedera.services.state.merkle.virtual.Account(accId.getShardNum(), accId.getRealmNum(), accId.getAccountNum()))
			);
		}

		maps.put(accId, map);
		return map;
	}

	@Override
	public void put(Address address, long nonce, Wei balance) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			final var account = ledger.get(accId);
			final BigInteger tinybarBalance = balance.getAsBigInteger().divide(BigInteger.valueOf(10).pow(10)); // TODO: make more adequate way of calculating this
			ledger.adjustBalance(accId, tinybarBalance.longValue() - account.getBalance());
			return;
		}

		System.out.printf("invalid address found: %s", accId);
	}

	@Override
	public void putCode(Address address, Bytes code) {
//		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
//		if (maps.get(accId) == null) {
//			var map = ledger.get(accId).map();
//			map.init(new MemMapDataSource(dataStore, new com.hedera.services.state.merkle.virtual.Account(accId.getShardNum(), accId.getRealmNum(), accId.getAccountNum())));
//			maps.put(accId, map);
//		}
//		var map = maps.get(accId);
		// TODO:
	}

	@Override
	public Bytes getCode(Address address) {
		return Bytes.of(this.blobStorageSource.get(EntityIdUtils.accountParsedFromSolidityAddress(address.toArray()).toByteArray())); // 0.0.1001 - fileID // 0.0.1002 - contractID
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
		maps.forEach((key, value) -> value.commit());
		// TODO: commit the provisional changes
	}
}