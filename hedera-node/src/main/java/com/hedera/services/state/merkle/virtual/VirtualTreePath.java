package com.hedera.services.state.merkle.virtual;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Represents a path in a virtual tree from the root to a node. The path is
 * represented as a string of bits and a rank, such that each bit represents either
 * the left or right node as you traverse from the root node downwards. The root
 * node is found at rank 0, while the first level of children are found at rank 1,
 * and so forth down the tree. Every node in the tree has a unique path at any given
 * point in time.
 *
 * TODO: NOTE! To optimize, we now use MSB->LSB of breadcrumbs! So 10 is left first (1)
 * and then right (0), and 110 is left first (1) and then left again (1) and then right (0).
 */
public final class VirtualTreePath {
    /** Site of an account when serialized to bytes */
    public static final int BYTES = Long.BYTES;

    private static final int[] LOG2_TABLE = new int[]{
            0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
    };

    /**
     * A special constant that represents the Path of a root node. It isn't necessary
     * for this constant to be used, but it makes the code a little more readable.
     */
    public static final long ROOT_PATH = 0L;
    public static final long INVALID_PATH = -1L; // All 1's!

    // Utility class only
    private VirtualTreePath() {
    }

    // Gets the rank part of the pathId
    public static int getRank(long path) {
//        var rank = 0;
//
//        if (path > 0x00000000FFFFFFFF) {
//            path >>= 32;
//            rank |= 32;
//        }
//        if (path > 0x000000000000FFFF) {
//            path >>= 16;
//            rank |= 16;
//        }
//        if (path > 0x00000000000000FF) {
//            path >>= 8;
//            rank |= 8;
//        }
//        rank |= LOG2_TABLE[(int)path];
//
//        return (byte) rank;

        if (path == 0) {
            return 0;
        } else if (path == 1) {
            return 1;
        } else if (path == 2) {
            return 1;
        }

        return (63 - Long.numberOfLeadingZeros(path + 1));
    }

    /**
     * Gets whether this path refers to the root node. The root node path is
     * special, since rank is 0. No leaf node or other internal node can
     * have a rank of 0.
     *
     * @return Whether this path refers to the root node.
     */
    public static boolean isRootPath(long path) {
        return path == 0;
    }

    // Gets the position of the node represented by this path at its rank. For example,
    // the node on the far left would be index "0" while the node on the far right
    // has an index depending on the rank (rank 1, index = 1; rank 2, index = 3; rank N, index = (2^N)-1)


    /**
     * Gets whether this Path represents a left-side node.
     *
     * @return Whether this Path is a left-side node.
     */
    public static boolean isLeft(long path) {
        return (path & 0x1) == 1;
    }

    /**
     * Gets the path of a node that would be the parent of the node represented by this path.
     *
     * @return The Path of the parent. This may be null if this Path is already the root (root nodes
     *         do not have a parent).
     */
    public static long getParentPath(long path) {
        return (path - 1) >> 1;
    }

    /**
     * Gets the path of the left-child.
     *
     * @return The path of the left child. This is never null.
     */
    public static long getLeftChildPath(long path) {
        return (path << 1) + 1;
    }

    /**
     * Gets the part of the right-child.
     *
     * @return The path of the right child. This is never null.
     */
    public static long getRightChildPath(long path) {
        return (path << 1) + 2;
    }
}
