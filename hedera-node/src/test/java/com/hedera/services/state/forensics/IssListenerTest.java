package com.hedera.services.state.forensics;
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

import com.hedera.services.ServicesState;
import com.hedera.services.context.domain.trackers.IssEventInfo;
import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.swirlds.common.AddressBook;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.events.Event;
import com.swirlds.common.CommonUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.inject.Inject;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith({MockitoExtension.class, LogCaptureExtension.class})
class IssListenerTest {
	long selfId = 1, otherId = 2, round = 1_234_567, numConsEvents = 111;
	NodeId self = new NodeId(false, selfId);
	NodeId other = new NodeId(false, otherId);
	// and:
	byte[] hash = "xyz".getBytes();
	String hashHex = CommonUtils.hex(hash);
	byte[] sig = "zyx".getBytes();
	String sigHex = CommonUtils.hex(sig);

	Instant consensusTime = Instant.now();

	@Mock
	FcmDump fcmDump;
	@Mock
	ServicesState state;
	@Mock
	Platform platform;
	@Mock
	AddressBook book;
	@Mock
	IssEventInfo info;
	@Mock
	private NodeLocalProperties nodeLocalProperties;

	@Inject
	private LogCaptor logCaptor;

	@LoggingSubject
	private IssListener subject;

	@BeforeEach
	void setup() {
		subject = new IssListener(fcmDump, info, nodeLocalProperties);
	}

	@Test
	void logsFallbackInfo() {
		// given:
		willThrow(IllegalStateException.class).given(info).alert(any());

		// when:
		subject.notifyError(
				platform, book, state, new Event[0], self, other, round, consensusTime, numConsEvents, sig, hash);

		// then:
		var desired = String.format(
				IssListener.ISS_FALLBACK_ERROR_MSG_PATTERN,
				round,
				String.valueOf(self),
				String.valueOf(other));
		assertThat(logCaptor.warnLogs(), contains(Matchers.startsWith(desired)));
	}

	@Test
	void logsAndDumpsIfConfiguredInfo() {
		given(info.shouldDumpThisRound()).willReturn(true);
		given(nodeLocalProperties.shouldDumpFcmsOnIss()).willReturn(true);

		// when:
		subject.notifyError(
				platform, book, state, new Event[0], self, other, round, consensusTime, numConsEvents, sig, hash);

		// then:
		var desired = String.format(IssListener.ISS_ERROR_MSG_PATTERN, round, selfId, otherId, sigHex, hashHex);
		verify(info).alert(consensusTime);
		verify(info).decrementRoundsToDump();
		assertThat(logCaptor.errorLogs(), contains(desired));
		verify(fcmDump).dumpFrom(state, self, round);
		verify(state).logSummary();
	}

	@Test
	void onlyLogsIfConfiguredInfo() {
		given(info.shouldDumpThisRound()).willReturn(true);

		// when:
		subject.notifyError(
				platform, book, state, new Event[0], self, other, round, consensusTime, numConsEvents, sig, hash);

		// then:
		var desired = String.format(IssListener.ISS_ERROR_MSG_PATTERN, round, selfId, otherId, sigHex, hashHex);
		verify(info).alert(consensusTime);
		verify(info).decrementRoundsToDump();
		assertThat(logCaptor.errorLogs(), contains(desired));
		verify(fcmDump, never()).dumpFrom(state, self, round);
		verify(state).logSummary();
	}
}
