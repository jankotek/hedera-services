package com.hedera.services.legacy.unit;

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


import com.hedera.services.fees.HbarCentExchange;
import com.hedera.services.files.HederaFs;
import com.hedera.services.legacy.handler.FreezeHandler;
import com.hedera.services.legacy.proto.utils.CommonUtils;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.swirlds.common.NodeId;
import com.swirlds.common.Platform;
import com.swirlds.common.SwirldDualState;
import com.swirlds.common.internal.SettingsCommon;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.INVALID_FREEZE_TRANSACTION_BODY;
import static java.lang.Thread.sleep;
import static java.util.Calendar.HOUR_OF_DAY;
import static java.util.Calendar.MINUTE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(LogCaptureExtension.class)
class FreezeHandlerTest {
	private Instant consensusTime = Instant.now();
	private HederaFs hfs;
	private Platform platform;
	private ExchangeRateSet rates;
	private HbarCentExchange exchange;
	private SwirldDualState dualState;

	@Inject
	private LogCaptor logCaptor;
	@LoggingSubject
	private FreezeHandler subject;

	@BeforeEach
	void setUp() {
		SettingsCommon.transactionMaxBytes = 1_234_567;

		hfs = mock(HederaFs.class);
		rates = mock(ExchangeRateSet.class);
		exchange = mock(HbarCentExchange.class);
		given(exchange.activeRates()).willReturn(rates);
		platform = Mockito.mock(Platform.class);
		given(platform.getSelfId()).willReturn(new NodeId(false, 1));
		dualState = mock(SwirldDualState.class);

		subject = new FreezeHandler(hfs, platform, exchange, () -> dualState);
	}

	@Test
	void setsInstantInSameDayWhenNatural() throws Exception {
		// setup:
		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, null);
		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		// and:
		final var nominalStartHour = txBody.getFreeze().getStartHour();
		final var nominalStartMin = txBody.getFreeze().getStartMin();
		final var expectedStart = naturalNextInstant(nominalStartHour, nominalStartMin, consensusTime);

		// when:
		TransactionRecord record = subject.freeze(txBody, consensusTime);

		// then:
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);
		verify(dualState).setFreezeTime(expectedStart);
	}

	@Test
	void setsInstantInNextDayWhenNatural() throws Exception {
		// setup:
		Transaction transaction = FreezeTestHelper.createFreezeTransaction(
				true,
				true,
				null,
				null,
				new int[] { 10, 0 },
				new int[] { 10, 1 });
		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		// and:
		final var nominalStartHour = txBody.getFreeze().getStartHour();
		final var nominalStartMin = txBody.getFreeze().getStartMin();
		final var expectedStart = naturalNextInstant(nominalStartHour, nominalStartMin, consensusTime);

		// when:
		TransactionRecord record = subject.freeze(txBody, consensusTime);

		// then:
		assertEquals(ResponseCodeEnum.SUCCESS, record.getReceipt().getStatus());
		verify(dualState).setFreezeTime(expectedStart);
	}

	@Test
	void computesMinsSinceConsensusMidnight() {
		// given:
		final var consensusNow = Instant.parse("2021-05-28T14:38:34.546097Z");
		// and:
		final int minutesSinceMidnight = 14 * 60 + 38;

		// expect:
		assertEquals(minutesSinceMidnight, subject.minutesSinceMidnight(consensusNow));
	}

	private Instant naturalNextInstant(int nominalHour, int nominalMin, Instant now) {
		final var calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
		calendar.setTimeInMillis(consensusTime.getEpochSecond() * 1_000);
		final int curHour = calendar.get(HOUR_OF_DAY);
		final int curMin = calendar.get(MINUTE);
		final int curMinsSinceMidnight = curHour * 60 + curMin;
		final int nominalMinsSinceMidnight = nominalHour * 60 + nominalMin;
		int diffMins = nominalMinsSinceMidnight - curMinsSinceMidnight;
		if (diffMins < 0) {
			diffMins += 1440;
		}
		final var ans = now.plusSeconds(diffMins * 60);
		return ans;
	}

	@Test
	void freeze_InvalidFreezeTxBody_Test() throws Exception {
		willThrow(IllegalArgumentException.class).given(dualState).setFreezeTime(any());
		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, false, null);
		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(INVALID_FREEZE_TRANSACTION_BODY, record.getReceipt().getStatus());
	}

	@Test
	void freeze_updateFeature() throws Exception {
		String zipFile = "src/test/resources/testfiles/updateFeature/update.zip";
		byte[] data = Files.readAllBytes(Paths.get(zipFile));
		byte[] hash = CommonUtils.noThrowSha384HashOf(data);
		FileID fileID = FileID.newBuilder().setShardNum(0L).setRealmNum(0L).setFileNum(150L).build();

		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, fileID, hash);

		given(hfs.exists(fileID)).willReturn(true);
		given(hfs.cat(fileID)).willReturn(data);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		subject.handleUpdateFeature();

		// Wait script to finish
		sleep(2000);

		//check whether new file has been added as expected
		File file3 = new File("new3.txt");
		assertTrue(file3.exists());
		file3.delete();
	}

	@Test
	void freezeOnlyNoUpdateFeature() throws Exception {
		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, null);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		// when:
		subject.handleUpdateFeature();

		// then:
		assertThat(
				logCaptor.infoLogs(),
				contains(
						Matchers.startsWith("Dual state freeze time set to"),
						stringContainsInOrder(List.of("Update file id is not defined"))));
	}

	@Test
	void freezeUpdateWarnsWhenFileNotDeleted() throws Exception {
		// setup:
		String zipFile = "src/test/resources/testfiles/updateFeature/update.zip";
		byte[] data = Files.readAllBytes(Paths.get(zipFile));
		byte[] hash = CommonUtils.noThrowSha384HashOf(data);
		FileID fileID = FileID.newBuilder().setShardNum(0L).setRealmNum(0L).setFileNum(150L).build();

		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, fileID, hash);

		given(hfs.exists(fileID)).willReturn(true);
		given(hfs.cat(fileID)).willReturn(data);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		try (MockedStatic<Files> utilities = Mockito.mockStatic(Files.class)) {
			// mockito set up
			utilities.when(() -> Files.delete(any())).thenThrow(new IOException());
			// when:
			subject.handleUpdateFeature();
		}

		// then:
		assertTrue(
				logCaptor.warnLogs().get(1).contains("File could not be deleted"));
	}

	@Test
	void freeze_updateAbort_EmptyFile() throws Exception {
		byte[] data = new byte[0];
		byte[] hash = CommonUtils.noThrowSha384HashOf(data);
		FileID fileID = FileID.newBuilder().setShardNum(0L).setRealmNum(0L).setFileNum(150L).build();

		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, fileID, hash);

		given(hfs.exists(fileID)).willReturn(true);
		given(hfs.cat(fileID)).willReturn(data);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		// when:
		subject.handleUpdateFeature();

		// then:
		assertThat(
				logCaptor.errorLogs(),
				contains(
						Matchers.equalTo("NETWORK_UPDATE Node 1 Update file is empty"),
						Matchers.equalTo("NETWORK_UPDATE Node 1 ABORT UPDATE PROCRESS")));
	}

	@Test
	void freeze_updateFileHash_MisMatch() throws Exception {
		FileID fileID = FileID.newBuilder().setShardNum(0L).setRealmNum(0L).setFileNum(150L).build();

		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, fileID, new byte[48]);

		given(hfs.exists(fileID)).willReturn(true);
		given(hfs.cat(fileID)).willReturn(new byte[100]);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		// when:
		subject.handleUpdateFeature();

		// then:
		assertThat(
				logCaptor.errorLogs(),
				contains(
						stringContainsInOrder(List.of("File hash mismatch")),
						Matchers.startsWith("NETWORK_UPDATE Node 1 Hash from transaction body"),
						Matchers.startsWith("NETWORK_UPDATE Node 1 Hash from file system"),
						Matchers.equalTo("NETWORK_UPDATE Node 1 ABORT UPDATE PROCRESS")));
	}


	@Test
	void freeze_updateFileID_NonExist() throws Exception {
		FileID fileID = FileID.newBuilder().setShardNum(0L).setRealmNum(0L).setFileNum(150L).build();
		Transaction transaction = FreezeTestHelper.createFreezeTransaction(true, true, fileID, new byte[48]);
		given(hfs.exists(fileID)).willReturn(false);
		given(hfs.cat(fileID)).willReturn(new byte[100]);

		TransactionBody txBody = CommonUtils.extractTransactionBody(transaction);
		TransactionRecord record = subject.freeze(txBody, consensusTime);
		assertEquals(record.getReceipt().getStatus(), ResponseCodeEnum.SUCCESS);

		// when:
		subject.handleUpdateFeature();

		// then:
		assertThat(
				logCaptor.errorLogs(),
				contains(stringContainsInOrder(List.of("not found in file system"))));
	}
}
