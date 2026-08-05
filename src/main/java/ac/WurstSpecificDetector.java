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

public class WurstSpecificDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Integer> freecamViolations = new HashMap<>();
    private final HashMap<UUID, Location> lastValidLocation = new HashMap<>();
    private final HashMap<UUID, Long> lastFreecamCheck = new HashMap<>();

    public WurstSpecificDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkFreecam(player, event);
        checkXRayBehavior(player, event);
        checkDerp(player, event);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkFastBreakWurst(player, event);
    }

    // Freecam 检测
    private void checkFreecam(Player player, PlayerMoveEvent event) {
        UUID playerId = player.getUniqueId();
        Location currentLoc = event.getTo();
        Location lastValid = lastValidLocation.get(playerId);

        if (lastValid != null) {
            double distance = currentLoc.distance(lastValid);

            // 检测异常的位置跳跃（Freecam 特征）
            if (distance > 10 && !player.isInsideVehicle()) {
                int violations = freecamViolations.getOrDefault(playerId, 0) + 1;
                freecamViolations.put(playerId, violations);

                if (violations > 1) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("freecam", 15);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "Freecam 嫌疑 (位置跳跃: " + String.format("%.1f", distance) + " 格)"
                    );
                }
            }
        }

        // 更新有效位置（只有当位置合理时）
        if (isValidLocation(player, currentLoc)) {
            lastValidLocation.put(playerId, currentLoc);
            freecamViolations.put(playerId, 0); // 重置违规计数
        }
    }

    // X-Ray 行为检测
    private void checkXRayBehavior(Player player, PlayerMoveEvent event) {
        // 检测玩家是否直接朝珍贵矿石挖掘
        Location loc = player.getLocation();
        Vector direction = loc.getDirection();

        // 检查视线方向上的方块
        for (int i = 1; i <= 10; i++) {
            Location checkLoc = loc.clone().add(direction.clone().multiply(i));
            Block block = checkLoc.getBlock();

            if (isValuableOre(block.getType())) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("xray_behavior", 8);
                plugin.getAlertManager().alertStaff(
                        player,
                        "X-Ray 行为嫌疑 (直接朝向: " + block.getType() + ")"
                );
                break;
            }

            // 遇到固体方块就停止检查
            if (block.getType().isSolid() && !isTransparentBlock(block.getType())) {
                break;
            }
        }
    }

    // Derp 检测（头部随机旋转）
    private void checkDerp(Player player, PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        float yawChange = Math.abs(to.getYaw() - from.getYaw());
        float pitchChange = Math.abs(to.getPitch() - from.getPitch());

        // 检测异常的头部旋转模式
        if ((yawChange > 90 || pitchChange > 45) &&
                !player.isInsideVehicle() && player.isOnGround()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("derp", 6);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Derp 嫌疑 (Yaw变化: " + String.format("%.1f", yawChange) +
                            ", Pitch变化: " + String.format("%.1f", pitchChange) + ")"
            );
        }
    }

    // Wurst 快速破坏检测
    private void checkFastBreakWurst(Player player, PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null) {
                long expectedTime = getExpectedBreakTime(player, block);
                long actualTime = System.currentTimeMillis() - getLastBreakTime(player);

                // 检测异常快速的破坏
                if (actualTime < expectedTime * 0.3) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_break_wurst", 10);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "Wurst 快速破坏嫌疑 (预期: " + expectedTime + "ms, 实际: " + actualTime + "ms)"
                    );
                }
            }
        }
    }

    // 工具方法
    private boolean isValidLocation(Player player, Location loc) {
        // 检查位置是否合理（不在墙内、不在虚空等）
        if (loc.getY() < -64 || loc.getY() > 320) return false;

        // 检查是否在固体方块内
        Block feet = loc.getBlock();
        if (feet.getType().isSolid()) return false;

        Block head = loc.clone().add(0, 1, 0).getBlock();
        if (head.getType().isSolid()) return false;

        return true;
    }

    private boolean isValuableOre(Material material) {
        return material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE ||
                material == Material.EMERALD_ORE || material == Material.DEEPSLATE_EMERALD_ORE ||
                material == Material.ANCIENT_DEBRIS;
    }

    private boolean isTransparentBlock(Material material) {
        return material == Material.GLASS || material == Material.WATER ||
                material.toString().contains("LEAVES") || material.toString().contains("ICE");
    }

    private long getExpectedBreakTime(Player player, Block block) {
        // 计算预期破坏时间（简化版）
        float hardness = block.getType().getHardness();
        if (hardness <= 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        float speed = getToolSpeed(tool, block.getType());

        return (long) (hardness * 1000 / speed);
    }

    private float getToolSpeed(ItemStack tool, Material blockType) {
        // 计算工具速度（简化版）
        float speed = 1.0f;

        if (isRightToolForBlock(tool.getType(), blockType)) {
            speed = 5.0f;
        }

        if (tool.containsEnchantment(Enchantment.EFFICIENCY)) {
            speed += tool.getEnchantmentLevel(Enchantment.EFFICIENCY) * 0.5f;
        }

        return speed;
    }

    private boolean isRightToolForBlock(Material tool, Material block) {
        // 检查工具是否适合挖掘该方块
        if (tool.toString().contains("PICKAXE") && block.toString().contains("ORE")) return true;
        if (tool.toString().contains("AXE") && block.toString().contains("LOG")) return true;
        if (tool.toString().contains("SHOVEL") && block.toString().contains("DIRT")) return true;
        return false;
    }

    private long getLastBreakTime(Player player) {
        // 获取上次破坏时间（需要从数据中获取）
        return System.currentTimeMillis() - 1000; // 示例值
    }

    public void cleanup(UUID playerId) {
        freecamViolations.remove(playerId);
        lastValidLocation.remove(playerId);
        lastFreecamCheck.remove(playerId);
    }
}
