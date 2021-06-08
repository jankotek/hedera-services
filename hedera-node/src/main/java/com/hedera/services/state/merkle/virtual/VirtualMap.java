package com.hedera.services.state.merkle.virtual;

import com.hedera.services.state.merkle.virtual.persistence.ImmutableFuture;
import com.hedera.services.state.merkle.virtual.persistence.VirtualDataSource;
import com.hedera.services.state.merkle.virtual.persistence.VirtualRecord;
import com.swirlds.common.Archivable;
import com.swirlds.common.FCMElement;
import com.swirlds.common.FCMValue;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.crypto.CryptoFactory;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleExternalLeaf;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.hedera.services.state.merkle.virtual.VirtualTreePath.INVALID_PATH;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.ROOT_PATH;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.compareTo;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getBreadcrumbs;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getIndexInRank;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getLeftChildPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getParentPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getPathForRankAndIndex;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getRank;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getRightChildPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getSiblingPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.isFarRight;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.isLeft;

/**
 * A type of Merkle node that is also map-like and is designed for working with
 * data stored primarily off-heap, and pulled on-heap only as needed. It buffers
 * changes locally and flushes them to the storage on {@link #commit()}.
 *
 * <p>To achieve this, we have a "virtual" map. It does not implement any of the
 * java.util.* APIs because it is not necessary and would only add complexity and
 * overhead to the implementation. It implements a simple get and put method pair.
 * Access to the storage subsystem (typically, the filesystem) is implemented by
 * the {@link VirtualDataSource}. Because MerkleNodes must all implement
 * SerializableDet, and since SerializableDet implementations must have a no-arg
 * constructor, we cannot be certain that a VirtualMap always has a VirtualDataSource.
 * However, without one, it will not function, so please make sure the VirtualMap
 * is configured with a functioning VirtualDataSource before using it.</p>
 *
 * <p>This map <strong>does not accept null keys</strong> but does accept null values.</p>
 */
@ConstructableIgnored
public final class VirtualMap
        extends AbstractMerkleLeaf
        implements Archivable, FCMValue, MerkleExternalLeaf {

    private static final long CLASS_ID = 0xb881f3704885e853L;
    private static final int CLASS_VERSION = 1;

    private static final ThreadGroup HASHING_GROUP = new ThreadGroup("VirtualMap-Hashers");

    private static final ExecutorService HASHING_POOL = Executors.newFixedThreadPool(48, (r) -> {
        final var thread = new Thread(HASHING_GROUP, r);
        thread.setDaemon(true);
        return thread;
    });

    private static final ThreadLocal<MessageDigest> MD_LOCAL = new ThreadLocal<>();

    /**
     * Pre-cache the NULL_HASH since we use it so frequently.
     */
    private static final Hash NULL_HASH = CryptoFactory.getInstance().getNullHash();

    /**
     * This data source is used for looking up the values and the virtual tree leaf node
     * information. All instances of VirtualTreeMap in the "family" (i.e. that are copies
     * going back to some first progenitor) share the same exact dataSource instance.
     */
    private final VirtualDataSource dataSource;

    /**
     * A local cache that maps from keys to leaves. Normally this map will contain a few
     * tens of items at most. It is only populated with "dirty" leaves, that were either
     * newly added or modified.
     */
    private final Map<VirtualKey, VirtualRecord> cache = new HashMap<>();
    private final LongObjectHashMap<VirtualRecord> cache2 = new LongObjectHashMap<>();

    /**
     * Keeps track of all tree nodes that were deleted. A leaf node that was deleted represents
     * a result of deleting a key in the main API. A parent node that was deleted represents a node
     * that was removed as a consequence of shrinking the binary tree.
     *
     * // TODO A deleted node might be re-added as a consequence of some sequence of delete and add.
     * // TODO So we need to remove things from deleted nodes if they are re-added later.
     */
//    private final Set<VirtualTreeInternal> deletedInternalNodes = new HashSet<>();
//    private final Set<VirtualTreeLeaf> deletedLeafNodes = new HashSet<>();

    /**
     * A future that contains the hash. If the hash is still being computed, then
     * any "get" on this will block until the computation finishes.
     */
    private Future<Hash> rootHash;

    /**
     * The path of the very last leaf in the tree. Can be null if there are no leaves.
     * It is pushed to the data source on commit.
     */
    private long lastLeafPath;

    /**
     * The path of the very first leaf in the tree. Can e null if there are no leaves.
     * It is pushed to the data source on commit;
     */
    private long firstLeafPath;

    /**
     * Creates a new VirtualTreeMap.
     */
    public VirtualMap(VirtualDataSource ds) {
        this.dataSource = Objects.requireNonNull(ds);
        this.firstLeafPath = ds.getFirstLeafPath();
        this.lastLeafPath = ds.getLastLeafPath();
        setImmutable(false);

        final var rh = ds.loadParentHash(ROOT_PATH);
        final var future = new CompletableFuture<Hash>();
        future.complete(rh);
        rootHash = future;
    }

    /**
     * Creates a copy based on the given source.
     *
     * @param source Not null.
     */
    private VirtualMap(VirtualMap source) {
        this.dataSource = source.dataSource;
        this.firstLeafPath = source.firstLeafPath;
        this.lastLeafPath = source.lastLeafPath;
        this.rootHash = source.rootHash;
        this.setImmutable(false);
        source.setImmutable(true);
    }

    /**
     * Gets the value associated with the given key. The key must not be null.
     *
     * @param key The key to use for getting the value. Must not be null.
     * @return The value, or null if there is no such data.
     */
    public VirtualValue getValue(VirtualKey key) {
        Objects.requireNonNull(key);

        // Check the cache and return the value if it was in there.
        var rec = cache.get(key);
        if (rec != null) {
            return rec.getValue();
        }

        return dataSource.getLeafValue(key);
    }

    /**
     * Puts the given value into the map, associated with the given key. The key
     * must be non-null. Cannot be called if the map is immutable.
     *
     * @param key   A non-null key
     * @param value The value. May be null.
     */
    public void putValue(VirtualKey key, VirtualValue value) {
        throwIfImmutable();
        Objects.requireNonNull(key);
        final var rec = findRecord(key);
        if (rec != null) {
            rec.setValue(value);
        } else {
            add(key, value);
        }
    }

    /**
     * Deletes the entry in the map for the given key.
     *
     * @param key A non-null key
     */
    public void deleteValue(VirtualKey key) {
        // Validate the key.
        Objects.requireNonNull(key);

        // TODO not sure yet how delete works with the cache.

        // Get the current record for the key
//        final var record = dataSource.getRecord(key);
//        if (record != null) {
//            // TODO delete the node associated with this record. We gotta realize everything to get hashes right
//            // and move everything around as needed.
//        }
    }

    /**
     * Commits all changes buffered in this virtual map to the {@link VirtualDataSource}.
     */
    public void commit() {
        // Write the leaf paths
        this.dataSource.writeFirstLeafPath(firstLeafPath);
        this.dataSource.writeLastLeafPath(lastLeafPath);

        this.cache.values().stream()
                .filter(VirtualRecord::isDirty)
                .forEach(dataSource::saveLeaf);

        // TODO handle deleting things, etc.

        // Start hash recomputation eagerly.
        final var dirtyParents = recomputeHash();

        // Now that all the hashing is done, we can save the new hashes to the data source.
        for (var dirtyParent : dirtyParents) {
            try {
                dataSource.saveParent(dirtyParent.path, dirtyParent.hash.get());
            } catch (InterruptedException | ExecutionException e) {
                // TODO Not sure what to do here!!
                e.printStackTrace();
            }
        }

        // All done!
        cache.clear();
        cache2.clear();
    }

    @Override
    public VirtualMap copy() {
        throwIfImmutable();
        throwIfReleased();
        return new VirtualMap(this);
    }

    @Override
    public Hash getHash() {
        // TODO To be rewritten such that we recompute here if needed based on the state of
        // dirtyParents or whatnot.
        try {
            final var h = rootHash.get();
            return h == null ? NULL_HASH : h;
        } catch (InterruptedException | ExecutionException e) {
            // TODO Not sure what to do if this fails... Try, try, again?
            e.printStackTrace();
            return NULL_HASH;
        }
    }

    @Override
    public void setHash(Hash hash) {
        throw new UnsupportedOperationException("Cannot set the hash on this node, it is computed");
    }

    private static final class HashJobData {
        private final long path;
        private final Future<Hash> leftHash;
        private final Future<Hash> rightHash;

        HashJobData(long path, Future<Hash> leftHash, Future<Hash> rightHash) {
            this.path = path;
            this.leftHash = leftHash;
            this.rightHash = rightHash;
        }
    }

    private static final class DirtyParent {
        private final long path;
        private final Future<Hash> hash;

        DirtyParent(long path, Future<Hash> hash) {
            this.path = path;
            this.hash = hash;
        }
    }

    private List<DirtyParent> recomputeHash() {
        // Get the old hash so we can see whether we need to recompute it at all.
        // Note that this call blocks if there was a previous hash running that
        // hasn't completed yet. This is critical, otherwise we may lose some
        // information about what needs to be rehashed and end up with the wrong
        // hash in the end.
        Hash hash = getHash();

        // Only recompute if we have to.
        if (hash == null || NULL_HASH.equals(hash) || !cache.isEmpty()) {

            // Keeps track of all data required for hashing. Initially, the list is null.
            // As we visit leaves, a new HashJobData is created for the parent of the
            // leaf and its sibling. There is only ever a single entry in this list for
            // each parent node. As the parents get processed, they remove the head of
            // the queue and add to the tail of the queue their own information about
            // _their_ parent, and so on. Each parent then has access to the data it needs
            // to compute its hash, without having to do any kind of map lookups.
            final var hashJobData = new LinkedList<HashJobData>();

            // First, process all of the leaves. We need to make sure we only handle each
            // leaf once, but we also have to deal with siblings. So we'll have an array
            // of dirty leaves, sorted by rank & breadcrumbs. Thus, two siblings will be
            // side-by-side in the array, if they are both dirty. If a sibling is missing,
            // then it was clean and I need to look up its hash from the data source.
            // I create a HashJobData, add it to the list, and process the next (non-sibling)
            // leaf.
            final var dirtyLeaves = new ArrayList<>(cache.values());
            final var numDirtyLeaves = dirtyLeaves.size();
            dirtyLeaves.sort((a, b) -> compareTo(b.getPath(), a.getPath())); // reverse the order

            // Gotta save parents that are modified here
            final var dirtyParents = new ArrayList<DirtyParent>(cache.size() * getRank(lastLeafPath));

            // Process the leaves
            for (int i=0; i<numDirtyLeaves; i++) {
                // We may have a dirty left leaf followed by a possible dirty right, or a
                // dirty right with a clean left.
                final var leaf = dirtyLeaves.get(i);
                final var leafPath = leaf.getPath();
                final var nextLeaf = i == numDirtyLeaves - 1 ? // Are we on the last iteration?
                        null : // If we're on the last iteration, there is no next.
                        dirtyLeaves.get(i + 1);

                // If the next leaf is the sibling of this leaf, then we don't need to look
                // it up in the data source
                final var siblingPath = getSiblingPath(leafPath);
                if (nextLeaf != null && nextLeaf.getPath() == siblingPath) {
                    i++; // Increment so we skip this leaf on the next iteration
                    hashJobData.addLast(new HashJobData(
                            getParentPath(leafPath),
                            leaf.getFutureHash(),
                            nextLeaf.getFutureHash()));
                } else {
                    // nextLeaf was not the sibling. This might have happened because there were
                    // no more leaves in the list, but a sibling might *now* exist which is a parent
                    // and can be found on the hashJobData. Look there. If we find the sibling there,
                    // then we use that. Otherwise, we need to look it up in the data source.
                    if (nextLeaf == null && !hashJobData.isEmpty() && hashJobData.getFirst().path == siblingPath) {
                        final var siblingFuture = new ImmutableFuture<>(call(new HashParent(hashJobData.removeFirst())));
                        dirtyParents.add(new DirtyParent(siblingPath, siblingFuture));
                        hashJobData.addLast(new HashJobData(
                                getParentPath(leafPath),
                                leaf.getFutureHash(),
                                siblingFuture));
//                                HASHING_POOL.submit(new HashParent(hashJobData.removeFirst()))));
                    } else {
                        // nextLeaf was not the sibling, so we need to look it up. There might be no
                        // sibling, in which case it will be null. A leaf might have a leaf OR a parent
                        // as a sibling, so we need to check both in the case one is null.
                        final var siblingLeaf = dataSource.loadLeaf(siblingPath);
                        if (siblingLeaf != null) {
                            hashJobData.addLast(new HashJobData(
                                    getParentPath(leafPath),
                                    leaf.getFutureHash(),
                                    siblingLeaf.getFutureHash()));
                        } else {
                            final var siblingParent = dataSource.loadParentHash(siblingPath);
                            hashJobData.addLast(new HashJobData(
                                    getParentPath(leafPath),
                                    leaf.getFutureHash(),
                                    siblingParent == null ? null : new ImmutableFuture<>(siblingParent)));
                        }
                    }
                }
            }

            // Now we start processing all of the HashJobData that has been setup. For each
            // one, we will create a new Future for creating the hash. Just like with the
            // leaves, we need to look for siblings and make sure we're not processing siblings
            // unnecessarily. Fortunately, just like with the leaves, if there *is* a sibling,
            // it will be the next item in the hashJobData list, since we were careful to
            // processing leaves in reverse order. As we process each parent, we add it to
            // the hashJobData, and keep iterating until we've eventually handled everything.
            while (!hashJobData.isEmpty()) {
                // FIFO, pull off the first, push on the last.
                final var data = hashJobData.removeFirst();
                final var path = data.path;

                // Create the future that will hash the left and right children as appropriate
//                final var future = HASHING_POOL.submit(new HashParent(data));
                final var future = new ImmutableFuture<>(call(new HashParent(data)));

                // Add this node to the dirty parents
                dirtyParents.add(new DirtyParent(path, future));

                if (path == ROOT_PATH) {
                    // Also set this future as the rootHash.
                    assert hashJobData.isEmpty(); // This must be true
                    this.rootHash = future;
                } else {
                    // We're not at the root yet, so we need to look for a sibling. Fortunately,
                    // if there is a dirty sibling, it will be next on the hashJobData list.
                    // Otherwise, we load it from dataSource. Add the new HashJobData to the
                    // list to be processed next.
                    final var siblingPath = getSiblingPath(path);
                    if (!hashJobData.isEmpty() && hashJobData.getFirst().path == siblingPath) {
                        // The next node is a sibling, so lets remove it too
                        final var sibling = hashJobData.removeFirst();
//                        final var siblingFuture = HASHING_POOL.submit(new HashParent(sibling));
                        final var siblingFuture = new ImmutableFuture<>(call(new HashParent(sibling)));
                        dirtyParents.add(new DirtyParent(sibling.path, siblingFuture));
                        hashJobData.addLast(new HashJobData(
                                getParentPath(path),
                                future,
                                siblingFuture));
                    } else {
                        // No dirty sibling, so get a fresh one from the data source
                        final var siblingHash = dataSource.loadParentHash(siblingPath);
                        hashJobData.addLast(new HashJobData(
                                getParentPath(path),
                                future,
                                siblingHash == null ? null : new ImmutableFuture<>(siblingHash)));
                    }
                }
            }

            return dirtyParents;
        }
        return Collections.emptyList();
    }

    private Hash call(HashParent c) {
        try {
            return c.call();
        } catch (Exception e) {
            e.printStackTrace();
            return NULL_HASH;
        }
    }

    private static final class HashParent implements Callable<Hash> {
        private final HashJobData data;
        HashParent(HashJobData data) {
            this.data = data;
        }
        @Override
        public Hash call() throws Exception {
//                        System.out.println("Hashing (" + getRank(path) + ", " + getBreadcrumbs(path) + ")");
            if (data.leftHash == null && data.rightHash != null) {
                // Since there is only a rightHash, we might as well pass it up and not bother
                // hashing anything at all.
                return data.rightHash.get();
            } else if (data.leftHash != null && data.rightHash == null) {
                // Since there is only a left hash, we can use it as our hash
                return data.leftHash.get();
            } else if (data.leftHash != null) {
                // BTW: This branch is hit if right and left hash != null.
                // Since we have both a left and right hash, we need to hash them together.

                // Get the message digest we will need for hashing.
                var md = MD_LOCAL.get();
                if (md == null) {
                    md = MessageDigest.getInstance("SHA-384");
                    MD_LOCAL.set(md);
                }

                md.update(data.leftHash.get().getValue());
                md.update(data.rightHash.get().getValue());
                return new Hash(md.digest(), DigestType.SHA_384);
            } else {
                System.err.println("Both children were null. This shouldn't be possible!");
                return NULL_HASH;
            }
        }
    }

    @Override
    public long getClassId() {
        return CLASS_ID;
    }

    @Override
    public int getVersion() {
        return CLASS_VERSION;
    }

    @Override
    public void archive() {
        // I only want to delegate the "archive" call to the data source if this
        // is the very last merkle tree to exist, not if it has been copied
        if (!isImmutable()) {
            try {
                dataSource.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void serializeAbbreviated(SerializableDataOutputStream serializableDataOutputStream) throws IOException {
        // TODO?
    }

    @Override
    public void deserializeAbbreviated(SerializableDataInputStream serializableDataInputStream, Hash hash, int i) throws IOException {
        // TODO?
    }

    @Override
    public void deserialize(SerializableDataInputStream serializableDataInputStream, int i) throws IOException {
        // TODO?
    }

    @Override
    public void serialize(SerializableDataOutputStream serializableDataOutputStream) throws IOException {
        // TODO?
    }

    // ----------------------------------------------------------------------------------------------------
    //
    // TODO Review this documentation for accuracy
    // Implementation of tree functionality, such as adding nodes, walking the tree, realizing nodes,
    // deleting nodes, etc.
    //
    // Definitions:
    //   - Node: Either a VirtualTreeInternal or a VirtualTreeLeaf. Every "parent" node, including the
    //           root node is a VirtualTreeInternal.
    //
    //   - Ghost Node: A "ghost" node is one that exists in the data source but has no associated node instance
    //                 in memory yet.
    //
    //   - Realized Node: A "realize" node is one that was once a ghost, but is no longer. To "realize" is
    //                    the process of reading a ghost from disk and creating an instance of the node
    //                    in Java.
    //
    //   - Path: Each node has a unique path. Since our tree is a binary tree, every internal node has,
    //           conceptually, two children; a left, and a right. In reality, sometimes the node has only
    //           one child, and the second is left null, but this is just an optimization. No parent node
    //           ever has zero children. "Left" nodes are represented with a "0" and "right" nodes are
    //           represented with a "1". The Path is made up of two pieces of information: a "rank", and
    //           a 64-bit number. Using the "rank", you can determine how many bits in the number are used
    //           for that path. Using this system, a tree cannot be more than 64 levels deep (which is
    //           way more than we need). The node on the far left side of any level will have a number of
    //           all zeros. The node on the far right side of any level will have all ones. All other nodes
    //           have some sequence of 0's and 1's that tell us how to walk from the root down to the node,
    //           turning "left" or "right" as we traverse at each level.
    //
    //  Notes:
    //   - Paths on a node change when the tree is modified. Since paths are used for node Ids and stored in
    //     in the data source, when the tree is modified it may be necessary to update the path associated
    //     with a node. This makes it difficult to have asynchronous writes (it requires some cache in the
    //     data source that will know the new value before it is visible in the memory mapped file, for example).
    //
    //   - We store in the data source the Path of the very last leaf node on the right. This makes it trivial
    //     to add and remove nodes in constant time.
    //
    //   - We always add nodes from "left to right". When a level if full (the last leaf node path is all 1's),
    //     then we insert an internal node where the left-most leaf node was, move that leaf down a level,
    //     and add the new leaf. A similar technique is used for every time we have to add a child to an
    //     internal node that is already full.
    //
    // ----------------------------------------------------------------------------------------------------

    /**
     *
     */
    private VirtualRecord findRecord(VirtualKey key) {
        assert key != null;

        var rec = cache.get(key);
        if (rec == null) {
            rec = dataSource.loadLeaf(key);
        }

        if (rec != null) {
            cache.put(key, rec);
            cache2.put(rec.getPath(), rec);
        }
        return rec;
    }

    private VirtualRecord findRecord(long path) {
        var rec = cache2.get(path);
        if (rec == null) {
            rec = dataSource.loadLeaf(path);
            if (rec != null) {
                cache.put(rec.getKey(), rec);
                cache2.put(path, rec);
            }
        }
        return rec;
    }

    /**
     * Adds a new leaf with the given key and value. At this point, we know for
     * certain that there is no record in the data source for this key, so
     * we can assume that here.
     *
     * @param key   A non-null key. Previously validated.
     * @param value The value to add. May be null.
     */
    private void add(VirtualKey key, VirtualValue value) {
        // We're going to imagine what happens to the leaf and the tree without
        // actually bringing into existence any nodes. Virtual Virtual!!

        // Find the lastLeafPath which will tell me the new path for this new item
        if (lastLeafPath == INVALID_PATH) {
            // There are no leaves! So this one will just go right on the root
            final var leafPath = getLeftChildPath(ROOT_PATH);
            final var newLeaf = new VirtualRecord(NULL_HASH, leafPath, key, value);
            newLeaf.makeDirty();
            // Save state.
            this.firstLeafPath = leafPath;
            this.lastLeafPath = leafPath;
            cache.put(key, newLeaf);
            cache2.put(leafPath, newLeaf);
        } else if (isLeft(lastLeafPath)) {
            // If the lastLeafPath is on the left, then this is easy, we just need
            // to add the new leaf to the right of it, on the same parent
            final var parentPath = getParentPath(lastLeafPath);
            assert parentPath != INVALID_PATH; // Cannot happen because lastLeafPath always points to a leaf in the tree
            final var leafPath = getRightChildPath(parentPath);
            final var newLeaf = new VirtualRecord(NULL_HASH, leafPath, key, value);
            newLeaf.makeDirty();
            // Save state.
            lastLeafPath = leafPath;
            cache.put(key, newLeaf);
            cache2.put(leafPath, newLeaf);
        } else {
            // We have to make some modification to the tree because there is not
            // an open slot. So we need to pick a slot where a leaf currently exists
            // and then swap it out with a parent, move the leaf to the parent as the
            // "left", and then we can put the new leaf on the right. It turns out,
            // the slot is always the firstLeafPath. If the current firstLeafPath
            // is all the way on the far right of the graph, then the next firstLeafPath
            // will be the first leaf on the far left of the next level. Otherwise,
            // it is just the sibling to the right.
            final var nextFirstLeafPath = isFarRight(firstLeafPath) ?
                    getPathForRankAndIndex((byte) (getRank(firstLeafPath) + 1), 0) :
                    getPathForRankAndIndex(getRank(firstLeafPath), getIndexInRank(firstLeafPath) + 1);

            // The firstLeafPath points to the old leaf that we want to replace.
            final var parentPath = getParentPath(firstLeafPath);

            // Get the old leaf. Could be null, if it has not been realized.
            final var oldLeafPath = isLeft(firstLeafPath) ? getLeftChildPath(parentPath) : getRightChildPath(parentPath);
            final var oldLeaf = findRecord(oldLeafPath);

            // Create a new internal node that is in the position of the old leaf and attach it to the parent
            // on the left side.
            final var newSlotParentPath = firstLeafPath;

            // Put the new item on the right side of the new parent.
            final var leafPath = getRightChildPath(newSlotParentPath);
            final var newLeaf = new VirtualRecord(NULL_HASH, leafPath, key, value);
            newLeaf.makeDirty();
            cache.put(key, newLeaf);
            cache2.put(leafPath, newLeaf);
            // Add the leaf nodes to the newSlotParent
            if (oldLeaf != null) {
                cache2.remove(oldLeafPath);
                oldLeaf.setPath(getLeftChildPath(newSlotParentPath));
                cache2.put(oldLeaf.getPath(), oldLeaf);
            }

            // Save the first and last leaf paths
            firstLeafPath = nextFirstLeafPath;
            lastLeafPath = leafPath;
        }
    }

    public String getAsciiArt() {
        if (lastLeafPath == INVALID_PATH) {
            return "<Empty>";
        }

        final var nodeWidth = 10; // Let's reserve this many chars for each node to write their name.

        // Use this for storing all the strings we produce as we go along.
        final var strings = new ArrayList<List<String>>(64);
        final var l = new ArrayList<String>(1);
        l.add("( )");
        strings.add(l);

        // Simple depth-first traversal
        print(strings, ROOT_PATH);

        final var buf = new StringBuilder();
        final var numRows = strings.size();
        final var width = (int) (Math.pow(2, numRows-1) * nodeWidth);
        for (int i=0; i<strings.size(); i++) {
            final var list = strings.get(i);
            int x = width/2 - (nodeWidth * (int)(Math.pow(2, i)))/2;
            buf.append(" ".repeat(x));
            x = 0;
            for (var s : list) {
                final var padLeft = ((nodeWidth - s.length()) / 2);
                final var padRight = ((nodeWidth - s.length()) - padLeft);
                buf.append(" ".repeat(padLeft)).append(s).append(" ".repeat(padRight));
                x += nodeWidth;
            }
            buf.append("\n");
        }
        return buf.toString();
    }

    private void print(List<List<String>> strings, long path) {
        // Write this node out
        final var rank = getRank(path);
        final var pnode = compareTo(path, firstLeafPath) < 0;
        final var dirtyMark = !pnode && cache2.containsKey(path) ? "*" : "";
        strings.get(rank).set(getIndexInRank(path), dirtyMark + "(" + (pnode ? "P" : "L") + ", " + getBreadcrumbs(path) + ")" + dirtyMark);

        if (pnode) {
            // Make sure we have another level down to go.
            if (strings.size() <= rank + 1) {
                final var size = (int)Math.pow(2, rank+1);
                final var list = new ArrayList<String>(size);
                for (int i=0; i<size; i++) {
                    list.add("( )");
                }
                strings.add(list);
            }

            print(strings, getLeftChildPath(path));
            print(strings, getRightChildPath(path));
        }
    }
}
