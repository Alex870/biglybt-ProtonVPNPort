package org.biglybt.plugin.protonvpn;

/** Snapshot of what the plugin last did, shown in the config section's Status group. */
final class Status {

	String logPath = "(not checked yet)";
	long lastCheckMs;
	String lastCheckResult = "(not checked yet)";
	Integer logPort;
	int biglyTcp;
	int biglyUdp;
	long lastChangeMs;
	String lastChange = "none";
	long lastErrorMs;
	String lastError = "none";
	String trialSummary = "(no data yet)";
	long copyRetries;
	long copyFailures;

	Status copy() {
		Status s = new Status();
		s.logPath = logPath;
		s.lastCheckMs = lastCheckMs;
		s.lastCheckResult = lastCheckResult;
		s.logPort = logPort;
		s.biglyTcp = biglyTcp;
		s.biglyUdp = biglyUdp;
		s.lastChangeMs = lastChangeMs;
		s.lastChange = lastChange;
		s.lastErrorMs = lastErrorMs;
		s.lastError = lastError;
		s.trialSummary = trialSummary;
		s.copyRetries = copyRetries;
		s.copyFailures = copyFailures;
		return s;
	}

	/** Receives a fresh snapshot after every check. */
	interface Sink {
		void update(Status status);
	}
}
