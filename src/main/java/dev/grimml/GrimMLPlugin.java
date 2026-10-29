package dev.grimml;

import com.github.retrooper.packetevents.PacketEvents;
import dev.grimml.buffer.SessionRegistry;
import dev.grimml.capture.PacketListener;
import dev.grimml.clock.TickClock;
import dev.grimml.commands.GrimMLCommand;
import dev.grimml.config.PluginConfig;
import dev.grimml.fight.FightAssembler;
import dev.grimml.geyser.GeyserHook;
import dev.grimml.grim.GrimFlagHook;
import dev.grimml.listeners.DamageListener;
import dev.grimml.listeners.ValidityListener;
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
    private final TickClock clock = new TickClock();
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
        registry = new SessionRegistry(config, clock);
        try {
            writer = new RecordWriter(this, config);
        } catch (IOException e) {
            getLogger().severe("cannot create recordings directory: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        writer.setVersions(
                Bukkit.getMinecraftVersion(),
                RecordWriter.pluginVersion("GrimAC"),
                RecordWriter.pluginVersion("packetevents")
        );
        writer.setAssembler(new FightAssembler(registry, config, clock, writer));

        diskCap = new DiskCapGuard(this, config);
        diskCap.start();

        geyserHook = new GeyserHook();
        geyserHook.init();

        packetListener = new PacketListener(this, clock);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);

        getServer().getPluginManager().registerEvents(new SessionLifecycle(this, registry, geyserHook), this);
        getServer().getPluginManager().registerEvents(new SprintSneakTracker(registry), this);
        getServer().getPluginManager().registerEvents(new DamageListener(this, clock), this);
        getServer().getPluginManager().registerEvents(new ValidityListener(this), this);
        stateSampler = new StateSampler(this, registry, config, geyserHook, new MsptGuard(this, config), clock);
        stateSampler.start();

        grimHook = new GrimFlagHook(this, clock);
        if (Bukkit.getPluginManager().isPluginEnabled("GrimAC")) {
            grimHook.enable();
        }

        GrimMLCommand cmd = new GrimMLCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,
                event -> event.registrar().register("grimml", "grimml recorder admin", cmd));

        getLogger().info("grimml recorder enabled schema 2");
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
        if (registry != null) {
            registry.clear();
        }
    }

    public PluginConfig config() {
        return config;
    }

    public TickClock clock() {
        return clock;
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
