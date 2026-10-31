package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFilePortSourceTest {

	@TempDir
	Path tmp;

	Path log;
	Path tempDir;
	AtomicLong now = new AtomicLong(1_000_000);
	AtomicInteger copies = new AtomicInteger();
	List<Long> sleeps = new ArrayList<>();
	RecordingLog rlog = new RecordingLog();

	@BeforeEach
	void setUp() throws IOException {
		log = tmp.resolve("client-logs.txt");
		tempDir = Files.createDirectory(tmp.resolve("temp"));
	}

	void writeLog(String text, long mtime) throws IOException {
		Files.write(log, text.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(log, FileTime.fromMillis(mtime));
	}

	LogFilePortSource source(LogFilePortSource.Copier copier) {
		LogFilePortSource.Copier counting = (s, t) -> {
			copies.incrementAndGet();
			copier.copy(s, t);
		};
		return new LogFilePortSource(() -> log.toString(),
				new LogLocator("Linux", Collections.<String, String>emptyMap(), tmp.toString()), tempDir, counting,
				sleeps::add, now::get, rlog);
	}

	long tempFiles() throws IOException {
		long n = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(tempDir)) {
			for (Path p : ds) n++;
		}
		return n;
	}

	@Test
	void readsPortAndCleansUpTempCopy() throws IOException {
		writeLog("a\nPort pair 40000->40000\nb\nPort pair 51234->51234, expiring\n", 5000);
		PortResult r = source(LogFilePortSource.FILES_COPY).query();
		assertEquals(PortResult.Kind.FOUND, r.kind);
		assertEquals(Integer.valueOf(51234), r.tcpPort);
		assertEquals(Integer.valueOf(51234), r.udpPort);
		assertEquals(log.toString(), r.sourcePath);
		assertEquals(5000, r.sourceMtimeMs);
		assertEquals(0, tempFiles(), "temp copy must be deleted");
	}

	@Test
	void unchangedFileIsNotCopiedAgain() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		LogFilePortSource src = source(LogFilePortSource.FILES_COPY);
		assertEquals(PortResult.Kind.FOUND, src.query().kind);
		now.addAndGet(60_000);
		PortResult second = src.query();
		assertEquals(PortResult.Kind.UNCHANGED, second.kind);
		assertEquals(Integer.valueOf(51234), second.port());
		assertEquals(1, copies.get());
	}

	@Test
	void changedFileIsReread() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		LogFilePortSource src = source(LogFilePortSource.FILES_COPY);
		src.query();
		writeLog("Port pair 51234->51234\nPort pair 60000->60000\n", 6000);
		PortResult r = src.query();
		assertEquals(PortResult.Kind.FOUND, r.kind);
		assertEquals(Integer.valueOf(60000), r.port());
		assertEquals(2, copies.get());
	}

	@Test
	void carriesTheLogEntryTimeAndCanBeForcedToReread() throws IOException {
		writeLog("2026-10-31T16:47:18.662Z | INFO | x | Port pair 49669->49669, expiring in 00:01:00\n", 5000);
		LogFilePortSource src = source(LogFilePortSource.FILES_COPY);
		long expected = java.time.Instant.parse("2026-10-31T16:47:18.662Z").toEpochMilli();
		assertEquals(expected, src.query().logEntryTimeMs);
		assertEquals(expected, src.query().logEntryTimeMs, "kept on UNCHANGED results");
		assertEquals(1, copies.get());
		src.invalidateCache();
		assertEquals(PortResult.Kind.FOUND, src.query().kind);
		assertEquals(2, copies.get());
	}

	@Test
	void forcedRereadAfterMaxCacheAge() throws IOException {
		// Windows may report stale size/mtime for a file another process holds open
		writeLog("Port pair 51234->51234\n", 5000);
		LogFilePortSource src = source(LogFilePortSource.FILES_COPY);
		src.query();
		now.addAndGet(LogFilePortSource.MAX_CACHE_AGE_MS);
		assertEquals(PortResult.Kind.FOUND, src.query().kind);
		assertEquals(2, copies.get());
	}

	@Test
	void unchangedFileWithoutPortStaysNotFoundWithoutCopy() throws IOException {
		writeLog("nothing here\n", 5000);
		LogFilePortSource src = source(LogFilePortSource.FILES_COPY);
		assertEquals(PortResult.Kind.NOT_FOUND, src.query().kind);
		assertEquals(PortResult.Kind.NOT_FOUND, src.query().kind);
		assertEquals(1, copies.get());
	}

	@Test
	void retriesCopyOnLockThenSucceeds() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		AtomicInteger failuresLeft = new AtomicInteger(2);
		LogFilePortSource src = source((s, t) -> {
			if (failuresLeft.getAndDecrement() > 0) throw new IOException("The process cannot access the file");
			LogFilePortSource.FILES_COPY.copy(s, t);
		});
		PortResult r = src.query();
		assertEquals(PortResult.Kind.FOUND, r.kind);
		assertEquals(2, src.copyRetries());
		assertEquals(0, src.copyFailures());
		assertEquals(java.util.Arrays.asList(250L, 500L), sleeps);
	}

	@Test
	void reportsErrorAfterAllRetriesAndLeavesNoTempFile() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		LogFilePortSource src = source((s, t) -> {
			throw new IOException("locked");
		});
		PortResult r = src.query();
		assertEquals(PortResult.Kind.ERROR, r.kind);
		assertTrue(r.detail.contains("locked"));
		assertEquals(4, copies.get());
		assertEquals(3, src.copyRetries());
		assertEquals(1, src.copyFailures());
		assertEquals(0, tempFiles());
	}

	@Test
	void missingLogIsNotFound() {
		PortResult r = source(LogFilePortSource.FILES_COPY).query();
		assertEquals(PortResult.Kind.NOT_FOUND, r.kind);
		assertEquals(0, copies.get());
	}

	@Test
	void unexpectedRuntimeExceptionBecomesError() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		PortResult r = source((s, t) -> {
			throw new IllegalStateException("boom");
		}).query();
		assertEquals(PortResult.Kind.ERROR, r.kind);
	}

	@Test
	void differingExternalAndInternalPortsWarnsAndUsesExternal() throws IOException {
		writeLog("Port pair 50000->50001\n", 5000);
		PortResult r = source(LogFilePortSource.FILES_COPY).query();
		assertEquals(Integer.valueOf(50000), r.port());
		assertEquals(1, rlog.count("WARN"));
	}

	@Test
	void invalidUtf8DoesNotBreakParsing() throws IOException {
		byte[] bad = { (byte) 0xff, (byte) 0xfe, 'x', '\n' };
		byte[] good = "Port pair 51234->51234\n".getBytes(StandardCharsets.UTF_8);
		byte[] all = new byte[bad.length + good.length];
		System.arraycopy(bad, 0, all, 0, bad.length);
		System.arraycopy(good, 0, all, bad.length, good.length);
		Files.write(log, all);
		assertEquals(Integer.valueOf(51234), source(LogFilePortSource.FILES_COPY).query().port());
	}

	@Test
	void uniqueTempNamesPerRead() throws IOException {
		writeLog("Port pair 51234->51234\n", 5000);
		List<Path> targets = new ArrayList<>();
		LogFilePortSource src = source((s, t) -> {
			targets.add(t);
			LogFilePortSource.FILES_COPY.copy(s, t);
		});
		src.query();
		writeLog("Port pair 51234->51234\n\n", 6000);
		src.query();
		assertEquals(2, targets.size());
		assertNotEquals(targets.get(0), targets.get(1));
		assertTrue(targets.get(0).getFileName().toString().startsWith(LogFilePortSource.TEMP_PREFIX));
	}

	@Test
	void cleansUpOnlyStaleTempFiles() throws IOException {
		Path stale = Files.createFile(tempDir.resolve("protonport-123.log"));
		Files.setLastModifiedTime(stale, FileTime.fromMillis(0));
		Path fresh = Files.createFile(tempDir.resolve("protonport-456.log"));
		Files.setLastModifiedTime(fresh, FileTime.fromMillis(10_000_000));
		Path other = Files.createFile(tempDir.resolve("unrelated.log"));
		Files.setLastModifiedTime(other, FileTime.fromMillis(0));
		assertEquals(1, LogFilePortSource.cleanupStaleTempFiles(tempDir, 3_600_000, 10_000_000));
		assertFalse(Files.exists(stale));
		assertTrue(Files.exists(fresh));
		assertTrue(Files.exists(other));
	}
}
