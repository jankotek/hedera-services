package com.hedera.services.store.models;

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

import com.hedera.services.state.submerkle.RichInstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UniqueTokenTest {

	@Test
	void objectContractWorks(){
		var subj = new UniqueToken(Id.DEFAULT, 1);
		assertEquals(1, subj.getSerialNumber());
		assertEquals(Id.DEFAULT, subj.getTokenId());

		var metadata = new byte[]{107, 117, 114};
		subj = new UniqueToken(Id.DEFAULT, 1, RichInstant.MISSING_INSTANT, new Id(1, 2, 3), new byte[]{111, 23, 85});
		assertEquals(RichInstant.MISSING_INSTANT, subj.getCreationTime());
		assertEquals(new Id(1,2 ,3), subj.getOwner());
		subj.setSerialNumber(2);
		assertEquals(2, subj.getSerialNumber());

		metadata = new byte[]{1, 2, 3};
		subj.setMetadata(metadata);
		assertEquals(metadata, subj.getMetadata());
		subj.setTokenId(Id.DEFAULT);
		assertEquals(Id.DEFAULT, subj.getTokenId());
		subj.setCreationTime(RichInstant.MISSING_INSTANT);
		assertEquals(RichInstant.MISSING_INSTANT, subj.getCreationTime());
	}

}