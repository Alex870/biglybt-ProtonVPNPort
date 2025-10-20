package org.biglybt.plugin.protonvpn;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.UTTimerEvent;
import com.biglybt.pif.utils.UTTimerEventPerformer;

public class PortConfig implements Plugin {
	
	private PluginInterface plugin_interface;
	private LoggerChannel logger;
	private final String msgPropPath = "org.biglybt.plugin.protonvpn.messages.Messages";
	private final String pluginId = "protonport";

	@Override
	public void initialize(PluginInterface _pi) throws PluginException {
		
		init(_pi);
	    configEventLogger();
	    
	    UIManager ui_manager = plugin_interface.getUIManager();
	    BasicPluginConfigModel config_model = ui_manager.createBasicPluginConfigModel(pluginId + ".name");
	    
	    // UI parameters:
	    final BooleanParameter plugin_enable = config_model.addBooleanParameter2(pluginId + ".enable", pluginId + ".enable", true);
	    final BooleanParameter plugin_debug = config_model.addBooleanParameter2(pluginId + ".debug", pluginId + ".debug", false);
	    final IntParameter plugin_debug_level = config_model.addIntParameter2(pluginId + ".debug_level", pluginId + ".debug_level", 1, 1, 5); // debug level 1 (highest) to 5 (lowest)
	    final IntParameter plugin_check_secs = config_model.addIntParameter2(pluginId + ".check_secs", pluginId + ".check_secs", 120, 15, 86400);
	    
	    final String defaultSource = System.getenv("LOCALAPPDATA") + "\\Proton\\Proton VPN\\Logs\\client-logs.txt";
	    final StringParameter plugin_vpn_log_path = config_model.addStringParameter2(pluginId + ".vpn_log_path", pluginId + ".vpn_log_path", defaultSource);
	    final String target = System.getenv("TEMP") + "\\client-logs.txt"; // use system temp directory to make a temporary copy
		
	    final long tickIntervalMSec = 15000; // 15 second tick interval

	    plugin_enable.addEnabledOnSelection(plugin_debug);
	    plugin_enable.addEnabledOnSelection(plugin_check_secs);
	    plugin_enable.addEnabledOnSelection(plugin_vpn_log_path);
	    plugin_debug.addEnabledOnSelection(plugin_debug_level);
	    
	    plugin_interface.getUtilities().createTimer("processor", true).addPeriodicEvent(tickIntervalMSec, new UTTimerEventPerformer() { 
	    	private boolean was_enabled = false;
	    	private int tick_count = 0;

	    	public void perform(UTTimerEvent event) {
	    		tick_count++;
	    		boolean is_enabled = plugin_enable.getValue();
	    		boolean is_debug = plugin_debug.getValue();
	    		boolean isTimeToCheck = false;
	    		int debug_level = plugin_debug_level.getValue(); // debug level 1 (highest) to 5 (lowest)

	    		if (tick_count == 0 || is_enabled != was_enabled) {
	    			if (is_debug && debug_level == 1) logger.log(LoggerChannel.LT_INFORMATION, "Proton Port Auto-Config plugin Enabled state changed to: " + is_enabled);
	    			was_enabled = is_enabled;
	    		}
	    		if (!is_enabled) return;
	    		
	    		// Timer based update
	    		int pluginCheckSecsInt = plugin_check_secs.getValue();
	    		long secsElapsed = tick_count * Math.round(tickIntervalMSec / 1000);
	    		boolean isCheckEvenlyDivisible = pluginCheckSecsInt % (Math.round(tickIntervalMSec / 1000)) == 0;
	    		if (!isCheckEvenlyDivisible) pluginCheckSecsInt = pluginCheckSecsInt / (Math.round(tickIntervalMSec / 1000));
	    		if (secsElapsed % pluginCheckSecsInt == 0) isTimeToCheck = true; // tick_count * (tickIntervalMSec / 1000) > pluginCheckSecsInt
	    		
	    		if (isTimeToCheck) {
	    			Boolean copyResult = copyFile(plugin_vpn_log_path.getValue(), target, is_debug, debug_level, logger);
	    			if (copyResult != null && copyResult) {
	    				String portNumber = getPortFromLogFile(target, is_debug, debug_level, logger);
	    				deleteFile(target, is_debug, debug_level, logger);
	    				if (portNumber != null) { // update BiglyBT with portNumber
	    					int portNumberInt = Integer.parseInt(portNumber);
	    					changePort(portNumberInt, _pi, is_debug, debug_level, logger);
	    				}
	    			}
	    		}
	    	}
	    });
	}

	/** Copies a file from source to target
	 * @param source path and file
	 * @param target path and file
	 * @param isDebug Is logging debug messages enabled?
	 * @param debugLevel 1-5 where 1 is highest/most verbose
	 * @param logger LoggerChannel object reference
	 * @return Null if source does not exist.  False if copy exception.  True if successful. */
	private static Boolean copyFile(String source, String target, boolean isDebug, int debugLevel, LoggerChannel logger) {
        Path sourcePath = Paths.get(source); // e.g. "C:\\source_folder\\source_file.txt"
        Path targetPath = Paths.get(target); // e.g. "C:\\target_folder\\target_file.txt"
        if (Files.exists(sourcePath)) {
            try {
                // Copy the file, replacing it if it already exists at the target
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                if (isDebug && debugLevel == 1) logger.log(LoggerChannel.LT_INFORMATION, "File copied successfully from [" + source + "] to [" + target + "]");
            } catch (IOException e) {
            	if (isDebug) {
            		logger.log(LoggerChannel.LT_ERROR, "Error copying file (" + source + "): " + e.getMessage());
            		logger.log(e);
            	}
                return false;
            }
        } else { 
        	if (isDebug) logger.log(LoggerChannel.LT_WARNING, "File does not exist for copying: " + source);
        	return null;
        }
        return true;
	}
	
	/** Delete file
	 * @param target path and file
	 * @param isDebug Is logging debug messages enabled?
	 * @param debugLevel 1-5 where 1 is highest/most verbose
	 * @param logger LoggerChannel object reference */
	private static void deleteFile(String target, boolean isDebug, int debugLevel, LoggerChannel logger) {
		Path targetPath = Paths.get(target); // e.g. "C:\\target_folder\\target_file.txt"
		try {
			Files.delete(targetPath);
		} catch (IOException e) {
        	if (isDebug) {
        		logger.log(LoggerChannel.LT_ERROR, "Error deleting file (" + target + "): " + e.getMessage());
        		logger.log(e);
        	}
        	return;
		}
		if (isDebug && debugLevel == 1) logger.log(LoggerChannel.LT_INFORMATION, "Temporary log copy deleted: " + target);
	}
	
	/** Retrieve the port number from the most recent entry in the log file.
	 * @param filePath path to the log file
	 * @param isDebug Is logging debug messages enabled?
	 * @param debugLevel 1-5 where 1 is highest/most verbose
	 * @param logger LoggerChannel object reference
	 * @return Port number or null */
	private static String getPortFromLogFile(String filePath, boolean isDebug, int debugLevel, LoggerChannel logger) {
		final String searchTxt = "Port pair ";
		String mostRecentLogEntry = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
            	if (line.contains(searchTxt)) mostRecentLogEntry = line;
            }
        } catch (IOException e) {
        	if (isDebug) {
        		logger.log(LoggerChannel.LT_ERROR, "Error reading BiglyBT log file: " + e.getMessage());
        		logger.log(e);
        	}
        }
        
        String portNumber = null;
        if (mostRecentLogEntry != null) {
        	int startPosition = mostRecentLogEntry.lastIndexOf(searchTxt);
        	if (startPosition >= 0) {
        		startPosition+= searchTxt.length();
        		mostRecentLogEntry = mostRecentLogEntry.substring(startPosition);
        		int endPosition = mostRecentLogEntry.indexOf("->");
        		mostRecentLogEntry = mostRecentLogEntry.substring(0, endPosition);
        		portNumber = mostRecentLogEntry;
        	} else if (isDebug) logger.log(LoggerChannel.LT_ERROR, "Proton VPN Port number wasn't found in log file. (startPosition = -1)");
        } else if (isDebug) logger.log(LoggerChannel.LT_INFORMATION, "Proton VPN Port number wasn't found in log file.");
        
        if (isDebug && debugLevel <= 2) logger.log(LoggerChannel.LT_INFORMATION, "Proton VPN Port number found in BiglyBT log file: " + portNumber);
        return portNumber;
	}
	
	/** Updates settings in: Options -> Connection -> Incoming TCP Listen port and UDP listen port to a new port setting.
	 * @param port New port number for TCP and UDP
	 * @param plugin_interface PluginInterface object reference
	 * @param isDebug Is logging debug messages enabled?
	 * @param debugLevel 1-5 where 1 is highest/most verbose
	 * @param logger LoggerChannel object reference */
	private static void changePort(int port, PluginInterface plugin_interface, boolean isDebug, int debugLevel, LoggerChannel logger) {
		boolean changed = false;
		PluginConfig pluginConfig = plugin_interface.getPluginconfig();
		int coreTCPPort = pluginConfig.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT);
		int coreUDPPort = pluginConfig.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_UDP_PORT);
		if (coreTCPPort != port) {
			pluginConfig.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT, port);
			if (isDebug && debugLevel <= 3) logger.log(LoggerChannel.LT_INFORMATION, "TCP port changed from " + Integer.toString(coreTCPPort) + " to " + Integer.toString(port));
			changed = true;
		}
		if (coreUDPPort != port) {
			pluginConfig.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_UDP_PORT, port);
			if (isDebug && debugLevel <= 3) logger.log(LoggerChannel.LT_INFORMATION, "UDP port changed from " + Integer.toString(coreTCPPort) + " to " + Integer.toString(port));
			changed = true;
		}
		if (!changed) {
			if (isDebug && debugLevel == 1) logger.log(LoggerChannel.LT_INFORMATION, "TCP and UDP port are already the correct port number: " + Integer.toString(port));
		}
	}
	    
	private void init(PluginInterface _pi) {
	    plugin_interface = _pi;
	    logger = plugin_interface.getLogger().getTimeStampedChannel("ProtonVpnPort"); // Config logger
	}
	
	private void configEventLogger() {
	    LocaleUtilities loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
	    loc_utils.integrateLocalisedMessageBundle(msgPropPath);
	    String plugin_name = loc_utils.getLocalisedMessageText(pluginId + ".name");
	    
	    UIManager ui_manager = plugin_interface.getUIManager();
	    final BasicPluginViewModel view_model = ui_manager.createBasicPluginViewModel(plugin_name);
	    view_model.getActivity().setVisible(false);
	    view_model.getProgress().setVisible(false);
	    view_model.setConfigSectionID(pluginId + ".name");	    
	    
	    logger.addListener(new LoggerChannelListener() {
	    	public void messageLogged(int type, String content) {
	    		view_model.getLogArea().appendText(content + "\n");
	    	}

	    	public void messageLogged(String str, Throwable error) {
	    		if (str.length() > 0) view_model.getLogArea().appendText(str + "\n");
	    		StringWriter sw = new StringWriter();
	    		PrintWriter pw = new PrintWriter(sw);
	    		error.printStackTrace(pw);
	    		pw.flush();
	    		view_model.getLogArea().appendText(sw.toString() + "\n");
	    	}
	    });
	}

}
