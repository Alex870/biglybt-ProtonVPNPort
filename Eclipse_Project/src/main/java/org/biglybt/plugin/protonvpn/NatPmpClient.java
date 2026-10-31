package org.biglybt.plugin.protonvpn;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Minimal NAT-PMP client (RFC 6886) for port-mapping requests. Encoding and decoding are
 * pure static functions so they can be unit-tested without a network.
 *
 * <p>Proton VPN documents the equivalent of {@code natpmpc -a 1 0 udp 60 -g 10.2.0.1}:
 * internal port 1, suggested external port 0, lifetime 60 s. The gateway replies with the
 * port it forwards for this VPN session.
 */
final class NatPmpClient {

	static final int NATPMP_PORT = 5351;
	static final int OP_UDP = 1;
	static final int OP_TCP = 2;
	static final int REQUEST_LENGTH = 12;
	static final int RESPONSE_LENGTH = 16;

	/** Receive timeout per attempt: 3 attempts, cutting RFC 6886's 9-attempt back-off short. */
	static final int[] DEFAULT_TIMEOUTS_MS = { 250, 500, 1000 };

	/** A decoded mapping response. */
	static final class Mapping {
		final int opcode;
		final int resultCode;
		final long epochSecs;
		final int internalPort;
		final int externalPort;
		final long lifetimeSecs;
		/** Round-trip time of the successful attempt (ms), set by {@link #requestMapping}. */
		long rttMs = -1;

		Mapping(int opcode, int resultCode, long epochSecs, int internalPort, int externalPort, long lifetimeSecs) {
			this.opcode = opcode;
			this.resultCode = resultCode;
			this.epochSecs = epochSecs;
			this.internalPort = internalPort;
			this.externalPort = externalPort;
			this.lifetimeSecs = lifetimeSecs;
		}
	}

	/** The gateway answered with a non-zero result code, or the reply was malformed. */
	static final class NatPmpException extends IOException {
		private static final long serialVersionUID = 1L;
		final int resultCode;

		NatPmpException(String message, int resultCode) {
			super(message);
			this.resultCode = resultCode;
		}
	}

	private final int serverPort;
	private final int[] timeoutsMs;

	NatPmpClient() {
		this(NATPMP_PORT, DEFAULT_TIMEOUTS_MS);
	}

	NatPmpClient(int serverPort, int[] timeoutsMs) {
		this.serverPort = serverPort;
		this.timeoutsMs = timeoutsMs.clone();
	}

	static byte[] encodeMappingRequest(int opcode, int internalPort, int suggestedExternalPort, long lifetimeSecs) {
		if (opcode != OP_UDP && opcode != OP_TCP) throw new IllegalArgumentException("opcode " + opcode);
		byte[] b = new byte[REQUEST_LENGTH];
		b[0] = 0; // version
		b[1] = (byte) opcode;
		// b[2..3] reserved = 0
		putU16(b, 4, internalPort);
		putU16(b, 6, suggestedExternalPort);
		putU32(b, 8, lifetimeSecs);
		return b;
	}

	/**
	 * Decodes a mapping response.
	 * @throws NatPmpException if the packet is malformed, answers a different opcode,
	 *         or carries a non-zero result code
	 */
	static Mapping decodeMappingResponse(byte[] b, int length, int expectedOpcode) throws NatPmpException {
		if (length < RESPONSE_LENGTH) {
			throw new NatPmpException("NAT-PMP response too short (" + length + " bytes)", -1);
		}
		if (b[0] != 0) {
			throw new NatPmpException("Unsupported NAT-PMP version " + (b[0] & 0xff), -1);
		}
		int op = b[1] & 0xff;
		if (op != 128 + expectedOpcode) {
			throw new NatPmpException("Unexpected NAT-PMP opcode " + op + " (expected " + (128 + expectedOpcode) + ")", -1);
		}
		int result = getU16(b, 2);
		if (result != 0) {
			throw new NatPmpException("NAT-PMP gateway refused mapping: " + resultName(result) + " (" + result + ")", result);
		}
		return new Mapping(expectedOpcode, result, getU32(b, 4), getU16(b, 8), getU16(b, 10), getU32(b, 12));
	}

	static String resultName(int code) {
		switch (code) {
		case 0: return "Success";
		case 1: return "Unsupported Version";
		case 2: return "Not Authorized/Refused";
		case 3: return "Network Failure";
		case 4: return "Out of Resources";
		case 5: return "Unsupported Opcode";
		default: return "Unknown result code";
		}
	}

	/**
	 * Sends a mapping request and waits for the reply, retrying with increasing timeouts.
	 * Replies that don't match the request (wrong opcode, malformed) are ignored; a reply
	 * with a non-zero result code ends the request with a {@link NatPmpException}.
	 */
	Mapping requestMapping(InetAddress gateway, int opcode, int internalPort, int suggestedExternalPort,
			long lifetimeSecs) throws IOException {
		byte[] request = encodeMappingRequest(opcode, internalPort, suggestedExternalPort, lifetimeSecs);
		byte[] buf = new byte[64];
		try (DatagramSocket socket = new DatagramSocket()) {
			socket.connect(gateway, serverPort);
			for (int timeout : timeoutsMs) {
				long start = System.nanoTime();
				socket.send(new DatagramPacket(request, request.length));
				long deadline = start + timeout * 1_000_000L;
				while (true) {
					long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
					if (remainingMs <= 0) break;
					socket.setSoTimeout((int) remainingMs);
					DatagramPacket reply = new DatagramPacket(buf, buf.length);
					try {
						socket.receive(reply);
					} catch (SocketTimeoutException e) {
						break;
					}
					Mapping m;
					try {
						m = decodeMappingResponse(reply.getData(), reply.getLength(), opcode);
					} catch (NatPmpException e) {
						if (e.resultCode > 0) throw e;
						continue; // malformed or unrelated packet: keep waiting
					}
					m.rttMs = (System.nanoTime() - start) / 1_000_000;
					return m;
				}
			}
		}
		throw new SocketTimeoutException("No NAT-PMP response from " + gateway.getHostAddress() + ":" + serverPort
				+ " after " + timeoutsMs.length + " attempts");
	}

	private static void putU16(byte[] b, int off, int v) {
		b[off] = (byte) (v >>> 8);
		b[off + 1] = (byte) v;
	}

	private static void putU32(byte[] b, int off, long v) {
		b[off] = (byte) (v >>> 24);
		b[off + 1] = (byte) (v >>> 16);
		b[off + 2] = (byte) (v >>> 8);
		b[off + 3] = (byte) v;
	}

	private static int getU16(byte[] b, int off) {
		return ((b[off] & 0xff) << 8) | (b[off + 1] & 0xff);
	}

	private static long getU32(byte[] b, int off) {
		return ((long) (b[off] & 0xff) << 24) | ((b[off + 1] & 0xff) << 16) | ((b[off + 2] & 0xff) << 8) | (b[off + 3] & 0xff);
	}
}
