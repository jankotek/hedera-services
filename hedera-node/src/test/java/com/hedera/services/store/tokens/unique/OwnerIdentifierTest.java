/*
 * -
 * ‌
 * Hedera Services Node
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *       http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.services.store.tokens.unique;

import com.hedera.services.state.submerkle.EntityId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerIdentifierTest {

	private OwnerIdentifier o1;
	private OwnerIdentifier o2;
	private EntityId entityId1;
	private EntityId entityId2;

	@BeforeEach
	void setup() {
		entityId1 = new EntityId(1, 2, 3);
		entityId2 = new EntityId(1, 2, 3);
		o1 = new OwnerIdentifier(entityId1);
		o2 = new OwnerIdentifier(entityId2);
	}

	@Test
	void testEquals() {
		var eqWithDiffObj = o1.equals(o2);
		var eqWithSameObj = o1.equals(o1);
		assertTrue(eqWithDiffObj);
		assertTrue(eqWithSameObj);
	}

	@Test
	void testHashCode() {
		var hc1 = o1.hashCode();
		var hc2 = o2.hashCode();
		assertEquals(hc1, hc2);
	}

	@Test
	void testEqualityWithNull() {
		assertFalse(o1.equals(null));
	}
}