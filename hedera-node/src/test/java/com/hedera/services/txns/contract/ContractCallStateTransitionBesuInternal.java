package com.hedera.services.txns.contract;

import com.hedera.services.context.TransactionContext;
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
    Wei value = Wei.of(1);

    private final AccountID sender = asAccount(new EntityId(0, 0, 1001));
    private final AccountID collector = asAccount(new EntityId(0, 0, 98));
    private final AccountID contract = asAccount(new EntityId(0, 0, 1002));

    private final Address senderAddress = Address.fromHexString(asSolidityAddressHex(sender));
    private final Address collectorAddress = Address.fromHexString(asSolidityAddressHex(collector));
    private final Address contractAddress = Address.fromHexString(asSolidityAddressHex(contract));

    public void setUp() {

        Instant consensusTime = Instant.now();

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

        long transactionsCount = 5000;


        long start = System.currentTimeMillis();

        for(long i = 0; i<transactionsCount; i++) {
            transitionTest.doStateTransitionBesuInternal();
        }

        long end = System.currentTimeMillis();

        System.out.println(transactionsCount + " transactions passed in " + (end - start) + "ms." );
        System.out.println("1 sec = " + transactionsCount / ((end-start) /1000) + " transactions.");
    }


    public void doStateTransitionBesuInternal() {

        var evmTx = new Transaction(0,
                gasPrice,
                gasLimit,
                Optional.of(contractAddress),
                value,
                null,
                Bytes.of("0xf35fd400f2eeb729e636a8cb783be044acf6b7b1e2c5863735b60d6daae84c366ee87d97".getBytes()),
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
        updater.commit();

    }

    private Bytes getBytesForContract() {
        return Bytes.of(("0x608060405234801561001057600080fd5b506004361061009e5760003560e01c806362f50e08116" +
                "1006657806362f50e0814610159578063cacdf36514610189578063d74b0d69146101a5578063e2420ef214610" +
                "1c3578063f35fd400146101df5761009e565b806320e54b96146100a35780632177d2ce146100d3578063449d2" +
                "c7c146101035780635fa585ef1461011f57806361bc221a1461013b575b600080fd5b6100bd600480360381019" +
                "06100b89190610706565b6101fb565b6040516100ca91906107e5565b60405180910390f35b6100ed600480360" +
                "38101906100e891906106a1565b61021f565b6040516100fa91906107e5565b60405180910390f35b61011d600" +
                "48036038101906101189190610660565b610237565b005b61013960048036038101906101349190610706565b6" +
                "102a6565b005b610143610352565b6040516101509190610820565b60405180910390f35b61017360048036038" +
                "1019061016e91906106ca565b610358565b6040516101809190610820565b60405180910390f35b6101a360048" +
                "0360381019061019e9190610706565b610389565b005b6101ad610498565b6040516101ba91906107e5565b604" +
                "05180910390f35b6101dd60048036038101906101d89190610706565b6104a1565b005b6101f96004803603810" +
                "1906101f491906106a1565b610514565b005b6002818154811061020b57600080fd5b906000526020600020016" +
                "000915090505481565b60016020528060005260406000206000915090505481565b60004360405160200161024" +
                "a91906107ca565b604051602081830303815290604052805190602001209050816003600083815260200190815" +
                "26020016000209080519060200190610289929190610536565b506004600081548092919061029d906108e2565" +
                "b91905055505050565b60005b818110156103365760008160001b436040516020016102c992919061079e565b6" +
                "040516020818303038152906040528051906020012090508060016000838152602001908152602001600020819" +
                "055506002819080600181540180825580915050600190039060005260206000200160009091909190915055508" +
                "08061032e906108e2565b9150506102a9565b506004600081548092919061034a906108e2565b9190505550505" +
                "65b60045481565b6003602052816000526040600020818154811061037457600080fd5b9060005260206000200" +
                "1600091509150505481565b6002805490508111156103d1576040517f08c379a00000000000000000000000000" +
                "000000000000000000000000000000081526004016103c890610800565b60405180910390fd5b60005b8181101" +
                "561047c5760008160001b436040516020016103f492919061079e565b604051602081830303815290604052805" +
                "19060200120905080600160006002858154811061044b577f4e487b71000000000000000000000000000000000" +
                "00000000000000000000000600052603260045260246000fd5b906000526020600020015481526020019081526" +
                "0200160002081905550508080610474906108e2565b9150506103d4565b5060046000815480929190610490906" +
                "108e2565b919050555050565b60008054905090565b8060001b426040516020016104b792919061079e565b604" +
                "0516020818303038152906040528051906020012060008190555060005b818110156104f857600080549050508" +
                "0806104f0906108e2565b9150506104d6565b506004600081548092919061050c906108e2565b9190505550505" +
                "65b806000819055506004600081548092919061052e906108e2565b919050555050565b8280548282559060005" +
                "26020600020908101928215610572579160200282015b828111156105715782518255916020019190600101906" +
                "10556565b5b50905061057f9190610583565b5090565b5b8082111561059c57600081600090555060010161058" +
                "4565b5090565b60006105b36105ae84610860565b61083b565b905080838252602082019050828560208602820" +
                "111156105d257600080fd5b60005b8581101561060257816105e8888261064b565b84526020840193506020830" +
                "19250506001810190506105d5565b5050509392505050565b600082601f83011261061d57600080fd5b8135610" +
                "62d8482602086016105a0565b91505092915050565b600081359050610645816109fd565b92915050565b60008" +
                "135905061065a81610a14565b92915050565b60006020828403121561067257600080fd5b600082013567fffff" +
                "fffffffffff81111561068c57600080fd5b6106988482850161060c565b91505092915050565b6000602082840" +
                "312156106b357600080fd5b60006106c184828501610636565b91505092915050565b600080604083850312156" +
                "106dd57600080fd5b60006106eb85828601610636565b92505060206106fc8582860161064b565b91505092509" +
                "29050565b60006020828403121561071857600080fd5b60006107268482850161064b565b91505092915050565" +
                "b6107388161089d565b82525050565b61074f61074a8261089d565b61092b565b82525050565b6000610762602" +
                "38361088c565b915061076d826109ae565b604082019050919050565b610781816108a7565b82525050565b610" +
                "798610793826108a7565b610935565b82525050565b60006107aa828561073e565b6020820191506107ba82846" +
                "10787565b6020820191508190509392505050565b60006107d68284610787565b6020820191508190509291505" +
                "0565b60006020820190506107fa600083018461072f565b92915050565b6000602082019050818103600083015" +
                "261081981610755565b9050919050565b60006020820190506108356000830184610778565b92915050565b600" +
                "0610845610856565b905061085182826108b1565b919050565b6000604051905090565b600067fffffffffffff" +
                "fff82111561087b5761087a61096e565b5b602082029050602081019050919050565b600082825260208201905" +
                "092915050565b6000819050919050565b6000819050919050565b6108ba8261099d565b810181811067fffffff" +
                "fffffffff821117156108d9576108d861096e565b5b80604052505050565b60006108ed826108a7565b91507ff" +
                "fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8214156109205761091f61093f5" +
                "65b5b600182019050919050565b6000819050919050565b6000819050919050565b7f4e487b710000000000000" +
                "0000000000000000000000000000000000000000000600052601160045260246000fd5b7f4e487b71000000000" +
                "00000000000000000000000000000000000000000000000600052604160045260246000fd5b6000601f19601f8" +
                "301169050919050565b7f6e6f7420656e6f756768206b6579732068617665206265656e2067656e65726160008" +
                "201527f7465640000000000000000000000000000000000000000000000000000000000602082015250565b610" +
                "a068161089d565b8114610a1157600080fd5b50565b610a1d816108a7565b8114610a2857600080fd5b5056fea" +
                "264697066735822122069123a8fe20d2b0a0e6390f0b52edddb121bb056b06d271c9da534d36844496a64736f6" +
                "c63430008040033").getBytes());
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
