package com.hedera.services.pricing;

/*-
 * ‌
 * Hedera Services API Fees
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

import org.junit.jupiter.api.Test;

import static com.hederahashgraph.api.proto.java.HederaFunctionality.CryptoTransfer;
import static com.hederahashgraph.api.proto.java.SubType.DEFAULT;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_FUNGIBLE_COMMON;
import static com.hederahashgraph.api.proto.java.SubType.TOKEN_NON_FUNGIBLE_UNIQUE;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BaseOperationUsageTest {
	final BaseOperationUsage subject = new BaseOperationUsage();

	@Test
	void cryptoTransferTbd() {
		assertThrows(AssertionError.class,
				() -> subject.baseUsageFor(CryptoTransfer, DEFAULT));
		assertThrows(AssertionError.class,
				() -> subject.baseUsageFor(CryptoTransfer, TOKEN_FUNGIBLE_COMMON));
		assertThrows(AssertionError.class,
				() -> subject.baseUsageFor(CryptoTransfer, TOKEN_NON_FUNGIBLE_UNIQUE));
	}
}
