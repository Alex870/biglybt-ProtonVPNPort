package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NatPmpClientTest {

	static byte[] response(int opcodePlus128, int result, long epoch, int internal, int external, long lifetime) {
		byte[] b = new byte[16];
		b[0] = 0;
		b[1] = (byte) opcodePlus128;
		b[2] = (byte) (result >>> 8);
		b[3] = (byte) result;
		b[4] = (byte) (epoch >>> 24);
		b[5] = (byte) (epoch >>> 16);
		b[6] = (byte) (epoch >>> 8);
		b[7] = (byte) epoch;
		b[8] = (byte) (internal >>> 8);
		b[9] = (byte) internal;
		b[10] = (byte) (external >>> 8);
		b[11] = (byte) external;
		b[12] = (byte) (lifetime >>> 24);
		b[13] = (byte) (lifetime >>> 16);
		b[14] = (byte) (lifetime >>> 8);
		b[15] = (byte) lifetime;
		return b;
	}

	@Test
	void encodesProtonDocumentedRequest() {
		// natpmpc -a 1 0 udp 60
		assertArrayEquals(new byte[] { 0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 60 },
				NatPmpClient.encodeMappingRequest(NatPmpClient.OP_UDP, 1, 0, 60));
		assertArrayEquals(new byte[] { 0, 2, 0, 0, (byte) 0xc8, 0x22, (byte) 0xff, (byte) 0xff, 0, 1, 0, 0 },
				NatPmpClient.encodeMappingRequest(NatPmpClient.OP_TCP, 51234, 65535, 65536));
	}

	@Test
	void rejectsUnknownOpcode() {
		assertThrows(IllegalArgumentException.class, () -> NatPmpClient.encodeMappingRequest(0, 1, 0, 60));
	}

	@Test
	void decodesSuccessResponseWithUnsignedFields() throws Exception {
		byte[] b = response(129, 0, 0xF0000000L, 1, 60211, 60);
		NatPmpClient.Mapping m = NatPmpClient.decodeMappingResponse(b, 16, NatPmpClient.OP_UDP);
		assertEquals(60211, m.externalPort);
		assertEquals(1, m.internalPort);
		assertEquals(60, m.lifetimeSecs);
		assertEquals(0xF0000000L, m.epochSecs);
	}

	@Test
	void decodeRejectsErrorsAndMalformedPackets() {
		NatPmpClient.NatPmpException refused = assertThrows(NatPmpClient.NatPmpException.class,
				() -> NatPmpClient.decodeMappingResponse(response(130, 2, 0, 1, 0, 0), 16, NatPmpClient.OP_TCP));
		assertEquals(2, refused.resultCode);
		assertTrue(refused.getMessage().contains("Not Authorized/Refused"));
		assertEquals(-1, assertThrows(NatPmpClient.NatPmpException.class,
				() -> NatPmpClient.decodeMappingResponse(new byte[16], 15, 1)).resultCode);
		assertThrows(NatPmpClient.NatPmpException.class,
				() -> NatPmpClient.decodeMappingResponse(response(130, 0, 0, 1, 5, 60), 16, NatPmpClient.OP_UDP));
		byte[] v1 = response(129, 0, 0, 1, 5, 60);
		v1[0] = 1;
		assertThrows(NatPmpClient.NatPmpException.class, () -> NatPmpClient.decodeMappingResponse(v1, 16, 1));
	}

	// --- Round trips against a local fake gateway -----------------------------------

	private FakeGateway gateway;

	@AfterEach
	void stop() {
		if (gateway != null) gateway.close();
	}

	/** Answers each request using {@code reply}; a null reply means "stay silent". */
	static final class FakeGateway implements AutoCloseable {
		final DatagramSocket socket;
		final AtomicInteger requests = new AtomicInteger();
		final Thread thread;

		FakeGateway(Function<byte[], byte[][]> reply) throws IOException {
			socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
			thread = new Thread(() -> {
				byte[] buf = new byte[64];
				while (!socket.isClosed()) {
					try {
						DatagramPacket p = new DatagramPacket(buf, buf.length);
						socket.receive(p);
						requests.incrementAndGet();
						byte[][] out = reply.apply(Arrays.copyOf(p.getData(), p.getLength()));
						if (out != null) {
							for (byte[] o : out) socket.send(new DatagramPacket(o, o.length, p.getSocketAddress()));
						}
					} catch (IOException e) {
						return;
					}
				}
			});
			thread.setDaemon(true);
			thread.start();
		}

		int port() {
			return socket.getLocalPort();
		}

		@Override
		public void close() {
			socket.close();
		}
	}

	NatPmpClient client() {
		return new NatPmpClient(gateway.port(), new int[] { 100, 100, 100 });
	}

	@Test
	void roundTripReturnsExternalPortAndIgnoresJunk() throws Exception {
		gateway = new FakeGateway(req -> new byte[][] { { 1, 2, 3 }, response(128 + req[1], 0, 1, 1, 51234, 60) });
		NatPmpClient.Mapping m = client().requestMapping(InetAddress.getLoopbackAddress(), NatPmpClient.OP_TCP, 1, 0, 60);
		assertEquals(51234, m.externalPort);
		assertTrue(m.rttMs >= 0);
	}

	@Test
	void retriesAfterSilenceThenSucceeds() throws Exception {
		AtomicInteger n = new AtomicInteger();
		gateway = new FakeGateway(req -> n.incrementAndGet() < 2 ? null : new byte[][] { response(128 + req[1], 0, 1, 1, 40000, 60) });
		assertEquals(40000, client().requestMapping(InetAddress.getLoopbackAddress(), NatPmpClient.OP_UDP, 1, 0, 60).externalPort);
		assertEquals(2, gateway.requests.get());
	}

	@Test
	void timesOutAfterThreeAttempts() throws Exception {
		gateway = new FakeGateway(req -> null);
		assertThrows(SocketTimeoutException.class,
				() -> client().requestMapping(InetAddress.getLoopbackAddress(), NatPmpClient.OP_UDP, 1, 0, 60));
		assertEquals(3, gateway.requests.get());
	}

	@Test
	void refusalEndsImmediately() throws Exception {
		gateway = new FakeGateway(req -> new byte[][] { response(128 + req[1], 3, 1, 1, 0, 0) });
		NatPmpClient.NatPmpException e = assertThrows(NatPmpClient.NatPmpException.class,
				() -> client().requestMapping(InetAddress.getLoopbackAddress(), NatPmpClient.OP_UDP, 1, 0, 60));
		assertEquals(3, e.resultCode);
		assertEquals(1, gateway.requests.get());
	}

	@Test
	void portSourceQueriesUdpAndTcp() throws Exception {
		gateway = new FakeGateway(req -> new byte[][] { response(128 + req[1], 0, 1, 1, req[1] == 1 ? 50000 : 50001, 60) });
		NatPmpPortSource src = new NatPmpPortSource(() -> "127.0.0.1", client(), () -> 42L);
		PortResult r = src.query();
		assertEquals(PortResult.Kind.FOUND, r.kind);
		assertEquals(Integer.valueOf(50000), r.udpPort);
		assertEquals(Integer.valueOf(50001), r.tcpPort);
		assertEquals(2, gateway.requests.get());
	}

	@Test
	void portSourceErrorsAreResultsNotExceptions() throws Exception {
		gateway = new FakeGateway(req -> null);
		assertEquals(PortResult.Kind.ERROR, new NatPmpPortSource(() -> "127.0.0.1", client(), () -> 1L).query().kind);
		assertEquals(PortResult.Kind.ERROR, new NatPmpPortSource(() -> " ", client(), () -> 1L).query().kind);
		assertEquals(PortResult.Kind.ERROR, new NatPmpPortSource(() -> "not a host name !", client(), () -> 1L).query().kind);
	}
}
