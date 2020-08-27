package com.hedera.test.forensics;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleAccountState;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.test.forensics.domain.PojoLedger;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.common.merkle.io.MerkleDataInputStream;
import com.swirlds.fcmap.FCMap;
import com.swirlds.fcqueue.FCQueue;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;
import org.spongycastle.util.encoders.Hex;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

@Disabled
@RunWith(JUnitPlatform.class)
public class AccountExtractor {

	static byte[] FCQ = Longs.toByteArray(0x000000206b1fa797L);
	static byte[] ACCOUNT_STATE = Longs.toByteArray(0x354cfc55834e7f12L);
	static byte[] MERKLE_ACCOUNT = Longs.toByteArray(0x950bcf7255691908L);
	static byte[] MERKLE_ENTITY_ID = Longs.toByteArray(0xd5dd2ebaa0bde03L);

//	static final String savedStateLoc =
//			"/Users/tinkerm/Dev/hgn3/hedera-services/hedera-node/" +
//					"data/saved/com.hedera.services.ServicesMain/0/123/919/SignedState.swh";
	static final String savedStateLoc = "/Users/tinkerm/Dev/hgn3/hedera-services/SignedState.swh";

	@Test
	public void extractAccounts() throws IOException, ConstructableRegistryException {
		AccountsReader.registerConstructables();

		FCMap<MerkleEntityId, MerkleAccount> accounts =
				new FCMap<>(new MerkleEntityId.Provider(), MerkleAccount.LEGACY_PROVIDER);

		var sbc = Files.newByteChannel(Paths.get(savedStateLoc));
		seekToNext(sbc, MERKLE_ENTITY_ID);
		System.out.println("First entity  @ " + sbc.position());

		seekToNext(sbc, MERKLE_ENTITY_ID);
		long firstEntity = sbc.position();
		seekToNext(sbc, MERKLE_ACCOUNT);
		System.out.println("First account @ " + sbc.position());
		long firstAccount = sbc.position();
		sbc.position(firstEntity);
		long lastEntity = firstEntity;
		while (true) {
			sbc.position(sbc.position() + 1);
			seekToNext(sbc, MERKLE_ENTITY_ID);
			if (sbc.position() > firstAccount) {
				sbc.position(lastEntity);
				break;
			} else {
				lastEntity = sbc.position();
			}
		}
		System.out.println("Last entity before accounts @ " + lastEntity);

		int maxAccounts = 1_000;
		while (seekToNext(sbc, MERKLE_ENTITY_ID)) {
			var id = deserializeEntityId(sbc);
			if (includeNum(id.getNum())) {
				System.out.println(id);
				if (!seekToNext(sbc, MERKLE_ACCOUNT)) {
					throw new IllegalStateException("No paired account!");
				}
				var account = deserializeAccount(sbc, false, false);
				System.out.println(account);
				if (includeAccount(account)) {
					accounts.put(id, account);
					System.out.println(account);
					if (accounts.size() > maxAccounts) {
						break;
					}
				}
			} else {
				sbc.position(sbc.position() + 1);
			}
		}

		PojoLedger.from(accounts).asJsonTo(loc("lowThresholds"));
	}

	private String loc(String desc) {
		return String.format("/Users/tinkerm/Dev/hgn3/hedera-services/%s.json", desc);
	}

	private boolean includeAccount(MerkleAccount account) {
		long TINYBARS_PER_HBAR = 100_000_000L;
		return account.getReceiverThreshold() <= TINYBARS_PER_HBAR || account.getSenderThreshold() <= TINYBARS_PER_HBAR;
	}

	private boolean includeNum(long num) {
		return true;
	}

	private MerkleAccount deserializeAccount(
			SeekableByteChannel sbc,
			boolean includePayerRecords,
			boolean includeThresholdRecords
	) throws IOException {
		long loc = sbc.position();

		MerkleAccountState state;
		if (!seekToNext(sbc, ACCOUNT_STATE)) {
			throw new IllegalStateException("No subsequent state!");
		}
		state = deserializeAccountState(sbc);

		FCQueue<ExpirableTxnRecord> records = new FCQueue<>(ExpirableTxnRecord.LEGACY_PROVIDER);
		if (includePayerRecords || includeThresholdRecords) {
			if (!seekToNext(sbc, FCQ)) {
				throw new IllegalStateException("No subsequent threshold records FCQ!");
			}
			if (includeThresholdRecords) {
				records = deserializeRecords(sbc);
			}
		}

		FCQueue<ExpirableTxnRecord> payerRecords = new FCQueue<>(ExpirableTxnRecord.LEGACY_PROVIDER);
		if (includePayerRecords) {
			if (!seekToNext(sbc, FCQ)) {
				throw new IllegalStateException("No subsequent payer records FCQ!");
			}
			payerRecords = deserializeRecords(sbc);
		}

		MerkleAccount account = new MerkleAccount(List.of(state, records, payerRecords));

		sbc.position(loc);
		return account;
	}

	private FCQueue<ExpirableTxnRecord> deserializeRecords(SeekableByteChannel sbc) throws IOException {
		int MAX_RECORDS_SIZE = 32 * 1_024;
		long loc = sbc.position();

		var backing = backingBytes(MAX_RECORDS_SIZE);
		var buffer = ByteBuffer.wrap(backing, 6, MAX_RECORDS_SIZE - 6);
		sbc.read(buffer);

		FCQueue<ExpirableTxnRecord> records;
		try (MerkleDataInputStream in = new MerkleDataInputStream(new ByteArrayInputStream(backing), false)) {
			records = in.readMerkleTree(1);
		}

		sbc.position(loc);
		return records;
	}

	private MerkleAccountState deserializeAccountState(SeekableByteChannel sbc) throws IOException {
		int MAX_STATE_SIZE = 1 * 1_024;
		long loc = sbc.position();

		var backing = backingBytes(MAX_STATE_SIZE);
		var buffer = ByteBuffer.wrap(backing, 6, MAX_STATE_SIZE - 6);
		sbc.read(buffer);

		MerkleAccountState state;
		try (MerkleDataInputStream in = new MerkleDataInputStream(new ByteArrayInputStream(backing), false)) {
			state = in.readMerkleTree(1);
		}

		sbc.position(loc);
		return state;
	}

	private MerkleEntityId deserializeEntityId(SeekableByteChannel sbc) throws IOException {
		int MAX_ID_SIZE = 1 * 1_024;
		long loc = sbc.position();

		var backing = backingBytes(MAX_ID_SIZE);
		var buffer = ByteBuffer.wrap(backing, 6, MAX_ID_SIZE - 6);
		sbc.read(buffer);

		MerkleEntityId id;
		try (MerkleDataInputStream in = new MerkleDataInputStream(new ByteArrayInputStream(backing), false)) {
			id = in.readMerkleTree(1);
		}

		sbc.position(loc);
		return id;
	}

	private byte[] backingBytes(int size) {
		byte[] backing = new byte[size + 6];
		System.arraycopy(Ints.toByteArray(1), 0, backing, 0, 4);
		backing[4] = 0;
		backing[5] = 0;
		return backing;
	}

//	private MerkleAccount deserializeFrom(SeekableByteChannel sbc) throws IOException {
//		int SERIALIZED_SIZE = 1 * 1_024 * 1_024;
//		long accountLoc = sbc.position();
//		var buffer = ByteBuffer.allocate(SERIALIZED_SIZE);
//		sbc.read(buffer);
//
//		sbc.position(accountLoc + 1);
//	}

	private boolean seekToNext(SeekableByteChannel sbc, byte[] id) throws IOException {
		final int BUFFER_SIZE = 4_096;

		long total = sbc.size();
		long cur = sbc.position();

		while (true) {
			var buffer = ByteBuffer.allocate(BUFFER_SIZE);
			sbc.read(buffer);
			int here = firstOf(buffer.array(), id);
			if (here != -1) {
				long foundLoc = (cur + here);
				sbc.position(foundLoc);
				return true;
			} else {
				cur += BUFFER_SIZE;
				if (cur >= total) {
					return false;
				}
			}
		}
	}

	private int firstOf(byte[] backing, byte[] id) {
		int nextI = 0;
		for (int i = 0, n = backing.length; i < n; i++) {
			if (backing[i] == id[nextI]) {
				nextI++;
				if (nextI == 8) {
					return i - 7;
				}
			} else {
				nextI = 0;
			}
		}
		return -1;
	}
}
