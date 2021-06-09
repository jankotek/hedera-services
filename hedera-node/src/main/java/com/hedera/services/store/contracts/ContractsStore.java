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

import com.hedera.services.ledger.HederaLedger;
import org.apache.tuweni.bytes.Bytes;
import org.ethereum.util.ALock;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;

import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.hedera.services.utils.EntityIdUtils.accountParsedFromSolidityAddress;

public class ContractsStore implements AccountStateStore {

	private final HederaLedger ledger;
	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private final ALock rLock = new ALock(rwLock.readLock());

	public ContractsStore(HederaLedger ledger) {
		this.ledger = ledger;
	}

	@Override
	public Account get(Address address) {
		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		return null;
	}

	@Override
	public void put(Address address, long nonce, Wei balance) {

	}

	@Override
	public void putCode(Address address, Bytes code) {

	}

	@Override
	public Bytes getCode(Address address) {
		return null;
	}

	@Override
	public void remove(Address address) {

	}

	@Override
	public void clearStorage(Address address) {

	}

	@Override
	public void commit() {

	}
}