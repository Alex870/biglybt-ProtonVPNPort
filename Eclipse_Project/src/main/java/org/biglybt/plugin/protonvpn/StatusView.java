package org.biglybt.plugin.protonvpn;

import java.text.SimpleDateFormat;
import java.util.Date;

import com.biglybt.pif.ui.components.UITextField;
import com.biglybt.pif.ui.config.LabelParameter;

/** Shows {@link Status} in the plugin's config section and the log view's status bar. */
final class StatusView implements Status.Sink {

	private final LabelParameter logPath;
	private final LabelParameter lastCheck;
	private final LabelParameter ports;
	private final LabelParameter lastChange;
	private final LabelParameter lastError;
	private final LabelParameter trial;
	private final UITextField viewStatus;

	StatusView(LabelParameter logPath, LabelParameter lastCheck, LabelParameter ports, LabelParameter lastChange,
			LabelParameter lastError, LabelParameter trial, UITextField viewStatus) {
		this.logPath = logPath;
		this.lastCheck = lastCheck;
		this.ports = ports;
		this.lastChange = lastChange;
		this.lastError = lastError;
		this.trial = trial;
		this.viewStatus = viewStatus;
	}

	@Override
	public void update(Status s) {
		logPath.setLabelText("Log file: " + s.logPath);
		lastCheck.setLabelText("Last check: " + time(s.lastCheckMs) + " — " + s.lastCheckResult
				+ (s.copyRetries > 0 || s.copyFailures > 0
						? " (log copy retries: " + s.copyRetries + ", failed copies: " + s.copyFailures + ")" : ""));
		ports.setLabelText("Ports: log " + (s.logPort == null ? "none" : s.logPort)
				+ " · BiglyBT TCP " + s.biglyTcp + " / UDP " + s.biglyUdp);
		lastChange.setLabelText("Last change: " + (s.lastChangeMs > 0 ? time(s.lastChangeMs) + " — " : "") + s.lastChange);
		lastError.setLabelText("Last error: " + (s.lastErrorMs > 0 ? time(s.lastErrorMs) + " — " : "") + s.lastError);
		trial.setLabelText("NAT-PMP trial: " + s.trialSummary);
		if (viewStatus != null) {
			viewStatus.setText(s.logPort == null ? "No Proton VPN port found" : "Proton VPN port " + s.logPort);
		}
	}

	private static String time(long ms) {
		return ms <= 0 ? "never" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ms));
	}
}
