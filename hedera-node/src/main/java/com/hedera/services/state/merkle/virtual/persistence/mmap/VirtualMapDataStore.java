package com.hedera.services.state.merkle.virtual.persistence.mmap;

import com.hedera.services.state.merkle.virtual.Account;
import com.hedera.services.state.merkle.virtual.VirtualKey;
import com.hedera.services.state.merkle.virtual.VirtualTreePath;
import com.hedera.services.state.merkle.virtual.VirtualValue;
import com.hedera.services.state.merkle.virtual.persistence.VirtualRecord;
import com.swirlds.common.crypto.Hash;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.ObjectLongHashMap;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * TODO we assume data size is less than hash length and use padded data value as the hash for leaf node
 */
@SuppressWarnings({"unused", "DuplicatedCode"})
public final class VirtualMapDataStore {
    private static final int MB = 1024*1024;
    /** The size of a hash we store in bytes, TODO what happens if we change digest? */
    private static final int HASH_SIZE_BYTES = 384/Byte.SIZE;

    /**
     * Store for all the tree leaves
     *
     * Contains:
     * Account -- Account.BYTES
     * Key -- keySizeBytes
     * Path -- VirtualTreePath.BYTES
     * Value -- dataSizeBytes
     */
    private final MemMapDataStore leafStore;
    /**
     * Store for all the tree parents
     *
     * Contains:
     * Account -- Account.BYTES
     * Path -- VirtualTreePath.BYTES
     * Hash -- HASH_SIZE_BYTES
     */
    private final MemMapDataStore parentStore;
    /**
     * Store for all the paths
     *
     * Contains:
     * Account -- Account.BYTES
     * Key -- 1 byte
     * Path -- VirtualTreePath.BYTES
     */
    private final MemMapDataStore pathStore;


    private final Map<Account, Index> indexMap = new HashMap<>();
    // fast path for default realm
    private final LongObjectHashMap<Index> defaultRealmShardIndex = new LongObjectHashMap<>();

    private final int keySizeBytes;
    private final int dataSizeBytes;

    /**
     * Class for indexes for each account
     */
    private static class Index {
        public final LongLongHashMap parentIndex = new LongLongHashMap();
        public final ObjectLongHashMap<VirtualKey> leafIndex = new ObjectLongHashMap<>();
        public final LongLongHashMap leafPathIndex = new LongLongHashMap();
        public final LongLongHashMap pathIndex = new LongLongHashMap();
    }

    public Index index(Account account) {
        Index index;
        if (account.isDefaultShardAndRealm()) {
            index = defaultRealmShardIndex.get(account.accountNum());
            if (index == null) {
                index = new Index();
                defaultRealmShardIndex.put(account.accountNum(),index);
            }
        } else {
            index = indexMap.get(account);
            if (index == null) {
                index = new Index();
                indexMap.put(account,index);
            }
        }
        return index;
    }

    public Index indexNoCreate(Account account) {
        return account.isDefaultShardAndRealm() ? defaultRealmShardIndex.get(account.accountNum()) : indexMap.get(account);
    }

    /**
     * Create new VirtualMapDataStore
     *
     * @param storageDirectory The path of the directory to store storage files
     * @param keySizeBytes The number of bytes for a key
     * @param dataSizeBytes The number of bytes for a data value TODO we assume data size is less than hash length and use padded data value as the hash for leaf node
     */
    public VirtualMapDataStore(Path storageDirectory, int keySizeBytes, int dataSizeBytes) {
        /* The path of the directory to store storage files */
        this.keySizeBytes = keySizeBytes;
        this.dataSizeBytes = dataSizeBytes;
        int leafStoreSlotSize = Account.BYTES + keySizeBytes + VirtualTreePath.BYTES + dataSizeBytes;
        int parentStoreSlotSize = Account.BYTES + VirtualTreePath.BYTES + HASH_SIZE_BYTES;
        leafStore = new MemMapDataStore(leafStoreSlotSize,100*MB,storageDirectory.resolve("leaves"),"leaves_","dat");
        parentStore = new MemMapDataStore(parentStoreSlotSize,100*MB,storageDirectory.resolve("parents"),"parents_","dat");
        pathStore = new MemMapDataStore(Account.BYTES + VirtualTreePath.BYTES,100*MB,storageDirectory.resolve("paths"),"paths_","dat");
    }

    /**
     * Open all storage files and read the indexes.
     */
    public void open(){
        leafStore.open((location, fileAtSlot) -> {
            try {
                final Account account = new Account(fileAtSlot.readLong(), fileAtSlot.readLong(), fileAtSlot.readLong());
                ObjectLongHashMap<VirtualKey> indexMap = index(account).leafIndex;
                byte[] keyBytes = new byte[keySizeBytes];
                fileAtSlot.read(keyBytes);
                indexMap.put(
                        new VirtualKey(keyBytes),
                        location);

                LongLongHashMap pathIndexMap = index(account).leafPathIndex;
                pathIndexMap.put(
                        fileAtSlot.readLong(),
                        location);
            } catch (IOException e) {
                e.printStackTrace(); // TODO something better here, this should only happen if our files are corrupt
            }
        });
        parentStore.open((location, fileAtSlot) -> {
            try {
                final Account account = new Account(fileAtSlot.readLong(), fileAtSlot.readLong(), fileAtSlot.readLong());
                final long path = fileAtSlot.readLong();
                LongLongHashMap indexMap = index(account).parentIndex;
                indexMap.put(path, location);
            } catch (IOException e) {
                e.printStackTrace(); // TODO something better here, this should only happen if our files are corrupt
            }
        });
        pathStore.open((location, fileAtSlot) -> {
            try {
                final Account account = new Account(fileAtSlot.readLong(), fileAtSlot.readLong(), fileAtSlot.readLong());
                final byte key = fileAtSlot.readByte();
                LongLongHashMap indexMap = index(account).pathIndex;
                indexMap.put(key, location);
            } catch (IOException e) {
                e.printStackTrace(); // TODO something better here, this should only happen if our files are corrupt
            }
        });
    }

    /**
     * Close all storage files
     */
    public void close(){
        leafStore.close();
        parentStore.close();
        pathStore.close();
        indexMap.clear();
        defaultRealmShardIndex.clear();
    }

    /**
     * Make sure all data is flushed to disk. This is an expensive operation. The OS will write all data to disk in the
     * background, so only call this if you need to insure it is written synchronously.
     */
    public void sync(){
        leafStore.sync();
        parentStore.sync();
        pathStore.sync();
    }

    /**
     * Delete a stored parent from storage, if it is stored.
     *
     * @param account The account that the parent belongs to
     * @param parentPath The path of the parent to delete
     */
    public void delete(Account account, long parentPath) {
        long slotLocation = findParent(account, parentPath);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) parentStore.deleteSlot(slotLocation);
    }

    /**
     * Delete a stored leaf from storage, if it is stored.
     *
     * @param account The account that the leaf belongs to
     * @param leaf The leaf to delete
     */
    public void delete(Account account, VirtualRecord leaf){
        long slotLocation = findLeaf(account, leaf.getKey());
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) leafStore.deleteSlot(slotLocation);
    }

    /**
     * Load a tree parent node from storage
     *
     * @param account The account that the parent belongs to
     * @param path The path of the parent to find and load
     * @return a loaded VirtualTreeInternal with path and hash set or null if not found
     */
    public Hash loadParentHash(Account account, long path) {
        long slotLocation = findParent(account,path);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) {
            ByteBuffer buffer = parentStore.accessSlot(slotLocation);
            // Account -- Account.BYTES
            // Path -- VirtualTreePath.BYTES
            buffer.position(buffer.position() + Account.BYTES + VirtualTreePath.BYTES); // jump over
            // Hash -- HASH_SIZE_BYTES
            byte[] hashBytes = new byte[HASH_SIZE_BYTES];
            buffer.get(hashBytes);
            return new Hash(hashBytes);
        }
        return null;
    }

    /**
     * Load a leaf node from storage
     *
     * @param account The account that the leaf belongs to
     * @param key The key of the leaf to find
     * @return a loaded VirtualTreeLeaf or null if not found
     */
    public VirtualRecord loadLeaf(Account account, VirtualKey key){
        long slotLocation = findLeaf(account,key);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) {
            ByteBuffer buffer = leafStore.accessSlot(slotLocation);
            // Account -- Account.BYTES
            buffer.position(buffer.position() + Account.BYTES); // jump over
            // Key -- keySizeBytes
            byte[] keyBytes = new byte[keySizeBytes];
            buffer.get(keyBytes);
            // Path -- VirtualTreePath.BYTES
            long path = buffer.getLong();
            // Value -- dataSizeBytes
            byte[] valueBytes = new byte[dataSizeBytes];
            buffer.get(valueBytes);
            // Hash TODO we assume we can use data value as hash here
            byte[] hashBytes = new byte[HASH_SIZE_BYTES];
            System.arraycopy(valueBytes, 0, hashBytes, 0, valueBytes.length);
            return new VirtualRecord(new Hash(hashBytes), path, new VirtualKey(keyBytes), new VirtualValue(valueBytes));
        }
        return null;
    }

    public VirtualRecord loadLeaf(Account account, long path) {
        long slotLocation = findLeaf(account, path);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) {
            ByteBuffer buffer = leafStore.accessSlot(slotLocation);
            // Account -- Account.BYTES
            buffer.position(buffer.position() + Account.BYTES); // jump over
            // Key -- keySizeBytes
            byte[] keyBytes = new byte[keySizeBytes];
            buffer.get(keyBytes);
            // Path -- VirtualTreePath.BYTES
            buffer.getLong(); // skip it!
            // Value -- dataSizeBytes
            byte[] valueBytes = new byte[dataSizeBytes];
            buffer.get(valueBytes);
            // Hash TODO we assume we can use data value as hash here
            byte[] hashBytes = new byte[HASH_SIZE_BYTES];
            System.arraycopy(valueBytes, 0, hashBytes, 0, valueBytes.length);
            return new VirtualRecord(new Hash(hashBytes), path, new VirtualKey(keyBytes), new VirtualValue(valueBytes));
        }
        return null;
    }

    public VirtualValue get(Account account, VirtualKey key) {
        long slotLocation = findLeaf(account,key);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) {
            ByteBuffer buffer = leafStore.accessSlot(slotLocation);
            // Account -- Account.BYTES
            buffer.position(buffer.position() + Account.BYTES + keySizeBytes + VirtualTreePath.BYTES); // jump over
            // Value -- dataSizeBytes
            byte[] valueBytes = new byte[dataSizeBytes];
            buffer.get(valueBytes);
            return new VirtualValue(valueBytes);
        }
        return null;
    }

    /**
     * Save a VirtualTreeInternal parent node into storage
     *
     * @param account The account that the parent belongs to
     * @param parentPath The path of the parent node to save
     * @param hash The parent's hash
     */
    public void save(Account account, long parentPath, Hash hash) {
        // if already stored and if so it is an update
        long slotLocation = findParent(account, parentPath);
        if (slotLocation == MemMapDataStore.NOT_FOUND_LOCATION) {
            // find a new slot location
            slotLocation = parentStore.getNewSlot();
            // store in index
            index(account).parentIndex.put(parentPath, slotLocation);
        }
        // write parent into slot
        ByteBuffer buffer = parentStore.accessSlot(slotLocation);
        // Account -- Account.BYTES
        buffer.putLong(account.shardNum());
        buffer.putLong(account.realmNum());
        buffer.putLong(account.accountNum());
        // Path -- VirtualTreePath.BYTES
        buffer.putLong(parentPath);
        // Hash -- HASH_SIZE_BYTES
        buffer.put(hash.getValue());

    }

    /**
     * Save a VirtualTreeLeaf to storage
     *
     * @param account The account that the leaf belongs to
     * @param leaf The leaf to store
     */
    public void save(Account account, VirtualRecord leaf) {
        // if already stored and if so it is an update
        long slotLocation = findLeaf(account,leaf.getKey());
        if (slotLocation == MemMapDataStore.NOT_FOUND_LOCATION) {
            // find a new slot location
            slotLocation = leafStore.getNewSlot();
            // store in indexes
            index(account).leafIndex.put(leaf.getKey(), slotLocation);
            index(account).leafPathIndex.put(leaf.getPath(), slotLocation);
        }
        // write leaf into slot
        ByteBuffer buffer = leafStore.accessSlot(slotLocation);
        // Account -- Account.BYTES
        buffer.putLong(account.shardNum());
        buffer.putLong(account.realmNum());
        buffer.putLong(account.accountNum());
        // Key -- keySizeBytes
        leaf.getKey().writeToByteBuffer(buffer);
        // Path -- VirtualTreePath.BYTES
        buffer.putLong(leaf.getPath());
        // Value -- dataSizeBytes
        leaf.getValue().writeToByteBuffer(buffer);
    }

    /**
     * Write a tree path to storage
     *
     * @param account The account the path belongs to
     * @param key The byte key for the path
     * @param path The path to write
     */
    public void save(Account account, byte key, long path) {
        // if already stored and if so it is an update
        long slotLocation = findPath(account, key);
        if (slotLocation == MemMapDataStore.NOT_FOUND_LOCATION) {
            // find a new slot location
            slotLocation = pathStore.getNewSlot();
            // store in index
            index(account).pathIndex.put(key, slotLocation);

        }
        // write path into slot
        ByteBuffer buffer = pathStore.accessSlot(slotLocation);
        // Account -- Account.BYTES
        buffer.putLong(account.shardNum());
        buffer.putLong(account.realmNum());
        buffer.putLong(account.accountNum());
        // Key -- 1 byte
        buffer.put(key);
        // Path -- VirtualTreePath.BYTES
        buffer.putLong(path);
    }

    /**
     * Load a Path from store
     *
     * @param account The account the path belongs to
     * @param key The byte key for the path
     * @return the Path if it was found in store or null
     */
    public long load(Account account, byte key) {
        long slotLocation = findPath(account,key);
        if (slotLocation != MemMapDataStore.NOT_FOUND_LOCATION) {
            // read path from slot
            ByteBuffer buffer = pathStore.accessSlot(slotLocation);
            // Account -- Account.BYTES
            // Key -- 1 byte
            buffer.position(buffer.position() + Account.BYTES); // jump over
            // Path -- VirtualTreePath.BYTES
            return buffer.getLong();
        } else {
            return VirtualTreePath.INVALID_PATH;
        }
    }

    /**
     * Find the slot location of a parent node
     *
     * @param account The account that the parent belongs to
     * @param path The path of the parent to find
     * @return slot location of parent if it is stored or null if not found
     */
    private long findParent(Account account, long path) {
        Index index = indexNoCreate(account);
        if (index != null) return index.parentIndex.getIfAbsent(path, MemMapDataStore.NOT_FOUND_LOCATION);
        return MemMapDataStore.NOT_FOUND_LOCATION;
    }

    /**
     * Find the slot location of a leaf node
     *
     * @param account The account that the leaf belongs to
     * @param key The key of the leaf to find
     * @return slot location of leaf if it is stored or null if not found
     */
    private long findLeaf(Account account, VirtualKey key) {
        Index index = indexNoCreate(account);
        if (index != null) return index.leafIndex.getIfAbsent(key,MemMapDataStore.NOT_FOUND_LOCATION);
        return MemMapDataStore.NOT_FOUND_LOCATION;
    }

    private long findLeaf(Account account, long path) {
        Index index = indexNoCreate(account);
        if (index != null) return index.leafPathIndex.getIfAbsent(path,MemMapDataStore.NOT_FOUND_LOCATION);
        return MemMapDataStore.NOT_FOUND_LOCATION;
    }

    /**
     * Find the slot location of a path
     *
     * @param account The account that the path belongs to
     * @param key The path of the path to find
     * @return slot location of path if it is stored or null if not found
     */
    private long findPath(Account account, byte key) {
        Index index = indexNoCreate(account);
        if (index != null) return index.pathIndex.getIfAbsent(key, MemMapDataStore.NOT_FOUND_LOCATION);
        return MemMapDataStore.NOT_FOUND_LOCATION;
    }

}
