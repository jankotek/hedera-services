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

import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleAccountState;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleTopic;
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
import java.util.concurrent.TimeUnit;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

@Disabled
@RunWith(JUnitPlatform.class)
public class AccountExtractor {

	static byte[] FCQ = Longs.toByteArray(0x000000206b1fa797L);
	static byte[] ACCOUNT_STATE = Longs.toByteArray(0x354cfc55834e7f12L);
	static byte[] MERKLE_TOPIC = Longs.toByteArray(0xcfc535576b57baf0L);
	static byte[] MERKLE_ACCOUNT = Longs.toByteArray(0x950bcf7255691908L);
	static byte[] MERKLE_ENTITY_ID = Longs.toByteArray(0xd5dd2ebaa0bde03L);

	static Set<Long> USUAL_SUSPECTS = Set.of(
			137705L,
			137706L,
			137707L,
			137708L,
			137709L,
			137710L
	);

	static Set<Long> LOCAL_SUSPECTS = Set.of(1001L, 1002L);

	static final String savedStateLoc = "/Users/tinkerm/Dev/hgn3/hedera-services/SignedState.swh";

	private long prevEntity = -1;

	private long nextFcq = 0;
	private long nextState = 0;
	private long nextTopic = 0;
	private long nextEntity = 0;
	private long nextAccount = 0;

	int[] totalRecordCounts = new int[2];

	@Test
	public void extractAccounts() throws IOException, ConstructableRegistryException {
		AccountsReader.registerConstructables();

//		var sbc = Files.newByteChannel(Paths.get(localLoc(903)));
		var sbc = Files.newByteChannel(Paths.get(savedStateLoc));

		extractTopics(sbc);
//		USUAL_SUSPECTS = LOCAL_SUSPECTS;
		var accounts = extractAccounts(sbc);

		PojoLedger.from(accounts).asJsonTo(loc("suspects"));
	}

	private FCMap<MerkleEntityId, MerkleTopic> extractTopics(SeekableByteChannel sbc) throws IOException {
		FCMap<MerkleEntityId, MerkleTopic> topics =
				new FCMap<>(new MerkleEntityId.Provider(), MerkleTopic.LEGACY_PROVIDER);

		int numScanned = 0;
		Stopwatch watch = Stopwatch.createStarted();
		while (seekToNext(sbc, MERKLE_ENTITY_ID)) {
			numScanned++;
			if (numScanned % 1_000 == 0) {
				System.out.println("Scanned " + numScanned + " topics in " + watch.elapsed(TimeUnit.SECONDS) + "s");
			}
			if (!seekToNext(sbc, MERKLE_TOPIC, (numScanned > 0) ? 2_000 : Integer.MAX_VALUE)) {
				numScanned--;
				nextEntity = prevEntity;
				System.out.println(nextEntity);
				break;
			}
		}
		System.out.println("------------");
		System.out.println("END - " + numScanned + " topics scanned in " + watch.elapsed(TimeUnit.SECONDS) + "s");
		return topics;
	}

	private static final int THRESHOLD = 0;
	private static final int PAYER = 1;
	private FCMap<MerkleEntityId, MerkleAccount> extractAccounts(SeekableByteChannel sbc) throws IOException {
		FCMap<MerkleEntityId, MerkleAccount> accounts =
				new FCMap<>(new MerkleEntityId.Provider(), MerkleAccount.LEGACY_PROVIDER);

		System.out.println("Starting with nextEntity: " + nextEntity);
		int numScanned = 0;
		Stopwatch watch = Stopwatch.createStarted();
		while (seekToNext(sbc, MERKLE_ENTITY_ID)) {
			numScanned++;
			if (numScanned % 1_000 == 0) {
				System.out.println("Scanned " + numScanned + " accounts in " + watch.elapsed(TimeUnit.SECONDS) + "s");
				System.out.println("  ==> " + Arrays.toString(totalRecordCounts));
			}
			var id = deserializeEntityId(sbc);
			if (!seekToNext(sbc, MERKLE_ACCOUNT)) {
				numScanned--;
				break;
			}
//			System.out.println("Next account @ " + sbc.position());
			int[] recordCounts = new int[0];
			var account = deserializeAccount(
					id.getNum(),
					sbc,
					recordCounts,
					false,
					false);
			if (recordCounts.length == 2) {
				totalRecordCounts[THRESHOLD] += recordCounts[THRESHOLD];
				totalRecordCounts[PAYER] += recordCounts[PAYER];
			}
//			System.out.println("  ==>> " + account);
			if (include(id, account)) {
				accounts.put(id, account);
//				System.out.println(account);
			}
		}
		System.out.println("------------");
		System.out.println("END - " + numScanned + " accounts scanned in " + watch.elapsed(TimeUnit.SECONDS) + "s");
		System.out.println("END - " + totalRecordCounts[THRESHOLD] + " threshold records");
		System.out.println("END - " + totalRecordCounts[PAYER] + " payer records");
		return accounts;
	}

	private String loc(String desc) {
		return String.format("/Users/tinkerm/Dev/hgn3/hedera-services/%s.json", desc);
	}

	private String localLoc(int round) {
		return String.format("/Users/tinkerm/Dev/hgn3/hedera-services/hedera-node/data/saved/" +
				"com.hedera.services.ServicesMain/0/123/%d/SignedState.swh", round);
	}

	private boolean include(MerkleEntityId id, MerkleAccount account) {
//		System.out.println(account.state());
//		return USUAL_SUSPECTS.contains(id.getNum());
		return account.getSenderThreshold() < 100_000_000 || account.getReceiverThreshold() < 100_000_000;
	}

	private MerkleAccount deserializeAccount(
			long forNum,
			SeekableByteChannel sbc,
			int[] recordCounts,
			boolean includePayerRecords,
			boolean includeThresholdRecords
	) throws IOException {
		long loc = sbc.position();

		MerkleAccountState state;
		if (!seekToNext(sbc, ACCOUNT_STATE)) {
			throw new IllegalStateException("No subsequent state!");
		}
		state = deserializeAccountState(sbc);

		boolean dontIgnoreRecords = recordCounts.length == 2;
		FCQueue<ExpirableTxnRecord> records = new FCQueue<>(ExpirableTxnRecord.LEGACY_PROVIDER);
		if (dontIgnoreRecords) {
			if (!seekToNext(sbc, FCQ)) {
				System.out.println("  ==> " + Arrays.toString(totalRecordCounts));
				throw new IllegalStateException("No subsequent threshold records FCQ!");
			}
			long fcqLoc1 = sbc.position();
			if (includeThresholdRecords) {
				records = deserializeRecords(sbc);
			}
			recordCounts[THRESHOLD] += readFcqSize(sbc, fcqLoc1);
		}

		FCQueue<ExpirableTxnRecord> payerRecords = new FCQueue<>(ExpirableTxnRecord.LEGACY_PROVIDER);
		if (dontIgnoreRecords) {
			if (!seekToNext(sbc, FCQ)) {
				System.out.println("  ==> " + Arrays.toString(totalRecordCounts));
				throw new IllegalStateException("No subsequent payer records FCQ!");
			}
			long fcqLoc2 = sbc.position();
			if (includePayerRecords) {
				payerRecords = deserializeRecords(sbc);
			}
			recordCounts[PAYER] += readFcqSize(sbc, fcqLoc2);
		}

		MerkleAccount account = new MerkleAccount(List.of(state, records, payerRecords));

		sbc.position(loc);
		return account;
	}

	private int readFcqSize(SeekableByteChannel sbc, long loc) throws IOException {
		sbc.position(loc);
		var buffer = ByteBuffer.allocate(16);
		sbc.read(buffer);
//		System.out.println(Hex.toHexString(buffer.array()));
		var raw = buffer.array();
		return Ints.fromBytes(raw[12], raw[13], raw[14], raw[15]);
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
		return seekToNext(sbc, id, Integer.MAX_VALUE);
	}

	private boolean seekToNext(SeekableByteChannel sbc, byte[] id, int maxBuffers) throws IOException {
		final int BUFFER_SIZE = 4_096;

		long total = sbc.size();
		long cur = searchStart(id) + 1;
		sbc.position(cur);
		while (maxBuffers-- > 0) {
			var buffer = ByteBuffer.allocate(BUFFER_SIZE);
			sbc.read(buffer);
			int here = firstOf(buffer.array(), id);
			if (here != -1) {
				long foundLoc = (cur + here);
				sbc.position(foundLoc);
				if (id == MERKLE_ENTITY_ID) {
					prevEntity = nextEntity;
				}
				updateNext(foundLoc, id);
				return true;
			} else {
				cur += BUFFER_SIZE;
				if (cur >= total) {
					return false;
				}
			}
		}
		return false;
	}

	private void updateNext(long loc, byte[] id) {
		if (id == MERKLE_ENTITY_ID) {
			nextEntity = loc;
		} else if (id == ACCOUNT_STATE) {
			nextState = loc;
		} else if (id == MERKLE_ACCOUNT) {
			nextAccount = loc;
		} else if (id == FCQ) {
			nextFcq = loc;
		} else if (id == MERKLE_TOPIC) {
			nextTopic = loc;
		}
	}

	private long searchStart(byte[] id) {
		if (id == MERKLE_ENTITY_ID) {
			return nextEntity + 1;
		} else if (id == ACCOUNT_STATE) {
			return nextState + 1;
		} else if (id == MERKLE_ACCOUNT) {
			return nextAccount + 1;
		} else if (id == FCQ) {
			return nextFcq + 1;
		} else if (id == MERKLE_TOPIC) {
			return nextTopic + 1;
		} else {
			throw new IllegalArgumentException("Nothing doing for " + Hex.toHexString(id));
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
