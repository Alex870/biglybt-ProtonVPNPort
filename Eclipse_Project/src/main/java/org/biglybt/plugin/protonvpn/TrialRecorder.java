package org.biglybt.plugin.protonvpn;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

/**
 * Appends one CSV row per comparison to a trial file in the plugin's user directory,
 * giving hard evidence for the v0.3.0 decision (docs/DESIGN.md §4.6). The file is capped
 * at {@link #MAX_BYTES}; when full it is renamed to {@code <name>.1} and a new one started.
 */
final class TrialRecorder {

	static final long MAX_BYTES = 1024 * 1024;
	static final String HEADER = "timestamp_utc,log_kind,log_port,natpmp_kind,natpmp_tcp,natpmp_udp,outcome,tcp_udp_split,natpmp_rtt_ms,"
			+ "natpmp_internal_port,natpmp_lifetime_s,natpmp_epoch_s,log_file_mtime_utc";

	private final File file;

	TrialRecorder(File file) {
		this.file = file;
	}

	File file() {
		return file;
	}

	synchronized void append(long nowMs, PortResult log, PortResult nat, ComparisonTracker.Evaluation eval) throws IOException {
		if (file.exists() && file.length() >= MAX_BYTES) {
			File rolled = new File(file.getPath() + ".1");
			Files.move(file.toPath(), rolled.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		File parent = file.getParentFile();
		if (parent != null && !parent.exists()) parent.mkdirs();
		boolean writeHeader = !file.exists() || file.length() == 0;
		try (Writer w = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
			if (writeHeader) w.write(HEADER + "\n");
			w.write(row(nowMs, log, nat, eval) + "\n");
		}
	}

	static String row(long nowMs, PortResult log, PortResult nat, ComparisonTracker.Evaluation eval) {
		return iso(nowMs)
				+ ',' + log.kind
				+ ',' + str(log.port())
				+ ',' + nat.kind
				+ ',' + str(nat.tcpPort)
				+ ',' + str(nat.udpPort)
				+ ',' + eval.outcome
				+ ',' + eval.tcpUdpSplit
				+ ',' + (nat.hasPort() ? Long.toString(nat.elapsedMs) : "")
				+ ',' + (nat.gatewayInternalPort >= 0 ? Integer.toString(nat.gatewayInternalPort) : "")
				+ ',' + (nat.gatewayLifetimeSecs >= 0 ? Long.toString(nat.gatewayLifetimeSecs) : "")
				+ ',' + (nat.gatewayEpochSecs >= 0 ? Long.toString(nat.gatewayEpochSecs) : "")
				+ ',' + (log.sourceMtimeMs > 0 ? iso(log.sourceMtimeMs) : "");
	}

	private static String str(Integer i) {
		return i == null ? "" : i.toString();
	}

	private static String iso(long ms) {
		SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		f.setTimeZone(TimeZone.getTimeZone("UTC"));
		return f.format(new Date(ms));
	}
}
