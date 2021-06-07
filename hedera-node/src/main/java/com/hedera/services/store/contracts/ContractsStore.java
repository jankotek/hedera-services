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

import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.EvmAccount;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.core.WorldUpdater;

import java.util.Collection;
import java.util.Optional;

public class ContractsStore implements WorldUpdater {


	@Override
	public EvmAccount createAccount(Address address, long nonce, Wei balance) {
		return null;
	}

	@Override
	public EvmAccount getAccount(Address address) {
		return null;
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
