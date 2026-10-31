package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.jupiter.api.Test;

class LogLineParserTest {

	// Synthetic lines modelled on the "Port pair X->Y" text the 0.0.2 parser looked for.
	// TODO: replace/extend with anonymized lines from a real Proton VPN client-logs.txt.
	static final String LINE = "2026-10-31 10:00:00.000 | INFO  | CONN:PORT_FORWARDING | Port pair 51234->51234, expiring in 59 seconds";

	@Test
	void parsesTypicalLine() {
		assertEquals(new LogLineParser.PortPair(51234, 51234), LogLineParser.parseLine(LINE));
	}

	@Test
	void toleratesWhitespaceAroundArrow() {
		assertEquals(new LogLineParser.PortPair(40000, 40001), LogLineParser.parseLine("x Port pair  40000 -> 40001 y"));
		assertEquals(new LogLineParser.PortPair(40000, 40000), LogLineParser.parseLine("Port pair 40000->\t40000"));
	}

	@Test
	void lineWithoutArrowIsIgnoredNotThrown() {
		// 0.0.2 threw StringIndexOutOfBoundsException here (bug B5)
		assertNull(LogLineParser.parseLine("Port pair 51234 expired"));
		assertNull(LogLineParser.parseLine("Port pair "));
	}

	@Test
	void unrelatedAndEmptyLines() {
		assertNull(LogLineParser.parseLine(""));
		assertNull(LogLineParser.parseLine(null));
		assertNull(LogLineParser.parseLine("Connected to server CH#12"));
	}

	@Test
	void lastMatchOnALineWins() {
		assertEquals(new LogLineParser.PortPair(2222, 2222), LogLineParser.parseLine("Port pair 1111->1111 then Port pair 2222->2222"));
	}

	@Test
	void outOfRangeNumbersAreReturnedForTheApplierToReject() {
		assertEquals(new LogLineParser.PortPair(99999, 99999), LogLineParser.parseLine("Port pair 99999->99999"));
		// more than 5 digits does not match at all
		assertNull(LogLineParser.parseLine("Port pair 123456->123456"));
	}

	@Test
	void findLastReturnsMostRecentEntry() throws Exception {
		String text = "start\n" + LINE + "\nnoise\nPort pair 60211->60211, expiring\nPort pair broken\nend\n";
		assertEquals(new LogLineParser.PortPair(60211, 60211), LogLineParser.findLast(new BufferedReader(new StringReader(text))));
	}

	@Test
	void findLastWithNoEntries() throws Exception {
		assertNull(LogLineParser.findLast(new BufferedReader(new StringReader("a\nb\n"))));
		assertNull(LogLineParser.findLast(new BufferedReader(new StringReader(""))));
	}
}
