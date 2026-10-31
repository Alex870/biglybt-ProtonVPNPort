package org.biglybt.plugin.protonvpn;

/**
 * The only component that writes BiglyBT's incoming port settings. Validates the port
 * and writes TCP and UDP only when they differ from it.
 */
final class PortApplier {

	static final int MIN_PORT = 1024;
	static final int MAX_PORT = 65535;

	/** Access to BiglyBT's incoming port settings. */
	interface CorePorts {
		int tcp();

		int udp();

		void setTcp(int port);

		void setUdp(int port);
	}

	static final class Result {
		enum Kind { CHANGED, UNCHANGED, INVALID }

		final Kind kind;
		final int port;
		final int oldTcp;
		final int oldUdp;

		Result(Kind kind, int port, int oldTcp, int oldUdp) {
			this.kind = kind;
			this.port = port;
			this.oldTcp = oldTcp;
			this.oldUdp = oldUdp;
		}

		String describe() {
			switch (kind) {
			case INVALID:
				return "Ignored port " + port + ": outside the valid range " + MIN_PORT + "-" + MAX_PORT;
			case UNCHANGED:
				return "TCP and UDP ports already set to " + port;
			default:
				StringBuilder sb = new StringBuilder("Incoming port changed:");
				if (oldTcp != port) sb.append(" TCP ").append(oldTcp).append(" -> ").append(port);
				if (oldUdp != port) sb.append(oldTcp != port ? "," : "").append(" UDP ").append(oldUdp).append(" -> ").append(port);
				return sb.toString();
			}
		}
	}

	private final CorePorts core;

	PortApplier(CorePorts core) {
		this.core = core;
	}

	static boolean isValid(int port) {
		return port >= MIN_PORT && port <= MAX_PORT;
	}

	Result apply(int port) {
		int oldTcp = core.tcp();
		int oldUdp = core.udp();
		if (!isValid(port)) {
			return new Result(Result.Kind.INVALID, port, oldTcp, oldUdp);
		}
		boolean changed = false;
		if (oldTcp != port) {
			core.setTcp(port);
			changed = true;
		}
		if (oldUdp != port) {
			core.setUdp(port);
			changed = true;
		}
		return new Result(changed ? Result.Kind.CHANGED : Result.Kind.UNCHANGED, port, oldTcp, oldUdp);
	}

	int currentTcp() {
		return core.tcp();
	}

	int currentUdp() {
		return core.udp();
	}
}
