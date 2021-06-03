package com.hedera.services.state.merkle.virtual;

import com.hedera.services.state.merkle.virtual.persistence.VirtualDataSource;
import com.hedera.services.state.merkle.virtual.persistence.VirtualRecord;
import com.hedera.services.state.merkle.virtual.tree.VirtualTreePath;
import com.hedera.services.state.merkle.virtual.tree.VirtualTreeInternal;
import com.hedera.services.state.merkle.virtual.tree.VirtualTreeLeaf;
import com.hedera.services.state.merkle.virtual.tree.VirtualTreeNode;
import com.hedera.services.state.merkle.virtual.tree.VirtualVisitor;
import com.swirlds.common.Archivable;
import com.swirlds.common.FCMElement;
import com.swirlds.common.FCMValue;
import com.swirlds.common.constructable.ConstructableIgnored;
import com.swirlds.common.crypto.CryptoFactory;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SerializableDataInputStream;
import com.swirlds.common.io.SerializableDataOutputStream;
import com.swirlds.common.merkle.MerkleExternalLeaf;
import com.swirlds.common.merkle.utility.AbstractMerkleLeaf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Pre-cache the NULL_HASH since we use it so frequently.
     */
    private static final Hash NULL_HASH = CryptoFactory.getInstance().getNullHash();

    /**
     * This data source is used for looking up the values and the
     * virtual tree leaf node information. All instances of
     * VirtualTreeMap in the "family" (i.e. that are copies
     * going back to some first progenitor) share the same exact
     * dataSource instance.
     */
    private final VirtualDataSource dataSource;

    /**
     * The root node of the inner merkle tree. It always starts off as null,
     * and created or loaded lazily.
     */
    private VirtualTreeInternal<VirtualKey, VirtualValue> root;

    /**
     * A local cache that maps from keys to leaves. Looking up the VirtualRecord in the
     * VirtualDataSource is a time-consuming process where every nanosecond counts, so
     * we're going to have a local cache of all leaves that have been created or realized
     * for faster lookup. Normally this map will contain a few tens of items.
     */
    private final Map<VirtualKey, VirtualTreeLeaf<VirtualKey, VirtualValue>> cache = new HashMap<>();

    /**
     * Keeps track of all tree nodes that were deleted. A leaf node that was deleted represents
     * a result of deleting a key in the main API. A parent node that was deleted represents a node
     * that was removed as a consequence of shrinking the binary tree.
     *
     * // TODO A deleted node might be re-added as a consequence of some sequence of delete and add.
     * // TODO So we need to remove things from deleted nodes if they are re-added later.
     */
    private final Map<VirtualTreePath, VirtualTreeNode<VirtualKey, VirtualValue>> deletedNodes = new HashMap<>();

    /**
     * The path of the very last leaf in the tree. Can be null if there are no leaves.
     * It is pushed to the data source on commit.
     */
    private VirtualTreePath lastLeafPath;

    /**
     * The path of the very first leaf in the tree. Can e null if there are no leaves.
     * It is pushed to the data source on commit;
     */
    private VirtualTreePath firstLeafPath;

    /**
     * Creates a new VirtualTreeMap.
     */
    public VirtualMap(VirtualDataSource ds) {
        this.root = null;
        this.dataSource = Objects.requireNonNull(ds);
        this.firstLeafPath = ds.getFirstLeafPath();
        this.lastLeafPath = ds.getLastLeafPath();
        setImmutable(false);
    }

    /**
     * Creates a copy based on the given source.
     *
     * @param source Not null.
     */
    private VirtualMap(VirtualMap source) {
        this.dataSource = source.dataSource;
        this.root = null;
        this.firstLeafPath = source.firstLeafPath;
        this.lastLeafPath = source.lastLeafPath;
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
        final var leaf = findLeaf(key);
        return leaf == null ? null : leaf.getData();
    }

    /**
     * Puts the given value into the map, associated with the given key. The key
     * must be non-null.
     *
     * @param key A non-null key
     * @param value The value. May be null.
     */
    public void putValue(VirtualKey key, VirtualValue value) {
        Objects.requireNonNull(key);
        final var leaf = findLeaf(key);
        if (leaf != null) {
            leaf.setData(value);
            leaf.makeDirty();
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
        final var record = dataSource.getRecord(key);
        if (record != null) {
            // TODO delete the node associated with this record. We gotta realize everything to get hashes right
            // and move everything around as needed.
        }
    }

    /**
     * Commits all changes buffered in this virtual map to the {@link VirtualDataSource}.
     */
    public void commit() {
        // Write the leaf paths
        this.dataSource.writeFirstLeafPath(firstLeafPath);
        this.dataSource.writeLastLeafPath(lastLeafPath);

        // Flush everything to the data source. Note that as long as the tree got larger,
        // this will overwrite records at various paths with the new data, which is exactly
        // what we want. For example, if a leaf node used to be at path 1001 but it is now
        // a parent node, the entry will be overwritten with the parent node data. All good!
        // If the tree got smaller, there can be some paths "left over". We maintain a map
        // of all such abandoned paths so that we can delete them during this phase.
        if (root != null) {
            // Commit all of the dirty tree nodes
            root.walkDirty(new VirtualVisitor<>() {
                @Override
                public void visitParent(VirtualTreeInternal<VirtualKey, VirtualValue> parent) {
                    // Create a new record and save it to the data store.
                    final var rec = new VirtualRecord(parent.hash(), parent.getPath());
                    dataSource.setRecord(rec);
                }

                @Override
                public void visitLeaf(VirtualTreeLeaf<VirtualKey, VirtualValue> leaf) {
                    // Create a new record and save it to the data store.
                    final var rec = new VirtualRecord(leaf.hash(), leaf.getPath(), leaf.getKey(), leaf.getData());
                    dataSource.setRecord(rec);
                }
            });

            // Delete all the paths that were removed.
            deletedNodes.keySet().forEach(dataSource::deleteRecord);
        }
    }

    @Override
    public FCMElement copy() {
        throwIfImmutable();
        throwIfReleased();
        return new VirtualMap(this);
    }

    @Override
    public Hash getHash() {
        // Realize the root node, if it doesn't already exist
        final var r = root == null ? realizeRootNode() : root;
        return r.hash(); // recomputes if needed
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
     * Finds the leaf associated with the given key, realizing a ghost if needed,
     * and returning the leaf, or null if one cannot be found.
     *
     * @param key They key. Cannot be null.
     * @return The tree leaf associated with this key, or null if there is not one.
     */
    private VirtualTreeLeaf<VirtualKey, VirtualValue> findLeaf(VirtualKey key) {
        Objects.requireNonNull(key);

        // Check the cache and return the leaf if it was in there.
        final var leaf = cache.get(key);
        if (leaf != null) {
            return leaf;
        }

        // Check for a ghost record and realize the leaf (and its parents) if there is a record.
        final var record = dataSource.getRecord(key);
        if (record != null) {
            return findLeaf(record.getPath());
        }

        // There is no record of this leaf either in the cache or the data source, so
        // the key is bogus.
        return null;
    }

    /**
     * Looks for and returns the VirtualTreeLeaf at this Path. If the leaf was a ghost, it
     * is realized. If the Path does not refer to a leaf node, then null is returned.
     *
     * @param path A path to the leaf. Can be null.
     * @return A VirtualTreeLeaf, if there is one, realizing it if needed. If Path is null, return a null.
     *         If the path doesn't refer to a leaf node (perhaps the path is greater than the last leaf
     *         node or smaller than the first leaf node) then return null.
     */
    private VirtualTreeLeaf<VirtualKey, VirtualValue> findLeaf(VirtualTreePath path) {
        // Quick check for null or root. Always return null in this case
        if (path == null || path.isRoot()) {
            return null;
        }

        // Check for whether there are any leaves at all
        if (lastLeafPath == null) {
            return null;
        }

        // Check whether the path is "greater than" the last leaf node.
        // There cannot be any valid leaf after the lastLeafPath
        if (path.isAfter(lastLeafPath)) {
            return null;
        }

        // Check whether the path is "less than" the first leaf node.
        if (path.isBefore(firstLeafPath)) {
            return null;
        }

        // Now we know that we have a valid leaf path, we start walking the tree from the root.
        // If we encounter a ghost, we realize it into an instance. Eventually, we'll find
        // the leaf, and we can return it.

        // If the root node is a ghost, then we realize it first (we know we will need it)
        if (root == null) {
            root = realizeRootNode();
        }

        final var theLeaf = new AtomicReference<VirtualTreeLeaf<VirtualKey, VirtualValue>>();
        root.walk(new VirtualVisitor<>() {
            private VirtualTreeInternal<VirtualKey, VirtualValue> parent;

            @Override
            public void visitParent(VirtualTreeInternal<VirtualKey, VirtualValue> parent) {
                this.parent = parent;
            }

            @Override
            public void visitUncreated(VirtualTreePath uncreated) {
                // If the uncreated tree path matches the prefix of our path, then we create
                // the node. Or maybe it is actually the leaf itself.
                if (uncreated.isParentOf(path)) {
                    realizeInternalNode(parent, uncreated);
                } else if (uncreated.equals(path)) {
                    theLeaf.set(realizeLeafNode(parent, path));
                }
            }

            @Override
            public void visitLeaf(VirtualTreeLeaf<VirtualKey, VirtualValue> leaf) {
                // We have found the leaf. This is what we want to return.
                theLeaf.set(leaf);
            }
        });

        return theLeaf.get();
    }

    /**
     * Adds a new leaf with the given key and value. At this point, we know for
     * certain that there is no record in the data source for this key, so
     * we can assume that here.
     *
     * @param key A non-null key. Previously validated.
     * @param value The value to add. May be null.
     */
    private void add(VirtualKey key, VirtualValue value) {
        // Gotta create the root, if there isn't one.
        if (root == null) {
            root = realizeRootNode();
        }

        // Find the lastLeafPath which will tell me the new path for this new item
        if (lastLeafPath == null) {
            // There are no leaves! So this one will just go right on the root
            final var leafPath = new VirtualTreePath((byte)1, 0);
            final var newLeaf = new VirtualTreeLeaf<>(NULL_HASH, leafPath, key, value);
            root.setLeftChild(newLeaf);
            // Save state.
            save(newLeaf, key, value);
            dataSource.writeFirstLeafPath(leafPath);
            dataSource.writeLastLeafPath(leafPath);
            cache.put(key, newLeaf);
        } else if (lastLeafPath.isLeft()) {
            // If the lastLeafPath is on the left, then this is easy, we just need
            // to add the new leaf to the right of it, on the same parent (same
            // path with a 1 as the MSB)
            final long mask = 1L << (lastLeafPath.rank - 1);
            final var leafPath = new VirtualTreePath(lastLeafPath.rank, lastLeafPath.path | mask);
            final var newLeaf = new VirtualTreeLeaf<>(NULL_HASH, leafPath, key, value);
            root.setRightChild(newLeaf);
            // Save state.
            save(newLeaf, key, value);
            dataSource.writeLastLeafPath(leafPath);
            cache.put(key, newLeaf);
        } else {
            // We have to make some modification to the tree because there is not
            // an open slot. So we need to pick a slot where a leaf currently exists
            // and then swap it out with a parent, move the leaf to the parent as the
            // "left", and then we can put the new leaf on the right. It turns out,
            // the slot is always the firstLeafPath. If the current firstLeafPath
            // is all the way on the far right of the graph, then the next firstLeafPath
            // will be the first leaf on the far left of the next level. Otherwise,
            // it is just the sibling to the right.
            final var mask = ~(-1L << firstLeafPath.rank);
            final var firstLeafIsOnFarRight = (firstLeafPath.path & mask) == mask;
            final var nextFirstLeafPath = firstLeafIsOnFarRight ?
                    new VirtualTreePath((byte)(firstLeafPath.rank + 1), 0) :
                    VirtualTreePath.getPathForRankAndIndex(firstLeafPath.rank, firstLeafPath.getIndex() + 1);

            // The firstLeafPath points to the old leaf that we want to replace.
            // We need to create a new record that contains the same data that was in the old record,
            // but at a new path. The old record will be overwritten by the new parent node that is
            // taking the place of oldLeaf at that path.
            final var oldLeaf = findLeaf(firstLeafPath);
            final var leafRecord = dataSource.getRecord(firstLeafPath)
                    .withPath(firstLeafPath.getLeftChildPath());
            dataSource.setRecord(leafRecord);

            // Now get the parent. It was some kind of internal node. The parent has some record
            // that may have a cached hash, and that needs to be invalidated since the substructure
            // of the parent is changing
            final var parent = oldLeaf.getParent();
            final var parentRecord = dataSource.getRecord(parent.getPath())
                    .invalidateHash();
            dataSource.setRecord(parentRecord);

            // Create a new internal node that is in the position of the old leaf and attach it to the parent
            // on the left side. We need to write a new record at the path position (which overwrites the old
            // leaf's record at that position. Good thing we already saved it in the new slot!)
            final var newSlotParent = realizeInternalNode(parent, firstLeafPath);
            final var newSlotParentRecord = new VirtualRecord(NULL_HASH, firstLeafPath);
            dataSource.setRecord(newSlotParentRecord);
            // And add this new node to the parent
            if (firstLeafPath.isLeft()) {
                parent.setLeftChild(newSlotParent);
            } else {
                parent.setRightChild(newSlotParent);
            }

            // Put the new item on the right side of the new parent.
            final var leafPath = firstLeafPath.getRightChildPath();
            newLeaf = new VirtualTreeLeaf<>(NULL_HASH, leafPath, key, value);
            save(newLeaf, key, value);
            cache.put(key, newLeaf);
            // Add the leaf nodes to the newSlotParent
            newSlotParent.setLeftChild(oldLeaf);
            newSlotParent.setRightChild(newLeaf);

            // Save the first and last leaf paths
            dataSource.writeFirstLeafPath(nextFirstLeafPath);
            dataSource.writeLastLeafPath(leafPath);
        }
    }

    private void save(VirtualTreeLeaf<VirtualKey, VirtualValue> leaf, VirtualKey key, VirtualValue value) {
        VirtualTreePath leafPath = leaf.getPath();
        leaf.setData(value);
        // Computing the hash here isn't really what I want, because I really only
        // want to compute the hash once per cycle. Bummer.
        final var newRecord = new VirtualRecord(leaf.hash(), leafPath, key, value);
        dataSource.setRecord(newRecord);
    }

    /**
     * Either convert a root ghost node to a realized one, or create a new one.
     *
     * @return A non-null internal root node
     */
    private VirtualTreeInternal<VirtualKey, VirtualValue> realizeRootNode() {
        final var record = dataSource.getRecord(VirtualTreePath.ROOT_PATH);

        // If there is no record, then we need to create and save one
        if (record == null) {
            dataSource.setRecord(new VirtualRecord(NULL_HASH, VirtualTreePath.ROOT_PATH));
        }

        // Create the node and return it
        return new VirtualTreeInternal<>(
                record == null ? NULL_HASH : record.getHash(),
                VirtualTreePath.ROOT_PATH);
    }

    /**
     * Either convert a ghost node to a realized one, or create a new one.
     *
     * @param parent The parent, cannot be null.
     * @param path The path to this node.
     * @return A non-null internal node
     */
    private VirtualTreeInternal<VirtualKey, VirtualValue> realizeInternalNode(
            VirtualTreeInternal<VirtualKey, VirtualValue> parent,
            VirtualTreePath path) {
        Objects.requireNonNull(parent);
        final var record = dataSource.getRecord(path);

        // The parent may be null if this is the root node. Setting the children here should
        // have no side effects -- it shouldn't cause hashes to be invalidated and it
        // shouldn't cause any dirty state. We're just piecing the virtual tree back together.
        final var node = new VirtualTreeInternal<VirtualKey, VirtualValue>(record == null ? NULL_HASH : record.getHash(), path);
        if(path.isLeft()) {
            parent.setLeftChild(node);
        } else {
            parent.setRightChild(node);
        }
        return node;
    }

    /**
     * Convert a ghost node to a realized one.
     *
     * @param parent The parent, cannot be null.
     * @param path The path, cannot be null
     * @return A non-null virtual leaf
     */
    private VirtualTreeLeaf<VirtualKey, VirtualValue> realizeLeafNode(
            VirtualTreeInternal<VirtualKey, VirtualValue> parent,
            VirtualTreePath path) {

        Objects.requireNonNull(parent);
        final var record = dataSource.getRecord(path);
        if (record == null) {
            throw new IllegalStateException("Unexpectedly encountered a null record for a leaf that " +
                    "should have existed.");
        }

        final var leaf = new VirtualTreeLeaf<>(record.getHash(), path, record.getKey(), record.getValue());
        if (path.isLeft()) {
            parent.setLeftChild(leaf);
        } else {
            parent.setRightChild(leaf);
        }
        return leaf;
    }

    public String getAsciiArt() {
        if (root == null) {
            return "<Empty>";
        }

        final var nodeWidth = 8; // Let's reserve this many chars for each node to write their name.

        // Use this for storing all the strings we produce as we go along.
        final var strings = new ArrayList<List<String>>(64);
        final var l = new ArrayList<String>(1);
        l.add("( )");
        strings.add(l);

        // Simple depth-first traversal
        print(strings, root);

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

    private void print(List<List<String>> strings, VirtualTreeNode<VirtualKey, VirtualValue> node) {
        // Write this node out
        final var path = node.getPath();
        final var rank = path.rank;
        final var pnode = node instanceof VirtualTreeInternal;
        strings.get(rank).set(path.getIndex(), "(" + (pnode ? "P" : "L") + ", " + path.path + ")");

        if (pnode) {
            final var parent = (VirtualTreeInternal<VirtualKey, VirtualValue>) node;
            final var left = parent.getLeftChild();
            final var right = parent.getRightChild();
            if (left != null || right != null) {
                // Make sure we have another level down to go.
                if (strings.size() <= rank + 1) {
                    final var size = (int)Math.pow(2, rank+1);
                    final var list = new ArrayList<String>(size);
                    for (int i=0; i<size; i++) {
                        list.add("( )");
                    }
                    strings.add(list);
                }

                if (left != null) {
                    print(strings, left);
                }

                if (right != null) {
                    print(strings, right);
                }
            }
        }
    }

}