package org.biglybt.plugin.protonvpn;

import java.io.IOException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * One check cycle: read the log (source of truth) and apply its port, then, if the trial
 * is enabled, query NAT-PMP and record how it compares. Never throws.
 */
final class PortCheck {

	private final LogFilePortSource logSource;
	private final NatPmpProbe natPmp;
	private final BooleanSupplier natPmpEnabled;
	private final PortApplier applier;
	private final ComparisonTracker tracker;
	private final TrialRecorder recorder;
	private final Log log;
	private final Status.Sink statusSink;
	private final LongSupplier clock;

	private final Status status = new Status();

	PortCheck(LogFilePortSource logSource, NatPmpProbe natPmp, BooleanSupplier natPmpEnabled,
			PortApplier applier, ComparisonTracker tracker, TrialRecorder recorder, Log log,
			Status.Sink statusSink, LongSupplier clock) {
		this.logSource = logSource;
		this.natPmp = natPmp;
		this.natPmpEnabled = natPmpEnabled;
		this.applier = applier;
		this.tracker = tracker;
		this.recorder = recorder;
		this.log = log;
		this.statusSink = statusSink;
		this.clock = clock;
	}

	/** Runs one check. Synchronized so a "Check now" can't overlap a timed check. */
	synchronized void run(String trigger) {
		long now = clock.getAsLong();
		try {
			log.verbose("Check started (" + trigger + ")");
			PortResult logResult = logSource.query();
			handleLogResult(logResult, now);

			if (natPmpEnabled.getAsBoolean()) {
				Integer logPort = logResult.port();
				if (logPort != null && PortApplier.isValid(logPort)) {
					// Ask about (and renew) the app's own mapping; see NatPmpPortSource.
					PortResult natResult = natPmp.query(logPort, logPort);
					handleTrial(logResult, natResult, now);
				} else {
					log.verbose("NAT-PMP trial skipped: no valid port in the log to ask the gateway about");
				}
			}
		} catch (Throwable t) {
			log.error("Unexpected error during port check", t);
			recordError("Unexpected error: " + t, now);
		} finally {
			try {
				status.biglyTcp = applier.currentTcp();
				status.biglyUdp = applier.currentUdp();
			} catch (Throwable t) {
				// leave the previous values
			}
			status.lastCheckMs = now;
			status.copyRetries = logSource.copyRetries();
			status.copyFailures = logSource.copyFailures();
			publish();
		}
	}

	/** Refreshes the trial summary (e.g. after the statistics are reset) and publishes the status. */
	synchronized void refreshTrialSummary() {
		status.trialSummary = tracker.summary();
		publish();
	}

	private void handleLogResult(PortResult r, long now) {
		LogLocator.Resolution res = logSource.lastResolution();
		if (res != null) {
			status.logPath = res.path != null ? res.description : "not found";
		}
		status.logPort = r.port();
		status.lastCheckResult = r.toString();

		switch (r.kind) {
		case FOUND:
		case UNCHANGED:
			log.onStateChange("log", "found", Log.Level.INFO, "Proton VPN port found in log: " + r.port()
					+ " (" + (r.sourcePath != null ? r.sourcePath : "?") + ")");
			PortApplier.Result applied = applier.apply(r.port());
			switch (applied.kind) {
			case CHANGED:
				String msg = applied.describe() + " (source: Proton VPN log)";
				log.info(msg);
				status.lastChange = msg;
				status.lastChangeMs = now;
				log.clearState("apply");
				break;
			case INVALID:
				log.onStateChange("apply", "invalid:" + applied.port, Log.Level.WARNING, applied.describe());
				recordError(applied.describe(), now);
				break;
			default:
				log.clearState("apply");
				log.verbose(applied.describe());
				break;
			}
			break;
		case NOT_FOUND:
			log.onStateChange("log", "not_found:" + r.detail, Log.Level.WARNING, "Proton VPN port not found: " + r.detail);
			break;
		case ERROR:
		default:
			if (log.onStateChange("log", "error:" + r.detail, Log.Level.ERROR, "Error reading Proton VPN log: " + r.detail)) {
				recordError(r.detail, now);
			}
			break;
		}
	}

	private void handleTrial(PortResult logResult, PortResult natResult, long now) {
		ComparisonTracker.Evaluation eval = tracker.record(logResult, natResult, now);
		log.verbose("NAT-PMP trial: log=" + logResult + ", nat-pmp=" + natResult + " -> " + eval.outcome);

		if (eval.changed) {
			String detail = "log=" + str(eval.logPort) + ", NAT-PMP=" + str(eval.natPort)
					+ (natResult.hasPort() || natResult.detail.isEmpty() ? "" : " (" + natResult.detail + ")");
			if (eval.outcome == ComparisonTracker.Outcome.MATCH) {
				log.info("NAT-PMP trial: NAT-PMP agrees with the log (" + detail + ")");
			} else {
				log.warn("NAT-PMP trial: " + eval.outcome + " — " + detail);
			}
		}
		if (eval.tcpUdpSplit) {
			log.onStateChange("trial.split", natResult.tcpPort + "/" + natResult.udpPort, Log.Level.WARNING,
					"NAT-PMP trial: gateway returned different TCP (" + natResult.tcpPort + ") and UDP ("
							+ natResult.udpPort + ") ports");
		}
		try {
			recorder.append(now, logResult, natResult, eval);
		} catch (IOException e) {
			log.onStateChange("trial.csv", e.getMessage(), Log.Level.WARNING,
					"Could not write NAT-PMP trial file " + recorder.file() + ": " + e.getMessage());
		}
		status.trialSummary = tracker.summary();
	}

	private void recordError(String message, long now) {
		status.lastError = message;
		status.lastErrorMs = now;
	}

	private void publish() {
		try {
			statusSink.update(status.copy());
		} catch (Throwable t) {
			log.verbose("Status update failed: " + t);
		}
	}

	private static String str(Integer i) {
		return i == null ? "none" : i.toString();
	}
}
