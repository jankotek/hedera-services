package com.hedera.services.store.contracts;/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2020 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.virtual.VirtualMap;
import com.hedera.services.state.merkle.virtual.persistence.mmap.MemMapDataSource;
import com.hedera.services.state.merkle.virtual.persistence.mmap.VirtualMapDataStore;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Account;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Wei;
import org.hyperledger.besu.ethereum.worldstate.AccountStateStore;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;

import java.util.HashMap;
import java.util.Map;

public class ContractsStore implements AccountStateStore {
	private final VirtualMapDataStore dataStore;
	private final HederaLedger ledger;
	private Map<AccountID, VirtualMap> maps;
	private final BlobStorageSource blobStorageSource;
	private Map<AccountID, MerkleAccount> provisionalAccounts;

	public ContractsStore(
			BlobStorageSource blobStorageSource,
			VirtualMapDataStore dataStore, HederaLedger ledger) {
		this.ledger = ledger;
		this.dataStore = dataStore;
		this.blobStorageSource = blobStorageSource;
		this.maps = new HashMap<>();
		this.provisionalAccounts = new HashMap<>();
	}
	// The EVM is executing this call everytime it needs to access a contract/address. F.e getting recipient address multiple times during 1 contract executions
	@Override
	public Account get(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			var account = ledger.get(accId);
			if (account.isSmartContract()) {
				var code = Bytes.fromHexString("608060405234801561001057600080fd5b50610971806100206000396000f3fe608060405234801561001057600080fd5b50600436106100885760003560e01c806362f50e081161005b57806362f50e0814610125578063ab2fdd2114610155578063cacdf36514610171578063d74b0d691461018d57610088565b806320e54b961461008d5780632177d2ce146100bd578063449d2c7c146100ed5780635fa585ef14610109575b600080fd5b6100a760048036038101906100a291906105cf565b6101ab565b6040516100b491906106b2565b60405180910390f35b6100d760048036038101906100d29190610562565b6101cf565b6040516100e491906106b2565b60405180910390f35b61010760048036038101906101029190610519565b6101e7565b005b610123600480360381019061011e91906105cf565b61023e565b005b61013f600480360381019061013a919061058f565b6102d2565b60405161014c91906106ed565b60405180910390f35b61016f600480360381019061016a9190610562565b610303565b005b61018b600480360381019061018691906105cf565b61030d565b005b6101956103de565b6040516101a291906106b2565b60405180910390f35b600281815481106101bb57600080fd5b906000526020600020016000915090505481565b60016020528060005260406000206000915090505481565b6000436040516020016101fa9190610697565b604051602081830303815290604052805190602001209050816003600083815260200190815260200160002090805190602001906102399291906103e7565b505050565b60005b818110156102ce5760008160001b4360405160200161026192919061066b565b60405160208183030381529060405280519060200120905080600160008381526020019081526020016000208190555060028190806001815401808255809150506001900390600052602060002001600090919091909150555080806102c6906107af565b915050610241565b5050565b600360205281600052604060002081815481106102ee57600080fd5b90600052602060002001600091509150505481565b8060008190555050565b600280549050811115610355576040517f08c379a000000000000000000000000000000000000000000000000000000000815260040161034c906106cd565b60405180910390fd5b60005b818110156103da5760008160001b4360405160200161037892919061066b565b6040516020818303038152906040528051906020012090508060016000600285815481106103a9576103a861083b565b5b90600052602060002001548152602001908152602001600020819055505080806103d2906107af565b915050610358565b5050565b60008054905090565b828054828255906000526020600020908101928215610423579160200282015b82811115610422578251825591602001919060010190610407565b5b5090506104309190610434565b5090565b5b8082111561044d576000816000905550600101610435565b5090565b600061046461045f8461072d565b610708565b905080838252602082019050828560208602820111156104875761048661089e565b5b60005b858110156104b7578161049d8882610504565b84526020840193506020830192505060018101905061048a565b5050509392505050565b600082601f8301126104d6576104d5610899565b5b81356104e6848260208601610451565b91505092915050565b6000813590506104fe8161090d565b92915050565b60008135905061051381610924565b92915050565b60006020828403121561052f5761052e6108a8565b5b600082013567ffffffffffffffff81111561054d5761054c6108a3565b5b610559848285016104c1565b91505092915050565b600060208284031215610578576105776108a8565b5b6000610586848285016104ef565b91505092915050565b600080604083850312156105a6576105a56108a8565b5b60006105b4858286016104ef565b92505060206105c585828601610504565b9150509250929050565b6000602082840312156105e5576105e46108a8565b5b60006105f384828501610504565b91505092915050565b6106058161076a565b82525050565b61061c6106178261076a565b6107f8565b82525050565b600061062f602383610759565b915061063a826108be565b604082019050919050565b61064e81610774565b82525050565b61066561066082610774565b610802565b82525050565b6000610677828561060b565b6020820191506106878284610654565b6020820191508190509392505050565b60006106a38284610654565b60208201915081905092915050565b60006020820190506106c760008301846105fc565b92915050565b600060208201905081810360008301526106e681610622565b9050919050565b60006020820190506107026000830184610645565b92915050565b6000610712610723565b905061071e828261077e565b919050565b6000604051905090565b600067ffffffffffffffff8211156107485761074761086a565b5b602082029050602081019050919050565b600082825260208201905092915050565b6000819050919050565b6000819050919050565b610787826108ad565b810181811067ffffffffffffffff821117156107a6576107a561086a565b5b80604052505050565b60006107ba82610774565b91507fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff8214156107ed576107ec61080c565b5b600182019050919050565b6000819050919050565b6000819050919050565b7f4e487b7100000000000000000000000000000000000000000000000000000000600052601160045260246000fd5b7f4e487b7100000000000000000000000000000000000000000000000000000000600052603260045260246000fd5b7f4e487b7100000000000000000000000000000000000000000000000000000000600052604160045260246000fd5b600080fd5b600080fd5b600080fd5b600080fd5b6000601f19601f8301169050919050565b7f6e6f7420656e6f756768206b6579732068617665206265656e2067656e65726160008201527f7465640000000000000000000000000000000000000000000000000000000000602082015250565b6109168161076a565b811461092157600080fd5b50565b61092d81610774565b811461093857600080fd5b5056fea26469706673582212203447aa70de4e4866c0758cd1440d266765ba6cec7e3f8648c79de2ad614b62cb64736f6c63430008050033");
				return new EvmAccountImpl(address, Wei.of(account.getBalance()), code);
			}
			// TODO what we do with nonces?
			return new EvmAccountImpl(address, Wei.of(account.getBalance()));
		}

		return null;
	}

	@Override
	public AccountStorageMap newStorageMap(Address address) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		var map = ledger.get(accId).map();
		if (!map.isInitialised()) {
			map.init(
					new MemMapDataSource(dataStore, new com.hedera.services.state.merkle.virtual.Account(accId.getShardNum(), accId.getRealmNum(), accId.getAccountNum()))
			);
		}

		maps.put(accId, map);
		return map;
	}

	@Override
	public void put(Address address, long nonce, Wei balance) {
		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
		if (ledger.exists(accId)) {
			final var account = ledger.get(accId);
			ledger.adjustBalance(accId, balance.toLong() - account.getBalance());
			return;
		}

		System.out.printf("invalid address found: %s", accId);
	}

	@Override
	public void putCode(Address address, Bytes code) {
//		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
//		if (maps.get(accId) == null) {
//			var map = ledger.get(accId).map();
//			map.init(new MemMapDataSource(dataStore, new com.hedera.services.state.merkle.virtual.Account(accId.getShardNum(), accId.getRealmNum(), accId.getAccountNum())));
//			maps.put(accId, map);
//		}
//		var map = maps.get(accId);
		// TODO:
	}

	@Override
	public Bytes getCode(Address address) {
		// TODO this must be implemented
//		final var accId = EntityIdUtils.accountParsedFromSolidityAddress(address.toArray());
//		if (ledger.exists(accId)) {
//			var account = ledger.get(accId);
//			if (account.isSmartContract()) {
//				return Bytes.fromHexString("608060405234801561001057600080fd5b50610662806100206000396000f3fe60806040526004361061008e576000357c0100000000000000000000000000000000000000000000000000000000900463ffffffff16806320e54b96146100935780632177d2ce146100e2578063449d2c7c146101315780635fa585ef146101f657806362f50e0814610231578063ab2fdd211461028a578063cacdf365146102c5578063d74b0d6914610300575b600080fd5b34801561009f57600080fd5b506100cc600480360360208110156100b657600080fd5b810190808035906020019092919050505061032b565b6040518082815260200191505060405180910390f35b3480156100ee57600080fd5b5061011b6004803603602081101561010557600080fd5b810190808035906020019092919050505061034e565b6040518082815260200191505060405180910390f35b34801561013d57600080fd5b506101f46004803603602081101561015457600080fd5b810190808035906020019064010000000081111561017157600080fd5b82018360208201111561018357600080fd5b803590602001918460208302840111640100000000831117156101a557600080fd5b919080806020026020016040519081016040528093929190818152602001838360200280828437600081840152601f19601f820116905080830192505050505050509192919290505050610366565b005b34801561020257600080fd5b5061022f6004803603602081101561021957600080fd5b81019080803590602001909291905050506103bd565b005b34801561023d57600080fd5b506102746004803603604081101561025457600080fd5b810190808035906020019092919080359060200190929190505050610457565b6040518082815260200191505060405180910390f35b34801561029657600080fd5b506102c3600480360360208110156102ad57600080fd5b8101908080359060200190929190505050610487565b005b3480156102d157600080fd5b506102fe600480360360208110156102e857600080fd5b8101908080359060200190929190505050610491565b005b34801561030c57600080fd5b506103156105bb565b6040518082815260200191505060405180910390f35b60028181548110151561033a57fe5b906000526020600020016000915090505481565b60016020528060005260406000206000915090505481565b60004360405160200180828152602001915050604051602081830303815290604052805190602001209050816003600083815260200190815260200160002090805190602001906103b89291906105c4565b505050565b60008090505b818110156104535760008160010243604051602001808381526020018281526020019250505060405160208183030381529060405280519060200120905080600160008381526020019081526020016000208190555060028190806001815401808255809150509060018203906000526020600020016000909192909190915055505080806001019150506103c3565b5050565b60036020528160005260406000208181548110151561047257fe5b90600052602060002001600091509150505481565b8060008190555050565b6002805490508111151515610534576040517f08c379a00000000000000000000000000000000000000000000000000000000081526004018080602001828103825260238152602001807f6e6f7420656e6f756768206b6579732068617665206265656e2067656e65726181526020017f746564000000000000000000000000000000000000000000000000000000000081525060400191505060405180910390fd5b60008090505b818110156105b757600081600102436040516020018083815260200182815260200192505050604051602081830303815290604052805190602001209050806001600060028581548110151561058c57fe5b906000526020600020015481526020019081526020016000208190555050808060010191505061053a565b5050565b60008054905090565b828054828255906000526020600020908101928215610600579160200282015b828111156105ff5782518255916020019190600101906105e4565b5b50905061060d9190610611565b5090565b61063391905b8082111561062f576000816000905550600101610617565b5090565b9056fea165627a7a72305820c38a051a662d5f16ae948a987c28f3e06c4d35aece608069324931e83cd750e20029");
//			}
//		}
		return Bytes.EMPTY;
	}

	@Override
	public void remove(Address address) {
		// TODO: set somewhere provisionally
	}

	@Override
	public void clearStorage(Address address) {
		// TODO: set somewhere provisionally
	}

	@Override
	public void commit() {
		maps.forEach((key, value) -> value.commit());
		// TODO: commit the provisional changes
	}
}