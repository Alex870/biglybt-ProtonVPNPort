package org.biglybt.plugin.protonvpn;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Turns each NAT-PMP comparison into a user-facing "port check" state, and decides when a
 * disagreement deserves a re-check or an alert (docs/DESIGN.md §4.8).
 *
 * <p>A first disagreement triggers a quick re-check with a fresh read of the log: the
 * likeliest cause is a log that hasn't caught up yet, for example just after a reconnect.
 * If the disagreement is still there on the next check, it's flagged with an alert, once
 * per episode. BiglyBT always stays on the log's port: when the log is stale, the plugin
 * asks the gateway about an old port and gets back a new, non-working mapping, so the
 * NAT-PMP port is not a safe fallback.
 */
final class PortAgreement {

	/** A log entry older than this is called out as possibly stale (the app logs about every 10 s). */
	static final long STALE_ENTRY_MS = 2 * 60_000;

	enum State {
		/** No check has run yet. */
		UNKNOWN,
		/** The NAT-PMP check is turned off. */
		OFF,
		/** The log had no port, so there was nothing to check. */
		NOTHING_TO_CHECK,
		/** The VPN server confirms the log's port. */
		AGREE,
		/** First disagreement: re-checking before flagging it. */
		RECHECKING,
		/** The disagreement has lasted more than one check. */
		DISAGREE,
		/** The VPN server didn't answer. */
		NO_ANSWER
	}

	/** What to do after a check. */
	static final class Decision {
		final State state;
		final String statusText;
		/** Re-check on the next tick, with a fresh read of the log. */
		final boolean recheckSoon;
		/** Alert text to show, or null. */
		final String alert;
		/** Log line to write (always, not verbose), or null. */
		final String logLine;

		Decision(State state, String statusText, boolean recheckSoon, String alert, String logLine) {
			this.state = state;
			this.statusText = statusText;
			this.recheckSoon = recheckSoon;
			this.alert = alert;
			this.logLine = logLine;
		}
	}

	private State state = State.UNKNOWN;
	private long episodeStartMs;
	private int episodeChecks;
	private boolean alerted;

	synchronized State state() {
		return state;
	}

	/** The NAT-PMP check is turned off. */
	synchronized Decision off() {
		return to(State.OFF, "NAT-PMP check is off", null, null, false);
	}

	/** The log had no port to ask the VPN server about. */
	synchronized Decision nothingToCheck() {
		return to(State.NOTHING_TO_CHECK, "Nothing to check: no port in the Proton VPN log", null, null, false);
	}

	/** Records one comparison of the log's port with the VPN server's answer. */
	synchronized Decision record(ComparisonTracker.Outcome outcome, PortResult log, PortResult nat, long nowMs) {
		Integer logPort = log.port();
		switch (outcome) {
		case MATCH: {
			String resolved = null;
			if (state == State.RECHECKING || state == State.DISAGREE) {
				resolved = "Port check: disagreement resolved — the VPN server now confirms port " + logPort
						+ " (after " + episodeChecks + " check" + (episodeChecks == 1 ? "" : "s") + ")";
			}
			return to(State.AGREE, "Agree — the VPN server confirms port " + logPort, null, resolved, false);
		}
		case MISMATCH: {
			Integer natPort = nat.port();
			if (state != State.RECHECKING && state != State.DISAGREE) {
				episodeStartMs = nowMs;
				episodeChecks = 1;
				alerted = false;
				state = State.RECHECKING;
				return new Decision(state, "Disagreement — log " + logPort + ", VPN server " + natPort
						+ " (re-checking)" + entryAge(log, nowMs), true, null, null);
			}
			episodeChecks++;
			state = State.DISAGREE;
			String text = "Disagreement since " + time(episodeStartMs) + " (" + episodeChecks + " checks) — log "
					+ logPort + entryAge(log, nowMs) + ", VPN server " + natPort + ". BiglyBT stays on " + logPort;
			String alert = null;
			if (!alerted) {
				alerted = true;
				alert = "Proton VPN port check: the Proton VPN log says port " + logPort + entryAge(log, nowMs)
						+ ", but the VPN server reports " + natPort + ". BiglyBT is staying on " + logPort + "."
						+ (isStale(log, nowMs)
								? " The log entry looks out of date: is Proton VPN still connected, with port forwarding on?"
								: " If incoming connections stop working, reconnect Proton VPN.");
			}
			return new Decision(state, text, false, alert, null);
		}
		case LOG_ONLY:
		default:
			String detail = nat != null && !nat.detail.isEmpty() ? " (" + nat.detail + ")" : "";
			return to(State.NO_ANSWER, "The VPN server didn't answer NAT-PMP" + detail
					+ " — using the log's port " + logPort, null, null, false);
		}
	}

	private Decision to(State next, String statusText, String alert, String logLine, boolean recheck) {
		state = next;
		episodeChecks = 0;
		alerted = false;
		return new Decision(next, statusText, recheck, alert, logLine);
	}

	static boolean isStale(PortResult log, long nowMs) {
		return log.logEntryTimeMs > 0 && nowMs - log.logEntryTimeMs > STALE_ENTRY_MS;
	}

	/** e.g. " (log entry 5 s old)", or "" if the entry has no timestamp. */
	static String entryAge(PortResult log, long nowMs) {
		if (log.logEntryTimeMs <= 0) return "";
		long secs = Math.max(0, (nowMs - log.logEntryTimeMs) / 1000);
		String age = secs < 120 ? secs + " s" : (secs / 60) + " min";
		return " (log entry " + age + " old" + (isStale(log, nowMs) ? ", looks stale" : "") + ")";
	}

	private static String time(long ms) {
		return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
	}
}
