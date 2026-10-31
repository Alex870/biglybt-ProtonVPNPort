package org.biglybt.plugin.protonvpn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Primary port source: the Proton VPN client log (docs/DESIGN.md §4.3).
 *
 * <p>The log is copied to a uniquely named temp file before it is read, because the
 * copy is fast and we don't know how long Proton VPN holds the log open. The copy is
 * skipped when the file's size and modified time haven't changed since the last read,
 * except that a full re-read is forced every {@link #MAX_CACHE_AGE_MS}: Windows can
 * report stale size/time for a file another process holds open.
 */
final class LogFilePortSource implements PortSource {

	static final String TEMP_PREFIX = "protonport-";
	static final String TEMP_SUFFIX = ".log";

	/** Force a full re-read at least this often, even if size and mtime look unchanged. */
	static final long MAX_CACHE_AGE_MS = 5 * 60_000;

	/** Back-off before each retry of a failed copy (3 retries). */
	static final long[] COPY_RETRY_DELAYS_MS = { 250, 500, 1000 };

	/** Copies a file. Replaceable in tests to simulate lock contention. */
	interface Copier {
		void copy(Path source, Path target) throws IOException;
	}

	/** Pauses between retries. Replaceable in tests. */
	interface Sleeper {
		void sleep(long ms) throws InterruptedException;
	}

	static final Copier FILES_COPY = (s, t) -> Files.copy(s, t, StandardCopyOption.REPLACE_EXISTING);

	private final Supplier<String> configuredPath;
	private final LogLocator locator;
	private final Path tempDir;
	private final Copier copier;
	private final Sleeper sleeper;
	private final LongSupplier clock;
	private final Log log;

	private Path lastPath;
	private long lastMtime = -1;
	private long lastSize = -1;
	private long lastReadAtMs;
	private PortResult lastResult;
	private LogLocator.Resolution lastResolution;

	private long copyRetries;
	private long copyFailures;

	LogFilePortSource(Supplier<String> configuredPath, LogLocator locator, Path tempDir, Copier copier,
			Sleeper sleeper, LongSupplier clock, Log log) {
		this.configuredPath = configuredPath;
		this.locator = locator;
		this.tempDir = tempDir;
		this.copier = copier;
		this.sleeper = sleeper;
		this.clock = clock;
		this.log = log;
	}

	@Override
	public synchronized PortResult query() {
		long now = clock.getAsLong();
		try {
			return doQuery(now);
		} catch (Throwable t) {
			return PortResult.error("Unexpected error reading Proton VPN log: " + t, now,
					lastPath == null ? null : lastPath.toString());
		}
	}

	private PortResult doQuery(long now) {
		LogLocator.Resolution res = locator.resolve(configuredPath.get(), now);
		lastResolution = res;
		if (res.path == null) {
			forget();
			return PortResult.notFound(res.description, now, null, -1);
		}
		Path path = res.path;
		String pathStr = path.toString();

		long mtime;
		long size;
		try {
			mtime = Files.getLastModifiedTime(path).toMillis();
			size = Files.size(path);
		} catch (IOException e) {
			forget();
			return PortResult.error("Cannot read attributes of " + pathStr + ": " + e.getMessage(), now, pathStr);
		}

		if (lastResult != null && path.equals(lastPath) && mtime == lastMtime && size == lastSize
				&& now - lastReadAtMs < MAX_CACHE_AGE_MS) {
			log.verbose("Log unchanged since last read (" + pathStr + "); skipping copy");
			return lastResult.hasPort() ? PortResult.unchanged(lastResult, now) : lastResult;
		}

		long start = System.nanoTime();
		Path temp = null;
		try {
			temp = Files.createTempFile(tempDir, TEMP_PREFIX, TEMP_SUFFIX);
			temp.toFile().deleteOnExit();
			copyWithRetry(path, temp);
			LogLineParser.PortPair pair;
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(temp), lenientUtf8()))) {
				pair = LogLineParser.findLast(reader);
			}
			long elapsedMs = (System.nanoTime() - start) / 1_000_000;
			PortResult result;
			if (pair == null) {
				result = PortResult.notFound("No \"" + LogLineParser.MARKER + "\" entry in " + pathStr, now, pathStr, mtime);
			} else {
				if (pair.external != pair.internal) {
					log.onStateChange("log.pair_differs", pair.toString(), Log.Level.WARNING,
							"Proton VPN log reports different external and internal ports (" + pair
									+ "); using the external port " + pair.external);
				}
				result = PortResult.found(pair.external, "Port pair " + pair, now, elapsedMs, pathStr, mtime)
						.withLogEntryTime(pair.entryTimeMs);
			}
			log.verbose("Read " + size + " bytes from " + pathStr + " in " + elapsedMs + " ms: " + result);
			lastPath = path;
			lastMtime = mtime;
			lastSize = size;
			lastReadAtMs = now;
			lastResult = result;
			return result;
		} catch (IOException e) {
			forget();
			return PortResult.error("Cannot read " + pathStr + ": " + e.getMessage(), now, pathStr);
		} finally {
			if (temp != null) {
				deleteQuietly(temp);
			}
		}
	}

	private void copyWithRetry(Path source, Path target) throws IOException {
		IOException lastError = null;
		for (int attempt = 0; attempt <= COPY_RETRY_DELAYS_MS.length; attempt++) {
			if (attempt > 0) {
				copyRetries++;
				try {
					sleeper.sleep(COPY_RETRY_DELAYS_MS[attempt - 1]);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
			try {
				copier.copy(source, target);
				if (attempt > 0) {
					log.verbose("Log copy succeeded after " + attempt + " retr" + (attempt == 1 ? "y" : "ies"));
				}
				return;
			} catch (IOException e) {
				lastError = e;
				log.verbose("Log copy attempt " + (attempt + 1) + " failed: " + e);
			}
		}
		copyFailures++;
		throw lastError != null ? lastError : new IOException("Copy interrupted");
	}

	private void forget() {
		lastPath = null;
		lastResult = null;
		lastMtime = -1;
		lastSize = -1;
	}

	private void deleteQuietly(Path temp) {
		try {
			Files.deleteIfExists(temp);
		} catch (IOException e) {
			log.onStateChange("log.temp_delete", e.getMessage(), Log.Level.WARNING,
					"Could not delete temporary log copy " + temp + ": " + e.getMessage()
							+ " (it will be retried when BiglyBT exits or restarts)");
		}
	}

	private static CharsetDecoder lenientUtf8() {
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPLACE)
				.onUnmappableCharacter(CodingErrorAction.REPLACE);
	}

	/** Forces the next query to copy and re-read the log even if it looks unchanged. */
	synchronized void invalidateCache() {
		forget();
	}

	/** Number of copy retries since load (a measure of lock contention on the log). */
	synchronized long copyRetries() {
		return copyRetries;
	}

	/** Number of copies that failed after all retries since load. */
	synchronized long copyFailures() {
		return copyFailures;
	}

	/** The most recent path resolution, or null before the first query. */
	synchronized LogLocator.Resolution lastResolution() {
		return lastResolution;
	}

	/**
	 * Deletes temp copies left behind by crashes: files in {@code dir} named
	 * {@code protonport-*.log} last modified more than {@code olderThanMs} ago.
	 * @return number of files deleted
	 */
	static int cleanupStaleTempFiles(Path dir, long olderThanMs, long nowMs) {
		int deleted = 0;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, TEMP_PREFIX + "*" + TEMP_SUFFIX)) {
			for (Path p : ds) {
				try {
					if (Files.isRegularFile(p) && nowMs - Files.getLastModifiedTime(p).toMillis() > olderThanMs) {
						Files.delete(p);
						deleted++;
					}
				} catch (IOException | RuntimeException e) {
					// leave it for next time
				}
			}
		} catch (IOException | RuntimeException e) {
			// temp dir unreadable: nothing to clean
		}
		return deleted;
	}
}
