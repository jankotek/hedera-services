package com.hedera.services.exceptions;

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

import com.hederahashgraph.api.proto.java.ResponseCodeEnum;

import java.util.function.Supplier;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.FAIL_INVALID;

/**
 * A minimalist collection of helpers to improve readability of code
 * that throws an {@code InvalidTransactionException}.
 */
public class ValidationUtils {
	public static void validateTrue(boolean flag, ResponseCodeEnum code) {
		if (!flag) {
			throw new InvalidTransactionException(code);
		}
	}

	public static void validateTrue(boolean flag, ResponseCodeEnum code, Supplier<String> failureMsg) {
		if (!flag) {
			throw new InvalidTransactionException(failureMsg.get(), code);
		}
	}

	public static void validateFalse(boolean flag, ResponseCodeEnum code) {
		if (flag) {
			throw new InvalidTransactionException(code);
		}
	}

	public static void validateFalse(boolean flag, ResponseCodeEnum code, Supplier<String> failureMsg) {
		if (flag) {
			throw new InvalidTransactionException(failureMsg.get(), code);
		}
	}
}
