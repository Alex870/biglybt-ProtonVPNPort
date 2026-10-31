package org.biglybt.plugin.protonvpn;

/** Outcome of asking one {@link PortSource} for the forwarded port. Immutable. */
final class PortResult {

	enum Kind {
		/** A port was found by reading the source. */
		FOUND,
		/** The source has not changed since the last read; the cached port is returned. */
		UNCHANGED,
		/** The source was read but contains no port (or the source does not exist). */
		NOT_FOUND,
		/** The source could not be read. */
		ERROR
	}

	final Kind kind;
	/** Forwarded TCP port, or null. Log-derived results set TCP and UDP to the same value. */
	final Integer tcpPort;
	/** Forwarded UDP port, or null. */
	final Integer udpPort;
	/** Human-readable explanation, never null. */
	final String detail;
	/** Time the result was produced (epoch ms). */
	final long timestampMs;
	/** Time spent producing the result (ms), or -1 if not measured. */
	final long elapsedMs;
	/** Path of the file that was read, or null. */
	final String sourcePath;
	/** Last-modified time of the source file (epoch ms), or -1. */
	final long sourceMtimeMs;
	/** Log only: time the log line holding the port was written (epoch ms), or -1. */
	final long logEntryTimeMs;
	/** NAT-PMP only: internal port echoed by the gateway, or -1. */
	final int gatewayInternalPort;
	/** NAT-PMP only: mapping lifetime granted by the gateway (s), or -1. */
	final long gatewayLifetimeSecs;
	/** NAT-PMP only: gateway's "seconds since start of epoch" counter, or -1. */
	final long gatewayEpochSecs;

	private PortResult(Kind kind, Integer tcpPort, Integer udpPort, String detail, long timestampMs,
			long elapsedMs, String sourcePath, long sourceMtimeMs) {
		this(kind, tcpPort, udpPort, detail, timestampMs, elapsedMs, sourcePath, sourceMtimeMs, -1, -1, -1, -1);
	}

	private PortResult(Kind kind, Integer tcpPort, Integer udpPort, String detail, long timestampMs,
			long elapsedMs, String sourcePath, long sourceMtimeMs, int gatewayInternalPort,
			long gatewayLifetimeSecs, long gatewayEpochSecs, long logEntryTimeMs) {
		this.kind = kind;
		this.tcpPort = tcpPort;
		this.udpPort = udpPort;
		this.detail = detail == null ? "" : detail;
		this.timestampMs = timestampMs;
		this.elapsedMs = elapsedMs;
		this.sourcePath = sourcePath;
		this.sourceMtimeMs = sourceMtimeMs;
		this.gatewayInternalPort = gatewayInternalPort;
		this.gatewayLifetimeSecs = gatewayLifetimeSecs;
		this.gatewayEpochSecs = gatewayEpochSecs;
		this.logEntryTimeMs = logEntryTimeMs;
	}

	/** Returns a copy carrying the extra fields of a NAT-PMP reply. */
	PortResult withGatewayReply(int internalPort, long lifetimeSecs, long epochSecs) {
		return new PortResult(kind, tcpPort, udpPort, detail, timestampMs, elapsedMs, sourcePath, sourceMtimeMs,
				internalPort, lifetimeSecs, epochSecs, logEntryTimeMs);
	}

	/** Returns a copy carrying the time the log entry was written. */
	PortResult withLogEntryTime(long entryTimeMs) {
		return new PortResult(kind, tcpPort, udpPort, detail, timestampMs, elapsedMs, sourcePath, sourceMtimeMs,
				gatewayInternalPort, gatewayLifetimeSecs, gatewayEpochSecs, entryTimeMs);
	}

	static PortResult found(int port, String detail, long now, long elapsedMs, String path, long mtime) {
		return new PortResult(Kind.FOUND, port, port, detail, now, elapsedMs, path, mtime);
	}

	static PortResult found(Integer tcpPort, Integer udpPort, String detail, long now, long elapsedMs) {
		return new PortResult(Kind.FOUND, tcpPort, udpPort, detail, now, elapsedMs, null, -1);
	}

	static PortResult unchanged(PortResult cached, long now) {
		return new PortResult(Kind.UNCHANGED, cached.tcpPort, cached.udpPort, cached.detail, now, 0,
				cached.sourcePath, cached.sourceMtimeMs).withLogEntryTime(cached.logEntryTimeMs);
	}

	static PortResult notFound(String detail, long now, String path, long mtime) {
		return new PortResult(Kind.NOT_FOUND, null, null, detail, now, -1, path, mtime);
	}

	static PortResult error(String detail, long now, String path) {
		return new PortResult(Kind.ERROR, null, null, detail, now, -1, path, -1);
	}

	/** True if this result carries a port (FOUND or UNCHANGED with a port). */
	boolean hasPort() {
		return tcpPort != null || udpPort != null;
	}

	/** The port to compare or apply: TCP if known, otherwise UDP. Null if none. */
	Integer port() {
		return tcpPort != null ? tcpPort : udpPort;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder(kind.name());
		if (tcpPort != null && tcpPort.equals(udpPort)) {
			sb.append(" port=").append(tcpPort);
		} else {
			if (tcpPort != null) sb.append(" tcp=").append(tcpPort);
			if (udpPort != null) sb.append(" udp=").append(udpPort);
		}
		if (gatewayInternalPort >= 0) {
			sb.append(" [gateway: internal=").append(gatewayInternalPort).append(", lifetime=")
					.append(gatewayLifetimeSecs).append("s, epoch=").append(gatewayEpochSecs).append("s]");
		}
		if (!detail.isEmpty()) sb.append(" (").append(detail).append(')');
		return sb.toString();
	}
}
