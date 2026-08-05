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

public class DebugManager {
    private final gofd plugin;
    private final Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> debugCounters = new ConcurrentHashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // 调试配置
    private boolean logToFile = true;
    private int maxLogEntries = 10000;

    public DebugManager(gofd plugin) {
        this.plugin = plugin;
    }

    // 记录调试信息
    public void logDebug(String message) {
        if (!plugin.isDebugMode()) return;

        String timestamp = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now());
        String logMessage = String.format("[DEBUG] %s - %s", timestamp, message);

        // 控制台输出
        plugin.getLogger().info(logMessage);

        // 文件记录
        if (logToFile) {
            logToDebugFile(logMessage);
        }

        // 发送给调试玩家
        sendToDebugPlayers(logMessage);

        // 更新计数器
        updateDebugCounter("total_debug_messages");
    }

    // 记录检测详情
    public void logDetectionDetail(Player player, String checkType, String details) {
        if (!plugin.isDebugMode()) return;

        String message = String.format("检测详情 [%s] %s: %s",
                player.getName(), checkType, details);
        logDebug(message);

        updateDebugCounter("detection_" + checkType);
    }

    // 记录性能数据
    public void logPerformance(String operation, long time) {
        if (!plugin.isDebugMode()) return;

        String message = String.format("性能 [%s]: %dms", operation, time);
        logDebug(message);

        updateDebugCounter("performance_" + operation);
    }

    // 添加调试玩家
    public void addDebugPlayer(Player player) {
        debugPlayers.add(player.getUniqueId());
        logDebug("添加调试玩家: " + player.getName());
    }

    // 移除调试玩家
    public void removeDebugPlayer(Player player) {
        debugPlayers.remove(player.getUniqueId());
        logDebug("移除调试玩家: " + player.getName());
    }

    // 切换调试玩家状态
    public boolean toggleDebugPlayer(Player player) {
        if (debugPlayers.contains(player.getUniqueId())) {
            removeDebugPlayer(player);
            return false;
        } else {
            addDebugPlayer(player);
            return true;
        }
    }

    // 生成调试报告
    public String generateDebugReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 调试报告 ===\n");
        report.append("调试玩家数量: ").append(debugPlayers.size()).append("\n");
        report.append("总调试消息: ").append(debugCounters.getOrDefault("total_debug_messages", 0)).append("\n");

        // 添加各检测类型的计数
        for (Map.Entry<String, Integer> entry : debugCounters.entrySet()) {
            if (entry.getKey().startsWith("detection_")) {
                report.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return report.toString();
    }

    // 清理旧日志
    public void cleanupOldLogs() {
        // 实现日志文件清理
        try {
            File debugFile = new File(plugin.getDataFolder(), "debug.log");
            if (debugFile.exists() && debugFile.length() > 1024 * 1024) { // 1MB
                // 备份并清理日志文件
                backupDebugLog();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("清理调试日志失败: " + e.getMessage());
        }
    }

    // 私有方法
    private void logToDebugFile(String message) {
        plugin.getFileService().append(
                new File(plugin.getDataFolder(), "debug.log").toPath(), message + "\n");
    }

    private void sendToDebugPlayers(String message) {
        for (UUID playerId : debugPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.GRAY + "[DEBUG] " + ChatColor.WHITE + message);
            }
        }
    }

    private void updateDebugCounter(String counter) {
        debugCounters.merge(counter, 1, Integer::sum);
    }

    private void backupDebugLog() {
        try {
            File debugFile = new File(plugin.getDataFolder(), "debug.log");
            File backupFile = new File(plugin.getDataFolder(),
                    "debug_backup_" + System.currentTimeMillis() + ".log");

            Files.move(debugFile.toPath(), backupFile.toPath());

            // 创建新的日志文件
            debugFile.createNewFile();

            logDebug("调试日志已备份: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("备份调试日志失败: " + e.getMessage());
        }
    }

    // Getter 方法
    public Set<UUID> getDebugPlayers() {
        return new HashSet<>(debugPlayers);
    }

    public Map<String, Integer> getDebugCounters() {
        return new HashMap<>(debugCounters);
    }

    public void cleanup(UUID playerId) {
        debugPlayers.remove(playerId);
    }
}
