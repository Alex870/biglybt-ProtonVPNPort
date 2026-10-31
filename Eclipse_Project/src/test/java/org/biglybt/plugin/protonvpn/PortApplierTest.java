package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PortApplierTest {

	static final class FakeCore implements PortApplier.CorePorts {
		int tcp, udp, writes;

		FakeCore(int tcp, int udp) {
			this.tcp = tcp;
			this.udp = udp;
		}

		public int tcp() { return tcp; }
		public int udp() { return udp; }
		public void setTcp(int p) { tcp = p; writes++; }
		public void setUdp(int p) { udp = p; writes++; }
	}

	@Test
	void changesBothPorts() {
		FakeCore core = new FakeCore(6881, 6881);
		PortApplier.Result r = new PortApplier(core).apply(51234);
		assertEquals(PortApplier.Result.Kind.CHANGED, r.kind);
		assertEquals(51234, core.tcp);
		assertEquals(51234, core.udp);
		assertEquals("Incoming port changed: TCP 6881 -> 51234, UDP 6881 -> 51234", r.describe());
	}

	@Test
	void udpMessageShowsOldUdpPort() {
		// 0.0.2 printed the old TCP port in the UDP message (bug B8)
		FakeCore core = new FakeCore(51234, 7000);
		PortApplier.Result r = new PortApplier(core).apply(51234);
		assertEquals("Incoming port changed: UDP 7000 -> 51234", r.describe());
		assertEquals(1, core.writes);
	}

	@Test
	void noWriteWhenAlreadySet() {
		FakeCore core = new FakeCore(51234, 51234);
		assertEquals(PortApplier.Result.Kind.UNCHANGED, new PortApplier(core).apply(51234).kind);
		assertEquals(0, core.writes);
	}

	@Test
	void rejectsOutOfRange() {
		FakeCore core = new FakeCore(6881, 6881);
		PortApplier a = new PortApplier(core);
		assertEquals(PortApplier.Result.Kind.INVALID, a.apply(0).kind);
		assertEquals(PortApplier.Result.Kind.INVALID, a.apply(1023).kind);
		assertEquals(PortApplier.Result.Kind.INVALID, a.apply(65536).kind);
		assertEquals(PortApplier.Result.Kind.INVALID, a.apply(99999).kind);
		assertEquals(0, core.writes);
		assertEquals(PortApplier.Result.Kind.CHANGED, a.apply(1024).kind);
		assertEquals(PortApplier.Result.Kind.UNCHANGED, new PortApplier(new FakeCore(65535, 65535)).apply(65535).kind);
	}
}
