package dev.grimml;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.capture.PacketListener;
import dev.grimml.commands.GrimMLCommand;
import dev.grimml.config.PluginConfig;
import dev.grimml.geyser.GeyserHook;
import dev.grimml.grim.GrimFlagHook;
import dev.grimml.safety.DiskCapGuard;
import dev.grimml.safety.MsptGuard;
import dev.grimml.state.SessionLifecycle;
import dev.grimml.state.SprintSneakTracker;
import dev.grimml.state.StateSampler;
import dev.grimml.writer.RecordWriter;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;

public final class GrimMLPlugin extends JavaPlugin {

    private final PluginConfig config = new PluginConfig();
    private SessionRegistry registry;
    private RecordWriter writer;
    private PacketListener packetListener;
    private StateSampler stateSampler;
    private DiskCapGuard diskCap;
    private GrimFlagHook grimHook;
    private GeyserHook geyserHook;

    private volatile boolean recordingEnabled;

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

        diskCap = new DiskCapGuard(this, config);
        diskCap.start();

        geyserHook = new GeyserHook();
        geyserHook.init();

        packetListener = new PacketListener(this, registry);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        getServer().getPluginManager().registerEvents(new SessionLifecycle(registry, geyserHook), this);
        getServer().getPluginManager().registerEvents(new SprintSneakTracker(registry), this);
        stateSampler = new StateSampler(this, registry, config, geyserHook, new MsptGuard(this, config));
        stateSampler.start();

        grimHook = new GrimFlagHook(this);
        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            grimHook.enable();
        }

        GrimMLCommand cmd = new GrimMLCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("grimml", "grimml recorder admin", cmd));

        getLogger().info("grimml recorder enabled");
    }

    @Override
    public void onDisable() {
        recordingEnabled = false;
        if (packetListener != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (stateSampler != null) {
            stateSampler.stop();
        }
        if (diskCap != null) {
            diskCap.stop();
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
}
