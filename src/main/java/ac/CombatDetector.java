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

public class CombatDetector implements Listener {

    private final gofd plugin;
    private final HashMap<UUID, Long> lastAttackTime = new HashMap<>();
    private final HashMap<UUID, Integer> attackCount = new HashMap<>();
    private final HashMap<UUID, Double> lastAttackYaw = new HashMap<>();
    private final HashMap<UUID, Location> lastAttackLocation = new HashMap<>();
    private final HashMap<UUID, Set<UUID>> attackedEntities = new HashMap<>();
    private final HashMap<UUID, Long> lastBowShot = new HashMap<>();
    private final HashMap<UUID, Integer> criticalHitCount = new HashMap<>();
    private final HashMap<UUID, Long> lastCriticalHit = new HashMap<>();
    private final HashMap<UUID, Integer> comboPatternCount = new HashMap<>();
    private final HashMap<UUID, Integer> randomCombatCheck = new HashMap<>();
    private final Random combatRandom = new Random();
    private final HashMap<UUID, Integer> killAuraViolations = new HashMap<>();
    private final HashMap<UUID, Long> lastHitTime = new HashMap<>();
    private final HashMap<UUID, Double> lastHitAngle = new HashMap<>();

    // 增强的战斗检测配置
    private int MAX_CPS = 12;
    private double MAX_REACH = 4;
    private double MAX_ANGLE_CHANGE = 60.0; // 最大角度变化
    private final long KILLAURA_CHECK_INTERVAL = 800; // 杀怒检测间隔

    public CombatDetector(gofd plugin) {
        this.plugin = plugin;
        reloadSettings();
    }

    public void reloadSettings() {
        ac.core.PluginSettings settings = plugin.getSettings();
        if (settings == null) {
            return;
        }
        MAX_CPS = settings.maxCps();
        MAX_REACH = settings.maxReach();
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        long startTime = System.nanoTime();

        try {
            if (!plugin.isEnableCombatChecks()) return;

            if (!(event.getDamager() instanceof Player)) return;

            Player player = (Player) event.getDamager();
            PlayerData data = plugin.getPlayerData(player);

            if (player.hasPermission("gofdac.bypass")) return;

            // 记录攻击
            data.recordAttack();

            // 多重检测
            checkAttackFrequency(player, data);
            checkReach(player, event, data);
            checkKillAura(player, event, data);
            checkAimbot(player, event, data);
            checkMultiAttack(player, event, data);
            checkHitWhileMoving(player, event, data);

            // 新增检测
            checkCriticalHits(player, event, data);
            checkEnhancedWallHit(player, event, data);
            checkComboPattern(player, event, data);
            performRandomCombatCheck(player, event, data);

            totalChecks++;
        } finally {
            long endTime = System.nanoTime();
            plugin.getPerformanceMonitor().recordDetectionTime(endTime - startTime);
            plugin.getPerformanceMonitor().recordEvent();
        }
    }

    public void cleanup(UUID playerId) {
        lastAttackTime.remove(playerId);
        attackCount.remove(playerId);
        lastAttackYaw.remove(playerId);
        lastAttackLocation.remove(playerId);
        attackedEntities.remove(playerId);
        lastBowShot.remove(playerId);
        criticalHitCount.remove(playerId);
        lastCriticalHit.remove(playerId);
        comboPatternCount.remove(playerId);
        randomCombatCheck.remove(playerId);
        killAuraViolations.remove(playerId);
        lastHitTime.remove(playerId);
        lastHitAngle.remove(playerId);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onProjectileLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (!(event.getEntity().getShooter() instanceof Player)) return;

        Player player = (Player) event.getEntity().getShooter();
        PlayerData data = plugin.getPlayerData(player);

        if (player.hasPermission("gofdac.bypass")) return;

        // 检测弓弩射击速度
        checkBowSpeed(player, data);

        // 检测弹道轨迹
        if (event.getEntity() instanceof Arrow) {
            checkArrowTrajectory(player, (Arrow) event.getEntity(), data);
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // 检测自动点击器
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR ||
                event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            checkAutoClicker(event.getPlayer());
        }
    }

    private void checkBowSpeed(Player player, PlayerData data) {
        long currentTime = System.currentTimeMillis();
        Long lastShot = lastBowShot.get(player.getUniqueId());

        if (lastShot != null && currentTime - lastShot < 200) { // 200ms内连续射击
            data.addViolation("fast_bow", 2);
            plugin.getAlertManager().alertStaff(player, "快速射击嫌疑");
        }

        lastBowShot.put(player.getUniqueId(), currentTime);
    }

    private void checkArrowTrajectory(Player player, Arrow arrow, PlayerData data) {
        Vector velocity = arrow.getVelocity();
        double speed = velocity.length();

        // 检测异常箭速
        if (speed > 4.0) {
            data.addViolation("arrow_speed", 3);
            plugin.getAlertManager().alertStaff(player,
                    "异常箭速: " + String.format("%.2f", speed));
        }
    }

    private void checkAttackFrequency(Player player, PlayerData data) {
        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        Long lastAttack = lastAttackTime.get(playerId);
        Integer attacks = attackCount.get(playerId);

        if (lastAttack == null || currentTime - lastAttack > 1000) {
            // 重置计数器
            lastAttackTime.put(playerId, currentTime);
            attackCount.put(playerId, 1);
            attackedEntities.put(playerId, new HashSet<>());
            return;
        }

        int newCount = (attacks == null ? 1 : attacks + 1);
        attackCount.put(playerId, newCount);

        // CPS检测
        if (newCount > MAX_CPS) {
            data.addViolation("cps", 3);
            int vl = data.getViolationLevel("cps");

            plugin.getAlertManager().alertStaff(
                    player,
                    "高频攻击嫌疑 (CPS: " + newCount + ", 限制: " + MAX_CPS + ", VL: " + vl + ")"
            );
        }

        // 检测一致性点击模式（自动点击器）
        if (newCount > 8) { // 只在较高CPS时检测模式
            checkClickPattern(player, currentTime, data);
        }
    }

    private void checkReach(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        double distance = player.getLocation().distance(event.getEntity().getLocation());

        // 考虑碰撞箱
        double entityWidth = getEntityWidth(event.getEntity());
        double actualMaxReach = MAX_REACH + entityWidth;

        if (distance > actualMaxReach) {
            data.addViolation("reach", 4);
            int vl = data.getViolationLevel("reach");

            plugin.getAlertManager().alertStaff(
                    player,
                    "长臂攻击嫌疑 (距离: " + String.format("%.2f", distance) +
                            ", 限制: " + String.format("%.2f", actualMaxReach) + ", VL: " + vl + ")"
            );
        }
    }

    private void checkKillAura(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        UUID targetId = event.getEntity().getUniqueId();

        // 记录攻击的实体
        Set<UUID> attacked = attackedEntities.computeIfAbsent(playerId, ignored -> new HashSet<>());
        attacked.add(targetId);

        // 检测短时间内攻击多个实体
        long currentTime = System.currentTimeMillis();
        Long lastAttack = lastAttackTime.get(playerId);

        if (lastAttack != null && currentTime - lastAttack < 500) { // 500ms内
            if (attacked.size() > 2) {
                data.addViolation("killaura", 8);
                plugin.getAlertManager().alertStaff(
                        player,
                        "KillAura嫌疑 (短时间内攻击 " + attacked.size() + " 个实体, VL: " +
                                data.getViolationLevel("killaura") + ")"
                );
            }
        }
    }

    private void checkAimbot(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        Location playerLoc = player.getLocation();
        Location targetLoc = event.getEntity().getLocation();

        // 计算理想的角度
        double deltaX = targetLoc.getX() - playerLoc.getX();
        double deltaZ = targetLoc.getZ() - playerLoc.getZ();
        double idealYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));

        // 获取玩家实际的角度
        double actualYaw = playerLoc.getYaw();

        // 标准化角度
        idealYaw = normalizeYaw(idealYaw);
        actualYaw = normalizeYaw(actualYaw);

        // 检查角度差异
        double angleDiff = Math.abs(idealYaw - actualYaw);

        Double lastYaw = lastAttackYaw.get(playerId);
        lastAttackYaw.put(playerId, actualYaw);

        // 检测异常角度变化（自瞄特征）
        if (lastYaw != null) {
            double yawChange = Math.abs(actualYaw - lastYaw);
            yawChange = Math.min(yawChange, 360 - yawChange); // 最短角度变化

            if (yawChange > MAX_ANGLE_CHANGE && angleDiff < 5.0) {
                // 角度变化过大但精度极高，可能是自瞄
                data.addViolation("aimbot", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "自瞄嫌疑 (角度变化: " + String.format("%.1f", yawChange) +
                                "°, 精度: " + String.format("%.1f", angleDiff) + "°, VL: " +
                                data.getViolationLevel("aimbot") + ")"
                );
            }
        }

        // 检测完美角度（过于精确）
        if (angleDiff < 1.0) {
            data.addViolation("perfect_aim", 2);
        }
    }

    private void checkMultiAttack(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        // 检测同时攻击多个实体
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        Location currentAttackLoc = lastAttackLocation.get(playerId);
        lastAttackLocation.put(playerId, player.getLocation());

        if (currentAttackLoc != null) {
            // 检查攻击位置是否异常变化
            double distanceMoved = currentAttackLoc.distance(player.getLocation());

            // 如果玩家移动距离很小但攻击了不同方向的实体
            if (distanceMoved < 0.5) {
                Set<UUID> attacked = attackedEntities.get(playerId);
                if (attacked != null && attacked.size() > 1) {
                    data.addViolation("multi_attack", 5);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "多重攻击嫌疑 (攻击 " + attacked.size() + " 个实体, 移动 " +
                                    String.format("%.2f", distanceMoved) + " 格, VL: " +
                                    data.getViolationLevel("multi_attack") + ")"
                    );
                }
            }
        }
    }

    private void checkHitWhileMoving(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        // 检测移动中攻击的准确性
        if (player.isSprinting() || player.isGliding()) {
            Location playerLoc = player.getLocation();
            Location targetLoc = event.getEntity().getLocation();

            double distance = playerLoc.distance(targetLoc);
            double deltaX = targetLoc.getX() - playerLoc.getX();
            double deltaZ = targetLoc.getZ() - playerLoc.getZ();
            double idealYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
            double actualYaw = normalizeYaw(playerLoc.getYaw());
            idealYaw = normalizeYaw(idealYaw);

            double angleDiff = Math.abs(idealYaw - actualYaw);

            // 高速移动中精度过高可能是作弊
            if (angleDiff < 2.0 && distance > 3.0) {
                data.addViolation("moving_accuracy", 3);
                plugin.getAlertManager().alertStaff(
                        player,
                        "移动中异常精度 (距离: " + String.format("%.1f", distance) +
                                ", 角度差: " + String.format("%.1f", angleDiff) + "°, VL: " +
                                data.getViolationLevel("moving_accuracy") + ")"
                );
            }
        }
    }

    private void checkAutoClicker(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastClick = lastAttackTime.get(playerId);

        if (lastClick != null) {
            long clickInterval = currentTime - lastClick;

            // 检测过于一致的点击间隔（自动点击器特征）
            if (clickInterval > 0 && clickInterval < 100) { // 极快点击
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("autoclicker_pattern", 1);

                if (data.getViolationLevel("autoclicker_pattern") > 20) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "自动点击器模式嫌疑 (VL: " +
                                    data.getViolationLevel("autoclicker_pattern") + ")"
                    );
                }
            }
        }
    }

    private void checkClickPattern(Player player, long currentTime, PlayerData data) {
        // 检测点击模式的一致性（自动点击器通常有非常一致的间隔）
        // 这里可以实现更复杂的模式检测算法
        UUID playerId = player.getUniqueId();
        Long lastAttack = lastAttackTime.get(playerId);

        if (lastAttack != null) {
            long averageInterval = (currentTime - lastAttack) /
                    attackCount.getOrDefault(playerId, 1);

            // 如果间隔非常一致，可能是自动点击器
            if (averageInterval > 0 && averageInterval < 80) { // 非常快的稳定点击
                data.addViolation("consistent_cps", 2);
            }
        }
    }

    // 暴击检测 - 检测异常连续暴击
    private void checkCriticalHits(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        if (isCriticalHit(player)) {
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();

            int critCount = criticalHitCount.getOrDefault(playerId, 0) + 1;
            criticalHitCount.put(playerId, critCount);

            Long lastCrit = lastCriticalHit.get(playerId);
            lastCriticalHit.put(playerId, currentTime);

            if (lastCrit != null) {
                long timeSinceLastCrit = currentTime - lastCrit;

                // 检测连续暴击
                if (timeSinceLastCrit < 1000) { // 1秒内
                    if (critCount > 3) {
                        data.addViolation("critical_spam", 4);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "异常连续暴击: " + critCount + " 次/秒"
                        );
                    }
                } else {
                    // 重置计数器
                    criticalHitCount.put(playerId, 1);
                }
            }

            // 检测暴击概率
            double critChance = calculateCriticalChance(player, data);
            if (critChance > 0.8) { // 80%以上暴击率
                data.addViolation("critical_probability", 3);
                if (data.getViolationLevel("critical_probability") > 15) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "异常暴击概率: " + String.format("%.1f", critChance * 100) + "%"
                    );
                }
            }
        }
    }

    // 增强的穿墙攻击检测
    private void checkEnhancedWallHit(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        Location playerLoc = player.getLocation();
        Location targetLoc = event.getEntity().getLocation();

        // 多层穿墙检测
        if (!hasLineOfSightAdvanced(player, event.getEntity())) {
            data.addViolation("wall_hit_advanced", 8);
            plugin.getAlertManager().alertStaff(player, "高级穿墙攻击检测");
            return;
        }

        // 检测通过薄墙攻击
        if (isThinWallBetween(playerLoc, targetLoc)) {
            data.addViolation("thin_wall_hit", 6);
            plugin.getAlertManager().alertStaff(player, "薄墙穿透攻击");
        }

        // 检测角落攻击
        if (isCornerHit(playerLoc, targetLoc)) {
            data.addViolation("corner_hit", 4);
            plugin.getAlertManager().alertStaff(player, "异常角落攻击");
        }
    }

    // 连击检测 - 检测过于规律的攻击间隔
    private void checkComboPattern(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        LinkedList<Long> attacks = data.attackTimestamps;
        if (attacks.size() < 8) return;

        // 分析攻击间隔模式
        List<Long> intervals = new ArrayList<>();
        Iterator<Long> iterator = attacks.iterator();
        long prev = iterator.next();

        while (iterator.hasNext()) {
            long current = iterator.next();
            intervals.add(current - prev);
            prev = current;
        }

        // 检测过于规律的间隔（自动点击器）
        double regularity = calculateAttackRegularity(intervals);
        if (regularity > 0.95) {
            data.addViolation("combo_regularity", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "规律连击模式: " + String.format("%.1f", regularity * 100) + "% 规律性"
            );
        }

        // 检测人类不可能达到的精度
        if (hasImpossiblePrecision(intervals)) {
            data.addViolation("impossible_precision", 8);
            plugin.getAlertManager().alertStaff(player, "不可能的攻击精度");
        }
    }

    // 随机战斗检查 - 随机检查攻击模式
    private void performRandomCombatCheck(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        int counter = randomCombatCheck.getOrDefault(playerId, 0) + 1;
        randomCombatCheck.put(playerId, counter);

        // 随机执行检查
        if (counter > combatRandom.nextInt(20) + 15) {
            randomCombatCheck.put(playerId, 0);

            int checkType = combatRandom.nextInt(5);
            switch (checkType) {
                case 0:
                    checkAimConsistency(player, event, data);
                    break;
                case 1:
                    checkDamageConsistency(player, event, data);
                    break;
                case 2:
                    checkAttackAngle(player, event, data);
                    break;
                case 3:
                    checkWeaponSwitchPattern(player, event, data);
                    break;
                case 4:
                    checkComboLength(player, event, data);
                    break;
            }
        }
    }

    // 瞄准一致性检测
    private void checkAimConsistency(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        Location playerLoc = player.getLocation();
        Location targetLoc = event.getEntity().getLocation();

        // 计算理想瞄准角度
        double deltaX = targetLoc.getX() - playerLoc.getX();
        double deltaZ = targetLoc.getZ() - playerLoc.getZ();
        double idealYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double actualYaw = normalizeYaw(playerLoc.getYaw());
        idealYaw = normalizeYaw(idealYaw);

        double angleDiff = Math.abs(idealYaw - actualYaw);

        // 检测异常一致的瞄准精度
        if (angleDiff < 0.1) { // 0.1度精度
            data.addViolation("aim_consistency", 2);
            if (data.getViolationLevel("aim_consistency") > 20) {
                plugin.getAlertManager().alertStaff(player, "异常瞄准一致性");
            }
        }
    }

    // 伤害一致性检测
    private void checkDamageConsistency(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        // 检测伤害值的异常一致性
        double damage = event.getDamage();

        // 这里可以记录历史伤害值并分析模式
        // 过于一致的伤害值可能是作弊特征
    }

    // 攻击角度检测
    private void checkAttackAngle(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        Location playerLoc = player.getLocation();
        Vector viewDirection = playerLoc.getDirection();
        Vector toTarget = event.getEntity().getLocation().toVector().subtract(playerLoc.toVector()).normalize();

        double dot = viewDirection.dot(toTarget);
        double angle = Math.acos(dot) * 180 / Math.PI;

        // 检测异常攻击角度
        if (angle > 90) { // 90度以外攻击
            data.addViolation("attack_angle", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "异常攻击角度: " + String.format("%.1f", angle) + "°"
            );
        }
    }

    // 武器切换模式检测
    private void checkWeaponSwitchPattern(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        // 检测攻击前后的武器切换模式
        // 某些作弊软件有特定的切换模式
    }

    // 连击长度检测
    private void checkComboLength(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        int comboLength = data.attackTimestamps.size();

        // 检测异常长的连击
        if (comboLength > 20) {
            data.addViolation("long_combo", 3);
            if (data.getViolationLevel("long_combo") > 10) {
                plugin.getAlertManager().alertStaff(
                        player,
                        "异常连击长度: " + comboLength + " 次"
                );
            }
        }
    }
    private void checkStrictKillAura(Player player, EntityDamageByEntityEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        UUID targetId = event.getEntity().getUniqueId();

        // 记录攻击时间
        long currentTime = System.currentTimeMillis();
        Long lastHit = lastHitTime.get(playerId);

        // 检测异常快速的连续攻击
        if (lastHit != null && currentTime - lastHit < 50) { // 20ms 内连续攻击
            int violations = killAuraViolations.getOrDefault(playerId, 0) + 1;
            killAuraViolations.put(playerId, violations);

            if (violations > 2) {
                data.addViolation("strict_killaura", 12);
                plugin.getAlertManager().alertStaff(
                        player,
                        "严格 KillAura 检测 (连续快速攻击: " + violations + " 次)"
                );
            }
        } else {
            killAuraViolations.put(playerId, 0);
        }

        lastHitTime.put(playerId, currentTime);

        // 检测攻击角度一致性
        checkAimbot(player, event, data);

        // 检测攻击距离
        checkReach(player, event, data);
    }


    // ========== 工具方法 ==========

    private double getEntityWidth(Entity entity) {
        if (entity instanceof LivingEntity) {
            return ((LivingEntity) entity).getWidth();
        }
        return 0.6; // 默认宽度
    }

    private double normalizeYaw(double yaw) {
        yaw %= 360.0;
        if (yaw < 0) {
            yaw += 360.0;
        }
        return yaw;
    }

    private boolean isCriticalHit(Player player) {
        // 检测暴击的简化方法
        return !player.isOnGround() && player.getFallDistance() > 0;
    }

    private double calculateCriticalChance(Player player, PlayerData data) {
        int totalHits = data.attackTimestamps.size();
        int criticalHits = criticalHitCount.getOrDefault(player.getUniqueId(), 0);

        if (totalHits == 0) return 0;
        return (double) criticalHits / totalHits;
    }

    private boolean hasLineOfSightAdvanced(Player player, Entity target) {
        // 增强的视线检测，考虑多种障碍物
        Location start = player.getEyeLocation();
        Location end = target.getLocation().add(0, 1, 0); // 目标中心偏上

        // 简单的射线检测
        double distance = start.distance(end);
        Vector direction = end.toVector().subtract(start.toVector()).normalize();

        for (double d = 0.5; d < distance; d += 0.5) {
            Location checkLoc = start.clone().add(direction.clone().multiply(d));
            Block block = checkLoc.getBlock();

            if (block.getType().isSolid() && !isTransparentBlock(block.getType())) {
                return false;
            }
        }

        return true;
    }

    private boolean isThinWallBetween(Location loc1, Location loc2) {
        // 检测薄墙（如玻璃板、铁栏杆等）
        double distance = loc1.distance(loc2);
        Vector direction = loc2.toVector().subtract(loc1.toVector()).normalize();

        int thinWallCount = 0;
        for (double d = 0.5; d < distance; d += 0.5) {
            Location checkLoc = loc1.clone().add(direction.clone().multiply(d));
            Block block = checkLoc.getBlock();

            if (isThinBlock(block.getType())) {
                thinWallCount++;
            }
        }

        return thinWallCount > 2; // 穿过多个薄墙块
    }

    private boolean isCornerHit(Location playerLoc, Location targetLoc) {
        // 检测是否从角落攻击
        // 简化实现：检查玩家和目标之间是否有墙角
        return false;
    }

    private double calculateAttackRegularity(List<Long> intervals) {
        if (intervals.size() < 2) return 0;

        // 计算间隔的一致性
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double sumSqDiff = intervals.stream().mapToDouble(i -> Math.pow(i - mean, 2)).sum();
        double variance = sumSqDiff / intervals.size();
        double stdDev = Math.sqrt(variance);

        // 标准差越小，规律性越高
        return 1.0 - (stdDev / mean);
    }

    private boolean hasImpossiblePrecision(List<Long> intervals) {
        // 检测人类不可能达到的点击精度
        // 例如所有间隔都是完全相同的毫秒数
        return intervals.stream().distinct().count() == 1 && intervals.size() > 5;
    }

    private boolean isTransparentBlock(Material material) {
        return material == Material.GLASS || material == Material.WATER ||
                material == Material.ICE || material.toString().contains("LEAVES");
    }

    private boolean isThinBlock(Material material) {
        return material.toString().contains("PANE") || material.toString().contains("FENCE") ||
                material == Material.IRON_BARS || material == Material.GLASS_PANE;
    }

    private int totalChecks = 0;
    public int getTotalChecks() { return totalChecks; }
}
