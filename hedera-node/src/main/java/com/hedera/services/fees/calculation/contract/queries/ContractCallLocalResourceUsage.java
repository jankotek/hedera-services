package com.hedera.services.fees.calculation.contract.queries;

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
import com.google.protobuf.TextFormat;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.fees.calculation.QueryResourceUsageEstimator;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.queries.contract.ContractCallLocalAnswer;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.store.contracts.stubs.StubbedBlockchain;
import com.hedera.services.txns.validation.PureValidation;
import com.hedera.services.utils.SignedTxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractCallLocalResponse;
import com.hederahashgraph.api.proto.java.ContractFunctionResult;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.ResponseHeader;
import com.hederahashgraph.api.proto.java.ResponseType;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.builder.RequestBuilder;
import com.hederahashgraph.fee.SmartContractFeeBuilder;
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
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static com.hedera.services.queries.AnswerService.NO_QUERY_CTX;
import static com.hedera.services.queries.contract.ContractCallLocalAnswer.CONTRACT_CALL_LOCAL_CTX_KEY;
import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static com.hederahashgraph.api.proto.java.ResponseType.ANSWER_ONLY;

public class ContractCallLocalResourceUsage implements QueryResourceUsageEstimator {
	private static final Logger log = LogManager.getLogger(ContractCallLocalResourceUsage.class);

	private final ContractCallLocalAnswer.LegacyLocalCaller delegate;
	private final SmartContractFeeBuilder usageEstimator;
	private final GlobalDynamicProperties properties;
	private final MainnetTransactionProcessor txProcessor;
	private final AccountStateStore store;
	private final Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts;
	private final HederaLedger ledger;


	public ContractCallLocalResourceUsage(
			ContractCallLocalAnswer.LegacyLocalCaller delegate,
			SmartContractFeeBuilder usageEstimator,
			GlobalDynamicProperties properties,
			MainnetTransactionProcessor txProcessor,
			Supplier<FCMap<MerkleEntityId, MerkleAccount>> contracts,
			HederaLedger ledger,
			AccountStateStore store
	) {
		this.delegate = delegate;
		this.properties = properties;
		this.usageEstimator = usageEstimator;
		this.txProcessor = txProcessor;
		this.store = store;
		this.ledger = ledger;
		this.contracts = contracts;
	}

	@Override
	public boolean applicableTo(Query query) {
		return query.hasContractCallLocal();
	}

	@Override
	public FeeData usageGiven(Query query, StateView view) {
		return usageFor(query, view, query.getContractCallLocal().getHeader().getResponseType(), NO_QUERY_CTX);
	}

	@Override
	public FeeData usageGivenType(Query query, StateView view, ResponseType type) {
		return usageFor(query, view, type, NO_QUERY_CTX);
	}

	@Override
	public FeeData usageGiven(Query query, StateView view, Map<String, Object> queryCtx) {
		return usageFor(
				query,
				view,
				query.getContractCallLocal().getHeader().getResponseType(),
				Optional.of(queryCtx));
	}

	private FeeData usageFor(Query query, StateView view, ResponseType type, Optional<Map<String, Object>> queryCtx) {
		try {
			var op = query.getContractCallLocal();
			ContractCallLocalResponse response;
			if (queryCtx.isEmpty()) {
				response = dummyResponse(op.getContractID());
			} else {
				ResponseCodeEnum callResponseStatus = validateContractExistence(op.getContractID());
				if (callResponseStatus != ResponseCodeEnum.OK) {
					ResponseHeader responseHeader = RequestBuilder.getResponseHeader(callResponseStatus, 0l,
							ANSWER_ONLY, ByteString.EMPTY);
					response = ContractCallLocalResponse.newBuilder().setHeader(responseHeader).build();
					if (log.isDebugEnabled()) {
						log.debug("contractCallLocal  -Invalid Contract ID "
								+ TextFormat.shortDebugString(op.getContractID()));
					}
				} else {

					TransactionBody body =
							SignedTxnAccessor.uncheckedFrom(op.getHeader().getPayment()).getTxn();

					TransactionID transactionID = body.getTransactionID();
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
					var evmTx = new Transaction(0, gasPrice, gasLimit, Optional.of(receiver), value, null, Bytes.fromHexString(CommonUtils.hex(op.getFunctionParameters().toByteArray())), sender, Optional.empty());
					var defaultMutableWorld = new DefaultMutableWorldState(this.store);
					var updater = defaultMutableWorld.updater();
					var result = txProcessor.processTransaction(
							stubbedBlockchain(),
							updater,
							stubbedBlockHeader(Instant.now().getEpochSecond()),
							evmTx,
							Address.fromHexString(asSolidityAddressHex(body.getNodeAccountID())),
							OperationTracer.NO_TRACING,
							false);
					var contractFunctionResult = ContractFunctionResult.newBuilder()
							.setGasUsed(result.getEstimateGasUsedByTransaction())
							.setErrorMessage(result.getRevertReason().toString());
					Optional.ofNullable(result.getOutput().toArray())
							.map(ByteString::copyFrom)
							.ifPresent(contractFunctionResult::setContractCallResult);
					var header = RequestBuilder.getResponseHeader(OK, 0L, ANSWER_ONLY, ByteString.EMPTY);
					response = ContractCallLocalResponse.newBuilder()
							.setHeader(header)
							.setFunctionResult(contractFunctionResult.build())
							.build();
				}

				queryCtx.get().put(CONTRACT_CALL_LOCAL_CTX_KEY, response);
			}
			var nonGasUsage = usageEstimator.getContractCallLocalFeeMatrices(
					op.getFunctionParameters().size(),
					response.getFunctionResult(),
					type);
			return nonGasUsage.toBuilder()
					.setNodedata(nonGasUsage.getNodedata().toBuilder().setGas(op.getGas()))
					.build();
		} catch (Exception internal) {
			log.warn("Usage estimation unexpectedly failed for {}!", query, internal);
			throw new IllegalStateException(internal);
		}
	}
	public ResponseCodeEnum validateContractExistence(ContractID cid) {
		return PureValidation.queryableContractStatus(cid, contracts.get());
	}

	ContractCallLocalResponse dummyResponse(ContractID target) {
		return ContractCallLocalResponse.newBuilder()
				.setFunctionResult(ContractFunctionResult.newBuilder()
						.setContractCallResult(ByteString.copyFrom(new byte[properties.localCallEstRetBytes()]))
						.setContractID(target))
				.setHeader(ResponseHeader.newBuilder().setNodeTransactionPrecheckCode(OK))
				.build();
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
				1L);
	}
}
