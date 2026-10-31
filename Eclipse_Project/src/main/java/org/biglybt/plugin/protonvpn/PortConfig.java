package org.biglybt.plugin.protonvpn;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.LongSupplier;

import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.LabelParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.UTTimer;

/**
 * BiglyBT plugin entry point. Wiring only: settings, UI, timer, and the components that
 * do the work (see docs/DESIGN.md §2).
 */
public class PortConfig implements UnloadablePlugin {

	static final String ID = "protonport";
	static final String MESSAGES = "org.biglybt.plugin.protonvpn.messages.Messages";
	/** Versioned: v2 asks about the app's mapping instead of creating a new one (v1, internal port 1). */
	static final String TRIAL_FILE = "natpmp-trial-v2.csv";
	static final long TRIAL_SCHEMA = 2;
	static final long STALE_TEMP_AGE_MS = 60 * 60_000;

	private final LongSupplier clock = System::currentTimeMillis;

	private UTTimer timer;
	private BasicPluginConfigModel configModel;
	private BasicPluginViewModel viewModel;

	@Override
	public void initialize(PluginInterface pi) throws PluginException {
		LocaleUtilities loc = pi.getUtilities().getLocaleUtilities();
		loc.integrateLocalisedMessageBundle(MESSAGES);
		String pluginName = loc.getLocalisedMessageText(ID + ".name");
		final PluginConfig pc = pi.getPluginconfig();
		UIManager ui = pi.getUIManager();

		// --- Log view -------------------------------------------------------------
		LoggerChannel channel = pi.getLogger().getTimeStampedChannel("ProtonVpnPort");
		viewModel = ui.createBasicPluginViewModel(pluginName);
		viewModel.getActivity().setVisible(false);
		viewModel.getProgress().setVisible(false);
		viewModel.setConfigSectionID(ID + ".name");
		final BasicPluginViewModel vm = viewModel;
		channel.addListener(new LoggerChannelListener() {
			@Override
			public void messageLogged(int type, String content) {
				vm.getLogArea().appendText(content + "\n");
			}

			@Override
			public void messageLogged(String str, Throwable error) {
				if (str != null && !str.isEmpty()) vm.getLogArea().appendText(str + "\n");
				StringWriter sw = new StringWriter();
				error.printStackTrace(new PrintWriter(sw));
				vm.getLogArea().appendText(sw + "\n");
			}
		});

		// --- Settings (keys unchanged from 0.0.2 where the meaning is unchanged) ----
		configModel = ui.createBasicPluginConfigModel(ID + ".name");
		final BooleanParameter enable = configModel.addBooleanParameter2(ID + ".enable", ID + ".enable", true);
		final IntParameter checkSecs = configModel.addIntParameter2(ID + ".check_secs", ID + ".check_secs", 60, 15, 86400);
		final StringParameter logPath = configModel.addStringParameter2(ID + ".vpn_log_path", ID + ".vpn_log_path", "");
		// Migration: 0.0.2's "Enable debug logging" carries over as the default for verbose logging.
		final BooleanParameter verbose = configModel.addBooleanParameter2(ID + ".verbose", ID + ".verbose",
				pc.getPluginBooleanParameter(ID + ".debug", false));
		final ActionParameter checkNow = configModel.addActionParameter2(ID + ".check_now", ID + ".check_now.button");

		final BooleanParameter natEnable = configModel.addBooleanParameter2(ID + ".natpmp.enable", ID + ".natpmp.enable", true);
		final StringParameter natGateway = configModel.addStringParameter2(ID + ".natpmp.gateway", ID + ".natpmp.gateway",
				NatPmpPortSource.DEFAULT_GATEWAY);
		final ActionParameter resetStats = configModel.addActionParameter2(ID + ".natpmp.reset_stats", ID + ".natpmp.reset_stats.button");
		configModel.createGroup(ID + ".group.natpmp", natEnable, natGateway, resetStats);

		LabelParameter stPath = configModel.addLabelParameter2(ID + ".status.log_path");
		LabelParameter stCheck = configModel.addLabelParameter2(ID + ".status.last_check");
		LabelParameter stPorts = configModel.addLabelParameter2(ID + ".status.ports");
		LabelParameter stChange = configModel.addLabelParameter2(ID + ".status.last_change");
		LabelParameter stError = configModel.addLabelParameter2(ID + ".status.last_error");
		LabelParameter stTrial = configModel.addLabelParameter2(ID + ".status.trial");
		configModel.createGroup(ID + ".group.status", stPath, stCheck, stPorts, stChange, stError, stTrial);

		enable.addEnabledOnSelection(checkSecs);
		enable.addEnabledOnSelection(logPath);
		enable.addEnabledOnSelection(verbose);
		enable.addEnabledOnSelection(checkNow);
		enable.addEnabledOnSelection(natEnable);
		natEnable.addEnabledOnSelection(natGateway);

		// --- Components -------------------------------------------------------------
		final Log log = new PluginLog(channel, verbose::getValue);

		Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));
		int stale = LogFilePortSource.cleanupStaleTempFiles(tempDir, STALE_TEMP_AGE_MS, clock.getAsLong());
		if (stale > 0) log.info("Removed " + stale + " stale temporary log copies from " + tempDir);

		LogFilePortSource logSource = new LogFilePortSource(logPath::getValue, LogLocator.forCurrentSystem(), tempDir,
				LogFilePortSource.FILES_COPY, Thread::sleep, clock, log);
		NatPmpPortSource natSource = new NatPmpPortSource(natGateway::getValue, new NatPmpClient(), clock);

		PortApplier applier = new PortApplier(new PortApplier.CorePorts() {
			@Override
			public int tcp() {
				return pc.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT);
			}

			@Override
			public int udp() {
				return pc.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_UDP_PORT);
			}

			@Override
			public void setTcp(int port) {
				pc.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT, port);
			}

			@Override
			public void setUdp(int port) {
				pc.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_UDP_PORT, port);
			}
		});

		final ComparisonTracker tracker = new ComparisonTracker(new ComparisonTracker.Store() {
			@Override
			public long get(String key) {
				return pc.getPluginLongParameter(ID + "." + key, 0);
			}

			@Override
			public void set(String key, long value) {
				pc.setPluginParameter(ID + "." + key, value);
			}
		});

		File trialFile = pc.getPluginUserFile(TRIAL_FILE);
		if (trialFile == null) trialFile = new File(System.getProperty("java.io.tmpdir"), "protonport-" + TRIAL_FILE);
		TrialRecorder recorder = new TrialRecorder(trialFile);

		StatusView statusView = new StatusView(stPath, stCheck, stPorts, stChange, stError, stTrial, viewModel.getStatus());

		final PortCheck check = new PortCheck(logSource, natSource, natEnable::getValue, applier, tracker, recorder,
				log, statusView, clock);
		final CheckScheduler scheduler = new CheckScheduler();

		if (tracker.ensureSchema(TRIAL_SCHEMA, clock.getAsLong())) {
			log.info("NAT-PMP trial statistics reset: this version asks about the Proton app's own mapping, "
					+ "so earlier results are not comparable");
		}
		check.refreshTrialSummary();
		log.info("Proton Port Auto-Config " + pi.getPluginVersion() + " loaded (" + LogLocator.detectOs(System.getProperty("os.name")) + "); "
				+ (enable.getValue() ? "enabled" : "disabled") + ", checking every " + checkSecs.getValue() + " s"
				+ (natEnable.getValue() ? "; NAT-PMP trial on (gateway " + natGateway.getValue() + ", results in " + trialFile + ")" : ""));

		// --- Actions ----------------------------------------------------------------
		checkNow.addListener(p -> {
			if (!enable.getValue()) {
				log.warn("Check now ignored: the plugin is disabled");
				return;
			}
			scheduler.markChecked(clock.getAsLong());
			Thread t = new Thread(() -> check.run("manual"), "ProtonVpnPort check");
			t.setDaemon(true);
			t.start();
		});
		resetStats.addListener(p -> {
			tracker.reset(clock.getAsLong());
			log.info("NAT-PMP trial statistics reset");
			check.refreshTrialSummary();
		});
		enable.addListener(p -> log.info("Proton Port Auto-Config " + (enable.getValue() ? "enabled" : "disabled")));

		// --- Timer ------------------------------------------------------------------
		timer = pi.getUtilities().createTimer("ProtonVpnPort", true);
		timer.addPeriodicEvent(CheckScheduler.TICK_MS, event -> {
			try {
				if (!enable.getValue()) return;
				long now = clock.getAsLong();
				if (scheduler.isDue(now, checkSecs.getValue())) {
					scheduler.markChecked(now);
					check.run("timer");
				}
			} catch (Throwable t) {
				log.error("Unexpected error in timer", t);
			}
		});
	}

	@Override
	public void unload() throws PluginException {
		if (timer != null) {
			timer.destroy();
			timer = null;
		}
		if (configModel != null) {
			configModel.destroy();
			configModel = null;
		}
		if (viewModel != null) {
			viewModel.destroy();
			viewModel = null;
		}
	}
}
