package org.biglybt.plugin.protonvpn;

/** Asks the VPN gateway about a port mapping via NAT-PMP. Implementations never throw. */
interface NatPmpProbe {

	/**
	 * Requests (and so renews) the mapping for {@code internalPort}, suggesting
	 * {@code suggestedExternalPort} as the public port.
	 */
	PortResult query(int internalPort, int suggestedExternalPort);
}
