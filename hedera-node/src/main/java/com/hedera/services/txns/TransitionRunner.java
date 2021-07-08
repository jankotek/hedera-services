package com.hedera.services.txns;

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
import com.hedera.services.exceptions.InvalidTransactionException;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class TransitionRunner {
	private static final Logger log = LogManager.getLogger(TransitionRunner.class);

	private static final EnumSet<HederaFunctionality> refactoredOps = EnumSet.of(
			TokenMint, TokenBurn,
			TokenAssociateToAccount,
			TokenAccountWipe
	);

	private final TransactionContext txnCtx;
	private final TransitionLogicLookup lookup;

	public TransitionRunner(TransactionContext txnCtx, TransitionLogicLookup lookup) {
		this.txnCtx = txnCtx;
		this.lookup = lookup;
	}

	/**
	 * Tries to find and run transition logic for the transaction wrapped by the
	 * given accessor.
	 *
	 * @param accessor the transaction accessor
	 * @return true if the logic was run to completion
	 */
	public boolean tryTransition(@NotNull TxnAccessor accessor) {
		final var txn = accessor.getTxn();
		final var function = accessor.getFunction();
		final var logic = lookup.lookupFor(function, txn);
		if (logic.isEmpty()) {
			log.warn("Transaction w/o applicable transition logic at consensus :: {}", accessor::getSignedTxnWrapper);
			txnCtx.setStatus(FAIL_INVALID);
			return false;
		} else {
			final var transition = logic.get();
			final var validity = transition.validateSemantics(accessor);
			if (validity != OK) {
				txnCtx.setStatus(validity);
				return false;
			}
			try {
				transition.doStateTransition();
				/* Only certain functions are refactored */
				if (refactoredOps.contains(function)) {
					txnCtx.setStatus(SUCCESS);
				}
			} catch (InvalidTransactionException ite) {
				final var code = ite.getResponseCode();
				txnCtx.setStatus(code);
				if (code == FAIL_INVALID) {
					log.warn("Avoidable failure in transition logic for {}", accessor.getSignedTxnWrapper(), ite);
				}
			}
			return true;
		}
	}
}
