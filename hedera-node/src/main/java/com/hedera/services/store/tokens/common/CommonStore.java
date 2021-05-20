package com.hedera.services.store.tokens.common;

/*
 * ‌
 * Hedera Services Node
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.hedera.services.store.tokens.TokenStore;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.TokenUpdateTransactionBody;

import java.util.List;

public interface CommonStore extends TokenStore {
	ResponseCodeEnum burn(TokenID tId, long amount);
	ResponseCodeEnum mint(TokenID tId, long amount);
	ResponseCodeEnum wipe(AccountID aId, TokenID tId, long wipingAmount, boolean skipKeyCheck);
	ResponseCodeEnum freeze(AccountID aId, TokenID tId);
	ResponseCodeEnum update(TokenUpdateTransactionBody changes, long now);
	ResponseCodeEnum unfreeze(AccountID aId, TokenID tId);
	ResponseCodeEnum grantKyc(AccountID aId, TokenID tId);
	ResponseCodeEnum revokeKyc(AccountID aId, TokenID tId);
	ResponseCodeEnum associate(AccountID aId, List<TokenID> tokens);
	ResponseCodeEnum dissociate(AccountID aId, List<TokenID> tokens);
	ResponseCodeEnum adjustBalance(AccountID aId, TokenID tId, long adjustment);
}
