package com.hedera.services.state.merkle.v2;

import com.hedera.services.state.merkle.v2.persistance.LongIndex;
import com.hedera.services.state.merkle.v2.persistance.SlotStore;
import com.hedera.services.state.merkle.v2_swirlds.VFCDataSource;
import com.hedera.services.state.merkle.v2_swirlds.VKey;
import com.hedera.services.state.merkle.v2_swirlds.VValue;
import com.swirlds.common.crypto.DigestType;
import com.swirlds.common.crypto.Hash;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.function.Supplier;

/**
 * VFCDataSource implementation that back out to two SlotStores and a LongIndex
 *
 * Stores [Hash], [leafDataIndex] slots in nodeStore.
 * Stores [KeySerializationVersion],[KeyBytes],[ValueSerializationVersion],[ValueBytes] in leafDataStore
 *
 * Node store data size is NODE_STORE_SLOTS_SIZE
 * Leaf store data size is Integer.BYTES + keySizeBytes + Integer.BYTES + valueSizeBytes
 *
 * @param <K> the type for leaf keys
 * @param <V> the type for leaf values
 */
public class VFCDataSourceImpl<K extends VKey, V extends VValue> implements VFCDataSource<K, V> {
    private final static int HASH_SIZE = Integer.BYTES+DigestType.SHA_384.digestLength();
    public final static int NODE_STORE_SLOTS_SIZE = HASH_SIZE + Long.BYTES;
    private final static int NULL_DATA_INDEX = -1;
    private final Supplier<K> keyConstructor;
    private final Supplier<V> valueConstructor;
    private final int keySizeBytes;
    @SuppressWarnings({"FieldCanBeLocal", "unused"})
    private final int valueSizeBytes;
    private final SlotStore nodeStore;
    private final SlotStore leafDataStore;
    private final LongIndex<K> leafKeyIndex;

    public VFCDataSourceImpl(int keySizeBytes, Supplier<K> keyConstructor, int valueSizeBytes, Supplier<V> valueConstructor,
                             SlotStore nodeStore, SlotStore leafDataStore, LongIndex<K> leafKeyIndex) {
        this.keySizeBytes = Integer.BYTES + keySizeBytes; // needs to include serialization version
        this.keyConstructor = keyConstructor;
        this.valueSizeBytes = Integer.BYTES + valueSizeBytes; // needs to include serialization version
        this.valueConstructor = valueConstructor;
        this.nodeStore = nodeStore;
        this.leafDataStore = leafDataStore;
        this.leafKeyIndex = leafKeyIndex;
    }

    /**
     * Close all data stores
     *
     * @throws IOException if there was a problem closing
     */
    @Override
    public void close() throws IOException {
        nodeStore.close();
        leafDataStore.close();
        leafKeyIndex.close();
    }

    /**
     * Load hash for a node with given path
     *
     * @param path the path to get hash for
     * @return loaded hash or null if hash is not stored
     * @throws IOException if there was a problem loading hash
     */
    @Override
    public Hash loadHash(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer buf = nodeStore.accessSlot(path,false);
        return buf != null ? Hash.fromByteBuffer(buf) : null;
    }

    /**
     * Load leaf's value
     *
     * @param path the path for leaf to get value for
     * @return loaded leaf value or null if none was saved
     * @throws IOException if there was a problem loading leaf data
     */
    @Override
    public V loadLeafValue(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer buf = nodeStore.accessSlot(path,false);
        if (buf == null) return null;
        int basePos = buf.position();
        // skip over hash
        buf.position(basePos + HASH_SIZE);
        // read data index
        long dataIndex = buf.getLong();
        // check there was some data stored
        if (dataIndex == NULL_DATA_INDEX) return null;
        // read
        ByteBuffer leafDataBuf = leafDataStore.accessSlot(dataIndex,false);
        if (leafDataBuf == null) return null;
        basePos = leafDataBuf.position();
        // skip over key
        leafDataBuf.position(basePos+keySizeBytes);
        // deserialize data
        int valueSerializationVersion = leafDataBuf.getInt();
        V value = valueConstructor.get();
        value.deserialize(leafDataBuf, valueSerializationVersion);
        return value;
    }

    /**
     * Load leaf's value
     *
     * @param key the key for leaf to get value for
     * @return loaded leaf value or null if none was saved
     * @throws IOException if there was a problem loading leaf data
     */
    @Override
    public V loadLeafValue(K key) throws IOException {
        Long path = leafKeyIndex.get(key);
        if (path == null) return null;
        return loadLeafValue(path);
    }

    /**
     * Load a leaf's key
     *
     * @param path the path to the leaf to load key for
     * @return the loaded key for leaf or null if none was saved
     * @throws IOException if there was a problem loading key
     */
    @Override
    public K loadLeafKey(long path) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        ByteBuffer buf = nodeStore.accessSlot(path,false);
        if (buf == null) return null;
        int basePos = buf.position();
        // skip over hash
        buf.position(basePos + HASH_SIZE);
        // read data index
        long dataIndex = buf.getLong();
        // check there was some data stored
        if (dataIndex == NULL_DATA_INDEX) return null;
        // read
        ByteBuffer leafDataBuf = leafDataStore.accessSlot(dataIndex,false);
        // deserialize data
        int keySerializationVersion = leafDataBuf.getInt();
        K key = keyConstructor.get();
        key.deserialize(leafDataBuf, keySerializationVersion);
        return key;
    }

    /**
     * Load path for a leaf
     *
     * @param key the key for the leaf to get path for
     * @return loaded path or null if none is stored for key
     * @throws IOException if there was a problem loading leaf's path
     */
    @Override
    public long loadLeafPath(K key) throws IOException {
        Long path = leafKeyIndex.get(key);
        return path != null ? path : INVALID_PATH;
    }

    /**
     * Save a hash for a internal node
     *
     * @param path the path of the node to save hash for, if nothing has been stored for this path before it will be created.
     * @param hash a non-null hash to write
     * @throws IOException if there was a problem writing the hash
     */
    @Override
    public void saveInternal(long path, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null)  throw new IllegalArgumentException("Hash is null");
        // access slot
        ByteBuffer nodeDataBuf = nodeStore.accessSlot(path,true);
        // write hash
        Hash.toByteBuffer(hash,nodeDataBuf);
    }

    /**
     * Update a leaf moving it from one path to another. Note! any existing node at the newPath will be overridden.
     *
     * @param oldPath Must be an existing valid path
     * @param newPath Can be larger than current max path, allowing tree to grow
     * @param key The key for the leaf so we can update key->path index
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(long oldPath, long newPath, K key) throws IOException {
        if (oldPath < 0) throw new IllegalArgumentException("path is less than 0");
        if (newPath < 0) throw new IllegalArgumentException("path is less than 0");
        // get new path node buffer
        ByteBuffer newPathNodeBuffer = nodeStore.accessSlot(newPath,false);
        int basePos;
        // check if the node being over ridden has data
        if (newPathNodeBuffer != null) {
            basePos = newPathNodeBuffer.position();
            // skip over hash
            newPathNodeBuffer.position(basePos+HASH_SIZE);
            // read data index
            long dataIndex = newPathNodeBuffer.getLong();
            // if there was some data then delete from leaf data store
            if (dataIndex == NULL_DATA_INDEX) {
                leafDataStore.deleteSlot(dataIndex);
            }
        } else {
            // create new node buffer
            newPathNodeBuffer = nodeStore.accessSlot(oldPath,true);
            basePos = newPathNodeBuffer.position();
        }
        // access the old node so we can read data
        ByteBuffer oldPathNodeBuffer = nodeStore.accessSlot(oldPath,false);
        if (oldPathNodeBuffer == null) throw new IOException("Tried to move a leaf that did not exist at path ["+oldPath+"]");
        // copy old buffer to new
        newPathNodeBuffer.position(basePos);
        newPathNodeBuffer.put(oldPathNodeBuffer);
        // update index
        leafKeyIndex.put(key,newPath);
    }

    /**
     * Update a leaf at given path, the leaf must exist. Writes hash and value.
     *
     * @param path valid path to saved leaf
     * @param value the value for new leaf, can be null
     * @param hash non-null hash for the leaf
     * @throws IOException if there was a problem saving leaf update
     */
    @Override
    public void updateLeaf(long path, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path ["+path+"]");
        // get node buffer
        ByteBuffer nodeBuffer = nodeStore.accessSlot(path,false);
        if (nodeBuffer == null) throw new IllegalArgumentException("Can not update a non-existent leaf at path ["+path+"]");
        int basePos = nodeBuffer.position();
        // write hash
        Hash.toByteBuffer(hash,nodeBuffer);
        // skip over hash in case it did not use all its bytes
        nodeBuffer.position(basePos+HASH_SIZE);
        // read data index
        long dataIndex = nodeBuffer.getLong();
        // check there was some data stored
        if (dataIndex == NULL_DATA_INDEX) throw new IOException("Tried to update leaf value when no previous value was saved. path ["+path+"]");
        // write value
        ByteBuffer leafDataBuf = leafDataStore.accessSlot(dataIndex,false);
        int leafBasePos = leafDataBuf.position();
        // jump over key
        leafDataBuf.position(leafBasePos+keySizeBytes);
        // serialize data
        leafDataBuf.putInt(value.getVersion());
        value.serialize(leafDataBuf);
    }

    /**
     * Add a new leaf to store
     *
     * @param path the path for the new leaf
     * @param key the non-null key for the new leaf
     * @param value the value for new leaf, can be null
     * @param hash the non-null hash for new leaf
     * @throws IOException if there was a problem writing leaf
     */
    @Override
    public void addLeaf(long path, K key, V value, Hash hash) throws IOException {
        if (path < 0) throw new IllegalArgumentException("path is less than 0");
        if (hash == null) throw new IllegalArgumentException("Can not save null hash for leaf at path ["+path+"]");
        if (key == null) throw new IllegalArgumentException("Can not save null key for leaf at path ["+path+"]");
        // insert the new path in key->path index
        leafKeyIndex.put(key,path);
        // get node buffer
        ByteBuffer nodeBuffer = nodeStore.accessSlot(path,true);
        int basePos = nodeBuffer.position();
        // write hash
        Hash.toByteBuffer(hash,nodeBuffer);
        // skip over hash in case it did not use all its bytes
        nodeBuffer.position(basePos+HASH_SIZE);
        // new so create data index
        long dataIndex = leafDataStore.getNewSlot();
        // write new data index to node store
        nodeBuffer.putLong(dataIndex);
        // write leaf data
        ByteBuffer leafDataBuf = leafDataStore.accessSlot(dataIndex,true);
        int leafBasePos = leafDataBuf.position();
        // serialize key
        leafDataBuf.putInt(key.getVersion());
        key.serialize(leafDataBuf);
        // jump over key size in case key did not use all its bytes
        leafDataBuf.position(leafBasePos+keySizeBytes);
        // serialize value
        leafDataBuf.putInt(value.getVersion());
        value.serialize(leafDataBuf);
    }

}