package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class CheckSchedulerTest {

	/** Simulates the 15 s timer for {@code durationSecs} and returns the times (s) a check ran. */
	static List<Long> simulate(int intervalSecs, int durationSecs, long jitterMs) {
		CheckScheduler s = new CheckScheduler();
		List<Long> fired = new ArrayList<>();
		for (long t = CheckScheduler.TICK_MS; t <= durationSecs * 1000L; t += CheckScheduler.TICK_MS) {
			long now = t + ((t / CheckScheduler.TICK_MS) % 2 == 0 ? -jitterMs : jitterMs);
			if (s.isDue(now, intervalSecs)) {
				s.markChecked(now);
				fired.add(t / 1000);
			}
		}
		return fired;
	}

	@Test
	void firstTickAlwaysChecks() {
		assertEquals(15L, (long) simulate(3600, 15, 0).get(0));
	}

	@Test
	void multipleOf15() {
		assertEquals(listOf(15, 135, 255), simulate(120, 300, 0));
	}

	@Test
	void hundredSecondsDoesNotFireEvery30Seconds() {
		// 0.0.2 fired every 30 s for a 100 s setting (bug B1). Now: next tick at or after 100 s.
		assertEquals(listOf(15, 120, 225, 330), simulate(100, 330, 0));
	}

	@Test
	void thousandSecondsIsNot330() {
		List<Long> fired = simulate(1000, 2100, 0);
		assertEquals(listOf(15, 1020, 2025), fired);
	}

	@Test
	void timerJitterDoesNotSkipATick() {
		assertEquals(listOf(15, 135, 255), simulate(120, 300, 400));
	}

	@Test
	void minimumIntervalFiresEveryTick() {
		assertEquals(listOf(15, 30, 45, 60), simulate(15, 60, 300));
	}

	private static List<Long> listOf(long... v) {
		List<Long> l = new ArrayList<>();
		for (long x : v) l.add(x);
		return l;
	}
}
