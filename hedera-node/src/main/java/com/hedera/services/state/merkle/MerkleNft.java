package com.hedera.services.state.merkle;

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

import com.google.common.base.MoreObjects;
import com.hedera.services.state.serdes.DomainSerdes;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.swirlds.common.FCMValue;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;

import java.io.IOException;
import java.util.Objects;

import static com.hedera.services.state.merkle.MerkleAccountState.DEFAULT_MEMO;

public class MerkleNft extends AbstractMerkleLeaf implements FCMValue {

	static final int MERKLE_VERSION = 1;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0x899641dafcc39164L;

	public static final int UPPER_BOUND_MEMO_UTF8_BYTES = 1024;

	static DomainSerdes serdes = new DomainSerdes();

	private EntityId owner;
	private RichInstant creationTime;
	private String memo = DEFAULT_MEMO;

	public MerkleNft(
			EntityId owner,
			String memo,
			RichInstant creationTime
	) {
		this.owner = owner;
		this.memo = memo;
		this.creationTime = creationTime;
	}

	public MerkleNft() {
		/* No-op. */
	}

	/* Object */
	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || MerkleNft.class != o.getClass()) {
			return false;
		}

		var that = (MerkleNft) o;
		return this.owner.equals(that.owner) &&
				Objects.equals(this.memo, that.memo) &&
				Objects.equals(creationTime, that.creationTime);
	}

	@Override
	public int hashCode() {
		return Objects.hash(
				owner,
				creationTime,
				memo);
	}

	@Override
	public String toString() {
		return MoreObjects.toStringHelper(MerkleNft.class)
				.add("owner", owner)
				.add("creationTime", creationTime)
				.add("memo", memo)
				.toString();
	}

	/* --- MerkleLeaf --- */

	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int i) throws IOException {
		owner = in.readSerializable();
		creationTime = serdes.deserializeTimestamp(in);
		memo = in.readNormalisedString(UPPER_BOUND_MEMO_UTF8_BYTES);
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		out.writeSerializable(owner, true);
		serdes.serializeTimestamp(creationTime, out);
		out.writeNormalisedString(memo);
	}

	/* --- FastCopyable --- */

	@Override
	public MerkleNft copy() {
		return new MerkleNft(owner, memo, creationTime);
	}

	public EntityId getOwner() {
		return owner;
	}

	public String getMemo() {
		return memo;
	}

	public RichInstant getCreationTime() {
		return creationTime;
	}
}
