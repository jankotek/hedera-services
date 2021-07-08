package com.hedera.services.store.tokens;

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

import com.hedera.services.ledger.BalanceChange;
import com.hedera.services.store.models.Id;
import com.hedera.services.store.models.NftId;
import com.hederahashgraph.api.proto.java.AccountAmount;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static com.hedera.services.ledger.BalanceChange.changingNftOwnership;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.nftXfer;
import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.OK;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doCallRealMethod;

class LegacyTokenStoreTest {
	private final Id t = new Id(1, 2, 3);
	private final TokenID tId = t.asGrpcToken();
	private final long delta = -1_234L;
	private final long serialNo = 1234L;
	private final AccountID a = asAccount("1.2.3");
	private final AccountID b = asAccount("2.3.4");
	private final NftId tNft = new NftId(1, 2, 3, serialNo);

	@Test
	void adaptsBehaviorToFungibleType() {
		// setup:
		final var aa = AccountAmount.newBuilder().setAccountID(a).setAmount(delta).build();
		final var fungibleChange = BalanceChange.changingFtUnits(t, t.asGrpcToken(), aa);
		// and:
		final var hybridSubject = Mockito.mock(TokenStore.class);

		// and:
		doCallRealMethod().when(hybridSubject).tryTokenChange(fungibleChange);
		given(hybridSubject.resolve(tId)).willReturn(tId);
		given(hybridSubject.adjustBalance(a, tId, delta)).willReturn(OK);

		// when:
		final var result = hybridSubject.tryTokenChange(fungibleChange);

		// then:
		Assertions.assertEquals(OK, result);
	}

	@Test
	void adaptsBehaviorToNonfungibleType() {
		// setup:
		final var nftChange = changingNftOwnership(t, t.asGrpcToken(), nftXfer(a, b, serialNo));
		// and:
		final var hybridSubject = Mockito.mock(TokenStore.class);

		// and:
		doCallRealMethod().when(hybridSubject).tryTokenChange(nftChange);
		given(hybridSubject.resolve(tId)).willReturn(tId);
		given(hybridSubject.changeOwner(tNft, a, b)).willReturn(OK);

		// when:
		final var result = hybridSubject.tryTokenChange(nftChange);

		// then:
		Assertions.assertEquals(OK, result);
	}
}
