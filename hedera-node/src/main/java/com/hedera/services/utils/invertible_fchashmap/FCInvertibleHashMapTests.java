///*
// * (c) 2016-2021 Swirlds, Inc.
// *
// * This software is the confidential and proprietary information of
// * Swirlds, Inc. ("Confidential Information"). You shall not
// * disclose such Confidential Information and shall use it only in
// * accordance with the terms of the license agreement you entered into
// * with Swirlds.
// *
// * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
// * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
// * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
// * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
// * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
// * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
// */
//package com.hedera.services.utils.invertible_fchashmap;
//
//
//import org.apache.commons.lang3.tuple.Pair;
//
//import java.util.*;
//
////import static org.junit.jupiter.api.Assertions.assertEquals;
//
////@DisplayName("InverseFCHashMap Tests")
//public class FCInvertibleHashMapTests {
//
//	/**
//	 * Given a map, build a inverse mapping between values and all keys that refer to a given value.
//	 */
//	private static Map<Integer, Set<Integer>> buildInverseReferenceMap(final Map<Integer, Integer> referenceMap) {
//
//		final Map<Integer, Set<Integer>> map = new HashMap<>();
//
//		for (final Integer value : referenceMap.values()) {
//			final Set<Integer> keySet = new HashSet<>();
//			map.put(value, keySet);
//			for (final Integer key : referenceMap.keySet()) {
//				if (Objects.equals(referenceMap.get(key), value)) {
//					keySet.add(key);
//				}
//			}
//		}
//
//		return map;
//	}
//
//	/**
//	 * Ensure that the data in the invertible map matches the data in the reference map
//	 */
//	private static void checkValidity(
//			final List<Pair<
//					Map<Integer, Integer>,
//					FCInvertibleHashMap<Integer, IdentifiableInt, Integer>>> mapPairs) {
//
//		for (final Pair<
//				Map<Integer, Integer>,
//				FCInvertibleHashMap<Integer, IdentifiableInt, Integer>> pair : mapPairs) {
//
//			final Map<Integer, Integer> referenceMap = pair.getLeft();
//			final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> map = pair.getRight();
//
//			assertEquals(referenceMap.keySet(), map.keySet(),
//					"maps should have the same keys");
//
//			for (final Integer key : referenceMap.keySet()) {
//				assertEquals(referenceMap.get(key), map.get(key).getValue(),
//						"values should be the same in each map");
//			}
//
//			final Map<Integer, Set<Integer>> inverseReferenceMap = buildInverseReferenceMap(referenceMap);
//			for (final Integer value : referenceMap.values()) {
//
//				final HashSet<Integer> keys = new HashSet<>();
//				final Iterator<Integer> iterator = map.inverseGet(new IdentifiableInt(value));
//				while (iterator.hasNext()) {
//					keys.add(iterator.next());
//				}
//
//				assertEquals(inverseReferenceMap.get(value), keys, "inverse mapping should match");
//			}
//		}
//	}
//
//	/**
//	 * Insert an object into the map and check its validity
//	 */
//	private static void put(
//			final Map<Integer, Integer> referenceMap,
//			final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> map,
//			final Integer key,
//			final Integer value) {
//
//		final Integer referenceValue = referenceMap.put(key, value);
//		final IdentifiableInt prevValue = map.put(key, new IdentifiableInt(value));
//
//		assertEquals(referenceValue, prevValue == null ? null : prevValue.getValue(),
//				"value returned by put should match reference value");
//	}
//
//	/**
//	 * Remove an object into the map and check its validity
//	 */
//	private static void remove(
//			final Map<Integer, Integer> referenceMap,
//			final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> map,
//			final Integer key) {
//
//		final Integer referenceValue = referenceMap.remove(key);
//		final IdentifiableInt value = map.remove(key);
//
//		assertEquals(referenceValue, value.getValue(), "value returned by remove should match reference value");
//	}
//
//	private static void copy(final List<Pair<
//			Map<Integer, Integer>,
//			FCInvertibleHashMap<Integer, IdentifiableInt, Integer>>> mapPairs) {
//
//		final Pair<
//				Map<Integer, Integer>,
//				FCInvertibleHashMap<Integer, IdentifiableInt, Integer>> lastPair =
//				mapPairs.get(mapPairs.size() - 1);
//
//		final Map<Integer, Integer> referenceMapCopy = new HashMap<>(lastPair.getLeft());
//		final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> mapCopy = lastPair.getRight().copy();
//
//		mapPairs.add(Pair.of(referenceMapCopy, mapCopy));
//	}
//
//	private static void deleteNthOldestCopy(
//			final List<Pair<
//					Map<Integer, Integer>,
//					FCInvertibleHashMap<Integer, IdentifiableInt, Integer>>> mapPairs,
//			final int copyToDelete) {
//
//		if (copyToDelete == mapPairs.size() - 1 && mapPairs.size() > 1) {
//			throw new IllegalArgumentException("mutable copy should be deleted last");
//		}
//
//		final Pair<
//				Map<Integer, Integer>,
//				FCInvertibleHashMap<Integer, IdentifiableInt, Integer>> pair = mapPairs.get(copyToDelete);
//
//		pair.getRight().release();
//
//		mapPairs.remove(copyToDelete);
//	}
//
//	@Test
//	@DisplayName("InverseFCHashMap Basic Test")
//	void inverseFCHashMapBasicTest() {
//
//		final Random random = new Random(0);
//
//		// Time complexity is something like O(maxValue * numberOfKeys ^ 3), so don't choose large values.
//		final int maxValue = 10;
//		final int numberOfKeys = 300;
//
//		final List<Pair<
//				Map<Integer, Integer>,
//				FCInvertibleHashMap<Integer, IdentifiableInt, Integer>>> mapPairs = new LinkedList<>();
//
//		mapPairs.add(Pair.of(new HashMap<>(), new FCInvertibleHashMap<>()));
//
//		checkValidity(mapPairs);
//
//		// Add a bunch of key value pairs.
//		// Every third operation also delete a random key.
//		// Every tenth operation make a copy.
//		// Every twentieth operation delete the oldest copy
//		for (int i = 0; i < numberOfKeys; i++) {
//
//			final Pair<
//					Map<Integer, Integer>,
//					FCInvertibleHashMap<Integer, IdentifiableInt, Integer>> lastPair =
//					mapPairs.get(mapPairs.size() - 1);
//
//			final Map<Integer, Integer> referenceMap = lastPair.getLeft();
//			final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> map = lastPair.getRight();
//
//			put(referenceMap, map, random.nextInt(), random.nextInt(maxValue));
//			checkValidity(mapPairs);
//
//			if (i != 0 && i % 3 == 0) {
//				// Delete a value
//				final List<Integer> keyList = new ArrayList<>(referenceMap.keySet());
//				Integer key = keyList.get(random.nextInt(keyList.size()));
//				remove(referenceMap, map, key);
//				checkValidity(mapPairs);
//			}
//
//			if (i != 0 && i % 10 == 0) {
//				// make a fast copy
//				copy(mapPairs);
//				checkValidity(mapPairs);
//			}
//
//			if (i != 0 && i % 20 == 0) {
//				deleteNthOldestCopy(mapPairs, 0);
//				checkValidity(mapPairs);
//			}
//		}
//
//		// Remove all remaining keys
//		final Pair<
//				Map<Integer, Integer>,
//				FCInvertibleHashMap<Integer, IdentifiableInt, Integer>> lastPair =
//				mapPairs.get(mapPairs.size() - 1);
//
//		final Map<Integer, Integer> referenceMap = lastPair.getLeft();
//		final FCInvertibleHashMap<Integer, IdentifiableInt, Integer> map = lastPair.getRight();
//		for (final Integer key : new ArrayList<>(referenceMap.keySet())) {
//			remove(referenceMap, map, key);
//			checkValidity(mapPairs);
//		}
//
//		// Release remaining maps in a random order
//		while (mapPairs.size() > 1) {
//			deleteNthOldestCopy(mapPairs, random.nextInt(mapPairs.size() - 1));
//			checkValidity(mapPairs);
//		}
//
//		// Release the mutable copy
//		deleteNthOldestCopy(mapPairs, 0);
//		checkValidity(mapPairs);
//
//		assertEquals(0, mapPairs.size(), "all maps should have been released");
//	}
//
//	// TODO test with null values
//	// TODO test garbage collection
//	// TODO test deletion out of order
//	// TODO verify thread death
//
//}