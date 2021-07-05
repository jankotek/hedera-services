package com.hedera.services.queries.validation;

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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransferList;
import com.swirlds.fcmap.FCMap;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_EXPIRED_AND_PENDING_REMOVAL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_ID_DOES_NOT_EXIST;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_PAYER_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TX_FEE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ACCOUNT_AMOUNTS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RECEIVING_NODE_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

public class QueryFeeCheck {
	private final OptionValidator validator;
	private final GlobalDynamicProperties dynamicProperties;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts;

	public QueryFeeCheck(
			OptionValidator validator,
			GlobalDynamicProperties dynamicProperties,
			Supplier<FCMap<MerkleEntityId, MerkleAccount>> accounts
	) {
		this.accounts = accounts;
		this.validator = validator;
		this.dynamicProperties = dynamicProperties;
	}

	public ResponseCodeEnum nodePaymentValidity(List<AccountAmount> transfers, long queryFee, AccountID node) {
		var plausibility = transfersPlausibility(transfers);
		if (plausibility != OK) {
			return plausibility;
		}

		long netPayment = -1 * transfers.stream()
				.mapToLong(AccountAmount::getAmount)
				.filter(amount -> amount < 0)
				.sum();
		if (netPayment < queryFee) {
			return INSUFFICIENT_TX_FEE;
		}
		// number of beneficiaries in query transfer transaction can be greater than one.
		// validate if node gets the required query payment
		if (transfers.stream().noneMatch(adj -> adj.getAmount() >= 0 && adj.getAccountID().equals(node))) {
			return INVALID_RECEIVING_NODE_ACCOUNT;
		}
		if (transfers.stream().anyMatch(adj -> adj.getAccountID().equals(node) && adj.getAmount() < queryFee)) {
			return INSUFFICIENT_TX_FEE;
		}

		return OK;
	}

	ResponseCodeEnum transfersPlausibility(List<AccountAmount> transfers) {
		if (Optional.ofNullable(transfers).map(List::size).orElse(0) == 0) {
			return INVALID_ACCOUNT_AMOUNTS;
		}

		var basicPlausibility = transfers
				.stream()
				.map(this::adjustmentPlausibility)
				.filter(status -> status != OK)
				.findFirst()
				.orElse(OK);
		if (basicPlausibility != OK) {
			return basicPlausibility;
		}

		try {
			long net = transfers.stream()
					.mapToLong(AccountAmount::getAmount)
					.reduce(0L, Math::addExact);
			return (net == 0) ? OK : INVALID_ACCOUNT_AMOUNTS;
		} catch (ArithmeticException ignore) {
			return INVALID_ACCOUNT_AMOUNTS;
		}
	}

	ResponseCodeEnum adjustmentPlausibility(AccountAmount adjustment) {
		var id = adjustment.getAccountID();
		var key = fromAccountId(id);
		long amount = adjustment.getAmount();

		if (amount == Long.MIN_VALUE) {
			return INVALID_ACCOUNT_AMOUNTS;
		}

		if (amount < 0) {
			return balanceCheck(accounts.get().get(key), Math.abs(amount));
		} else {
			if (!accounts.get().containsKey(key)) {
				return ACCOUNT_ID_DOES_NOT_EXIST;
			}
		}

		return OK;
	}

	/**
	 * Validates query payment transfer transaction before reaching consensus.
	 * Validate each payer has enough balance that is needed for transfer.
	 * If one of the payer for query is also paying transactionFee validate the payer has balance to pay both
	 *
	 * @param txn the transaction body to validate
	 * @return the corresponding {@link ResponseCodeEnum} after the validation
	 */
	public ResponseCodeEnum validateQueryPaymentTransfers(TransactionBody txn) {
		AccountID transactionPayer = txn.getTransactionID().getAccountID();
		TransferList transferList = txn.getCryptoTransfer().getTransfers();
		List<AccountAmount> transfers = transferList.getAccountAmountsList();
		long transactionFee = txn.getTransactionFee();

		final var currentAccounts = accounts.get();
		ResponseCodeEnum status;
		for (AccountAmount accountAmount : transfers) {
			var id = accountAmount.getAccountID();
			long amount = accountAmount.getAmount();

			if (amount < 0) {
				amount = -1 * amount;
				if (id.equals(transactionPayer)) {
					try {
						amount = Math.addExact(amount, transactionFee);
					} catch (ArithmeticException e) {
						return INSUFFICIENT_PAYER_BALANCE;
					}
				}
				if ((status = balanceCheck(currentAccounts.get(fromAccountId(id)), amount)) != OK) {
					return status;
				}
			}
		}
		return OK;
	}

	private ResponseCodeEnum balanceCheck(@Nullable MerkleAccount payingAccount, long req) {
		if (payingAccount == null) {
			return ACCOUNT_ID_DOES_NOT_EXIST;
		}
		final long balance = payingAccount.getBalance();
		if (balance >= req) {
			return OK;
		} else {
			final var isDetached = balance == 0
					&& dynamicProperties.autoRenewEnabled()
					&& !validator.isAfterConsensusSecond(payingAccount.getExpiry());
			return isDetached ? ACCOUNT_EXPIRED_AND_PENDING_REMOVAL : INSUFFICIENT_PAYER_BALANCE;
		}
	}
}
