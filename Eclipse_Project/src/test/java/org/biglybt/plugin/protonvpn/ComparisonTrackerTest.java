package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.Test;

import org.biglybt.plugin.protonvpn.ComparisonTracker.Outcome;

class ComparisonTrackerTest {

	final Map<String, Long> store = new HashMap<>();
	final ComparisonTracker tracker = new ComparisonTracker(new ComparisonTracker.Store() {
		public long get(String key) {
			Long v = store.get(key);
			return v == null ? 0 : v;
		}

		public void set(String key, long value) {
			store.put(key, value);
		}
	});

	static PortResult log(Integer port) {
		return port == null ? PortResult.notFound("none", 0, null, -1) : PortResult.found(port, "", 0, 0, "f", 1);
	}

	static PortResult nat(Integer port) {
		return port == null ? PortResult.error("timeout", 0, null) : PortResult.found(port, port, "", 0, 5);
	}

	@Test
	void classifies() {
		assertEquals(Outcome.MATCH, ComparisonTracker.classify(log(1), nat(1)));
		assertEquals(Outcome.MISMATCH, ComparisonTracker.classify(log(1), nat(2)));
		assertEquals(Outcome.LOG_ONLY, ComparisonTracker.classify(log(1), nat(null)));
		assertEquals(Outcome.NATPMP_ONLY, ComparisonTracker.classify(log(null), nat(2)));
		assertEquals(Outcome.NEITHER, ComparisonTracker.classify(log(null), nat(null)));
		assertEquals(Outcome.LOG_ONLY, ComparisonTracker.classify(PortResult.unchanged(log(5), 0), nat(null)));
	}

	@Test
	void countsAndReportsChangesOnlyOnTransitions() {
		assertTrue(tracker.record(log(1), nat(1), 100).changed);
		assertFalse(tracker.record(log(1), nat(1), 200).changed);
		assertTrue(tracker.record(log(1), nat(null), 300).changed);
		assertEquals(2, tracker.count(Outcome.MATCH));
		assertEquals(1, tracker.count(Outcome.LOG_ONLY));
		assertEquals(100, tracker.count(ComparisonTracker.SINCE));
	}

	@Test
	void singleMismatchThatClearsIsTransientAndNatPmpLed() {
		tracker.record(log(1000 + 1), nat(2000), 1); // reconnect: NAT-PMP already sees the new port
		tracker.record(log(2000), nat(2000), 2); // log caught up
		assertEquals(1, tracker.count(ComparisonTracker.TRANSIENT));
		assertEquals(0, tracker.count(ComparisonTracker.PERSISTENT));
		assertEquals(1, tracker.count(ComparisonTracker.NATPMP_LED));
	}

	@Test
	void repeatedMismatchIsPersistentCountedOnce() {
		for (int i = 0; i < 5; i++) tracker.record(log(1001), nat(2000), i);
		tracker.record(log(1001), nat(1001), 9);
		assertEquals(5, tracker.count(Outcome.MISMATCH));
		assertEquals(1, tracker.count(ComparisonTracker.PERSISTENT));
		assertEquals(0, tracker.count(ComparisonTracker.TRANSIENT));
		assertEquals(0, tracker.count(ComparisonTracker.NATPMP_LED));
	}

	@Test
	void mismatchWithNewPortsIsReportedAgain() {
		assertTrue(tracker.record(log(1001), nat(2000), 1).changed);
		assertFalse(tracker.record(log(1001), nat(2000), 2).changed);
		assertTrue(tracker.record(log(1001), nat(3000), 3).changed);
	}

	@Test
	void detectsTcpUdpSplit() {
		ComparisonTracker.Evaluation e = tracker.record(log(50000), PortResult.found(50001, 50000, "", 0, 1), 1);
		assertTrue(e.tcpUdpSplit);
		assertEquals(Outcome.MISMATCH, e.outcome); // TCP port is the one compared
		assertEquals(1, tracker.count(ComparisonTracker.SPLIT));
	}

	@Test
	void persistsAcrossInstancesAndResets() {
		tracker.record(log(1), nat(1), 50);
		ComparisonTracker reloaded = new ComparisonTracker(new ComparisonTracker.Store() {
			public long get(String key) {
				Long v = store.get(key);
				return v == null ? 0 : v;
			}

			public void set(String key, long value) {
				store.put(key, value);
			}
		});
		assertEquals(1, reloaded.count(Outcome.MATCH));
		reloaded.reset(999);
		assertEquals(0, reloaded.count(Outcome.MATCH));
		assertEquals(999, reloaded.count(ComparisonTracker.SINCE));
	}

	@Test
	void counterKeysIgnoreDefaultLocale() {
		Locale previous = Locale.getDefault();
		try {
			Locale.setDefault(new Locale("tr", "TR"));
			assertEquals("mismatch", ComparisonTracker.key(Outcome.MISMATCH));
		} finally {
			Locale.setDefault(previous);
		}
	}

	@Test
	void summaryText() {
		assertEquals("no data yet", tracker.summary());
		tracker.record(log(1), nat(1), 86_400_000L);
		String s = tracker.summary();
		assertTrue(s.startsWith("1 match / 0 mismatch"), s);
		assertTrue(s.contains("1 checks since 1970-01-0"), s);
	}
}
