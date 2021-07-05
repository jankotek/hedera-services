package com.hedera.services.state.merkle.virtual.persistence;

import com.swirlds.common.FastCopyable;
import com.swirlds.common.crypto.Hash;
import com.swirlds.common.io.SelfSerializable;
import com.swirlds.fcmap.FCVirtualRecord;
import com.swirlds.fcmap.VKey;

import java.io.IOException;

/**
 * Interface for Fast Copyable Virtual Map Data Store
 *
 * It should be thread safe and can be used by multiple-threads.
 *
 * @param <LP> The type for leaf paths, must implement SelfSerializable
 * @param <LK> The type for leaf keys, must implement SelfSerializable
 * @param <LV> The type for leaf data, must implement SelfSerializable
 */
public interface FCVirtualMapLeafStore<LK extends VKey,
        LP extends SelfSerializable, LV extends SelfSerializable>
        extends FastCopyable {

    /**
     * Delete a stored leaf from storage, if it is stored.
     *
     * @param leafKey The key for the leaf to delete
     * @param leafPath The path for the leaf to delete
     */
    void deleteLeaf(LK leafKey, LP leafPath) throws IOException;

    /**
     * Check if this store contains a leaf by key
     *
     * @param leafKey The key of the leaf to check for
     * @return true if that leaf is stored, false if it is not known
     */
    boolean containsLeafKey(LK leafKey) throws IOException;

    /**
     * Get the number of leaves for a given account
     *
     * @return 0 if the account doesn't exist otherwise the number of leaves stored for the account
     */
    int leafCount();

    /**
     * Load a leaf value from storage given the key for it
     *
     * @param key The key of the leaf to find
     * @return a loaded leaf data or null if not found
     */
    LV loadLeafValueByKey(LK key) throws IOException;

    /**
     * Load a leaf value from storage given a path to it
     *
     * @param leafPath The path to the leaf
     * @return a loaded leaf data or null if not found
     */
    LV loadLeafValueByPath(LP leafPath) throws IOException;

    /**
     * Load a leaf value from storage given a path to it
     *
     * @param key The key of the leaf to find
     * @return a loaded leaf data or null if not found
     */
    LP loadLeafPathByKey(LK key) throws IOException;

    /**
     * Load a leaf value from storage given a path to it
     *
     * @param leafPath The path to the leaf
     * @return a loaded leaf key and data or null if not found
     */
    FCVirtualRecord<LK, LV> loadLeafRecordByPath(LP leafPath) throws IOException;

    /**
     * Update the path to a leaf
     *
     * @param oldPath The current path to the leaf in the store
     */
    void updateLeafPath(LP oldPath, LP newPath) throws IOException;

    /**
     * Save a VirtualTreeLeaf to storage
     *
     * @param leafKey The key for the leaf to store
     * @param leafPath The path for the leaf to store
     * @param leafData The data for the leaf to store
     */
    void saveLeaf(LK leafKey, LP leafPath, LV leafData) throws IOException;

    @Override
    FCVirtualMapLeafStore<LK, LP, LV> copy();
}
