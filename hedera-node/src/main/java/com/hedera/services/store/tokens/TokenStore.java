package com.hedera.services.store.tokens;

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

import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.store.CreationResult;
import com.hedera.services.store.Store;
import com.hedera.services.store.models.NftId;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;

import java.util.List;
import java.util.function.Consumer;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;

/**
 * Defines a type able to manage arbitrary tokens.
 *
 * @author Michael Tinker
 */
public interface TokenStore extends Store<TokenID, MerkleToken> {
	TokenID MISSING_TOKEN = TokenID.getDefaultInstance();
	NftID MISSING_NFT = NftID.getDefaultInstance();
	Consumer<MerkleToken> DELETION = token -> token.setDeleted(true);

	boolean isKnownTreasury(AccountID id);
	boolean associationExists(AccountID aId, TokenID tId);
	boolean isTreasuryForToken(AccountID aId, TokenID tId);
	List<TokenID> listOfTokensServed(AccountID treasury);

	ResponseCodeEnum wipe(AccountID aId, TokenID tId, long wipingAmount, boolean skipKeyCheck);

	ResponseCodeEnum freeze(AccountID aId, TokenID tId);

	ResponseCodeEnum update(TokenUpdateTransactionBody changes, long now);

	ResponseCodeEnum unfreeze(AccountID aId, TokenID tId);

	ResponseCodeEnum grantKyc(AccountID aId, TokenID tId);

	ResponseCodeEnum revokeKyc(AccountID aId, TokenID tId);

	ResponseCodeEnum associate(AccountID aId, List<TokenID> tokens);

	ResponseCodeEnum dissociate(AccountID aId, List<TokenID> tokens);

	ResponseCodeEnum adjustBalance(AccountID aId, TokenID tId, long adjustment);

	ResponseCodeEnum changeOwner(NftId nftId, AccountID from, AccountID to);

	CreationResult<TokenID> createProvisionally(TokenCreateTransactionBody request, AccountID sponsor, long now);

	default TokenID resolve(TokenID id) {
		return exists(id) ? id : MISSING_TOKEN;
	}

	default ResponseCodeEnum delete(TokenID id) {
		var idRes = resolve(id);
		if (idRes == MISSING_TOKEN) {
			return INVALID_TOKEN_ID;
		}

		var token = get(id);
		if (token.adminKey().isEmpty()) {
			return TOKEN_IS_IMMUTABLE;
		}
		if (token.isDeleted()) {
			return TOKEN_WAS_DELETED;
		}

		apply(id, DELETION);
		return OK;
	}

	default ResponseCodeEnum tryTokenChange(BalanceChange change) {
		var validity = OK;
		var tokenId = resolve(change.tokenId());
		if (tokenId == MISSING_TOKEN) {
			validity = INVALID_TOKEN_ID;
		}
		if (validity == OK) {
			if (change.isForNft()) {
				validity = changeOwner(change.nftId(), change.accountId(), change.counterPartyAccountId());
			} else {
				validity = adjustBalance(change.accountId(), tokenId, change.units());
				if (validity == INSUFFICIENT_TOKEN_BALANCE) {
					validity = change.codeForInsufficientBalance();
				}
			}
		}
		return validity;
	}
}
