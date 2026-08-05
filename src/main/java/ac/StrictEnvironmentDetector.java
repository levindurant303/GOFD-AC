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

public class StrictEnvironmentDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastBlockBreak = new HashMap<>();
    private final HashMap<UUID, Integer> fastBreakCount = new HashMap<>();

    // 极严格阈值
    private final long MIN_BREAK_INTERVAL = 200; // 毫秒
    private final int MAX_BLOCKS_PER_SECOND = 3;

    public StrictEnvironmentDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        PlayerData data = plugin.getPlayerData(player);

        checkUltraFastBreak(player, event, data);
        checkXRayStrict(player, event, data);
        checkNukerStrict(player, event, data);
        checkIllegalBreakStrict(player, event, data);
    }

    // 超快速破坏检测
    private void checkUltraFastBreak(Player player, BlockBreakEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastBreak = lastBlockBreak.get(playerId);

        if (lastBreak != null) {
            long timeSinceLastBreak = currentTime - lastBreak;

            if (timeSinceLastBreak < MIN_BREAK_INTERVAL) {
                int count = fastBreakCount.getOrDefault(playerId, 0) + 1;
                fastBreakCount.put(playerId, count);

                if (count > 2) {
                    data.addViolation("ultra_fast_break", 15);
                    plugin.getAlertManager().alertStaff(
                            player, "超快速破坏检测: " + timeSinceLastBreak + "ms 间隔"
                    );
                }
            } else {
                fastBreakCount.put(playerId, 0);
            }
        }

        lastBlockBreak.put(playerId, currentTime);

        // 检测每秒破坏方块数
        checkBlocksPerSecond(player, data);
    }

    // 严格 X-Ray 检测
    private void checkXRayStrict(Player player, BlockBreakEvent event, PlayerData data) {
        Block block = event.getBlock();

        if (isValuableBlock(block.getType())) {
            // 检测挖掘路径是否直接朝向珍贵方块
            Location playerLoc = player.getLocation();
            Location blockLoc = block.getLocation();

            Vector toBlock = blockLoc.toVector().subtract(playerLoc.toVector()).normalize();
            Vector viewDir = playerLoc.getDirection();

            double dot = toBlock.dot(viewDir);

            if (dot > 0.98) { // 几乎直接朝向
                data.addViolation("xray_strict", 20);
                plugin.getAlertManager().alertStaff(
                        player, "严格 X-Ray 检测: 直接挖掘 " + block.getType()
                );
            }
        }
    }

    // 严格 Nuker 检测
    private void checkNukerStrict(Player player, BlockBreakEvent event, PlayerData data) {
        // 检测同时破坏多个方块
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 这里需要记录破坏的方块位置和时间
        // 简化实现：检测破坏频率

        int breakCount = data.getViolationLevel("nuker_count") + 1;
        data.addViolation("nuker_count", 1);

        if (breakCount > MAX_BLOCKS_PER_SECOND) {
            data.addViolation("nuker_strict", 25);
            plugin.getAlertManager().alertStaff(
                    player, "严格 Nuker 检测: " + breakCount + " 方块/秒"
            );
            data.addViolation("nuker_count", -breakCount); // 重置计数
        }
    }

    // 严格非法破坏检测
    private void checkIllegalBreakStrict(Player player, BlockBreakEvent event, PlayerData data) {
        Block block = event.getBlock();

        // 检测破坏不可破坏的方块
        if (isUnbreakableBlock(block.getType())) {
            data.addViolation("illegal_break_strict", 30);
            plugin.getAlertManager().alertStaff(
                    player, "严格非法破坏: " + block.getType()
            );
            event.setCancelled(true);
        }

        // 检测在保护区域破坏
        if (isInProtectedArea(block.getLocation())) {
            data.addViolation("protected_break_strict", 20);
            plugin.getAlertManager().alertStaff(player, "保护区域破坏检测");
            event.setCancelled(true);
        }
    }

    // 每秒破坏方块数检测
    private void checkBlocksPerSecond(Player player, PlayerData data) {
        // 实现每秒破坏方块数统计
        // 需要记录时间窗口内的破坏次数
    }

    // 工具方法
    private boolean isValuableBlock(Material material) {
        return material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE ||
                material == Material.EMERALD_ORE || material == Material.DEEPSLATE_EMERALD_ORE ||
                material == Material.ANCIENT_DEBRIS || material == Material.NETHERITE_BLOCK;
    }

    private boolean isUnbreakableBlock(Material material) {
        return material == Material.BEDROCK || material == Material.END_PORTAL_FRAME ||
                material == Material.BARRIER || material == Material.COMMAND_BLOCK;
    }

    private boolean isInProtectedArea(Location location) {
        // 检测是否在保护区域内
        // 需要与领地插件集成
        return false;
    }

    public void cleanup(UUID playerId) {
        lastBlockBreak.remove(playerId);
        fastBreakCount.remove(playerId);
    }
}
