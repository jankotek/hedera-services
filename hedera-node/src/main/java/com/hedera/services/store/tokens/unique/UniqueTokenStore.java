package com.hedera.services.store.tokens.unique;

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.RichInstant;
import com.hedera.services.store.tokens.BaseTokenStore;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.invertible_fchashmap.FCInvertibleHashMap;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.*;
/*
 * Notes:
 * */


/**
 * Provides functionality to work with Unique tokens.
 *
 * @author Yoan Sredkov
 */
public class UniqueTokenStore extends BaseTokenStore implements UniqueStore {

	private final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokenSupplier;
	private final List<Pair<MerkleUniqueTokenId, MerkleUniqueToken>> provisionalUniqueTokens;

	public UniqueTokenStore(final EntityIdSource ids,
							final OptionValidator validator,
							final GlobalDynamicProperties properties,
							final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
							final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokenSupplier,
							final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger
	) {
		super(ids, validator, properties, tokens, tokenRelsLedger);
		this.uniqueTokenSupplier = uniqueTokenSupplier;
		this.provisionalUniqueTokens = new ArrayList<>(); // TODO MAX_SIZE

	}

	private ResponseCodeEnum mintOne(final TokenID tId, final String memo, final RichInstant creationTime) {
		return tokenSanityCheck(tId, (merkleToken -> {
			if (!merkleToken.hasSupplyKey()) {
				return TOKEN_HAS_NO_SUPPLY_KEY;
			}
			var adjustmentResult = tryAdjustment(merkleToken.treasury().toGrpcAccountId(), tId, 1);
			if (!adjustmentResult.equals(OK)) {
				return adjustmentResult;
			}
			final var suppliedTokens = uniqueTokenSupplier.get();
			final var eId = EntityId.fromGrpcTokenId(tId);
			final var owner = merkleToken.treasury();
			final long serialNum = merkleToken.incrementSerialNum();

			final var nftId = new MerkleUniqueTokenId(eId, serialNum);
			final var nft = new MerkleUniqueToken(owner, memo, creationTime);
			suppliedTokens.put(nftId, nft);
			return OK;
		}));
	}

	@Override
	public ResponseCodeEnum wipe(final AccountID aId, final TokenID tId, final long wipingAmount, final boolean skipKeyCheck) {
		return null;
	}

	@Override
	public ResponseCodeEnum mintProvisional(final TokenMintTransactionBody request, final RichInstant creationTime) {
		return tokenSanityCheck(request.getToken(), merkleToken -> {
			if (!merkleToken.hasSupplyKey()) {
				return TOKEN_HAS_NO_SUPPLY_KEY;
			}
			final var eId = EntityId.fromGrpcTokenId(request.getToken());
			final var owner = merkleToken.treasury();
			final var metadataList = request.getMetadataList();
			metadataList.stream().map(el -> {
				String metaAsStr = el.toStringUtf8();
				final long serialNum = merkleToken.getCurrentSerialNum();
				final var nftId = new MerkleUniqueTokenId(eId, serialNum);
				final var nft = new MerkleUniqueToken(owner, metaAsStr, creationTime);
				return Pair.of(nftId, nft);
			}).forEach(provisionalUniqueTokens::add);
			return OK;
		});
	}

	@Override
	public ResponseCodeEnum mint(final TokenMintTransactionBody txBody, final RichInstant creationTime) {
		var res = mintProvisional(txBody, creationTime);
		if (!res.equals(OK)) {
			clearProvisional();
			return res;
		}

		res = commitProvisional();
		if (!res.equals(OK)) {
			clearProvisional();
			return res;
		}
		clearProvisional();
		return OK;
	}

	private boolean checkProvisional() {
		// Memo should not be repeated
		var ptokenSet = provisionalUniqueTokens.stream().map(e -> e.getValue().getMemo()).collect(Collectors.toSet());
		return ptokenSet.size() == provisionalUniqueTokens.size();
	}

	private void clearProvisional() {
		provisionalUniqueTokens.clear();
	}

	@Override
	public ResponseCodeEnum commitProvisional() {
		final AtomicBoolean didAnyFail = new AtomicBoolean(false);
		if (!checkProvisional()) {
			return INVALID_TRANSACTION_BODY;
		} else {
			provisionalUniqueTokens.forEach(e -> {
				TokenID tokenId = e.getKey().tokenId().toGrpcTokenId();
				String memo = e.getValue().getMemo();
				RichInstant creationTime = e.getValue().getCreationTime();
				var res = mintOne(tokenId, memo, creationTime);
				var a = 5;
				// if native minting fails for some reason, revert changes
				if (!res.equals(OK)) {
					// TODO decrement serial num?
					// revert adjustment
					var token = get(tokenId);
					revertAdjustmentOfToken(token, tokenId);
					// revert from map
					uniqueTokenSupplier.get().remove(new MerkleUniqueTokenId(EntityId.fromGrpcTokenId(tokenId), token.getCurrentSerialNum()));
					didAnyFail.set(true);
				}
			});
			return didAnyFail.get() ? FAIL_INVALID : OK;
		}
	}

	@Override
	public ResponseCodeEnum adjustBalance(AccountID senderAId, AccountID receiverAId, TokenID tId, long serialNumber) {
		return sanityChecked(senderAId, receiverAId, tId, token -> tryAdjustment(senderAId, receiverAId, tId, serialNumber));
	}

	private void revertAdjustmentOfToken(MerkleToken token, TokenID tokenID) {
		tryAdjustment(token.treasury().toGrpcAccountId(), tokenID, -1);
	}
}
