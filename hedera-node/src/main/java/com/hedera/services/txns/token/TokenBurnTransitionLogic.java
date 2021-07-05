package com.hedera.services.txns.token;

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
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.Id;
import com.hedera.services.txns.TransitionLogic;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenBurnTransactionBody;
import com.hederahashgraph.api.proto.java.TransactionBody;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Function;
import java.util.function.Predicate;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_BURN_AMOUNT;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_TOKEN_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;

/**
 * Provides the state transition for token burning.
 */
public class TokenBurnTransitionLogic implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(TokenBurnTransitionLogic.class);

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

	private final TypedTokenStore store;
	private final TransactionContext txnCtx;

	public TokenBurnTransitionLogic(
			TypedTokenStore store,
			TransactionContext txnCtx
	) {
		this.store = store;
		this.txnCtx = txnCtx;
	}

	@Override
	public void doStateTransition() {
		/* --- Translate from gRPC types --- */
		final var op = txnCtx.accessor().getTxn().getTokenBurn();
		final var grpcId = op.getToken();
		final var targetId = new Id(grpcId.getShardNum(), grpcId.getRealmNum(), grpcId.getTokenNum());

		/* --- Load the model objects --- */
		final var token = store.loadToken(targetId);
		final var treasuryRel = store.loadTokenRelationship(token, token.getTreasury());

		/* --- Do the business logic --- */
		token.burn(treasuryRel, op.getAmount());

		/* --- Persist the updated models --- */
		store.persistToken(token);
		store.persistTokenRelationship(treasuryRel);
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasTokenBurn;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	public ResponseCodeEnum validate(TransactionBody txnBody) {
		TokenBurnTransactionBody op = txnBody.getTokenBurn();

		if (!op.hasToken()) {
			return INVALID_TOKEN_ID;
		}

		if (op.getAmount() <= 0) {
			return INVALID_TOKEN_BURN_AMOUNT;
		}

		return OK;
	}
}
