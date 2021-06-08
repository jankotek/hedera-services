package com.hedera.services.contracts.state;/*
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

import org.apache.tuweni.bytes.Bytes32;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.MutableWorldState;
import org.hyperledger.besu.ethereum.core.WorldUpdater;
import org.hyperledger.besu.ethereum.worldstate.WorldStatePreimageStorage;
import org.hyperledger.besu.ethereum.worldstate.WorldStateStorage;

import java.util.stream.Stream;

public class MutableWorldStateImpl implements MutableWorldState {

	// TODO WorldStateStorage must BlobSupplier?
//	private final WorldStateStorage worldStateStorage;
//	private final WorldStatePreimageStorage preimageStorage;

	// TODO
	public MutableWorldStateImpl() {
		// worldStateStorage
		// preimageStorage
	}

	@Override
	public MutableWorldState copy() {
		return null;
	} // TODO

	@Override
	public void persist(BlockHeader blockHeader) {

	}

	@Override
	public WorldUpdater updater() {
		return null;
	}

	@Override
	public Hash rootHash() {
		return null;
	} // TODO?

	@Override
	public Hash frontierRootHash() {
		return rootHash();
	}

	@Override
	public Stream<StreamableAccount> streamAccounts(Bytes32 startKeyHash, int limit) {
		return null;
	}

	@Override
	public Account get(Address address) {
		return null;
	}
}
