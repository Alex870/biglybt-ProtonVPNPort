package org.biglybt.plugin.protonvpn;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Classifies and counts how the NAT-PMP result compares with the log result
 * (docs/DESIGN.md §4.6). Counters are persisted through {@link Store} so they survive
 * restarts. The log remains the source of truth; this class only records.
 */
final class ComparisonTracker {

	enum Outcome {
		/** Log and NAT-PMP report the same port. */
		MATCH,
		/** Both report a port, but they differ. */
		MISMATCH,
		/** Log has a port; NAT-PMP failed. */
		LOG_ONLY,
		/** NAT-PMP has a port; the log has none. */
		NATPMP_ONLY,
		/** Neither has a port. */
		NEITHER
	}

	/** Persistent counter storage. */
	interface Store {
		long get(String key);

		void set(String key, long value);
	}

	static final String KEY_PREFIX = "natpmp.trial.";
	static final String SINCE = "since";
	static final String SPLIT = "tcp_udp_split";
	static final String TRANSIENT = "mismatch_transient";
	static final String PERSISTENT = "mismatch_persistent";
	static final String NATPMP_LED = "natpmp_led";

	/** Result of recording one comparison. */
	static final class Evaluation {
		final Outcome outcome;
		final Integer logPort;
		final Integer natPort;
		/** NAT-PMP returned different TCP and UDP ports. */
		final boolean tcpUdpSplit;
		/** The outcome (or, for mismatches, the pair of ports) differs from the previous check. */
		final boolean changed;
		/** Length of the current run of consecutive mismatches (0 if not a mismatch). */
		final int mismatchStreak;

		Evaluation(Outcome outcome, Integer logPort, Integer natPort, boolean tcpUdpSplit, boolean changed, int mismatchStreak) {
			this.outcome = outcome;
			this.logPort = logPort;
			this.natPort = natPort;
			this.tcpUdpSplit = tcpUdpSplit;
			this.changed = changed;
			this.mismatchStreak = mismatchStreak;
		}
	}

	private final Store store;

	private Outcome previousOutcome;
	private String previousMismatchKey;
	private int mismatchStreak;
	private Integer streakNatPort;

	ComparisonTracker(Store store) {
		this.store = store;
	}

	static Outcome classify(PortResult log, PortResult nat) {
		boolean logHas = log != null && log.hasPort();
		boolean natHas = nat != null && nat.hasPort();
		if (logHas && natHas) {
			return log.port().equals(nat.port()) ? Outcome.MATCH : Outcome.MISMATCH;
		}
		if (logHas) return Outcome.LOG_ONLY;
		if (natHas) return Outcome.NATPMP_ONLY;
		return Outcome.NEITHER;
	}

	synchronized Evaluation record(PortResult log, PortResult nat, long nowMs) {
		if (get(SINCE) == 0) set(SINCE, nowMs);

		Outcome outcome = classify(log, nat);
		Integer logPort = log != null && log.hasPort() ? log.port() : null;
		Integer natPort = nat != null && nat.hasPort() ? nat.port() : null;
		boolean split = nat != null && nat.tcpPort != null && nat.udpPort != null && !nat.tcpPort.equals(nat.udpPort);

		increment(key(outcome));
		if (split) increment(SPLIT);

		if (outcome == Outcome.MISMATCH) {
			mismatchStreak++;
			if (mismatchStreak == 1) {
				streakNatPort = natPort;
			} else if (mismatchStreak == 2) {
				increment(PERSISTENT);
			}
		} else if (mismatchStreak > 0) {
			if (mismatchStreak == 1) increment(TRANSIENT);
			if (logPort != null && logPort.equals(streakNatPort)) increment(NATPMP_LED);
			mismatchStreak = 0;
			streakNatPort = null;
		}

		String mismatchKey = outcome == Outcome.MISMATCH ? logPort + "/" + natPort : null;
		boolean changed = outcome != previousOutcome
				|| (outcome == Outcome.MISMATCH && !mismatchKey.equals(previousMismatchKey));
		previousOutcome = outcome;
		previousMismatchKey = mismatchKey;

		return new Evaluation(outcome, logPort, natPort, split, changed, mismatchStreak);
	}

	synchronized long count(Outcome outcome) {
		return get(key(outcome));
	}

	synchronized long count(String key) {
		return get(key);
	}

	synchronized void reset(long nowMs) {
		for (Outcome o : Outcome.values()) set(key(o), 0);
		set(SPLIT, 0);
		set(TRANSIENT, 0);
		set(PERSISTENT, 0);
		set(NATPMP_LED, 0);
		set(SINCE, nowMs);
		previousOutcome = null;
		previousMismatchKey = null;
		mismatchStreak = 0;
		streakNatPort = null;
	}

	/** One-line summary for the status area, e.g. "412 match / 3 mismatch (...) since 2026-11-02". */
	synchronized String summary() {
		long since = get(SINCE);
		if (since == 0) return "no data yet";
		long total = 0;
		for (Outcome o : Outcome.values()) total += get(key(o));
		StringBuilder sb = new StringBuilder();
		sb.append(get("match")).append(" match / ")
				.append(get("mismatch")).append(" mismatch (")
				.append(get(TRANSIENT)).append(" transient, ")
				.append(get(PERSISTENT)).append(" persistent, ")
				.append(get(NATPMP_LED)).append(" NAT-PMP ahead of log) / ")
				.append(get("log_only")).append(" log-only / ")
				.append(get("natpmp_only")).append(" NAT-PMP-only / ")
				.append(get("neither")).append(" neither");
		if (get(SPLIT) > 0) sb.append(" / ").append(get(SPLIT)).append(" TCP-UDP split");
		sb.append(" — ").append(total).append(" checks since ")
				.append(new SimpleDateFormat("yyyy-MM-dd").format(new Date(since)));
		return sb.toString();
	}

	/** Counter key for an outcome; Locale.ROOT so e.g. a Turkish locale doesn't change "MISMATCH". */
	static String key(Outcome o) {
		return o.name().toLowerCase(Locale.ROOT);
	}

	private long get(String key) {
		return store.get(KEY_PREFIX + key);
	}

	private void set(String key, long value) {
		store.set(KEY_PREFIX + key, value);
	}

	private void increment(String key) {
		set(key, get(key) + 1);
	}
}
