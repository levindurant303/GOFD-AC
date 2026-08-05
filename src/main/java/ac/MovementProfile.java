package ac;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MovementProfile {
    private final LinkedList<Location> locationHistory = new LinkedList<>();
    private final LinkedList<Long> timeHistory = new LinkedList<>();
    private final LinkedList<Double> verticalHistory = new LinkedList<>();
    private int airTicks = 0;
    private long lastUpdate = System.currentTimeMillis();

    public void update(Location newLocation) {
        long currentTime = System.currentTimeMillis();

        // 更新历史记录
        locationHistory.add(newLocation.clone());
        timeHistory.add(currentTime);

        if (locationHistory.size() > 1) {
            double verticalChange = newLocation.getY() - locationHistory.get(locationHistory.size() - 2).getY();
            verticalHistory.add(verticalChange);
        }

        // 限制历史记录大小
        if (locationHistory.size() > 50) {
            locationHistory.removeFirst();
            timeHistory.removeFirst();
            if (verticalHistory.size() > 0) verticalHistory.removeFirst();
        }

        // 更新空中时间
        if (isInAir(newLocation)) {
            airTicks++;
        } else {
            airTicks = 0;
        }

        lastUpdate = currentTime;
    }

    public int getAirTime() {
        return airTicks;
    }

    public double getVerticalConsistency() {
        if (verticalHistory.size() < 5) return 0;

        // 计算垂直移动的一致性
        double mean = verticalHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = verticalHistory.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance, 1.0);
    }

    public double getCurrentSpeed() {
        if (locationHistory.size() < 2) return 0;

        Location current = locationHistory.getLast();
        Location previous = locationHistory.get(locationHistory.size() - 2);
        long timeDiff = timeHistory.getLast() - timeHistory.get(timeHistory.size() - 2);

        if (timeDiff == 0) return 0;

        double distance = current.distance(previous);
        return distance / (timeDiff / 1000.0);
    }

    public double getCurrentAcceleration() {
        if (locationHistory.size() < 3) return 0;

        double speed1 = getSpeedAt(locationHistory.size() - 2);
        double speed2 = getSpeedAt(locationHistory.size() - 1);
        long timeDiff = timeHistory.getLast() - timeHistory.get(timeHistory.size() - 2);

        if (timeDiff == 0) return 0;

        return Math.abs(speed2 - speed1) / (timeDiff / 1000.0);
    }

    public double getTimeConsistency() {
        if (timeHistory.size() < 10) return 0;

        // 计算时间间隔的一致性
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timeHistory.size(); i++) {
            intervals.add(timeHistory.get(i) - timeHistory.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
                .mapToDouble(i -> Math.pow(i - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance / (mean * mean), 1.0);
    }

    public double getAirControlEfficiency() {
        if (locationHistory.size() < 10) return 0;

        // 计算空中移动的效率（正常玩家在空中控制能力有限）
        int airMoves = 0;
        int totalMoves = 0;

        for (int i = 1; i < locationHistory.size(); i++) {
            if (isInAir(locationHistory.get(i))) {
                totalMoves++;
                Location current = locationHistory.get(i);
                Location previous = locationHistory.get(i - 1);
                double distance = current.distance(previous);
                if (distance > 0.1) { // 有显著移动
                    airMoves++;
                }
            }
        }

        if (totalMoves == 0) return 0;
        return (double) airMoves / totalMoves;
    }

    private double getSpeedAt(int index) {
        if (index < 1 || index >= locationHistory.size()) return 0;

        Location current = locationHistory.get(index);
        Location previous = locationHistory.get(index - 1);
        long timeDiff = timeHistory.get(index) - timeHistory.get(index - 1);

        if (timeDiff == 0) return 0;

        double distance = current.distance(previous);
        return distance / (timeDiff / 1000.0);
    }

    private boolean isInAir(Location loc) {
        Location below = loc.clone().subtract(0, 0.1, 0);
        return !below.getBlock().getType().isSolid();
    }
}
