package org.biglybt.plugin.protonvpn;

/** Something that can report the port Proton VPN is currently forwarding. */
interface PortSource {

	/** Queries the source. Implementations never throw; failures become {@link PortResult.Kind#ERROR}. */
	PortResult query();
}
