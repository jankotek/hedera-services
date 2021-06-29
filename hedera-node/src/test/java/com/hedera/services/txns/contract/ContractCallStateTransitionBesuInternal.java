package com.hedera.services.txns.contract;

import com.hedera.services.context.TransactionContext;
import com.hedera.services.state.merkle.virtual.VirtualMap;
import com.hedera.services.state.merkle.virtual.persistence.mmap.MemMapDataSource;
import com.hedera.services.state.merkle.virtual.persistence.mmap.VirtualMapDataStore;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.store.contracts.EvmAccountImpl;
import com.hedera.services.store.contracts.stubs.StubbedBlockchain;
import com.hederahashgraph.api.proto.java.AccountID;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.chain.Blockchain;
import org.hyperledger.besu.ethereum.core.*;
import org.hyperledger.besu.ethereum.core.fees.CoinbaseFeePriceCalculator;
import org.hyperledger.besu.ethereum.core.fees.TransactionPriceCalculator;
import org.hyperledger.besu.ethereum.mainnet.*;
import org.hyperledger.besu.ethereum.mainnet.contractvalidation.MaxCodeSizeRule;
import org.hyperledger.besu.ethereum.processing.TransactionProcessingResult;
import org.hyperledger.besu.ethereum.vm.OperationTracer;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.DefaultMutableWorldState;

import java.io.File;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static com.hedera.services.utils.EntityIdUtils.asAccount;
import static com.hedera.services.utils.EntityIdUtils.asSolidityAddressHex;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

public class ContractCallStateTransitionBesuInternal {

	private TransactionContext txnCtx;
	private AccountStateStore accountStateStore;

	MainnetTransactionProcessor txProcessor;
	Wei gasPrice = Wei.of(1);
	long gasLimit = 1_000_000L; // 1М gas limit
	Wei value = Wei.of(0);

	private final AccountID sender = asAccount(new EntityId(0, 0, 1001));
	private final AccountID collector = asAccount(new EntityId(0, 0, 98));
	private final AccountID contract = asAccount(new EntityId(0, 0, 1002));

	private final Address senderAddress = Address.fromHexString(asSolidityAddressHex(sender));
	private final Address collectorAddress = Address.fromHexString(asSolidityAddressHex(collector));
	private final Address contractAddress = Address.fromHexString(asSolidityAddressHex(contract));

	public void setUp() {

		Instant consensusTime = Instant.now();
		var dataStore = new VirtualMapDataStore(new File("data/diskFs/blobs").toPath(), 32, 32);
		dataStore.open(); // otherwise throws
		var contractMap = new VirtualMap(
				new MemMapDataSource(
						dataStore,
						new com.hedera.services.state.merkle.virtual.Account(
								contract.getShardNum(), contract.getRealmNum(), contract.getAccountNum())
				));

		txnCtx = mock(TransactionContext.class);
		accountStateStore = mock(AccountStateStore.class);

		given(txnCtx.consensusTime()).willReturn(consensusTime);

		EvmAccount senderAccount = new EvmAccountImpl(senderAddress, Wei.of(1000000000));
		UpdateTrackingAccount callerTrackingAccount = new UpdateTrackingAccount(senderAccount);
		EvmAccount collectorAccount = new EvmAccountImpl(collectorAddress, Wei.of(1000000000));
		EvmAccount contractAccount = new EvmAccountImpl(contractAddress, Wei.of(1000000000), getBytesForContract());

		when(accountStateStore.get(senderAddress)).thenReturn(senderAccount);
		when(accountStateStore.get(collectorAddress)).thenReturn(collectorAccount);
		when(accountStateStore.get(contractAddress)).thenReturn(contractAccount);

		when(accountStateStore.getCode(any())).thenReturn(getBytesForContract());
		when(accountStateStore.newStorageMap(contractAddress)).thenReturn(contractMap);
		var gasCalculator = new ConstantinopleGasCalculator();
		var transactionValidator = new MainnetTransactionValidator(gasCalculator, false, Optional.empty(), false);
		var evm = MainnetEvmRegistries.constantinople(gasCalculator);
		var contractCreateProcessor = new MainnetContractCreationProcessor(gasCalculator, evm, false, Collections.singletonList(MaxCodeSizeRule.of(24576)), 0);
		var privacyParameters = new PrivacyParameters.Builder().setEnabled(false).build();
		var precompiledContractConfiguration = new PrecompiledContractConfiguration(gasCalculator, privacyParameters);
		PrecompileContractRegistry precompileContractRegistry = MainnetPrecompiledContractRegistries.byzantium(precompiledContractConfiguration);
		var messageCallProcessor = new MainnetMessageCallProcessor(evm, precompileContractRegistry);

		txProcessor = new MainnetTransactionProcessor(
				gasCalculator,
				transactionValidator,
				contractCreateProcessor,
				messageCallProcessor,
				false,
				1024,
				Account.DEFAULT_VERSION,
				TransactionPriceCalculator.frontier(),
				CoinbaseFeePriceCalculator.frontier()
		);

	}

	public static void main(String[] args) {

		ContractCallStateTransitionBesuInternal transitionTest = new ContractCallStateTransitionBesuInternal();

		transitionTest.setUp();

		long transactionsCount = 10;


		long start = System.currentTimeMillis();

		for (long i = 0; i < transactionsCount; i++) {
			transitionTest.doStateTransitionBesuInternal();
		}

		long end = System.currentTimeMillis();

		System.out.println(transactionsCount + " transactions passed in " + (end - start) + "ms.");
		System.out.println("1 sec = " + transactionsCount / ((end - start) / 1000) + " transactions.");
	}


	public void doStateTransitionBesuInternal() {

		var evmTx = new Transaction(0,
				gasPrice,
				gasLimit,
				Optional.of(contractAddress),
				value,
				null,
				Bytes.fromHexString("0xab2fdd21f2eeb729e636a8cb783be044acf6b7b1e2c5863735b60d6daae84c366ee87d97"),
				senderAddress,
				Optional.empty());

		var defaultMutableWorld = new DefaultMutableWorldState(accountStateStore);
		var updater = defaultMutableWorld.updater();
		TransactionProcessingResult result = txProcessor.processTransaction(
				stubbedBlockchain(),
				updater,
				stubbedBlockHeader(txnCtx.consensusTime().getEpochSecond()),
				evmTx,
				collectorAddress,
				OperationTracer.NO_TRACING,
				null,
				false);
		System.out.println(result.isSuccessful());
		System.out.println(result.getEstimateGasUsedByTransaction());
		updater.commit();
	}

	private Bytes getBytesForContract() {
		return Bytes.fromHexString("0x608060405234801561001057600080fd5b50600436106100935760003560e01c806362f50e081161006657806362f50e0814610130578063ab2fdd2114610160578063cacdf3651461017c578063d74b0d6914610198578063e5aa3d58146101b657610093565b806320e54b96146100985780632177d2ce146100c8578063449d2c7c146100f85780635fa585ef14610114575b600080fd5b6100b260048036038101906100ad9190610624565b6101d4565b6040516100bf9190610703565b60405180910390f35b6100e260048036038101906100dd91906105bf565b6101f8565b6040516100ef9190610703565b60405180910390f35b610112600480360381019061010d919061057e565b610210565b005b61012e60048036038101906101299190610624565b610267565b005b61014a600480360381019061014591906105e8565b6102fb565b604051610157919061073e565b60405180910390f35b61017a600480360381019061017591906105bf565b61032c565b005b61019660048036038101906101919190610624565b61034e565b005b6101a0610445565b6040516101ad9190610703565b60405180910390f35b6101be61044e565b6040516101cb919061073e565b60405180910390f35b600281815481106101e457600080fd5b906000526020600020016000915090505481565b60016020528060005260406000206000915090505481565b60004360405160200161022391906106e8565b60405160208183030381529060405280519060200120905081600360008381526020019081526020016000209080519060200190610262929190610454565b505050565b60005b818110156102f75760008160001b4360405160200161028a9291906106bc565b60405160208183030381529060405280519060200120905080600160008381526020019081526020016000208190555060028190806001815401808255809150506001900390600052602060002001600090919091909150555080806102ef90610800565b91505061026a565b5050565b6003602052816000526040600020818154811061031757600080fd5b90600052602060002001600091509150505481565b806000819055506004600081548092919061034690610800565b919050555050565b600280549050811115610396576040517f08c379a000000000000000000000000000000000000000000000000000000000815260040161038d9061071e565b60405180910390fd5b60005b818110156104415760008160001b436040516020016103b99291906106bc565b604051602081830303815290604052805190602001209050806001600060028581548110610410577f4e487b7100000000000000000000000000000000000000000000000000000000600052603260045260246000fd5b906000526020600020015481526020019081526020016000208190555050808061043990610800565b915050610399565b5050565b60008054905090565b60045481565b828054828255906000526020600020908101928215610490579160200282015b8281111561048f578251825591602001919060010190610474565b5b50905061049d91906104a1565b5090565b5b808211156104ba5760008160009055506001016104a2565b5090565b60006104d16104cc8461077e565b610759565b905080838252602082019050828560208602820111156104f057600080fd5b60005b8581101561052057816105068882610569565b8452602084019350602083019250506001810190506104f3565b5050509392505050565b600082601f83011261053b57600080fd5b813561054b8482602086016104be565b91505092915050565b6000813590506105638161091b565b92915050565b60008135905061057881610932565b92915050565b60006020828403121561059057600080fd5b600082013567ffffffffffffffff8111156105aa57600080fd5b6105b68482850161052a565b91505092915050565b6000602082840312156105d157600080fd5b60006105df84828501610554565b91505092915050565b600080604083850312156105fb57600080fd5b600061060985828601610554565b925050602061061a85828601610569565b9150509250929050565b60006020828403121561063657600080fd5b600061064484828501610569565b91505092915050565b610656816107bb565b82525050565b61066d610668826107bb565b610849565b82525050565b60006106806023836107aa565b915061068b826108cc565b604082019050919050565b61069f816107c5565b82525050565b6106b66106b1826107c5565b610853565b82525050565b60006106c8828561065c565b6020820191506106d882846106a5565b6020820191508190509392505050565b60006106f482846106a5565b60208201915081905092915050565b6000602082019050610718600083018461064d565b92915050565b6000602082019050818103600083015261073781610673565b9050919050565b60006020820190506107536000830184610696565b92915050565b6000610763610774565b905061076f82826107cf565b919050565b6000604051905090565b600067ffffffffffffffff8211156107995761079861088c565b5b602082029050602081019050919050565b600082825260208201905092915050565b6000819050919050565b6000819050919050565b6107d8826108bb565b810181811067ffffffffffffffff821117156107f7576107f661088c565b5b80604052505050565b600061080b826107c5565b91507fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82141561083e5761083d61085d565b5b600182019050919050565b6000819050919050565b6000819050919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052601160045260246000fd5b7f4e487b7100000000000000000000000000000000000000000000000000000000600052604160045260246000fd5b6000601f19601f8301169050919050565b7f6e6f7420656e6f756768206b6579732068617665206265656e2067656e65726160008201527f7465640000000000000000000000000000000000000000000000000000000000602082015250565b610924816107bb565b811461092f57600080fd5b50565b61093b816107c5565b811461094657600080fd5b5056fea264697066735822122087f307fbb51a37b84c966e524587c00d9a98c7c70e7c87d96a7c58e286c1fc7564736f6c63430008040033");
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
