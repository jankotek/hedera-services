package com.hedera.services.context;

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

import com.hedera.services.ServicesState;
import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.EntityNumbers;
import com.hedera.services.config.FileNumbers;
import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.context.domain.trackers.ConsensusStatusCounts;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.context.properties.SemanticVersions;
import com.hedera.services.context.properties.StandardizedPropertySources;
import com.hedera.services.contracts.execution.SolidityLifecycle;
import com.hedera.services.contracts.execution.TxnAwareSoliditySigsVerifier;
import com.hedera.services.contracts.persistence.BlobStoragePersistence;
import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.contracts.sources.LedgerAccountsSource;
import com.hedera.services.fees.AwareHbarCentExchange;
import com.hedera.services.fees.StandardExemptions;
import com.hedera.services.fees.TxnRateFeeMultiplierSource;
import com.hedera.services.fees.calculation.AwareFcfsUsagePrices;
import com.hedera.services.fees.calculation.UsageBasedFeeCalculator;
import com.hedera.services.fees.calculation.utils.AccessorBasedUsages;
import com.hedera.services.fees.calculation.utils.PricedUsageCalculator;
import com.hedera.services.fees.charging.FeeChargingPolicy;
import com.hedera.services.fees.charging.NarratedLedgerCharging;
import com.hedera.services.fees.charging.TxnChargingPolicyAgent;
import com.hedera.services.files.HFileMeta;
import com.hedera.services.files.SysFileCallbacks;
import com.hedera.services.files.TieredHederaFs;
import com.hedera.services.files.interceptors.FeeSchedulesManager;
import com.hedera.services.files.interceptors.ThrottleDefsManager;
import com.hedera.services.files.interceptors.TxnAwareRatesManager;
import com.hedera.services.files.interceptors.ValidatingCallbackInterceptor;
import com.hedera.services.files.store.FcBlobsBytesStore;
import com.hedera.services.grpc.NettyGrpcServerManager;
import com.hedera.services.grpc.controllers.ConsensusController;
import com.hedera.services.grpc.controllers.ContractController;
import com.hedera.services.grpc.controllers.CryptoController;
import com.hedera.services.grpc.controllers.FileController;
import com.hedera.services.grpc.controllers.FreezeController;
import com.hedera.services.grpc.controllers.NetworkController;
import com.hedera.services.grpc.controllers.ScheduleController;
import com.hedera.services.grpc.controllers.TokenController;
import com.hedera.services.grpc.marshalling.ImpliedTransfersMarshal;
import com.hedera.services.keys.CharacteristicsFactory;
import com.hedera.services.keys.InHandleActivationHelper;
import com.hedera.services.keys.LegacyEd25519KeyReader;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.ledger.accounts.BackingAccounts;
import com.hedera.services.ledger.accounts.BackingNfts;
import com.hedera.services.ledger.accounts.BackingTokenRels;
import com.hedera.services.ledger.ids.SeqNoEntityIdSource;
import com.hedera.services.legacy.handler.FreezeHandler;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.legacy.services.state.AwareProcessLogic;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.queries.answering.QueryHeaderValidity;
import com.hedera.services.queries.answering.QueryResponseHelper;
import com.hedera.services.queries.answering.StakedAnswerFlow;
import com.hedera.services.queries.answering.ZeroStakeAnswerFlow;
import com.hedera.services.queries.consensus.HcsAnswers;
import com.hedera.services.queries.contract.ContractAnswers;
import com.hedera.services.queries.crypto.CryptoAnswers;
import com.hedera.services.queries.meta.MetaAnswers;
import com.hedera.services.queries.schedule.ScheduleAnswers;
import com.hedera.services.queries.token.TokenAnswers;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.records.RecordCache;
import com.hedera.services.records.TxnAwareRecordsHistorian;
import com.hedera.services.security.ops.SystemOpPolicies;
import com.hedera.services.sigs.order.HederaSigningOrder;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.expiry.EntityAutoRenewal;
import com.hedera.services.state.expiry.ExpiringCreations;
import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.state.exports.SignedStateBalancesExporter;
import com.hedera.services.state.exports.ToStringAccountsExporter;
import com.hedera.services.state.initialization.BackedSystemAccountsCreator;
import com.hedera.services.state.initialization.HfsSystemFilesManager;
import com.hedera.services.state.logic.AwareNodeDiligenceScreen;
import com.hedera.services.state.logic.InvariantChecks;
import com.hedera.services.state.logic.NetworkCtxManager;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleBlobMeta;
import com.hedera.services.state.merkle.MerkleDiskFs;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleNetworkContext;
import com.hedera.services.state.merkle.MerkleOptionalBlob;
import com.hedera.services.state.merkle.MerkleSchedule;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.state.merkle.MerkleTopic;
import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.migration.StdStateMigrations;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.state.validation.BasedLedgerValidator;
import com.hedera.services.stats.HapiOpCounters;
import com.hedera.services.stats.MiscRunningAvgs;
import com.hedera.services.stats.MiscSpeedometers;
import com.hedera.services.stats.ServicesStatsManager;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.HederaTokenStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.stream.NonBlockingHandoff;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.stream.RecordsRunningHashLeaf;
import com.hedera.services.throttling.HapiThrottling;
import com.hedera.services.throttling.TransactionThrottling;
import com.hedera.services.throttling.TxnAwareHandleThrottling;
import com.hedera.services.txns.TransitionLogicLookup;
import com.hedera.services.txns.TransitionRunner;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.txns.span.SpanMapManager;
import com.hedera.services.txns.submission.BasicSubmissionFlow;
import com.hedera.services.txns.submission.PlatformSubmissionManager;
import com.hedera.services.txns.submission.SyntaxPrecheck;
import com.hedera.services.txns.submission.TransactionPrecheck;
import com.hedera.services.txns.submission.TxnResponseHelper;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.utils.SleepingPause;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.Console;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.PlatformStatus;
import com.swirlds.common.crypto.Cryptography;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.RunningHash;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;
import java.lang.reflect.Field;
import org.ethereum.db.ServicesRepositoryRoot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.hedera.services.stream.RecordStreamManagerTest.INITIAL_RANDOM_HASH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.inOrder;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.spy;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.when;

class ServicesContextTest {
	private final long id = 1L;
	private final NodeId nodeId = new NodeId(false, id);
	private static final String recordStreamDir = "somePath/recordStream";

	private Instant consensusTimeOfLastHandledTxn = Instant.now();
	private Platform platform;
	private SequenceNumber seqNo;
	private ExchangeRates midnightRates;
	private MerkleNetworkContext networkCtx;
	private ServicesState state;
  private StateChildren workingState;
	private Cryptography crypto;
	private PropertySource properties;
	private StandardizedPropertySources propertySources;
	private FCMap<MerkleEntityId, MerkleTopic> topics;
	private FCMap<MerkleEntityId, MerkleToken> tokens;
	private FCMap<MerkleEntityId, MerkleAccount> accounts;
	private FCMap<MerkleEntityId, MerkleSchedule> schedules;
	private FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage;
	private FCMap<MerkleUniqueTokenId, MerkleUniqueToken> uniqueTokens;
	private FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenAssociations;
	private FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations;
	private FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations;
  private AddressBook addresses;
	private MerkleDiskFs diskFs;

	@BeforeEach
	void setup() {
		uniqueTokens = mock(FCMap.class);
		uniqueTokenAssociations = mock(FCOneToManyRelation.class);
		uniqueOwnershipAssociations = mock(FCOneToManyRelation.class);
		topics = mock(FCMap.class);
		tokens = mock(FCMap.class);
		tokenAssociations = mock(FCMap.class);
		schedules = mock(FCMap.class);
		addresses = mock(AddressBook.class);
		storage = mock(FCMap.class);
		accounts = mock(FCMap.class);
		diskFs = mock(MerkleDiskFs.class);
		seqNo = mock(SequenceNumber.class);
		midnightRates = mock(ExchangeRates.class);
		networkCtx = new MerkleNetworkContext(consensusTimeOfLastHandledTxn, seqNo, 1000L, midnightRates);
		state = mock(ServicesState.class);
		given(state.networkCtx()).willReturn(networkCtx);
		given(state.accounts()).willReturn(accounts);
		given(state.storage()).willReturn(storage);
		given(state.topics()).willReturn(topics);
		given(state.tokens()).willReturn(tokens);
		given(state.tokenAssociations()).willReturn(tokenAssociations);
		given(state.scheduleTxs()).willReturn(schedules);

    given(state.addressBook()).willReturn(addresses);
		given(state.diskFs()).willReturn(diskFs);
		workingState = mock(StateChildren.class);
		given(workingState.getNetworkCtx()).willReturn(networkCtx);
		given(workingState.getAccounts()).willReturn(accounts);
		given(workingState.getStorage()).willReturn(storage);
		given(workingState.getTopics()).willReturn(topics);
		given(workingState.getTokens()).willReturn(tokens);
		given(workingState.getTokenAssociations()).willReturn(tokenAssociations);
		given(workingState.getSchedules()).willReturn(schedules);
		given(workingState.getAddressBook()).willReturn(addresses);
		given(workingState.getDiskFs()).willReturn(diskFs);
		given(state.uniqueTokens()).willReturn(uniqueTokens);
		given(state.uniqueTokenAssociations()).willReturn(uniqueTokenAssociations);
		given(state.uniqueOwnershipAssociations()).willReturn(uniqueOwnershipAssociations);

    crypto = mock(Cryptography.class);
		platform = mock(Platform.class);
		given(platform.getSelfId()).willReturn(new NodeId(false, 0L));
		given(platform.getCryptography()).willReturn(crypto);
		properties = mock(PropertySource.class);
		propertySources = mock(StandardizedPropertySources.class);
		given(propertySources.asResolvingSource()).willReturn(properties);
	}

	@Test
	void updatesStateAsExpected() throws Exception {
		// setup:
		var newState = mock(ServicesState.class);
		var newAccounts = mock(FCMap.class);
		var newTopics = mock(FCMap.class);
		var newStorage = mock(FCMap.class);
		var newTokens = mock(FCMap.class);
		var newTokenRels = mock(FCMap.class);
		var newSchedules = mock(FCMap.class);
		var newUniqueTokens = mock(FCMap.class);
		var newUniqueTokenAssociations = mock(FCOneToManyRelation.class);
		var newUniqueOwnershipAssociations = mock(FCOneToManyRelation.class);

		given(newState.accounts()).willReturn(newAccounts);
		given(newState.topics()).willReturn(newTopics);
		given(newState.tokens()).willReturn(newTokens);
		given(newState.storage()).willReturn(newStorage);
		given(newState.tokenAssociations()).willReturn(newTokenRels);
		given(newState.scheduleTxs()).willReturn(newSchedules);
		given(newState.uniqueTokens()).willReturn(newUniqueTokens);
		given(newState.uniqueTokenAssociations()).willReturn(newUniqueTokenAssociations);
		given(newState.uniqueOwnershipAssociations()).willReturn(newUniqueOwnershipAssociations);

    // given:
		var subject = new ServicesContext(nodeId, platform, state, propertySources);

		AtomicReference<StateChildren> queryableState = getQueryableState(subject);

		// and:
		assertSame(state, subject.state);
		assertSame(state.accounts(), queryableState.get().getAccounts());
		assertSame(state.topics(), queryableState.get().getTopics());
		assertSame(state.storage(), queryableState.get().getStorage());
		assertSame(state.tokens(), queryableState.get().getTokens());
		assertSame(state.tokenAssociations(), queryableState.get().getTokenAssociations());
		assertSame(state.scheduleTxs(), queryableState.get().getSchedules());
		assertSame(state.uniqueTokens(), queryableState.get().getUniqueTokens());
		assertSame(state.uniqueTokenAssociations(), queryableState.get().getUniqueTokenAssociations());
		assertSame(state.uniqueOwnershipAssociations(), queryableState.get().getUniqueOwnershipAssociations());

		// when:
		subject.update(newState);

		assertSame(newState, subject.state);
		assertSame(newState.accounts(), queryableState.get().getAccounts());
		assertSame(newState.topics(), queryableState.get().getTopics());
		assertSame(newState.storage(), queryableState.get().getStorage());
		assertSame(newState.tokens(), queryableState.get().getTokens());
		assertSame(newState.tokenAssociations(), queryableState.get().getTokenAssociations());
		assertSame(newState.scheduleTxs(), queryableState.get().getSchedules());

		assertSame(newState.uniqueTokens(), queryableState.get().getUniqueTokens());
		assertSame(newState.uniqueTokenAssociations(), queryableState.get().getUniqueTokenAssociations());
		assertSame(newState.uniqueOwnershipAssociations(), queryableState.get().getUniqueOwnershipAssociations());
	}

	@Test
	void queryableUniqueTokenAssociationsReturnsProperReference() throws Exception {
		var subject = new ServicesContext(nodeId, platform, state, propertySources);
		AtomicReference<StateChildren> queryableState = getQueryableState(subject);

		compareFCOTMR(subject.uniqueTokenAssociations(), queryableState.get().getUniqueTokenAssociations());
	}

	@Test
	void queryableUniqueTokenAccountOwnershipsReturnsProperReference() throws Exception {
		var subject = new ServicesContext(nodeId, platform, state, propertySources);
		AtomicReference<StateChildren> queryableState = getQueryableState(subject);

		compareFCOTMR(subject.uniqueOwnershipAssociations(), queryableState.get().getUniqueOwnershipAssociations());
	}

	@Test
	void delegatesPrimitivesToState() throws Exception {
		// setup:
		InOrder inOrder = inOrder(workingState);

		// given:
		ServicesContext subject = new ServicesContext(nodeId, platform, state, propertySources);

		Field workingStateField = null;

		try {
			workingStateField = subject.getClass().getDeclaredField("workingState");
			workingStateField.setAccessible(true);
			workingStateField.set(subject, workingState);
		} catch (NoSuchFieldException e) {
			throw new Exception("Unable to set working state field", e);
		}

		// when:
		subject.addressBook();
		var actualSeqNo = subject.seqNo();
		var actualMidnightRates = subject.midnightRates();
		var actualLastHandleTime = subject.consensusTimeOfLastHandledTxn();
		subject.topics();
		subject.storage();
		subject.accounts();

		// then:
		inOrder.verify(workingState).getAddressBook();
		assertEquals(seqNo, actualSeqNo);
		assertEquals(midnightRates, actualMidnightRates);
		assertEquals(consensusTimeOfLastHandledTxn, actualLastHandleTime);
		inOrder.verify(workingState).getTopics();
		inOrder.verify(workingState).getStorage();
		inOrder.verify(workingState).getAccounts();
	}

	@Test
	void constructorSetsWorkingState() {
		ServicesContext subject = new ServicesContext(nodeId, platform, state, propertySources);

		assertEquals(state.accounts(), subject.accounts());
		assertEquals(state.topics(), subject.topics());
		assertEquals(state.storage(), subject.storage());
		assertEquals(state.tokens(), subject.tokens());
		assertEquals(state.tokenAssociations(), subject.tokenAssociations());
		assertEquals(state.scheduleTxs(), subject.schedules());
		assertEquals(state.networkCtx(), subject.networkCtx());
		assertEquals(state.addressBook(), subject.addressBook());
		assertEquals(state.diskFs(), subject.diskFs());
	}

	@Test
	void canOverrideLastHandledConsensusTime() {
		// given:
		Instant dataDrivenNow = Instant.now();
		ServicesContext ctx =
				new ServicesContext(
						nodeId,
						platform,
						state,
						propertySources);

		// when:
		ctx.updateConsensusTimeOfLastHandledTxn(dataDrivenNow);

		// then:
		assertEquals(dataDrivenNow, ctx.consensusTimeOfLastHandledTxn());
	}

	@Test
	void hasExpectedConsole() {
		// setup:
		Console console = mock(Console.class);
		given(platform.createConsole(true)).willReturn(console);

		// when:
		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);

		// then:
		assertEquals(console, ctx.console());
		assertNull(ctx.consoleOut());
	}

	@Test
	void hasExpectedZeroStakeInfrastructure() {
		// setup:
		Address address = mock(Address.class);
		AddressBook book = mock(AddressBook.class);
		given(address.getMemo()).willReturn("0.0.3");
		given(address.getStake()).willReturn(0L);
		given(book.getAddress(1L)).willReturn(address);
		given(state.addressBook()).willReturn(book);

		// given:
		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);

		// expect:
		assertEquals(ServicesNodeType.ZERO_STAKE_NODE, ctx.nodeType());
		// and:
		assertThat(ctx.answerFlow(), instanceOf(ZeroStakeAnswerFlow.class));
	}

	@Test
	void rebuildsStoreViewsIfNonNull() {
		// setup:
		ScheduleStore scheduleStore = mock(ScheduleStore.class);
		TokenStore tokenStore = mock(TokenStore.class);

		// given:
		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);

		// expect:
		assertDoesNotThrow(ctx::rebuildStoreViewsIfPresent);

		// and given:
		ctx.setTokenStore(tokenStore);
		ctx.setScheduleStore(scheduleStore);

		// when:
		ctx.rebuildStoreViewsIfPresent();

		// then:
		verify(tokenStore).rebuildViews();
		verify(scheduleStore).rebuildViews();
	}

	@Test
	void rebuildsBackingAccountsIfNonNull() {
		// setup:
		BackingNfts nfts = mock(BackingNfts.class);
		BackingTokenRels tokenRels = mock(BackingTokenRels.class);
		BackingAccounts backingAccounts = mock(BackingAccounts.class);

		// given:
		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);

		// expect:
		assertDoesNotThrow(ctx::rebuildBackingStoresIfPresent);

		// and given:
		ctx.setBackingAccounts(backingAccounts);
		ctx.setBackingTokenRels(tokenRels);
		ctx.setBackingNfts(nfts);

		// when:
		ctx.rebuildBackingStoresIfPresent();

		// then:
		verify(tokenRels).rebuildFromSources();
		verify(backingAccounts).rebuildFromSources();
		verify(nfts).rebuildFromSources();
	}

	@Test
	void hasExpectedStakedInfrastructure() throws Exception {
		// setup:
		Address address = mock(Address.class);
		AddressBook book = mock(AddressBook.class);
		given(address.getMemo()).willReturn("0.0.3");
		given(address.getStake()).willReturn(1_234_567L);
		given(book.getAddress(1L)).willReturn(address);
		given(state.addressBook()).willReturn(book);

		// given:
		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);
		// and:
		ctx.platformStatus().set(PlatformStatus.DISCONNECTED);

		AtomicReference<StateChildren> queryableState = getQueryableState(ctx);

		// expect:
		assertEquals(SleepingPause.SLEEPING_PAUSE, ctx.pause());
		assertEquals(PlatformStatus.DISCONNECTED, ctx.platformStatus().get());
		assertEquals(ctx.properties(), properties);
		assertEquals(ctx.propertySources(), propertySources);
		// and expect TDD:
		assertThat(ctx.hfs(), instanceOf(TieredHederaFs.class));
		assertThat(ctx.ids(), instanceOf(SeqNoEntityIdSource.class));
		assertThat(ctx.fees(), instanceOf(UsageBasedFeeCalculator.class));
		assertThat(ctx.grpc(), instanceOf(NettyGrpcServerManager.class));
		assertThat(ctx.ledger(), instanceOf(HederaLedger.class));
		assertThat(ctx.txnCtx(), instanceOf(AwareTransactionContext.class));
		assertThat(ctx.keyOrder(), instanceOf(HederaSigningOrder.class));
		assertThat(ctx.backedKeyOrder(), instanceOf(HederaSigningOrder.class));
		assertThat(ctx.validator(), instanceOf(ContextOptionValidator.class));
		assertThat(ctx.hcsAnswers(), instanceOf(HcsAnswers.class));
		assertThat(ctx.issEventInfo(), instanceOf(IssEventInfo.class));
		assertThat(ctx.cryptoGrpc(), instanceOf(CryptoController.class));
		assertThat(ctx.answerFlow(), instanceOf(StakedAnswerFlow.class));
		assertThat(ctx.recordCache(), instanceOf(RecordCache.class));
		assertThat(ctx.topics(), instanceOf(FCMap.class));
		assertThat(ctx.storage(), instanceOf(FCMap.class));
		assertThat(ctx.metaAnswers(), instanceOf(MetaAnswers.class));
		assertThat(ctx.stateViews().get(), instanceOf(StateView.class));
		assertThat(ctx.fileNums(), instanceOf(FileNumbers.class));
		assertThat(ctx.accountNums(), instanceOf(AccountNumbers.class));
		assertThat(ctx.usagePrices(), instanceOf(AwareFcfsUsagePrices.class));
		assertThat(ctx.currentView(), instanceOf(StateView.class));
		assertThat(ctx.blobStore(), instanceOf(FcBlobsBytesStore.class));
		assertThat(ctx.entityExpiries(), instanceOf(Map.class));
		assertThat(ctx.syncVerifier(), instanceOf(SyncVerifier.class));
		assertThat(ctx.txnThrottling(), instanceOf(TransactionThrottling.class));
		assertThat(ctx.accountSource(), instanceOf(LedgerAccountsSource.class));
		assertThat(ctx.bytecodeDb(), instanceOf(BlobStorageSource.class));
		assertThat(ctx.cryptoAnswers(), instanceOf(CryptoAnswers.class));
		assertThat(ctx.tokenAnswers(), instanceOf(TokenAnswers.class));
		assertThat(ctx.scheduleAnswers(), instanceOf(ScheduleAnswers.class));
		assertThat(ctx.consensusGrpc(), instanceOf(ConsensusController.class));
		assertThat(ctx.storagePersistence(), instanceOf(BlobStoragePersistence.class));
		assertThat(ctx.filesGrpc(), instanceOf(FileController.class));
		assertThat(ctx.networkGrpc(), instanceOf(NetworkController.class));
		assertThat(ctx.entityNums(), instanceOf(EntityNumbers.class));
		assertThat(ctx.feeSchedulesManager(), instanceOf(FeeSchedulesManager.class));
		assertThat(ctx.submissionFlow(), instanceOf(BasicSubmissionFlow.class));
		assertThat(ctx.answerFunctions(), instanceOf(AnswerFunctions.class));
		assertThat(ctx.queryFeeCheck(), instanceOf(QueryFeeCheck.class));
		assertThat(queryableState, instanceOf(AtomicReference.class));
		assertThat(queryableState.get(), instanceOf(StateChildren.class));
		assertThat(queryableState.get().getTopics(), instanceOf(FCMap.class));

		assertThat(ctx.transitionLogic(), instanceOf(TransitionLogicLookup.class));
		assertThat(ctx.precheckVerifier(), instanceOf(PrecheckVerifier.class));
		assertThat(ctx.apiPermissionsReloading(), instanceOf(ValidatingCallbackInterceptor.class));
		assertThat(ctx.applicationPropertiesReloading(), instanceOf(ValidatingCallbackInterceptor.class));
		assertThat(ctx.recordsHistorian(), instanceOf(TxnAwareRecordsHistorian.class));
		assertThat(queryableState.get().getAccounts(), instanceOf(FCMap.class));
		assertThat(queryableState.get().getUniqueTokens(), instanceOf(FCMap.class));
		assertThat(queryableState.get().getTokenAssociations(), instanceOf(FCMap.class));
		assertThat(queryableState.get().getUniqueOwnershipAssociations(), instanceOf(FCOneToManyRelation.class));

    assertThat(ctx.txnChargingPolicy(), instanceOf(FeeChargingPolicy.class));
		assertThat(ctx.txnResponseHelper(), instanceOf(TxnResponseHelper.class));
		assertThat(ctx.statusCounts(), instanceOf(ConsensusStatusCounts.class));
		assertThat(queryableState.get().getStorage(), instanceOf(FCMap.class));
		assertThat(ctx.systemFilesManager(), instanceOf(HfsSystemFilesManager.class));
		assertThat(ctx.queryResponseHelper(), instanceOf(QueryResponseHelper.class));
		assertThat(ctx.solidityLifecycle(), instanceOf(SolidityLifecycle.class));
		assertThat(ctx.repository(), instanceOf(ServicesRepositoryRoot.class));
		assertThat(ctx.newPureRepo(), instanceOf(Supplier.class));
		assertThat(ctx.exchangeRatesManager(), instanceOf(TxnAwareRatesManager.class));
		assertThat(ctx.lookupRetryingKeyOrder(), instanceOf(HederaSigningOrder.class));
		assertThat(ctx.soliditySigsVerifier(), instanceOf(TxnAwareSoliditySigsVerifier.class));
		assertThat(ctx.expiries(), instanceOf(ExpiryManager.class));
		assertThat(ctx.creator(), instanceOf(ExpiringCreations.class));
		assertThat(ctx.txnHistories(), instanceOf(Map.class));
		assertThat(ctx.backingAccounts(), instanceOf(BackingAccounts.class));
		assertThat(ctx.backingTokenRels(), instanceOf(BackingTokenRels.class));
		assertThat(ctx.systemAccountsCreator(), instanceOf(BackedSystemAccountsCreator.class));
		assertThat(ctx.b64KeyReader(), instanceOf(LegacyEd25519KeyReader.class));
		assertThat(ctx.ledgerValidator(), instanceOf(BasedLedgerValidator.class));
		assertThat(ctx.systemOpPolicies(), instanceOf(SystemOpPolicies.class));
		assertThat(ctx.exemptions(), instanceOf(StandardExemptions.class));
		assertThat(ctx.submissionManager(), instanceOf(PlatformSubmissionManager.class));
		assertThat(ctx.platformStatus(), instanceOf(ContextPlatformStatus.class));
		assertThat(ctx.contractAnswers(), instanceOf(ContractAnswers.class));
		assertThat(ctx.tokenStore(), instanceOf(HederaTokenStore.class));
		assertThat(ctx.globalDynamicProperties(), instanceOf(GlobalDynamicProperties.class));
		assertThat(ctx.tokenGrpc(), instanceOf(TokenController.class));
		assertThat(ctx.scheduleGrpc(), instanceOf(ScheduleController.class));
		assertThat(ctx.nodeLocalProperties(), instanceOf(NodeLocalProperties.class));
		assertThat(ctx.balancesExporter(), instanceOf(SignedStateBalancesExporter.class));
		assertThat(ctx.exchange(), instanceOf(AwareHbarCentExchange.class));
		assertThat(ctx.stateMigrations(), instanceOf(StdStateMigrations.class));
		assertThat(ctx.opCounters(), instanceOf(HapiOpCounters.class));
		assertThat(ctx.runningAvgs(), instanceOf(MiscRunningAvgs.class));
		assertThat(ctx.speedometers(), instanceOf(MiscSpeedometers.class));
		assertThat(ctx.statsManager(), instanceOf(ServicesStatsManager.class));
		assertThat(ctx.semVers(), instanceOf(SemanticVersions.class));
		assertThat(ctx.freezeGrpc(), instanceOf(FreezeController.class));
		assertThat(ctx.contractsGrpc(), instanceOf(ContractController.class));
		assertThat(ctx.activationHelper(), instanceOf(InHandleActivationHelper.class));
		assertThat(ctx.characteristics(), instanceOf(CharacteristicsFactory.class));
		assertThat(ctx.nodeDiligenceScreen(), instanceOf(AwareNodeDiligenceScreen.class));
		assertThat(ctx.feeMultiplierSource(), instanceOf(TxnRateFeeMultiplierSource.class));
		assertThat(ctx.hapiThrottling(), instanceOf(HapiThrottling.class));
		assertThat(ctx.handleThrottling(), instanceOf(TxnAwareHandleThrottling.class));
		assertThat(ctx.throttleDefsManager(), instanceOf(ThrottleDefsManager.class));
		assertThat(ctx.sysFileCallbacks(), instanceOf(SysFileCallbacks.class));
		assertThat(ctx.networkCtxManager(), instanceOf(NetworkCtxManager.class));
		assertThat(ctx.hapiOpPermissions(), instanceOf(HapiOpPermissions.class));
		assertThat(ctx.accountsExporter(), instanceOf(ToStringAccountsExporter.class));
		assertThat(ctx.syntaxPrecheck(), instanceOf(SyntaxPrecheck.class));
		assertThat(ctx.transactionPrecheck(), instanceOf(TransactionPrecheck.class));
		assertThat(ctx.queryHeaderValidity(), instanceOf(QueryHeaderValidity.class));
		assertThat(ctx.entityAutoRenewal(), instanceOf(EntityAutoRenewal.class));
		assertThat(ctx.typedTokenStore(), instanceOf(TypedTokenStore.class));
		assertThat(ctx.transitionRunner(), instanceOf(TransitionRunner.class));
		assertThat(ctx.nodeInfo(), instanceOf(NodeInfo.class));
		assertThat(ctx.invariants(), instanceOf(InvariantChecks.class));
		assertThat(ctx.narratedCharging(), instanceOf(NarratedLedgerCharging.class));
		assertThat(ctx.chargingPolicyAgent(), instanceOf(TxnChargingPolicyAgent.class));
		assertThat(ctx.expandHandleSpan(), instanceOf(ExpandHandleSpan.class));
		assertThat(ctx.nonBlockingHandoff(), instanceOf(NonBlockingHandoff.class));
		assertThat(ctx.accessorBasedUsages(), instanceOf(AccessorBasedUsages.class));
		assertThat(ctx.pricedUsageCalculator(), instanceOf(PricedUsageCalculator.class));
		assertThat(ctx.accountStore(), instanceOf(AccountStore.class));
		assertThat(ctx.spanMapManager(), instanceOf(SpanMapManager.class));
		assertThat(ctx.impliedTransfersMarshal(), instanceOf(ImpliedTransfersMarshal.class));
		assertThat(ctx.transferSemanticChecks(), instanceOf(PureTransferSemanticChecks.class));
		assertThat(ctx.backingNfts(), instanceOf(BackingNfts.class));
		// and:
		assertEquals(ServicesNodeType.STAKED_NODE, ctx.nodeType());
		// and expect legacy:
		assertThat(ctx.contracts(), instanceOf(SmartContractRequestHandler.class));
		assertThat(ctx.freeze(), instanceOf(FreezeHandler.class));
		assertThat(ctx.logic(), instanceOf(AwareProcessLogic.class));
	}

	@Test
	void shouldInitFees() throws Exception {
		// setup:
		MerkleNetworkContext networkCtx = new MerkleNetworkContext();

		given(properties.getLongProperty("files.feeSchedules")).willReturn(111L);
		given(properties.getIntProperty("cache.records.ttl")).willReturn(180);
		var book = mock(AddressBook.class);
		var diskFs = mock(MerkleDiskFs.class);
		var blob = mock(MerkleOptionalBlob.class);
		byte[] fileInfo = new HFileMeta(false, StateView.EMPTY_WACL, 1_234_567L).serialize();
		byte[] fileContents = new byte[0];
		given(state.networkCtx()).willReturn(networkCtx);
		given(state.addressBook()).willReturn(book);
		given(state.diskFs()).willReturn(diskFs);
		given(storage.containsKey(any())).willReturn(true);
		given(storage.get(any())).willReturn(blob);
		given(blob.getData()).willReturn(fileInfo);
		given(diskFs.contains(any())).willReturn(true);
		given(diskFs.contentsOf(any())).willReturn(fileContents);

		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);
		var subject = ctx.systemFilesManager();

		assertSame(networkCtx, ctx.networkCtx());
		assertDoesNotThrow(() -> subject.loadFeeSchedules());
	}

	@Test
	void getRecordStreamDirectoryTest() {
		String expectedDir = "/here/we/are";

		NodeLocalProperties sourceProps = mock(NodeLocalProperties.class);
		given(sourceProps.recordLogDir()).willReturn(expectedDir);
		final AddressBook book = mock(AddressBook.class);
		final Address address = mock(Address.class);
		given(state.addressBook()).willReturn(book);
		given(address.getStake()).willReturn(1L);
		given(address.getMemo()).willReturn("0.0.3");
		given(book.getAddress(0)).willReturn(address);
		given(book.getSize()).willReturn(1);

		ServicesContext ctx = new ServicesContext(nodeId, platform, state, propertySources);
		assertEquals(expectedDir + "/record0.0.0", ctx.getRecordStreamDirectory(sourceProps));
	}

	@Test
	void updateRecordRunningHashTest() {
		// given:
		final RunningHash runningHash = mock(RunningHash.class);
		final RecordsRunningHashLeaf runningHashLeaf = new RecordsRunningHashLeaf();
		when(state.runningHashLeaf()).thenReturn(runningHashLeaf);

		ServicesContext ctx =
				new ServicesContext(
						nodeId,
						platform,
						state,
						propertySources);

		// when:
		ctx.updateRecordRunningHash(runningHash);

		// then:
		assertEquals(runningHash, ctx.state.runningHashLeaf().getRunningHash());
	}

	@Test
	void initRecordStreamManagerTest() {
		// given:
		final AddressBook book = mock(AddressBook.class);
		final Address address = mock(Address.class);
		given(state.addressBook()).willReturn(book);
		given(book.getAddress(id)).willReturn(address);
		given(address.getMemo()).willReturn("0.0.3");
		given(properties.getStringProperty("hedera.recordStream.logDir")).willReturn(recordStreamDir);
		given(properties.getIntProperty("hedera.recordStream.queueCapacity")).willReturn(123);
		given(properties.getLongProperty("hedera.recordStream.logPeriod")).willReturn(1L);
		given(properties.getBooleanProperty("hedera.recordStream.isEnabled")).willReturn(true);
		final Hash initialHash = INITIAL_RANDOM_HASH;

		ServicesContext ctx =
				new ServicesContext(
						nodeId,
						platform,
						state,
						propertySources);

		assertNull(ctx.recordStreamManager());

		// when:
		ctx.setRecordsInitialHash(initialHash);
		ctx.initRecordStreamManager();

		// then:
		assertEquals(initialHash, ctx.getRecordsInitialHash());
		assertNotNull(ctx.recordStreamManager());
		assertEquals(initialHash, ctx.recordStreamManager().getInitialHash());
	}

	@Test
	void setRecordsInitialHashTest() {
		// given:
		final Hash initialHash = INITIAL_RANDOM_HASH;

		ServicesContext ctx = spy(new ServicesContext(
				nodeId,
				platform,
				state,
				propertySources));
		RecordStreamManager recordStreamManager = mock(RecordStreamManager.class);

		when(ctx.recordStreamManager()).thenReturn(recordStreamManager);

		// when:
		ctx.setRecordsInitialHash(initialHash);

		// then:
		verify(recordStreamManager).setInitialHash(initialHash);
	}

	/**
	 * Use reflection to extract the private field queryableState from ServiceContext
	 * @param serviceContext service context
	 * @return queryable state
	 * @throws Exception if unable to extract field
	 */
	private AtomicReference<StateChildren> getQueryableState(ServicesContext serviceContext) throws Exception {

		Field workingStateField;
		AtomicReference<StateChildren> queryableState;

		try {
			workingStateField = serviceContext.getClass().getDeclaredField("queryableState");
			workingStateField.setAccessible(true);
			queryableState = (AtomicReference<StateChildren>) workingStateField.get(serviceContext);
		} catch (NoSuchFieldException | IllegalAccessException e) {
			throw new Exception("Unable to set working state field", e);
		}

		return queryableState;
  }

  private void compareFCOTMR(FCOneToManyRelation expected, FCOneToManyRelation actual) {
		assertEquals(expected.getKeySet(), actual.getKeySet());
		expected.getKeySet().forEach(key -> {
			assertEquals(expected.getList(key), actual.getList(key));
		});
	}
}
