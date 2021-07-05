package com.hedera.services.bdd.spec.transactions.crypto;

/*-
 * ‌
 * Hedera Services Test Clients
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

import com.google.common.base.MoreObjects;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.fees.AdapterUtils;
import com.hedera.services.bdd.spec.transactions.HapiTxnOp;
import com.hedera.services.bdd.spec.transactions.TxnUtils;
import com.hedera.services.bdd.spec.transactions.token.TokenMovement;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.hedera.services.usage.BaseTransactionMeta;
import com.hedera.services.usage.crypto.CryptoTransferMeta;
import com.hedera.services.usage.state.UsageAccumulator;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoTransferTransactionBody;
import com.hederahashgraph.api.proto.java.FeeData;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenTransferList;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionResponse;
import com.hederahashgraph.api.proto.java.TransferList;
import com.hederahashgraph.fee.FeeObject;
import com.hederahashgraph.fee.SigValueObj;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Stream;

import static com.hedera.services.bdd.spec.transactions.TxnUtils.asId;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.suFrom;
import static com.hedera.services.bdd.spec.transactions.token.TokenMovement.HBAR_SENTINEL_TOKEN_ID;
import static java.util.stream.Collectors.collectingAndThen;
import static java.util.stream.Collectors.flatMapping;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.summingLong;
import static java.util.stream.Collectors.toList;

public class HapiCryptoTransfer extends HapiTxnOp<HapiCryptoTransfer> {
	static final Logger log = LogManager.getLogger(HapiCryptoTransfer.class);

	private static final List<TokenMovement> MISSING_TOKEN_AWARE_PROVIDERS = null;
	private static final Function<HapiApiSpec, TransferList> MISSING_HBAR_ONLY_PROVIDER = null;

	private boolean logResolvedStatus = false;
	private boolean breakNetZeroTokenChangeInvariant = false;

	private List<TokenMovement> tokenAwareProviders = MISSING_TOKEN_AWARE_PROVIDERS;
	private Function<HapiApiSpec, TransferList> hbarOnlyProvider = MISSING_HBAR_ONLY_PROVIDER;
	private Optional<String> tokenWithEmptyTransferAmounts = Optional.empty();
	private Optional<Pair<String[], Long>> appendedFromTo = Optional.empty();
	private Optional<AtomicReference<FeeObject>> feesObserver = Optional.empty();

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.CryptoTransfer;
	}

	public HapiCryptoTransfer showingResolvedStatus() {
		logResolvedStatus = true;
		return this;
	}

	public HapiCryptoTransfer breakingNetZeroInvariant() {
		breakNetZeroTokenChangeInvariant = true;
		return this;
	}

	public HapiCryptoTransfer exposingFeesTo(AtomicReference<FeeObject> obs) {
		feesObserver = Optional.of(obs);
		return this;
	}

	private static Collector<TransferList, ?, TransferList> transferCollector(
			BinaryOperator<List<AccountAmount>> reducer
	) {
		return collectingAndThen(
				reducing(
						Collections.emptyList(),
						TransferList::getAccountAmountsList,
						reducer),
				aList -> TransferList.newBuilder().addAllAccountAmounts(aList).build());
	}

	private final static BinaryOperator<List<AccountAmount>> accountMerge = (a, b) ->
			Stream.of(a, b).flatMap(List::stream).collect(collectingAndThen(
					groupingBy(AccountAmount::getAccountID, mapping(AccountAmount::getAmount, toList())),
					aMap -> aMap.entrySet()
							.stream()
							.map(entry ->
									AccountAmount.newBuilder()
											.setAccountID(entry.getKey())
											.setAmount(entry.getValue().stream().mapToLong(l -> (long) l).sum())
											.build())
							.collect(toList())));
	private final static Collector<TransferList, ?, TransferList> mergingAccounts = transferCollector(accountMerge);

	@SafeVarargs
	public HapiCryptoTransfer(Function<HapiApiSpec, TransferList>... providers) {
		if (providers.length == 0) {
			hbarOnlyProvider = ignore -> TransferList.getDefaultInstance();
		} else if (providers.length == 1) {
			hbarOnlyProvider = providers[0];
		} else {
			this.hbarOnlyProvider = spec -> Stream.of(providers).map(p -> p.apply(spec)).collect(mergingAccounts);
		}
	}

	public HapiCryptoTransfer(TokenMovement... sources) {
		this.tokenAwareProviders = List.of(sources);
	}


	public HapiCryptoTransfer withEmptyTokenTransfers(String token) {
		tokenWithEmptyTransferAmounts = Optional.of(token);
		return this;
	}

	public HapiCryptoTransfer appendingTokenFromTo(String token, String from, String to, long amount) {
		appendedFromTo = Optional.of(Pair.of(new String[] { token, from, to }, amount));
		return this;
	}

	@Override
	protected Function<HapiApiSpec, List<Key>> variableDefaultSigners() {
		if (hbarOnlyProvider != MISSING_HBAR_ONLY_PROVIDER) {
			return hbarOnlyVariableDefaultSigners();
		} else {
			return tokenAwareVariableDefaultSigners();
		}
	}

	public static Function<HapiApiSpec, TransferList> tinyBarsFromTo(String from, String to, long amount) {
		return tinyBarsFromTo(from, to, ignore -> amount);
	}

	public static Function<HapiApiSpec, TransferList> tinyBarsFromTo(
			String from, String to, Function<HapiApiSpec, Long> amountFn) {
		return spec -> {
			long amount = amountFn.apply(spec);
			AccountID toAccount = asId(to, spec);
			AccountID fromAccount = asId(from, spec);
			return TransferList.newBuilder()
					.addAllAccountAmounts(Arrays.asList(
							AccountAmount.newBuilder().setAccountID(toAccount).setAmount(amount).build(),
							AccountAmount.newBuilder().setAccountID(fromAccount).setAmount(
									-1L * amount).build())).build();
		};
	}

	public static Function<HapiApiSpec, TransferList> tinyBarsFromToWithInvalidAmounts(String from, String to,
			long amount) {
		return tinyBarsFromToWithInvalidAmounts(from, to, ignore -> amount);
	}

	public static Function<HapiApiSpec, TransferList> tinyBarsFromToWithInvalidAmounts(
			String from, String to, Function<HapiApiSpec, Long> amountFn) {
		return spec -> {
			long amount = amountFn.apply(spec);
			AccountID toAccount = asId(to, spec);
			AccountID fromAccount = asId(from, spec);
			return TransferList.newBuilder()
					.addAllAccountAmounts(Arrays.asList(
							AccountAmount.newBuilder().setAccountID(toAccount).setAmount(amount).build(),
							AccountAmount.newBuilder().setAccountID(fromAccount).setAmount(
									-1L * amount + 1L).build())).build();
		};
	}

	@Override
	protected Consumer<TransactionBody.Builder> opBodyDef(HapiApiSpec spec) throws Throwable {
		CryptoTransferTransactionBody opBody = spec.txns()
				.<CryptoTransferTransactionBody, CryptoTransferTransactionBody.Builder>body(
						CryptoTransferTransactionBody.class, b -> {
							if (hbarOnlyProvider != MISSING_HBAR_ONLY_PROVIDER) {
								b.setTransfers(hbarOnlyProvider.apply(spec));
							} else {
								var xfers = transfersFor(spec);
								for (TokenTransferList scopedXfers : xfers) {
									if (scopedXfers.getToken() == HBAR_SENTINEL_TOKEN_ID) {
										b.setTransfers(TransferList.newBuilder()
												.addAllAccountAmounts(scopedXfers.getTransfersList())
												.build());
									} else {
										b.addTokenTransfers(scopedXfers);
									}
								}
								misconfigureIfRequested(b, spec);
							}
						}
				);
		return builder -> builder.setCryptoTransfer(opBody);
	}

	private void misconfigureIfRequested(CryptoTransferTransactionBody.Builder b, HapiApiSpec spec) {
		if (tokenWithEmptyTransferAmounts.isPresent()) {
			var empty = tokenWithEmptyTransferAmounts.get();
			var emptyToken = TxnUtils.asTokenId(empty, spec);
			var emptyList = TokenTransferList.newBuilder()
					.setToken(emptyToken);
			b.addTokenTransfers(emptyList);
		}
		if (appendedFromTo.isPresent()) {
			var extra = appendedFromTo.get();
			var involved = extra.getLeft();
			var token = TxnUtils.asTokenId(involved[0], spec);
			var sender = TxnUtils.asId(involved[1], spec);
			var receiver = TxnUtils.asId(involved[2], spec);
			var amount = extra.getRight();
			var appendList = TokenTransferList.newBuilder()
					.setToken(token)
					.addTransfers(AccountAmount.newBuilder()
							.setAccountID(sender)
							.setAmount(-amount))
					.addTransfers(AccountAmount.newBuilder()
							.setAccountID(receiver)
							.setAmount(+amount));
			b.addTokenTransfers(appendList);
		}
		if (breakNetZeroTokenChangeInvariant && b.getTokenTransfersCount() > 0) {
			for (int i = 0, n = b.getTokenTransfersCount(); i < n; i++) {
				var changesHere = b.getTokenTransfersBuilder(i);
				if (changesHere.getTransfersCount() > 0) {
					var mutated = changesHere.getTransfersBuilder(0);
					mutated.setAmount(mutated.getAmount() + 1_234);
					b.setTokenTransfers(i, changesHere);
					break;
				}
			}
		}
	}

	@Override
	protected long feeFor(HapiApiSpec spec, Transaction txn, int numPayerKeys) throws Throwable {
		if (feesObserver.isPresent()) {
			return spec.fees().forActivityBasedOpWithDetails(
					HederaFunctionality.CryptoTransfer,
					(_txn, _svo) -> usageEstimate(_txn, _svo, spec.fees().tokenTransferUsageMultiplier()),
					txn,
					numPayerKeys,
					feesObserver.get());
		}
		return spec.fees().forActivityBasedOp(
				HederaFunctionality.CryptoTransfer,
				(_txn, _svo) -> usageEstimate(_txn, _svo, spec.fees().tokenTransferUsageMultiplier()),
				txn,
				numPayerKeys);
	}

	public static FeeData usageEstimate(TransactionBody txn, SigValueObj svo, int multiplier) {
		final var op = txn.getCryptoTransfer();

		final var baseMeta = new BaseTransactionMeta(
				txn.getMemoBytes().size(),
				op.getTransfers().getAccountAmountsCount());

		int numTokensInvolved = 0, numTokenTransfers = 0;
		for (var tokenTransfers : op.getTokenTransfersList()) {
			numTokensInvolved++;
			numTokenTransfers += tokenTransfers.getTransfersCount();
		}
		final var xferMeta = new CryptoTransferMeta(multiplier, numTokensInvolved, numTokenTransfers);

		final var accumulator = new UsageAccumulator();
		cryptoOpsUsage.cryptoTransferUsage(suFrom(svo), xferMeta, baseMeta, accumulator);

		return AdapterUtils.feeDataFrom(accumulator);
	}

	@Override
	protected Function<Transaction, TransactionResponse> callToUse(HapiApiSpec spec) {
		return spec.clients().getCryptoSvcStub(targetNodeFor(spec), useTls)::cryptoTransfer;
	}

	@Override
	protected HapiCryptoTransfer self() {
		return this;
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		MoreObjects.ToStringHelper helper = super.toStringHelper();
		if (txnSubmitted != null) {
			try {
				TransactionBody txn = CommonUtils.extractTransactionBody(txnSubmitted);
				helper.add(
						"transfers",
						TxnUtils.readableTransferList(txn.getCryptoTransfer().getTransfers()));
				helper.add(
						"tokenTransfers",
						TxnUtils.readableTokenTransfers(txn.getCryptoTransfer().getTokenTransfersList()));
			} catch (Exception ignore) {
			}
		}
		return helper;
	}

	private Function<HapiApiSpec, List<Key>> tokenAwareVariableDefaultSigners() {
		return spec -> {
			Set<Key> partyKeys = new HashSet<>();
			Map<String, Long> partyInvolvements = tokenAwareProviders.stream()
					.map(TokenMovement::generallyInvolved)
					.flatMap(List::stream)
					.collect(groupingBy(
							Map.Entry::getKey,
							summingLong(Map.Entry<String, Long>::getValue)));
			partyInvolvements.forEach((account, value) -> {
				int divider = account.indexOf("|");
				var key = account.substring(divider + 1);
				if (value < 0 || spec.registry().isSigRequired(key)) {
					partyKeys.add(spec.registry().getKey(key));
				}
			});
			return new ArrayList<>(partyKeys);
		};
	}

	private Function<HapiApiSpec, List<Key>> hbarOnlyVariableDefaultSigners() {
		return spec -> {
			List<Key> partyKeys = new ArrayList<>();
			TransferList transfers = hbarOnlyProvider.apply(spec);
			transfers.getAccountAmountsList().stream().forEach(accountAmount -> {
				String account = spec.registry().getAccountIdName(accountAmount.getAccountID());
				boolean isPayer = (accountAmount.getAmount() < 0L);
				if (isPayer || spec.registry().isSigRequired(account)) {
					partyKeys.add(spec.registry().getKey(account));
				}
			});
			return partyKeys;
		};
	}

	private List<TokenTransferList> transfersFor(HapiApiSpec spec) {
		Map<TokenID, List<AccountAmount>> aggregated = tokenAwareProviders.stream()
				.map(p -> p.specializedFor(spec))
				.collect(groupingBy(
						TokenTransferList::getToken,
						flatMapping(xfers -> xfers.getTransfersList().stream(), toList())));
		return aggregated.entrySet().stream()
				.map(entry -> TokenTransferList.newBuilder()
						.setToken(entry.getKey())
						.addAllTransfers(entry.getValue())
						.build())
				.collect(toList());
	}

	@Override
	protected void updateStateOf(HapiApiSpec spec) throws Throwable {
		if (logResolvedStatus) {
			log.info("Resolved to {}", actualStatus);
		}
	}
}
