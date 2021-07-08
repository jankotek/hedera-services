package com.hedera.services.store.contracts.precompiles;

import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.store.models.Id;
import com.hedera.services.utils.EntityIdUtils;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.ethereum.core.Address;
import org.hyperledger.besu.ethereum.core.Gas;
import org.hyperledger.besu.ethereum.mainnet.AbstractPrecompiledContract;
import org.hyperledger.besu.ethereum.vm.GasCalculator;
import org.hyperledger.besu.ethereum.vm.MessageFrame;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public class HTSTransferPrecompileContract extends AbstractPrecompiledContract {
	private final HederaLedger ledger;

	public HTSTransferPrecompileContract(
			final HederaLedger ledger,
			final GasCalculator gasCalculator) {
		super("HTS_TRANSFER", gasCalculator);
		this.ledger = ledger;
	}

	@Override
	public Gas gasRequirement(Bytes input) {
		return Gas.of(10_000);
	}

	@Override
	public Bytes compute(Bytes input, MessageFrame messageFrame) {
		final var fromAddress = messageFrame.getSenderAddress();
		final Bytes tokenAddress = Address.wrap(input.slice(0, 20));
		final Bytes toAddress = Address.wrap(input.slice(20, 20));
		final BigInteger amount = input.slice(40, 32).toBigInteger();

		final var from = EntityIdUtils.accountParsedFromSolidityAddress(fromAddress.toArray());
		final var token = EntityIdUtils.tokenParsedFromSolidityAddress(tokenAddress.toArray());
		final var to = EntityIdUtils.accountParsedFromSolidityAddress(toAddress.toArray());

		final List<BalanceChange> changes = new ArrayList<>();
		changes.add(
				BalanceChange.changingFtUnits(
						Id.fromGrpcToken(token),
						token,
						AccountAmount.newBuilder().setAccountID(from).setAmount(-amount.longValue()).build()
				));
		changes.add(
				BalanceChange.changingFtUnits(
						Id.fromGrpcToken(token),
						token,
						AccountAmount.newBuilder().setAccountID(to).setAmount(amount.longValue()).build()
				));

		final var outcome = ledger.doZeroSum(changes);
		if (outcome != ResponseCodeEnum.OK) {
			return null;
		}

		return Bytes.EMPTY;
	}

	public static org.hyperledger.besu.ethereum.core.Address precompiledAddress() {
		final byte[] address = new byte[20];
		address[address.length - 1] = 0x13;

		return org.hyperledger.besu.ethereum.core.Address.wrap(Bytes.wrap(address));
	}
}
