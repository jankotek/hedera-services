package com.hedera.services;

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

import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.context.ServicesContext;
import com.hedera.services.context.properties.BootstrapProperties;
import com.hedera.services.context.properties.StandardizedPropertySources;
import com.hedera.services.exceptions.ContextNotFoundException;
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
import com.hedera.services.state.submerkle.EntityId;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hedera.services.state.submerkle.SequenceNumber;
import com.hedera.services.stream.RecordsRunningHashLeaf;
import com.hederahashgraph.api.proto.java.AccountID;
import com.swirlds.blob.BinaryObjectStore;
import com.swirlds.common.AddressBook;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldState;
import com.swirlds.common.SwirldTransaction;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.ImmutableHash;
import com.swirlds.common.crypto.RunningHash;
import com.swirlds.common.merkle.MerkleNode;
import com.swirlds.common.merkle.utility.AbstractNaryMerkleInternal;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

import static com.hedera.services.context.SingletonContextsManager.CONTEXTS;
import static com.hedera.services.sigs.HederaToPlatformSigOps.expandIn;
import static com.hedera.services.state.initialization.ViewBuilder.rebuildUniqueTokenViews;
import static com.hedera.services.state.merkle.MerkleNetworkContext.UNKNOWN_CONSENSUS_TIME;
import static com.hedera.services.utils.EntityIdUtils.asLiteralString;
import static com.hedera.services.utils.EntityIdUtils.parseAccount;

public class ServicesState extends AbstractNaryMerkleInternal implements SwirldState.SwirldState2 {
	private static final Logger log = LogManager.getLogger(ServicesState.class);

	private static final ImmutableHash emptyHash = new ImmutableHash(new byte[DigestType.SHA_384.digestLength()]);

	static final int RELEASE_070_VERSION = 1;
	static final int RELEASE_080_VERSION = 2;
	static final int RELEASE_090_VERSION = 3;
	static final int RELEASE_0100_VERSION = 4;
	static final int RELEASE_0110_VERSION = 5;
	static final int RELEASE_0120_VERSION = 6;
	static final int RELEASE_0130_VERSION = 7;
	static final int RELEASE_0140_VERSION = 8;
	static final int RELEASE_0150_VERSION = 9;
	static final int RELEASE_0160_VERSION = 10;
	static final int MERKLE_VERSION = RELEASE_0160_VERSION;
	static final long RUNTIME_CONSTRUCTABLE_ID = 0x8e300b0dfdafbb1aL;

	static final String UNSUPPORTED_VERSION_MSG_TPL = "Argument 'version=%d' is invalid!";

	static Supplier<BinaryObjectStore> blobStoreSupplier = BinaryObjectStore::getInstance;

	NodeId nodeId = null;
	boolean skipDiskFsHashCheck = false;
	private FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations;
	private FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations;

	/* Order of Merkle node children */
	static class ChildIndices {
		static final int ADDRESS_BOOK = 0;
		static final int NETWORK_CTX = 1;
		static final int TOPICS = 2;
		static final int STORAGE = 3;
		static final int ACCOUNTS = 4;
		static final int NUM_070_CHILDREN = 5;
		static final int TOKENS = 5;
		static final int NUM_080_CHILDREN = 6;
		static final int TOKEN_ASSOCIATIONS = 6;
		static final int DISK_FS = 7;
		static final int NUM_090_CHILDREN = 8;
		static final int NUM_0100_CHILDREN = 8;
		static final int SCHEDULE_TXS = 8;
		static final int RECORD_STREAM_RUNNING_HASH = 9;
		static final int NUM_0110_CHILDREN = 10;
		static final int NUM_0120_CHILDREN = 10;
		static final int NUM_0130_CHILDREN = 10;
		static final int NUM_0140_CHILDREN = 10;
		static final int NUM_0150_CHILDREN = 10;
		static final int UNIQUE_TOKENS = 10;
		static final int NUM_0160_CHILDREN = 11;
	}

	ServicesContext ctx;

	public ServicesState() {
		/* RuntimeConstructable */
	}

	public ServicesState(
			ServicesContext ctx,
			NodeId nodeId,
			List<MerkleNode> children,
			FCOneToManyRelation<EntityId, MerkleUniqueTokenId> mutableUniqueTokenAssociations,
			FCOneToManyRelation<EntityId, MerkleUniqueTokenId> mutableUniqueOwnershipAssociations,
			ServicesState immutableState
	) {
		super(immutableState);
		addDeserializedChildren(children, MERKLE_VERSION);
		this.ctx = ctx;
		this.nodeId = nodeId;
		this.uniqueTokenAssociations = mutableUniqueTokenAssociations;
		this.uniqueOwnershipAssociations = mutableUniqueOwnershipAssociations;
		if (ctx != null) {
			ctx.update(this);
		}
	}

	/* --- MerkleInternal --- */
	@Override
	public long getClassId() {
		return RUNTIME_CONSTRUCTABLE_ID;
	}

	@Override
	public int getVersion() {
		return MERKLE_VERSION;
	}

	@Override
	public int getMinimumChildCount(int version) {
		switch (version) {
			case RELEASE_0160_VERSION:
				return ChildIndices.NUM_0160_CHILDREN;
			case RELEASE_0150_VERSION:
				return ChildIndices.NUM_0150_CHILDREN;
			case RELEASE_0140_VERSION:
				return ChildIndices.NUM_0140_CHILDREN;
			case RELEASE_0130_VERSION:
				return ChildIndices.NUM_0130_CHILDREN;
			case RELEASE_0120_VERSION:
				return ChildIndices.NUM_0120_CHILDREN;
			case RELEASE_0110_VERSION:
				return ChildIndices.NUM_0110_CHILDREN;
			case RELEASE_0100_VERSION:
				return ChildIndices.NUM_0100_CHILDREN;
			case RELEASE_090_VERSION:
				return ChildIndices.NUM_090_CHILDREN;
			case RELEASE_080_VERSION:
				return ChildIndices.NUM_080_CHILDREN;
			case RELEASE_070_VERSION:
				return ChildIndices.NUM_070_CHILDREN;
			default:
				throw new IllegalArgumentException(String.format(UNSUPPORTED_VERSION_MSG_TPL, version));
		}
	}

	@Override
	public void genesisInit(Platform platform, AddressBook addressBook) {
		this.init(platform, addressBook);
	}

	@Override
	public void initialize() {
		if (uniqueTokens() == null) {
			setChild(ChildIndices.UNIQUE_TOKENS, new FCMap<>());
		}
	}

	/* --- SwirldState --- */
	@Override
	public void init(Platform platform, AddressBook addressBook) {
		setImmutable(false);
		nodeId = platform.getSelfId();

		/* Note this overrides the address book from the saved state if it is present. */
		setChild(ChildIndices.ADDRESS_BOOK, addressBook);

		var bootstrapProps = new BootstrapProperties();
		var properties = new StandardizedPropertySources(bootstrapProps);
		try {
			ctx = CONTEXTS.lookup(nodeId.getId());
		} catch (ContextNotFoundException ignoreToInstantiateNewContext) {
			ctx = new ServicesContext(nodeId, platform, this, properties);
		}
		if (getNumberOfChildren() < ChildIndices.NUM_0150_CHILDREN) {
			log.info("Init called on Services node {} WITHOUT Merkle saved state", nodeId);
			long seqStart = bootstrapProps.getLongProperty("hedera.numReservedSystemEntities") + 1;
			setChild(ChildIndices.NETWORK_CTX, new MerkleNetworkContext(
					UNKNOWN_CONSENSUS_TIME,
					new SequenceNumber(seqStart),
					seqStart - 1,
					new ExchangeRates()));
			setChild(ChildIndices.TOPICS, new FCMap<>());
			setChild(ChildIndices.STORAGE, new FCMap<>());
			setChild(ChildIndices.ACCOUNTS, new FCMap<>());
			setChild(ChildIndices.TOKENS, new FCMap<>());
			setChild(ChildIndices.TOKEN_ASSOCIATIONS, new FCMap<>());
			setChild(ChildIndices.DISK_FS, new MerkleDiskFs());
			setChild(ChildIndices.SCHEDULE_TXS, new FCMap<>());
			setChild(ChildIndices.UNIQUE_TOKENS, new FCMap<MerkleUniqueTokenId, MerkleUniqueToken>());

			/* Initialize the running hash leaf at genesis to an empty hash. */
			final var firstRunningHash = new RunningHash();
			firstRunningHash.setHash(emptyHash);
			setChild(ChildIndices.RECORD_STREAM_RUNNING_HASH, new RecordsRunningHashLeaf(firstRunningHash));
		} else {
			log.info("Init called on Services node {} WITH Merkle saved state", nodeId);

			var restoredDiskFs = diskFs();
			if (networkCtx().getStateVersion() < RELEASE_0140_VERSION) {
				final long defaultLastScanned = bootstrapProps.getLongProperty("hedera.numReservedSystemEntities");
				networkCtx().updateLastScannedEntity(defaultLastScanned);
				try {
					restoredDiskFs.migrateLegacyDiskFsFromV13LocFor(
							MerkleDiskFs.DISK_FS_ROOT_DIR,
							asLiteralString(ctx.nodeInfo().selfAccount()));
				} catch (UncheckedIOException expectedNonFatal) {
					log.warn("Legacy diskFs directory not migrated, was it missing?", expectedNonFatal);
				}
			}
			if (!skipDiskFsHashCheck) {
				restoredDiskFs.checkHashesAgainstDiskContents();
			}
		}

		networkCtx().setStateVersion(MERKLE_VERSION);
		ctx.setRecordsInitialHash(runningHashLeaf().getRunningHash().getHash());

		logSummary();

		initializeContext(ctx);
		CONTEXTS.store(ctx);

		log.info("  --> Context initialized accordingly on Services node {}", nodeId);
	}

	private void initializeContext(final ServicesContext ctx) {
		/* Set the primitive state in the context and signal the managing stores (if
		 * they are already constructed) to rebuild their auxiliary views of the state.
		 * All the initialization that follows will be a function of the primitive state. */
		ctx.update(this);
		ctx.rebuildBackingStoresIfPresent();
		ctx.rebuildStoreViewsIfPresent();
		uniqueTokenAssociations = new FCOneToManyRelation<>();
		uniqueOwnershipAssociations = new FCOneToManyRelation<>();
		rebuildUniqueTokenViews(uniqueTokens(), uniqueTokenAssociations, uniqueOwnershipAssociations);

		/* Use any payer records stored in state to rebuild the recent transaction
		 * history. This history has two main uses: Purging expired records, and
		 * classifying duplicate transactions. */
		ctx.recordsHistorian().reviewExistingRecords();
		/* Use any entities stored in state to rebuild queue of expired entities. */
		ctx.expiries().reviewExistingShortLivedEntities();
		/* Re-initialize the "observable" system files; that is, the files which have
	 	associated callbacks managed by the SysFilesCallback object. We explicitly
	 	re-mark the files are not loaded here, in case this is a reconnect. (During a
	 	reconnect the blob store might still be reloading, and we will finish loading
	 	the observable files in the ServicesMain.init method.) */
		ctx.networkCtxManager().setObservableFilesNotLoaded();
		if (!blobStoreSupplier.get().isInitializing()) {
			ctx.networkCtxManager().loadObservableSysFilesIfNeeded();
		}
	}

	@Override
	public AddressBook getAddressBookCopy() {
		return addressBook().copy();
	}

	@Override
	public synchronized void handleTransaction(
			long submittingMember,
			boolean isConsensus,
			Instant creationTime,
			Instant consensusTime,
			SwirldTransaction transaction,
			SwirldDualState dualState
	) {
		if (isConsensus) {
			ctx.setDualState(dualState);
			ctx.logic().incorporateConsensusTxn(transaction, consensusTime, submittingMember);
		}
	}

	@Override
	public void expandSignatures(SwirldTransaction platformTxn) {
		try {
			final var accessor = ctx.expandHandleSpan().track(platformTxn);
			expandIn(accessor, ctx.lookupRetryingKeyOrder(), accessor.getPkToSigsFn());
		} catch (InvalidProtocolBufferException e) {
			log.warn("expandSignatures called with non-gRPC txn!", e);
		} catch (Exception race) {
			log.warn("Unexpected problem, signatures will be verified synchronously in handleTransaction!", race);
		}
	}

	@Override
	public void noMoreTransactions() {
		/* No-op. */
	}

	/* --- FastCopyable --- */
	@Override
	public synchronized ServicesState copy() {
		setImmutable(true);
		final var mutableUniqTokenAssocsIfInit =
				(uniqueTokenAssociations == null) ? null : uniqueTokenAssociations.copy();
		final var mutableOwnerAssocsIfInit =
				(uniqueOwnershipAssociations == null) ? null : uniqueOwnershipAssociations.copy();
		return new ServicesState(ctx, nodeId, List.of(
				addressBook().copy(),
				networkCtx().copy(),
				topics().copy(),
				storage().copy(),
				accounts().copy(),
				tokens().copy(),
				tokenAssociations().copy(),
				diskFs().copy(),
				scheduleTxs().copy(),
				runningHashLeaf().copy(),
				uniqueTokens().copy()
		), mutableUniqTokenAssocsIfInit, mutableOwnerAssocsIfInit, this);
	}

	/* --------------- */
	public AccountID getAccountFromNodeId(NodeId nodeId) {
		var address = addressBook().getAddress(nodeId.getId());
		var memo = address.getMemo();
		return parseAccount(memo);
	}

	public void logSummary() {
		logHashes();
		log.info(networkCtx().toString());
	}

	private void logHashes() {
		log.info(String.format("[SwirldState Hashes]\n" +
						"  Overall                :: %s\n" +
						"  Accounts               :: %s\n" +
						"  Storage                :: %s\n" +
						"  Topics                 :: %s\n" +
						"  Tokens                 :: %s\n" +
						"  TokenAssociations      :: %s\n" +
						"  DiskFs                 :: %s\n" +
						"  ScheduledTxs           :: %s\n" +
						"  NetworkContext         :: %s\n" +
						"  AddressBook            :: %s\n" +
						"  RecordsRunningHashLeaf :: %s\n" +
						"    ↪ Running hash       :: %s\n" +
						"  UniqueTokens           :: %s\n",
				getHash(),
				accounts().getHash(),
				storage().getHash(),
				topics().getHash(),
				tokens().getHash(),
				tokenAssociations().getHash(),
				diskFs().getHash(),
				scheduleTxs().getHash(),
				networkCtx().getHash(),
				addressBook().getHash(),
				runningHashLeaf().getHash(),
				runningHashLeaf().getRunningHash().getHash(),
				uniqueTokens().getHash()));
	}

	public FCMap<MerkleEntityId, MerkleAccount> accounts() {
		return getChild(ChildIndices.ACCOUNTS);
	}

	public FCMap<MerkleBlobMeta, MerkleOptionalBlob> storage() {
		return getChild(ChildIndices.STORAGE);
	}

	public FCMap<MerkleEntityId, MerkleTopic> topics() {
		return getChild(ChildIndices.TOPICS);
	}

	public FCMap<MerkleEntityId, MerkleToken> tokens() {
		return getChild(ChildIndices.TOKENS);
	}

	public FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenAssociations() {
		return getChild(ChildIndices.TOKEN_ASSOCIATIONS);
	}

	public FCMap<MerkleEntityId, MerkleSchedule> scheduleTxs() {
		return getChild(ChildIndices.SCHEDULE_TXS);
	}

	public MerkleNetworkContext networkCtx() {
		return getChild(ChildIndices.NETWORK_CTX);
	}

	public AddressBook addressBook() {
		return getChild(ChildIndices.ADDRESS_BOOK);
	}

	public MerkleDiskFs diskFs() {
		return getChild((ChildIndices.DISK_FS));
	}

	public RecordsRunningHashLeaf runningHashLeaf() {
		return getChild(ChildIndices.RECORD_STREAM_RUNNING_HASH);
	}

	public FCMap<MerkleUniqueTokenId, MerkleUniqueToken> uniqueTokens() {
		return getChild(ChildIndices.UNIQUE_TOKENS);
	}

	public FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations() {
		return uniqueTokenAssociations;
	}

	public FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations() {
		return uniqueOwnershipAssociations;
	}

	void setUniqueTokenAssociations(FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations) {
		this.uniqueTokenAssociations = uniqueTokenAssociations;
	}

	void setUniqueOwnershipAssociations(FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations) {
		this.uniqueOwnershipAssociations = uniqueOwnershipAssociations;
	}
}
