package com.hedera.services.ledger;

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

import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.NftId;
import com.hedera.test.utils.IdUtils;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.NftTransfer;
import org.junit.jupiter.api.Test;

import static com.hedera.services.ledger.BalanceChange.NO_TOKEN_FOR_HBAR_ADJUST;
import static com.hedera.services.ledger.BalanceChange.changingNftOwnership;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.nftXfer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BalanceChangeTest {
	private final Id t = new Id(1, 2, 3);
	private final long delta = -1_234L;
	private final long serialNo = 1234L;
	private final AccountID a = asAccount("1.2.3");
	private final AccountID b = asAccount("2.3.4");

	@Test
	void objectContractSanityChecks() {
		// given:
		final var hbarChange = IdUtils.hbarChange(a, delta);
		final var tokenChange = IdUtils.tokenChange(t, a, delta);
		final var nftChange = changingNftOwnership(t, t.asGrpcToken(), nftXfer(a, b, serialNo));
		// and:
		final var hbarRepr = "BalanceChange{token=ℏ, account=Id{shard=1, realm=2, num=3}, units=-1234}";
		final var tokenRepr = "BalanceChange{token=Id{shard=1, realm=2, num=3}, " +
				"account=Id{shard=1, realm=2, num=3}, units=-1234}";
		final var nftRepr = "BalanceChange{nft=Id{shard=1, realm=2, num=3}, serialNo=1234, " +
				"from=Id{shard=1, realm=2, num=3}, to=Id{shard=2, realm=3, num=4}}";

		// expect:
		assertNotEquals(hbarChange, tokenChange);
		assertNotEquals(hbarChange.hashCode(), tokenChange.hashCode());
		// and:
		assertEquals(hbarRepr, hbarChange.toString());
		assertEquals(tokenRepr, tokenChange.toString());
		assertEquals(nftRepr, nftChange.toString());
		// and:
		assertSame(a, hbarChange.accountId());
		assertEquals(delta, hbarChange.units());
		assertEquals(t.asGrpcToken(), tokenChange.tokenId());
	}

	@Test
	void recognizesFungibleTypes() {
		// given:
		final var hbarChange = IdUtils.hbarChange(a, delta);
		final var tokenChange = IdUtils.tokenChange(t, a, delta);

		assertTrue(hbarChange.isForHbar());
		assertFalse(tokenChange.isForHbar());
		// and:
		assertFalse(hbarChange.isForNft());
		assertFalse(tokenChange.isForNft());
	}

	@Test
	void noTokenForHbarAdjust() {
		final var hbarChange = IdUtils.hbarChange(a, delta);
		assertSame(NO_TOKEN_FOR_HBAR_ADJUST, hbarChange.tokenId());
	}

	@Test
	void ownershipChangeFactoryWorks() {
		// setup:
		final var xfer = NftTransfer.newBuilder()
				.setSenderAccountID(a)
				.setReceiverAccountID(b)
				.setSerialNumber(serialNo)
				.build();

		// given:
		final var nftChange = changingNftOwnership(t, t.asGrpcToken(), xfer);

		// expect:
		assertEquals(a, nftChange.accountId());
		assertEquals(b, nftChange.counterPartyAccountId());
		assertEquals(t.asGrpcToken(), nftChange.tokenId());
		assertEquals(serialNo, nftChange.serialNo());
		// and:
		assertTrue(nftChange.isForNft());
		assertEquals(new NftId(t.getShard(), t.getRealm(), t.getNum(), serialNo), nftChange.nftId());
	}
}
