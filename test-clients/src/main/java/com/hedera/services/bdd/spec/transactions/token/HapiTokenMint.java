package com.hedera.services.bdd.spec.transactions.token;

/*-
 * ‌
 * Hedera Services Test Clients
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
import com.google.protobuf.ByteString;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.usage.token.TokenMintUsage;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.SubType;
import com.hederahashgraph.api.proto.java.TokenMintTransactionBody;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.fee.SigValueObj;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class HapiTokenMint extends HapiTxnOp<HapiTokenMint> {
	static final Logger log = LogManager.getLogger(HapiTokenMint.class);

	private long amount;
	private String token;
	private List<ByteString> metadata;
	private SubType subType;

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.TokenMint;
	}

	public HapiTokenMint(String token, long amount) {
		this.token = token;
		this.amount = amount;
		this.metadata = new ArrayList<>();
		this.subType = figureSubType();
	}

	public HapiTokenMint(String token, List<ByteString> metadata) {
		this.token = token;
		this.metadata = metadata;
		this.subType = figureSubType();
	}

	public HapiTokenMint(String token, List<ByteString> metadata, String txNamePrefix) {
		this.token = token;
		this.metadata = metadata;
		this.amount = 0;
	}

	@Override
	protected HapiTokenMint self() {
		return this;
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		return spec.fees().forActivityBasedOp(
				HederaFunctionality.TokenMint, subType, this::usageEstimate, txn, numPayerKeys);
	}

	private SubType figureSubType() {
		if (metadata.isEmpty()) {
			return SubType.TOKEN_FUNGIBLE_COMMON;
		} else {
			return SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
		}
	}

	private FeeData usageEstimate(TransactionBody txn, SigValueObj svo) {
		return TokenMintUsage.newEstimate(txn, suFrom(svo)).givenSubType(subType).get();
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		var tId = TxnUtils.asTokenId(token, spec);
		TokenMintTransactionBody opBody = spec
				.txns()
				.<TokenMintTransactionBody, TokenMintTransactionBody.Builder>body(
						TokenMintTransactionBody.class, b -> {
							b.setToken(tId);
							b.setAmount(amount);
							b.addAllMetadata(metadata);
						});
		return b -> b.setTokenMint(opBody);
	}

	@Override
	protected List<Function<HapiApiSpec, Key>> defaultSigners() {
		return List.of(
				spec -> spec.registry().getKey(effectivePayer(spec)),
				spec -> spec.registry().getSupplyKey(token));
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getTokenSvcStub(targetNodeFor(spec), useTls)::mintToken;
	}

	@Override
	public void updateStateOf(HapiApiSpec spec) throws Throwable {
		if (actualStatus != SUCCESS) {
			return;
		}
		lookupSubmissionRecord(spec);
		spec.registry().saveCreationTime(token, recordOfSubmission.getConsensusTimestamp());
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper()
				.add("token", token)
				.add("amount", amount)
				.add("metadata", metadata);
		return helper;
	}
}
