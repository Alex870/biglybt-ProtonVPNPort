package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogLocatorTest {

	@TempDir
	Path tmp;

	static Path write(Path p, String text, long mtime) throws IOException {
		Files.createDirectories(p.getParent());
		Files.write(p, text.getBytes(StandardCharsets.UTF_8));
		Files.setLastModifiedTime(p, FileTime.fromMillis(mtime));
		return p;
	}

	@Test
	void detectsOs() {
		assertEquals(LogLocator.Os.WINDOWS, LogLocator.detectOs("Windows 11"));
		assertEquals(LogLocator.Os.MAC, LogLocator.detectOs("Mac OS X"));
		assertEquals(LogLocator.Os.LINUX_OR_OTHER, LogLocator.detectOs("Linux"));
		assertEquals(LogLocator.Os.LINUX_OR_OTHER, LogLocator.detectOs(null));
	}

	@Test
	void windowsDefaultUnderLocalAppData() throws IOException {
		Path log = write(tmp.resolve("Proton").resolve("Proton VPN").resolve("Logs").resolve("client-logs.txt"), "x", 1000);
		Map<String, String> env = new HashMap<>();
		env.put("LOCALAPPDATA", tmp.toString());
		LogLocator.Resolution r = new LogLocator("Windows 10", env, "").resolve("", 0);
		assertEquals(log, r.path);
		assertTrue(r.auto);
	}

	@Test
	void windowsWithoutLocalAppDataDoesNotProduceNullPath() {
		// 0.0.2 built "null\Proton\..." (bug in §4.4)
		LogLocator.Resolution r = new LogLocator("Windows 10", Collections.<String, String>emptyMap(), "").resolve("  ", 0);
		assertNull(r.path);
		assertTrue(r.description.contains("LOCALAPPDATA"));
	}

	@Test
	void configuredPathWinsOverAutoDetect() throws IOException {
		Path auto = write(tmp.resolve("Proton").resolve("Proton VPN").resolve("Logs").resolve("client-logs.txt"), "x", 1000);
		Path mine = write(tmp.resolve("custom.txt"), "y", 1000);
		Map<String, String> env = new HashMap<>();
		env.put("LOCALAPPDATA", tmp.toString());
		LogLocator.Resolution r = new LogLocator("Windows 10", env, "").resolve(" " + mine + " ", 0);
		assertEquals(mine, r.path);
		assertFalse(r.auto);
		assertNotEquals(auto, r.path);
	}

	@Test
	void configuredButMissingIsReported() {
		LogLocator.Resolution r = new LogLocator("Linux", Collections.<String, String>emptyMap(), tmp.toString())
				.resolve(tmp.resolve("nope.txt").toString(), 0);
		assertNull(r.path);
		assertTrue(r.description.startsWith("Configured log file not found"));
	}

	@Test
	void fallsBackToNewestRotatedSibling() throws IOException {
		Path dir = tmp.resolve("Logs");
		write(dir.resolve("client-logs.1.txt"), "old", 1000);
		Path newer = write(dir.resolve("client-logs.2.txt"), "new", 5000);
		write(dir.resolve("other.txt"), "unrelated", 9000);
		assertEquals(newer, LogLocator.existingOrRotated(dir.resolve("client-logs.txt")));
	}

	@Test
	void existingFileIsPreferredOverRotated() throws IOException {
		Path dir = tmp.resolve("Logs");
		Path current = write(dir.resolve("client-logs.txt"), "cur", 1000);
		write(dir.resolve("client-logs.1.txt"), "newer mtime", 5000);
		assertEquals(current, LogLocator.existingOrRotated(current));
	}

	@Test
	void linuxSearchesXdgCacheForProtonLogs() throws IOException {
		Path cache = tmp.resolve("cache");
		write(cache.resolve("Proton").resolve("VPN").resolve("logs").resolve("vpn-app.log"), "x", 1000);
		Path newest = write(cache.resolve("Proton").resolve("VPN").resolve("logs").resolve("vpn-app.1.log"), "x", 2000);
		write(cache.resolve("Other").resolve("thing.log"), "x", 9000);
		Map<String, String> env = new HashMap<>();
		env.put("XDG_CACHE_HOME", cache.toString());
		LogLocator.Resolution r = new LogLocator("Linux", env, tmp.resolve("home").toString()).resolve("", 0);
		assertEquals(newest, r.path);
		assertTrue(r.description.contains("unverified"));
	}

	@Test
	void macSearchesLibraryLogs() throws IOException {
		Path home = tmp.resolve("home");
		Path log = write(home.resolve("Library").resolve("Logs").resolve("ProtonVPN").resolve("app.log"), "x", 1000);
		LogLocator.Resolution r = new LogLocator("Mac OS X", Collections.<String, String>emptyMap(), home.toString()).resolve("", 0);
		assertEquals(log, r.path);
	}

	@Test
	void nothingFoundOnLinux() {
		LogLocator.Resolution r = new LogLocator("Linux", Collections.<String, String>emptyMap(), tmp.toString()).resolve("", 0);
		assertNull(r.path);
		assertTrue(r.description.contains("set the log path manually"));
	}
}
