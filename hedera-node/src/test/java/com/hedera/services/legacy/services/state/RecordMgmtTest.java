package com.hedera.services.legacy.services.state;

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

import com.hedera.services.context.ServicesContext;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.state.submerkle.ExpirableTxnRecord;
import com.hedera.services.stream.NonBlockingHandoff;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.stream.RecordStreamObject;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


@ExtendWith(MockitoExtension.class)
class RecordMgmtTest {
	private final Instant consensusNow = Instant.ofEpochSecond(1_234_567L);

	@Mock
	private TxnAccessor txnAccessor;
	@Mock
	private ServicesContext ctx;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private AccountRecordsHistorian recordsHistorian;
	@Mock
	private RecordStreamManager recordStreamManager;
	@Mock
	private NonBlockingHandoff nonBlockingHandoff;

	private AwareProcessLogic subject;

	@BeforeEach
	void setUp() {
		subject = new AwareProcessLogic(ctx);
	}

	@Test
	void streamsRecordIfPresent() {
		// setup:
		final Transaction txn = Transaction.getDefaultInstance();
		final ExpirableTxnRecord lastRecord = ExpirableTxnRecord.newBuilder().build();
		final RecordStreamObject expectedRso = new RecordStreamObject(lastRecord, txn, consensusNow);

		given(txnAccessor.getSignedTxnWrapper()).willReturn(txn);
		given(txnCtx.accessor()).willReturn(txnAccessor);
		given(txnCtx.consensusTime()).willReturn(consensusNow);
		given(recordsHistorian.lastCreatedRecord()).willReturn(Optional.of(lastRecord));
		given(ctx.recordsHistorian()).willReturn(recordsHistorian);
		given(ctx.txnCtx()).willReturn(txnCtx);
		given(ctx.nonBlockingHandoff()).willReturn(nonBlockingHandoff);
		given(nonBlockingHandoff.offer(expectedRso)).willReturn(true);

		// when:
		subject.addRecordToStream();

		// then:
		verify(nonBlockingHandoff).offer(expectedRso);
	}

	@Test
	void doesNothingIfNoLastCreatedRecord() {
		given(recordsHistorian.lastCreatedRecord()).willReturn(Optional.empty());
		given(ctx.recordsHistorian()).willReturn(recordsHistorian);

		// when:
		subject.addRecordToStream();

		// then:
		verifyNoInteractions(recordStreamManager);
	}
}
