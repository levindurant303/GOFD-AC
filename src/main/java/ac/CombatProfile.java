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

public class CombatProfile {
    private final LinkedList<Long> attackTimes = new LinkedList<>();
    private final LinkedList<Double> attackAngles = new LinkedList<>();
    private final LinkedList<UUID> recentTargets = new LinkedList<>();
    private final LinkedList<Long> reactionTimes = new LinkedList<>();
    private long lastAttackTime = 0;
    private double lastAimAngle = 0;

    public void recordAttack() {
        long currentTime = System.currentTimeMillis();
        attackTimes.add(currentTime);

        // 清理旧记录
        while (!attackTimes.isEmpty() && currentTime - attackTimes.getFirst() > 5000) {
            attackTimes.removeFirst();
        }

        lastAttackTime = currentTime;
    }

    public void recordTarget(UUID targetId) {
        recentTargets.add(targetId);
        // 只保留最近10个目标
        while (recentTargets.size() > 10) {
            recentTargets.removeFirst();
        }
    }

    public void recordAimAngle(double angle) {
        attackAngles.add(angle);
        while (attackAngles.size() > 20) {
            attackAngles.removeFirst();
        }
        lastAimAngle = angle;
    }

    public void recordReactionTime(long time) {
        reactionTimes.add(time);
        while (reactionTimes.size() > 10) {
            reactionTimes.removeFirst();
        }
    }

    public double getCurrentCPS() {
        if (attackTimes.isEmpty()) return 0;

        long currentTime = System.currentTimeMillis();
        long oneSecondAgo = currentTime - 1000;

        int count = 0;
        for (Long time : attackTimes) {
            if (time > oneSecondAgo) {
                count++;
            }
        }
        return count;
    }

    public double getClickPatternScore() {
        if (attackTimes.size() < 10) return 0;

        // 计算点击间隔的模式得分
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < attackTimes.size(); i++) {
            intervals.add(attackTimes.get(i) - attackTimes.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        if (mean <= 0.0D) return 0.0D;
        double variance = intervals.stream()
                .mapToDouble(i -> Math.pow(i - mean, 2))
                .average().orElse(0);

        // 方差越小，模式得分越高
        return 1.0 - Math.min(variance / (mean * mean), 1.0);
    }

    public double getAimConsistency() {
        if (attackAngles.size() < 5) return 0;

        // 计算瞄准角度的一致性
        double mean = attackAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = attackAngles.stream()
                .mapToDouble(a -> Math.pow(a - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance / 100.0, 1.0); // 假设最大方差为100
    }

    public int getRecentTargetCount() {
        return new HashSet<>(recentTargets).size();
    }

    public double getAttackAngleVariance() {
        if (attackAngles.size() < 3) return 180.0; // 默认最大值

        double mean = attackAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return attackAngles.stream()
                .mapToDouble(a -> Math.pow(a - mean, 2))
                .average().orElse(0);
    }

    public long getAverageReactionTime() {
        if (reactionTimes.isEmpty()) return 200; // 默认反应时间

        return (long) reactionTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    public double getAttackTimingScore() {
        // 计算攻击时机的完美程度
        // 简化实现
        return getClickPatternScore(); // 重用点击模式得分
    }

    public long getBlockReactionTime() {
        return getAverageReactionTime();
    }
}
