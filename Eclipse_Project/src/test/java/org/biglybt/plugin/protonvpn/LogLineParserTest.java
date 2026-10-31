package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.jupiter.api.Test;

class LogLineParserTest {

	// Real lines from a Proton VPN for Windows client-logs.txt (2026-10-31). The app logs its
	// port-forwarding state about every 10 s and renews the 60 s mapping every 30 s.
	static final String LINE = "2026-10-31T16:47:18.662Z | INFO  | PROCESS.COMM | Received PortForwarding Status "
			+ "'SleepingUntilRefresh' triggered at '10/31/2026 4:46:59 PM', Port pair 49669->49669, expiring in "
			+ "00:01:00 at 10/31/2026 4:47:59 PM | {\"Caller\":\"ClientControllerListener.StartPortForwardingStateListenerAsync:121\"}";
	static final String RENEWAL_LINE = "2026-10-31T16:47:29.609Z | INFO  | PROCESS.COMM | Received PortForwarding Status "
			+ "'PortMappingCommunication' triggered at '10/31/2026 4:47:29 PM', Port pair 49669->49669, expiring in "
			+ "00:01:00 at 10/31/2026 4:47:59 PM | {\"Caller\":\"ClientControllerListener.StartPortForwardingStateListenerAsync:121\"}";
	static final String STATUS_CHANGE_LINE = "2026-10-31T16:47:29.609Z | INFO  | APP | Port forwarding status changed from "
			+ "'SleepingUntilRefresh' to 'PortMappingCommunication'. | {\"Caller\":\"PortForwardingManager.SetStatus:84\"}";
	static final String BOM = "\uFEFF";

	@Test
	void parsesRealWindowsClientLines() {
		assertEquals(new LogLineParser.PortPair(49669, 49669), LogLineParser.parseLine(LINE));
		assertEquals(new LogLineParser.PortPair(49669, 49669), LogLineParser.parseLine(RENEWAL_LINE));
		assertNull(LogLineParser.parseLine(STATUS_CHANGE_LINE));
	}

	@Test
	void realLogWithByteOrderMarkAndNewerPortAtEnd() throws Exception {
		String text = BOM + LINE + "\n" + STATUS_CHANGE_LINE + "\n" + RENEWAL_LINE + "\n"
				+ RENEWAL_LINE.replace("49669->49669", "51002->51002") + "\n" + STATUS_CHANGE_LINE + "\n";
		assertEquals(new LogLineParser.PortPair(51002, 51002), LogLineParser.findLast(new BufferedReader(new StringReader(text))));
	}

	@Test
	void readsTheLinesUtcTimestamp() {
		assertEquals(java.time.Instant.parse("2026-10-31T16:47:18.662Z").toEpochMilli(), LogLineParser.parseLine(LINE).entryTimeMs);
		assertEquals(java.time.Instant.parse("2026-10-31T16:47:18.662Z").toEpochMilli(), LogLineParser.parseLine(BOM + LINE).entryTimeMs);
		assertEquals(-1, LogLineParser.parseLine("Port pair 1->1").entryTimeMs);
		assertEquals(-1, LogLineParser.parseLineTimestamp("2026-13-45T99:00:00Z | Port pair 1->1"));
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
