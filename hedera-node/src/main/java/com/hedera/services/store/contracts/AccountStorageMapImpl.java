package com.hedera.services.store.contracts;

import com.hedera.services.state.merkle.virtual.ContractUint256;
import com.swirlds.fcmap.VFCMap;
import org.apache.tuweni.units.bigints.UInt256;
import org.hyperledger.besu.ethereum.worldstate.AccountStorageMap;

import java.util.Optional;

public class AccountStorageMapImpl implements AccountStorageMap {
	private final VFCMap<ContractUint256, ContractUint256> map;

	public AccountStorageMapImpl(VFCMap<ContractUint256, ContractUint256> map) {
		this.map = map;
	}
	@Override
	public Optional<UInt256> get(UInt256 key) {
		var value = map.get(new ContractUint256(key.toBigInteger()));
		return value == null ? Optional.empty() : Optional.of(UInt256.valueOf(value.getValue()));
	}

	@Override
	public void put(UInt256 key, UInt256 value) {
		map.put(new ContractUint256(key.toBigInteger()), new ContractUint256(value.toBigInteger()));
	}

	@Override
	public void remove(UInt256 key) {
		map.remove(new ContractUint256(key.toBigInteger()));
	}
}
