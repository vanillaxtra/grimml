package dev.grimml;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.commands.GrimMLCommand;
import dev.grimml.configs.PluginConfig;
import dev.grimml.hooks.*;
import dev.grimml.listeners.StateMetadataListener;
import dev.grimml.record.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class GrimMLPlugin extends JavaPlugin {

    private final PluginConfig config = new PluginConfig();
    private SessionRegistry registry;
    private RecordWriter writer;
    private PacketListener packetListener;
    private StateMetadataListener stateListener;
    private GrimFlagHook grimHook;
    private GeyserHook geyserHook;

    private volatile boolean recordingEnabled;
    private volatile double lastMspt = 0;
    private long lastTickCheck;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        config.load(getConfig());

        recordingEnabled = config.enabled();
        registry = new SessionRegistry(config);
        try {
            writer = new RecordWriter(this, config);
        } catch (IOException e) {
            getLogger().severe("cannot create recordings directory: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        geyserHook = new GeyserHook();
        geyserHook.init();

        packetListener = new PacketListener(this, registry);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        stateListener = new StateMetadataListener(this, registry, config, geyserHook);
        getServer().getPluginManager().registerEvents(stateListener, this);
        stateListener.start();

        grimHook = new GrimFlagHook(this);
        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            grimHook.enable();
        }

        GrimMLCommand cmd = new GrimMLCommand(this);
        getCommand("grimml").setExecutor(cmd);
        getCommand("grimml").setTabCompleter(cmd);

        getLogger().info("grimml recorder enabled");
    }

    @Override
    public void onDisable() {
        recordingEnabled = false;
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (stateListener != null) {
            stateListener.stop();
        }
        if (writer != null) {
            writer.stop();
        }
        registry.clear();
    }

    public PluginConfig config() {
        return config;
    }

    public RecordWriter writer() {
        return writer;
    }

    public SessionRegistry registry() {
        return registry;
    }

    public boolean recordingEnabled() {
        return recordingEnabled && writer != null && !writer.isPaused();
    }

    public String clientBrand(Player player) {
        if (player == null) {
            return "";
        }
        return player.getClientBrandName() == null ? "" : player.getClientBrandName();
    }

    public int clientProtocol(Player player) {
        try {
            var user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            return user == null ? 0 : user.getClientVersion().getProtocolVersion();
        } catch (Throwable t) {
            return 0;
        }
    }

    public void checkMsptPause() {
        if (config.msptPauseThreshold() <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickCheck < 1000L) {
            return;
        }
        lastTickCheck = now;
        double mspt = Bukkit.getAverageTickTime();
        lastMspt = mspt;
        if (mspt > config.msptPauseThreshold()) {
            if (!writer.isPaused()) {
                writer.setPaused(true);
                getLogger().warning("mspt " + mspt + " over threshold " + config.msptPauseThreshold() + "; pausing recorder");
            }
        } else if (mspt < config.msptPauseThreshold() * 0.8) {
            if (writer.isPaused()) {
                writer.setPaused(false);
                getLogger().info("mspt recovered; resuming recorder");
            }
        }
    }
}
