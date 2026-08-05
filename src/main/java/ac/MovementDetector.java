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

public class MovementDetector implements Listener {

    private final gofd plugin;
    private final HashMap<UUID, Long> lastFlightCheck = new HashMap<>();
    private final HashMap<UUID, Integer> airTicks = new HashMap<>();
    private final HashMap<UUID, Location> lastLocations = new HashMap<>();
    private final HashMap<UUID, Double> lastHorizontalSpeed = new HashMap<>();
    private final HashMap<UUID, Long> lastLiquidMove = new HashMap<>();
    private final HashMap<UUID, Integer> randomCheckCounter = new HashMap<>();
    private final Random random = new Random();
    private final HashMap<UUID, List<Vector>> previousDirections = new HashMap<>();

    // 增强的移动检测配置
    private double MAX_WALK_SPEED = 0.65;
    private double MAX_FLY_SPEED = 1.5;
    private double MAX_VERTICAL_SPEED = 0.6;
    private double MAX_JUMP_HEIGHT = 1.5;
    private static final double MAX_VERTICAL_SPEED_STRICT = 10.0;
    private final long FLIGHT_CHECK_INTERVAL = 1000;
    private final int MAX_AIR_TICKS = 60;


    // 地面材料检测
    private final Set<Material> GROUND_MATERIALS = Set.of(
            Material.STONE, Material.DIRT, Material.GRASS_BLOCK, Material.SAND,
            Material.GRAVEL, Material.COBBLESTONE, Material.OAK_PLANKS,
            Material.BEDROCK, Material.ANDESITE, Material.DIORITE, Material.GRANITE
    );

    public MovementDetector(gofd plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        ac.core.PluginSettings settings = plugin.getSettings();
        if (settings == null) {
            return;
        }
        MAX_WALK_SPEED = settings.maxWalkSpeed();
        MAX_FLY_SPEED = settings.maxFlySpeed();
        MAX_VERTICAL_SPEED = settings.maxVerticalSpeed();
        MAX_JUMP_HEIGHT = settings.maxJumpHeight();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        long startTime = System.nanoTime();

        try {
            if (!plugin.isEnableMovementChecks()) return;

            Player player = event.getPlayer();
            Location from = event.getFrom();
            Location to = event.getTo();
            if (to == null || from.getWorld() != to.getWorld()) return;
            PlayerData data = plugin.getPlayerData(player);

            if (player.hasPermission("gofdac.bypass")) return;

            boolean positionChanged = from.getX() != to.getX()
                    || from.getY() != to.getY()
                    || from.getZ() != to.getZ();
            if (!positionChanged) {
                // Rotation-only packets need the aim check, but do not need the
                // expensive block and history checks below.
                checkFastRotation(player, event, data);
                data.setLastMoveTime(System.currentTimeMillis());
                return;
            }

            // 多维度检测
            checkFlight(player, event, data);
            checkSpeed(player, event, data);
            checkVerticalMovement(player, event, data);
            checkNoFall(player, event, data);
            checkJesus(player, event, data);
            checkSpider(player, event, data);
            checkStep(player, event, data);
            checkFastRotation(player, event, data);
            checkTeleport(player, event, data);

            // 新增检测
            checkGroundAcceleration(player, event, data);
            checkIrregularMovement(player, event, data);
            checkLiquidMovement(player, event, data);
            performRandomMovementCheck(player, event, data);

            // 更新位置历史
            updateLocationHistory(player, event.getTo());
            data.updatePosition(to);

            totalChecks++;
        } finally {
            long endTime = System.nanoTime();
            plugin.getPerformanceMonitor().recordDetectionTime(endTime - startTime);
            plugin.getPerformanceMonitor().recordEvent();
        }
    }

    public void cleanup(UUID playerId) {
        lastFlightCheck.remove(playerId);
        airTicks.remove(playerId);
        lastLocations.remove(playerId);
        lastHorizontalSpeed.remove(playerId);
        lastLiquidMove.remove(playerId);
        randomCheckCounter.remove(playerId);
        previousDirections.remove(playerId);
    }

    private void checkFlight(Player player, PlayerMoveEvent event, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.isGliding() ||
                player.isInsideVehicle() || player.isSwimming()) return;

        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        Location from = event.getFrom();
        Location to = event.getTo();

        // 垂直移动检测
        double verticalMovement = to.getY() - from.getY();

        // 飞行状态检测
        if (!player.isOnGround() && !player.isFlying()) {
            int airTime = airTicks.getOrDefault(playerId, 0) + 1;
            airTicks.put(playerId, airTime);

            // 检查是否应该落地
            if (shouldBeOnGround(player) && airTime > 20) {
                data.addViolation("flight", 3);
                int vl = data.getViolationLevel("flight");

                if (vl > plugin.getAlertThreshold()) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "飞行作弊嫌疑 (空中时间: " + airTime + " ticks, VL: " + vl + ")"
                    );
                }
            }

            // 检查异常悬浮
            if (Math.abs(verticalMovement) < 0.001 && airTime > 40) {
                data.addViolation("hover", 5);
                plugin.getAlertManager().alertStaff(
                        player,
                        "悬停作弊嫌疑 (VL: " + data.getViolationLevel("hover") + ")"
                );
            }
        } else {
            airTicks.put(playerId, 0);
        }

        // 垂直速度检测
        if (Math.abs(verticalMovement) > MAX_VERTICAL_SPEED && !hasJumpPotion(player)) {
            data.addViolation("vertical_speed", 2);
            plugin.getAlertManager().alertStaff(
                    player,
                    "异常垂直速度: " + String.format("%.2f", verticalMovement) +
                            " (VL: " + data.getViolationLevel("vertical_speed") + ")"
            );
        }
    }

    private void checkSpeed(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getWorld() != to.getWorld()) return;

        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);

        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDiff == 0) return;

        double speed = horizontalDistance / (timeDiff / 1000.0);
        double maxAllowedSpeed = calculateMaxSpeed(player);

        if (speed > maxAllowedSpeed * 1.3) { // 30% 容差
            data.addViolation("speed", 2);
            int vl = data.getViolationLevel("speed");

            plugin.getAlertManager().alertStaff(
                    player,
                    "速度作弊嫌疑 (速度: " + String.format("%.2f", speed) +
                            ", 限制: " + String.format("%.2f", maxAllowedSpeed) + ", VL: " + vl + ")"
            );
        }

        data.setLastMoveTime(System.currentTimeMillis());
    }

    private void checkVerticalMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();
        double verticalChange = to.getY() - from.getY();

        // 检测异常跳跃高度
        if (verticalChange > MAX_JUMP_HEIGHT && !hasJumpPotion(player) &&
                !player.isOnGround() && !isOnSlabOrStairs(player)) {
            data.addViolation("high_jump", 4);
            plugin.getAlertManager().alertStaff(
                    player,
                    "异常跳跃高度: " + String.format("%.2f", verticalChange) +
                            " (VL: " + data.getViolationLevel("high_jump") + ")"
            );
        }
    }

    private void checkNoFall(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 检测NoFall作弊 - 玩家从高处落下但没有受到摔落伤害
        if (from.getY() > to.getY() + 4 && shouldTakeFallDamage(player, from.getY() - to.getY())) {
            double fallDistance = getFallDistance(player);
            if (fallDistance > 3 && player.getFallDistance() == 0) {
                data.addViolation("nofall", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "NoFall作弊嫌疑 (摔落距离: " + String.format("%.1f", fallDistance) +
                                ", VL: " + data.getViolationLevel("nofall") + ")"
                );
            }
        }
    }

    private void checkJesus(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测水上行走作弊
        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();

        if ((below == Material.WATER || below.toString().contains("WATER")) &&
                !player.isSwimming() && !isInBoat(player)) {
            data.addViolation("jesus", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "水上行走嫌疑 (VL: " + data.getViolationLevel("jesus") + ")"
            );
        }
    }

    private void checkSpider(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测爬墙作弊
        Location loc = player.getLocation();
        Block faceBlock = loc.getBlock();

        if (isClimbableBlock(faceBlock)) {
            // 正常情况
            return;
        }

        // 检查玩家是否在没有梯子的情况下垂直移动
        double verticalMovement = event.getTo().getY() - event.getFrom().getY();
        if (verticalMovement > 0.5 && !isClimbableBlock(faceBlock) &&
                !player.isOnGround() && !player.isFlying()) {
            data.addViolation("spider", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "爬墙作弊嫌疑 (VL: " + data.getViolationLevel("spider") + ")"
            );
        }
    }

    private void checkStep(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测Step作弊 - 自动走上高方块
        double verticalChange = event.getTo().getY() - event.getFrom().getY();

        if (verticalChange > 1.0 && !player.isOnGround()) {
            data.addViolation("step", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Step作弊嫌疑 (高度: " + String.format("%.2f", verticalChange) +
                            ", VL: " + data.getViolationLevel("step") + ")"
            );
        }
    }

    // 新增：快速转向检测
    private void checkFastRotation(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        float yawChange = Math.abs(to.getYaw() - from.getYaw());
        float pitchChange = Math.abs(to.getPitch() - from.getPitch());

        // 检测异常的角度变化
        if (yawChange > 90.0f && pitchChange > 45.0f) {
            data.addViolation("fast_rotation", 3);
            plugin.getAlertManager().alertStaff(player,
                    "快速转向嫌疑 (Yaw: " + String.format("%.1f", yawChange) +
                            ", Pitch: " + String.format("%.1f", pitchChange) + ")");
        }
    }

    // 新增：传送检测
    private void checkTeleport(Player player, PlayerMoveEvent event, PlayerData data) {
        double distance = event.getFrom().distance(event.getTo());
        if (distance > 10.0 && !player.isInsideVehicle()) {
            data.addViolation("teleport", 10);
            plugin.getAlertManager().alertStaff(player,
                    "疑似传送作弊 (距离: " + String.format("%.1f", distance) + " 格)");
        }
    }

    // 地面加速检测 - 检测异常的地面移动加速度
    private void checkGroundAcceleration(Player player, PlayerMoveEvent event, PlayerData data) {
        if (!player.isOnGround()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        double horizontalDistance = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) +
                        Math.pow(to.getZ() - from.getZ(), 2)
        );

        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDiff == 0) return;

        double currentSpeed = horizontalDistance / (timeDiff / 1000.0);
        UUID playerId = player.getUniqueId();

        Double lastSpeed = lastHorizontalSpeed.get(playerId);
        if (lastSpeed != null) {
            double acceleration = Math.abs(currentSpeed - lastSpeed);
            double maxAcceleration = 2.0; // 最大允许加速度

            // 考虑速度药水效果
            if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                maxAcceleration += 0.5;
            }

            if (acceleration > maxAcceleration) {
                data.addViolation("ground_acceleration", 4);
                plugin.getAlertManager().alertStaff(
                        player,
                        "异常地面加速: " + String.format("%.2f", acceleration) +
                                " (VL: " + data.getViolationLevel("ground_acceleration") + ")"
                );
            }
        }

        lastHorizontalSpeed.put(playerId, currentSpeed);
    }

    // 不规则移动检测 - 检测Timer作弊导致的异常移动
    private void checkIrregularMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 检测异常平滑的移动（Timer特征）
        double distance = from.distance(to);
        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();

        if (timeDiff > 0) {
            double speed = distance / (timeDiff / 1000.0);

            // 检查速度是否过于稳定（Timer作弊）
            if (speed > 0.1) {
                List<Double> recentSpeeds = getRecentMovementSpeeds(player);
                if (recentSpeeds.size() > 10) {
                    double variance = calculateVariance(recentSpeeds);
                    if (variance < 0.001) { // 方差过小，移动过于规律
                        data.addViolation("timer_movement", 6);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "疑似Timer作弊 (移动方差: " + String.format("%.6f", variance) + ")"
                        );
                    }
                }
            }
        }
    }

    // 液体移动检测 - 检测水中异常移动速度
    private void checkLiquidMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location loc = player.getLocation();
        boolean inWater = loc.getBlock().getType() == Material.WATER ||
                loc.clone().subtract(0, 1, 0).getBlock().getType() == Material.WATER;
        boolean inLava = loc.getBlock().getType() == Material.LAVA ||
                loc.clone().subtract(0, 1, 0).getBlock().getType() == Material.LAVA;

        if (inWater || inLava) {
            Location from = event.getFrom();
            Location to = event.getTo();
            double horizontalDistance = Math.sqrt(
                    Math.pow(to.getX() - from.getX(), 2) +
                            Math.pow(to.getZ() - from.getZ(), 2)
            );

            long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
            if (timeDiff > 0) {
                double speed = horizontalDistance / (timeDiff / 1000.0);
                double maxLiquidSpeed = inWater ? 0.3 : 0.15; // 水中和熔岩中的最大速度

                // 考虑水深游效果
                if (inWater && player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                    maxLiquidSpeed *= 1.5;
                }

                if (speed > maxLiquidSpeed) {
                    data.addViolation("liquid_speed", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "液体中异常移动: " + String.format("%.2f", speed) +
                                    " (类型: " + (inWater ? "水" : "熔岩") + ")"
                    );
                }
            }

            // 检测液体中飞行
            if (!player.isSwimming() && player.getLocation().getY() > getLiquidSurface(loc)) {
                data.addViolation("liquid_flight", 5);
                plugin.getAlertManager().alertStaff(player, "液体中飞行嫌疑");
            }
        }
    }

    // 随机移动检查 - 防止作弊者预测检测模式
    private void performRandomMovementCheck(Player player, PlayerMoveEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        int counter = randomCheckCounter.getOrDefault(playerId, 0) + 1;
        randomCheckCounter.put(playerId, counter);

        // 每20-50次移动执行一次随机检查
        if (counter > random.nextInt(30) + 20) {
            randomCheckCounter.put(playerId, 0);

            // 随机选择一种检查类型
            int checkType = random.nextInt(4);
            switch (checkType) {
                case 0:
                    checkMicroMovements(player, event, data);
                    break;
                case 1:
                    checkMovementConsistency(player, event, data);
                    break;
                case 2:
                    checkPositionDesync(player, event, data);
                    break;
                case 3:
                    checkMovementPattern(player, event, data);
                    break;
            }
        }
    }

    // 微移动检测
    private void checkMicroMovements(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();
        double distance = from.distance(to);

        // 检测异常小的移动（某些作弊特征）
        if (distance < 0.001 && distance > 0) {
            data.addViolation("micro_movements", 1);
            if (data.getViolationLevel("micro_movements") > 50) {
                plugin.getAlertManager().alertStaff(player, "异常微移动模式");
            }
        }
    }

    // 移动一致性检测
    private void checkMovementConsistency(Player player, PlayerMoveEvent event, PlayerData data) {
        List<Location> history = data.getMovementHistory();
        if (history.size() < 10) return;

        // 分析移动方向变化模式
        List<Double> directionChanges = new ArrayList<>();
        for (int i = 1; i < history.size() - 1; i++) {
            Location prev = history.get(i - 1);
            Location current = history.get(i);
            Location next = history.get(i + 1);

            double dir1 = Math.atan2(current.getZ() - prev.getZ(), current.getX() - prev.getX());
            double dir2 = Math.atan2(next.getZ() - current.getZ(), next.getX() - current.getX());
            double change = Math.abs(dir1 - dir2);

            directionChanges.add(change);
        }

        // 检测过于规律的移动模式
        double consistency = calculateConsistency(directionChanges);
        if (consistency > 0.9) {
            data.addViolation("movement_consistency", 2);
        }
    }

    // 位置同步检测
    private void checkPositionDesync(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测客户端和服务端位置不同步
        Location serverLocation = player.getLocation();
        Location clientLocation = event.getTo();

        double distance = serverLocation.distance(clientLocation);
        if (distance > 0.5) {
            data.addViolation("position_desync", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "位置同步异常: " + String.format("%.2f", distance)
            );
        }
    }

    // 移动模式检测
    private void checkMovementPattern(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测作弊软件特有的移动模式
        List<Location> history = data.getMovementHistory();
        if (history.size() < 20) return;

        // 分析移动序列的统计特性
        int zigzagCount = countZigzagPatterns(history);
        if (zigzagCount > history.size() * 0.3) { // 30%以上是锯齿模式
            data.addViolation("suspicious_movement_pattern", 4);
            plugin.getAlertManager().alertStaff(player, "可疑移动模式: 锯齿运动");
        }
    }

    private boolean shouldBeOnGround(Player player) {
        Location loc = player.getLocation();
        Location below = loc.clone().subtract(0, 1, 0);
        return isSolidBlock(below.getBlock());
    }

    private boolean isSolidBlock(Block block) {
        return block.getType().isSolid() && !block.isLiquid() &&
                !block.getType().toString().contains("LEAVES") &&
                !block.getType().toString().contains("SIGN");
    }

    private boolean hasJumpPotion(Player player) {
        return player.hasPotionEffect(PotionEffectType.JUMP_BOOST);
    }

    private boolean isOnSlabOrStairs(Player player) {
        Location below = player.getLocation().subtract(0, 1, 0);
        Material material = below.getBlock().getType();
        return material.toString().contains("SLAB") || material.toString().contains("STAIRS");
    }

    private boolean shouldTakeFallDamage(Player player, double fallDistance) {
        return fallDistance > 3 && !player.isOnGround();
    }

    private double getFallDistance(Player player) {
        // 简化版摔落距离计算
        return player.getFallDistance();
    }

    private boolean isInBoat(Player player) {
        return player.isInsideVehicle() &&
                player.getVehicle() != null &&
                player.getVehicle().getType().toString().contains("BOAT");
    }

    private boolean isClimbableBlock(Block block) {
        Material material = block.getType();
        return material == Material.LADDER || material == Material.VINE ||
                material.toString().contains("SCAFFOLD");
    }

    private double calculateMaxSpeed(Player player) {
        double baseSpeed = player.isFlying() ? MAX_FLY_SPEED : MAX_WALK_SPEED;

        // 考虑药水效果
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().equals(PotionEffectType.SPEED)) {
                    baseSpeed *= (1.0 + 0.2 * (effect.getAmplifier() + 1));
                }
            }
        }

        return baseSpeed;
    }

    private void updateLocationHistory(Player player, Location location) {
        lastLocations.put(player.getUniqueId(), location.clone());
    }

    // 工具方法
    private List<Double> getRecentMovementSpeeds(Player player) {
        // 实现获取最近移动速度的逻辑
        List<Double> speeds = new ArrayList<>();
        // 这里需要从PlayerData中获取历史速度数据
        return speeds;
    }

    private double calculateVariance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return variance;
    }

    private double getLiquidSurface(Location loc) {
        // 简化实现，找到液体表面高度
        for (int y = (int) loc.getY(); y < loc.getWorld().getMaxHeight(); y++) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType() != Material.WATER && block.getType() != Material.LAVA) {
                return y - 1;
            }
        }
        return loc.getY();
    }

    private double calculateConsistency(List<Double> values) {
        if (values.isEmpty()) return 0;
        // 计算数值的一致性（1.0表示完全一致）
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumDiff = values.stream().mapToDouble(v -> Math.abs(v - mean)).sum();
        return 1.0 - (sumDiff / (values.size() * Math.PI));
    }
    // 更严格的飞行检测
    private void checkStrictFlight(Player player, PlayerMoveEvent event, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.isGliding() ||
                player.isInsideVehicle() || player.isSwimming()) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        double verticalChange = to.getY() - from.getY();

        // 如果玩家不在梯子或藤蔓上，并且垂直移动超过一定速度，则标记
        if (!isClimbing(player) && Math.abs(verticalChange) > MAX_VERTICAL_SPEED_STRICT) {
            data.addViolation("strict_vertical_speed", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "严格垂直速度检测: " + String.format("%.2f", verticalChange)
            );
        }

        // 检测空中移动方向变化（飞行作弊往往有异常的方向变化）
        if (!player.isOnGround() && !player.isFlying()) {
            Vector fromVector = from.toVector();
            Vector toVector = to.toVector();
            Vector direction = toVector.subtract(fromVector);

            // 如果水平移动距离大于0，检查方向变化
            if (direction.length() > 0.1) {
                Vector horizontalDirection = new Vector(direction.getX(), 0, direction.getZ());
                Vector previousHorizontalDirection = getPreviousHorizontalDirection(player);

                if (previousHorizontalDirection != null) {
                    double angle = horizontalDirection.angle(previousHorizontalDirection);
                    // 如果角度变化过大，可能是飞行作弊
                    if (angle > Math.PI / 4) { // 45度
                        data.addViolation("strict_air_direction_change", 3);
                        if (data.getViolationLevel("strict_air_direction_change") > 5) {
                            plugin.getAlertManager().alertStaff(
                                    player,
                                    "空中异常方向变化: " + String.format("%.2f", Math.toDegrees(angle)) + "度"
                            );
                        }
                    }
                }
                setPreviousHorizontalDirection(player, horizontalDirection);
            }
        }
    }

    // 获取先前的水平方向
    private Vector getPreviousHorizontalDirection(Player player) {
        // 从存储中获取先前的方向
        return (Vector) previousDirections.get(player.getUniqueId());
    }

    // 设置先前的水平方向
    private void setPreviousHorizontalDirection(Player player, Vector direction) {
        previousDirections.put(player.getUniqueId(), (List<Vector>) direction);
    }

    // 判断玩家是否在爬梯子或藤蔓
    private boolean isClimbing(Player player) {
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        return isClimbableBlock(block) || isClimbableBlock(block.getRelative(0, 1, 0));
    }

    private int countZigzagPatterns(List<Location> history) {
        int count = 0;
        for (int i = 2; i < history.size(); i++) {
            Location p1 = history.get(i - 2);
            Location p2 = history.get(i - 1);
            Location p3 = history.get(i);

            double angle1 = Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX());
            double angle2 = Math.atan2(p3.getZ() - p2.getZ(), p3.getX() - p2.getX());
            double angleDiff = Math.abs(angle1 - angle2);

            if (angleDiff > Math.PI / 4 && angleDiff < Math.PI * 3 / 4) { // 45-135度转折
                count++;
            }
        }
        return count;
    }

    private int totalChecks = 0;
    public int getTotalChecks() { return totalChecks; }
}
