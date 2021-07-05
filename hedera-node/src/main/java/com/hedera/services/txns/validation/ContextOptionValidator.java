package com.hedera.services.txns.validation;

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
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.Duration;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TopicID;
import com.hederahashgraph.api.proto.java.TransferList;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bouncycastle.util.Arrays;

import java.time.Instant;
import java.util.Optional;

import static com.hedera.services.legacy.core.jproto.JKey.mapKey;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_ZERO_BYTE_IN_STRING;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MEMO_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_NAME;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.MISSING_TOKEN_SYMBOL;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_NAME_TOO_LONG;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.TOKEN_SYMBOL_TOO_LONG;

/**
 * Implements an {@link OptionValidator} that relies an injected instance
 * of the {@link TransactionContext} to determine whether various options are
 * permissible.
 *
 * @author Michael Tinker
 */
public class ContextOptionValidator implements OptionValidator {
	private static final Logger log = LogManager.getLogger(ContextOptionValidator.class);

	private final long maxEntityLifetime;
	private final AccountID nodeAccount;
	private final TransactionContext txnCtx;
	private final GlobalDynamicProperties dynamicProperties;

	public ContextOptionValidator(
			AccountID nodeAccount,
			PropertySource properties,
			TransactionContext txnCtx,
			GlobalDynamicProperties dynamicProperties
	) {
		maxEntityLifetime = properties.getLongProperty("entities.maxLifetime");
		this.txnCtx = txnCtx;
		this.nodeAccount = nodeAccount;
		this.dynamicProperties = dynamicProperties;
	}

	@Override
	public boolean isThisNodeAccount(AccountID id) {
		return nodeAccount.equals(id);
	}

	@Override
	public boolean hasGoodEncoding(Key key) {
		try {
			mapKey(key);
			return true;
		} catch (DecoderException ignore) {
			return false;
		}
	}

	@Override
	public boolean isValidTxnDuration(long duration) {
		return duration >= dynamicProperties.minTxnDuration() && duration <= dynamicProperties.maxTxnDuration();
	}

	@Override
	public boolean isValidExpiry(Timestamp expiry) {
		final var consensusNow = txnCtx.consensusTime();
		final var expiryGivenMaxLifetime = consensusNow.plusSeconds(maxEntityLifetime);
		final var then = Instant.ofEpochSecond(expiry.getSeconds(), expiry.getNanos());
		return then.isAfter(consensusNow) && then.isBefore(expiryGivenMaxLifetime);
	}

	@Override
	public boolean isValidAutoRenewPeriod(Duration autoRenewPeriod) {
		long duration = autoRenewPeriod.getSeconds();

		return duration >= dynamicProperties.minAutoRenewDuration() &&
				duration <= dynamicProperties.maxAutoRenewDuration();
	}

	@Override
	public boolean isAcceptableTransfersLength(TransferList accountAmounts) {
		return accountAmounts.getAccountAmountsCount() <= dynamicProperties.maxTransferListSize();
	}

	@Override
	public ResponseCodeEnum queryableTopicStatus(TopicID id, FCMap<MerkleEntityId, MerkleTopic> topics) {
		MerkleTopic merkleTopic = topics.get(MerkleEntityId.fromTopicId(id));

		return Optional.ofNullable(merkleTopic)
				.map(t -> t.isDeleted() ? INVALID_TOPIC_ID : OK)
				.orElse(INVALID_TOPIC_ID);
	}

	@Override
	public ResponseCodeEnum tokenSymbolCheck(String symbol) {
		return tokenStringCheck(
				symbol,
				dynamicProperties.maxTokenSymbolUtf8Bytes(),
				MISSING_TOKEN_SYMBOL,
				TOKEN_SYMBOL_TOO_LONG);
	}

	@Override
	public ResponseCodeEnum tokenNameCheck(String name) {
		return tokenStringCheck(
				name,
				dynamicProperties.maxTokenNameUtf8Bytes(),
				MISSING_TOKEN_NAME,
				TOKEN_NAME_TOO_LONG);
	}

	private ResponseCodeEnum tokenStringCheck(
			String s,
			int maxLen,
			ResponseCodeEnum onMissing,
			ResponseCodeEnum onTooLong
	) {
		int numUtf8Bytes = StringUtils.getBytesUtf8(s).length;
		if (numUtf8Bytes == 0) {
			return onMissing;
		}
		if (numUtf8Bytes > maxLen) {
			return onTooLong;
		}
		if (s.contains("\u0000")) {
			return INVALID_ZERO_BYTE_IN_STRING;
		}
		return OK;

	}

	@Override
	public ResponseCodeEnum memoCheck(String cand) {
		return rawMemoCheck(StringUtils.getBytesUtf8(cand));
	}

	@Override
	public ResponseCodeEnum rawMemoCheck(byte[] utf8Cand) {
		return rawMemoCheck(utf8Cand, Arrays.contains(utf8Cand, (byte)0));
	}

	@Override
	public ResponseCodeEnum rawMemoCheck(byte[] utf8Cand, boolean hasZeroByte) {
		if (utf8Cand.length > dynamicProperties.maxMemoUtf8Bytes()) {
			return MEMO_TOO_LONG;
		} else if (hasZeroByte) {
			return INVALID_ZERO_BYTE_IN_STRING;
		} else {
			return OK;
		}
	}

	/* Not applicable until auto-renew is implemented. */
	boolean isExpired(MerkleTopic merkleTopic) {
		Instant expiry = Instant.ofEpochSecond(
				merkleTopic.getExpirationTimestamp().getSeconds(),
				merkleTopic.getExpirationTimestamp().getNanos());
		return txnCtx.consensusTime().isAfter(expiry);
	}

	@Override
	public boolean isAfterConsensusSecond(long now) {
		return now > txnCtx.consensusTime().getEpochSecond();
	}
}
