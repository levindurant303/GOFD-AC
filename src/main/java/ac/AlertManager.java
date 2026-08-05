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

public class AlertManager {
    private final gofd plugin;
    private final String ALERT_PREFIX = "&8[&cGOFDAC&8] &7";
    private final Set<UUID> disabledAlerts = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> alertStatistics = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertTime = new ConcurrentHashMap<>();

    public AlertManager(gofd plugin) {
        this.plugin = plugin;
    }

    public void alertStaff(Player player, String message) {
        if (!Bukkit.isPrimaryThread()) {
            if (!plugin.isEnabled()) {
                return;
            }
            UUID playerId = player.getUniqueId();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(playerId);
                if (current != null && plugin.isEnabled()) {
                    alertStaff(current, message);
                }
            });
            return;
        }

        // 检查警报冷却
        if (isOnCooldown(player)) {
            return;
        }

        // 记录最后警报时间
        lastAlertTime.put(player.getUniqueId(), System.currentTimeMillis());

        // 统计警报类型
        String alertType = extractAlertType(message);
        alertStatistics.merge(alertType, 1, Integer::sum);

        // 原有警报逻辑...
        String alertMessage = ALERT_PREFIX + "&e" + player.getName() + " &7- &f" + message;
        String consoleMessage = ChatColor.stripColor(alertMessage.replace("&", ""));

        // 后台控制台输出
        plugin.getLogger().warning(consoleMessage);

        // 记录到日志文件
        logToFile(player.getName(), message);

        // 发送给有权限的在线管理员
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("gofdac.alerts") &&
                    !staff.equals(player) &&
                    !disabledAlerts.contains(staff.getUniqueId())) {
                staff.sendMessage(ChatColor.translateAlternateColorCodes('&', alertMessage));
            }
        }

        // 记录检测
        plugin.recordDetection(alertType);

        // 检查是否需要惩罚
        PlayerData data = plugin.getPlayerData(player);
        plugin.getPunishmentManager().checkStrictPunishment(player, data);
    }

    private void logToFile(String playerName, String message) {
        String timestamp = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now());
        String logEntry = String.format("[%s] %s: %s%n", timestamp, playerName, message);
        plugin.getFileService().append(
                new File(plugin.getDataFolder(), "alerts.log").toPath(), logEntry);
    }

    public boolean toggleAlerts(Player player) {
        if (disabledAlerts.contains(player.getUniqueId())) {
            disabledAlerts.remove(player.getUniqueId());
            return true;
        } else {
            disabledAlerts.add(player.getUniqueId());
            return false;
        }
    }

    public void cleanup(UUID playerId) {
        disabledAlerts.remove(playerId);
        lastAlertTime.remove(playerId);
    }

    public void logDebug(String message) {
        if (plugin.isDebugMode()) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    // 新增方法：获取警报统计
    public Map<String, Integer> getAlertStatistics() {
        return new HashMap<>(alertStatistics);
    }

    // 新增方法：重置统计
    public void resetStatistics() {
        alertStatistics.clear();
    }

    // 新增方法：检查冷却
    private boolean isOnCooldown(Player player) {
        Long lastAlert = lastAlertTime.get(player.getUniqueId());
        if (lastAlert == null) return false;

        return System.currentTimeMillis() - lastAlert < plugin.getAlertCooldownMillis();
    }

    // 新增方法：提取警报类型
    private String extractAlertType(String message) {
        if (message.contains("飞行")) return "flight";
        if (message.contains("速度")) return "speed";
        if (message.contains("CPS")) return "cps";
        if (message.contains("长臂")) return "reach";
        if (message.contains("穿墙")) return "wall_hit";
        if (message.contains("KillAura")) return "killaura";
        if (message.contains("自瞄")) return "aimbot";
        return "other";
    }

    // 新增方法：生成统计报告
    public String generateStatisticsReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 警报统计 ===\n");

        int totalAlerts = alertStatistics.values().stream().mapToInt(Integer::intValue).sum();
        report.append("总警报数: ").append(totalAlerts).append("\n");

        if (totalAlerts == 0) {
            return report.toString();
        }

        for (Map.Entry<String, Integer> entry : alertStatistics.entrySet()) {
            double percentage = (double) entry.getValue() / totalAlerts * 100;
            report.append(String.format("%s: %d (%.1f%%)%n",
                    entry.getKey(), entry.getValue(), percentage));
        }

        return report.toString();
    }
}
