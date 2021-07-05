package com.hedera.services.stream;

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

import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.swirlds.common.crypto.AbstractSerializableHashable;
import com.swirlds.common.crypto.RunningHash;
import com.swirlds.common.crypto.SerializableRunningHashable;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.stream.Timestamped;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import java.io.IOException;
import java.time.Instant;

/**
 * Contains a TransactionRecord, its related Transaction, and consensus Timestamp of the Transaction.
 * Is used for record streaming
 */
public class RecordStreamObject extends AbstractSerializableHashable implements Timestamped,
		SerializableRunningHashable {
	private static final long CLASS_ID = 0xe370929ba5429d8bL;
	static final int CLASS_VERSION = 1;

	private static final int MAX_RECORD_LENGTH = 64 * 1024;
	private static final int MAX_TRANSACTION_LENGTH = 64 * 1024;

	/* The gRPC transaction for the record stream file */
	private Transaction transaction;
	private TransactionRecord transactionRecord;
	/* The fast-copyable equivalent of the gRPC transaction record for the record stream file */
	private ExpirableTxnRecord fcTransactionRecord;

	/* The consensus timestamp of this object's transaction; determines when to start a
	 * new record stream file, and the name to use for a new file if started. However,
	 * this field is NOT itself included in the record stream. */
	private Instant consensusTimestamp;

	/* The running hash of all objects streamed up to and including this consensus time. */
	private RunningHash runningHash;

	public RecordStreamObject() {
	}

	public RecordStreamObject(
			final TransactionRecord transactionRecord,
			final Transaction transaction,
			final Instant consensusTimestamp
	) {
		this.transaction = transaction;
		this.consensusTimestamp = consensusTimestamp;
		this.transactionRecord = transactionRecord;

		runningHash = new RunningHash();
	}

	public RecordStreamObject(
			final ExpirableTxnRecord fcTransactionRecord,
			final Transaction transaction,
			final Instant consensusTimestamp
	) {
		this.transaction = transaction;
		this.consensusTimestamp = consensusTimestamp;
		this.fcTransactionRecord = fcTransactionRecord;

		runningHash = new RunningHash();
	}

	@Override
	public void serialize(SerializableDataOutputStream out) throws IOException {
		ensureNonNullGrpcRecord();
		out.writeByteArray(transactionRecord.toByteArray());
		out.writeByteArray(transaction.toByteArray());
	}

	@Override
	public void deserialize(SerializableDataInputStream in, int version) throws IOException {
		transactionRecord = TransactionRecord.parseFrom(in.readByteArray(MAX_RECORD_LENGTH));
		transaction = Transaction.parseFrom(in.readByteArray(MAX_TRANSACTION_LENGTH));

		final var timestamp = transactionRecord.getConsensusTimestamp();
		consensusTimestamp = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public long getClassId() {
		return CLASS_ID;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getVersion() {
		return CLASS_VERSION;
	}

	@Override
	public Instant getTimestamp() {
		return consensusTimestamp;
	}

	@Override
	public String toString() {
		ensureNonNullGrpcRecord();
		return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
				.append("TransactionRecord", transactionRecord)
				.append("Transaction", transaction)
				.append("ConsensusTimestamp", consensusTimestamp).toString();
	}

	String toShortString() {
		ensureNonNullGrpcRecord();
		return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE)
				.append("TransactionRecord", toShortStringRecord(transactionRecord))
				.append("ConsensusTimestamp", consensusTimestamp)
				.toString();
	}

	static String toShortStringRecord(TransactionRecord transactionRecord) {
		return new ToStringBuilder(transactionRecord, ToStringStyle.NO_CLASS_NAME_STYLE)
				.append("TransactionID", transactionRecord.getTransactionID())
				.toString();
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || obj.getClass() != getClass()) {
			return false;
		}
		if (this == obj) {
			return true;
		}
		RecordStreamObject that = (RecordStreamObject) obj;
		ensureNonNullGrpcRecord();
		that.ensureNonNullGrpcRecord();
		return new EqualsBuilder()
				.append(this.transactionRecord, that.transactionRecord)
				.append(this.transaction, that.transaction)
				.append(this.consensusTimestamp, that.consensusTimestamp)
				.isEquals();
	}

	@Override
	public int hashCode() {
		ensureNonNullGrpcRecord();
		return new HashCodeBuilder()
				.append(transactionRecord)
				.append(transaction)
				.append(consensusTimestamp)
				.toHashCode();
	}

	@Override
	public RunningHash getRunningHash() {
		return runningHash;
	}

	Transaction getTransaction() {
		return transaction;
	}

	TransactionRecord getTransactionRecord() {
		ensureNonNullGrpcRecord();
		return transactionRecord;
	}

	private void ensureNonNullGrpcRecord() {
		if (transactionRecord == null) {
			transactionRecord = fcTransactionRecord.asGrpc();
		}
	}
}
