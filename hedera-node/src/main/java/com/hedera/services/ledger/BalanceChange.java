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

import com.google.common.base.MoreObjects;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.NftId;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_ACCOUNT_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;

/**
 * Process object that encapsulates a balance change, either ℏ or token unit .
 *
 * Includes an optional override for the {@link ResponseCodeEnum} to be used
 * in the case that the change is determined to result to an insufficient balance;
 * and a field to contain the new balance that will result from the change.
 * (This field is helpful to simplify work done in {@link HederaLedger}.)
 *
 * The {@code tokenId} and {@code accountId} fields are
 * temporary, needed to interact with the {@link com.hedera.services.ledger.accounts.BackingAccounts}
 * and {@link com.hedera.services.ledger.accounts.BackingTokenRels} components
 * whose APIs still use gRPC types.
 */
public class BalanceChange {
	static final TokenID NO_TOKEN_FOR_HBAR_ADJUST = TokenID.getDefaultInstance();

	private Id token;

	private Id account;
	private long units;
	private ResponseCodeEnum codeForInsufficientBalance;
	private long newBalance;
	private NftId nftId = null;
	private TokenID tokenId = null;
	private AccountID accountId;
	private AccountID counterPartyAccountId = null;

	private BalanceChange(Id token, AccountAmount aa, ResponseCodeEnum code) {
		this.token = token;

		this.accountId = aa.getAccountID();
		this.account = Id.fromGrpcAccount(accountId);
		this.units = aa.getAmount();

		this.codeForInsufficientBalance = code;
	}

	private BalanceChange(Id token, AccountID sender, AccountID receiver, long serialNo, ResponseCodeEnum code) {
		this.token = token;

		this.accountId = sender;
		this.counterPartyAccountId = receiver;
		this.account = Id.fromGrpcAccount(accountId);
		this.units = serialNo;

		this.codeForInsufficientBalance = code;
	}

	public static BalanceChange changingHbar(AccountAmount aa) {
		return new BalanceChange(null, aa, INSUFFICIENT_ACCOUNT_BALANCE);
	}

	public static BalanceChange changingFtUnits(Id token, TokenID tokenId, AccountAmount aa) {
		final var tokenChange = new BalanceChange(token, aa, INSUFFICIENT_TOKEN_BALANCE);
		tokenChange.tokenId = tokenId;
		return tokenChange;
	}

	private BalanceChange(Id account, long amount, ResponseCodeEnum code) {
		this.token = null;
		this.account = account;
		this.accountId = account.asGrpcAccount();
		this.units = amount;
		this.codeForInsufficientBalance = code;
	}

	public void adjustUnits(long units) {
		this.units += units;
	}

	public static BalanceChange hbarAdjust(Id id, long amount) {
		return new BalanceChange(id, amount, INSUFFICIENT_ACCOUNT_BALANCE);
	}

	public static BalanceChange changingNftOwnership(Id token, TokenID tokenId, NftTransfer nftTransfer) {
		final var serialNo = nftTransfer.getSerialNumber();
		final var nftChange = new BalanceChange(
				token,
				nftTransfer.getSenderAccountID(),
				nftTransfer.getReceiverAccountID(),
				serialNo,
				SENDER_DOES_NOT_OWN_NFT_SERIAL_NO);
		nftChange.nftId = new NftId(token.getShard(), token.getRealm(), token.getNum(), serialNo);
		nftChange.tokenId = tokenId;
		return nftChange;
        }

	public static BalanceChange tokenAdjust(Id account, Id token, long amount) {
		final var tokenChange = new BalanceChange(account, amount, INSUFFICIENT_TOKEN_BALANCE);
		tokenChange.token = token;
		tokenChange.tokenId = token.asGrpcToken();
		return tokenChange;
	}

	public boolean isForHbar() {
		return token == null;
	}

	public boolean isForNft() {
		return token != null && counterPartyAccountId != null;
	}

	public NftId nftId() {
		return nftId;
	}

	public long units() {
		return units;
	}

	public long serialNo() {
		return units;
	}

	public long getNewBalance() {
		return newBalance;
	}

	public void setNewBalance(final long newBalance) {
		this.newBalance = newBalance;
	}

	public TokenID tokenId() {
		return (tokenId != null) ? tokenId : NO_TOKEN_FOR_HBAR_ADJUST;
	}

	public AccountID accountId() {
		return accountId;
	}

	public AccountID counterPartyAccountId() {
		return counterPartyAccountId;
        }

	public Id getAccount() {
		return account;
	}

	public Id getToken() {
		return token;
	}

	public ResponseCodeEnum codeForInsufficientBalance() {
		return codeForInsufficientBalance;
	}

	/* NOTE: The object methods below are only overridden to improve readability of unit tests;
	this model object is not used in hash-based collections, so the performance of these
	methods doesn't matter. */

	@Override
	public boolean equals(final Object obj) {
		return EqualsBuilder.reflectionEquals(this, obj);
	}

	@Override
	public int hashCode() {
		return HashCodeBuilder.reflectionHashCode(this);
	}

	@Override
	public String toString() {
		if (counterPartyAccountId == null) {
			return MoreObjects.toStringHelper(BalanceChange.class)
					.add("token", token == null ? "ℏ" : token)
					.add("account", account)
					.add("units", units)
					.toString();
		} else {
			return MoreObjects.toStringHelper(BalanceChange.class)
					.add("nft", token)
					.add("serialNo", units)
					.add("from", account)
					.add("to", Id.fromGrpcAccount(counterPartyAccountId))
					.toString();
		}
	}

	public void setCodeForInsufficientBalance(ResponseCodeEnum codeForInsufficientBalance) {
		this.codeForInsufficientBalance = codeForInsufficientBalance;
	}
}
