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

import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.sigs.utils.ImmutableKeyUtils;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.CreationResult;
import com.hedera.services.store.HederaStore;
import com.hedera.services.store.models.NftId;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TokenCreateTransactionBody;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;
import proto.CustomFeesOuterClass;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.ledger.accounts.BackingTokenRels.asTokenRel;
import static com.hedera.services.ledger.properties.AccountProperty.NUM_NFTS_OWNED;
import static com.hedera.services.ledger.properties.NftProperty.OWNER;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_FROZEN;
import static com.hedera.services.ledger.properties.TokenRelProperty.IS_KYC_GRANTED;
import static com.hedera.services.ledger.properties.TokenRelProperty.TOKEN_BALANCE;
import static com.hedera.services.state.merkle.MerkleEntityId.fromTokenId;
import static com.hedera.services.state.merkle.MerkleToken.UNUSED_KEY;
import static com.hedera.services.state.submerkle.EntityId.fromGrpcAccountId;
import static com.hedera.services.state.submerkle.EntityId.fromGrpcTokenId;
import static com.hedera.services.store.CreationResult.failure;
import static com.hedera.services.store.CreationResult.success;
import static com.hedera.services.utils.EntityIdUtils.readableId;
import static com.hedera.services.utils.MiscUtils.asFcKeyUnchecked;
import static com.hedera.services.utils.MiscUtils.asUsableFcKey;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_FROZEN_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_IS_TREASURY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_ARE_MARKED_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEES_LIST_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_MUST_BE_POSITIVE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CUSTOM_FEE_NOT_FULLY_SPECIFIED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FRACTION_DIVIDES_BY_ZERO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INSUFFICIENT_TOKEN_BALANCE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_AUTORENEW_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_CUSTOM_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_EXPIRATION_TIME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_NFT_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID_IN_CUSTOM_FEES;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TREASURY_ACCOUNT_FOR_TOKEN;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_WIPING_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_FREEZE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_KYC_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_SUPPLY_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_HAS_NO_WIPE_KEY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_IS_IMMUTABLE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_WAS_DELETED;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
import static java.util.stream.Collectors.toList;

/**
 * Provides a managing store for arbitrary tokens.
 */
public class HederaTokenStore extends HederaStore implements TokenStore {

	static final TokenID NO_PENDING_ID = TokenID.getDefaultInstance();

	static Predicate<Key> REMOVES_ADMIN_KEY = ImmutableKeyUtils::signalsKeyRemoval;

	private final OptionValidator validator;
	private final GlobalDynamicProperties properties;
	private final Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens;
	private final Supplier<FCOneToManyRelation<EntityId, MerkleUniqueTokenId>> uniqueOwnershipAssociations;
	private final TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger;
	private final TransactionalLedger<
			Pair<AccountID, TokenID>,
			TokenRelProperty,
			MerkleTokenRelStatus> tokenRelsLedger;
	Map<AccountID, Set<TokenID>> knownTreasuries = new HashMap<>();

	TokenID pendingId = NO_PENDING_ID;
	MerkleToken pendingCreation;

	public HederaTokenStore(
			EntityIdSource ids,
			OptionValidator validator,
			GlobalDynamicProperties properties,
			Supplier<FCMap<MerkleEntityId, MerkleToken>> tokens,
			Supplier<FCOneToManyRelation<EntityId, MerkleUniqueTokenId>> uniqueOwnershipAssociations,
			TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger,
			TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger
	) {
		super(ids);
		this.tokens = tokens;
		this.validator = validator;
		this.properties = properties;
		this.nftsLedger = nftsLedger;
		this.tokenRelsLedger = tokenRelsLedger;
		this.uniqueOwnershipAssociations = uniqueOwnershipAssociations;
		rebuildViewOfKnownTreasuries();
	}

	@Override
	public void rebuildViews() {
		knownTreasuries.clear();
		rebuildViewOfKnownTreasuries();
	}

	private void rebuildViewOfKnownTreasuries() {
		tokens.get().forEach((key, value) -> {
			/* A deleted token's treasury is no longer bound by ACCOUNT_IS_TREASURY restrictions. */
			if (!value.isDeleted()) {
				addKnownTreasury(value.treasury().toGrpcAccountId(), key.toTokenId());
			}
		});
	}

	@Override
	public List<TokenID> listOfTokensServed(AccountID treasury) {
		if (!isKnownTreasury(treasury)) {
			return Collections.emptyList();
		} else {
			return knownTreasuries.get(treasury).stream()
					.sorted(HederaLedger.TOKEN_ID_COMPARATOR)
					.collect(toList());
		}
	}

	@Override
	public boolean isCreationPending() {
		return pendingId != NO_PENDING_ID;
	}

	@Override
	public void setHederaLedger(HederaLedger hederaLedger) {
		hederaLedger.setNftsLedger(nftsLedger);
		hederaLedger.setTokenRelsLedger(tokenRelsLedger);
		super.setHederaLedger(hederaLedger);
	}

	@Override
	public ResponseCodeEnum associate(AccountID aId, List<TokenID> tokens) {
		return fullySanityChecked(true, aId, tokens, (account, tokenIds) -> {
			var accountTokens = hederaLedger.getAssociatedTokens(aId);
			for (TokenID id : tokenIds) {
				if (accountTokens.includes(id)) {
					return TOKEN_ALREADY_ASSOCIATED_TO_ACCOUNT;
				}
			}
			var validity = OK;
			if ((accountTokens.numAssociations() + tokenIds.size()) > properties.maxTokensPerAccount()) {
				validity = TOKENS_PER_ACCOUNT_LIMIT_EXCEEDED;
			} else {
				accountTokens.associateAll(new HashSet<>(tokenIds));
				for (TokenID id : tokenIds) {
					var relationship = asTokenRel(aId, id);
					tokenRelsLedger.create(relationship);
					var token = get(id);
					tokenRelsLedger.set(
							relationship,
							TokenRelProperty.IS_FROZEN,
							token.hasFreezeKey() && token.accountsAreFrozenByDefault());
					tokenRelsLedger.set(
							relationship,
							TokenRelProperty.IS_KYC_GRANTED,
							!token.hasKycKey());
				}
			}
			hederaLedger.setAssociatedTokens(aId, accountTokens);
			return validity;
		});
	}

	@Override
	public ResponseCodeEnum dissociate(AccountID aId, List<TokenID> targetTokens) {
		return fullySanityChecked(false, aId, targetTokens, (account, tokenIds) -> {
			var accountTokens = hederaLedger.getAssociatedTokens(aId);
			for (TokenID tId : tokenIds) {
				if (!accountTokens.includes(tId)) {
					return TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
				}
				if (!tokens.get().containsKey(fromTokenId(tId))) {
					/* Expired tokens that have been removed from state (either because they
					were also deleted, or their grace period ended) should be dissociated
					with no additional checks. */
					continue;
				}
				var token = get(tId);
				var isTokenDeleted = token.isDeleted();
				/* Once a token is deleted, this always returns false. */
				if (isTreasuryForToken(aId, tId)) {
					return ACCOUNT_IS_TREASURY;
				}
				var relationship = asTokenRel(aId, tId);
				if (!isTokenDeleted && (boolean) tokenRelsLedger.get(relationship, IS_FROZEN)) {
					return ACCOUNT_FROZEN_FOR_TOKEN;
				}
				long balance = (long) tokenRelsLedger.get(relationship, TOKEN_BALANCE);
				if (balance > 0) {
					var expiry = Timestamp.newBuilder().setSeconds(token.expiry()).build();
					var isTokenExpired = !validator.isValidExpiry(expiry);
					if (!isTokenDeleted && !isTokenExpired) {
						return TRANSACTION_REQUIRES_ZERO_TOKEN_BALANCES;
					}
					if (!isTokenDeleted) {
						/* Must be expired; return balance to treasury account. */
						hederaLedger.doTokenTransfer(tId, aId, token.treasury().toGrpcAccountId(), balance);
					}
				}
			}
			accountTokens.dissociateAll(new HashSet<>(tokenIds));
			tokenIds.forEach(id -> tokenRelsLedger.destroy(asTokenRel(aId, id)));
			hederaLedger.setAssociatedTokens(aId, accountTokens);
			return OK;
		});
	}

	@Override
	public boolean associationExists(AccountID aId, TokenID tId) {
		return checkExistence(aId, tId) == OK && tokenRelsLedger.exists(asTokenRel(aId, tId));
	}

	@Override
	public boolean exists(TokenID id) {
		return (isCreationPending() && pendingId.equals(id)) || tokens.get().containsKey(fromTokenId(id));
	}

	@Override
	public MerkleToken get(TokenID id) {
		throwIfMissing(id);

		return pendingId.equals(id) ? pendingCreation : tokens.get().get(fromTokenId(id));
	}

	@Override
	public void apply(TokenID id, Consumer<MerkleToken> change) {
		throwIfMissing(id);

		var key = fromTokenId(id);
		var token = tokens.get().getForModify(key);
		try {
			change.accept(token);
		} catch (Exception internal) {
			throw new IllegalArgumentException("Token change failed unexpectedly!", internal);
		}
	}

	@Override
	public ResponseCodeEnum grantKyc(AccountID aId, TokenID tId) {
		return setHasKyc(aId, tId, true);
	}

	@Override
	public ResponseCodeEnum revokeKyc(AccountID aId, TokenID tId) {
		return setHasKyc(aId, tId, false);
	}

	@Override
	public ResponseCodeEnum unfreeze(AccountID aId, TokenID tId) {
		return setIsFrozen(aId, tId, false);
	}

	@Override
	public ResponseCodeEnum freeze(AccountID aId, TokenID tId) {
		return setIsFrozen(aId, tId, true);
	}

	private ResponseCodeEnum setHasKyc(AccountID aId, TokenID tId, boolean value) {
		return manageFlag(
				aId,
				tId,
				value,
				TOKEN_HAS_NO_KYC_KEY,
				TokenRelProperty.IS_KYC_GRANTED,
				MerkleToken::kycKey);
	}

	private ResponseCodeEnum setIsFrozen(AccountID aId, TokenID tId, boolean value) {
		return manageFlag(
				aId,
				tId,
				value,
				TOKEN_HAS_NO_FREEZE_KEY,
				TokenRelProperty.IS_FROZEN,
				MerkleToken::freezeKey);
	}

	@Override
	public ResponseCodeEnum adjustBalance(AccountID aId, TokenID tId, long adjustment) {
		return sanityChecked(aId, null, tId, token -> tryAdjustment(aId, tId, adjustment));
	}

	@Override
	public ResponseCodeEnum changeOwner(NftId nftId, AccountID from, AccountID to) {
		final var tId = nftId.tokenId();
		return sanityChecked(from, to, tId, token -> {
			if (!nftsLedger.exists(nftId)) {
				return INVALID_NFT_ID;
			}

			final var fromFreezeAndKycValidity = checkRelFrozenAndKycProps(from, tId);
			if (fromFreezeAndKycValidity != OK) {
				return fromFreezeAndKycValidity;
			}
			final var toFreezeAndKycValidity = checkRelFrozenAndKycProps(to, tId);
			if (toFreezeAndKycValidity != OK) {
				return toFreezeAndKycValidity;
			}

			final var owner = (EntityId) nftsLedger.get(nftId, OWNER);
			if (!owner.matches(from)) {
				return SENDER_DOES_NOT_OWN_NFT_SERIAL_NO;
			}

			final var nftType = nftId.tokenId();
			final var fromRel = asTokenRel(from, nftType);
			final var toRel = asTokenRel(to, nftType);
			final var fromNftsOwned = (long) accountsLedger.get(from, NUM_NFTS_OWNED);
			final var fromThisNftsOwned = (long) tokenRelsLedger.get(fromRel, TOKEN_BALANCE);
			final var toNftsOwned = (long) accountsLedger.get(to, NUM_NFTS_OWNED);
			final var toThisNftsOwned = (long) tokenRelsLedger.get(asTokenRel(to, nftType), TOKEN_BALANCE);
			nftsLedger.set(nftId, OWNER, EntityId.fromGrpcAccountId(to));
			accountsLedger.set(from, NUM_NFTS_OWNED, fromNftsOwned - 1);
			accountsLedger.set(to, NUM_NFTS_OWNED, toNftsOwned + 1);
			tokenRelsLedger.set(fromRel, TOKEN_BALANCE, fromThisNftsOwned - 1);
			tokenRelsLedger.set(toRel, TOKEN_BALANCE, toThisNftsOwned + 1);

			var merkleUniqueTokenId = new MerkleUniqueTokenId(fromGrpcTokenId(nftId.tokenId()), nftId.serialNo());
			this.uniqueOwnershipAssociations.get().disassociate(
					fromGrpcAccountId(from),
					merkleUniqueTokenId);

			this.uniqueOwnershipAssociations.get().associate(
					fromGrpcAccountId(to),
					merkleUniqueTokenId);

			hederaLedger.updateOwnershipChanges(nftId, from, to);

			return OK;
		});
	}

	@Override
	public ResponseCodeEnum wipe(AccountID aId, TokenID tId, long amount, boolean skipKeyCheck) {
		return sanityChecked(aId, null, tId, token -> {
			if (!skipKeyCheck && !token.hasWipeKey()) {
				return TOKEN_HAS_NO_WIPE_KEY;
			}
			if (fromGrpcAccountId(aId).equals(token.treasury())) {
				return CANNOT_WIPE_TOKEN_TREASURY_ACCOUNT;
			}

			var relationship = asTokenRel(aId, tId);
			long balance = (long) tokenRelsLedger.get(relationship, TOKEN_BALANCE);
			if (amount > balance) {
				return INVALID_WIPING_AMOUNT;
			}
			tokenRelsLedger.set(relationship, TOKEN_BALANCE, balance - amount);
			hederaLedger.updateTokenXfers(tId, aId, -amount);
			apply(tId, t -> t.adjustTotalSupplyBy(-amount));

			return OK;
		});
	}

	@Override
	public CreationResult<TokenID> createProvisionally(
			TokenCreateTransactionBody request,
			AccountID sponsor,
			long now
	) {
		var validity = usableOrElse(request.getTreasury(), INVALID_TREASURY_ACCOUNT_FOR_TOKEN);
		if (validity != OK) {
			return failure(validity);
		}
		if (request.hasAutoRenewAccount()) {
			validity = usableOrElse(request.getAutoRenewAccount(), INVALID_AUTORENEW_ACCOUNT);
			if (validity != OK) {
				return failure(validity);
			}
		}

		var freezeKey = asUsableFcKey(request.getFreezeKey());
		var adminKey = asUsableFcKey(request.getAdminKey());
		var kycKey = asUsableFcKey(request.getKycKey());
		var wipeKey = asUsableFcKey(request.getWipeKey());
		var supplyKey = asUsableFcKey(request.getSupplyKey());

		var expiry = expiryOf(request, now);
		pendingId = ids.newTokenId(sponsor);
		pendingCreation = new MerkleToken(
				expiry,
				request.getInitialSupply(),
				request.getDecimals(),
				request.getSymbol(),
				request.getName(),
				request.getFreezeDefault(),
				kycKey.isEmpty(),
				fromGrpcAccountId(request.getTreasury()));
		pendingCreation.setTokenType(request.getTokenTypeValue());
		pendingCreation.setSupplyType(request.getSupplyTypeValue());
		pendingCreation.setMemo(request.getMemo());
		pendingCreation.setMaxSupply(request.getMaxSupply());
		adminKey.ifPresent(pendingCreation::setAdminKey);
		kycKey.ifPresent(pendingCreation::setKycKey);
		wipeKey.ifPresent(pendingCreation::setWipeKey);
		freezeKey.ifPresent(pendingCreation::setFreezeKey);
		supplyKey.ifPresent(pendingCreation::setSupplyKey);
		if (request.hasAutoRenewAccount()) {
			pendingCreation.setAutoRenewAccount(fromGrpcAccountId(request.getAutoRenewAccount()));
			pendingCreation.setAutoRenewPeriod(request.getAutoRenewPeriod().getSeconds());
		}

		if (request.hasCustomFees()) {
			final var customFees = request.getCustomFees();
			validity = validateFeeSchedule(customFees.getCustomFeesList());
			if (validity != OK) {
				return failure(validity);
			}
			pendingCreation.setFeeScheduleFrom(customFees);
		}

		return success(pendingId);
	}

	private ResponseCodeEnum validateFeeSchedule(List<CustomFeesOuterClass.CustomFee> feeSchedule) {
		if (feeSchedule.size() > properties.maxCustomFeesAllowed()) {
			return CUSTOM_FEES_LIST_TOO_LONG;
		}

		for (var customFee : feeSchedule) {
			final var feeCollector = customFee.getFeeCollectorAccountId();
			final var feeCollectorValidity = usableOrElse(feeCollector, INVALID_CUSTOM_FEE_COLLECTOR);

			if (feeCollectorValidity != OK) {
				return INVALID_CUSTOM_FEE_COLLECTOR;
			}

			if (customFee.hasFixedFee()) {
				final var fixedFee = customFee.getFixedFee();
				if (fixedFee.getAmount() <= 0) {
					return CUSTOM_FEE_MUST_BE_POSITIVE;
				}
				if (fixedFee.hasDenominatingTokenId()) {
					final var denom = fixedFee.getDenominatingTokenId();
					if (resolve(denom) == MISSING_TOKEN) {
						return INVALID_TOKEN_ID_IN_CUSTOM_FEES;
					}
					if (!associationExists(feeCollector, denom)) {
						return TOKEN_NOT_ASSOCIATED_TO_FEE_COLLECTOR;
					}
				}
			} else if (customFee.hasFractionalFee()) {
				final var fractionalSpec = customFee.getFractionalFee();
				final var fraction = fractionalSpec.getFractionalAmount();
				if (fraction.getDenominator() == 0) {
					return FRACTION_DIVIDES_BY_ZERO;
				}
				if (!areValidPositiveNumbers(fraction.getNumerator(), fraction.getDenominator())) {
					return CUSTOM_FEE_MUST_BE_POSITIVE;
				}
				if (fractionalSpec.getMaximumAmount() < 0 || fractionalSpec.getMinimumAmount() < 0) {
					return CUSTOM_FEE_MUST_BE_POSITIVE;
				}
				if (fractionalSpec.getMaximumAmount() > 0 &&
						fractionalSpec.getMaximumAmount() < fractionalSpec.getMinimumAmount()) {
					return FRACTIONAL_FEE_MAX_AMOUNT_LESS_THAN_MIN_AMOUNT;
				}
			} else {
				return CUSTOM_FEE_NOT_FULLY_SPECIFIED;
			}
		}

		return OK;
	}

	private boolean areValidPositiveNumbers(long a, long b) {
		return (a > 0 && b > 0);
	}

	public void addKnownTreasury(AccountID aId, TokenID tId) {
		knownTreasuries.computeIfAbsent(aId, ignore -> new HashSet<>()).add(tId);
	}

	void removeKnownTreasuryForToken(AccountID aId, TokenID tId) {
		throwIfKnownTreasuryIsMissing(aId);
		knownTreasuries.get(aId).remove(tId);
		if (knownTreasuries.get(aId).isEmpty()) {
			knownTreasuries.remove(aId);
		}
	}

	private void throwIfKnownTreasuryIsMissing(AccountID aId) {
		if (!knownTreasuries.containsKey(aId)) {
			throw new IllegalArgumentException(String.format(
					"Argument 'aId=%s' does not refer to a known treasury!",
					readableId(aId)));
		}
	}

	private ResponseCodeEnum tryAdjustment(AccountID aId, TokenID tId, long adjustment) {
		var freezeAndKycValidity = checkRelFrozenAndKycProps(aId, tId);
		if (!freezeAndKycValidity.equals(OK)) {
			return freezeAndKycValidity;
		}

		var relationship = asTokenRel(aId, tId);
		long balance = (long) tokenRelsLedger.get(relationship, TOKEN_BALANCE);
		long newBalance = balance + adjustment;
		if (newBalance < 0) {
			return INSUFFICIENT_TOKEN_BALANCE;
		}
		tokenRelsLedger.set(relationship, TOKEN_BALANCE, newBalance);
		hederaLedger.updateTokenXfers(tId, aId, adjustment);
		return OK;
	}

	private ResponseCodeEnum checkRelFrozenAndKycProps(AccountID aId, TokenID tId) {
		var relationship = asTokenRel(aId, tId);
		if ((boolean) tokenRelsLedger.get(relationship, IS_FROZEN)) {
			return ACCOUNT_FROZEN_FOR_TOKEN;
		}
		if (!(boolean) tokenRelsLedger.get(relationship, IS_KYC_GRANTED)) {
			return ACCOUNT_KYC_NOT_GRANTED_FOR_TOKEN;
		}
		return OK;
	}

	private boolean isValidAutoRenewPeriod(long secs) {
		return validator.isValidAutoRenewPeriod(Duration.newBuilder().setSeconds(secs).build());
	}

	private long expiryOf(TokenCreateTransactionBody request, long now) {
		return request.hasAutoRenewAccount()
				? now + request.getAutoRenewPeriod().getSeconds()
				: request.getExpiry().getSeconds();
	}

	@Override
	public void commitCreation() {
		throwIfNoCreationPending();

		tokens.get().put(fromTokenId(pendingId), pendingCreation);
		addKnownTreasury(pendingCreation.treasury().toGrpcAccountId(), pendingId);

		resetPendingCreation();
	}

	@Override
	public void rollbackCreation() {
		throwIfNoCreationPending();

		ids.reclaimLastId();
		resetPendingCreation();
	}

	@Override
	public ResponseCodeEnum delete(TokenID tId) {
		var outcome = TokenStore.super.delete(tId);
		if (outcome != OK) {
			return outcome;
		}

		var treasury = tokens.get().get(fromTokenId(tId)).treasury().toGrpcAccountId();
		var tokensServed = knownTreasuries.get(treasury);
		tokensServed.remove(tId);
		if (tokensServed.isEmpty()) {
			knownTreasuries.remove(treasury);
		}
		return OK;
	}

	@Override
	public ResponseCodeEnum update(TokenUpdateTransactionBody changes, long now) {
		var tId = resolve(changes.getToken());
		if (tId == MISSING_TOKEN) {
			return INVALID_TOKEN_ID;
		}
		var validity = OK;
		var isExpiryOnly = affectsExpiryAtMost(changes);
		var hasNewSymbol = changes.getSymbol().length() > 0;
		var hasNewTokenName = changes.getName().length() > 0;
		var hasAutoRenewAccount = changes.hasAutoRenewAccount();
		if (hasAutoRenewAccount) {
			validity = usableOrElse(changes.getAutoRenewAccount(), INVALID_AUTORENEW_ACCOUNT);
			if (validity != OK) {
				return validity;
			}
		}

		Optional<JKey> newKycKey = changes.hasKycKey() ? asUsableFcKey(changes.getKycKey()) : Optional.empty();
		Optional<JKey> newWipeKey = changes.hasWipeKey() ? asUsableFcKey(changes.getWipeKey()) : Optional.empty();
		Optional<JKey> newSupplyKey = changes.hasSupplyKey() ? asUsableFcKey(changes.getSupplyKey()) : Optional.empty();
		Optional<JKey> newFreezeKey = changes.hasFreezeKey() ? asUsableFcKey(changes.getFreezeKey()) : Optional.empty();

		var appliedValidity = new AtomicReference<>(OK);
		apply(tId, token -> {
			var candidateExpiry = changes.getExpiry().getSeconds();
			if (candidateExpiry != 0 && candidateExpiry < token.expiry()) {
				appliedValidity.set(INVALID_EXPIRATION_TIME);
			}
			if (hasAutoRenewAccount || token.hasAutoRenewAccount()) {
				long changedAutoRenewPeriod = changes.getAutoRenewPeriod().getSeconds();
				if ((changedAutoRenewPeriod != 0 || !token.hasAutoRenewAccount()) &&
						!isValidAutoRenewPeriod(changedAutoRenewPeriod)) {
					appliedValidity.set(INVALID_RENEWAL_PERIOD);
				}
			}
			if (!token.hasKycKey() && newKycKey.isPresent()) {
				appliedValidity.set(TOKEN_HAS_NO_KYC_KEY);
			}
			if (!token.hasFreezeKey() && newFreezeKey.isPresent()) {
				appliedValidity.set(TOKEN_HAS_NO_FREEZE_KEY);
			}
			if (!token.hasWipeKey() && newWipeKey.isPresent()) {
				appliedValidity.set(TOKEN_HAS_NO_WIPE_KEY);
			}
			if (!token.hasSupplyKey() && newSupplyKey.isPresent()) {
				appliedValidity.set(TOKEN_HAS_NO_SUPPLY_KEY);
			}
			if (!token.hasAdminKey() && !isExpiryOnly) {
				appliedValidity.set(TOKEN_IS_IMMUTABLE);
			}
			if (OK != appliedValidity.get()) {
				return;
			}
			if (changes.hasAdminKey()) {
				var newAdminKey = changes.getAdminKey();
				if (REMOVES_ADMIN_KEY.test(newAdminKey)) {
					token.setAdminKey(UNUSED_KEY);
				} else {
					token.setAdminKey(asFcKeyUnchecked(changes.getAdminKey()));
				}
			}
			if (changes.hasAutoRenewAccount()) {
				token.setAutoRenewAccount(fromGrpcAccountId(changes.getAutoRenewAccount()));
			}
			if (token.hasAutoRenewAccount()) {
				long changedAutoRenewPeriod = changes.getAutoRenewPeriod().getSeconds();
				if (changedAutoRenewPeriod > 0) {
					token.setAutoRenewPeriod(changedAutoRenewPeriod);
				}
			}
			if (changes.hasFreezeKey()) {
				token.setFreezeKey(asFcKeyUnchecked(changes.getFreezeKey()));
			}
			if (changes.hasKycKey()) {
				token.setKycKey(asFcKeyUnchecked(changes.getKycKey()));
			}
			if (changes.hasSupplyKey()) {
				token.setSupplyKey(asFcKeyUnchecked(changes.getSupplyKey()));
			}
			if (changes.hasWipeKey()) {
				token.setWipeKey(asFcKeyUnchecked(changes.getWipeKey()));
			}
			if (hasNewSymbol) {
				var newSymbol = changes.getSymbol();
				token.setSymbol(newSymbol);
			}
			if (hasNewTokenName) {
				var newName = changes.getName();
				token.setName(newName);
			}
			if (changes.hasTreasury() && !changes.getTreasury().equals(token.treasury().toGrpcAccountId())) {
				var treasuryId = fromGrpcAccountId(changes.getTreasury());
				removeKnownTreasuryForToken(token.treasury().toGrpcAccountId(), tId);
				token.setTreasury(treasuryId);
				addKnownTreasury(changes.getTreasury(), tId);
			}
			if (changes.hasMemo()) {
				token.setMemo(changes.getMemo().getValue());
			}
			var expiry = changes.getExpiry().getSeconds();
			if (expiry != 0) {
				token.setExpiry(expiry);
			}
			if (changes.hasCustomFees()) {
				if (!token.isFeeScheduleMutable()) {
					appliedValidity.set(CUSTOM_FEES_ARE_MARKED_IMMUTABLE);
					return;
				}
				final var customFees = changes.getCustomFees();
				appliedValidity.set(validateFeeSchedule(customFees.getCustomFeesList()));
				if (OK != appliedValidity.get()) {
					return;
				}
				token.setFeeScheduleFrom(customFees);
			}
		});
		return appliedValidity.get();
	}

	public static boolean affectsExpiryAtMost(TokenUpdateTransactionBody op) {
		return !op.hasAdminKey() &&
				!op.hasKycKey() &&
				!op.hasWipeKey() &&
				!op.hasFreezeKey() &&
				!op.hasSupplyKey() &&
				!op.hasTreasury() &&
				!op.hasAutoRenewAccount() &&
				!op.hasCustomFees() &&
				op.getSymbol().length() == 0 &&
				op.getName().length() == 0 &&
				op.getAutoRenewPeriod().getSeconds() == 0;
	}

	private ResponseCodeEnum fullySanityChecked(
			boolean strictTokenCheck,
			AccountID aId,
			List<TokenID> tokens,
			BiFunction<AccountID, List<TokenID>, ResponseCodeEnum> action
	) {
		var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		if (strictTokenCheck) {
			for (TokenID tID : tokens) {
				var id = resolve(tID);
				if (id == MISSING_TOKEN) {
					return INVALID_TOKEN_ID;
				}
				var token = get(id);
				if (token.isDeleted()) {
					return TOKEN_WAS_DELETED;
				}
			}
		}
		return action.apply(aId, tokens);
	}

	private void resetPendingCreation() {
		pendingId = NO_PENDING_ID;
		pendingCreation = null;
	}

	private void throwIfNoCreationPending() {
		if (pendingId == NO_PENDING_ID) {
			throw new IllegalStateException("No pending token creation!");
		}
	}

	private void throwIfMissing(TokenID id) {
		if (!exists(id)) {
			throw new IllegalArgumentException(String.format(
					"Argument 'id=%s' does not refer to a known token!",
					readableId(id)));
		}
	}

	public boolean isKnownTreasury(AccountID aid) {
		return knownTreasuries.containsKey(aid);
	}

	@Override
	public boolean isTreasuryForToken(AccountID aId, TokenID tId) {
		if (!knownTreasuries.containsKey(aId)) {
			return false;
		}
		return knownTreasuries.get(aId).contains(tId);
	}

	private ResponseCodeEnum manageFlag(
			AccountID aId,
			TokenID tId,
			boolean value,
			ResponseCodeEnum keyFailure,
			TokenRelProperty flagProperty,
			Function<MerkleToken, Optional<JKey>> controlKeyFn
	) {
		return sanityChecked(aId, null, tId, token -> {
			if (controlKeyFn.apply(token).isEmpty()) {
				return keyFailure;
			}
			var relationship = asTokenRel(aId, tId);
			tokenRelsLedger.set(relationship, flagProperty, value);
			return OK;
		});
	}

	private ResponseCodeEnum sanityChecked(
			AccountID aId,
			AccountID aCounterPartyId,
			TokenID tId,
			Function<MerkleToken, ResponseCodeEnum> action
	) {
		var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		if (aCounterPartyId != null) {
			validity = checkAccountUsability(aCounterPartyId);
			if (validity != OK) {
				return validity;
			}
		}

		validity = checkTokenExistence(tId);
		if (validity != OK) {
			return validity;
		}

		var token = get(tId);
		if (token.isDeleted()) {
			return TOKEN_WAS_DELETED;
		}

		var key = asTokenRel(aId, tId);
		if (!tokenRelsLedger.exists(key)) {
			return TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
		}
		if (aCounterPartyId != null) {
			key = asTokenRel(aCounterPartyId, tId);
			if (!tokenRelsLedger.exists(key)) {
				return TOKEN_NOT_ASSOCIATED_TO_ACCOUNT;
			}
		}

		return action.apply(token);
	}

	private ResponseCodeEnum checkExistence(AccountID aId, TokenID tId) {
		var validity = checkAccountUsability(aId);
		if (validity != OK) {
			return validity;
		}
		return exists(tId) ? OK : INVALID_TOKEN_ID;
	}

	private ResponseCodeEnum checkTokenExistence(TokenID tId) {
		return exists(tId) ? OK : INVALID_TOKEN_ID;
	}

	Map<AccountID, Set<TokenID>> getKnownTreasuries() {
		return knownTreasuries;
	}
}
