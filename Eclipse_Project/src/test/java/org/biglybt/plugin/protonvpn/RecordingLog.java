package org.biglybt.plugin.protonvpn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Test {@link Log} that records messages; verbose messages are kept separately. */
final class RecordingLog implements Log {

	final List<String> messages = new ArrayList<>();
	final List<String> verbose = new ArrayList<>();
	private final Map<String, String> states = new HashMap<>();

	@Override
	public void info(String message) {
		messages.add("INFO " + message);
	}

	@Override
	public void warn(String message) {
		messages.add("WARN " + message);
	}

	@Override
	public void error(String message) {
		messages.add("ERROR " + message);
	}

	@Override
	public void error(String message, Throwable error) {
		messages.add("ERROR " + message + ": " + error);
	}

	@Override
	public void alert(String message) {
		messages.add("ALERT " + message);
	}

	@Override
	public void verbose(String message) {
		verbose.add(message);
	}

	@Override
	public boolean onStateChange(String key, String state, Level level, String message) {
		if (state.equals(states.put(key, state))) return false;
		messages.add((level == Level.ERROR ? "ERROR " : level == Level.WARNING ? "WARN " : "INFO ") + message);
		return true;
	}

	@Override
	public void clearState(String key) {
		states.remove(key);
	}

	long count(String prefix) {
		return messages.stream().filter(m -> m.startsWith(prefix)).count();
	}
}
