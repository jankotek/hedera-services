package com.hedera.services.records;

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

import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.EntityCreator;
import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Optional;

/**
 * Provides a {@link AccountRecordsHistorian} using the natural collaborators.
 *
 * @author Michael Tinker
 */
public class TxnAwareRecordsHistorian implements AccountRecordsHistorian {
	private static final Logger log = LogManager.getLogger(TxnAwareRecordsHistorian.class);

	private ExpirableTxnRecord lastExpirableRecord;

	private EntityCreator creator;

	private final RecordCache recordCache;
	private final ExpiryManager expiries;
	private final TransactionContext txnCtx;

	public TxnAwareRecordsHistorian(RecordCache recordCache, TransactionContext txnCtx, ExpiryManager expiries) {
		this.expiries = expiries;
		this.txnCtx = txnCtx;
		this.recordCache = recordCache;
	}

	@Override
	public Optional<ExpirableTxnRecord> lastCreatedRecord() {
		return Optional.ofNullable(lastExpirableRecord);
	}

	@Override
	public void setCreator(EntityCreator creator) {
		this.creator = creator;
	}

	@Override
	public void finalizeExpirableTransactionRecord() {
		lastExpirableRecord = txnCtx.recordSoFar();
	}

	@Override
	public void saveExpirableTransactionRecord() {
		long now = txnCtx.consensusTime().getEpochSecond();
		long submittingMember = txnCtx.submittingSwirldsMember();
		var accessor = txnCtx.accessor();
		var payerRecord = creator.saveExpiringRecord(
				txnCtx.effectivePayer(),
				lastExpirableRecord,
				now,
				submittingMember);
		recordCache.setPostConsensus(
				accessor.getTxnId(),
				ResponseCodeEnum.valueOf(lastExpirableRecord.getReceipt().getStatus()),
				payerRecord);
	}

	@Override
	public void reviewExistingRecords() {
		expiries.reviewExistingPayerRecords();
	}

	@Override
	public void noteNewExpirationEvents() {
		for (var expiringEntity : txnCtx.expiringEntities()) {
			expiries.trackExpirationEvent(
					Pair.of(expiringEntity.id().num(), expiringEntity.consumer()),
					expiringEntity.expiry());
		}
	}
}
