package dev.grimml.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

// paper region schedulers run on the main thread on paper and on the owning region on folia
public final class Tasks {

    public static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private final Plugin plugin;

    public Tasks(Plugin plugin) {
        this.plugin = plugin;
    }

    public ScheduledTask repeatGlobal(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), Math.max(1L, delayTicks), periodTicks);
    }

    public ScheduledTask repeatAsync(Runnable task, long delayMs, long periodMs) {
        return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(), delayMs, periodMs, TimeUnit.MILLISECONDS);
    }

    public void async(Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public void global(Runnable task) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, task);
    }

    public void atChunk(World world, int chunkX, int chunkZ, long delayTicks, Runnable task) {
        Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, t -> task.run(), Math.max(1L, delayTicks));
    }

    // folia needs the entity owner thread for entity state; paper is already on main
    public void forEntity(Entity entity, Runnable task) {
        if (FOLIA) {
            entity.getScheduler().run(plugin, t -> task.run(), null);
        } else {
            task.run();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
