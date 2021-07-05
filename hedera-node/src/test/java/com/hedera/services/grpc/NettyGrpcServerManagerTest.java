package com.hedera.services.grpc;

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

import com.hedera.services.context.properties.NodeLocalProperties;
import com.hedera.services.utils.Pause;
import com.hedera.test.extensions.LogCaptor;
import com.hedera.test.extensions.LogCaptureExtension;
import com.hedera.test.extensions.LoggingSubject;
import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.NettyServerBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

import javax.inject.Inject;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

import static com.hedera.test.CiConditions.outsideCircleCi;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsIterableContainingInOrder.contains;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.any;
import static org.mockito.BDDMockito.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.mock;
import static org.mockito.BDDMockito.verify;
import static org.mockito.BDDMockito.verifyNoInteractions;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ExtendWith(LogCaptureExtension.class)
class NettyGrpcServerManagerTest {
	private int startRetries = 3;
	private long startRetryIntervalMs = 1_000L;
	private int port = 8080;
	private int tlsPort = port + 1;
	private Server server;
	private Server tlsServer;
	private Consumer<Thread> hookAdder;
	private Consumer<String> println;
	private NodeLocalProperties nodeProperties;
	private NettyServerBuilder nettyBuilder;
	private NettyServerBuilder tlsBuilder;
	private ConfigDrivenNettyFactory nettyFactory;
	private BindableService a, b, c;
	private List<BindableService> bindableServices;
	private List<ServerServiceDefinition> serviceDefinitions;
	private ServerServiceDefinition d;

	@Inject
	private LogCaptor logCaptor;

	@LoggingSubject
	private NettyGrpcServerManager subject;

	@BeforeEach
	private void setup() throws Exception {
		server = mock(Server.class);
		tlsServer = mock(Server.class);
		a = mock(BindableService.class);
		b = mock(BindableService.class);
		c = mock(BindableService.class);
		bindableServices = List.of(a, b, c);
		d = mock(ServerServiceDefinition.class);
		serviceDefinitions = List.of(d);

		nettyBuilder = mock(NettyServerBuilder.class);
		given(nettyBuilder.addService(any(BindableService.class))).willReturn(nettyBuilder);
		given(nettyBuilder.addService(any(ServerServiceDefinition.class))).willReturn(nettyBuilder);
		given(nettyBuilder.build()).willReturn(server);

		tlsBuilder = mock(NettyServerBuilder.class);
		given(tlsBuilder.addService(any(BindableService.class))).willReturn(tlsBuilder);
		given(tlsBuilder.addService(any(ServerServiceDefinition.class))).willReturn(tlsBuilder);
		given(tlsBuilder.build()).willReturn(tlsServer);

		nettyFactory = mock(ConfigDrivenNettyFactory.class);
		given(nettyFactory.builderFor(port, false)).willReturn(nettyBuilder);
		given(nettyFactory.builderFor(tlsPort, true)).willReturn(tlsBuilder);

		nodeProperties = mock(NodeLocalProperties.class);
		given(nodeProperties.nettyStartRetries()).willReturn(startRetries);
		given(nodeProperties.nettyStartRetryIntervalMs()).willReturn(startRetryIntervalMs);

		println = mock(Consumer.class);
		hookAdder = mock(Consumer.class);

		subject = new NettyGrpcServerManager(
				hookAdder, nodeProperties, bindableServices, nettyFactory, serviceDefinitions);
	}

	@Test
	void retriesStartingTilSuccess() throws Exception {
		// setup:
		final var mockPause = mock(Pause.class);

		given(server.start())
				.willThrow(new IOException("Failed to bind"))
				.willThrow(new IOException("Failed to bind"))
				.willReturn(server);

		// when:
		subject.startOneNettyServer(false, port, ignore -> {}, mockPause);

		// then:
		verify(mockPause, times(2)).forMs(startRetryIntervalMs);
		verify(server, times(3)).start();
		if (outsideCircleCi.getAsBoolean()) {
			assertThat(logCaptor.warnLogs(), contains(
					"(Attempts=1) Still trying to start Netty on port 8080...Failed to bind",
					"(Attempts=2) Still trying to start Netty on port 8080...Failed to bind"));
		}
	}

	@Test
	void givesUpIfMaxRetriesExhaustedAndPropagatesIOException() throws Exception {
		// setup:
		final var mockPause = mock(Pause.class);

		given(server.start())
				.willThrow(new IOException("Failed to bind"));

		// expect:
		assertThrows(IOException.class, () ->
				subject.startOneNettyServer(false, port, ignore -> {}, mockPause));

		// then:
		verify(mockPause, times(startRetries)).forMs(startRetryIntervalMs);
		verify(server, times(startRetries + 1)).start();
		if (outsideCircleCi.getAsBoolean()) {
			assertThat(logCaptor.warnLogs(), contains(
					"(Attempts=1) Still trying to start Netty on port 8080...Failed to bind",
					"(Attempts=2) Still trying to start Netty on port 8080...Failed to bind",
					"(Attempts=3) Still trying to start Netty on port 8080...Failed to bind"));
		}
	}

	@Test
	void neverRetriesIfZeroRetriesSet() throws Exception {
		// setup:
		final var mockPause = mock(Pause.class);

		given(nodeProperties.nettyStartRetries()).willReturn(0);
		subject = new NettyGrpcServerManager(
				hookAdder, nodeProperties, bindableServices, nettyFactory, serviceDefinitions);
		given(server.start())
				.willThrow(new IOException("Failed to bind"));

		// expect:
		assertThrows(IOException.class, () ->
				subject.startOneNettyServer(false, port, ignore -> {}, mockPause));

		// then:
		verify(mockPause, never()).forMs(startRetryIntervalMs);
		verify(server).start();
		if (outsideCircleCi.getAsBoolean()) {
			assertTrue(logCaptor.warnLogs().isEmpty());
		}
	}

	@Test
	void buildsAndAddsHookNonTlsOnNonExistingCertOrKey() throws Exception {
		given(nettyFactory.builderFor(tlsPort, true)).willThrow(new FileNotFoundException());
		ArgumentCaptor<Thread> captor = ArgumentCaptor.forClass(Thread.class);

		willDoNothing().given(hookAdder).accept(captor.capture());

		// when:
		subject.start(port, tlsPort, println);

		// then:
		verifyBuilder(nettyBuilder);
		verifyNoInteractions(tlsBuilder);
		// and:
		verify(server).start();
		verifyNoInteractions(tlsServer);
		// and:
		try {
			captor.getValue().run();
		} catch (Exception ignore) {}
		// and:
		verify(server).awaitTermination(anyLong(), any());
	}

	@Test
	void buildsAndAddsHookAsExpected() throws Exception {
		// setup:
		ArgumentCaptor<Thread> captor = ArgumentCaptor.forClass(Thread.class);

		willDoNothing().given(hookAdder).accept(captor.capture());

		// when:
		subject.start(port, tlsPort, println);

		// then:
		verifyBuilder(nettyBuilder);
		verifyBuilder(tlsBuilder);
		// and:
		verify(server).start();
		verify(tlsServer).start();
		// and:
		try {
			captor.getValue().run();
		} catch (Exception ignore) {}
		// and:
		verify(server).awaitTermination(anyLong(), any());
		verify(tlsServer).awaitTermination(anyLong(), any());
	}

	private void verifyBuilder(NettyServerBuilder builder) {
		verify(builder).addService(a);
		verify(builder).addService(b);
		verify(builder).addService(c);
		verify(builder).addService(d);
		verify(builder).build();
	}

	@Test
	void throwsIseOnProblem() {
		willThrow(RuntimeException.class).given(hookAdder).accept(any());

		// expect:
		assertThrows(IllegalStateException.class, () -> subject.start(port, tlsPort, println));
	}

	@Test
	void catchesInterruptedException() throws Exception {
		// setup:
		ArgumentCaptor<Thread> captor = ArgumentCaptor.forClass(Thread.class);

		willDoNothing().given(hookAdder).accept(captor.capture());
		// and:
		given(server.awaitTermination(anyLong(), any())).willAnswer(ignore -> {
			Thread.sleep(5_000L);
			return null;
		});

		// when:
		subject.start(port, tlsPort, println);

		// then:
		captor.getValue().start();
		Thread.sleep(10L);
		assertDoesNotThrow(() -> captor.getValue().interrupt());
	}
}
