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

import com.google.protobuf.ByteString;
import com.hedera.services.config.HederaNumbers;
import com.hedera.services.context.TransactionContext;
import com.hedera.services.context.properties.BootstrapProperties;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.virtual.ContractKey;
import com.hedera.services.state.merkle.virtual.ContractPath;
import com.hedera.services.state.merkle.virtual.ContractUint256;
import com.hedera.services.state.merkle.virtual.persistence.FCVirtualMapHashStore;
import com.hedera.services.state.merkle.virtual.persistence.FCVirtualMapLeafStore;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.store.contracts.stubs.StubbedBlockchain;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.txns.validation.PureValidation;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.swirlds.common.CommonUtils;
import com.swirlds.fcmap.FCMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_GAS;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.CONTRACT_NEGATIVE_VALUE;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.SUCCESS;

public class ContractCallTransitionLogic implements TransitionLogic {
	private static final Logger log = LogManager.getLogger(ContractCallTransitionLogic.class);

	private final LegacyCaller delegate;
	private final OptionValidator validator;
	private final TransactionContext txnCtx;
	private final Supplier<SequenceNumber> seqNo;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts;
	private final MainnetTransactionProcessor txProcessor;
	private final AccountStateStore store;
	private final GlobalDynamicProperties properties;

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

	private FCVirtualMapHashStore<ContractPath> hashStore;
	private FCVirtualMapLeafStore<ContractKey, ContractPath, ContractUint256> leafStore;

	public ContractCallTransitionLogic(
			LegacyCaller delegate,
			OptionValidator validator,
			TransactionContext txnCtx,
			Supplier<SequenceNumber> seqNo,
			Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts,
			MainnetTransactionProcessor txProcessor,
			AccountStateStore store,
			GlobalDynamicProperties properties
	) {
		this.delegate = delegate;
		this.validator = validator;
		this.txnCtx = txnCtx;
		this.seqNo = seqNo;
		this.contracts = contracts;
		this.txProcessor = txProcessor;
		this.store = store;
		this.properties = properties;
	}

	@FunctionalInterface
	public interface LegacyCaller {
		TransactionRecord perform(TransactionBody txn, Instant consensusTime, SequenceNumber seqNo);
	}

	@Override
	public void doStateTransition() {
		try {
			var contractCallTxn = txnCtx.accessor().getTxn();
			var op = contractCallTxn.getContractCall();
			ResponseCodeEnum callResponseStatus = validateContractExistence(op.getContractID());
			if (callResponseStatus != ResponseCodeEnum.OK) {
				txnCtx.setStatus(FAIL_INVALID);
				return;
			}

			TransactionID transactionID = contractCallTxn.getTransactionID();
			AccountID senderAccount = transactionID.getAccountID();
			Address sender = Address.fromHexString(asSolidityAddressHex(senderAccount));
			AccountID receiverAccount =
					AccountID.newBuilder().setAccountNum(op.getContractID().getContractNum())
							.setRealmNum(op.getContractID().getRealmNum())
							.setShardNum(op.getContractID().getShardNum()).build();
			Address receiver = Address.fromHexString(asSolidityAddressHex(receiverAccount));
			Wei gasPrice = Wei.of(1);
			long gasLimit = 1_000_000L; // 1М gas limit

			Wei value = Wei.ZERO;
			if (op.getAmount() > 0) {
				value = Wei.of(op.getAmount());
			}

			var evmTx = new Transaction(0, gasPrice, gasLimit, Optional.of(receiver), value, null, Bytes.fromHexString(CommonUtils.hex(op.getFunctionParameters().toByteArray())), sender, Optional.empty());
			var defaultMutableWorld = new DefaultMutableWorldState(this.store);
			var updater = defaultMutableWorld.updater();
			var result = txProcessor.processTransaction(
					stubbedBlockchain(),
					updater,
					stubbedBlockHeader(txnCtx.consensusTime().getEpochSecond()),
					evmTx,
					Address.fromHexString(asSolidityAddressHex(txnCtx.submittingNodeAccount())),
					OperationTracer.NO_TRACING,
					false);
			updater.commit();
			var contractFunctionResult = ContractFunctionResult.newBuilder()
					.setGasUsed(result.getEstimateGasUsedByTransaction())
					.setErrorMessage(result.getRevertReason().toString());
			Optional.ofNullable(result.getOutput().toArray())
					.map(ByteString::copyFrom)
					.ifPresent(contractFunctionResult::setContractCallResult);
			txnCtx.setCallResult(contractFunctionResult.build());
			if (result.isSuccessful()) {
				txnCtx.setStatus(SUCCESS);
			} else {
				txnCtx.setStatus(FAIL_INVALID);
			}
		} catch (Exception e) {
			log.warn("Avoidable exception!", e);
			txnCtx.setStatus(FAIL_INVALID);
		}
	}

	@Override
	public Predicate<TransactionBody> applicability() {
		return TransactionBody::hasContractCall;
	}

	@Override
	public Function<TransactionBody, ResponseCodeEnum> semanticCheck() {
		return SEMANTIC_CHECK;
	}

	public ResponseCodeEnum validate(TransactionBody contractCallTxn) {
		var op = contractCallTxn.getContractCall();

		var status = validator.queryableContractStatus(op.getContractID(), contracts.get());
		if (status != OK) {
			return status;
		}
		if (op.getGas() < 0) {
			return CONTRACT_NEGATIVE_GAS;
		}
		if (op.getAmount() < 0) {
			return CONTRACT_NEGATIVE_VALUE;
		}
		return OK;
	}

	public ResponseCodeEnum validateContractExistence(ContractID cid) {
		return PureValidation.queryableContractStatus(cid, contracts.get());
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
				properties.maxGas(),
				timestamp,
				1L);
	}
}
