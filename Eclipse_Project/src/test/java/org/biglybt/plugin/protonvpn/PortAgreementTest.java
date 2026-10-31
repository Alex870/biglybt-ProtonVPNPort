package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import org.biglybt.plugin.protonvpn.ComparisonTracker.Outcome;
import org.biglybt.plugin.protonvpn.PortAgreement.State;

class PortAgreementTest {

	final PortAgreement a = new PortAgreement();
	static final long NOW = 1_000_000_000L;

	static PortResult log(int port, long entryTime) {
		return PortResult.found(port, "", 0, 0, "f", 1).withLogEntryTime(entryTime);
	}

	static PortResult nat(int port) {
		return PortResult.found(port, port, "", 0, 1);
	}

	@Test
	void agreeIsQuiet() {
		PortAgreement.Decision d = a.record(Outcome.MATCH, log(49669, NOW - 5000), nat(49669), NOW);
		assertEquals(State.AGREE, d.state);
		assertEquals("Agree — the VPN server confirms port 49669", d.statusText);
		assertNull(d.alert);
		assertNull(d.logLine);
		assertFalse(d.recheckSoon);
	}

	@Test
	void firstMismatchRechecksSecondAlertsOnce() {
		PortAgreement.Decision first = a.record(Outcome.MISMATCH, log(49669, NOW - 5000), nat(50000), NOW);
		assertEquals(State.RECHECKING, first.state);
		assertTrue(first.recheckSoon);
		assertNull(first.alert);
		assertTrue(first.statusText.contains("(log entry 5 s old)"), first.statusText);

		PortAgreement.Decision second = a.record(Outcome.MISMATCH, log(49669, NOW - 5000), nat(50000), NOW + 15_000);
		assertEquals(State.DISAGREE, second.state);
		assertFalse(second.recheckSoon);
		assertNotNull(second.alert);
		assertTrue(second.alert.contains("If incoming connections stop working, reconnect Proton VPN."), second.alert);

		PortAgreement.Decision third = a.record(Outcome.MISMATCH, log(49669, NOW - 5000), nat(50000), NOW + 75_000);
		assertNull(third.alert, "one alert per episode");
		assertTrue(third.statusText.contains("(3 checks)"));
	}

	@Test
	void staleLogEntryIsCalledOut() {
		a.record(Outcome.MISMATCH, log(49669, NOW - 10 * 60_000), nat(50000), NOW);
		PortAgreement.Decision d = a.record(Outcome.MISMATCH, log(49669, NOW - 10 * 60_000), nat(50000), NOW + 15_000);
		assertTrue(d.alert.contains("(log entry 10 min old, looks stale)"), d.alert);
		assertTrue(d.alert.contains("is Proton VPN still connected"), d.alert);
	}

	@Test
	void resolutionIsLoggedAndANewEpisodeAlertsAgain() {
		a.record(Outcome.MISMATCH, log(1, -1), nat(2), NOW);
		a.record(Outcome.MISMATCH, log(1, -1), nat(2), NOW);
		PortAgreement.Decision ok = a.record(Outcome.MATCH, log(2, -1), nat(2), NOW);
		assertTrue(ok.logLine.startsWith("Port check: disagreement resolved"), ok.logLine);
		assertTrue(ok.logLine.contains("after 2 checks"), ok.logLine);

		assertTrue(a.record(Outcome.MISMATCH, log(2, -1), nat(3), NOW).recheckSoon, "new episode re-checks again");
		assertNotNull(a.record(Outcome.MISMATCH, log(2, -1), nat(3), NOW).alert, "and alerts again");
	}

	@Test
	void noAnswerAndOffAndNothingToCheck() {
		PortResult timeout = PortResult.error("UDP: timed out", 0, null);
		PortAgreement.Decision d = a.record(Outcome.LOG_ONLY, log(49669, -1), timeout, NOW);
		assertEquals(State.NO_ANSWER, d.state);
		assertTrue(d.statusText.contains("didn't answer NAT-PMP (UDP: timed out)"), d.statusText);
		assertNull(d.alert);
		assertEquals(State.OFF, a.off().state);
		assertEquals(State.NOTHING_TO_CHECK, a.nothingToCheck().state);
	}

	@Test
	void noAnswerInTheMiddleEndsTheEpisodeWithoutAResolvedMessage() {
		a.record(Outcome.MISMATCH, log(1, -1), nat(2), NOW);
		a.record(Outcome.LOG_ONLY, log(1, -1), PortResult.error("x", 0, null), NOW);
		assertNull(a.record(Outcome.MATCH, log(1, -1), nat(1), NOW).logLine);
	}
}
