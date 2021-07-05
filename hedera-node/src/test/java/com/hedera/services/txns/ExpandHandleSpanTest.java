package com.hedera.services.txns;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.txns.span.SpanMapManager;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.swirlds.common.SwirldTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class ExpandHandleSpanTest {
	@Mock
	private SpanMapManager handleSpanMap;

	private final long duration = 20;
	private final TimeUnit testUnit = TimeUnit.MILLISECONDS;

	private final byte[] validTxnBytes = Transaction.newBuilder()
			.setBodyBytes(TransactionBody.newBuilder()
					.setTransactionID(TransactionID.newBuilder()
							.setTransactionValidStart(Timestamp.newBuilder()
									.setSeconds(1_234_567L)
									.build())
							.setAccountID(IdUtils.asAccount("0.0.1234")))
					.build()
					.toByteString())
			.build().toByteArray();

	private final SwirldTransaction validTxn = new SwirldTransaction(validTxnBytes);
	private final SwirldTransaction invalidTxn = new SwirldTransaction("NONSENSE".getBytes());

	private ExpandHandleSpan subject;

	@BeforeEach
	void setUp() {
		subject = new ExpandHandleSpan(duration, testUnit, handleSpanMap);
	}

	@Test
	void propagatesIpbe() {
		// expect:
		assertThrows(InvalidProtocolBufferException.class, ()  -> subject.track(invalidTxn));
		assertThrows(InvalidProtocolBufferException.class, ()  -> subject.accessorFor(invalidTxn));
	}

	@Test
	void expandsOnTracking() throws InvalidProtocolBufferException {
		// when:
		final var startAccessor = subject.track(validTxn);
		// and:
		final var endAccessor = subject.accessorFor(validTxn);

		// then:
		assertSame(startAccessor, endAccessor);
		// and:
		Mockito.verify(handleSpanMap).expandSpan(startAccessor);
		Mockito.verify(handleSpanMap).rationalizeSpan(endAccessor);
	}

	@Test
	void reExpandsIfNotCached() throws InvalidProtocolBufferException {
		// when:
		final var endAccessor = subject.accessorFor(validTxn);

		Mockito.verify(handleSpanMap).expandSpan(endAccessor);
	}
}
