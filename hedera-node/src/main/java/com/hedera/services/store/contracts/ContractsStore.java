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
import org.ethereum.util.ALock;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.EvmAccount;
import org.hyperledger.besu.ethereum.core.UpdateTrackingAccount;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.WorldUpdater;

import java.math.BigInteger;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.hedera.services.utils.EntityIdUtils.accountParsedFromSolidityAddress;
import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;

public class ContractsStore implements WorldUpdater {

	private final HederaLedger ledger;
	private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
	private final ALock rLock = new ALock(rwLock.readLock());
//	private final ALock wLock = new ALock(rwLock.writeLock());

	public ContractsStore(HederaLedger ledger) {
		this.ledger = ledger;
	}


	@Override
	public EvmAccount createAccount(Address address, long nonce, Wei balance) {
		// TODO must implement
		return null;
	}

	// TODO We are always returning UpdateTrackingAccount. Is it OK?
	@Override
	public EvmAccount getAccount(Address address) {
		try (ALock ignored = rLock.lock()) {
			var id = accountParsedFromSolidityAddress(address.toArray());
			if (!ledger.exists(id) || ledger.isDetached(id)) {
				return null;
			}

			var account = ledger.get(id);
			// TODO check whether we need more properties to be set
			return new UpdateTrackingAccount<>(new EvmAccountImpl(address, Wei.of(account.getBalance())));
		}
	}


	@Override
	public void deleteAccount(Address address) {

	}

	@Override
	public Collection<? extends Account> getTouchedAccounts() {
		return null;
	}

	@Override
	public Collection<Address> getDeletedAccountAddresses() {
		return null;
	}

	@Override
	public void revert() {

	}

	@Override
	public void commit() {

	}

	@Override
	public Optional<WorldUpdater> parentUpdater() {
		return Optional.empty();
	}

	@Override
	public WorldUpdater updater() {
		return null;
	}

	@Override
	public Account get(Address address) {
		return null;
	}
}
