package com.hedera.services.store.contracts.stubs;/*
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

import org.hyperledger.besu.ethereum.chain.BlockAddedObserver;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.chain.ChainHead;
import org.hyperledger.besu.ethereum.chain.ChainReorgObserver;
import org.hyperledger.besu.ethereum.chain.TransactionLocation;
import org.hyperledger.besu.ethereum.core.BlockBody;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.TransactionReceipt;

import java.util.List;
import java.util.Optional;

public class StubbedBlockchain implements Blockchain {
	@Override
	public ChainHead getChainHead() {
		return null;
	}

	@Override
	public long getChainHeadBlockNumber() {
		return 0;
	}

	@Override
	public Hash getChainHeadHash() {
		return null;
	}

	@Override
	public Optional<BlockHeader> getBlockHeader(long blockNumber) {
		return Optional.empty();
	}

	@Override
	public Optional<BlockHeader> getBlockHeader(Hash blockHeaderHash) {
		return Optional.empty();
	}

	@Override
	public Optional<BlockBody> getBlockBody(Hash blockHeaderHash) {
		return Optional.empty();
	}

	@Override
	public Optional<List<TransactionReceipt>> getTxReceipts(Hash blockHeaderHash) {
		return Optional.empty();
	}

	@Override
	public Optional<Hash> getBlockHashByNumber(long number) {
		return Optional.empty();
	}

	@Override
	public Optional<Difficulty> getTotalDifficultyByHash(Hash blockHeaderHash) {
		return Optional.empty();
	}

	@Override
	public Optional<Transaction> getTransactionByHash(Hash transactionHash) {
		return Optional.empty();
	}

	@Override
	public Optional<TransactionLocation> getTransactionLocation(Hash transactionHash) {
		return Optional.empty();
	}

	@Override
	public long observeBlockAdded(BlockAddedObserver observer) {
		return 0;
	}

	@Override
	public boolean removeObserver(long observerId) {
		return false;
	}

	@Override
	public long observeChainReorg(ChainReorgObserver observer) {
		return 0;
	}

	@Override
	public boolean removeChainReorgObserver(long observerId) {
		return false;
	}
}
