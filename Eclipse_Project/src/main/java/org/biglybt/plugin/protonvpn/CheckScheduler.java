package org.biglybt.plugin.protonvpn;

/**
 * Decides when a check is due. The plugin's timer ticks every {@link #TICK_MS}; a check
 * runs on the first tick and then whenever at least the configured interval has elapsed
 * since the previous check. Time-based, so intervals that are not multiples of the tick
 * work correctly (fixes bug B1 in docs/DESIGN.md).
 */
final class CheckScheduler {

	static final long TICK_MS = 15_000;

	/** Allowance for timer jitter, so a 120 s interval fires on the 8th tick, not the 9th. */
	static final long JITTER_ALLOWANCE_MS = 1_000;

	private boolean checkedOnce = false;
	private long lastCheckMs;

	synchronized boolean isDue(long nowMs, int intervalSecs) {
		if (!checkedOnce) {
			return true;
		}
		long intervalMs = Math.max(intervalSecs, 1) * 1000L;
		return nowMs - lastCheckMs >= intervalMs - JITTER_ALLOWANCE_MS;
	}

	synchronized void markChecked(long nowMs) {
		checkedOnce = true;
		lastCheckMs = nowMs;
	}
}
