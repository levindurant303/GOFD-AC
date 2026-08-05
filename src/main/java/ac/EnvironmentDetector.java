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

public class EnvironmentDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastBlockBreak = new HashMap<>();
    private final HashMap<UUID, Integer> blockBreakCount = new HashMap<>();
    private final HashMap<UUID, Integer> totalBlocksBroken = new HashMap<>();
    private final HashMap<UUID, Long> lastBlockPlace = new HashMap<>();
    private final HashMap<UUID, Integer> blockPlaceCount = new HashMap<>();
    private final HashMap<UUID, Set<Location>> brokenBlocks = new HashMap<>();
    private final HashMap<UUID, Long> brokenBlockWindowStart = new HashMap<>();
    private final HashMap<UUID, Map<Material, Integer>> oreMiningStats = new HashMap<>();
    private final HashMap<UUID, Material> lastOreType = new HashMap<>();
    private final HashMap<UUID, Integer> consecutiveOreCount = new HashMap<>();
    private final HashMap<UUID, Long> lastScaffoldPlace = new HashMap<>();
    private final HashMap<UUID, Integer> scaffoldCount = new HashMap<>();

    // 珍贵矿石列表
    private final Set<Material> valuableOres = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE
    );

    // 可透视方块列表（X-Ray特征）
    private final Set<Material> transparentBlocks = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.NETHERRACK,
            Material.END_STONE, Material.TUFF, Material.ANDESITE
    );

    public EnvironmentDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;
        UUID playerId = player.getUniqueId();
        totalBlocksBroken.merge(playerId, 1, Integer::sum);

        checkBreakSpeed(player, event);
        checkNuker(player, event);
        checkXRay(player, event);
        checkInstaBreak(player, event);
        checkIllegalBreak(player, event);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkPlaceSpeed(player, event);
        checkScaffold(player, event);
        checkIllegalPlace(player, event);
        checkBlockReach(player, event);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkInteractReach(player, event);
        checkAutoTool(player, event);
    }

    // 破坏速度检测 - 检测异常方块破坏速度
    private void checkBreakSpeed(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastBreak = lastBlockBreak.get(playerId);

        if (lastBreak != null) {
            long timeSinceLastBreak = currentTime - lastBreak;

            // 检测连续破坏方块的速度
            if (timeSinceLastBreak < 100) { // 100ms内连续破坏
                int count = blockBreakCount.getOrDefault(playerId, 0) + 1;
                blockBreakCount.put(playerId, count);

                if (count > 5) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_break", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "快速破坏方块: " + count + " 次连续快速破坏"
                    );
                }
            } else {
                blockBreakCount.put(playerId, 1);
            }
        } else {
            blockBreakCount.put(playerId, 1);
        }

        lastBlockBreak.put(playerId, currentTime);

        // 检测特定方块的破坏速度
        checkSpecificBlockBreakSpeed(player, event);
    }

    // Nuker检测 - 检测同时破坏多个方块
    private void checkNuker(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Set<Location> broken = brokenBlocks.computeIfAbsent(playerId, ignored -> new HashSet<>());
        long windowStart = brokenBlockWindowStart.getOrDefault(playerId, currentTime);
        if (currentTime - windowStart > 5000L) {
            broken.clear();
            windowStart = currentTime;
        }
        brokenBlockWindowStart.put(playerId, windowStart);
        broken.add(event.getBlock().getLocation());

        // 检查短时间内破坏的方块数量
        if (broken.size() > 5) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("nuker", 8);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Nuker嫌疑: 短时间内破坏 " + broken.size() + " 个方块"
            );
            broken.clear();
        }

        brokenBlocks.put(playerId, broken);

        // 检测3x3区域破坏
        checkAreaBreak(player, event);
    }

    // X-Ray检测 - 检测直接挖掘珍贵矿石
    private void checkXRay(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        if (valuableOres.contains(material)) {
            // 记录矿石挖掘统计
            Map<Material, Integer> stats = oreMiningStats.getOrDefault(
                    player.getUniqueId(), new HashMap<>());

            int count = stats.getOrDefault(material, 0) + 1;
            stats.put(material, count);
            oreMiningStats.put(player.getUniqueId(), stats);

            // 检测矿石挖掘效率（X-Ray特征）
            double efficiency = calculateMiningEfficiency(player, block);
            if (efficiency > 0.8) { // 80%以上效率挖掘到矿石
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("xray_efficiency", 4);

                if (data.getViolationLevel("xray_efficiency") > 10) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "X-Ray效率嫌疑: " + String.format("%.1f", efficiency * 100) + "%"
                    );
                }
            }

            // 检测连续挖掘珍贵矿石
            checkConsecutiveOreMining(player, material);

            // 检测挖掘路径（是否直接朝向矿石）
            checkMiningDirection(player, block);
        }

        // 检测透视挖掘模式
        checkOreToWasteRatio(player, block);
    }

    // 瞬间破坏检测
    private void checkInstaBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检查不应该被瞬间破坏的方块
        if (material.getHardness() > 1.0) {
            long breakTime = calculateExpectedBreakTime(player, block);
            long actualTime = System.currentTimeMillis() - lastBlockBreak.getOrDefault(player.getUniqueId(), 0L);

            if (actualTime < breakTime * 0.1) { // 实际时间低于预期时间的10%
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("insta_break", 10);
                plugin.getAlertManager().alertStaff(
                        player,
                        "瞬间破坏: " + material + " (预期: " + breakTime + "ms, 实际: " + actualTime + "ms)"
                );
            }
        }
    }

    // 非法破坏检测
    private void checkIllegalBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检测破坏不可破坏的方块
        if (material == Material.BEDROCK || material == Material.END_PORTAL_FRAME ||
                material == Material.BARRIER || material.toString().contains("SPAWNER")) {

            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("illegal_break", 15);
            plugin.getAlertManager().alertStaff(
                    player,
                    "非法破坏: " + material
            );

            event.setCancelled(true);
        }

        // 检测在保护区域破坏
        if (isInProtectedRegion(block.getLocation())) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("protected_break", 8);
            plugin.getAlertManager().alertStaff(player, "在保护区域破坏方块");
        }
    }

    // 放置速度检测 - 检测异常方块放置速度
    private void checkPlaceSpeed(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastPlace = lastBlockPlace.get(playerId);

        if (lastPlace != null) {
            long timeSinceLastPlace = currentTime - lastPlace;

            if (timeSinceLastPlace < 100) { // 100ms内连续放置
                int count = blockPlaceCount.getOrDefault(playerId, 0) + 1;
                blockPlaceCount.put(playerId, count);

                if (count > 5) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_place", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "快速放置方块: " + count + " 次连续快速放置"
                    );
                }
            } else {
                blockPlaceCount.put(playerId, 1);
            }
        } else {
            blockPlaceCount.put(playerId, 1);
        }

        lastBlockPlace.put(playerId, currentTime);
    }

    // Scaffold检测 - 检测自动搭路作弊
    private void checkScaffold(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        Block block = event.getBlock();
        Location blockLoc = block.getLocation();
        Location playerLoc = player.getLocation();

        // 检测脚下放置（搭路特征）
        Location feetLoc = playerLoc.clone().subtract(0, 1, 0);
        if (blockLoc.getBlockX() == feetLoc.getBlockX() &&
                blockLoc.getBlockZ() == feetLoc.getBlockZ() &&
                Math.abs(blockLoc.getY() - feetLoc.getY()) <= 2) {

            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            Long lastScaffold = lastScaffoldPlace.get(playerId);

            if (lastScaffold != null) {
                long timeSinceLastScaffold = currentTime - lastScaffold;

                if (timeSinceLastScaffold < 200) { // 200ms内连续搭路
                    int count = scaffoldCount.getOrDefault(playerId, 0) + 1;
                    scaffoldCount.put(playerId, count);

                    if (count > 3) {
                        PlayerData data = plugin.getPlayerData(player);
                        data.addViolation("scaffold", 5);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "Scaffold嫌疑: " + count + " 次连续搭路"
                        );
                    }
                } else {
                    scaffoldCount.put(playerId, 1);
                }
            } else {
                scaffoldCount.put(playerId, 1);
            }

            lastScaffoldPlace.put(playerId, currentTime);
        }

        // 检测搭路方向一致性
        checkScaffoldDirection(player, event);

        // 检测空中搭路
        checkAirPlace(player, event);
    }

    // 非法放置检测
    private void checkIllegalPlace(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检测放置非法方块
        if (material == Material.BEDROCK || material == Material.BARRIER ||
                material.toString().contains("COMMAND") || material.toString().contains("STRUCTURE")) {

            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("illegal_place", 12);
            plugin.getAlertManager().alertStaff(
                    player,
                    "非法放置: " + material
            );

            event.setCancelled(true);
        }

        // 检测在异常位置放置
        if (block.getY() > 255 || block.getY() < -64) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("impossible_place", 8);
            plugin.getAlertManager().alertStaff(player, "在不可能位置放置方块");
            event.setCancelled(true);
        }
    }

    // 方块交互距离检测
    private void checkInteractReach(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        double distance = player.getLocation().distance(event.getClickedBlock().getLocation());
        double maxInteractDistance = plugin.getConfig().getDouble("interaction.max-block-interact-distance", 6.0);

        if (distance > maxInteractDistance) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("interact_reach", 4);
            plugin.getAlertManager().alertStaff(
                    player,
                    "超距交互: " + String.format("%.2f", distance) + " 格"
            );
        }
    }

    // 自动工具检测
    private void checkAutoTool(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        // 检测异常快速的最佳工具切换
        ItemStack currentTool = player.getInventory().getItemInMainHand();
        Block targetBlock = event.getClickedBlock();

        // 检查是否使用了最佳工具
        Material bestTool = getBestToolForBlock(targetBlock.getType());
        if (bestTool != null && currentTool.getType() == bestTool) {
            // 记录工具切换模式
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("auto_tool_pattern", 1);

            if (data.getViolationLevel("auto_tool_pattern") > 20) {
                plugin.getAlertManager().alertStaff(player, "疑似自动工具切换");
            }
        }
    }

    // 方块放置距离检测
    private void checkBlockReach(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        double distance = player.getLocation().distance(event.getBlock().getLocation());
        double maxPlaceDistance = 6.0;

        if (distance > maxPlaceDistance) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("place_reach", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "超距放置: " + String.format("%.2f", distance) + " 格"
            );
        }
    }

    // 工具方法
    private void checkSpecificBlockBreakSpeed(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 对特定方块进行更严格的检测
        if (material.getHardness() > 2.0) { // 硬方块
            long expectedTime = (long) (material.getHardness() * 1000);
            long actualTime = System.currentTimeMillis() - lastBlockBreak.getOrDefault(player.getUniqueId(), 0L);

            if (actualTime < expectedTime * 0.3) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("fast_hard_break", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "快速破坏硬方块: " + material + " (时间: " + actualTime + "ms)"
                );
            }
        }
    }

    private void checkAreaBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        // 检测3x3区域破坏
        Block center = event.getBlock();
        int brokenInArea = 0;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block relative = center.getRelative(x, 0, z);
                if (relative.getType().isAir()) {
                    brokenInArea++;
                }
            }
        }

        if (brokenInArea >= 5) { // 3x3区域中5个以上方块被破坏
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("area_break", 7);
            plugin.getAlertManager().alertStaff(player, "区域破坏嫌疑 (3x3)");
        }
    }

    private double calculateMiningEfficiency(Player player, Block oreBlock) {
        // 计算挖掘效率（挖到矿石的比例）
        Map<Material, Integer> stats = oreMiningStats.get(player.getUniqueId());
        if (stats == null) return 0;

        int totalOres = stats.values().stream().mapToInt(Integer::intValue).sum();
        int totalBlocks = getTotalBlocksBroken(player);

        if (totalBlocks == 0) return 0;
        return (double) totalOres / totalBlocks;
    }

    private void checkConsecutiveOreMining(Player player, Material oreType) {
        UUID playerId = player.getUniqueId();
        Material previous = lastOreType.put(playerId, oreType);
        int count = previous == oreType ? consecutiveOreCount.getOrDefault(playerId, 0) + 1 : 1;
        consecutiveOreCount.put(playerId, count);
        if (count >= 8 && count % 8 == 0) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("consecutive_ore", 1);
        }
    }

    private void checkMiningDirection(Player player, Block oreBlock) {
        // 检测挖掘方向（是否直接朝向矿石）
        Location playerLoc = player.getLocation();
        Location oreLoc = oreBlock.getLocation();

        Vector toOre = oreLoc.toVector().subtract(playerLoc.toVector()).normalize();
        Vector viewDirection = playerLoc.getDirection();

        double dot = toOre.dot(viewDirection);
        if (dot > 0.95) { // 几乎直接朝向矿石
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("direct_ore_targeting", 3);
        }
    }

    private void checkOreToWasteRatio(Player player, Block block) {
        Map<Material, Integer> stats = oreMiningStats.get(player.getUniqueId());
        int totalBlocks = getTotalBlocksBroken(player);
        if (stats == null || totalBlocks < 30) {
            return;
        }
        int totalOres = stats.values().stream().mapToInt(Integer::intValue).sum();
        if (totalOres / (double) totalBlocks > 0.35D) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("ore_ratio", 1);
        }
    }

    private long calculateExpectedBreakTime(Player player, Block block) {
        // 计算预期破坏时间（考虑工具、附魔等）
        float hardness = block.getType().getHardness();
        if (hardness <= 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        float speedMultiplier = getToolEfficiency(tool, block.getType());

        return (long) (hardness * 1000 / speedMultiplier);
    }

    private boolean isInProtectedRegion(Location location) {
        // 检测是否在保护区域内（需要与领地插件集成）
        return false;
    }

    private void checkScaffoldDirection(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        // 检测搭路方向的一致性
        Vector movementDir = player.getLocation().getDirection();
        Vector placeDir = event.getBlock().getLocation().toVector().subtract(player.getLocation().toVector()).normalize();

        double dot = movementDir.dot(placeDir);
        if (dot > 0.8) { // 放置方向与移动方向高度一致
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("scaffold_direction", 2);
        }
    }

    private void checkAirPlace(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        // 检测空中放置方块
        Block block = event.getBlock();
        Block below = block.getRelative(0, -1, 0);

        if (below.getType().isAir() && !player.isOnGround()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("air_place", 4);
            plugin.getAlertManager().alertStaff(player, "空中放置方块嫌疑");
        }
    }

    private Material getBestToolForBlock(Material blockType) {
        // 返回挖掘该方块的最佳工具
        // 简化实现
        if (blockType.toString().contains("ORE")) {
            return Material.IRON_PICKAXE;
        }
        if (blockType.toString().contains("LOG")) {
            return Material.IRON_AXE;
        }
        return null;
    }

    private float getToolEfficiency(ItemStack tool, Material blockType) {
        // 计算工具效率
        if (tool == null) return 1.0f;

        float efficiency = 1.0f;

        // 检查工具类型
        if (isRightToolForBlock(tool.getType(), blockType)) {
            efficiency *= 5.0f; // 正确工具的基础倍率
        }

        // 检查效率附魔
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchants()) {
            org.bukkit.enchantments.Enchantment enchant = org.bukkit.enchantments.Enchantment.EFFICIENCY;
            if (tool.getItemMeta().hasEnchant(enchant)) {
                efficiency += tool.getItemMeta().getEnchantLevel(enchant) * 0.5f;
            }
        }

        return efficiency;
    }

    private boolean isRightToolForBlock(Material tool, Material block) {
        // 检查工具是否适合挖掘该方块
        if (tool.toString().contains("PICKAXE") && block.toString().contains("ORE")) return true;
        if (tool.toString().contains("AXE") && block.toString().contains("LOG")) return true;
        if (tool.toString().contains("SHOVEL") && block.toString().contains("DIRT")) return true;
        return false;
    }

    private int getTotalBlocksBroken(Player player) {
        return totalBlocksBroken.getOrDefault(player.getUniqueId(), 0);
    }

    public void cleanup(UUID playerId) {
        lastBlockBreak.remove(playerId);
        blockBreakCount.remove(playerId);
        totalBlocksBroken.remove(playerId);
        lastBlockPlace.remove(playerId);
        blockPlaceCount.remove(playerId);
        brokenBlocks.remove(playerId);
        brokenBlockWindowStart.remove(playerId);
        oreMiningStats.remove(playerId);
        lastOreType.remove(playerId);
        consecutiveOreCount.remove(playerId);
        lastScaffoldPlace.remove(playerId);
        scaffoldCount.remove(playerId);
    }
}
