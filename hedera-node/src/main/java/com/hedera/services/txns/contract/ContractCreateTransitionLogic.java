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
import com.hedera.services.contracts.execution.DomainUtils;
import com.hedera.services.files.HederaFs;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.accounts.HederaAccountCustomizer;
import com.hedera.services.legacy.core.jproto.JContractIDKey;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.store.contracts.ContractsStore;
import com.hedera.services.store.contracts.stubs.StubbedBlockchain;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCreateTransactionBody;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionRecord;
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
import org.hyperledger.besu.ethereum.worldstate.DefaultMutableWorldState;

import java.time.Instant;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.utils.EntityIdUtils.asContract;
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

	private final HederaLedger ledger;
	private final HederaFs hfs;
	private final LegacyCreator delegate;
	private final OptionValidator validator;
	private final TransactionContext txnCtx;
	private final MainnetTransactionProcessor txProcessor;
	private final ContractsStore store;
	private final Supplier<SequenceNumber> seqNo;

	private final Function<TransactionBody, ResponseCodeEnum> SEMANTIC_CHECK = this::validate;

	public ContractCreateTransitionLogic(
			HederaLedger ledger,
			HederaFs hfs,
			LegacyCreator delegate,
			OptionValidator validator,
			TransactionContext txnCtx,
			MainnetTransactionProcessor txProcessor,
			ContractsStore store,
			Supplier<SequenceNumber> seqNo
	) {
		this.ledger = ledger;
		this.hfs = hfs;
		this.txnCtx = txnCtx;
		this.delegate = delegate;
		this.validator = validator;
		this.txProcessor = txProcessor;
		this.store = store;
		this.seqNo = seqNo;
	}

	@Override
	public void doStateTransition() {
		try {
			var contractCreateTxn = txnCtx.accessor().getTxn();
			var op = contractCreateTxn.getContractCreateInstance();
			TransactionID transactionID = contractCreateTxn.getTransactionID();
			AccountID senderAccount = transactionID.getAccountID();
			Address sender = Address.fromHexString(asSolidityAddressHex(senderAccount));

			var key = new JContractIDKey(asContract(senderAccount)); // TODO: fix key

			HederaAccountCustomizer customizer = new HederaAccountCustomizer()
					.key(key)
					.memo(op.getMemo())
					.proxy(EntityId.fromGrpcAccountId(senderAccount))
					.expiry(txnCtx.consensusTime().getEpochSecond() + op.getAutoRenewPeriod().getSeconds())
					.autoRenewPeriod(op.getAutoRenewPeriod().getSeconds())
					.isSmartContract(true);
			var contractID = AccountID.newBuilder().setRealmNum(senderAccount.getRealmNum()).setShardNum(senderAccount.getShardNum()).setAccountNum(seqNo.get().getAndIncrement()).build();
			Address contractAddress = Address.fromHexString(asSolidityAddressHex(contractID));
			this.store.prepareAccountCreation(senderAccount, contractID, customizer);

			// TODO max gas check from Dynamic Properties

			var inputs = prepBytecode(op);
			if (inputs.getValue() != OK) {
				txnCtx.setStatus(inputs.getValue());
				return;
			}
			String contractByteCodeString = new String(inputs.getKey());
			if (!op.getConstructorParameters().isEmpty()) {
				final var constructorParamsHexString = CommonUtils.hex(
						op.getConstructorParameters().toByteArray());
				contractByteCodeString += constructorParamsHexString;
			}

			// TODO Gas Price
			Wei gasPrice = Wei.of(1);
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
					Address.fromHexString(asSolidityAddressHex(txnCtx.submittingNodeAccount())),
					OperationTracer.NO_TRACING,
					false);
			updater.commit();
			if (result.isSuccessful()) {
				var contractFunctionResult = ContractFunctionResult.newBuilder()
						.setGasUsed(result.getEstimateGasUsedByTransaction())
						.setErrorMessage(result.getRevertReason().toString())
						.setContractID(EntityIdUtils.asContract(contractID));
				result.getLogs().stream().map(DomainUtils::asBesuHapiLog).forEach(contractFunctionResult::addLogInfo);

				txnCtx.setCreateResult(contractFunctionResult.build());
				txnCtx.setCreated(EntityIdUtils.asContract(contractID));
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
				Address.ZERO, // TODO Coinbase might be the 0.98 address
				Difficulty.ONE,
				0,
				12_500_000L, // TODO GlobalDynamic property maxGas
				timestamp,
				1L);
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
		
		return validator.memoCheck(op.getMemo());
	}
}
