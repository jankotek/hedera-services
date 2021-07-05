package com.hedera.services.usage.token;

/*-
 * ‌
 * Hedera Services API Fees
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

import com.hedera.services.test.IdUtils;
import com.hedera.services.usage.EstimatorFactory;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.TxnUsage;
import com.hedera.services.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.List;

import static com.hedera.services.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.services.usage.SingletonUsageProperties.USAGE_PROPERTIES;
import static com.hedera.services.usage.token.entities.TokenEntitySizes.TOKEN_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static com.hederahashgraph.fee.FeeBuilder.LONG_SIZE;
import static org.junit.Assert.assertEquals;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;

public class TokenBurnUsageTest {
	long now = 1_234_567L;
	int numSigs = 3, sigSize = 100, numPayerKeys = 1;
	SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
	TokenID id = IdUtils.asToken("0.0.75231");

	TokenBurnTransactionBody op;
	TransactionBody txn;

	EstimatorFactory factory;
	TxnUsageEstimator base;
	TokenBurnUsage subject;

	@BeforeEach
	public void setUp() throws Exception {
		base = mock(TxnUsageEstimator.class);
		given(base.get()).willReturn(A_USAGES_MATRIX);

		factory = mock(EstimatorFactory.class);
		given(factory.get(any(), any(), any())).willReturn(base);

		TxnUsage.estimatorFactory = factory;
	}

	@Test
	 void createsExpectedDelta() {
		givenOp();
		// and:
		subject = TokenBurnUsage.newEstimate(txn, sigUsage);
		subject.givenSubType(SubType.TOKEN_FUNGIBLE_COMMON);
		given(base.get(SubType.TOKEN_FUNGIBLE_COMMON)).willReturn(A_USAGES_MATRIX);
		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		verify(base).addBpt(8);
		verify(base).addBpt(BASIC_ENTITY_ID_SIZE);
		verify(base).addRbs(
				TOKEN_ENTITY_SIZES.bytesUsedToRecordTokenTransfers(1, 1, 0) *
						USAGE_PROPERTIES.legacyReceiptStorageSecs());
	}

	@Test
	 void createsExpectedDeltaForUnique() {
		op = TokenBurnTransactionBody.newBuilder()
				.setToken(id)
				.addAllSerialNumbers(List.of(1L, 2L, 3L))
				.build();
		setTxn();
		// and:
		subject = TokenBurnUsage.newEstimate(txn, sigUsage).givenSubType(SubType.TOKEN_NON_FUNGIBLE_UNIQUE);
		given(base.get(SubType.TOKEN_NON_FUNGIBLE_UNIQUE)).willReturn(A_USAGES_MATRIX);
		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		InOrder inOrder = Mockito.inOrder(base);
		inOrder.verify(base).addBpt((long) op.getSerialNumbersCount() * LONG_SIZE);
		inOrder.verify(base)
				.addBpt(BASIC_ENTITY_ID_SIZE);
	}

	@Test
	void selfTest() {
		subject = TokenBurnUsage.newEstimate(txn, sigUsage).givenSubType(SubType.TOKEN_NON_FUNGIBLE_UNIQUE);
		assertEquals(subject, subject.self());
	}

	private void givenOp() {
		op = TokenBurnTransactionBody.newBuilder()
				.setToken(id)
				.setAmount(10)
				.build();
		setTxn();
	}

	private void setTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setTokenBurn(op)
				.build();
	}
}