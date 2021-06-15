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

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.ethereum.core.AccountStorageEntry;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.EvmAccount;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.ModificationNotAllowedException;
import org.hyperledger.besu.ethereum.core.MutableAccount;
import org.hyperledger.besu.ethereum.core.Wei;

import java.util.NavigableMap;

public class EvmAccountImpl implements EvmAccount {

	private final Address address;
	private final Wei balance;
	private final Hash addressHash;
	private final Bytes code;

	public EvmAccountImpl (Address address, Wei balance) {
		this(address, balance, null);
	}

	public EvmAccountImpl (Address address, Wei balance, Bytes code) {
		this.address = address;
		this.balance = balance;
		this.addressHash = Hash.hash(address);
		this.code = code;
	}

	@Override
	public MutableAccount getMutable() throws ModificationNotAllowedException {
		throw new ModificationNotAllowedException();
	}

	@Override
	public Address getAddress() {
		return address;
	}

	@Override
	public Hash getAddressHash() {
		return addressHash;
	}

	@Override
	public long getNonce() {
		return 0;
	}

	@Override
	public Wei getBalance() {
		return balance;
	}

	@Override
	public Bytes getCode() {
		return code;
	}

	@Override
	public Hash getCodeHash() {
		return null;
	}

	@Override
	public int getVersion() {
		return 0;
	}

	@Override
	public UInt256 getStorageValue(UInt256 key) {
		return null;
	}

	@Override
	public UInt256 getOriginalStorageValue(UInt256 key) {
		return null;
	}

	@Override
	public NavigableMap<Bytes32, AccountStorageEntry> storageEntriesFrom(Bytes32 startKeyHash, int limit) {
		return null;
	}
}
