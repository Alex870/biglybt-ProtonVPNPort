package org.biglybt.plugin.protonvpn;

/**
 * Logging policy for the plugin (see docs/DESIGN.md §4.5).
 * <ul>
 * <li>{@link #error}, {@link #warn} and {@link #info} are always written.</li>
 * <li>{@link #verbose} is written only when the user has enabled verbose logging.</li>
 * <li>{@link #onStateChange} suppresses repeats: a message is written only when the state
 *     recorded for its key differs from the previous one, so a condition that persists
 *     across many checks is reported once.</li>
 * </ul>
 */
interface Log {

	enum Level { INFO, WARNING, ERROR }

	void info(String message);

	void warn(String message);

	void error(String message);

	void error(String message, Throwable error);

	void verbose(String message);

	/** Shows a BiglyBT alert (popup and alert history) and writes it to the log as a warning. */
	void alert(String message);

	/**
	 * Writes {@code message} at {@code level} only if {@code state} differs from the last
	 * state recorded for {@code key}.
	 * @return true if the message was written
	 */
	boolean onStateChange(String key, String state, Level level, String message);

	/** Forgets the state recorded for {@code key}, without logging anything. */
	void clearState(String key);
}
