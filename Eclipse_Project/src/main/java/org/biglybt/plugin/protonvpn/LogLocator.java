package org.biglybt.plugin.protonvpn;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Works out which Proton VPN log file to read (docs/DESIGN.md §4.4).
 *
 * <ol>
 * <li>A path configured by the user always wins.</li>
 * <li>Otherwise, OS-specific candidates are tried. Only the Windows location is verified;
 *     the Linux and macOS locations are best guesses.</li>
 * </ol>
 * If the expected file is missing, the newest rotated sibling (e.g. {@code client-logs.1.txt})
 * is used instead.
 */
final class LogLocator {

	/** How long an auto-detected path found by searching directories is reused before searching again. */
	static final long DISCOVERY_CACHE_MS = 10 * 60_000;

	/** Directory depth searched below each non-Windows candidate directory. */
	static final int SEARCH_DEPTH = 4;

	enum Os { WINDOWS, MAC, LINUX_OR_OTHER }

	/** Result of resolving the log path. */
	static final class Resolution {
		/** The file to read, or null if none was found. */
		final Path path;
		/** True if the path was auto-detected rather than configured. */
		final boolean auto;
		/** Short description for status/logging. */
		final String description;

		Resolution(Path path, boolean auto, String description) {
			this.path = path;
			this.auto = auto;
			this.description = description;
		}
	}

	private final Os os;
	private final Map<String, String> env;
	private final String userHome;

	private Path cachedDiscovery;
	private long cachedDiscoveryAtMs;

	LogLocator(String osName, Map<String, String> env, String userHome) {
		this.os = detectOs(osName);
		this.env = env;
		this.userHome = userHome;
	}

	static LogLocator forCurrentSystem() {
		return new LogLocator(System.getProperty("os.name", ""), System.getenv(), System.getProperty("user.home", ""));
	}

	static Os detectOs(String osName) {
		String n = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
		if (n.startsWith("windows")) return Os.WINDOWS;
		if (n.startsWith("mac") || n.startsWith("darwin")) return Os.MAC;
		return Os.LINUX_OR_OTHER;
	}

	Os os() {
		return os;
	}

	/** The Windows client's documented log file, or null if %LOCALAPPDATA% is not set. */
	Path windowsDefault() {
		String localAppData = env.get("LOCALAPPDATA");
		if (isBlank(localAppData)) return null;
		return Paths.get(localAppData, "Proton", "Proton VPN", "Logs", "client-logs.txt");
	}

	/**
	 * Directories to search for a log on non-Windows systems. Unverified guesses (see the
	 * design doc); the parser may not understand those logs even if one is found.
	 */
	List<Path> searchDirectories() {
		List<Path> dirs = new ArrayList<>();
		if (isBlank(userHome)) return dirs;
		Path home = Paths.get(userHome);
		if (os == Os.MAC) {
			dirs.add(home.resolve("Library").resolve("Logs"));
			dirs.add(home.resolve("Library").resolve("Application Support"));
		} else if (os == Os.LINUX_OR_OTHER) {
			String cache = env.get("XDG_CACHE_HOME");
			String state = env.get("XDG_STATE_HOME");
			dirs.add((isBlank(cache) ? home.resolve(".cache") : Paths.get(cache)));
			dirs.add((isBlank(state) ? home.resolve(".local").resolve("state") : Paths.get(state)));
		}
		return dirs;
	}

	/**
	 * Resolves the log file to read.
	 * @param configured the user's configured path; blank means auto-detect
	 * @param nowMs current time, for the discovery cache
	 */
	synchronized Resolution resolve(String configured, long nowMs) {
		if (!isBlank(configured)) {
			Path p;
			try {
				p = Paths.get(configured.trim());
			} catch (InvalidPathException e) {
				return new Resolution(null, false, "Configured log path is not valid: " + configured);
			}
			Path actual = existingOrRotated(p);
			if (actual == null) {
				return new Resolution(null, false, "Configured log file not found: " + p);
			}
			return new Resolution(actual, false, actual.equals(p) ? actual.toString() : actual + " (rotated)");
		}

		if (os == Os.WINDOWS) {
			Path p = windowsDefault();
			if (p == null) {
				return new Resolution(null, true, "Cannot auto-detect: %LOCALAPPDATA% is not set; set the log path manually");
			}
			Path actual = existingOrRotated(p);
			if (actual == null) {
				return new Resolution(null, true, "Proton VPN log not found at " + p + "; set the log path manually");
			}
			return new Resolution(actual, true, actual + " (auto-detected)");
		}

		if (cachedDiscovery != null && nowMs - cachedDiscoveryAtMs < DISCOVERY_CACHE_MS
				&& Files.isRegularFile(cachedDiscovery)) {
			return new Resolution(cachedDiscovery, true, cachedDiscovery + " (auto-detected, unverified OS)");
		}
		Path found = null;
		for (Path dir : searchDirectories()) {
			Path candidate = newestProtonLog(dir);
			if (candidate != null && (found == null || lastModified(candidate) > lastModified(found))) {
				found = candidate;
			}
		}
		cachedDiscovery = found;
		cachedDiscoveryAtMs = nowMs;
		if (found == null) {
			return new Resolution(null, true, "Proton VPN log not found; set the log path manually");
		}
		return new Resolution(found, true, found + " (auto-detected, unverified OS)");
	}

	/** Returns {@code file} if it exists, else its newest rotated sibling, else null. */
	static Path existingOrRotated(Path file) {
		if (Files.isRegularFile(file)) return file;
		return newestRotatedSibling(file);
	}

	/**
	 * For {@code dir/client-logs.txt}, returns the most recently modified regular file in
	 * {@code dir} named {@code client-logs*.txt}, or null.
	 */
	static Path newestRotatedSibling(Path file) {
		Path dir = file.getParent();
		Path name = file.getFileName();
		if (dir == null || name == null || !Files.isDirectory(dir)) return null;
		String fileName = name.toString();
		int dot = fileName.lastIndexOf('.');
		String prefix = dot > 0 ? fileName.substring(0, dot) : fileName;
		String suffix = dot > 0 ? fileName.substring(dot) : "";
		Path newest = null;
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
			for (Path p : ds) {
				String n = p.getFileName().toString();
				if (n.startsWith(prefix) && n.endsWith(suffix) && Files.isRegularFile(p)
						&& (newest == null || lastModified(p) > lastModified(newest))) {
					newest = p;
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		return newest;
	}

	/**
	 * Searches below {@code baseDir} for subdirectories whose name starts with "Proton"
	 * and returns the most recently modified .log/.txt file inside them, or null.
	 */
	static Path newestProtonLog(Path baseDir) {
		if (baseDir == null || !Files.isDirectory(baseDir)) return null;
		List<Path> protonDirs = new ArrayList<>();
		try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir)) {
			for (Path p : ds) {
				if (Files.isDirectory(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("proton")) {
					protonDirs.add(p);
				}
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
		Collections.sort(protonDirs);
		Path newest = null;
		for (Path dir : protonDirs) {
			try (Stream<Path> files = Files.walk(dir, SEARCH_DEPTH)) {
				Path[] candidates = files.filter(Files::isRegularFile).filter(LogLocator::looksLikeLog).toArray(Path[]::new);
				for (Path p : candidates) {
					if (newest == null || lastModified(p) > lastModified(newest)) {
						newest = p;
					}
				}
			} catch (IOException | RuntimeException e) {
				// unreadable directory: skip it
			}
		}
		return newest;
	}

	private static boolean looksLikeLog(Path p) {
		String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
		return n.endsWith(".log") || n.endsWith(".txt");
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException | RuntimeException e) {
			return Long.MIN_VALUE;
		}
	}

	static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}
}
