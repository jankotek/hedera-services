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
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;

public class UniqueTokenStore extends BaseTokenStore implements UniqueStore {

	private final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokensSupply;

	public UniqueTokenStore(final EntityIdSource ids,
							final OptionValidator validator,
							final GlobalDynamicProperties properties,
							final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
							final Supplier<FCInvertibleHashMap<MerkleUniqueTokenId, MerkleUniqueToken, OwnerIdentifier>> uniqueTokensSupply,
							final TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger
	) {
		super(ids, validator, properties, tokens, tokenRelsLedger);
		this.uniqueTokensSupply = uniqueTokensSupply;
	}

	@Override
	public ResponseCodeEnum mint(final TokenID tId, String memo, RichInstant creationTime) {

		// is it unique type token? * - not yet impl
		return tokenSanityCheck(tId, (merkleToken -> {
			if (!merkleToken.hasSupplyKey()) {
				return TOKEN_HAS_NO_SUPPLY_KEY;
			}

			var mintResult = super.mint(tId, 1);

			final var suppliedTokens = uniqueTokensSupply.get();
			final var eId = EntityId.fromGrpcTokenId(tId);
			final var owner = get(tId).treasury(); // get next available serial num here as well
			final int serialNum = 0;
			// TODO serialNum
			// When a merkleUniqueToken is created, the next present serial number must be available as a method

			final var nftId = new MerkleUniqueTokenId(eId, serialNum);
			final var nft = new MerkleUniqueToken(owner, memo, creationTime);
			final var putResult = suppliedTokens.putIfAbsent(nftId, nft);

			return (putResult == null && mintResult == ResponseCodeEnum.OK) ? ResponseCodeEnum.OK : ResponseCodeEnum.INVALID_TOKEN_ID;
		}));

	}

	@Override
	public MerkleUniqueToken getUnique(final EntityId eId, final int serialNum) {
		return uniqueTokensSupply.get().get(new MerkleUniqueTokenId(eId, serialNum));
	}

	@Override
	public Iterator<MerkleUniqueTokenId> getByToken(final MerkleUniqueToken token) {
		return uniqueTokensSupply.get().inverseGet(token);
	}

	@Override
	public Iterator<MerkleUniqueTokenId> getByTokenFromIdx(final MerkleUniqueToken token, final int start) {
		return uniqueTokensSupply.get().inverseGet(token, start);
	}

	@Override
	public Iterator<MerkleUniqueTokenId> getByTokenFromIdxToIdx(final MerkleUniqueToken token, final int start, final int end) {
		return uniqueTokensSupply.get().inverseGet(token, start, end);
	}

	// currently not taking care of start/end
	@Override
	public Iterator<MerkleUniqueTokenId> getByAccountFromIdxToIdx(final AccountID aId, final int start, final int end) {

		if(end < 0 || start < 0)
			throw new IllegalArgumentException("Neither start, neither end idx can be less than 0");

		if(end < start)
			throw new IllegalArgumentException("End idx cannot be smaller than start idx");

		if (end > uniqueTokensSupply.get().size())
			throw new IllegalArgumentException("End idx cannot be bigger than map size");


		final var eId = EntityId.fromGrpcAccountId(aId);
		var tokenSupply = uniqueTokensSupply.get();
		final var ownerIdentifier = new OwnerIdentifier(eId);
		Set<MerkleUniqueTokenId> accountRelatedTokenIds = new HashSet<>();
		tokenSupply.values()
				.stream()
				.filter(merkleUniqueToken -> merkleUniqueToken.getIdentity().equals(ownerIdentifier)) // filter by owner
				.map(merkleUniqueToken -> {
					// fetch our iterators
					final var nftIds = tokenSupply.inverseGet(merkleUniqueToken);
					// conversion: iterator -> spliterator -> stream -> set
					var nftIdsSpliterator = Spliterators.spliteratorUnknownSize(nftIds, 0);
					// TODO parallel
					// Pros - utilizes multiple cores, faster
					// Cons - does not take care of order, which we don't need anyway
					// returns a stream of sets
					return StreamSupport.stream(nftIdsSpliterator, true).collect(Collectors.toSet());
				}).forEach(accountRelatedTokenIds::addAll);// adds all entries from each set into the local scope set

		return List.copyOf(accountRelatedTokenIds).subList(start, end).iterator();
	}


	// TODO does is it logical to burn such token?
	@Override
	public ResponseCodeEnum burn(final TokenID tId, final long amount) {
		return super.burn(tId, amount);
	}

	// TODO
	@Override
	public ResponseCodeEnum wipe(final AccountID aId, final TokenID tId, final long amount, final boolean skipKeyCheck) {
		return super.wipe(aId, tId, amount, skipKeyCheck);
	}

	// TODO
	@Override
	public ResponseCodeEnum dissociate(final AccountID aId, final List<TokenID> targetTokens) {
		return super.dissociate(aId, targetTokens);
	}

	// TODO
	@Override
	public ResponseCodeEnum adjustBalance(final AccountID aId, final TokenID tId, final long adjustment) {
		return super.adjustBalance(aId, tId, adjustment);
	}


}
