package com.animosity.craftirc;

import java.io.File;
import java.util.logging.Logger;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Server;
import org.bukkit.event.Event.Priority;
import org.bukkit.event.Event;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.java.JavaPlugin;
import com.animosity.craftirc.CraftIRCListener;
import com.animosity.craftirc.Minebot;
import com.animosity.craftirc.Util;

import org.bukkit.util.config.ConfigurationNode;

/**
 * @author Animosity
 * @author ricin
 * @author Protected
 */

public class CraftIRC extends JavaPlugin {
    public static final String NAME = "CraftIRC";
    public static String VERSION;

    protected static final Logger log = Logger.getLogger("Minecraft");

    //Misc class attributes
    private final CraftIRCListener listener = new CraftIRCListener(this);
    private ArrayList<Minebot> instances;
    private boolean debug;
    private Timer holdTimer;
    protected HashMap<HoldType, Boolean> hold;

    //Bots and channels config storage
    private ArrayList<ConfigurationNode> bots;
    private ArrayList<ConfigurationNode> colormap;
    private HashMap<Integer, ArrayList<ConfigurationNode>> channodes;
    private HashMap<Integer, ArrayList<String>> channames;
    
    public CraftIRC(PluginLoader pluginLoader, Server instance, PluginDescriptionFile desc, File folder, File plugin,
            ClassLoader cLoader) {
        super(pluginLoader, instance, desc, folder, plugin, cLoader);
        VERSION = desc.getVersion();
    }

    public void onEnable() {
        try {
        	//Load node lists. Bukkit does it now, hurray!
        	bots = new ArrayList<ConfigurationNode>(getConfiguration().getNodeList("bots", null));
        	colormap = new ArrayList<ConfigurationNode>(getConfiguration().getNodeList("colormap", null));
        	channodes = new HashMap<Integer, ArrayList<ConfigurationNode>>();
        	channames = new HashMap<Integer, ArrayList<String>>();
        	for (int i = 0; i < bots.size(); i++) {
        		channodes.put(i, new ArrayList<ConfigurationNode>(bots.get(i).getNodeList("channels", null)));
        		ArrayList<String> cn = new ArrayList<String>();
        		for (Iterator<ConfigurationNode> it = channodes.get(i).iterator(); it.hasNext(); )
        			cn.add(it.next().getString("name"));
        		channames.put(i, cn);
        	}

            //Event listeners
            getServer().getPluginManager().registerEvent(Event.Type.PLAYER_JOIN, listener, Priority.Monitor, this);
            getServer().getPluginManager().registerEvent(Event.Type.PLAYER_QUIT, listener, Priority.Monitor, this);
            getServer().getPluginManager().registerEvent(Event.Type.PLAYER_COMMAND, listener, Priority.Monitor, this);
            getServer().getPluginManager().registerEvent(Event.Type.PLAYER_CHAT, listener, Priority.Monitor, this);

            //Create bots
            instances = new ArrayList<Minebot>();
            for (int i = 0; i < bots.size(); i++)
                instances.add(new Minebot(this, i).init());

            log.info(NAME + " Enabled.");
            
            //Hold timers
            hold = new HashMap<HoldType, Boolean>();
            holdTimer = new Timer();
			Date now = new Date();
			if (cHold("chat") > 0) {
				hold.put(HoldType.CHAT, true);
				holdTimer.schedule(new RemoveHoldTask(this, HoldType.CHAT), now.getTime() + cHold("chat"));
			} else hold.put(HoldType.CHAT, false);
			if (cHold("joins") > 0) {
				hold.put(HoldType.JOINS, true);
				holdTimer.schedule(new RemoveHoldTask(this, HoldType.JOINS), now.getTime() + cHold("joins"));
			} else hold.put(HoldType.JOINS, false);
			if (cHold("quits") > 0) {
				hold.put(HoldType.QUITS, true);
				holdTimer.schedule(new RemoveHoldTask(this, HoldType.QUITS), now.getTime() + cHold("quits"));
			} else hold.put(HoldType.QUITS, false);
			if (cHold("kicks") > 0) {
				hold.put(HoldType.KICKS, true);
				holdTimer.schedule(new RemoveHoldTask(this, HoldType.KICKS), now.getTime() + cHold("kicks"));
			} else hold.put(HoldType.KICKS, false);
			if (cHold("bans") > 0) {
				hold.put(HoldType.BANS, true);
				holdTimer.schedule(new RemoveHoldTask(this, HoldType.BANS), now.getTime() + cHold("bans"));
			} else hold.put(HoldType.BANS, false);

            setDebug(cDebug());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void onDisable() {

    	holdTimer.cancel();
    	
        //Disconnect bots
        for (int i = 0; i < bots.size(); i++)
            instances.get(i).disconnect();

        log.info(NAME + " Disabled.");
    }

    
    public HashMap<String,String> initFormatParams() {
        HashMap<String,String> formatParams = new HashMap<String,String>();
        formatParams.put("player", "");
        formatParams.put("message", "");
        formatParams.put("server", "");
        formatParams.put("nickname", "");
        formatParams.put("moderator", "");
        formatParams.put("channel", "");
        
        return formatParams;
        
    }
    
    public void sendMessage(Minebot source, HashMap<String,String> formatParams, String tag, String event) {
        for (int i = 0; i < bots.size(); i++) {
            ArrayList<String> chans = cBotChannels(i);
            Iterator<String> it = chans.iterator();
            while (it.hasNext()) {
                String chan = it.next();
                // Intra-relay between bots -- currently not formatted!
                if (source == null || instances.get(i) != source) { instances.get(i).sendMessage(chan, formatParams.get("message")); }
                // Send to all bots, channels with event enabled
                if ((tag == null || cChanCheckTag(tag, i, chan)) && (event == null || cEvents(event, i, chan))) {
                    //String message = this.cFormatting(event, i, formatParams.get("channel"));
                    
                    // Need to determine which formatter to use... please find a better way!
                    String message = (event.substring(0,event.lastIndexOf(".")).equalsIgnoreCase("game-to-irc")) 
                        ? this.formatGameToIRC(this.cFormatting(event, i, formatParams.get("channel")), formatParams)
                        : this.formatIRCToGame(this.cFormatting(event, i, formatParams.get("channel")), formatParams);
                    instances.get(i).sendMessage(chan, message);
                }
            }
        }
    }
    
    public String formatGameToIRC(String formatStyle, HashMap<String,String> params) {
        String formattedMsg = formatStyle;
        formattedMsg = formattedMsg.replaceAll("%player%", params.get("player"));
        formattedMsg = formattedMsg.replaceAll("%moderator%", params.get("moderator"));
        formattedMsg = formattedMsg.replaceAll("%message%", params.get("message"));
        return formattedMsg;
    }
    

    public String formatIRCToGame(String formatStyle, HashMap<String,String> params) {
        String formattedMsg = formatStyle;
        formattedMsg = formattedMsg.replaceAll("%server%", params.get("server"));
        formattedMsg = formattedMsg.replaceAll("%channel%", params.get("channel"));
        formattedMsg = formattedMsg.replaceAll("%nickname%", params.get("nickname"));
        formattedMsg = formattedMsg.replaceAll("%message%", params.get("message"));
        return formattedMsg;
    }

    public ArrayList<String> ircUserLists(String tag) {
        ArrayList<String> result = new ArrayList<String>();
        if (tag == null)
            return result;
        for (int i = 0; i < bots.size(); i++) {
            ArrayList<String> chans = cBotChannels(i);
            Iterator<String> it = chans.iterator();
            while (it.hasNext()) {
                String chan = it.next();
                if (cChanCheckTag(tag, i, chan))
                    result.add(Util.getIrcUserList(instances.get(i), chan));
            }
        }
        return result;
    }

    public void noticeAdmins(String message) {
        for (int i = 0; i < bots.size(); i++) {
            ArrayList<String> chans = cBotChannels(i);
            Iterator<String> it = chans.iterator();
            while (it.hasNext()) {
                String chan = it.next();
                if (cChanAdmin(i, chan))
                    instances.get(i).sendNotice(chan, message);
            }
        }
    }

    public void setDebug(boolean d) {
        debug = d;

        for (int i = 0; i < bots.size(); i++)
            instances.get(i).setVerbose(d);

        log.info(NAME + " DEBUG [" + (d ? "ON" : "OFF") + "]");
    }

    public boolean isDebug() {
        return debug;
    }

    private ConfigurationNode getChanNode(int bot, String channel) {
        ArrayList<ConfigurationNode> botChans = channodes.get(bot);
        for (Iterator<ConfigurationNode> it = botChans.iterator(); it.hasNext();) {
            ConfigurationNode chan = it.next();
            if (chan.getString("name").equalsIgnoreCase(channel))
                return chan;
        }
        return null;
    }

    public boolean cDebug() {
        return getConfiguration().getBoolean("settings.debug", false);
    }

    public String cAdminsCmd() {
        return getConfiguration().getString("settings.admins-cmd", "/admins!");
    }

    public ArrayList<String> cConsoleCommands() {
        return new ArrayList<String>(getConfiguration().getStringList("settings.console-commands", null));
    }

    public ArrayList<String> cIgnoredPrefixes(String source) {
        return new ArrayList<String>(getConfiguration().getStringList("settings.ignored-prefixes." + source, null));
    }

    public int cHold(String eventType) {
        return getConfiguration().getInt("settings.hold-after-enable." + eventType, 0);
    }

    public String cFormatting(String eventType, int bot, String channel) {
        eventType = (eventType.equals("game-to-irc.all-chat") ? "formatting.chat" : eventType);
        ConfigurationNode source = getChanNode(bot, channel);
        if (source == null || source.getString("formatting." + eventType) == null)
            source = bots.get(bot);
        if (source == null || source.getString("formatting." + eventType) == null)
            return getConfiguration().getString("settings.formatting." + eventType, "MESSAGE FORMATTING MISSING");
        else
            return source.getString("formatting." + eventType);
    }

    public boolean cEvents(String eventType, int bot, String channel) {
        ConfigurationNode source = null;
        boolean def = (eventType.equalsIgnoreCase("game-to-irc.all-chat")
                || eventType.equalsIgnoreCase("irc-to-game.all-chat") ? false : true);
        if (channel != null)
            source = getChanNode(bot, channel);
        if ((source == null || source.getProperty("events." + eventType) == null) && bot > -1)
            source = bots.get(bot);
        if (source == null || source.getProperty("events." + eventType) == null)
            return getConfiguration().getBoolean("settings.events." + eventType, def);
        else
            return source.getBoolean("events." + eventType, false);
    }

    public int cColorIrcFromGame(String game) {
        ConfigurationNode color;
        Iterator<ConfigurationNode> it = colormap.iterator();
        while (it.hasNext()) {
            color = it.next();
            if (color.getString("game").equals(game))
                return color.getInt("irc", cColorIrcFromName("foreground"));
        }
        return cColorIrcFromName("foreground");
    }

    public int cColorIrcFromName(String name) {
        ConfigurationNode color;
        Iterator<ConfigurationNode> it = colormap.iterator();
        while (it.hasNext()) {
            color = it.next();
            if (color.getString("name").equalsIgnoreCase(name) && color.getProperty("irc") != null)
                return color.getInt("irc", 1);
        }
        if (name.equalsIgnoreCase("foreground"))
            return 1;
        else
            return cColorIrcFromName("foreground");
    }

    public String cColorGameFromIrc(int irc) {
        ConfigurationNode color;
        Iterator<ConfigurationNode> it = colormap.iterator();
        while (it.hasNext()) {
            color = it.next();
            if (color.getInt("irc", -1) == irc)
                return color.getString("game", cColorGameFromName("foreground"));
        }
        return cColorGameFromName("foreground");
    }

    public String cColorGameFromName(String name) {
        ConfigurationNode color;
        Iterator<ConfigurationNode> it = colormap.iterator();
        while (it.hasNext()) {
            color = it.next();
            if (color.getString("name").equalsIgnoreCase(name) && color.getProperty("game") != null)
                return color.getString("game", "�f");
        }
        if (name.equalsIgnoreCase("foreground"))
            return "�f";
        else
            return cColorGameFromName("foreground");
    }

    public ArrayList<String> cBotChannels(int bot) {
        return channames.get(bot);
    }

    public String cBotNickname(int bot) {
        return bots.get(bot).getString("nickname", "CraftIRCbot");
    }

    public String cBotServer(int bot) {
        return bots.get(bot).getString("server", "irc.esper.net");
    }

    public int cBotPort(int bot) {
        return bots.get(bot).getInt("port", 6667);
    }

    public String cBotLogin(int bot) {
        return bots.get(bot).getString("userident", "");
    }

    public String cBotPassword(int bot) {
        return bots.get(bot).getString("serverpass", "");
    }

    public boolean cBotSsl(int bot) {
        return bots.get(bot).getBoolean("ssl", false);
    }

    public int cBotTimeout(int bot) {
        return bots.get(bot).getInt("timeout", 5000);
    }

    public int cBotMessageDelay(int bot) {
        return bots.get(bot).getInt("message-delay", 1000);
    }

    public String cCommandPrefix(int bot) {
        return bots.get(bot).getString("command-prefix", getConfiguration().getString("settings.command-prefix", "."));
    }

    public ArrayList<String> cBotAdminPrefixes(int bot) {
        return new ArrayList<String>(bots.get(bot).getStringList("admin-prefixes", null));
    }

    public ArrayList<String> cBotIgnoredUsers(int bot) {
        return new ArrayList<String>(bots.get(bot).getStringList("ignored-users", null));
    }

    public String cBotAuthMethod(int bot) {
        return bots.get(bot).getString("auth.method", "nickserv");
    }

    public String cBotAuthUsername(int bot) {
        return bots.get(bot).getString("auth.username", "");
    }

    public String cBotAuthPassword(int bot) {
        return bots.get(bot).getString("auth.password", "");
    }

    public ArrayList<String> cBotOnConnect(int bot) {
        return new ArrayList<String>(bots.get(bot).getStringList("on-connect", null));
    }

    public String cChanName(int bot, String channel) {
        return getChanNode(bot, channel).getString("name", "#changeme");
    }

    public String cChanPassword(int bot, String channel) {
        return getChanNode(bot, channel).getString("password", "");
    }

    public boolean cChanAdmin(int bot, String channel) {
        return getChanNode(bot, channel).getBoolean("admin", false);
    }

    public ArrayList<String> cChanOnJoin(int bot, String channel) {
        return new ArrayList<String>(getChanNode(bot, channel).getStringList("on-join", null));
    }

    public boolean cChanChatColors(int bot, String channel) {
        return getChanNode(bot, channel).getBoolean("chat-colors", true);
    }

    public boolean cChanNameColors(int bot, String channel) {
        return getChanNode(bot, channel).getBoolean("name-colors", true);
    }

    public boolean cChanCheckTag(String tag, int bot, String channel) {
        if (tag == null || tag.equals(""))
            return false;
        if (getConfiguration().getString("settings.tag", "all").equalsIgnoreCase(tag))
            return true;
        if (bots.get(bot).getString("tag", "").equalsIgnoreCase(tag))
            return true;
        if (getChanNode(bot, channel).getString("tag", "").equalsIgnoreCase(tag))
            return true;
        return false;
    }
    
    protected enum HoldType {
    	CHAT, JOINS, QUITS, KICKS, BANS
    }
    
	protected class RemoveHoldTask extends TimerTask {
		private CraftIRC plugin;
		private HoldType ht;
		protected RemoveHoldTask(CraftIRC plugin, HoldType ht) {
			super();
			this.plugin = plugin;
			this.ht = ht;
		}
		public void run() {
			this.plugin.hold.put(ht, false);
		}
	}
	
	public boolean isHeld(HoldType ht) {
		return hold.get(ht);
	}

}
