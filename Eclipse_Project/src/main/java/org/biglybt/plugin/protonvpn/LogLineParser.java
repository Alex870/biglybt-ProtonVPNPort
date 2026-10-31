package org.biglybt.plugin.protonvpn;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the forwarded port from Proton VPN log text. Pure functions with no I/O of
 * their own, so they can be unit-tested against sample lines.
 *
 * <p>The Windows client logs lines containing {@code Port pair <external>-><internal>}.
 * The last such line in the file is the current port.
 */
final class LogLineParser {

	static final String MARKER = "Port pair";

	/** ISO-8601 UTC timestamp at the start of a Windows client log line (after an optional BOM). */
	static final Pattern LINE_TIMESTAMP = Pattern.compile("^\\uFEFF?(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)");

	/** Tolerates whitespace around the numbers and the arrow. */
	static final Pattern PORT_PAIR = Pattern.compile("Port pair\\s*(\\d{1,5})\\s*->\\s*(\\d{1,5})");

	private LogLineParser() {
	}

	/** A parsed {@code Port pair X->Y} entry. Values are as written in the log, not range-checked. */
	static final class PortPair {
		final int external;
		final int internal;
		/** Time the log line was written (epoch ms), or -1 if the line has no timestamp. */
		final long entryTimeMs;

		PortPair(int external, int internal) {
			this(external, internal, -1);
		}

		PortPair(int external, int internal, long entryTimeMs) {
			this.external = external;
			this.internal = internal;
			this.entryTimeMs = entryTimeMs;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof PortPair)) return false;
			PortPair p = (PortPair) o;
			return p.external == external && p.internal == internal;
		}

		@Override
		public int hashCode() {
			return external * 31 + internal;
		}

		@Override
		public String toString() {
			return external + "->" + internal;
		}
	}

	/** Returns the last port pair on the line, or null if the line has none. */
	static PortPair parseLine(String line) {
		if (line == null || !line.contains(MARKER)) {
			return null;
		}
		Matcher m = PORT_PAIR.matcher(line);
		PortPair last = null;
		while (m.find()) {
			last = new PortPair(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
		}
		if (last != null) {
			long t = parseLineTimestamp(line);
			if (t >= 0) last = new PortPair(last.external, last.internal, t);
		}
		return last;
	}

	/** Returns the epoch ms of the line's leading ISO-8601 UTC timestamp, or -1. */
	static long parseLineTimestamp(String line) {
		Matcher m = LINE_TIMESTAMP.matcher(line);
		if (!m.find()) return -1;
		try {
			return Instant.parse(m.group(1)).toEpochMilli();
		} catch (DateTimeParseException e) {
			return -1;
		}
	}

	/** Reads all lines and returns the last port pair found, or null if there is none. */
	static PortPair findLast(BufferedReader reader) throws IOException {
		PortPair last = null;
		String line;
		while ((line = reader.readLine()) != null) {
			PortPair p = parseLine(line);
			if (p != null) {
				last = p;
			}
		}
		return last;
	}
}
