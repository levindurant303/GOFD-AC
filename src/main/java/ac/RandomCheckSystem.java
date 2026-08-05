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

public class RandomCheckSystem {
    private final gofd plugin;
    private final Random random = new Random();
    private final Map<UUID, Integer> checkCounters = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastRandomCheck = new ConcurrentHashMap<>();
    private org.bukkit.scheduler.BukkitTask randomTask;
    private org.bukkit.scheduler.BukkitTask deepRandomTask;

    // 检查类型权重
    private final Map<String, Integer> checkWeights = Map.of(
            "MOVEMENT", 25,
            "COMBAT", 20,
            "INVENTORY", 15,
            "ENVIRONMENT", 15,
            "NETWORK", 10,
            "ITEM", 10,
            "CHAT", 5
    );

    public RandomCheckSystem(gofd plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (randomTask != null) {
            return;
        }
        int intervalSeconds = plugin.getSettings() == null
                ? 30 : plugin.getSettings().randomCheckIntervalSeconds();
        long intervalTicks = Math.max(20L, intervalSeconds * 20L);
        // 每30秒执行一次随机检查
        randomTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // 每个玩家有15%的几率被随机检查
                    if (random.nextDouble() < 0.15) {
                        performRandomCheck(player);
                    }
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks); // 30秒间隔

        // 每5分钟执行一次深度随机检查
        deepRandomTask = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // 每个玩家有5%的几率被深度检查
                    if (random.nextDouble() < 0.05) {
                        performDeepRandomCheck(player);
                    }
                }
            }
        }.runTaskTimer(plugin, intervalTicks * 10L, intervalTicks * 10L); // 5分钟间隔
    }

    public void stop() {
        if (randomTask != null) {
            randomTask.cancel();
            randomTask = null;
        }
        if (deepRandomTask != null) {
            deepRandomTask.cancel();
            deepRandomTask = null;
        }
    }

    // 执行随机检查
    private void performRandomCheck(Player player) {
        if (player.hasPermission("gofdac.bypass")) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastCheck = lastRandomCheck.get(playerId);

        // 防止过于频繁的检查
        if (lastCheck != null && currentTime - lastCheck < 10000) {
            return;
        }

        lastRandomCheck.put(playerId, currentTime);

        // 根据权重随机选择检查类型
        String checkType = getRandomCheckType();

        switch (checkType) {
            case "MOVEMENT":
                performRandomMovementCheck(player);
                break;
            case "COMBAT":
                performRandomCombatCheck(player);
                break;
            case "INVENTORY":
                performRandomInventoryCheck(player);
                break;
            case "ENVIRONMENT":
                performRandomEnvironmentCheck(player);
                break;
            case "NETWORK":
                performRandomNetworkCheck(player);
                break;
            case "ITEM":
                performRandomItemCheck(player);
                break;
            case "CHAT":
                performRandomChatCheck(player);
                break;
        }

        // 更新检查计数器
        int count = checkCounters.getOrDefault(playerId, 0) + 1;
        checkCounters.put(playerId, count);

        plugin.getAlertManager().logDebug("对玩家 " + player.getName() + " 执行随机检查: " + checkType);
    }

    // 执行深度随机检查
    private void performDeepRandomCheck(Player player) {
        if (player.hasPermission("gofdac.bypass")) return;

        // 执行所有类型的检查
        performRandomMovementCheck(player);
        performRandomCombatCheck(player);
        performRandomInventoryCheck(player);
        performRandomEnvironmentCheck(player);
        performRandomNetworkCheck(player);
        performRandomItemCheck(player);
        performRandomChatCheck(player);

        plugin.getAlertManager().logDebug("对玩家 " + player.getName() + " 执行深度随机检查");
    }

    // 随机移动检查
    private void performRandomMovementCheck(Player player) {
        PlayerData data = plugin.getPlayerData(player);

        // 检查移动模式
        List<Location> movementHistory = data.getMovementHistory();
        if (movementHistory.size() > 10) {
            // 分析移动模式的随机性
            double randomness = calculateMovementRandomness(movementHistory);
            if (randomness < 0.1) { // 移动模式过于规律
                data.addViolation("patterned_movement", 2);
            }
        }

        // 检查当前速度
        Location currentLoc = player.getLocation();
        if (movementHistory.size() > 1) {
            Location lastLoc = movementHistory.get(movementHistory.size() - 2);
            double distance = currentLoc.distance(lastLoc);
            long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();

            if (timeDiff > 0) {
                double speed = distance / (timeDiff / 1000.0);
                if (speed > 2.0 && player.isOnGround()) {
                    data.addViolation("random_check_speed", 3);
                }
            }
        }
    }

    // 随机战斗检查
    private void performRandomCombatCheck(Player player) {
        PlayerData data = plugin.getPlayerData(player);

        // 检查CPS
        double cps = data.getCPS();
        if (cps > 20) {
            data.addViolation("random_check_cps", 3);
        }

        // 检查攻击模式
        LinkedList<Long> attacks = data.attackTimestamps;
        if (attacks.size() > 5) {
            double regularity = calculateAttackRegularity(attacks);
            if (regularity > 0.9) {
                data.addViolation("random_check_attack_pattern", 2);
            }
        }
    }

    // 随机库存检查
    private void performRandomInventoryCheck(Player player) {
        // 检查库存中的可疑物品
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isSuspiciousItem(item)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("random_check_suspicious_item", 4);
                break;
            }
        }

        // 检查物品数量异常
        checkItemQuantityAnomalies(player);
    }

    // 随机环境检查
    private void performRandomEnvironmentCheck(Player player) {
        // 检查玩家位置是否异常
        Location loc = player.getLocation();
        if (loc.getY() < -64 || loc.getY() > 320) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("random_check_position", 5);
        }

        // 检查周围方块异常
        checkSurroundingBlocks(player);
    }

    // 随机网络检查
    private void performRandomNetworkCheck(Player player) {
        NetworkDetector networkDetector = plugin.networkDetector;
        if (networkDetector != null) {
            networkDetector.checkLatencyAnomaly(player);
            networkDetector.checkKeepAlive(player);
        }
    }

    // 随机物品检查
    private void performRandomItemCheck(Player player) {
        // 检查手持物品
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem != null) {
            checkItemValidity(player, handItem);
        }

        // 检查装备
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null) {
                checkItemValidity(player, armor);
            }
        }
    }

    // 随机聊天检查
    private void performRandomChatCheck(Player player) {
        // 检查最近的聊天记录
        // 实现聊天记录分析
    }

    // 工具方法
    private String getRandomCheckType() {
        int totalWeight = checkWeights.values().stream().mapToInt(Integer::intValue).sum();
        int randomValue = random.nextInt(totalWeight);
        int currentWeight = 0;

        for (Map.Entry<String, Integer> entry : checkWeights.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue < currentWeight) {
                return entry.getKey();
            }
        }

        return "MOVEMENT"; // 默认值
    }

    private double calculateMovementRandomness(List<Location> movementHistory) {
        if (movementHistory.size() < 3) return 1.0;

        // 计算移动方向变化的随机性
        List<Double> directionChanges = new ArrayList<>();
        for (int i = 1; i < movementHistory.size() - 1; i++) {
            Location prev = movementHistory.get(i - 1);
            Location current = movementHistory.get(i);
            Location next = movementHistory.get(i + 1);

            double dir1 = Math.atan2(current.getZ() - prev.getZ(), current.getX() - prev.getX());
            double dir2 = Math.atan2(next.getZ() - current.getZ(), next.getX() - current.getX());
            double change = Math.abs(dir1 - dir2);

            directionChanges.add(change);
        }

        // 计算标准差（标准差越小，模式越规律）
        double mean = directionChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = directionChanges.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        return stdDev; // 返回随机性指标
    }

    private double calculateAttackRegularity(LinkedList<Long> attacks) {
        if (attacks.size() < 2) return 0;

        List<Long> intervals = new ArrayList<>();
        Iterator<Long> iterator = attacks.iterator();
        long prev = iterator.next();

        while (iterator.hasNext()) {
            long current = iterator.next();
            intervals.add(current - prev);
            prev = current;
        }

        // 计算间隔的一致性
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double sumSqDiff = intervals.stream().mapToDouble(i -> Math.pow(i - mean, 2)).sum();
        double variance = sumSqDiff / intervals.size();
        double stdDev = Math.sqrt(variance);

        // 标准差越小，规律性越高
        return 1.0 - (stdDev / mean);
    }

    private boolean isSuspiciousItem(ItemStack item) {
        if (item == null) return false;

        // 检测非法附魔
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry :
                    item.getEnchantments().entrySet()) {
                if (entry.getValue() > entry.getKey().getMaxLevel() + 5) {
                    return true;
                }
            }
        }

        // 检测非法堆叠
        if (item.getAmount() > item.getMaxStackSize()) {
            return true;
        }

        // 检测创造模式专属物品
        return isCreativeOnlyItem(item);
    }

    private boolean isCreativeOnlyItem(ItemStack item) {
        Material material = item.getType();
        return material == Material.COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW || material == Material.BARRIER ||
                material == Material.BEDROCK;
    }

    private void checkItemQuantityAnomalies(Player player) {
        // 检查物品数量异常（复制物品嫌疑）
        Map<Material, Integer> itemCounts = new HashMap<>();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) {
                Material material = item.getType();
                int count = itemCounts.getOrDefault(material, 0) + item.getAmount();
                itemCounts.put(material, count);

                // 检查是否超过合理数量
                if (count > getReasonableQuantity(material)) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("suspicious_item_quantity", 6);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "可疑物品数量: " + material + " x" + count
                    );
                }
            }
        }
    }

    private int getReasonableQuantity(Material material) {
        // 返回该物品的合理数量上限
        if (material.toString().contains("DIAMOND")) return 64;
        if (material.toString().contains("EMERALD")) return 64;
        if (material.toString().contains("NETHERITE")) return 16;
        return 256; // 普通物品
    }

    private void checkSurroundingBlocks(Player player) {
        // 检查玩家周围的方块是否异常
        Location loc = player.getLocation();

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    if (isSuspiciousBlock(block)) {
                        PlayerData data = plugin.getPlayerData(player);
                        data.addViolation("suspicious_surroundings", 3);
                        break;
                    }
                }
            }
        }
    }

    private boolean isSuspiciousBlock(Block block) {
        // 检测可疑方块（如命令方块、结构方块等）
        Material material = block.getType();
        return material == Material.COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW || material == Material.BARRIER;
    }

    private void checkItemValidity(Player player, ItemStack item) {
        // 检查物品有效性
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String name = item.getItemMeta().getDisplayName();
            if (name.contains("作弊") || name.contains("hack") || name.contains("exploit")) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_item_name", 4);
            }
        }
    }

    public void cleanup(UUID playerId) {
        checkCounters.remove(playerId);
        lastRandomCheck.remove(playerId);
    }
}
