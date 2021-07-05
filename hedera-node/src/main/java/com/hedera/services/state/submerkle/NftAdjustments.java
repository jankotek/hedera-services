package com.hedera.services.state.submerkle;

/*-
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
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

import com.google.common.base.MoreObjects;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import static com.hedera.services.utils.MiscUtils.readableNftTransferList;
import static java.util.stream.Collectors.toList;

public class NftAdjustments implements SelfSerializable {

	private static final int MERKLE_VERSION = 1;
	private static final long RUNTIME_CONSTRUCTABLE_ID = 0xd7a02bf45e103466L;
	private static final long[] NO_ADJUSTMENTS = new long[0];

	static final int MAX_NUM_ADJUSTMENTS = 1024;

	private long[] serialNums = NO_ADJUSTMENTS;
	private List<EntityId> senderAccIds = Collections.emptyList();
	private List<EntityId> receiverAccIds = Collections.emptyList();

	public NftAdjustments() {
		/* RuntimeConstructable */
	}

	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		serialNums = in.readLongArray(MAX_NUM_ADJUSTMENTS);
		senderAccIds = in.readSerializableList(MAX_NUM_ADJUSTMENTS, true, EntityId::new);
		receiverAccIds = in.readSerializableList(MAX_NUM_ADJUSTMENTS, true, EntityId::new);
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeLongArray(serialNums);
		out.writeSerializableList(senderAccIds, true, true);
		out.writeSerializableList(receiverAccIds, true, true);
	}

	/* ---- Object --- */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		NftAdjustments that = (NftAdjustments) o;
		return Arrays.equals(serialNums, that.serialNums)
				&& senderAccIds.equals(that.senderAccIds) && receiverAccIds.equals(that.receiverAccIds);
	}

	@Override
	public int hashCode() {
		int result = Long.hashCode(RUNTIME_CONSTRUCTABLE_ID);
		result = result * 31 + senderAccIds.hashCode();
		result = result * 31 + receiverAccIds.hashCode();
		return result * 31 + Arrays.hashCode(serialNums);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(this)
				.add("readable", readableNftTransferList(toGrpc()))
				.toString();
	}

	/* --- Helpers --- */
	public TokenTransferList toGrpc() {
		var grpc = TokenTransferList.newBuilder();
		IntStream.range(0, serialNums.length)
				.mapToObj(i -> NftTransfer.newBuilder()
						.setSerialNumber(serialNums[i])
						.setSenderAccountID(EntityIdUtils.asAccount(senderAccIds.get(i)))
						.setReceiverAccountID(EntityIdUtils.asAccount(receiverAccIds.get(i))))
				.forEach(grpc::addNftTransfers);

		return grpc.build();
	}

	public static NftAdjustments fromGrpc(List<NftTransfer> grpc) {
		var pojo = new NftAdjustments();

		pojo.serialNums = grpc.stream()
				.mapToLong(NftTransfer::getSerialNumber)
				.toArray();
		pojo.senderAccIds = grpc.stream()
				.filter(nftTransfer -> nftTransfer.getSenderAccountID() != null)
				.map(NftTransfer::getSenderAccountID)
				.map(EntityId::fromGrpcAccountId)
				.collect(toList());
		pojo.receiverAccIds = grpc.stream()
				.map(NftTransfer::getReceiverAccountID)
				.map(EntityId::fromGrpcAccountId)
				.collect(toList());

		return pojo;
	}
}
