package com.hedera.services.state.merkle.virtual;

import com.hedera.services.state.merkle.virtual.persistence.ImmutableFuture;
import com.hedera.services.state.merkle.virtual.persistence.VirtualDataSource;
import com.hedera.services.state.merkle.virtual.persistence.VirtualNode;
import com.swirlds.common.Archivable;
import com.swirlds.common.FCMValue;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.crypto.CryptoFactory;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleExternalLeaf;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;

import static com.hedera.services.state.merkle.virtual.VirtualTreePath.INVALID_PATH;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.ROOT_PATH;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getLeftChildPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getParentPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getRank;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.getRightChildPath;
import static com.hedera.services.state.merkle.virtual.VirtualTreePath.isLeft;

/**
 * A map-like Merkle node designed for working with huge numbers of key/value pairs stored primarily
 * in off-heap memory mapped files and pulled on-heap only as needed. It buffers changes locally and
 * flushes them to the storage on {@link #commit()}.
 *
 * <p>The {@code VirtualMap} is created with a {@link VirtualDataSource}. The {@code VirtualDataSource} is
 * used by the map to read/write data to/from disk. This interface has only one practical implementation
 * in this code base, the {@link com.hedera.services.state.merkle.virtual.persistence.mmap.MemMapDataSource},
 * which is backed by memory-mapped files. Several in-memory data source implementations exist for testing
 * purposes. The API of the data source is closely tied to the needs of the {@code VirtualMap}. This was done
 * intentionally to <strong>reduce temporary objects and misalignment of the API to enhance performance.</strong>.
 * The {@code VirtualDataSource} exists as an implementation interface of the {@code VirtualMap} and is unsuited
 * for generic use.</p>
 *
 * <p>The {@code VirtualMap} buffers changes in memory and only flushes them to the {@code VirtualDataSource} on
 * {@link #commit()}. The commit should happen in a background thread and not as part of {@code handleTransaction}.
 * This map <strong>does not accept null keys</strong> but does accept null values.</p>
 *
 * TODO: Right now the implementation will break if commit is called on a background thread. This needs to be fixed.
 *
 * <p>The {@code VirtualMap} is {@code FastCopyable} and should be used in a similar manner to any other normal
 * Swirlds MerkleNode. The {@code VirtualMap} does have some runtime overhead, such as caches, which require
 * size relative to the number of <strong>dirty leaves</strong> in the map, not relative to the number of values
 * read or the number of items in the backing store. On {@code commit} these changes are flushed to disk and
 * the caches are cleared. Thus, a node in the Merkle tree that had a VirtualMap has very little overhead when
 * not in use, and very little overhead after commit, and reasonable overhead when in use for reasonable-sized
 * changes.</p>
 *
 * TODO: The FastCopyable implementation needs to be improved. It should use FCHashMap for the cache.
 * TODO: The implementation needs to be made to work with reconnect. There are serialization methods to attend to.
 * TODO: The implementation needs to be integrated into the normal Merkle commit mechanism
 * TODO: The implementation needs to prove out the integration of hashing with how the Merkle tree normally hashes.
 * TODO: I'm not sure how the datasource can be serialized and restored, or how that works.
 */
@ConstructableIgnored
public final class VirtualMap
        extends AbstractMerkleLeaf
        implements Archivable, FCMValue, MerkleExternalLeaf {

    private static final long CLASS_ID = 0xb881f3704885e853L;
    private static final int CLASS_VERSION = 1;

    private static final ThreadGroup HASHING_GROUP = new ThreadGroup("VirtualMap-Hashers");

    private static final MessageDigest DIGEST = getDigest();
    static MessageDigest getDigest() {
        try {
            return MessageDigest.getInstance("SHA-384");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null; // cannot be reached...?
    }

    /**
     * Pre-cache the NULL_HASH since we use it so frequently.
     */
    private static final byte[] NULL_HASH = CryptoFactory.getInstance().getNullHash().getValue();

    /**
     * This data source is used for looking up the values and virtual node information.
     * All instances of VirtualTreeMap in the "family" (i.e. that are copies
     * going back to some first progenitor) share the same exact dataSource instance.
     */
    private final VirtualDataSource dataSource;

    /**
     * A local cache that maps from keys to dirty (i.e. modified) nodes.
     * Normally this map will contain a few tens of items at most. It is only populated
     * with "dirty" nodes, that were either newly added or modified.
     */
    private final Map<VirtualKey, VirtualNode> dirtyNodes = new HashMap<>();

    /**
     * Keeps track of all tree nodes that were deleted.
     */
    private final Map<VirtualKey, VirtualNode> deletedNodes = new HashMap<>();

    // See if these still prove their worth.
//    private final HashWorkQueue hashWork;
    private final LinkedBlockingQueue<VirtualNode> hashWork;

    private final ExecutorService hashingPool;
    private final LongArrayList[] dirtyRanks;

    /**
     * A future that contains the hash of the root node. If the hash is still being computed, then
     * any "get" on this will block until the computation finishes.
     */
    private Future<byte[]> rootHash;

    // Used for knowing the index to give to new nodes and when removing nodes what node to use to fill the gap
    private long lastNodeIndex = INVALID_PATH;


    /**
     * Creates a new VirtualTreeMap.
     */
    public VirtualMap(VirtualDataSource ds) {
        this.dataSource = Objects.requireNonNull(ds);
        this.lastNodeIndex = ds.getLastLeafPath();
        setImmutable(false);

        final var rh = ds.loadLeaf(ROOT_PATH);
        final var future = new CompletableFuture<byte[]>();
        future.complete(rh == null ? NULL_HASH : rh.getHash());
        rootHash = future;

        this.dirtyRanks = new LongArrayList[64];
//        this.hashWork = new HashWorkQueue(100);
        this.hashWork = new LinkedBlockingQueue<>(1000);
        this.hashingPool = Executors.newSingleThreadExecutor((r) -> {
            final var thread = new Thread(HASHING_GROUP, r);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Creates a copy based on the given source.
     *
     * @param source Not null.
     */
    private VirtualMap(VirtualMap source) {
        this.dataSource = source.dataSource;
        this.rootHash = source.rootHash;
        this.hashingPool = source.hashingPool;
        this.hashWork = source.hashWork;
        this.dirtyRanks = source.dirtyRanks;
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
        var rec = dirtyNodes.get(key);
        if (rec != null) {
            return rec.getValue();
        }

        // The node wasn't dirty, so get the value from the data source
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

        // Lookup the node. It might be in dirty nodes, or in the data source.
        var node = dirtyNodes.get(key);
        if (node == null) {
            node = dataSource.loadLeaf(key);
        }

        // If we found it, modify it and make it dirty. Otherwise, create it (and also make it dirty).
        if (node != null) {
            node.setValue(value);
        } else {
            // Maybe this particular node was previously deleted and is now being added back in.
            // We can reuse the node in this case, and just put it at the end.
            node = deletedNodes.remove(key);
            if (node != null) {
                node.setPath(++lastNodeIndex);
                node.setValue(value);
            } else {
                node = new VirtualNode(NULL_HASH, ++lastNodeIndex, key, value);
            }
        }

        dirtyNodes.put(key, node);
        rootHash = null;
    }

    /**
     * Deletes the entry in the map for the given key.
     *
     * @param key A non-null key
     */
    public void deleteValue(VirtualKey key) {
        // Validate the key.
        Objects.requireNonNull(key);

        // Maybe this node was already dirty. If so, we can just remove it from the dirty list
        var node = dirtyNodes.remove(key);
        if (node == null) {
            node = dataSource.loadLeaf(key);
            if (node == null) {
                return; // The node didn't exist, so we can just bail.
            }
        }

        // Mark the node as deleted
        deletedNodes.put(key, node);
        rootHash = null;

        // If the very last leaf wasn't removed, then move the last leaf into the slot
        // previously occupied by the node.
        if (node.getPath() != lastNodeIndex) {
            var lastNode = dataSource.loadLeaf(lastNodeIndex);
            if (dirtyNodes.containsKey(lastNode.getKey())) {
                lastNode = dirtyNodes.get(lastNode.getKey());
            }

            lastNode.setPath(node.getPath());
            dirtyNodes.put(lastNode.getKey(), lastNode);
            lastNodeIndex--;
        }
    }

    /**
     * Commits all changes buffered in this virtual map to the {@link VirtualDataSource}.
     */
    public void commit() {
        this.dataSource.writeLastLeafPath(lastNodeIndex);

        // Start hash recomputation eagerly.
        recomputeHash();

        // Delete the deleted nodes and save the modified ones.
        this.deletedNodes.values().forEach(dataSource::deleteLeaf);
        this.dirtyNodes.values().forEach(dataSource::saveLeaf);

        // All done!
        dirtyNodes.clear();
        deletedNodes.clear();
    }

    @Override
    public VirtualMap copy() {
        throwIfImmutable();
        throwIfReleased();
        return new VirtualMap(this);
    }

    @Override
    public Hash getHash() {
        recomputeHash();
        return new Hash(getRootHash());
    }

    private byte[] getRootHash() {
        try {
            return rootHash.get();
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

    private void recomputeHash() {
        // Get the old hash so we can see whether we need to recompute it at all.
        // Note that this call blocks if there was a previous hash running that
        // hasn't completed yet. This is critical, otherwise we may lose some
        // information about what needs to be rehashed and end up with the wrong
        // hash in the end.
        byte[] hash = rootHash == null ? null : getRootHash();

        // Only recompute if we have to.
        if (hash == null || !dirtyNodes.isEmpty() || Arrays.equals(NULL_HASH, hash)) {
            final var rootHashResult = hashingPool.submit(() -> {
                VirtualNode node;
                Future<byte[]> lastHash = new ImmutableFuture<>(NULL_HASH);
                while ((node = hashWork.poll()) != null) {
                    try {
                        final var leftChild = node.getLeftChild();
                        final var rightChild = node.getRightChild();
                        final var leftHash = leftChild == null ? null : leftChild.getFutureHash();
                        final var rightHash = rightChild == null ? null : rightChild.getFutureHash();
                        if (leftHash == null && rightHash != null) {
                            // Since there is only a rightHash, we might as well pass it up and not bother
                            // hashing anything at all.
                            node.setFutureHash(rightHash);
                        } else if (leftHash != null && rightHash == null) {
                            // Since there is only a left hash, we can use it as our hash
                            node.setFutureHash(leftHash);
                        } else if (leftHash != null) {
                            // BTW: This branch is hit if right and left hash != null.
                            // Since we have both a left and right hash, we need to hash them together.
                            assert leftHash.get() != null;
                            assert rightHash.get() != null;

                            // Hash it.
                            DIGEST.update(leftHash.get());
                            DIGEST.update(rightHash.get());
                            node.setFutureHash(new ImmutableFuture<>(DIGEST.digest()));
                        } else {
//                            System.err.println("Both children were null. This shouldn't be possible!");
                            node.setFutureHash(new ImmutableFuture<>(NULL_HASH));
                        }

                    } catch (Exception e) {
                        e.printStackTrace();
                        // TODO not sure what to do with exceptions here
                        node.setFutureHash(new ImmutableFuture<>(NULL_HASH));
                    }

                    lastHash = node.getFutureHash();
                }

                return lastHash;
            });

            // Used for looking up known dirty nodes by path
            final var lookup = new LongObjectHashMap<VirtualNode>();

            // Add each dirty node to "lookup" and to the list of dirty paths at each rank.
            var maxRank = 0;
            for (var dirtyNode : dirtyNodes.values()) {
                final var path = dirtyNode.getPath();
                lookup.put(path, dirtyNode);
                final var rank = getRank(path);
                assert rank < 64;
                if (dirtyRanks[rank] == null) {
                    dirtyRanks[rank] = new LongArrayList(100);
                }
                dirtyRanks[rank].add(path);
                maxRank = Math.max(maxRank, rank);
            }

            // Starting at the maxRank and working backwards, process each rank (could do it totally in parallel
            // if data source access was threadsafe and if "lookup" was threadsafe and if the LongArrayLists were
            // threadsafe...
            for (int i=maxRank; i >= 0; i--) {
                var paths = dirtyRanks[i];
                if (paths == null) {
                    paths = new LongArrayList(100);
                    dirtyRanks[i] = paths;
                }
                for (int j=0; j<paths.size(); j++) {
                    final var path = paths.get(j);
                    final var node = lookup.get(path);
                    if (node == null) {
                        System.out.println("Oof");
                    }
                    assert node != null : "null node on i=" + i + ", j=" + j + ", path=" + path;

                    if (node.isDirty()) {
                        if (node.getLeftChild() == null) {
                            final var leftChildPath = getLeftChildPath(path);
                            node.setLeftChild(dataSource.loadLeaf(leftChildPath));
                        }

                        if (node.getRightChild() == null) {
                            final var rightChildPath = getRightChildPath(path);
                            node.setRightChild(dataSource.loadLeaf(rightChildPath));
                        }

                        hashWork.offer(node);
                        node.setDirty(false);

                        // Now, lookup the parent and deal with that business.
                        if (path != ROOT_PATH) {
                            final var parentPath = getParentPath(path);
                            var parent = lookup.get(parentPath);
                            if (parent == null) {
                                parent = dataSource.loadLeaf(parentPath);
                                parent.makeDirty();
                                lookup.put(parentPath, parent);
                                if (dirtyRanks[i - 1] == null) {
                                    dirtyRanks[i - 1] = new LongArrayList(100);
                                }
                                dirtyRanks[i - 1].add(parentPath);
                            }

                            if (isLeft(path)) {
                                parent.setLeftChild(node);
                            } else {
                                parent.setRightChild(node);
                            }
                        } else {
                            assert i==0;
                            rootHash = node.getFutureHash();
                        }
                    }
                }

                paths.clear();
            }

            try {
                // block until it is done hashing. Might actually care to do that here.
                rootHash = rootHashResult.get();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
                // TODO oof, now what?
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

//    public String getAsciiArt() {
//        if (lastLeafPath == INVALID_PATH) {
//            return "<Empty>";
//        }
//
//        final var nodeWidth = 10; // Let's reserve this many chars for each node to write their name.
//
//        // Use this for storing all the strings we produce as we go along.
//        final var strings = new ArrayList<List<String>>(64);
//        final var l = new ArrayList<String>(1);
//        l.add("( )");
//        strings.add(l);
//
//        // Simple depth-first traversal
//        print(strings, ROOT_PATH);
//
//        final var buf = new StringBuilder();
//        final var numRows = strings.size();
//        final var width = (int) (Math.pow(2, numRows-1) * nodeWidth);
//        for (int i=0; i<strings.size(); i++) {
//            final var list = strings.get(i);
//            int x = width/2 - (nodeWidth * (int)(Math.pow(2, i)))/2;
//            buf.append(" ".repeat(x));
//            x = 0;
//            for (var s : list) {
//                final var padLeft = ((nodeWidth - s.length()) / 2);
//                final var padRight = ((nodeWidth - s.length()) - padLeft);
//                buf.append(" ".repeat(padLeft)).append(s).append(" ".repeat(padRight));
//                x += nodeWidth;
//            }
//            buf.append("\n");
//        }
//        return buf.toString();
//    }
//
//    private void print(List<List<String>> strings, long path) {
//        // Write this node out
//        final var rank = getRank(path);
//        final var pnode = compareTo(path, firstLeafPath) < 0;
//        final var dirtyMark = !pnode && dirtyNodesByPath.containsKey(path) ? "*" : "";
//        strings.get(rank).set(getIndexInRank(path), dirtyMark + "(" + (pnode ? "P" : "L") + ", " + getBreadcrumbs(path) + ")" + dirtyMark);
//
//        if (pnode) {
//            // Make sure we have another level down to go.
//            if (strings.size() <= rank + 1) {
//                final var size = (int)Math.pow(2, rank+1);
//                final var list = new ArrayList<String>(size);
//                for (int i=0; i<size; i++) {
//                    list.add("( )");
//                }
//                strings.add(list);
//            }
//
//            print(strings, getLeftChildPath(path));
//            print(strings, getRightChildPath(path));
//        }
//    }
//

    private static final class HashWorkQueue {
        private VirtualNode[] q;
        private int head = -1; // Points to first
        private int tail = -1; // Points to last

        public HashWorkQueue(int initialSize) {
            q = new VirtualNode[initialSize];
        }

        public VirtualNode getFirst() {
            return q[head];
        }

        public VirtualNode getLast() {
            return q[tail];
        }

        public VirtualNode get(int index) {
            if (index < head || index > tail) {
                throw new IndexOutOfBoundsException();
            }

            return q[index];
        }

        public void addLast(VirtualNode data) {
            head = 0;
            tail += 1;
            if (tail >= q.length) {
                q = Arrays.copyOf(q, q.length * 2);
            }
            q[tail] = data;
        }

        public boolean isEmpty() {
            return head == -1;
        }

        public int size() {
            return head == -1 ? 0 : tail - head + 1;
        }

        public void reset() {
            head = tail = -1;
        }
    }
}
