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
import com.hederahashgraph.api.proto.java.NftID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;

import java.util.function.Supplier;

import static com.hedera.services.state.merkle.MerkleUniqueTokenId.fromNftID;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;

/**
 * Provides functionality to work with Unique tokens.
 *
 * @author Yoan Sredkov
 */
public class UniqueTokenStore extends BaseTokenStore implements UniqueStore {

	private final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokenSupplier;

	public UniqueTokenStore(final EntityIdSource ids,
							final OptionValidator validator,
							final GlobalDynamicProperties properties,
							final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
							final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokenSupplier,
							final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger
	) {
		super(ids, validator, properties, tokens, tokenRelsLedger);
		this.uniqueTokenSupplier = uniqueTokenSupplier;
	}

	@Override
	public ResponseCodeEnum mint(final TokenID tId, final String memo, final RichInstant creationTime) {
		return tokenSanityCheck(tId, (merkleToken -> {
			if (!merkleToken.hasSupplyKey()) {
				return TOKEN_HAS_NO_SUPPLY_KEY;
			}
			var mintResult = super.mint(tId, 1);
			if (!mintResult.equals(OK)) {
				return mintResult;
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

	public boolean nftExists(final NftID id) {
		return uniqueTokenSupplier.get().containsKey(fromNftID(id));
	}

	public MerkleUniqueToken get(final NftID id) {
		throwIfMissing(id);

		return uniqueTokenSupplier.get().get(fromNftID(id));
	}

	private void throwIfMissing(NftID id) {
		if (!nftExists(id)) {
			throw new IllegalArgumentException(String.format(
					"Argument 'id=%s' does not refer to a known token!",
					readableId(id)));
		}
	}

	@Override
	public ResponseCodeEnum adjustBalance(AccountID senderAId, AccountID receiverAId, TokenID tId, long serialNumber) {
		var nftId = NftID.newBuilder()
				.setTokenID(tId)
				.setSerialNumber(serialNumber)
				.build();
		if (nftExists(nftId)) {
			get(nftId).setOwner(EntityId.fromGrpcAccountId(receiverAId));
			return sanityChecked(senderAId, receiverAId, tId, token -> tryAdjustment(senderAId, receiverAId, tId, serialNumber));
		}
		return FAIL_INVALID;
	}

	private void revertAdjustmentOfToken(MerkleToken token, TokenID tokenID) {
		tryAdjustment(token.treasury().toGrpcAccountId(), tokenID, -1);
	}
}
