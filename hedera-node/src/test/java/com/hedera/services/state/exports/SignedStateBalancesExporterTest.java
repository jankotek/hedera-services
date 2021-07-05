package com.hedera.services.state.exports;

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
import com.hedera.services.config.MockGlobalDynamicProps;
import com.hedera.services.context.properties.GlobalDynamicProperties;
import com.hedera.services.context.properties.PropertySource;
import com.hedera.services.exceptions.NegativeAccountBalanceException;
import com.hedera.services.state.merkle.MerkleAccount;
import com.hedera.services.state.merkle.MerkleEntityAssociation;
import com.hedera.services.state.merkle.MerkleEntityId;
import com.hedera.services.state.merkle.MerkleToken;
import com.hedera.services.state.merkle.MerkleTokenRelStatus;
import com.hedera.services.stream.proto.AllAccountBalances;
import com.hedera.services.stream.proto.SingleAccountBalances;
import com.hedera.services.stream.proto.TokenUnitBalance;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import com.hedera.test.factories.accounts.MerkleAccountFactory;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.TokenID;
import com.swirlds.common.Address;
import com.swirlds.common.AddressBook;
import com.swirlds.common.NodeId;
import com.swirlds.common.constructable.ClassConstructorPair;
import com.swirlds.common.constructable.ConstructableRegistry;
import com.swirlds.common.constructable.ConstructableRegistryException;
import com.swirlds.fcmap.FCMap;
import com.swirlds.fcmap.internal.FCMLeaf;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import javax.inject.Inject;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static com.hedera.services.state.exports.SignedStateBalancesExporter.SINGLE_ACCOUNT_BALANCES_COMPARATOR;
import static com.hedera.services.state.exports.SignedStateBalancesExporter.b64Encode;
import static com.hedera.services.state.merkle.MerkleEntityAssociation.fromAccountTokenRel;
import static com.hedera.services.state.merkle.MerkleEntityId.fromAccountId;
import static com.hedera.services.state.merkle.MerkleEntityId.fromTokenId;
import static com.hedera.test.utils.IdUtils.asAccount;
import static com.hedera.test.utils.IdUtils.asToken;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(LogCaptureExtension.class)
class SignedStateBalancesExporterTest {
	private final NodeId nodeId = new NodeId(false, 1);
	private FCMap<MerkleEntityId, MerkleToken> tokens = new FCMap<>();
	private FCMap<MerkleEntityId, MerkleAccount> accounts = new FCMap<>();
	private FCMap<MerkleEntityAssociation, MerkleTokenRelStatus> tokenRels = new FCMap<>();

	private MerkleToken token;
	private MerkleToken deletedToken;

	private long ledgerFloat = 1_000;

	private long thisNodeBalance = 400;
	private AccountID thisNode = asAccount("0.0.3");
	private long anotherNodeBalance = 100;
	private AccountID anotherNode = asAccount("0.0.4");
	private long firstNonNodeAccountBalance = 250;
	private AccountID firstNonNode = asAccount("0.0.1001");
	private long secondNonNodeAccountBalance = 250;
	private AccountID secondNonNode = asAccount("0.0.1002");
	private AccountID deleted = asAccount("0.0.1003");

	private TokenID theToken = asToken("0.0.1004");
	private long secondNonNodeTokenBalance = 100;
	private TokenID theDeletedToken = asToken("0.0.1005");
	private long secondNonNodeDeletedTokenBalance = 100;

	private byte[] sig = "not-really-a-sig".getBytes();
	private byte[] fileHash = "not-really-a-hash".getBytes();

	private MerkleAccount thisNodeAccount, anotherNodeAccount, firstNonNodeAccount, secondNonNodeAccount, deletedAccount;

	private GlobalDynamicProperties dynamicProperties = new MockGlobalDynamicProps();

	private Instant now = Instant.now();
	private Instant shortlyAfter = now.plusSeconds(dynamicProperties.balancesExportPeriodSecs() / 2);
	private Instant anEternityLater = now.plusSeconds(dynamicProperties.balancesExportPeriodSecs() * 2);

	private ServicesState state;
	private PropertySource properties;
	private UnaryOperator<byte[]> signer;
	private SigFileWriter sigFileWriter;
	private FileHashReader hashReader;
	private DirectoryAssurance assurance;

	@Inject
	private LogCaptor logCaptor;

	@LoggingSubject
	private SignedStateBalancesExporter subject;

	@BeforeEach
	void setUp() throws ConstructableRegistryException {
		// setup:
		ConstructableRegistry.registerConstructable(
				new ClassConstructorPair(FCMLeaf.class, FCMLeaf::new));
		ConstructableRegistry.registerConstructable(
				new ClassConstructorPair(MerkleAccount.class, MerkleAccount::new));

		thisNodeAccount = MerkleAccountFactory.newAccount().balance(thisNodeBalance).get();
		anotherNodeAccount = MerkleAccountFactory.newAccount().balance(anotherNodeBalance).get();
		firstNonNodeAccount = MerkleAccountFactory.newAccount().balance(firstNonNodeAccountBalance).get();
		secondNonNodeAccount = MerkleAccountFactory.newAccount()
				.balance(secondNonNodeAccountBalance)
				.tokens(theToken, theDeletedToken)
				.get();
		deletedAccount = MerkleAccountFactory.newAccount().deleted(true).get();

		accounts.put(fromAccountId(thisNode), thisNodeAccount);
		accounts.put(fromAccountId(anotherNode), anotherNodeAccount);
		accounts.put(fromAccountId(firstNonNode), firstNonNodeAccount);
		accounts.put(fromAccountId(secondNonNode), secondNonNodeAccount);
		accounts.put(fromAccountId(deleted), deletedAccount);

		token = mock(MerkleToken.class);
		given(token.isDeleted()).willReturn(false);
		deletedToken = mock(MerkleToken.class);
		given(deletedToken.isDeleted()).willReturn(true);
		tokens.put(fromTokenId(theToken), token);
		tokens.put(fromTokenId(theDeletedToken), deletedToken);

		tokenRels.put(
				fromAccountTokenRel(secondNonNode, theToken),
				new MerkleTokenRelStatus(secondNonNodeTokenBalance, false, true));
		tokenRels.put(
				fromAccountTokenRel(secondNonNode, theDeletedToken),
				new MerkleTokenRelStatus(secondNonNodeDeletedTokenBalance, false, true));

		assurance = mock(DirectoryAssurance.class);

		properties = mock(PropertySource.class);
		given(properties.getLongProperty("ledger.totalTinyBarFloat")).willReturn(ledgerFloat);

		var firstNodeAddress = mock(Address.class);
		given(firstNodeAddress.getMemo()).willReturn("0.0.3");
		var secondNodeAddress = mock(Address.class);
		given(secondNodeAddress.getMemo()).willReturn("0.0.4");

		var book = mock(AddressBook.class);
		given(book.getSize()).willReturn(2);
		given(book.getAddress(0)).willReturn(firstNodeAddress);
		given(book.getAddress(1)).willReturn(secondNodeAddress);

		state = mock(ServicesState.class);
		given(state.getAccountFromNodeId(nodeId)).willReturn(thisNode);
		given(state.tokens()).willReturn(tokens);
		given(state.accounts()).willReturn(accounts);
		given(state.tokenAssociations()).willReturn(tokenRels);
		given(state.addressBook()).willReturn(book);

		signer = mock(UnaryOperator.class);
		given(signer.apply(fileHash)).willReturn(sig);
		subject = new SignedStateBalancesExporter(properties, signer, dynamicProperties);

		sigFileWriter = mock(SigFileWriter.class);
		hashReader = mock(FileHashReader.class);
		subject.sigFileWriter = sigFileWriter;
		subject.hashReader = hashReader;
	}

	@Test
	void logsOnIoException() throws IOException {
		// setup:
		var otherDynamicProperties = new MockGlobalDynamicProps() {
			@Override
			public String pathToBalancesExportDir() {
				return "not/a/real/location";
			}
		};
		subject = new SignedStateBalancesExporter(properties, signer, otherDynamicProperties);

		// given:
		subject.directories = assurance;

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		assertThat(logCaptor.errorLogs(), contains(Matchers.startsWith("Could not export to")));
	}

	@Test
	void logsOnSigningFailure() {
		// setup:
		var loc = expectedExportLoc();

		given(hashReader.readHash(loc)).willThrow(IllegalStateException.class);

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		assertThat(logCaptor.errorLogs(), contains(Matchers.startsWith("Could not sign balance file")));

		// cleanup:
		new File(loc).delete();
	}

	@Test
	void matchesV2OutputForCsv() throws IOException {
		// setup:
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		// and:
		var loc = expectedExportLoc();
		var desiredDebugMsg = "Created balance signature file " + "'" + loc + "_sig'.";
		// and:
		accounts.clear();
		accounts.put(
				new MerkleEntityId(0, 0, 1),
				MerkleAccountFactory.newAccount().get());
		accounts.put(
				new MerkleEntityId(0, 0, 2),
				MerkleAccountFactory.newAccount()
						.balance(4999999999999999920L)
						.tokens(asToken("0.0.1001"), asToken("0.0.1002"))
						.get());
		accounts.put(
				new MerkleEntityId(0, 0, 3),
				MerkleAccountFactory.newAccount()
						.balance(80L)
						.tokens(asToken("0.0.1002"))
						.get());
		// and:
		tokenRels.clear();
		tokenRels.put(
				new MerkleEntityAssociation(0, 0, 2, 0, 0, 1001),
				new MerkleTokenRelStatus(666L, false, false));
		tokenRels.put(
				new MerkleEntityAssociation(0, 0, 2, 0, 0, 1002),
				new MerkleTokenRelStatus(444L, false, false));
		tokenRels.put(
				new MerkleEntityAssociation(0, 0, 3, 0, 0, 1002),
				new MerkleTokenRelStatus(333L, false, false));
		// and:
		tokens.clear();
		tokens.put(new MerkleEntityId(0, 0, 1001), token);
		tokens.put(new MerkleEntityId(0, 0, 1002), token);

		given(hashReader.readHash(loc)).willReturn(fileHash);
		given(sigFileWriter.writeSigFile(captor.capture(), any(), any())).willReturn(loc + "_sig");
		given(properties.getLongProperty("ledger.totalTinyBarFloat"))
				.willReturn(5000000000000000000L);
		given(signer.apply(fileHash)).willReturn(sig);
		// and:
		subject = new SignedStateBalancesExporter(properties, signer, dynamicProperties);
		subject.sigFileWriter = sigFileWriter;
		subject.hashReader = hashReader;

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		var lines = Files.readAllLines(Paths.get(loc));
		assertEquals(6, lines.size());
		assertEquals("# " + SignedStateBalancesExporter.CURRENT_VERSION, lines.get(0));
		assertEquals("# TimeStamp:" + now, lines.get(1));
		assertEquals("shardNum,realmNum,accountNum,balance,tokenBalances", lines.get(2));
		assertEquals("0,0,1,0,", lines.get(3));
		assertEquals("0,0,2,4999999999999999920,CggKAxjpBxCaBQoICgMY6gcQvAM=", lines.get(4));
		assertEquals("0,0,3,80,CggKAxjqBxDNAg==", lines.get(5));
		// and:
		verify(sigFileWriter).writeSigFile(loc, sig, fileHash);
		// and:
		assertThat(logCaptor.debugLogs(), contains(desiredDebugMsg));

		// cleanup:
		new File(loc).delete();
	}

	@Test
	void usesNewFormatWhenExportingTokenBalances() throws IOException {
		// setup:
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		// and:
		var loc = expectedExportLoc();
		var desiredDebugMsg = "Created balance signature file " + "'" + loc + "_sig'.";

		given(hashReader.readHash(loc)).willReturn(fileHash);
		given(sigFileWriter.writeSigFile(captor.capture(), any(), any())).willReturn(loc + "_sig");

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		var lines = Files.readAllLines(Paths.get(loc));
		var expected = theExpectedBalances();
		assertEquals(expected.size() + 3, lines.size());
		assertEquals("# " + SignedStateBalancesExporter.CURRENT_VERSION, lines.get(0));
		assertEquals("# TimeStamp:" + now, lines.get(1));
		assertEquals("shardNum,realmNum,accountNum,balance,tokenBalances", lines.get(2));
		for (int i = 0; i < expected.size(); i++) {
			var entry = expected.get(i);
			assertEquals(String.format(
					"%d,%d,%d,%d,%s",
					entry.getAccountID().getShardNum(),
					entry.getAccountID().getRealmNum(),
					entry.getAccountID().getAccountNum(),
					entry.getHbarBalance(),
					entry.getTokenUnitBalancesList().size() > 0 ? b64Encode(entry) : ""), lines.get(i + 3));
		}
		// and:
		verify(sigFileWriter).writeSigFile(loc, sig, fileHash);
		// and:
		assertThat(logCaptor.debugLogs(), contains(desiredDebugMsg));

		// cleanup:
		new File(loc).delete();
	}

	@Test
	void usesLegacyFormatWhenNotExportingTokenBalances() throws IOException {
		// setup:
		var otherDynamicProperties = new MockGlobalDynamicProps() {
			@Override
			public boolean shouldExportTokenBalances() {
				return false;
			}
		};
		subject = new SignedStateBalancesExporter(properties, signer, otherDynamicProperties);
		subject.sigFileWriter = sigFileWriter;
		subject.hashReader = hashReader;

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		var lines = Files.readAllLines(Paths.get(expectedExportLoc()));
		var expected = theExpectedBalances();
		assertEquals(expected.size() + 2, lines.size());
		assertEquals("TimeStamp:" + now, lines.get(0));
		assertEquals("shardNum,realmNum,accountNum,balance", lines.get(1));
		for (int i = 0; i < expected.size(); i++) {
			var entry = expected.get(i);
			assertEquals(String.format(
					"%d,%d,%d,%d",
					entry.getAccountID().getShardNum(),
					entry.getAccountID().getRealmNum(),
					entry.getAccountID().getAccountNum(),
					entry.getHbarBalance()), lines.get(i + 2));
		}

		// cleanup:
		new File(expectedExportLoc()).delete();
	}

	@Test
	void testExportingTokenBalancesProto() throws IOException {
		// setup:
		ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
		// and:
		var loc = expectedExportLoc(true);
		var desiredDebugMsg = "Created balance signature file " + "'" + loc + "_sig'.";

		given(hashReader.readHash(loc)).willReturn(fileHash);
		given(sigFileWriter.writeSigFile(captor.capture(), any(), any())).willReturn(loc + "_sig");

		// when:
		subject.exportCsv = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// and:
		java.util.Optional<AllAccountBalances> fileContent = importBalanceProtoFile(loc);

		AllAccountBalances allAccountBalances = fileContent.get();

		// then:
		List<SingleAccountBalances> accounts = allAccountBalances.getAllAccountsList();

		assertEquals(accounts.size(), 4);

		for (SingleAccountBalances account : accounts) {
			if (account.getAccountID().getAccountNum() == 1001) {
				assertEquals(account.getHbarBalance(), 250);
			} else if (account.getAccountID().getAccountNum() == 1002) {
				assertEquals(account.getHbarBalance(), 250);
				assertEquals(account.getTokenUnitBalances(0).getTokenId().getTokenNum(), 1004);
				assertEquals(account.getTokenUnitBalances(0).getBalance(), 100);
			}
		}

		// and:
		verify(sigFileWriter).writeSigFile(loc, sig, fileHash);
		// and:
		assertThat(logCaptor.debugLogs(), contains(desiredDebugMsg));

		// cleanup:
		new File(loc).delete();
	}

	@Test
	void protoWriteIoException() throws IOException {
		// setup:
		var otherDynamicProperties = new MockGlobalDynamicProps() {
			@Override
			public String pathToBalancesExportDir() {
				return "not/a/real/location";
			}
		};
		subject = new SignedStateBalancesExporter(properties, signer, otherDynamicProperties);

		// given:
		subject.directories = assurance;

		// when:
		subject.exportCsv = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		assertThat(logCaptor.errorLogs(), contains(Matchers.startsWith("Could not export to")));
	}


	@Test
	void getEmptyAllAccountBalancesFromCorruptedProtoFileImport() throws Exception {
		// setup:
		var loc = expectedExportLoc(true);

		given(hashReader.readHash(loc)).willReturn(fileHash);

		// when: Pretend the .csv file is a corrupted .pb file
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// and:
		java.util.Optional<AllAccountBalances> accounts = importBalanceProtoFile(loc);

		// then:
		assertEquals(Optional.empty(), accounts);
	}

	@Test
	void assuresExpectedProtoFileDir() throws IOException {
		// given:
		subject.directories = assurance;

		// when:
		subject.exportCsv = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		verify(assurance).ensureExistenceOf(expectedExportDir());
	}


	private String expectedExportLoc() {
		return expectedExportLoc(false);
	}

	private String expectedExportLoc(boolean isProto) {
		return dynamicProperties.pathToBalancesExportDir()
				+ File.separator
				+ "balance0.0.3"
				+ File.separator
				+ expectedBalancesName(isProto);
	}


	@Test
	void errorProtoLogsOnIoException() throws IOException {
		// given:
		subject.directories = assurance;
		var desiredMsg = "Cannot ensure existence of export dir " + "'" + expectedExportDir() + "'!";
		// and:
		willThrow(IOException.class).given(assurance).ensureExistenceOf(any());

		// when:
		subject.exportCsv = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		assertThat(logCaptor.errorLogs(), contains(desiredMsg));
	}

	private String expectedBalancesName(Boolean isProto) {
		return isProto ? now.toString().replace(":", "_") + "_Balances.pb"
				: now.toString().replace(":", "_") + "_Balances.csv";
	}

	@Test
	void testSingleAccountBalancingSort() {
		// given:
		List<SingleAccountBalances> expectedBalances = theExpectedBalances();
		List<SingleAccountBalances> sorted = new ArrayList<>();
		sorted.addAll(expectedBalances);

		SingleAccountBalances singleAccountBalances = sorted.remove(0);
		sorted.add(singleAccountBalances);

		assertNotEquals(expectedBalances, sorted);
		// when
		sorted.sort(SINGLE_ACCOUNT_BALANCES_COMPARATOR);

		// then:
		assertEquals(expectedBalances, sorted);

	}

	@Test
	void summarizesAsExpected() {
		// given:
		List<SingleAccountBalances> expectedBalances = theExpectedBalances();
		// and:
		var desiredWarning = "Node '0.0.4' has unacceptably low balance " + anotherNodeBalance + "!";

		// when:
		var summary = subject.summarized(state);

		// then:
		assertEquals(ledgerFloat, summary.getTotalFloat().longValue());
		assertEquals(expectedBalances, summary.getOrderedBalances());
		// and:
		assertThat(logCaptor.warnLogs(), contains(desiredWarning));
	}

	private List<SingleAccountBalances> theExpectedBalances() {
		var singleAcctBuilder = SingleAccountBalances.newBuilder();
		var thisNode = singleAcctBuilder
				.setAccountID(asAccount("0.0.3"))
				.setHbarBalance(thisNodeBalance).build();

		var anotherNode = singleAcctBuilder
				.setHbarBalance(anotherNodeBalance)
				.setAccountID(asAccount("0.0.4")).build();

		var firstNon = singleAcctBuilder
				.setAccountID(asAccount("0.0.1001"))
				.setHbarBalance(firstNonNodeAccountBalance).build();

		TokenUnitBalance tokenBalances = TokenUnitBalance.newBuilder()
				.setTokenId(theToken)
				.setBalance(secondNonNodeTokenBalance).build();

		var secondNon = singleAcctBuilder
				.setAccountID(asAccount("0.0.1002"))
				.setHbarBalance(secondNonNodeAccountBalance)
				.addTokenUnitBalances(tokenBalances).build();

		return List.of(thisNode, anotherNode, firstNon, secondNon);
	}


	@Test
	void assuresExpectedDir() throws IOException {
		// given:
		subject.directories = assurance;

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		verify(assurance).ensureExistenceOf(expectedExportDir());
	}

	@Test
	void throwsOnUnexpectedTotalFloat() throws NegativeAccountBalanceException {
		// setup:
		var mutableAnotherNodeAccount = accounts.getForModify(fromAccountId(anotherNode));

		// given:
		mutableAnotherNodeAccount.setBalance(anotherNodeBalance + 1);

		// then:
		assertThrows(IllegalStateException.class,
				() -> subject.exportBalancesFrom(state, now, nodeId));
	}

	@Test
	void errorLogsOnIoException() throws IOException {
		// given:
		subject.directories = assurance;
		var desiredError = "Cannot ensure existence of export dir " + "'" + expectedExportDir() + "'!";
		// and:
		willThrow(IOException.class).given(assurance).ensureExistenceOf(any());

		// when:
		subject.exportProto = false;
		subject.exportBalancesFrom(state, now, nodeId);

		// then:
		assertThat(logCaptor.errorLogs(), contains(desiredError));
	}

	private String expectedExportDir() {
		return dynamicProperties.pathToBalancesExportDir() + File.separator + "balance0.0.3" + File.separator;
	}

	@Test
	void initsAsExpected() {
		// expect:
		assertEquals(ledgerFloat, subject.expectedFloat);
	}

	@Test
	void exportsWhenPeriodSecsHaveElapsed() {
		Instant startTime = Instant.parse("2021-03-11T10:59:59.0Z");

		subject = new SignedStateBalancesExporter(properties, signer, dynamicProperties);
		assertFalse(subject.isTimeToExport(startTime));
		assertEquals(startTime, subject.periodBegin);
		assertTrue(subject.isTimeToExport(startTime.plusSeconds(1)));
		assertEquals(startTime.plusSeconds(1), subject.periodBegin);

		subject = new SignedStateBalancesExporter(properties, signer, dynamicProperties);
		assertFalse(subject.isTimeToExport(startTime));
		assertTrue(subject.isTimeToExport(startTime.plusSeconds(2)));
		assertEquals(startTime.plusSeconds(2), subject.periodBegin);

		// Other scenarios
		shortlyAfter = startTime.plusSeconds(dynamicProperties.balancesExportPeriodSecs() / 2);
		assertFalse(subject.isTimeToExport(shortlyAfter));
		assertEquals(shortlyAfter, subject.periodBegin);

		Instant nextPeriod  = startTime.plusSeconds(dynamicProperties.balancesExportPeriodSecs() + 1);

		assertTrue(subject.isTimeToExport(nextPeriod));
		assertEquals(nextPeriod, subject.periodBegin );

		anEternityLater = startTime.plusSeconds(dynamicProperties.balancesExportPeriodSecs() * 2 + 1);
		assertTrue(subject.isTimeToExport(anEternityLater));
		assertEquals(anEternityLater, subject.periodBegin);

		subject = new SignedStateBalancesExporter(properties, signer, dynamicProperties);
		startTime = Instant.parse("2021-03-11T10:59:59.0Z");
		assertFalse(subject.isTimeToExport(startTime));
	}

	@AfterAll
	static void tearDown() throws IOException {
		Files.walk(Path.of("src/test/resources/balance0.0.3"))
				.sorted(Comparator.reverseOrder())
				.map(Path::toFile)
				.forEach(File::delete);
	}

	static Optional<AllAccountBalances> importBalanceProtoFile(String protoLoc) {
		try {
			FileInputStream fin = new FileInputStream(protoLoc);
			AllAccountBalances allAccountBalances = AllAccountBalances.parseFrom(fin);
			return Optional.ofNullable(allAccountBalances);
		} catch (IOException e) {
			return Optional.empty();
		}
	}
}
