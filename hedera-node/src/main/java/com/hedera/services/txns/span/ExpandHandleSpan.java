package com.hedera.services.txns.span;

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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.protobuf.InvalidProtocolBufferException;
import com.hedera.services.utils.PlatformTxnAccessor;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.SwirldTransaction;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * Encapsulates a "span" that tracks our contact with a given {@link SwirldTransaction}
 * between the {@link com.hedera.services.ServicesState#expandSignatures(SwirldTransaction)}
 * and {@link com.hedera.services.ServicesState#handleTransaction(long, boolean, Instant, Instant, SwirldTransaction, SwirldDualState)}
 * Platform callbacks.
 *
 * At first this span only tracks the {@link PlatformTxnAccessor} parsed from the
 * transaction contents in an expiring cache. Since the parsing is a pure function
 * of the contents, this is a trivial exercise.
 *
 * However, a major (perhaps <i>the</i> major) performance optimization available
 * to Services will be to,
 * <ol>
 *     <li>Expand signatures from the latest signed state.</li>
 *     <li>Track the expanded signatures, along with the entities involved, in the transaction's span.</li>
 *     <li>From {@code handleTransaction}, alert the {@code ExpandHandleSpan} when an entity's keys or
 *     usability changes; this will invalidate the signatures for any span involving the entity.</li>
 *     <li>When a transaction reaches {@code handleTransaction} with valid expanded signatures, simply
 *     reuse them instead of recomputing them.</li>
 * </ol>
 */
public class ExpandHandleSpan {
	private final SpanMapManager spanMapManager;
	private final Cache<SwirldTransaction, PlatformTxnAccessor> accessorCache;

	public ExpandHandleSpan(
			long duration,
			TimeUnit timeUnit,
			SpanMapManager spanMapManager
	) {
		this.spanMapManager = spanMapManager;
		this.accessorCache = CacheBuilder.newBuilder()
				.expireAfterWrite(duration, timeUnit)
				.build();
	}

	public PlatformTxnAccessor track(SwirldTransaction transaction) throws InvalidProtocolBufferException {
		final var accessor = spanAccessorFor(transaction);
		accessorCache.put(transaction, accessor);
		return accessor;
	}

	public PlatformTxnAccessor accessorFor(SwirldTransaction transaction) throws InvalidProtocolBufferException {
		final var cachedAccessor = accessorCache.getIfPresent(transaction);
		if (cachedAccessor != null) {
			spanMapManager.rationalizeSpan(cachedAccessor);
			return cachedAccessor;
		} else {
			return spanAccessorFor(transaction);
		}
	}

	private PlatformTxnAccessor spanAccessorFor(SwirldTransaction transaction) throws InvalidProtocolBufferException {
		final var accessor = new PlatformTxnAccessor(transaction);
		spanMapManager.expandSpan(accessor);
		return accessor;
	}
}
