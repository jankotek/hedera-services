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
import com.hedera.services.exceptions.DeletedAccountException;
import com.hedera.services.exceptions.MissingAccountException;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoDeleteTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.inject.Inject;

import static com.hedera.test.utils.IdUtils.asAccount;

import java.time.Instant;

import static junit.framework.TestCase.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.BDDMockito.*;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;

@ExtendWith(LogCaptureExtension.class)
public class CryptoDeleteTransitionLogicTest {
	final private AccountID payer = AccountID.newBuilder().setAccountNum(1_234L).build();
	final private AccountID target = AccountID.newBuilder().setAccountNum(9_999L).build();
	final private boolean withKnownTreasury = true;

	private HederaLedger ledger;
	private TransactionBody cryptoDeleteTxn;
	private TransactionContext txnCtx;
	private PlatformTxnAccessor accessor;

	@Inject
	private LogCaptor logCaptor;
	@LoggingSubject
	private CryptoDeleteTransitionLogic subject;

	@BeforeEach
	private void setup() {
		txnCtx = mock(TransactionContext.class);
		ledger = mock(HederaLedger.class);
		accessor = mock(PlatformTxnAccessor.class);

		given(ledger.allTokenBalancesVanish(target)).willReturn(true);

		subject = new CryptoDeleteTransitionLogic(ledger, txnCtx);
	}

	@Test
	void hasCorrectApplicability() {
		givenValidTxnCtx();

		// expect:
		assertTrue(subject.applicability().test(cryptoDeleteTxn));
		assertFalse(subject.applicability().test(TransactionBody.getDefaultInstance()));
	}

	@Test
	void rejectsTargetAsBeneficiary() {
		givenValidTxnCtx(target);

		// expect:
		assertEquals(TRANSFER_ACCOUNT_SAME_AS_DELETE_ACCOUNT, subject.semanticCheck().apply(cryptoDeleteTxn));
	}

	@Test
	void acceptsValidTxn() {
		givenValidTxnCtx();

		// expect:
		assertEquals(OK, subject.semanticCheck().apply(cryptoDeleteTxn));
	}

	@Test
	void translatesMissingAccount() {
		givenValidTxnCtx();
		willThrow(MissingAccountException.class).given(ledger).delete(any(), any());

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(INVALID_ACCOUNT_ID);
	}

	@Test
	void translatesDeletedAccount() {
		givenValidTxnCtx();
		willThrow(DeletedAccountException.class).given(ledger).delete(any(), any());

		// when:
		subject.doStateTransition();

		// then:
		verify(txnCtx).setStatus(ACCOUNT_DELETED);
	}

	@Test
	void followsHappyPath() {
		givenValidTxnCtx();

		// when:
		subject.doStateTransition();

		// then:
		verify(ledger).delete(target, payer);
		verify(txnCtx).setStatus(SUCCESS);
	}

	@Test
	void rejectsDetachedAccountAsTarget() {
		// setup:
		givenValidTxnCtx();
		given(ledger.isDetached(target)).willReturn(true);

		// when:
		subject.doStateTransition();

		// when:
		verify(ledger, never()).delete(target, payer);
		verify(txnCtx).setStatus(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
	}

	@Test
	void rejectsDetachedAccountAsReceiver() {
		// setup:
		var receiver = IdUtils.asAccount("0.0.7676");

		givenValidTxnCtx(receiver);
		given(ledger.isDetached(receiver)).willReturn(true);

		// when:
		subject.doStateTransition();

		// when:
		verify(ledger, never()).delete(target, receiver);
		verify(txnCtx).setStatus(ACCOUNT_EXPIRED_AND_PENDING_REMOVAL);
	}

	@Test
	void capturesFailInvalid() {
		// setup:
		givenValidTxnCtx();
		given(ledger.isKnownTreasury(target)).willThrow(RuntimeException.class);
		// and:
		var desired = "Avoidable exception! java.lang.RuntimeException: null";

		// when:
		subject.doStateTransition();

		// when:
		verify(txnCtx).setStatus(FAIL_INVALID);
		assertThat(logCaptor.warnLogs(), contains(desired));
	}

	@Test
	void rejectsDeletionOfKnownTreasury() {
		// setup:
		givenValidTxnCtx();
		given(ledger.isKnownTreasury(target)).willReturn(withKnownTreasury);

		// when:
		subject.doStateTransition();

		// when:
		verify(ledger, never()).delete(target, payer);
		verify(txnCtx).setStatus(ACCOUNT_IS_TREASURY);
	}

	@Test
	void rejectsIfTargetHasNonZeroTokenBalances() {
		givenValidTxnCtx();
		given(ledger.allTokenBalancesVanish(target)).willReturn(false);

		// when:
		subject.doStateTransition();

		// then:
		verify(ledger, never()).delete(target, payer);
		verify(txnCtx).setStatus(TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES);
	}

	@Test
	void rejectsIfTargetMissing() {
		givenDeleteTxnMissingTarget();

		// when:
		ResponseCodeEnum validity = subject.semanticCheck().apply(cryptoDeleteTxn);

		// then:
		assertEquals(ACCOUNT_ID_DOES_NOT_EXIST, validity);
	}

	@Test
	void rejectsIfTransferMissing() {
		givenDeleteTxnMissingTransfer();

		// when:
		ResponseCodeEnum validity = subject.semanticCheck().apply(cryptoDeleteTxn);

		// then:
		assertEquals(ACCOUNT_ID_DOES_NOT_EXIST, validity);
	}

	private void givenValidTxnCtx() {
		givenValidTxnCtx(payer);
	}

	private void givenValidTxnCtx(AccountID transfer) {
		cryptoDeleteTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoDelete(
						CryptoDeleteTransactionBody.newBuilder()
								.setDeleteAccountID(target)
								.setTransferAccountID(transfer)
								.build()
				).build();
		given(accessor.getTxn()).willReturn(cryptoDeleteTxn);
		given(txnCtx.accessor()).willReturn(accessor);
	}

	private void givenDeleteTxnMissingTarget() {
		cryptoDeleteTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoDelete(
						CryptoDeleteTransactionBody.newBuilder()
								.setTransferAccountID(asAccount("0.0.1234"))
								.build()
				).build();
	}

	private void givenDeleteTxnMissingTransfer() {
		cryptoDeleteTxn = TransactionBody.newBuilder()
				.setTransactionID(ourTxnId())
				.setCryptoDelete(
						CryptoDeleteTransactionBody.newBuilder()
								.setDeleteAccountID(asAccount("0.0.1234"))
								.build()
				).build();
	}

	private TransactionID ourTxnId() {
		return TransactionID.newBuilder()
				.setAccountID(payer)
				.setTransactionValidStart(
						Timestamp.newBuilder().setSeconds(Instant.now().getEpochSecond()))
				.build();
	}
}
