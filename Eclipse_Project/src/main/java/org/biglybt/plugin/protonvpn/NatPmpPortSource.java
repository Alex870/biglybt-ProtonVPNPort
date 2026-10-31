package org.biglybt.plugin.protonvpn;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Secondary port source used only for the NAT-PMP comparison trial (docs/DESIGN.md §4.6).
 * Its results are never applied to BiglyBT in v0.1.x.
 *
 * <p>Sends the same requests as Proton's documented {@code natpmpc} commands (UDP and TCP,
 * internal port 1, external port 0, lifetime 60 s). Note these requests create or renew a
 * mapping on the gateway; the trial can be switched off in settings.
 */
final class NatPmpPortSource implements PortSource {

	static final String DEFAULT_GATEWAY = "10.2.0.1";
	static final int REQUEST_INTERNAL_PORT = 1;
	static final int REQUEST_EXTERNAL_PORT = 0;
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
	public PortResult query() {
		long now = clock.getAsLong();
		try {
			String gw = gateway.get();
			if (LogLocator.isBlank(gw)) {
				return PortResult.error("No NAT-PMP gateway configured", now, null);
			}
			InetAddress address = InetAddress.getByName(gw.trim());

			Integer udp = null;
			Integer tcp = null;
			long rtt = 0;
			StringBuilder errors = new StringBuilder();
			try {
				NatPmpClient.Mapping m = client.requestMapping(address, NatPmpClient.OP_UDP,
						REQUEST_INTERNAL_PORT, REQUEST_EXTERNAL_PORT, REQUEST_LIFETIME_SECS);
				udp = m.externalPort;
				rtt += m.rttMs;
			} catch (IOException e) {
				errors.append("UDP: ").append(e.getMessage());
			}
			try {
				NatPmpClient.Mapping m = client.requestMapping(address, NatPmpClient.OP_TCP,
						REQUEST_INTERNAL_PORT, REQUEST_EXTERNAL_PORT, REQUEST_LIFETIME_SECS);
				tcp = m.externalPort;
				rtt += m.rttMs;
			} catch (IOException e) {
				if (errors.length() > 0) errors.append("; ");
				errors.append("TCP: ").append(e.getMessage());
			}
			if (udp == null && tcp == null) {
				return PortResult.error(errors.toString(), now, null);
			}
			return PortResult.found(tcp, udp, errors.toString(), now, rtt);
		} catch (Throwable t) {
			return PortResult.error("NAT-PMP query failed: " + t, now, null);
		}
	}
}
