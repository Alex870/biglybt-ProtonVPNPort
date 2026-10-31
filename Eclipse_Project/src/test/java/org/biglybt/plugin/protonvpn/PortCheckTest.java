package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PortCheckTest {

	@TempDir
	Path tmp;

	Path logFile;
	RecordingLog rlog = new RecordingLog();
	PortApplierTest.FakeCore core = new PortApplierTest.FakeCore(6881, 6881);
	AtomicReference<PortResult> natReply = new AtomicReference<>();
	AtomicInteger natQueries = new AtomicInteger();
	AtomicReference<String> natRequest = new AtomicReference<>();
	AtomicBoolean natEnabled = new AtomicBoolean(true);
	AtomicReference<Status> lastStatus = new AtomicReference<>();
	Map<String, Long> store = new HashMap<>();
	ComparisonTracker tracker;
	PortCheck check;

	@BeforeEach
	void setUp() throws IOException {
		logFile = tmp.resolve("client-logs.txt");
		Path temp = Files.createDirectory(tmp.resolve("temp"));
		LogFilePortSource logSource = new LogFilePortSource(() -> logFile.toString(),
				new LogLocator("Linux", Collections.<String, String>emptyMap(), tmp.toString()), temp,
				LogFilePortSource.FILES_COPY, ms -> { }, () -> 1000L, rlog);
		tracker = new ComparisonTracker(new ComparisonTracker.Store() {
			public long get(String key) {
				Long v = store.get(key);
				return v == null ? 0 : v;
			}

			public void set(String key, long value) {
				store.put(key, value);
			}
		});
		NatPmpProbe nat = (internal, external) -> {
			natQueries.incrementAndGet();
			natRequest.set(internal + "/" + external);
			return natReply.get();
		};
		check = new PortCheck(logSource, nat, natEnabled::get, new PortApplier(core), tracker,
				new TrialRecorder(tmp.resolve("trial.csv").toFile()), rlog, lastStatus::set, () -> 5000L);
	}

	void writeLog(String s) throws IOException {
		Files.write(logFile, s.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void appliesLogPortAndAlwaysLogsTheChange() throws IOException {
		writeLog("Port pair 51234->51234\n");
		natReply.set(PortResult.found(51234, 51234, "", 0, 3));
		check.run("test");
		assertEquals(51234, core.tcp);
		assertEquals(51234, core.udp);
		assertTrue(rlog.messages.stream().anyMatch(m -> m.startsWith("INFO Incoming port changed: TCP 6881 -> 51234")), rlog.messages.toString());
		assertEquals(1, tracker.count(ComparisonTracker.Outcome.MATCH));
		assertEquals("51234/51234", natRequest.get(), "the trial asks about the app's mapping, not a new one");
		Status s = lastStatus.get();
		assertEquals(Integer.valueOf(51234), s.logPort);
		assertEquals(51234, s.biglyTcp);
		assertTrue(Files.exists(tmp.resolve("trial.csv")));
	}

	@Test
	void natPmpNeverChangesPortsEvenWhenItDisagrees() throws IOException {
		writeLog("Port pair 51234->51234\n");
		natReply.set(PortResult.found(60000, 60000, "", 0, 3));
		check.run("test");
		assertEquals(51234, core.tcp);
		assertEquals(1, tracker.count(ComparisonTracker.Outcome.MISMATCH));
		assertEquals(1, rlog.count("WARN NAT-PMP trial: MISMATCH"));
	}

	@Test
	void natPmpNotQueriedWhenTrialDisabled() throws IOException {
		writeLog("Port pair 51234->51234\n");
		natEnabled.set(false);
		check.run("test");
		assertEquals(0, natQueries.get());
		assertEquals(51234, core.tcp);
	}

	@Test
	void missingPortIsWarnedOnceNotEveryCheckAndTrialIsSkipped() {
		natReply.set(PortResult.error("timeout", 0, null));
		check.run("1");
		check.run("2");
		check.run("3");
		assertEquals(1, rlog.count("WARN Proton VPN port not found"));
		assertEquals(6881, core.tcp);
		assertEquals(0, natQueries.get(), "no NAT-PMP request without a port to ask about");
	}

	@Test
	void invalidPortIsRejected() throws IOException {
		writeLog("Port pair 99999->99999\n");
		natReply.set(PortResult.error("timeout", 0, null));
		check.run("test");
		assertEquals(0, natQueries.get());
		assertEquals(6881, core.tcp);
		assertEquals(1, rlog.count("WARN Ignored port 99999"));
		assertTrue(lastStatus.get().lastError.contains("99999"));
	}

	@Test
	void exceptionsAreContained() throws IOException {
		writeLog("Port pair 51234->51234\n");
		natReply.set(null); // the tracker will hit a NullPointerException
		assertDoesNotThrow(() -> check.run("test"));
		assertEquals(51234, core.tcp, "the log result is applied before the trial runs");
		assertEquals(1, rlog.count("ERROR Unexpected error during port check"));
		assertNotNull(lastStatus.get());
	}
}
