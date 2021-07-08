package com.hedera.services.state.initialization;

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

import com.hedera.services.state.merkle.MerkleUniqueToken;
import com.hedera.services.state.merkle.MerkleUniqueTokenId;
import com.hedera.services.state.submerkle.EntityId;
import com.swirlds.fchashmap.FCOneToManyRelation;
import com.swirlds.fcmap.FCMap;

public class ViewBuilder {
	public static void rebuildUniqueTokenViews(
			FCMap<MerkleUniqueTokenId, MerkleUniqueToken> uniqueTokens,
			FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueTokenAssociations,
			FCOneToManyRelation<EntityId, MerkleUniqueTokenId> uniqueOwnershipAssociations
	) {
		uniqueTokens.forEach((id, uniq) -> {
			uniqueTokenAssociations.associate(id.tokenId(), id);
			uniqueOwnershipAssociations.associate(uniq.getOwner(), id);
		});
	}
}
