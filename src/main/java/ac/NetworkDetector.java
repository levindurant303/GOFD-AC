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

public class NetworkDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastPacketTime = new HashMap<>();
    private final HashMap<UUID, Integer> packetCount = new HashMap<>();
    private final HashMap<UUID, Long> lastMovementPacket = new HashMap<>();
    private final HashMap<UUID, Integer> movementPacketCount = new HashMap<>();
    private final HashMap<UUID, Set<String>> suspiciousPackets = new HashMap<>();
    private final HashMap<UUID, Long> lastKeepAlive = new HashMap<>();

    // 可疑数据包模式
    private final Set<String> suspiciousPatterns = Set.of(
            "Invalid payload", "Bad packet", "Out of order"
    );

    public NetworkDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        checkPacketFlood(event.getPlayer(), "Movement");
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            checkPacketFlood(player, "Inventory");
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        checkPacketFlood(event.getPlayer(), "Interact");
    }

    // 数据包洪水检测 - 检测异常数据包频率
    public void checkPacketFlood(Player player, String packetType) {
        if (!plugin.isEnablePacketChecks()) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 通用数据包频率检测
        Long lastPacket = lastPacketTime.get(playerId);
        if (lastPacket != null) {
            long timeSinceLastPacket = currentTime - lastPacket;

            if (timeSinceLastPacket < 10) { // 100包/秒
                int count = packetCount.getOrDefault(playerId, 0) + 1;
                packetCount.put(playerId, count);

                if (count > 100) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("packet_flood", 6);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "数据包洪水: " + packetType + " (" + count + " 包/秒)"
                    );
                }
            } else {
                packetCount.put(playerId, 0);
            }
        }

        lastPacketTime.put(playerId, currentTime);

        // 特定类型数据包检测
        if (packetType.equals("Movement")) {
            checkMovementPackets(player);
        } else if (packetType.equals("Inventory")) {
            checkInventoryPackets(player);
        }
    }

    // 可疑数据包检测 - 检测异常数据包内容
    public void checkSuspiciousPacket(Player player, String packetData) {
        if (!plugin.isEnablePacketChecks()) return;

        // 检测可疑数据包内容
        for (String pattern : suspiciousPatterns) {
            if (packetData.contains(pattern)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_packet", 8);
                plugin.getAlertManager().alertStaff(
                        player,
                        "可疑数据包: " + pattern + " - " + packetData.substring(0, Math.min(50, packetData.length()))
                );
                break;
            }
        }

        // 检测数据包注入
        if (isPacketInjection(packetData)) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("packet_injection", 15);
            plugin.getAlertManager().alertStaff(player, "数据包注入嫌疑");
        }

        // 记录可疑数据包
        recordSuspiciousPacket(player, packetData);
    }

    // 移动数据包检测
    private void checkMovementPackets(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        Long lastMovePacket = lastMovementPacket.get(playerId);
        if (lastMovePacket != null) {
            long timeSinceLastMove = currentTime - lastMovePacket;

            if (timeSinceLastMove < 5) { // 200包/秒
                int count = movementPacketCount.getOrDefault(playerId, 0) + 1;
                movementPacketCount.put(playerId, count);

                if (count > 200) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("movement_packet_flood", 5);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "移动数据包洪水: " + count + " 包/秒"
                    );
                }
            } else {
                movementPacketCount.put(playerId, 0);
            }
        }

        lastMovementPacket.put(playerId, currentTime);
    }

    // 库存数据包检测
    private void checkInventoryPackets(Player player) {
        // 检测库存操作数据包频率
        // 实现库存数据包检测逻辑
    }

    // 保持连接检测
    public void checkKeepAlive(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastAlive = lastKeepAlive.get(playerId);

        if (lastAlive != null) {
            long timeSinceLastAlive = currentTime - lastAlive;

            // 检测异常的保持连接间隔
            if (timeSinceLastAlive > 30000) { // 30秒无响应
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("keep_alive_anomaly", 3);
                plugin.getAlertManager().alertStaff(
                        player,
                        "保持连接异常: " + (timeSinceLastAlive / 1000) + " 秒无响应"
                );
            }
        }

        lastKeepAlive.put(playerId, currentTime);
    }

    // 延迟检测
    public void checkLatencyAnomaly(Player player) {
        int ping = getPing(player);

        // 检测异常延迟模式
        if (ping < 1) { // 不可能的低延迟
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("impossible_latency", 10);
            plugin.getAlertManager().alertStaff(player, "不可能的低延迟: " + ping + "ms");
        }

        // 检测延迟波动（可能使用延迟欺骗）
        checkLatencyFluctuation(player, ping);
    }

    // 数据包顺序检测
    public void checkPacketOrder(Player player, String packetType, long sequence) {
        // 检测数据包顺序异常
        // 实现数据包序列检测逻辑
    }

    // 工具方法
    private boolean isPacketInjection(String packetData) {
        // 检测数据包注入特征
        return packetData.contains("nbt") ||
                packetData.contains("entity_metadata") ||
                packetData.length() > 10000; // 过大的数据包
    }

    private void recordSuspiciousPacket(Player player, String packetData) {
        UUID playerId = player.getUniqueId();
        Set<String> packets = suspiciousPackets.computeIfAbsent(playerId, ignored -> new HashSet<>());

        // 只记录前100个字符
        String shortened = packetData.substring(0, Math.min(100, packetData.length()));
        packets.add(shortened);

        if (packets.size() > 10) {
            // 记录到文件以便进一步分析
            logSuspiciousPackets(player, packets);
            packets.clear();
        }

        suspiciousPackets.put(playerId, packets);
    }

    private void logSuspiciousPackets(Player player, Set<String> packets) {
        String timestamp = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now());
        String logEntry = String.format("[%s] %s: %s%n",
                timestamp, player.getName(), String.join(" | ", packets));
        plugin.getFileService().append(
                new File(plugin.getDataFolder(), "suspicious_packets.log").toPath(), logEntry);
    }

    private int getPing(Player player) {
        // 获取玩家延迟
        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            return (int) entityPlayer.getClass().getField("ping").get(entityPlayer);
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkLatencyFluctuation(Player player, int currentPing) {
        // 检测延迟波动模式
        // 实现延迟波动检测逻辑
    }

    public void cleanup(UUID playerId) {
        lastPacketTime.remove(playerId);
        packetCount.remove(playerId);
        lastMovementPacket.remove(playerId);
        movementPacketCount.remove(playerId);
        suspiciousPackets.remove(playerId);
        lastKeepAlive.remove(playerId);
    }
}
