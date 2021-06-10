package com.hedera.services.txns.contract;

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
import com.hedera.services.files.HederaFs;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.store.contracts.ContractsStore;
import com.hedera.services.store.contracts.stubs.StubbedBlockchain;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.builder.RequestBuilder;
import com.swirlds.common.CommonUtils;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Difficulty;
import org.hyperledger.besu.ethereum.core.Hash;
import org.hyperledger.besu.ethereum.core.ProcessableBlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.mainnet.MainnetTransactionProcessor;
import org.hyperledger.besu.ethereum.vm.OperationTracer;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.DefaultMutableWorldState;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.AUTORENEW_DURATION_NOT_IN_RANGE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_FILE_EMPTY;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FILE_ID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_RENEWAL_PERIOD;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractCreateTransitionLogic implements TransitionLogic {
	private static final byte[] MISSING_BYTECODE = new byte[0];

	@FunctionalInterface
	public interface LegacyCreator {
		TransactionRecord perform(
				TransactionBody txn,
				Instant consensusTime,
				byte[] bytecode,
				SequenceNumber seqNum);
	}

	private final HederaFs hfs;
	private final LegacyCreator delegate;
	private final OptionValidator validator;
	private final TransactionContext txnCtx;
	private final Supplier<SequenceNumber> seqNo;
	private final MainnetTransactionProcessor txProcessor;
	private final AccountStateStore store;

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

	public ContractCreateTransitionLogic(
			HederaFs hfs,
			LegacyCreator delegate,
			Supplier<SequenceNumber> seqNo,
			OptionValidator validator,
			TransactionContext txnCtx,
			MainnetTransactionProcessor txProcessor,
			ContractsStore store
	) {
		this.hfs = hfs;
		this.seqNo = seqNo;
		this.txnCtx = txnCtx;
		this.delegate = delegate;
		this.validator = validator;
		this.txProcessor = txProcessor;
		this.store = store;
	}

	@Override
	public void doStateTransition() {
		try {
			var contractCreateTxn = txnCtx.accessor().getTxn();
			var op = contractCreateTxn.getContractCreateInstance();
			TransactionID transactionID = contractCreateTxn.getTransactionID();
			Instant startTime = RequestBuilder.convertProtoTimeStamp(transactionID.getTransactionValidStart());
			AccountID senderAccount = transactionID.getAccountID();
			Address sender = Address.fromHexString(asSolidityAddressHex(senderAccount));
			Address contractAddress = Address.fromHexString(asSolidityAddressHex(
					AccountID.newBuilder()
							.setShardNum(0)
							.setRealmNum(senderAccount.getRealmNum())
							.setAccountNum(seqNo.get().getAndIncrement()).build()));
			// TODO max gas check?

			var inputs = prepBytecode(op);
			if (inputs.getValue() != OK) {
				txnCtx.setStatus(inputs.getValue());
				return;
			}
			String contractByteCodeString = new String(inputs.getKey());
			if (op.getConstructorParameters() != null && !op.getConstructorParameters().isEmpty()) {
				final var constructorParamsHexString = CommonUtils.hex(
						op.getConstructorParameters().toByteArray());
				contractByteCodeString += constructorParamsHexString;
			}

//			// TODO Gas Price
			Wei gasPrice = Wei.of(1000000000L);
			long gasLimit = 15000000L;

			Wei value = Wei.ZERO;
			if (op.getInitialBalance() > 0) {
				value = Wei.of(op.getInitialBalance());
			}

			// TODO miningBeneficiary, blockHashLookup
			// TODO we can remove SECPSignature from Transaction
			var evmTx = new Transaction(0, gasPrice, gasLimit, Optional.empty(), value, null, Bytes.fromHexString(contractByteCodeString), sender, Optional.empty(), contractAddress);
			var defaultMutableWorld = new DefaultMutableWorldState(this.store);
			var updater = defaultMutableWorld.updater();
			var result = txProcessor.processTransaction(
					stubbedBlockchain(),
					updater,
					stubbedBlockHeader(txnCtx.consensusTime().getEpochSecond()),
					evmTx,
					Address.ZERO,
					OperationTracer.NO_TRACING,
					null,
					false);
			updater.commit();
			// Blockchain -> we have to stub fake block
			// WorldUpdater -> we have to implement it
			// ProcessableBlockHeader -> we have to stub fake block header
			// Transaction
			// Address
			// BlockHashLookup
			// isPersistingPrivateState = false
			// TransactionValidationParams

//			var legacyRecord = delegate.perform(contractCreateTxn, txnCtx.consensusTime(), inputs.getKey(), seqNo.get());

//			var outcome = legacyRecord.getReceipt().getStatus();
//			txnCtx.setStatus();
//			txnCtx.setCreateResult(legacyRecord.getContractCreateResult());
//			if (outcome == SUCCESS) {
//				txnCtx.setCreated(legacyRecord.getReceipt().getContractID());
//			}
			if (result.isSuccessful()) {
				txnCtx.setStatus(SUCCESS);
			} else {
				txnCtx.setStatus(FAIL_INVALID);
			}
		} catch (Exception e) {
			txnCtx.setStatus(FAIL_INVALID);
		}
	}

	private Blockchain stubbedBlockchain() {
		return new StubbedBlockchain();
	}

	private ProcessableBlockHeader stubbedBlockHeader(long timestamp) {
		return new ProcessableBlockHeader(
				Hash.EMPTY,
				Address.ZERO, //Coinbase might be the 0.98 address?
				Difficulty.ONE,
				0,
				12_500_000L,
				timestamp,
				1000000000L);
	}

	private Map.Entry<byte[], ResponseCodeEnum> prepBytecode(ContractCreateTransactionBody op) {
		var bytecodeSrc = op.getFileID();
		if (!hfs.exists(bytecodeSrc)) {
			return new AbstractMap.SimpleImmutableEntry<>(MISSING_BYTECODE, INVALID_FILE_ID);
		}
		byte[] bytecode = hfs.cat(bytecodeSrc);
		if (bytecode.length == 0) {
			return new AbstractMap.SimpleImmutableEntry<>(MISSING_BYTECODE, CONTRACT_FILE_EMPTY);
		}
		return new AbstractMap.SimpleImmutableEntry<>(bytecode, OK);
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasContractCreateInstance;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	public ResponseCodeEnum validate(TransactionBody contractCreateTxn) {
		var op = contractCreateTxn.getContractCreateInstance();

		if (!op.hasAutoRenewPeriod() || op.getAutoRenewPeriod().getSeconds() < 1) {
			return INVALID_RENEWAL_PERIOD;
		}
		if (!validator.isValidAutoRenewPeriod(op.getAutoRenewPeriod())) {
			return AUTORENEW_DURATION_NOT_IN_RANGE;
		}
		if (op.getGas() < 0) {
			return CONTRACT_NEGATIVE_GAS;
		}
		if (op.getInitialBalance() < 0) {
			return CONTRACT_NEGATIVE_VALUE;
		}
		var memoValidity = validator.memoCheck(op.getMemo());
		if (memoValidity != OK) {
			return memoValidity;
		}

		return OK;
	}
}
