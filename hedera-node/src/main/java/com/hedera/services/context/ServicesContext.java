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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hedera.services.ServicesState;
import com.hedera.services.calc.OverflowCheckingCalc;
import com.hedera.services.config.AccountNumbers;
import com.hedera.services.config.EntityNumbers;
import com.hedera.services.config.FileNumbers;
import com.hedera.services.config.HederaNumbers;
import com.hedera.services.context.domain.security.HapiOpPermissions;
import com.hedera.services.context.domain.trackers.ConsensusStatusCounts;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.hedera.services.context.primitives.StateView;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.context.properties.PropertySources;
import com.hedera.services.context.properties.SemanticVersions;
import com.hedera.services.context.properties.StandardizedPropertySources;
import com.hedera.services.contracts.execution.SolidityLifecycle;
import com.hedera.services.contracts.execution.SoliditySigsVerifier;
import com.hedera.services.contracts.execution.TxnAwareSoliditySigsVerifier;
import com.hedera.services.contracts.persistence.BlobStoragePersistence;
import com.hedera.services.contracts.sources.BlobStorageSource;
import com.hedera.services.contracts.sources.LedgerAccountsSource;
import com.hedera.services.fees.AwareHbarCentExchange;
import com.hedera.services.fees.FeeCalculator;
import com.hedera.services.fees.FeeExemptions;
import com.hedera.services.fees.FeeMultiplierSource;
import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.fees.StandardExemptions;
import com.hedera.services.fees.TxnRateFeeMultiplierSource;
import com.hedera.services.fees.calculation.AutoRenewCalcs;
import com.hedera.services.fees.calculation.AwareFcfsUsagePrices;
import com.hedera.services.fees.calculation.TxnResourceUsageEstimator;
import com.hedera.services.fees.calculation.UsageBasedFeeCalculator;
import com.hedera.services.fees.calculation.UsagePricesProvider;
import com.hedera.services.fees.calculation.consensus.queries.GetTopicInfoResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.CreateTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.DeleteTopicResourceUsage;
import com.hedera.services.fees.calculation.consensus.txns.UpdateTopicResourceUsage;
import com.hedera.services.fees.calculation.contract.queries.ContractCallLocalResourceUsage;
import com.hedera.services.fees.calculation.contract.queries.GetBytecodeResourceUsage;
import com.hedera.services.fees.calculation.contract.queries.GetContractInfoResourceUsage;
import com.hedera.services.fees.calculation.contract.queries.GetContractRecordsResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractCallResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractCreateResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractDeleteResourceUsage;
import com.hedera.services.fees.calculation.contract.txns.ContractUpdateResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetAccountInfoResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetAccountRecordsResourceUsage;
import com.hedera.services.fees.calculation.crypto.queries.GetTxnRecordResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoCreateResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoDeleteResourceUsage;
import com.hedera.services.fees.calculation.crypto.txns.CryptoUpdateResourceUsage;
import com.hedera.services.fees.calculation.file.queries.GetFileContentsResourceUsage;
import com.hedera.services.fees.calculation.file.queries.GetFileInfoResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileAppendResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileCreateResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileDeleteResourceUsage;
import com.hedera.services.fees.calculation.file.txns.FileUpdateResourceUsage;
import com.hedera.services.fees.calculation.file.txns.SystemDeleteFileResourceUsage;
import com.hedera.services.fees.calculation.file.txns.SystemUndeleteFileResourceUsage;
import com.hedera.services.fees.calculation.meta.queries.GetVersionInfoResourceUsage;
import com.hedera.services.fees.calculation.schedule.queries.GetScheduleInfoResourceUsage;
import com.hedera.services.fees.calculation.schedule.txns.ScheduleCreateResourceUsage;
import com.hedera.services.fees.calculation.schedule.txns.ScheduleDeleteResourceUsage;
import com.hedera.services.fees.calculation.schedule.txns.ScheduleSignResourceUsage;
import com.hedera.services.fees.calculation.system.txns.FreezeResourceUsage;
import com.hedera.services.fees.calculation.token.queries.GetAccountNftInfosResourceUsage;
import com.hedera.services.fees.calculation.token.queries.GetTokenInfoResourceUsage;
import com.hedera.services.fees.calculation.token.queries.GetTokenNftInfoResourceUsage;
import com.hedera.services.fees.calculation.token.queries.GetTokenNftInfosResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenAssociateResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenBurnResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenCreateResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenDeleteResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenDissociateResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenFreezeResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenGrantKycResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenMintResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenRevokeKycResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenUnfreezeResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenUpdateResourceUsage;
import com.hedera.services.fees.calculation.token.txns.TokenWipeResourceUsage;
import com.hedera.services.fees.calculation.utils.AccessorBasedUsages;
import com.hedera.services.fees.calculation.utils.OpUsageCtxHelper;
import com.hedera.services.fees.calculation.utils.PricedUsageCalculator;
import com.hedera.services.fees.charging.FeeChargingPolicy;
import com.hedera.services.fees.charging.NarratedCharging;
import com.hedera.services.fees.charging.NarratedLedgerCharging;
import com.hedera.services.fees.charging.TxnChargingPolicyAgent;
import com.hedera.services.files.DataMapFactory;
import com.hedera.services.files.EntityExpiryMapFactory;
import com.hedera.services.files.FileUpdateInterceptor;
import com.hedera.services.files.HederaFs;
import com.hedera.services.files.MetadataMapFactory;
import com.hedera.services.files.SysFileCallbacks;
import com.hedera.services.files.TieredHederaFs;
import com.hedera.services.files.interceptors.ConfigListUtils;
import com.hedera.services.files.interceptors.FeeSchedulesManager;
import com.hedera.services.files.interceptors.ThrottleDefsManager;
import com.hedera.services.files.interceptors.TxnAwareRatesManager;
import com.hedera.services.files.interceptors.ValidatingCallbackInterceptor;
import com.hedera.services.files.store.FcBlobsBytesStore;
import com.hedera.services.files.sysfiles.ConfigCallbacks;
import com.hedera.services.files.sysfiles.CurrencyCallbacks;
import com.hedera.services.files.sysfiles.ThrottlesCallback;
import com.hedera.services.grpc.ConfigDrivenNettyFactory;
import com.hedera.services.grpc.GrpcServerManager;
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
import com.hedera.services.keys.StandardSyncActivationCheck;
import com.hedera.services.ledger.HederaLedger;
import com.hedera.services.ledger.PureTransferSemanticChecks;
import com.hedera.services.ledger.TransactionalLedger;
import com.hedera.services.ledger.accounts.BackingAccounts;
import com.hedera.services.ledger.accounts.BackingNfts;
import com.hedera.services.ledger.accounts.BackingStore;
import com.hedera.services.ledger.accounts.BackingTokenRels;
import com.hedera.services.ledger.accounts.PureBackingAccounts;
import com.hedera.services.ledger.ids.EntityIdSource;
import com.hedera.services.ledger.ids.SeqNoEntityIdSource;
import com.hedera.services.ledger.properties.AccountProperty;
import com.hedera.services.ledger.properties.ChangeSummaryManager;
import com.hedera.services.ledger.properties.NftProperty;
import com.hedera.services.ledger.properties.TokenRelProperty;
import com.hedera.services.legacy.handler.FreezeHandler;
import com.hedera.services.legacy.handler.SmartContractRequestHandler;
import com.hedera.services.legacy.services.state.AwareProcessLogic;
import com.hedera.services.queries.AnswerFlow;
import com.hedera.services.queries.answering.AnswerFunctions;
import com.hedera.services.queries.answering.QueryHeaderValidity;
import com.hedera.services.queries.answering.QueryResponseHelper;
import com.hedera.services.queries.answering.StakedAnswerFlow;
import com.hedera.services.queries.answering.ZeroStakeAnswerFlow;
import com.hedera.services.queries.consensus.GetTopicInfoAnswer;
import com.hedera.services.queries.consensus.HcsAnswers;
import com.hedera.services.queries.contract.ContractAnswers;
import com.hedera.services.queries.contract.ContractCallLocalAnswer;
import com.hedera.services.queries.contract.GetBySolidityIdAnswer;
import com.hedera.services.queries.contract.GetBytecodeAnswer;
import com.hedera.services.queries.contract.GetContractInfoAnswer;
import com.hedera.services.queries.contract.GetContractRecordsAnswer;
import com.hedera.services.queries.crypto.CryptoAnswers;
import com.hedera.services.queries.crypto.GetAccountBalanceAnswer;
import com.hedera.services.queries.crypto.GetAccountInfoAnswer;
import com.hedera.services.queries.crypto.GetAccountRecordsAnswer;
import com.hedera.services.queries.crypto.GetLiveHashAnswer;
import com.hedera.services.queries.crypto.GetStakersAnswer;
import com.hedera.services.queries.file.FileAnswers;
import com.hedera.services.queries.file.GetFileContentsAnswer;
import com.hedera.services.queries.file.GetFileInfoAnswer;
import com.hedera.services.queries.meta.GetFastTxnRecordAnswer;
import com.hedera.services.queries.meta.GetTxnReceiptAnswer;
import com.hedera.services.queries.meta.GetTxnRecordAnswer;
import com.hedera.services.queries.meta.GetVersionInfoAnswer;
import com.hedera.services.queries.meta.MetaAnswers;
import com.hedera.services.queries.schedule.GetScheduleInfoAnswer;
import com.hedera.services.queries.schedule.ScheduleAnswers;
import com.hedera.services.queries.token.GetAccountNftInfosAnswer;
import com.hedera.services.queries.token.GetTokenInfoAnswer;
import com.hedera.services.queries.token.GetTokenNftInfoAnswer;
import com.hedera.services.queries.token.GetTokenNftInfosAnswer;
import com.hedera.services.queries.token.TokenAnswers;
import com.hedera.services.queries.validation.QueryFeeCheck;
import com.hedera.services.records.AccountRecordsHistorian;
import com.hedera.services.records.RecordCache;
import com.hedera.services.records.RecordCacheFactory;
import com.hedera.services.records.TransactionRecordService;
import com.hedera.services.records.TxnAwareRecordsHistorian;
import com.hedera.services.records.TxnIdRecentHistory;
import com.hedera.services.security.ops.SystemOpPolicies;
import com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup;
import com.hedera.services.sigs.order.HederaSigningOrder;
import com.hedera.services.sigs.verification.PrecheckKeyReqs;
import com.hedera.services.sigs.verification.PrecheckVerifier;
import com.hedera.services.sigs.verification.SyncVerifier;
import com.hedera.services.state.expiry.EntityAutoRenewal;
import com.hedera.services.state.expiry.ExpiringCreations;
import com.hedera.services.state.expiry.ExpiryManager;
import com.hedera.services.state.expiry.renewal.RenewalHelper;
import com.hedera.services.state.expiry.renewal.RenewalProcess;
import com.hedera.services.state.expiry.renewal.RenewalRecordsHelper;
import com.hedera.services.state.exports.AccountsExporter;
import com.hedera.services.state.exports.BalancesExporter;
import com.hedera.services.state.exports.SignedStateBalancesExporter;
import com.hedera.services.state.exports.ToStringAccountsExporter;
import com.hedera.services.state.initialization.BackedSystemAccountsCreator;
import com.hedera.services.state.initialization.HfsSystemFilesManager;
import com.hedera.services.state.initialization.SystemAccountsCreator;
import com.hedera.services.state.initialization.SystemFilesManager;
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
import com.hedera.services.state.migration.StateMigrations;
import com.hedera.services.state.migration.StdStateMigrations;
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.state.validation.BasedLedgerValidator;
import com.hedera.services.state.validation.LedgerValidator;
import com.hedera.services.stats.CounterFactory;
import com.hedera.services.stats.HapiOpCounters;
import com.hedera.services.stats.HapiOpSpeedometers;
import com.hedera.services.stats.MiscRunningAvgs;
import com.hedera.services.stats.MiscSpeedometers;
import com.hedera.services.stats.RunningAvgFactory;
import com.hedera.services.stats.ServicesStatsManager;
import com.hedera.services.stats.SpeedometerFactory;
import com.hedera.services.store.AccountStore;
import com.hedera.services.store.TypedTokenStore;
import com.hedera.services.store.models.NftId;
import com.hedera.services.store.schedule.HederaScheduleStore;
import com.hedera.services.store.schedule.ScheduleStore;
import com.hedera.services.store.tokens.HederaTokenStore;
import com.hedera.services.store.tokens.TokenStore;
import com.hedera.services.stream.NonBlockingHandoff;
import com.hedera.services.stream.RecordStreamManager;
import com.hedera.services.throttling.DeterministicThrottling;
import com.hedera.services.throttling.FunctionalityThrottling;
import com.hedera.services.throttling.HapiThrottling;
import com.hedera.services.throttling.TransactionThrottling;
import com.hedera.services.throttling.TxnAwareHandleThrottling;
import com.hedera.services.txns.ProcessLogic;
import com.hedera.services.txns.SubmissionFlow;
import com.hedera.services.txns.TransitionLogic;
import com.hedera.services.txns.TransitionLogicLookup;
import com.hedera.services.txns.TransitionRunner;
import com.hedera.services.txns.consensus.SubmitMessageTransitionLogic;
import com.hedera.services.txns.consensus.TopicCreateTransitionLogic;
import com.hedera.services.txns.consensus.TopicDeleteTransitionLogic;
import com.hedera.services.txns.consensus.TopicUpdateTransitionLogic;
import com.hedera.services.txns.contract.ContractCallTransitionLogic;
import com.hedera.services.txns.contract.ContractCreateTransitionLogic;
import com.hedera.services.txns.contract.ContractDeleteTransitionLogic;
import com.hedera.services.txns.contract.ContractSysDelTransitionLogic;
import com.hedera.services.txns.contract.ContractSysUndelTransitionLogic;
import com.hedera.services.txns.contract.ContractUpdateTransitionLogic;
import com.hedera.services.txns.contract.helpers.UpdateCustomizerFactory;
import com.hedera.services.txns.crypto.CryptoCreateTransitionLogic;
import com.hedera.services.txns.crypto.CryptoDeleteTransitionLogic;
import com.hedera.services.txns.crypto.CryptoTransferTransitionLogic;
import com.hedera.services.txns.crypto.CryptoUpdateTransitionLogic;
import com.hedera.services.txns.customfees.CustomFeeSchedules;
import com.hedera.services.txns.customfees.FcmCustomFeeSchedules;
import com.hedera.services.txns.file.FileAppendTransitionLogic;
import com.hedera.services.txns.file.FileCreateTransitionLogic;
import com.hedera.services.txns.file.FileDeleteTransitionLogic;
import com.hedera.services.txns.file.FileSysDelTransitionLogic;
import com.hedera.services.txns.file.FileSysUndelTransitionLogic;
import com.hedera.services.txns.file.FileUpdateTransitionLogic;
import com.hedera.services.txns.network.FreezeTransitionLogic;
import com.hedera.services.txns.network.UncheckedSubmitTransitionLogic;
import com.hedera.services.txns.schedule.ScheduleCreateTransitionLogic;
import com.hedera.services.txns.schedule.ScheduleDeleteTransitionLogic;
import com.hedera.services.txns.schedule.ScheduleExecutor;
import com.hedera.services.txns.schedule.ScheduleSignTransitionLogic;
import com.hedera.services.txns.span.ExpandHandleSpan;
import com.hedera.services.txns.span.ExpandHandleSpanMapAccessor;
import com.hedera.services.txns.span.SpanMapManager;
import com.hedera.services.txns.submission.BasicSubmissionFlow;
import com.hedera.services.txns.submission.PlatformSubmissionManager;
import com.hedera.services.txns.submission.SemanticPrecheck;
import com.hedera.services.txns.submission.SolvencyPrecheck;
import com.hedera.services.txns.submission.StagedPrechecks;
import com.hedera.services.txns.submission.StructuralPrecheck;
import com.hedera.services.txns.submission.SyntaxPrecheck;
import com.hedera.services.txns.submission.SystemPrecheck;
import com.hedera.services.txns.submission.TransactionPrecheck;
import com.hedera.services.txns.submission.TxnResponseHelper;
import com.hedera.services.txns.token.TokenAssociateTransitionLogic;
import com.hedera.services.txns.token.TokenBurnTransitionLogic;
import com.hedera.services.txns.token.TokenCreateTransitionLogic;
import com.hedera.services.txns.token.TokenDeleteTransitionLogic;
import com.hedera.services.txns.token.TokenDissociateTransitionLogic;
import com.hedera.services.txns.token.TokenFeeScheduleUpdateTransitionLogic;
import com.hedera.services.txns.token.TokenFreezeTransitionLogic;
import com.hedera.services.txns.token.TokenGrantKycTransitionLogic;
import com.hedera.services.txns.token.TokenMintTransitionLogic;
import com.hedera.services.txns.token.TokenRevokeKycTransitionLogic;
import com.hedera.services.txns.token.TokenUnfreezeTransitionLogic;
import com.hedera.services.txns.token.TokenUpdateTransitionLogic;
import com.hedera.services.txns.token.TokenWipeTransitionLogic;
import com.hedera.services.txns.validation.ContextOptionValidator;
import com.hedera.services.txns.validation.OptionValidator;
import com.hedera.services.usage.consensus.ConsensusOpsUsage;
import com.hedera.services.usage.crypto.CryptoOpsUsage;
import com.hedera.services.usage.file.FileOpsUsage;
import com.hedera.services.usage.schedule.ScheduleOpsUsage;
import com.hedera.services.usage.token.TokenOpsUsage;
import com.hedera.services.utils.MiscUtils;
import com.hedera.services.utils.Pause;
import com.hedera.services.utils.SleepingPause;
import com.hedera.services.utils.TxnAccessor;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.fee.CryptoFeeBuilder;
import com.hederahashgraph.fee.FileFeeBuilder;
import com.hederahashgraph.fee.SmartContractFeeBuilder;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.Console;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.crypto.ImmutableHash;
import com.swirlds.common.crypto.RunningHash;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ethereum.core.AccountState;
import org.ethereum.datasource.Source;
import org.ethereum.datasource.StoragePersistence;
import org.ethereum.db.ServicesRepositoryRoot;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static com.hedera.services.context.ServicesNodeType.STAKED_NODE;
import static com.hedera.services.context.ServicesNodeType.ZERO_STAKE_NODE;
import static com.hedera.services.contracts.sources.AddressKeyedMapFactory.bytecodeMapFrom;
import static com.hedera.services.contracts.sources.AddressKeyedMapFactory.storageMapFrom;
import static com.hedera.services.files.interceptors.ConfigListUtils.uncheckedParse;
import static com.hedera.services.files.interceptors.PureRatesValidation.isNormalIntradayChange;
import static com.hedera.services.ledger.ids.ExceptionalEntityIdSource.NOOP_ID_SOURCE;
import static com.hedera.services.records.NoopRecordsHistorian.NOOP_RECORDS_HISTORIAN;
import static com.hedera.services.security.ops.SystemOpAuthorization.AUTHORIZED;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.backedLookupsFor;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultAccountRetryingLookupsFor;
import static com.hedera.services.sigs.metadata.DelegatingSigMetadataLookup.defaultLookupsFor;
import static com.hedera.services.sigs.metadata.SigMetadataLookup.REF_LOOKUP_FACTORY;
import static com.hedera.services.sigs.metadata.SigMetadataLookup.SCHEDULE_REF_LOOKUP_FACTORY;
import static com.hedera.services.sigs.utils.PrecheckUtils.queryPaymentTestFor;
import static com.hedera.services.state.expiry.NoopExpiringCreations.NOOP_EXPIRING_CREATIONS;
import static com.hedera.services.store.tokens.ExceptionalTokenStore.NOOP_TOKEN_STORE;
import static com.hedera.services.txns.submission.StructuralPrecheck.HISTORICAL_MAX_PROTO_MESSAGE_DEPTH;
import static com.hedera.services.utils.EntityIdUtils.asLiteralString;
import static com.hedera.services.utils.MiscUtils.lookupInCustomStore;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusCreateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusDeleteTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusSubmitMessage;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ConsensusUpdateTopic;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCall;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ContractUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileAppend;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.FileUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.Freeze;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.ScheduleSign;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.SystemUndelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAccountWipe;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenAssociateToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenBurn;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenCreate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDelete;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenDissociateFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenGrantKycToAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenMint;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenRevokeKycFromAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUnfreezeAccount;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.TokenFeeScheduleUpdate;
import static com.hederahashgraph.api.proto.java.HederaFunctionality.UncheckedSubmit;
import static java.util.Map.entry;

/**
 * Provide a trivial implementation of the inversion-of-control pattern,
 * isolating secondary responsibilities of dependency creation and
 * injection in a single component.
 *
 * @author Michael Tinker
 */
public class ServicesContext {
	private static final Logger log = LogManager.getLogger(ServicesContext.class);

	/* Injected dependencies. */
	ServicesState state;

	private final NodeId id;
	private final Platform platform;
	private final PropertySources propertySources;

	/* Context-sensitive singletons. */
	/** the directory to which we writes .rcd and .rcd_sig files */
	private String recordStreamDir;
	/** the initialHash of RecordStreamManager */
	private Hash recordsInitialHash = new ImmutableHash(new byte[DigestType.SHA_384.digestLength()]);
	private Address address;
	private Console console;
	private HederaFs hfs;
	private NodeInfo nodeInfo;
	private StateView currentView;
	private TokenStore tokenStore;
	private AnswerFlow answerFlow;
	private HcsAnswers hcsAnswers;
	private FileNumbers fileNums;
	private FileAnswers fileAnswers;
	private MetaAnswers metaAnswers;
	private RecordCache recordCache;
	private BackingNfts backingNfts;
	private AccountStore accountStore;
	private TokenAnswers tokenAnswers;
	private HederaLedger ledger;
	private SyncVerifier syncVerifier;
	private IssEventInfo issEventInfo;
	private ProcessLogic logic;
	private QueryFeeCheck queryFeeCheck;
	private HederaNumbers hederaNums;
	private ExpiryManager expiries;
	private FeeCalculator fees;
	private FeeExemptions exemptions;
	private EntityNumbers entityNums;
	private FreezeHandler freeze;
	private CryptoAnswers cryptoAnswers;
	private ScheduleStore scheduleStore;
	private SyntaxPrecheck syntaxPrecheck;
	private AccountNumbers accountNums;
	private SubmissionFlow submissionFlow;
	private PropertySource properties;
	private EntityIdSource ids;
	private FileController fileGrpc;
	private HapiOpCounters opCounters;
	private SpanMapManager spanMapManager;
	private AnswerFunctions answerFunctions;
	private ContractAnswers contractAnswers;
	private SwirldDualState dualState;
	private OptionValidator validator;
	private LedgerValidator ledgerValidator;
	private BackingAccounts backingAccounts;
	private TokenController tokenGrpc;
	private MiscRunningAvgs runningAvgs;
	private ScheduleAnswers scheduleAnswers;
	private InvariantChecks invariantChecks;
	private TypedTokenStore typedTokenStore;
	private MiscSpeedometers speedometers;
	private ScheduleExecutor scheduleExecutor;
	private ServicesNodeType nodeType;
	private SystemOpPolicies systemOpPolicies;
	private CryptoController cryptoGrpc;
	private HbarCentExchange exchange;
	private TransitionRunner transitionRunner;
	private SemanticVersions semVers;
	private PrecheckVerifier precheckVerifier;
	private BackingTokenRels backingTokenRels;
	private FreezeController freezeGrpc;
	private ExpandHandleSpan expandHandleSpan;
	private BalancesExporter balancesExporter;
	private SysFileCallbacks sysFileCallbacks;
	private NarratedCharging narratedCharging;
	private NetworkCtxManager networkCtxManager;
	private SolidityLifecycle solidityLifecycle;
	private ExpiringCreations creator;
	private NetworkController networkGrpc;
	private GrpcServerManager grpc;
	private FeeChargingPolicy txnChargingPolicy;
	private TxnResponseHelper txnResponseHelper;
	private BlobStorageSource bytecodeDb;
	private HapiOpPermissions hapiOpPermissions;
	private EntityAutoRenewal entityAutoRenewal;
	private TransactionContext txnCtx;
	private ContractController contractsGrpc;
	private HederaSigningOrder keyOrder;
	private HederaSigningOrder backedKeyOrder;
	private HederaSigningOrder lookupRetryingKeyOrder;
	private StoragePersistence storagePersistence;
	private ScheduleController scheduleGrpc;
	private NonBlockingHandoff nonBlockingHandoff;
	private AccessorBasedUsages accessorBasedUsages;
	private ConsensusController consensusGrpc;
	private QueryResponseHelper queryResponseHelper;
	private UsagePricesProvider usagePrices;
	private Supplier<StateView> stateViews;
	private FeeSchedulesManager feeSchedulesManager;
	private RecordStreamManager recordStreamManager;
	private ThrottleDefsManager throttleDefsManager;
	private QueryHeaderValidity queryHeaderValidity;
	private Map<String, byte[]> blobStore;
	private Map<EntityId, Long> entityExpiries;
	private TransactionPrecheck transactionPrecheck;
	private FeeMultiplierSource feeMultiplierSource;
	private NodeLocalProperties nodeLocalProperties;
	private TxnAwareRatesManager exchangeRatesManager;
	private ServicesStatsManager statsManager;
	private LedgerAccountsSource accountSource;
	private TransitionLogicLookup transitionLogic;
	private FcmCustomFeeSchedules activeCustomFeeSchedules;
	private PricedUsageCalculator pricedUsageCalculator;
	private TransactionThrottling txnThrottling;
	private ConsensusStatusCounts statusCounts;
	private HfsSystemFilesManager systemFilesManager;
	private CurrentPlatformStatus platformStatus;
	private SystemAccountsCreator systemAccountsCreator;
	private TxnChargingPolicyAgent chargingPolicyAgent;
	private ServicesRepositoryRoot repository;
	private CharacteristicsFactory characteristics;
	private AccountRecordsHistorian recordsHistorian;
	private GlobalDynamicProperties globalDynamicProperties;
	private FunctionalityThrottling hapiThrottling;
	private FunctionalityThrottling handleThrottling;
	private ImpliedTransfersMarshal impliedTransfersMarshal;
	private AwareNodeDiligenceScreen nodeDiligenceScreen;
	private InHandleActivationHelper activationHelper;
	private PlatformSubmissionManager submissionManager;
	private PureTransferSemanticChecks transferSemanticChecks;
	private SmartContractRequestHandler contracts;
	private TxnAwareSoliditySigsVerifier soliditySigsVerifier;
	private ValidatingCallbackInterceptor apiPermissionsReloading;
	private ValidatingCallbackInterceptor applicationPropertiesReloading;
	private Supplier<ServicesRepositoryRoot> newPureRepo;
	private Map<TransactionID, TxnIdRecentHistory> txnHistories;
	private StateChildren workingState = new StateChildren();
	private AtomicReference<StateChildren> queryableState = new AtomicReference<>(new StateChildren());

	/* Context-free infrastructure. */
	private static Pause pause;
	private static StateMigrations stateMigrations;
	private static AccountsExporter accountsExporter;
	private static LegacyEd25519KeyReader b64KeyReader;

	static {
		pause = SleepingPause.SLEEPING_PAUSE;
		b64KeyReader = new LegacyEd25519KeyReader();
		stateMigrations = new StdStateMigrations(SleepingPause.SLEEPING_PAUSE);
		accountsExporter = new ToStringAccountsExporter();
	}

	public ServicesContext(
			NodeId id,
			Platform platform,
			ServicesState state,
			PropertySources propertySources
	) {
		this.id = id;
		this.platform = platform;
		this.state = state;
		this.propertySources = propertySources;

		updateWorkingState(state);
		updateQueryableState(state);
	}

	/**
	 * Update the state and working state based on the provided service state
	 *
	 * @param state
	 * 		latest state from the services
	 */
	public void update(ServicesState state) {
		this.state = state;

		updateWorkingState(state);
		updateQueryableState(state);
	}

	/**
	 * Update the queryable state
	 */
	private void updateQueryableState(ServicesState state) {
		final StateChildren newQueryableStateChildren = new StateChildren();

		newQueryableStateChildren.setAccounts(state.accounts());
		newQueryableStateChildren.setTopics(state.topics());
		newQueryableStateChildren.setStorage(state.storage());
		newQueryableStateChildren.setTokens(state.tokens());
		newQueryableStateChildren.setTokenAssociations(state.tokenAssociations());
		newQueryableStateChildren.setSchedules(state.scheduleTxs());
		newQueryableStateChildren.setUniqueTokens(state.uniqueTokens());
		newQueryableStateChildren.setUniqueTokenAssociations(state.uniqueTokenAssociations());
		newQueryableStateChildren.setUniqueOwnershipAssociations(state.uniqueOwnershipAssociations());

		queryableState.set(newQueryableStateChildren);
	}

	/**
	 * Update the working state when given the state
	 *
	 * @param state
	 * 		to set for the working state
	 */
	private void updateWorkingState(ServicesState state) {
		workingState.setAccounts(state.accounts());
		workingState.setTopics(state.topics());
		workingState.setStorage(state.storage());
		workingState.setTokens(state.tokens());
		workingState.setTokenAssociations(state.tokenAssociations());
		workingState.setSchedules(state.scheduleTxs());
		workingState.setNetworkCtx(state.networkCtx());
		workingState.setAddressBook(state.addressBook());
		workingState.setDiskFs(state.diskFs());
		workingState.setUniqueTokens(state.uniqueTokens());
		workingState.setUniqueTokenAssociations(state.uniqueTokenAssociations());
		workingState.setUniqueOwnershipAssociations(state.uniqueOwnershipAssociations());
	}

	public SwirldDualState getDualState() {
		return dualState;
	}

	public void setDualState(SwirldDualState dualState) {
		this.dualState = dualState;
	}

	public void rebuildBackingStoresIfPresent() {
		if (backingTokenRels != null) {
			backingTokenRels.rebuildFromSources();
		}
		if (backingAccounts != null) {
			backingAccounts.rebuildFromSources();
		}
		if (backingNfts != null) {
			backingNfts.rebuildFromSources();
		}
	}

	public void rebuildStoreViewsIfPresent() {
		if (scheduleStore != null) {
			scheduleStore.rebuildViews();
		}
		if (tokenStore != null) {
			tokenStore.rebuildViews();
		}
	}

	public NonBlockingHandoff nonBlockingHandoff() {
		if (nonBlockingHandoff == null) {
			nonBlockingHandoff = new NonBlockingHandoff(recordStreamManager(), nodeLocalProperties());
		}
		return nonBlockingHandoff;
	}

	public TransitionRunner transitionRunner() {
		if (transitionRunner == null) {
			transitionRunner = new TransitionRunner(txnCtx(), transitionLogic());
		}
		return transitionRunner;
	}

	public HapiOpCounters opCounters() {
		if (opCounters == null) {
			opCounters = new HapiOpCounters(new CounterFactory() {
			}, runningAvgs(), txnCtx(), MiscUtils::baseStatNameOf);
		}
		return opCounters;
	}

	public QueryHeaderValidity queryHeaderValidity() {
		if (queryHeaderValidity == null) {
			queryHeaderValidity = new QueryHeaderValidity();
		}
		return queryHeaderValidity;
	}

	public MiscRunningAvgs runningAvgs() {
		if (runningAvgs == null) {
			runningAvgs = new MiscRunningAvgs(new RunningAvgFactory() {
			}, nodeLocalProperties());
		}
		return runningAvgs;
	}

	public TransactionPrecheck transactionPrecheck() {
		if (transactionPrecheck == null) {
			final var structure = new StructuralPrecheck(
					Platform.getTransactionMaxBytes(), HISTORICAL_MAX_PROTO_MESSAGE_DEPTH);
			final var semantics = new SemanticPrecheck(
					transitionLogic());
			final var solvency = new SolvencyPrecheck(
					exemptions(), fees(), validator(),
					precheckVerifier(), stateViews(), globalDynamicProperties(), this::accounts);
			final var system = new SystemPrecheck(
					systemOpPolicies(), hapiOpPermissions(), txnThrottling());
			final var stagedChecks = new StagedPrechecks(
					syntaxPrecheck(), system, semantics, solvency, structure);
			transactionPrecheck = new TransactionPrecheck(queryFeeCheck(), stagedChecks, platformStatus());
		}
		return transactionPrecheck;
	}

	public PricedUsageCalculator pricedUsageCalculator() {
		if (pricedUsageCalculator == null) {
			pricedUsageCalculator = new PricedUsageCalculator(
					accessorBasedUsages(),
					feeMultiplierSource(),
					new OverflowCheckingCalc());
		}
		return pricedUsageCalculator;
	}

	public FeeMultiplierSource feeMultiplierSource() {
		if (feeMultiplierSource == null) {
			feeMultiplierSource = new TxnRateFeeMultiplierSource(globalDynamicProperties(), handleThrottling());
		}
		return feeMultiplierSource;
	}

	public MiscSpeedometers speedometers() {
		if (speedometers == null) {
			speedometers = new MiscSpeedometers(new SpeedometerFactory() {
			}, nodeLocalProperties());
		}
		return speedometers;
	}

	public FunctionalityThrottling hapiThrottling() {
		if (hapiThrottling == null) {
			hapiThrottling = new HapiThrottling(new DeterministicThrottling(() -> addressBook().getSize()));
		}
		return hapiThrottling;
	}

	public FunctionalityThrottling handleThrottling() {
		if (handleThrottling == null) {
			handleThrottling = new TxnAwareHandleThrottling(txnCtx(), new DeterministicThrottling(() -> 1));
		}
		return handleThrottling;
	}

	public ImpliedTransfersMarshal impliedTransfersMarshal() {
		if (impliedTransfersMarshal == null) {
			impliedTransfersMarshal = new ImpliedTransfersMarshal(globalDynamicProperties(), transferSemanticChecks(),
					customFeeSchedules());
		}
		return impliedTransfersMarshal;
	}

	private CustomFeeSchedules customFeeSchedules() {
		if (activeCustomFeeSchedules == null) {
			activeCustomFeeSchedules = new FcmCustomFeeSchedules(this::tokens);
		}
		return activeCustomFeeSchedules;
	}

	public AwareNodeDiligenceScreen nodeDiligenceScreen() {
		if (nodeDiligenceScreen == null) {
			nodeDiligenceScreen = new AwareNodeDiligenceScreen(validator(), txnCtx(), backingAccounts());
		}
		return nodeDiligenceScreen;
	}

	public SemanticVersions semVers() {
		if (semVers == null) {
			semVers = new SemanticVersions();
		}
		return semVers;
	}

	public ServicesStatsManager statsManager() {
		if (statsManager == null) {
			var opSpeedometers = new HapiOpSpeedometers(
					opCounters(),
					new SpeedometerFactory() {
					},
					nodeLocalProperties(),
					MiscUtils::baseStatNameOf);
			statsManager = new ServicesStatsManager(
					opCounters(),
					runningAvgs(),
					speedometers(),
					opSpeedometers,
					nodeLocalProperties());
		}
		return statsManager;
	}

	public CurrentPlatformStatus platformStatus() {
		if (platformStatus == null) {
			platformStatus = new ContextPlatformStatus();
		}
		return platformStatus;
	}

	public LedgerValidator ledgerValidator() {
		if (ledgerValidator == null) {
			ledgerValidator = new BasedLedgerValidator(hederaNums(), properties(), globalDynamicProperties());
		}
		return ledgerValidator;
	}

	public InHandleActivationHelper activationHelper() {
		if (activationHelper == null) {
			activationHelper = new InHandleActivationHelper(characteristics(), txnCtx()::accessor);
		}
		return activationHelper;
	}

	public NodeInfo nodeInfo() {
		if (nodeInfo == null) {
			nodeInfo = new NodeInfo(id.getId(), this::addressBook);
		}
		return nodeInfo;
	}

	public InvariantChecks invariants() {
		if (invariantChecks == null) {
			invariantChecks = new InvariantChecks(nodeInfo(), this::networkCtx);
		}
		return invariantChecks;
	}

	public ScheduleExecutor scheduleExecutor() {
		if (scheduleExecutor == null) {
			scheduleExecutor = new ScheduleExecutor();
		}
		return scheduleExecutor;
	}

	public IssEventInfo issEventInfo() {
		if (issEventInfo == null) {
			issEventInfo = new IssEventInfo(properties());
		}
		return issEventInfo;
	}

	public Map<String, byte[]> blobStore() {
		if (blobStore == null) {
			blobStore = new FcBlobsBytesStore(MerkleOptionalBlob::new, this::storage);
		}
		return blobStore;
	}

	public Supplier<StateView> stateViews() {
		if (stateViews == null) {
			stateViews = () -> new StateView(
					tokenStore(),
					scheduleStore(),
					() -> queryableState.get().getTopics(),
					() -> queryableState.get().getAccounts(),
					() -> queryableState.get().getStorage(),
					() -> queryableState.get().getUniqueTokens(),
					() -> queryableState.get().getTokenAssociations(),
					() -> queryableState.get().getUniqueTokenAssociations(),
					() -> queryableState.get().getUniqueOwnershipAssociations(),
					this::diskFs,
					nodeLocalProperties());
		}
		return stateViews;
	}

	public StateView currentView() {
		if (currentView == null) {
			currentView = new StateView(
					tokenStore(),
					scheduleStore(),
					this::topics,
					this::accounts,
					this::storage,
					this::uniqueTokens,
					this::tokenAssociations,
					this::uniqueTokenAssociations,
					this::uniqueOwnershipAssociations,
					this::diskFs,
					nodeLocalProperties());
		}
		return currentView;
	}

	public HederaNumbers hederaNums() {
		if (hederaNums == null) {
			hederaNums = new HederaNumbers(properties());
		}
		return hederaNums;
	}

	public FileNumbers fileNums() {
		if (fileNums == null) {
			fileNums = new FileNumbers(hederaNums(), properties());
		}
		return fileNums;
	}

	public AccountNumbers accountNums() {
		if (accountNums == null) {
			accountNums = new AccountNumbers(properties());
		}
		return accountNums;
	}

	public TxnResponseHelper txnResponseHelper() {
		if (txnResponseHelper == null) {
			txnResponseHelper = new TxnResponseHelper(submissionFlow(), opCounters());
		}
		return txnResponseHelper;
	}

	public TransactionThrottling txnThrottling() {
		if (txnThrottling == null) {
			txnThrottling = new TransactionThrottling(hapiThrottling());
		}
		return txnThrottling;
	}

	public SubmissionFlow submissionFlow() {
		if (submissionFlow == null) {
			submissionFlow = new BasicSubmissionFlow(nodeType(), transactionPrecheck(), submissionManager());
		}
		return submissionFlow;
	}

	public QueryResponseHelper queryResponseHelper() {
		if (queryResponseHelper == null) {
			queryResponseHelper = new QueryResponseHelper(answerFlow(), opCounters());
		}
		return queryResponseHelper;
	}

	public FileAnswers fileAnswers() {
		if (fileAnswers == null) {
			fileAnswers = new FileAnswers(
					new GetFileInfoAnswer(validator()),
					new GetFileContentsAnswer(validator())
			);
		}
		return fileAnswers;
	}

	public ContractAnswers contractAnswers() {
		if (contractAnswers == null) {
			contractAnswers = new ContractAnswers(
					new GetBytecodeAnswer(validator()),
					new GetContractInfoAnswer(validator()),
					new GetBySolidityIdAnswer(),
					new GetContractRecordsAnswer(validator()),
					new ContractCallLocalAnswer(contracts()::contractCallLocal, validator())
			);
		}
		return contractAnswers;
	}

	public HcsAnswers hcsAnswers() {
		if (hcsAnswers == null) {
			hcsAnswers = new HcsAnswers(
					new GetTopicInfoAnswer(validator())
			);
		}
		return hcsAnswers;
	}

	/**
	 * Returns the singleton {@link TypedTokenStore} used in {@link ServicesState#handleTransaction(long, boolean,
	 * Instant, Instant, SwirldTransaction, SwirldDualState)} to load, save, and create tokens in the Swirlds
	 * application state. It decouples the {@code handleTransaction} logic from the details of the Merkle state.
	 *
	 * Here "singleton" means that, no matter how many fast-copies are made of the {@link ServicesState}, the mutable
	 * instance receiving the {@code handleTransaction} call will always use the same {@code typedTokenStore} instance.
	 *
	 * Hence we inject the {@code typedTokenStore} with method references to {@link ServicesContext#tokens()} and
	 * {@link ServicesContext#tokenAssociations()} so it can always access the children of the mutable
	 * {@link ServicesState}.
	 *
	 * @return the singleton TypedTokenStore
	 */
	public TypedTokenStore typedTokenStore() {
		if (typedTokenStore == null) {
			typedTokenStore = new TypedTokenStore(
					accountStore(),
					new TransactionRecordService(txnCtx()),
					this::tokens,
					this::uniqueTokens,
					this::uniqueOwnershipAssociations,
					this::uniqueTokenAssociations,
					this::tokenAssociations,
					(BackingTokenRels) backingTokenRels(),
					backingNfts());
		}
		return typedTokenStore;
	}

	/**
	 * Returns the singleton {@link AccountStore} used in {@link ServicesState#handleTransaction(long, boolean, Instant,
	 * Instant, SwirldTransaction, SwirldDualState)} to load, save, and create accounts from the Swirlds application
	 * state. It decouples the {@code handleTransaction} logic from the details of the Merkle state.
	 *
	 * @return the singleton accounts store
	 */
	public AccountStore accountStore() {
		if (accountStore == null) {
			accountStore = new AccountStore(validator(), globalDynamicProperties(), this::accounts);
		}
		return accountStore;
	}

	public MetaAnswers metaAnswers() {
		if (metaAnswers == null) {
			metaAnswers = new MetaAnswers(
					new GetTxnRecordAnswer(recordCache(), validator(), answerFunctions()),
					new GetTxnReceiptAnswer(recordCache()),
					new GetVersionInfoAnswer(semVers()),
					new GetFastTxnRecordAnswer()
			);
		}
		return metaAnswers;
	}

	public EntityNumbers entityNums() {
		if (entityNums == null) {
			entityNums = new EntityNumbers(fileNums(), hederaNums(), accountNums());
		}
		return entityNums;
	}

	public TokenAnswers tokenAnswers() {
		if (tokenAnswers == null) {
			tokenAnswers = new TokenAnswers(
					new GetTokenInfoAnswer(),
					new GetTokenNftInfoAnswer(),
					new GetTokenNftInfosAnswer(validator()),
					new GetAccountNftInfosAnswer(validator())
			);
		}
		return tokenAnswers;
	}

	public ScheduleAnswers scheduleAnswers() {
		if (scheduleAnswers == null) {
			scheduleAnswers = new ScheduleAnswers(
					new GetScheduleInfoAnswer()
			);
		}
		return scheduleAnswers;
	}

	public CryptoAnswers cryptoAnswers() {
		if (cryptoAnswers == null) {
			cryptoAnswers = new CryptoAnswers(
					new GetLiveHashAnswer(),
					new GetStakersAnswer(),
					new GetAccountInfoAnswer(validator()),
					new GetAccountBalanceAnswer(validator()),
					new GetAccountRecordsAnswer(answerFunctions(), validator())
			);
		}
		return cryptoAnswers;
	}

	public AnswerFunctions answerFunctions() {
		if (answerFunctions == null) {
			answerFunctions = new AnswerFunctions();
		}
		return answerFunctions;
	}

	public QueryFeeCheck queryFeeCheck() {
		if (queryFeeCheck == null) {
			queryFeeCheck = new QueryFeeCheck(validator(), globalDynamicProperties(), this::accounts);
		}
		return queryFeeCheck;
	}

	public FeeCalculator fees() {
		if (fees == null) {
			FileOpsUsage fileOpsUsage = new FileOpsUsage();
			CryptoOpsUsage cryptoOpsUsage = new CryptoOpsUsage();
			FileFeeBuilder fileFees = new FileFeeBuilder();
			CryptoFeeBuilder cryptoFees = new CryptoFeeBuilder();
			ScheduleOpsUsage scheduleOpsUsage = new ScheduleOpsUsage();
			SmartContractFeeBuilder contractFees = new SmartContractFeeBuilder();

			fees = new UsageBasedFeeCalculator(
					new AutoRenewCalcs(cryptoOpsUsage),
					exchange(),
					usagePrices(),
					feeMultiplierSource(),
					pricedUsageCalculator(),
					List.of(
							/* Meta */
							new GetVersionInfoResourceUsage(),
							new GetTxnRecordResourceUsage(recordCache(), answerFunctions(), cryptoFees),
							/* Crypto */
							new GetAccountInfoResourceUsage(cryptoOpsUsage),
							new GetAccountRecordsResourceUsage(answerFunctions(), cryptoFees),
							/* File */
							new GetFileInfoResourceUsage(fileOpsUsage),
							new GetFileContentsResourceUsage(fileFees),
							/* Consensus */
							new GetTopicInfoResourceUsage(),
							/* Smart Contract */
							new GetBytecodeResourceUsage(contractFees),
							new GetContractInfoResourceUsage(),
							new GetContractRecordsResourceUsage(contractFees),
							new ContractCallLocalResourceUsage(
									contracts()::contractCallLocal, contractFees, globalDynamicProperties()),
							/* Token */
							new GetTokenInfoResourceUsage(),
							/* Schedule */
							new GetScheduleInfoResourceUsage(scheduleOpsUsage),
							/* NftInfo */
							new GetTokenNftInfoResourceUsage(),
							new GetTokenNftInfosResourceUsage(),
							new GetAccountNftInfosResourceUsage()
					),
					txnUsageEstimators(
							cryptoOpsUsage, fileOpsUsage, fileFees, cryptoFees, contractFees, scheduleOpsUsage)
			);
		}
		return fees;
	}

	private Function<HederaFunctionality, List<TxnResourceUsageEstimator>> txnUsageEstimators(
			CryptoOpsUsage cryptoOpsUsage,
			FileOpsUsage fileOpsUsage,
			FileFeeBuilder fileFees,
			CryptoFeeBuilder cryptoFees,
			SmartContractFeeBuilder contractFees,
			ScheduleOpsUsage scheduleOpsUsage
	) {
		var props = globalDynamicProperties();

		Map<HederaFunctionality, List<TxnResourceUsageEstimator>> estimatorsMap = Map.ofEntries(
				/* Crypto */
				entry(CryptoCreate, List.of(new CryptoCreateResourceUsage(cryptoOpsUsage))),
				entry(CryptoDelete, List.of(new CryptoDeleteResourceUsage(cryptoFees))),
				entry(CryptoUpdate, List.of(new CryptoUpdateResourceUsage(cryptoOpsUsage))),
				/* Contract */
				entry(ContractCall, List.of(new ContractCallResourceUsage(contractFees))),
				entry(ContractCreate, List.of(new ContractCreateResourceUsage(contractFees))),
				entry(ContractDelete, List.of(new ContractDeleteResourceUsage(contractFees))),
				entry(ContractUpdate, List.of(new ContractUpdateResourceUsage(contractFees))),
				/* File */
				entry(FileCreate, List.of(new FileCreateResourceUsage(fileOpsUsage))),
				entry(FileDelete, List.of(new FileDeleteResourceUsage(fileFees))),
				entry(FileUpdate, List.of(new FileUpdateResourceUsage(fileOpsUsage))),
				entry(FileAppend, List.of(new FileAppendResourceUsage(fileFees))),
				/* Consensus */
				entry(ConsensusCreateTopic, List.of(new CreateTopicResourceUsage())),
				entry(ConsensusUpdateTopic, List.of(new UpdateTopicResourceUsage())),
				entry(ConsensusDeleteTopic, List.of(new DeleteTopicResourceUsage())),
				/* Token */
				entry(TokenCreate, List.of(new TokenCreateResourceUsage())),
				entry(TokenUpdate, List.of(new TokenUpdateResourceUsage())),
				// TODO: add resourceUsage of TokenFeeScheduleUpdate to estimatorsMap
				entry(TokenFreezeAccount, List.of(new TokenFreezeResourceUsage())),
				entry(TokenUnfreezeAccount, List.of(new TokenUnfreezeResourceUsage())),
				entry(TokenGrantKycToAccount, List.of(new TokenGrantKycResourceUsage())),
				entry(TokenRevokeKycFromAccount, List.of(new TokenRevokeKycResourceUsage())),
				entry(TokenDelete, List.of(new TokenDeleteResourceUsage())),
				entry(TokenMint, List.of(new TokenMintResourceUsage())),
				entry(TokenBurn, List.of(new TokenBurnResourceUsage())),
				entry(TokenAccountWipe, List.of(new TokenWipeResourceUsage())),
				entry(TokenAssociateToAccount, List.of(new TokenAssociateResourceUsage())),
				entry(TokenDissociateFromAccount, List.of(new TokenDissociateResourceUsage())),
				/* Schedule */
				entry(ScheduleCreate, List.of(new ScheduleCreateResourceUsage(scheduleOpsUsage, props))),
				entry(ScheduleDelete, List.of(new ScheduleDeleteResourceUsage(scheduleOpsUsage, props))),
				entry(ScheduleSign, List.of(new ScheduleSignResourceUsage(scheduleOpsUsage, props))),
				/* System */
				entry(Freeze, List.of(new FreezeResourceUsage())),
				entry(SystemDelete, List.of(new SystemDeleteFileResourceUsage(fileFees))),
				entry(SystemUndelete, List.of(new SystemUndeleteFileResourceUsage(fileFees)))
		);

		return estimatorsMap::get;
	}

	public AnswerFlow answerFlow() {
		if (answerFlow == null) {
			if (nodeType() == STAKED_NODE) {
				answerFlow = new StakedAnswerFlow(
						fees(),
						stateViews(),
						usagePrices(),
						hapiThrottling(),
						submissionManager(),
						queryHeaderValidity(),
						transactionPrecheck(),
						hapiOpPermissions(),
						queryFeeCheck());
			} else {
				answerFlow = new ZeroStakeAnswerFlow(queryHeaderValidity(), stateViews(), hapiThrottling());
			}
		}
		return answerFlow;
	}

	public HederaSigningOrder keyOrder() {
		if (keyOrder == null) {
			var lookups = defaultLookupsFor(
					hfs(),
					this::accounts,
					this::topics,
					REF_LOOKUP_FACTORY.apply(tokenStore()),
					SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore()));
			keyOrder = keyOrderWith(lookups);
		}
		return keyOrder;
	}

	public HederaSigningOrder backedKeyOrder() {
		if (backedKeyOrder == null) {
			var lookups = backedLookupsFor(
					hfs(),
					backingAccounts(),
					this::topics,
					this::accounts,
					REF_LOOKUP_FACTORY.apply(tokenStore()),
					SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore()));
			backedKeyOrder = keyOrderWith(lookups);
		}
		return backedKeyOrder;
	}

	public HederaSigningOrder lookupRetryingKeyOrder() {
		if (lookupRetryingKeyOrder == null) {
			var lookups = defaultAccountRetryingLookupsFor(
					hfs(),
					nodeLocalProperties(),
					this::accounts,
					this::topics,
					REF_LOOKUP_FACTORY.apply(tokenStore()),
					SCHEDULE_REF_LOOKUP_FACTORY.apply(scheduleStore()),
					runningAvgs(),
					speedometers());
			lookupRetryingKeyOrder = keyOrderWith(lookups);
		}
		return lookupRetryingKeyOrder;
	}

	public ServicesNodeType nodeType() {
		if (nodeType == null) {
			nodeType = (address().getStake() > 0) ? STAKED_NODE : ZERO_STAKE_NODE;
		}
		return nodeType;
	}

	private HederaSigningOrder keyOrderWith(DelegatingSigMetadataLookup lookups) {
		var policies = systemOpPolicies();
		var properties = globalDynamicProperties();
		return new HederaSigningOrder(
				entityNums(),
				lookups,
				txn -> policies.check(txn, CryptoUpdate) != AUTHORIZED,
				(txn, function) -> policies.check(txn, function) != AUTHORIZED,
				properties);
	}

	public StoragePersistence storagePersistence() {
		if (storagePersistence == null) {
			storagePersistence = new BlobStoragePersistence(storageMapFrom(blobStore()));
		}
		return storagePersistence;
	}

	public SyncVerifier syncVerifier() {
		if (syncVerifier == null) {
			syncVerifier = platform().getCryptography()::verifySync;
		}
		return syncVerifier;
	}

	public PrecheckVerifier precheckVerifier() {
		if (precheckVerifier == null) {
			Predicate<TransactionBody> isQueryPayment = queryPaymentTestFor(effectiveNodeAccount());
			PrecheckKeyReqs reqs = new PrecheckKeyReqs(keyOrder(), lookupRetryingKeyOrder(), isQueryPayment);
			precheckVerifier = new PrecheckVerifier(syncVerifier(), reqs, TxnAccessor::getPkToSigsFn);
		}
		return precheckVerifier;
	}

	public PrintStream consoleOut() {
		return Optional.ofNullable(console()).map(c -> c.out).orElse(null);
	}

	public BalancesExporter balancesExporter() {
		if (balancesExporter == null) {
			balancesExporter = new SignedStateBalancesExporter(
					properties(),
					platform()::sign,
					globalDynamicProperties());
		}
		return balancesExporter;
	}

	public Map<EntityId, Long> entityExpiries() {
		if (entityExpiries == null) {
			entityExpiries = EntityExpiryMapFactory.entityExpiryMapFrom(blobStore());
		}
		return entityExpiries;
	}

	public HederaFs hfs() {
		if (hfs == null) {
			hfs = new TieredHederaFs(
					ids(),
					globalDynamicProperties(),
					txnCtx()::consensusTime,
					DataMapFactory.dataMapFrom(blobStore()),
					MetadataMapFactory.metaMapFrom(blobStore()),
					this::getCurrentSpecialFileSystem);
			hfs.register(feeSchedulesManager());
			hfs.register(exchangeRatesManager());
			hfs.register(apiPermissionsReloading());
			hfs.register(applicationPropertiesReloading());
			hfs.register(throttleDefsManager());
		}
		return hfs;
	}

	/**
	 * Get the current special file system from working state disk fs
	 *
	 * @return current working state disk fs
	 */
	MerkleDiskFs getCurrentSpecialFileSystem() {
		return this.workingState.getDiskFs();
	}

	public SoliditySigsVerifier soliditySigsVerifier() {
		if (soliditySigsVerifier == null) {
			soliditySigsVerifier = new TxnAwareSoliditySigsVerifier(
					syncVerifier(),
					txnCtx(),
					StandardSyncActivationCheck::allKeysAreActive,
					this::accounts);
		}
		return soliditySigsVerifier;
	}

	public FileUpdateInterceptor applicationPropertiesReloading() {
		if (applicationPropertiesReloading == null) {
			var propertiesCb = sysFileCallbacks().propertiesCb();
			applicationPropertiesReloading = new ValidatingCallbackInterceptor(
					0,
					"files.networkProperties",
					properties(),
					contents -> propertiesCb.accept(uncheckedParse(contents)),
					ConfigListUtils::isConfigList
			);
		}
		return applicationPropertiesReloading;
	}

	public FileUpdateInterceptor apiPermissionsReloading() {
		if (apiPermissionsReloading == null) {
			var permissionsCb = sysFileCallbacks().permissionsCb();
			apiPermissionsReloading = new ValidatingCallbackInterceptor(
					0,
					"files.hapiPermissions",
					properties(),
					contents -> permissionsCb.accept(uncheckedParse(contents)),
					ConfigListUtils::isConfigList
			);
		}
		return apiPermissionsReloading;
	}

	public TransitionLogicLookup transitionLogic() {
		if (transitionLogic == null) {
			transitionLogic = new TransitionLogicLookup(transitions());
		}
		return transitionLogic;
	}

	private Function<HederaFunctionality, List<TransitionLogic>> transitions() {
		final var spanMapAccessor = new ExpandHandleSpanMapAccessor();

		Map<HederaFunctionality, List<TransitionLogic>> transitionsMap = Map.ofEntries(
				/* Crypto */
				entry(CryptoCreate,
						List.of(new CryptoCreateTransitionLogic(ledger(), validator(), txnCtx()))),
				entry(CryptoUpdate,
						List.of(new CryptoUpdateTransitionLogic(ledger(), validator(), txnCtx()))),
				entry(CryptoDelete,
						List.of(new CryptoDeleteTransitionLogic(ledger(), txnCtx()))),
				entry(CryptoTransfer,
						List.of(new CryptoTransferTransitionLogic(
								ledger(),
								txnCtx(),
								globalDynamicProperties(),
								impliedTransfersMarshal(),
								transferSemanticChecks(),
								spanMapAccessor))),
				/* File */
				entry(FileUpdate,
						List.of(new FileUpdateTransitionLogic(hfs(), entityNums(), validator(), txnCtx()))),
				entry(FileCreate,
						List.of(new FileCreateTransitionLogic(hfs(), validator(), txnCtx()))),
				entry(FileDelete,
						List.of(new FileDeleteTransitionLogic(hfs(), txnCtx()))),
				entry(FileAppend,
						List.of(new FileAppendTransitionLogic(hfs(), txnCtx()))),
				/* Contract */
				entry(ContractCreate,
						List.of(new ContractCreateTransitionLogic(
								hfs(), contracts()::createContract, this::seqNo, validator(), txnCtx()))),
				entry(ContractUpdate,
						List.of(new ContractUpdateTransitionLogic(
								ledger(), validator(), txnCtx(), new UpdateCustomizerFactory(), this::accounts))),
				entry(ContractDelete,
						List.of(new ContractDeleteTransitionLogic(
								ledger(), contracts()::deleteContract, validator(), txnCtx(), this::accounts))),
				entry(ContractCall,
						List.of(new ContractCallTransitionLogic(
								contracts()::contractCall, validator(), txnCtx(), this::seqNo, this::accounts))),
				/* Consensus */
				entry(ConsensusCreateTopic,
						List.of(new TopicCreateTransitionLogic(
								this::accounts, this::topics, ids(), validator(), txnCtx(), ledger()))),
				entry(ConsensusUpdateTopic,
						List.of(new TopicUpdateTransitionLogic(
								this::accounts, this::topics, validator(), txnCtx(), ledger()))),
				entry(ConsensusDeleteTopic,
						List.of(new TopicDeleteTransitionLogic(
								this::topics, validator(), txnCtx()))),
				entry(ConsensusSubmitMessage,
						List.of(new SubmitMessageTransitionLogic(
								this::topics, validator(), txnCtx(), globalDynamicProperties()))),
				/* Token */
				entry(TokenCreate,
						List.of(new TokenCreateTransitionLogic(validator(), tokenStore(), ledger(), txnCtx()))),
				entry(TokenUpdate,
						List.of(new TokenUpdateTransitionLogic(
								validator(), tokenStore(), ledger(), txnCtx(), HederaTokenStore::affectsExpiryAtMost))),
				entry(TokenFeeScheduleUpdate, List.of(new TokenFeeScheduleUpdateTransitionLogic(tokenStore(), txnCtx(),
						validator, globalDynamicProperties()))),
				entry(TokenFreezeAccount,
						List.of(new TokenFreezeTransitionLogic(tokenStore(), ledger(), txnCtx()))),
				entry(TokenUnfreezeAccount,
						List.of(new TokenUnfreezeTransitionLogic(tokenStore(), ledger(), txnCtx()))),
				entry(TokenGrantKycToAccount,
						List.of(new TokenGrantKycTransitionLogic(tokenStore(), ledger(), txnCtx()))),
				entry(TokenRevokeKycFromAccount,
						List.of(new TokenRevokeKycTransitionLogic(tokenStore(), ledger(), txnCtx()))),
				entry(TokenDelete,
						List.of(new TokenDeleteTransitionLogic(tokenStore(), txnCtx()))),
				entry(TokenMint,
						List.of(new TokenMintTransitionLogic(validator(), accountStore(), typedTokenStore(),
								txnCtx()))),
				entry(TokenBurn,
						List.of(new TokenBurnTransitionLogic(validator(), accountStore(), typedTokenStore(),
								txnCtx()))),
				entry(TokenAccountWipe,
						List.of(new TokenWipeTransitionLogic(validator(), typedTokenStore(), accountStore(),
								txnCtx()))),
				entry(TokenAssociateToAccount,
						List.of(new TokenAssociateTransitionLogic(
								accountStore(), typedTokenStore(), txnCtx(), globalDynamicProperties()))),
				entry(TokenDissociateFromAccount,
						List.of(new TokenDissociateTransitionLogic(tokenStore(), txnCtx()))),
				/* Schedule */
				entry(ScheduleCreate,
						List.of(new ScheduleCreateTransitionLogic(
								scheduleStore(), txnCtx(), activationHelper(), validator(), scheduleExecutor()))),
				entry(ScheduleSign,
						List.of(new ScheduleSignTransitionLogic(
								scheduleStore(), txnCtx(), activationHelper(), scheduleExecutor()))),
				entry(ScheduleDelete,
						List.of(new ScheduleDeleteTransitionLogic(scheduleStore(), txnCtx()))),
				/* System */
				entry(SystemDelete,
						List.of(
								new FileSysDelTransitionLogic(hfs(), entityExpiries(), txnCtx()),
								new ContractSysDelTransitionLogic(
										validator(), txnCtx(), contracts()::systemDelete, this::accounts))),
				entry(SystemUndelete,
						List.of(
								new FileSysUndelTransitionLogic(hfs(), entityExpiries(), txnCtx()),
								new ContractSysUndelTransitionLogic(
										validator(), txnCtx(), contracts()::systemUndelete, this::accounts))),
				/* Network */
				entry(Freeze,
						List.of(new FreezeTransitionLogic(fileNums(), freeze()::freeze, txnCtx()))),
				entry(UncheckedSubmit,
						List.of(new UncheckedSubmitTransitionLogic()))
		);
		return transitionsMap::get;
	}

	public EntityIdSource ids() {
		if (ids == null) {
			ids = new SeqNoEntityIdSource(this::seqNo);
		}
		return ids;
	}

	public TransactionContext txnCtx() {
		if (txnCtx == null) {
			txnCtx = new AwareTransactionContext(this);
		}
		return txnCtx;
	}

	public Map<TransactionID, TxnIdRecentHistory> txnHistories() {
		if (txnHistories == null) {
			txnHistories = new ConcurrentHashMap<>();
		}
		return txnHistories;
	}

	public RecordCache recordCache() {
		if (recordCache == null) {
			recordCache = new RecordCache(
					this,
					new RecordCacheFactory(properties()).getRecordCache(),
					txnHistories());
		}
		return recordCache;
	}

	public CharacteristicsFactory characteristics() {
		if (characteristics == null) {
			characteristics = new CharacteristicsFactory(hfs());
		}
		return characteristics;
	}

	public AccountRecordsHistorian recordsHistorian() {
		if (recordsHistorian == null) {
			recordsHistorian = new TxnAwareRecordsHistorian(recordCache(), txnCtx(), expiries());
		}
		return recordsHistorian;
	}

	public FeeExemptions exemptions() {
		if (exemptions == null) {
			exemptions = new StandardExemptions(accountNums(), systemOpPolicies());
		}
		return exemptions;
	}

	public HbarCentExchange exchange() {
		if (exchange == null) {
			exchange = new AwareHbarCentExchange(txnCtx());
		}
		return exchange;
	}

	public BackingStore<Pair<AccountID, TokenID>, MerkleTokenRelStatus> backingTokenRels() {
		if (backingTokenRels == null) {
			backingTokenRels = new BackingTokenRels(this::tokenAssociations);
		}
		return backingTokenRels;
	}

	public BackingStore<AccountID, MerkleAccount> backingAccounts() {
		if (backingAccounts == null) {
			backingAccounts = new BackingAccounts(this::accounts);
		}
		return backingAccounts;
	}

	public NodeLocalProperties nodeLocalProperties() {
		if (nodeLocalProperties == null) {
			nodeLocalProperties = new NodeLocalProperties(properties());
		}
		return nodeLocalProperties;
	}

	public GlobalDynamicProperties globalDynamicProperties() {
		if (globalDynamicProperties == null) {
			globalDynamicProperties = new GlobalDynamicProperties(hederaNums(), properties());
		}
		return globalDynamicProperties;
	}

	public BackingNfts backingNfts() {
		if (backingNfts == null) {
			backingNfts = new BackingNfts(this::uniqueTokens);
		}
		return backingNfts;
	}

	public TokenStore tokenStore() {
		if (tokenStore == null) {
			TransactionalLedger<NftId, NftProperty, MerkleUniqueToken> nftsLedger =
					new TransactionalLedger<>(
							NftProperty.class,
							MerkleUniqueToken::new,
							backingNfts(),
							new ChangeSummaryManager<>());
			TransactionalLedger<Pair<AccountID, TokenID>, TokenRelProperty, MerkleTokenRelStatus> tokenRelsLedger =
					new TransactionalLedger<>(
							TokenRelProperty.class,
							MerkleTokenRelStatus::new,
							backingTokenRels(),
							new ChangeSummaryManager<>());
			tokenRelsLedger.setKeyToString(BackingTokenRels::readableTokenRel);
			tokenStore = new HederaTokenStore(
					ids(),
					validator(),
					globalDynamicProperties(),
					this::tokens,
					this::uniqueOwnershipAssociations,
					tokenRelsLedger,
					nftsLedger);
		}
		return tokenStore;
	}

	public ScheduleStore scheduleStore() {
		if (scheduleStore == null) {
			scheduleStore = new HederaScheduleStore(globalDynamicProperties(), ids(), txnCtx(), this::schedules);
		}
		return scheduleStore;
	}

	public HederaLedger ledger() {
		if (ledger == null) {
			TransactionalLedger<AccountID, AccountProperty, MerkleAccount> accountsLedger =
					new TransactionalLedger<>(
							AccountProperty.class,
							MerkleAccount::new,
							backingAccounts(),
							new ChangeSummaryManager<>());
			ledger = new HederaLedger(
					tokenStore(),
					ids(),
					creator(),
					validator(),
					recordsHistorian(),
					globalDynamicProperties(),
					accountsLedger);
			scheduleStore().setAccountsLedger(accountsLedger);
			scheduleStore().setHederaLedger(ledger);
		}
		return ledger;
	}

	public EntityAutoRenewal entityAutoRenewal() {
		if (entityAutoRenewal == null) {
			final var helper = new RenewalHelper(
					tokenStore(), hederaNums(), globalDynamicProperties(),
					this::tokens, this::accounts, this::tokenAssociations);
			final var recordHelper = new RenewalRecordsHelper(
					this, recordStreamManager(), globalDynamicProperties());
			final var renewalProcess = new RenewalProcess(
					fees(), hederaNums(), helper, recordHelper);
			entityAutoRenewal = new EntityAutoRenewal(
					hederaNums(), renewalProcess, this,
					globalDynamicProperties(), networkCtxManager(), this::networkCtx);
		}
		return entityAutoRenewal;
	}

	public NarratedCharging narratedCharging() {
		if (narratedCharging == null) {
			narratedCharging = new NarratedLedgerCharging(
					nodeInfo(), ledger(), exemptions(), globalDynamicProperties(), this::accounts);
		}
		return narratedCharging;
	}

	public ExpiryManager expiries() {
		if (expiries == null) {
			var histories = txnHistories();
			expiries = new ExpiryManager(
					recordCache(), scheduleStore(), hederaNums(), histories, this::accounts, this::schedules);
		}
		return expiries;
	}

	public ExpiringCreations creator() {
		if (creator == null) {
			creator = new ExpiringCreations(expiries(), globalDynamicProperties(), this::accounts);
			creator.setRecordCache(recordCache());
		}
		return creator;
	}

	public OptionValidator validator() {
		if (validator == null) {
			validator = new ContextOptionValidator(
					effectiveNodeAccount(),
					properties(),
					txnCtx(),
					globalDynamicProperties());
		}
		return validator;
	}

	public ProcessLogic logic() {
		if (logic == null) {
			logic = new AwareProcessLogic(this);
		}
		return logic;
	}

	public FreezeHandler freeze() {
		if (freeze == null) {
			freeze = new FreezeHandler(hfs(), platform(), exchange(), this::getDualState);
		}
		return freeze;
	}

	public void updateFeature() {
		if (freeze != null) {
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("mac")) {
				if (platform.getSelfId().getId() == 0) {
					freeze.handleUpdateFeature();
				}
			} else {
				freeze.handleUpdateFeature();
			}
		}
	}

	public NetworkCtxManager networkCtxManager() {
		if (networkCtxManager == null) {
			networkCtxManager = new NetworkCtxManager(
					issEventInfo(),
					properties(),
					opCounters(),
					exchange(),
					systemFilesManager(),
					feeMultiplierSource(),
					globalDynamicProperties(),
					handleThrottling(),
					this::networkCtx);
		}
		return networkCtxManager;
	}

	public ThrottleDefsManager throttleDefsManager() {
		if (throttleDefsManager == null) {
			throttleDefsManager = new ThrottleDefsManager(
					fileNums(), this::addressBook, sysFileCallbacks().throttlesCb());
		}
		return throttleDefsManager;
	}

	public RecordStreamManager recordStreamManager() {
		return recordStreamManager;
	}

	/**
	 * RecordStreamManager should only be initialized after system files have been loaded,
	 * which means enableRecordStreaming has been read from file
	 */
	public void initRecordStreamManager() {
		try {
			var nodeLocalProps = nodeLocalProperties();
			var nodeScopedRecordLogDir = getRecordStreamDirectory(nodeLocalProps);
			recordStreamManager = new RecordStreamManager(
					platform,
					runningAvgs(),
					nodeLocalProps,
					nodeScopedRecordLogDir,
					getRecordsInitialHash());
		} catch (IOException | NoSuchAlgorithmException ex) {
			log.error("Fail to initialize RecordStreamManager.", ex);
		}
	}

	public FileUpdateInterceptor exchangeRatesManager() {
		if (exchangeRatesManager == null) {
			exchangeRatesManager = new TxnAwareRatesManager(
					fileNums(),
					accountNums(),
					globalDynamicProperties(),
					txnCtx(),
					this::midnightRates,
					exchange()::updateRates,
					limitPercent -> (base, proposed) -> isNormalIntradayChange(base, proposed, limitPercent));
		}
		return exchangeRatesManager;
	}

	public FileUpdateInterceptor feeSchedulesManager() {
		if (feeSchedulesManager == null) {
			feeSchedulesManager = new FeeSchedulesManager(fileNums(), fees());
		}
		return feeSchedulesManager;
	}

	public ExpandHandleSpan expandHandleSpan() {
		if (expandHandleSpan == null) {
			expandHandleSpan = new ExpandHandleSpan(10, TimeUnit.SECONDS, spanMapManager());
		}
		return expandHandleSpan;
	}

	public SpanMapManager spanMapManager() {
		if (spanMapManager == null) {
			spanMapManager = new SpanMapManager(impliedTransfersMarshal(), globalDynamicProperties(),
					customFeeSchedules());
		}
		return spanMapManager;
	}

	public FreezeController freezeGrpc() {
		if (freezeGrpc == null) {
			freezeGrpc = new FreezeController(txnResponseHelper());
		}
		return freezeGrpc;
	}

	public NetworkController networkGrpc() {
		if (networkGrpc == null) {
			networkGrpc = new NetworkController(metaAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return networkGrpc;
	}

	public FileController filesGrpc() {
		if (fileGrpc == null) {
			fileGrpc = new FileController(fileAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return fileGrpc;
	}

	public SystemOpPolicies systemOpPolicies() {
		if (systemOpPolicies == null) {
			systemOpPolicies = new SystemOpPolicies(entityNums());
		}
		return systemOpPolicies;
	}

	public TokenController tokenGrpc() {
		if (tokenGrpc == null) {
			tokenGrpc = new TokenController(tokenAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return tokenGrpc;
	}

	public ScheduleController scheduleGrpc() {
		if (scheduleGrpc == null) {
			scheduleGrpc = new ScheduleController(scheduleAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return scheduleGrpc;
	}

	public CryptoController cryptoGrpc() {
		if (cryptoGrpc == null) {
			cryptoGrpc = new CryptoController(
					metaAnswers(),
					cryptoAnswers(),
					txnResponseHelper(),
					queryResponseHelper());
		}
		return cryptoGrpc;
	}

	public ContractController contractsGrpc() {
		if (contractsGrpc == null) {
			contractsGrpc = new ContractController(contractAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return contractsGrpc;
	}

	public PlatformSubmissionManager submissionManager() {
		if (submissionManager == null) {
			submissionManager = new PlatformSubmissionManager(platform(), recordCache(), speedometers());
		}
		return submissionManager;
	}

	public AccessorBasedUsages accessorBasedUsages() {
		if (accessorBasedUsages == null) {
			final var opUsageCtxHelper = new OpUsageCtxHelper(this::tokens);
			accessorBasedUsages = new AccessorBasedUsages(
					new TokenOpsUsage(),
					new CryptoOpsUsage(),
					opUsageCtxHelper,
					new ConsensusOpsUsage(),
					globalDynamicProperties());
		}
		return accessorBasedUsages;
	}

	public ConsensusController consensusGrpc() {
		if (null == consensusGrpc) {
			consensusGrpc = new ConsensusController(hcsAnswers(), txnResponseHelper(), queryResponseHelper());
		}
		return consensusGrpc;
	}

	public GrpcServerManager grpc() {
		if (grpc == null) {
			grpc = new NettyGrpcServerManager(
					Runtime.getRuntime()::addShutdownHook,
					nodeLocalProperties(),
					List.of(
							cryptoGrpc(),
							filesGrpc(),
							freezeGrpc(),
							contractsGrpc(),
							consensusGrpc(),
							networkGrpc(),
							tokenGrpc(),
							scheduleGrpc()),
					new ConfigDrivenNettyFactory(nodeLocalProperties()),
					Collections.emptyList());
		}
		return grpc;
	}

	public PureTransferSemanticChecks transferSemanticChecks() {
		if (transferSemanticChecks == null) {
			transferSemanticChecks = new PureTransferSemanticChecks();
		}
		return transferSemanticChecks;
	}

	public SmartContractRequestHandler contracts() {
		if (contracts == null) {
			contracts = new SmartContractRequestHandler(
					repository(),
					ledger(),
					this::accounts,
					txnCtx(),
					exchange(),
					usagePrices(),
					newPureRepo(),
					solidityLifecycle(),
					soliditySigsVerifier(),
					entityExpiries(),
					globalDynamicProperties());
		}
		return contracts;
	}

	public SysFileCallbacks sysFileCallbacks() {
		if (sysFileCallbacks == null) {
			var configCallbacks = new ConfigCallbacks(
					hapiOpPermissions(),
					globalDynamicProperties(),
					(StandardizedPropertySources) propertySources());
			var currencyCallbacks = new CurrencyCallbacks(fees(), exchange(), this::midnightRates);
			var throttlesCallback = new ThrottlesCallback(feeMultiplierSource(), hapiThrottling(), handleThrottling());
			sysFileCallbacks = new SysFileCallbacks(configCallbacks, throttlesCallback, currencyCallbacks);
		}
		return sysFileCallbacks;
	}

	public SolidityLifecycle solidityLifecycle() {
		if (solidityLifecycle == null) {
			solidityLifecycle = new SolidityLifecycle(globalDynamicProperties());
		}
		return solidityLifecycle;
	}

	public PropertySource properties() {
		if (properties == null) {
			properties = propertySources().asResolvingSource();
		}
		return properties;
	}

	public SystemFilesManager systemFilesManager() {
		if (systemFilesManager == null) {
			systemFilesManager = new HfsSystemFilesManager(
					addressBook(),
					fileNums(),
					properties(),
					(TieredHederaFs) hfs(),
					() -> lookupInCustomStore(
							b64KeyReader(),
							properties.getStringProperty("bootstrap.genesisB64Keystore.path"),
							properties.getStringProperty("bootstrap.genesisB64Keystore.keyName")),
					sysFileCallbacks());
		}
		return systemFilesManager;
	}

	public HapiOpPermissions hapiOpPermissions() {
		if (hapiOpPermissions == null) {
			hapiOpPermissions = new HapiOpPermissions(accountNums());
		}
		return hapiOpPermissions;
	}

	public ServicesRepositoryRoot repository() {
		if (repository == null) {
			repository = new ServicesRepositoryRoot(accountSource(), bytecodeDb());
			repository.setStoragePersistence(storagePersistence());
		}
		return repository;
	}

	public Supplier<ServicesRepositoryRoot> newPureRepo() {
		if (newPureRepo == null) {
			TransactionalLedger<AccountID, AccountProperty, MerkleAccount> pureDelegate = new TransactionalLedger<>(
					AccountProperty.class,
					MerkleAccount::new,
					new PureBackingAccounts(this::accounts),
					new ChangeSummaryManager<>());
			HederaLedger pureLedger = new HederaLedger(
					NOOP_TOKEN_STORE,
					NOOP_ID_SOURCE,
					NOOP_EXPIRING_CREATIONS,
					validator(),
					NOOP_RECORDS_HISTORIAN,
					globalDynamicProperties(),
					pureDelegate);
			Source<byte[], AccountState> pureAccountSource = new LedgerAccountsSource(pureLedger);
			newPureRepo = () -> {
				var pureRepository = new ServicesRepositoryRoot(pureAccountSource, bytecodeDb());
				pureRepository.setStoragePersistence(storagePersistence());
				return pureRepository;
			};
		}
		return newPureRepo;
	}

	public ConsensusStatusCounts statusCounts() {
		if (statusCounts == null) {
			statusCounts = new ConsensusStatusCounts(new ObjectMapper());
		}
		return statusCounts;
	}

	public LedgerAccountsSource accountSource() {
		if (accountSource == null) {
			accountSource = new LedgerAccountsSource(ledger());
		}
		return accountSource;
	}

	public BlobStorageSource bytecodeDb() {
		if (bytecodeDb == null) {
			bytecodeDb = new BlobStorageSource(bytecodeMapFrom(blobStore()));
		}
		return bytecodeDb;
	}

	public SyntaxPrecheck syntaxPrecheck() {
		if (syntaxPrecheck == null) {
			syntaxPrecheck = new SyntaxPrecheck(recordCache(), validator(), globalDynamicProperties());
		}
		return syntaxPrecheck;
	}

	public Console console() {
		if (console == null) {
			console = platform().createConsole(true);
		}
		return console;
	}

	public Address address() {
		if (address == null) {
			address = addressBook().getAddress(id.getId());
		}
		return address;
	}

	public UsagePricesProvider usagePrices() {
		if (usagePrices == null) {
			usagePrices = new AwareFcfsUsagePrices(hfs(), fileNums(), txnCtx());
		}
		return usagePrices;
	}

	public FeeChargingPolicy txnChargingPolicy() {
		if (txnChargingPolicy == null) {
			txnChargingPolicy = new FeeChargingPolicy(narratedCharging());
		}
		return txnChargingPolicy;
	}

	public TxnChargingPolicyAgent chargingPolicyAgent() {
		if (chargingPolicyAgent == null) {
			chargingPolicyAgent = new TxnChargingPolicyAgent(
					fees(), txnChargingPolicy(), txnCtx(), this::currentView, nodeDiligenceScreen(), txnHistories());
		}
		return chargingPolicyAgent;
	}

	public SystemAccountsCreator systemAccountsCreator() {
		if (systemAccountsCreator == null) {
			systemAccountsCreator = new BackedSystemAccountsCreator(
					hederaNums(),
					accountNums(),
					properties(),
					b64KeyReader());
		}
		return systemAccountsCreator;
	}

	/* Context-free infrastructure. */
	public LegacyEd25519KeyReader b64KeyReader() {
		return b64KeyReader;
	}

	public Pause pause() {
		return pause;
	}

	public StateMigrations stateMigrations() {
		return stateMigrations;
	}

	public AccountsExporter accountsExporter() {
		return accountsExporter;
	}

	/* Injected dependencies. */
	public NodeId id() {
		return id;
	}

	public Platform platform() {
		return platform;
	}

	public PropertySources propertySources() {
		return propertySources;
	}

	/**
	 * Get consensus time of last handled transaction
	 *
	 * @return instant representing last handled transaction from working state
	 */
	public Instant consensusTimeOfLastHandledTxn() {
		return workingState.getNetworkCtx().consensusTimeOfLastHandledTxn();
	}

	public void updateConsensusTimeOfLastHandledTxn(Instant dataDrivenNow) {
		state.networkCtx().setConsensusTimeOfLastHandledTxn(dataDrivenNow);
	}

	/**
	 * Get the working state of address book
	 *
	 * @return current working state address book
	 */
	public AddressBook addressBook() {
		return workingState.getAddressBook();
	}

	/**
	 * Get the working state network ctx and extract sequence number
	 *
	 * @return sequence number from the current working state network ctx
	 */
	public SequenceNumber seqNo() {
		return workingState.getNetworkCtx().seqNo();
	}

	/**
	 * Get the working state network ctx and extract the last scanned entity
	 *
	 * @return last scanned entity from the current working state network ctx
	 */
	public long lastScannedEntity() {
		return workingState.getNetworkCtx().lastScannedEntity();
	}

	public void updateLastScannedEntity(long lastScannedEntity) {
		state.networkCtx().updateLastScannedEntity(lastScannedEntity);
	}

	/**
	 * Gets the working state of network ctx and extracts midnight rates
	 *
	 * @return current working state network ctx midnight rates
	 */
	public ExchangeRates midnightRates() {
		return workingState.getNetworkCtx().midnightRates();
	}

	/**
	 * Gets the working state of the accounts
	 *
	 * @return current working state of accounts
	 */
	public FCMap<MerkleEntityId, MerkleAccount> accounts() {
		return workingState.getAccounts();
	}

	/**
	 * Gets the working state of the topics
	 *
	 * @return current working state of topics
	 */
	public FCMap<MerkleEntityId, MerkleTopic> topics() {
		return workingState.getTopics();
	}

	/**
	 * Gets the working state of storage
	 *
	 * @return current working state of storage
	 */
	public FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage() {
		return workingState.getStorage();
	}

	/**
	 * Gets the working state of tokens
	 *
	 * @return current working state of tokens
	 */
	public FCMap<MerkleEntityId, MerkleToken> tokens() {
		return workingState.getTokens();
	}

	/**
	 * Gets the working state of token associations
	 *
	 * @return current working state of token associations
	 */
	public FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenAssociations() {
		return workingState.getTokenAssociations();
	}

	/**
	 * Gets the working state of schedules
	 *
	 * @return current working state of schedules
	 */
	public FCMap<MerkleEntityId, MerkleSchedule> schedules() {
		return workingState.getSchedules();
	}

	public FCMap<MerkleUniqueTokenId, MerkleUniqueToken> uniqueTokens() {
		return state.uniqueTokens();
	}

	public FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations() {
		return state.uniqueTokenAssociations();
	}

	public FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations() {
		return state.uniqueOwnershipAssociations();
	}

	/**
	 * Get the working state of disk fs
	 *
	 * @return current working state of disk fs
	 */
	public MerkleDiskFs diskFs() {
		return workingState.getDiskFs();
	}

	/**
	 * Get the working state of network ctx
	 *
	 * @return current working state of network ctx
	 */
	public MerkleNetworkContext networkCtx() {
		return workingState.getNetworkCtx();
	}

	/**
	 * return the directory to which record stream files should be write
	 *
	 * @param source
	 * 		the node local properties that contain the record logging directory
	 * @return the direct file folder for writing record stream files
	 */
	public String getRecordStreamDirectory(NodeLocalProperties source) {
		if (recordStreamDir == null) {
			final String nodeAccountString = asLiteralString(effectiveNodeAccount());
			String parentDir = source.recordLogDir();
			if (!parentDir.endsWith(File.separator)) {
				parentDir += File.separator;
			}
			recordStreamDir = parentDir + "record" + nodeAccountString;
		}
		return recordStreamDir;
	}

	/**
	 * update the runningHash instance saved in runningHashLeaf
	 *
	 * @param runningHash
	 * 		new runningHash instance
	 */
	public void updateRecordRunningHash(final RunningHash runningHash) {
		state.runningHashLeaf().setRunningHash(runningHash);
	}

	/**
	 * set recordsInitialHash, which will be set to RecordStreamManager as initialHash.
	 * recordsInitialHash is read at restart, either from the state's runningHashLeaf,
	 * or from the last old .rcd_sig file in migration.
	 * When recordsInitialHash is read, the RecordStreamManager might not be initialized yet,
	 * because RecordStreamManager can only be initialized after system files are loaded so that enableRecordStream
	 * setting is read.
	 * Thus we save the initialHash in the context, and use it when initializing RecordStreamManager
	 *
	 * @param recordsInitialHash
	 * 		initial running Hash of records
	 */
	public void setRecordsInitialHash(final Hash recordsInitialHash) {
		this.recordsInitialHash = recordsInitialHash;
		if (recordStreamManager() != null) {
			recordStreamManager().setInitialHash(recordsInitialHash);
		}
	}

	Hash getRecordsInitialHash() {
		return recordsInitialHash;
	}

	void setBackingTokenRels(BackingTokenRels backingTokenRels) {
		this.backingTokenRels = backingTokenRels;
	}

	public void setBackingNfts(BackingNfts backingNfts) {
		this.backingNfts = backingNfts;
	}

	void setBackingAccounts(BackingAccounts backingAccounts) {
		this.backingAccounts = backingAccounts;
	}

	public void setTokenStore(TokenStore tokenStore) {
		this.tokenStore = tokenStore;
	}

	public void setScheduleStore(ScheduleStore scheduleStore) {
		this.scheduleStore = scheduleStore;
	}

	private AccountID effectiveNodeAccount() {
		final var info = nodeInfo();
		/* If we do not have a self account, we must be zero-stake and will never process a query payment. */
		return info.hasSelfAccount() ? info.selfAccount() : AccountID.getDefaultInstance();
	}
}
