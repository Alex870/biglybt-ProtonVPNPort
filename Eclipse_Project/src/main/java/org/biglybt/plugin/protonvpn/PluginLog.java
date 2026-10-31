package org.biglybt.plugin.protonvpn;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.biglybt.pif.logging.LoggerChannel;

/** {@link Log} backed by a BiglyBT {@link LoggerChannel}. */
final class PluginLog implements Log {

	private final LoggerChannel channel;
	private final BooleanSupplier verboseEnabled;
	private final Map<String, String> lastStates = new HashMap<>();

	PluginLog(LoggerChannel channel, BooleanSupplier verboseEnabled) {
		this.channel = channel;
		this.verboseEnabled = verboseEnabled;
	}

	@Override
	public void info(String message) {
		channel.log(LoggerChannel.LT_INFORMATION, message);
	}

	@Override
	public void warn(String message) {
		channel.log(LoggerChannel.LT_WARNING, message);
	}

	@Override
	public void error(String message) {
		channel.log(LoggerChannel.LT_ERROR, message);
	}

	@Override
	public void error(String message, Throwable error) {
		channel.log(message, error);
	}

	@Override
	public void verbose(String message) {
		if (verboseEnabled.getAsBoolean()) {
			channel.log(LoggerChannel.LT_INFORMATION, "[verbose] " + message);
		}
	}

	@Override
	public synchronized boolean onStateChange(String key, String state, Level level, String message) {
		String previous = lastStates.put(key, state);
		if (state.equals(previous)) {
			return false;
		}
		switch (level) {
		case ERROR:
			error(message);
			break;
		case WARNING:
			warn(message);
			break;
		default:
			info(message);
			break;
		}
		return true;
	}

	@Override
	public synchronized void clearState(String key) {
		lastStates.remove(key);
	}
}
