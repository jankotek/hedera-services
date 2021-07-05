package com.hedera.services.ledger;

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

import com.hedera.services.grpc.marshalling.ImpliedTransfersMeta;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static com.hedera.test.utils.TxnUtils.withAdjustments;
import static com.hedera.test.utils.TxnUtils.withOwnershipChanges;
import static com.hedera.test.utils.TxnUtils.withTokenAdjustments;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.BATCH_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ID_REPEATED_IN_TOKEN_LIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSFER_LIST_SIZE_LIMIT_EXCEEDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;

class PureTransferSemanticChecksTest {
	final private int maxHbarAdjusts = 5;
	final private int maxTokenAdjusts = 10;
	final private int maxOwnershipChanges = 3;
	final ImpliedTransfersMeta.ValidationProps validationProps = new ImpliedTransfersMeta.ValidationProps(
			maxHbarAdjusts, maxTokenAdjusts, maxOwnershipChanges);
	final private AccountID a = AccountID.newBuilder().setAccountNum(9_999L).build();
	final private AccountID b = AccountID.newBuilder().setAccountNum(8_999L).build();
	final private AccountID c = AccountID.newBuilder().setAccountNum(7_999L).build();
	final private AccountID d = AccountID.newBuilder().setAccountNum(6_999L).build();
	final private TokenID aTId = TokenID.newBuilder().setTokenNum(1_234L).build();
	final private TokenID bTId = TokenID.newBuilder().setTokenNum(2_345L).build();
	final private TokenID cTId = TokenID.newBuilder().setTokenNum(3_456L).build();
	final private TokenID dTId = TokenID.newBuilder().setTokenNum(4_567L).build();

	PureTransferSemanticChecks subject = new PureTransferSemanticChecks();

	@Test
	void preservesTraditionalResponseCodePriority() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, b, +2L, c, +2L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);
		// and:
		subject = mock(PureTransferSemanticChecks.class);
		InOrder inOrder = Mockito.inOrder(subject);

		given(subject.isNetZeroAdjustment(hbarAdjusts.getAccountAmountsList())).willReturn(true);
		given(subject.isAcceptableSize(hbarAdjusts.getAccountAmountsList(), maxHbarAdjusts)).willReturn(true);
		given(subject.validateTokenTransferSizes(tokenAdjusts, maxTokenAdjusts, maxOwnershipChanges)).willReturn(OK);
		given(subject.validateTokenTransferSemantics(tokenAdjusts)).willReturn(OK);
		// and:
		doCallRealMethod().when(subject)
				.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// when:
		final var result = subject.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// then:
		inOrder.verify(subject).hasRepeatedAccount(hbarAdjusts.getAccountAmountsList());
		inOrder.verify(subject).isNetZeroAdjustment(hbarAdjusts.getAccountAmountsList());
		inOrder.verify(subject).isAcceptableSize(hbarAdjusts.getAccountAmountsList(), maxHbarAdjusts);
		inOrder.verify(subject).validateTokenTransferSizes(tokenAdjusts, maxTokenAdjusts, maxOwnershipChanges);
		inOrder.verify(subject).validateTokenTransferSemantics(tokenAdjusts);
		// and:
		assertEquals(OK, result);
	}

	@Test
	void rejectsInvalidTokenSizes() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, b, +2L, c, +2L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);
		// and:
		subject = mock(PureTransferSemanticChecks.class);

		given(subject.isNetZeroAdjustment(hbarAdjusts.getAccountAmountsList())).willReturn(true);
		given(subject.isAcceptableSize(hbarAdjusts.getAccountAmountsList(), maxHbarAdjusts)).willReturn(true);
		given(subject.validateTokenTransferSizes(tokenAdjusts, maxTokenAdjusts, maxOwnershipChanges))
				.willReturn(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED);
		// and:
		doCallRealMethod().when(subject)
				.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// when:
		final var result = subject.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// then:
		assertEquals(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED, result);
	}

	@Test
	void rejectsInvalidTokenSemantics() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, b, +2L, c, +2L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);
		// and:
		subject = mock(PureTransferSemanticChecks.class);

		final var validationProps = new ImpliedTransfersMeta.ValidationProps(
				maxHbarAdjusts, maxTokenAdjusts, maxOwnershipChanges);
		// and:
		given(subject.isNetZeroAdjustment(hbarAdjusts.getAccountAmountsList())).willReturn(true);
		given(subject.isAcceptableSize(hbarAdjusts.getAccountAmountsList(), maxHbarAdjusts)).willReturn(true);
		given(subject.validateTokenTransferSizes(tokenAdjusts, maxTokenAdjusts, maxOwnershipChanges)).willReturn(OK);
		given(subject.validateTokenTransferSemantics(tokenAdjusts)).willReturn(TOKEN_ID_REPEATED_IN_TOKEN_LIST);
		// and:
		doCallRealMethod().when(subject).fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// when:
		final var result = subject.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps);

		// then:
		assertEquals(TOKEN_ID_REPEATED_IN_TOKEN_LIST, result);
	}

	@Test
	void rejectsNonNetZeroAccounts() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, b, +2L, c, +3L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);

		// expect:
		assertEquals(
				INVALID_ACCOUNT_AMOUNTS,
				subject.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps));
	}

	@Test
	void rejectsRepeatedAccounts() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, a, +2L, c, +2L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);

		// expect:
		assertEquals(
				ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS,
				subject.fullPureValidation(hbarAdjusts, tokenAdjusts, validationProps));
	}

	@Test
	void rejectsOversizeTransfers() {
		// setup:
		final var hbarAdjusts = withAdjustments(a, -4L, b, +2L, c, +2L);
		final var tokenAdjusts = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);
		// and:
		final var strictValProps = new ImpliedTransfersMeta.ValidationProps(
				1, 1, 1);

		// expect:
		assertEquals(
				TRANSFER_LIST_SIZE_LIMIT_EXCEEDED,
				subject.fullPureValidation(hbarAdjusts, tokenAdjusts, strictValProps));
	}

	@Test
	void recognizesNetZeroAdjusts() {
		// expect:
		assertTrue(subject.isNetZeroAdjustment(
				withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList()));
		assertFalse(subject.isNetZeroAdjustment(
				withAdjustments(a, -5L, b, +2L, c, +2L).getAccountAmountsList()));
	}

	@Test
	void acceptsReasonableTokenTransfersLength() {
		// given:
		List<TokenTransferList> wrapper = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3);

		// when:
		final var result = subject.validateTokenTransferSizes(wrapper, 4, 2);

		// expect:
		assertEquals(OK, result);
	}

	@Test
	void acceptsNoTokenTransfers() {
		// given:
		final var result = subject.validateTokenTransferSizes(Collections.emptyList(), 10, 2);

		// expect:
		assertEquals(OK, result);
	}

	@Test
	void tokenSemanticsOkForEmpty() {
		// expect:
		assertEquals(OK, subject.validateTokenTransferSemantics(Collections.emptyList()));
	}

	@Test
	void rejectsMissingTokenId() {
		// expect:
		assertEquals(INVALID_TOKEN_ID, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.addAllTransfers(withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList())
				.build()
		)));
	}

	@Test
	void rejectsMissingAccountId() {
		// expect:
		assertEquals(INVALID_ACCOUNT_ID, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addTransfers(AccountAmount.newBuilder().setAmount(123).build())
						.build()
		)));
	}

	@Test
	void rejectsZeroAccountAmount() {
		// expect:
		assertEquals(INVALID_ACCOUNT_AMOUNTS, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addTransfers(AccountAmount.newBuilder().setAccountID(a).setAmount(0).build())
						.build()
		)));
	}

	@Test
	void rejectsNonNetZeroScopedAccountAmounts() {
		// expect:
		assertEquals(TRANSFERS_NOT_ZERO_SUM_FOR_TOKEN, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addTransfers(AccountAmount.newBuilder().setAccountID(a).setAmount(-1).build())
						.addTransfers(AccountAmount.newBuilder().setAccountID(b).setAmount(2).build())
						.build()
		)));
	}

	@Test
	void rejectsRepeatedAccountInScopedAdjusts() {
		// expect:
		assertEquals(ACCOUNT_REPEATED_IN_ACCOUNT_AMOUNTS, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addTransfers(AccountAmount.newBuilder().setAccountID(a).setAmount(-1).build())
						.addTransfers(AccountAmount.newBuilder().setAccountID(a).setAmount(1).build())
						.build()
		)));
	}

	@Test
	void rejectsRepeatedTokens() {
		// expect:
		assertEquals(TOKEN_ID_REPEATED_IN_TOKEN_LIST, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addAllTransfers(withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList())
						.build(),
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addAllTransfers(withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList())
						.build()
		)));
	}

	@Test
	void oksSaneTokenExchange() {
		// expect:
		assertEquals(OK, subject.validateTokenTransferSemantics(List.of(
				TokenTransferList.newBuilder()
						.setToken(aTId)
						.addAllTransfers(withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList())
						.build(),
				TokenTransferList.newBuilder()
						.setToken(bTId)
						.addAllTransfers(withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList())
						.build()
		)));
	}

	@Test
	void rejectsExceedingMaxOwnershipChanges() {
		// given:
		List<TokenTransferList> wrapper = withOwnershipChanges(
				aTId, a, a, 123,
				bTId, b, c, 234,
				cTId, c, a, 345);

		// when:
		final var result = subject.validateTokenTransferSizes(wrapper, 20, 1);

		// then:
		assertEquals(BATCH_SIZE_LIMIT_EXCEEDED, result);
	}

	@Test
	void rejectsExceedingTokenTransfersAccountAmountsLength() {
		// given:
		List<TokenTransferList> wrapper = withTokenAdjustments(aTId, a, -1, bTId, b, 2, cTId, c, 3, dTId, d, -4);

		// when:
		final var result = subject.validateTokenTransferSizes(wrapper, 4, 2);

		// then:
		assertEquals(TOKEN_TRANSFER_LIST_SIZE_LIMIT_EXCEEDED, result);
	}

	@Test
	void rejectsEmptyTokenTransferAmounts() {
		// given:
		List<TokenTransferList> wrapper = List.of(TokenTransferList.newBuilder()
				.setToken(aTId)
				.build());

		// when:
		final var result = subject.validateTokenTransferSizes(wrapper, 10, 2);

		// then:
		assertEquals(EMPTY_TOKEN_TRANSFER_ACCOUNT_AMOUNTS, result);
	}

	@Test
	void acceptsDegenerateCases() {
		// expect:
		assertFalse(subject.hasRepeatedAccount(Collections.emptyList()));
		assertFalse(subject.hasRepeatedAccount(List.of(
				AccountAmount.newBuilder().setAccountID(a).setAmount(123).build())));
	}

	@Test
	void distinguishesRepeated() {
		// expect:
		assertFalse(subject.hasRepeatedAccount(
				withAdjustments(a, -4L, b, +2L, c, +2L).getAccountAmountsList()));
		assertTrue(subject.hasRepeatedAccount(
				withAdjustments(a, -4L, b, +2L, a, +2L).getAccountAmountsList()));
		assertTrue(subject.hasRepeatedAccount(
				withAdjustments(a, -4L, b, +2L, b, +2L).getAccountAmountsList()));
		assertTrue(subject.hasRepeatedAccount(
				withAdjustments(a, -4L, a, +2L, b, +2L).getAccountAmountsList()));
	}
}
