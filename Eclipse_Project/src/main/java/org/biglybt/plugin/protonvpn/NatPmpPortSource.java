package org.biglybt.plugin.protonvpn;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * NAT-PMP side of the comparison trial (docs/DESIGN.md §4.6). Its results are never
 * applied to BiglyBT.
 *
 * <p>The trial asks about the <b>Proton app's own mapping</b>: it requests the port the
 * log reports as both the internal and the suggested external port. Proton allows one
 * working forward per VPN session and the app already holds it, so a request for a new
 * mapping (Proton's Linux recipe, internal port 1) returns a port that doesn't forward
 * anything (observed 2026-10-31).
 *
 * <p>NAT-PMP has no read-only query: every request also renews the mapping, to
 * {@link #REQUEST_LIFETIME_SECS}. That matches the Windows app, which asks for 60 s and
 * renews every 30 s, so the trial never shortens the app's mapping.
 */
final class NatPmpPortSource implements NatPmpProbe {

	static final String DEFAULT_GATEWAY = "10.2.0.1";
	static final long REQUEST_LIFETIME_SECS = 60;

	private final Supplier<String> gateway;
	private final NatPmpClient client;
	private final LongSupplier clock;

	NatPmpPortSource(Supplier<String> gateway, NatPmpClient client, LongSupplier clock) {
		this.gateway = gateway;
		this.client = client;
		this.clock = clock;
	}

	@Override
	public PortResult query(int internalPort, int suggestedExternalPort) {
		long now = clock.getAsLong();
		try {
			String gw = gateway.get();
			if (LogLocator.isBlank(gw)) {
				return PortResult.error("No NAT-PMP gateway configured", now, null);
			}
			InetAddress address = InetAddress.getByName(gw.trim());

			NatPmpClient.Mapping udp = null;
			NatPmpClient.Mapping tcp = null;
			StringBuilder errors = new StringBuilder();
			try {
				udp = client.requestMapping(address, NatPmpClient.OP_UDP, internalPort, suggestedExternalPort,
						REQUEST_LIFETIME_SECS);
			} catch (IOException e) {
				errors.append("UDP: ").append(e.getMessage());
			}
			try {
				tcp = client.requestMapping(address, NatPmpClient.OP_TCP, internalPort, suggestedExternalPort,
						REQUEST_LIFETIME_SECS);
			} catch (IOException e) {
				if (errors.length() > 0) errors.append("; ");
				errors.append("TCP: ").append(e.getMessage());
			}
			if (udp == null && tcp == null) {
				return PortResult.error(errors.toString(), now, null);
			}
			long rtt = (udp != null ? udp.rttMs : 0) + (tcp != null ? tcp.rttMs : 0);
			NatPmpClient.Mapping reported = tcp != null ? tcp : udp;
			return PortResult.found(tcp != null ? tcp.externalPort : null, udp != null ? udp.externalPort : null,
					errors.toString(), now, rtt)
					.withGatewayReply(reported.internalPort, reported.lifetimeSecs, reported.epochSecs);
		} catch (Throwable t) {
			return PortResult.error("NAT-PMP query failed: " + t, now, null);
		}
	}
}
