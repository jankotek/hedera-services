package com.hedera.services.txns.crypto;

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
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.grpc.marshalling.ImpliedTransfers;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.txns.span.ExpandHandleSpanMapAccessor;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransferList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.hedera.test.utils.IdUtils.adjustFrom;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static com.hedera.test.utils.IdUtils.hbarChange;
import static com.hedera.test.utils.TxnUtils.withAdjustments;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


@ExtendWith(MockitoExtension.class)
class CryptoTransferTransitionLogicTest {
	final private int maxHbarAdjusts = 5;
	final private int maxTokenAdjusts = 10;
	final private AccountID payer = AccountID.newBuilder().setAccountNum(1_234L).build();
	final private AccountID a = AccountID.newBuilder().setAccountNum(9_999L).build();
	final private AccountID b = AccountID.newBuilder().setAccountNum(8_999L).build();
	final private AccountID c = AccountID.newBuilder().setAccountNum(7_999L).build();

	@Mock
	private HederaLedger ledger;
	@Mock
	private TransactionContext txnCtx;
	@Mock
	private GlobalDynamicProperties dynamicProperties;
	@Mock
	private ImpliedTransfersMarshal impliedTransfersMarshal;
	@Mock
	private PureTransferSemanticChecks transferSemanticChecks;
	@Mock
	private ExpandHandleSpanMapAccessor spanMapAccessor;
	@Mock
	private PlatformTxnAccessor accessor;

	private TransactionBody cryptoTransferTxn;

	private CryptoTransferTransitionLogic subject;

	@BeforeEach
	private void setup() {
		subject = new CryptoTransferTransitionLogic(
				ledger, txnCtx, dynamicProperties, impliedTransfersMarshal, transferSemanticChecks, spanMapAccessor);
	}

	@Test
	void happyPathUsesLedgerNetZero() {
		final var a = asAccount("1.2.3");
		final var b = asAccount("2.3.4");
		final var impliedTransfers = ImpliedTransfers.valid(
				maxHbarAdjusts, maxTokenAdjusts, List.of(
						hbarChange(a, +100),
						hbarChange(b, -100)
				));

		givenValidTxnCtx();
		// and:
		given(spanMapAccessor.getImpliedTransfers(accessor)).willReturn(impliedTransfers);
		given(ledger.doZeroSum(impliedTransfers.getChanges())).willReturn(INSUFFICIENT_ACCOUNT_BALANCE);

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(INSUFFICIENT_ACCOUNT_BALANCE);
	}

	@Test
	void recomputesImpliedTransfersIfNotAvailableInSpan() {
		final var a = asAccount("1.2.3");
		final var b = asAccount("2.3.4");
		final var impliedTransfers = ImpliedTransfers.valid(
				maxHbarAdjusts, maxTokenAdjusts, List.of(
						hbarChange(a, +100),
						hbarChange(b, -100)
				));

		givenValidTxnCtx();
		given(accessor.getTxn()).willReturn(cryptoTransferTxn);
		// and:
		given(impliedTransfersMarshal.unmarshalFromGrpc(cryptoTransferTxn.getCryptoTransfer()))
				.willReturn(impliedTransfers);
		given(ledger.doZeroSum(impliedTransfers.getChanges()))
				.willReturn(OK);

		// when:
		subject.doStateTransition();


		// then:
		verify(txnCtx).setStatus(SUCCESS);
	}

	@Test
	void shortCircuitsToImpliedTransfersValidityIfNotAvailableInSpan() {
		final var impliedTransfers = ImpliedTransfers.invalid(
				maxHbarAdjusts, maxTokenAdjusts, TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);

		givenValidTxnCtx();
		given(accessor.getTxn()).willReturn(cryptoTransferTxn);
		// and:
		given(impliedTransfersMarshal.unmarshalFromGrpc(cryptoTransferTxn.getCryptoTransfer()))
				.willReturn(impliedTransfers);

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);
		verifyNoInteractions(ledger);
	}

	@Test
	void reusesPrecomputedFailureIfImpliedTransfersInSpan() {
		// setup:
		final var impliedTransfers = ImpliedTransfers.invalid(
				maxHbarAdjusts, maxTokenAdjusts, TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);

		given(spanMapAccessor.getImpliedTransfers(accessor)).willReturn(impliedTransfers);

		// when:
		final var validity = subject.validateSemantics(accessor);

		// then:
		assertEquals(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN, validity);
	}

	@Test
	void computesFailureIfImpliedTransfersNotInSpan() {
		// setup:
		final var pretendXferTxn = TransactionBody.getDefaultInstance();

		given(dynamicProperties.maxTransferListSize()).willReturn(maxHbarAdjusts);
		given(dynamicProperties.maxTokenTransferListSize()).willReturn(maxTokenAdjusts);
		given(accessor.getTxn()).willReturn(pretendXferTxn);
		given(transferSemanticChecks.fullPureValidation(
				maxHbarAdjusts,
				maxTokenAdjusts,
				pretendXferTxn.getCryptoTransfer().getTransfers(),
				pretendXferTxn.getCryptoTransfer().getTokenTransfersList())
		)
				.willReturn(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN);

		// when:
		final var validity = subject.validateSemantics(accessor);

		// then:
		assertEquals(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN, validity);
	}

	@Test
	void translatesUnknownException() {
		givenValidTxnCtx(withAdjustments(a, -2L, b, 1L, c, 1L));

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(FAIL_INVALID);
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx(withAdjustments(a, -2L, b, 1L, c, 1L));

		// expect:
		assertTrue(subject.applicability().test(cryptoTransferTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	private void givenValidTxnCtx(TransferList wrapper) {
		cryptoTransferTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoTransfer(
						CryptoTransferTransactionBody.newBuilder()
								.setTransfers(wrapper)
								.build()
				).build();
	}

	private void givenValidTxnCtx() {
		cryptoTransferTxn = TransactionBody.newBuilder()
				.setCryptoTransfer(xfers)
				.build();
		given(txnCtx.accessor()).willReturn(accessor);
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
				.build();
	}

	CryptoTransferTransactionBody xfers = CryptoTransferTransactionBody.newBuilder()
			.setTransfers(TransferList.newBuilder()
					.addAccountAmounts(adjustFrom(asAccount("0.0.75231"), -1_000))
					.addAccountAmounts(adjustFrom(asAccount("0.0.2"), +1_000))
					.build())
			.addTokenTransfers(TokenTransferList.newBuilder()
					.setToken(asToken("0.0.12345"))
					.addAllTransfers(List.of(
							adjustFrom(asAccount("0.0.2"), -1_000),
							adjustFrom(asAccount("0.0.3"), +1_000)
					)))
			.build();

}
