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

public class StrictCombatDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, CombatProfile> combatProfiles = new HashMap<>();

    // 极严格的阈值
    private final int MAX_CPS_ULTRA = 10;
    private final double MAX_REACH_ULTRA = 3.5;
    private final double MAX_ANGLE_ULTRA = 30.0;
    private final long MIN_ATTACK_INTERVAL = 100; // 毫秒

    public StrictCombatDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!plugin.isEnableCombatChecks()) return;
        if (!(event.getDamager() instanceof Player)) return;

        Player player = (Player) event.getDamager();
        if (player.hasPermission("gofdac.bypass")) return;

        PlayerData data = plugin.getPlayerData(player);
        CombatProfile profile = combatProfiles.computeIfAbsent(
                player.getUniqueId(), k -> new CombatProfile()
        );

        // 执行所有严格检测
        checkUltraStrictCPS(player, data, profile);
        checkStrictReach(player, event, data, profile);
        checkPerfectAim(player, event, data, profile);
        checkKillAuraStrict(player, event, data, profile);
        checkTriggerBotStrict(player, event, data, profile);
        checkAutoBlockStrict(player, event, data, profile);
        checkHitThroughWalls(player, event, data, profile);

        profile.recordAttack();
    }

    // 超严格 CPS 检测
    private void checkUltraStrictCPS(Player player, PlayerData data, CombatProfile profile) {
        double cps = profile.getCurrentCPS();

        if (cps > MAX_CPS_ULTRA) {
            data.addViolation("cps_ultra_strict", 12);
            plugin.getAlertManager().alertStaff(
                    player, "超严格 CPS 检测: " + String.format("%.1f", cps) + " CPS"
            );
        }

        // 检测点击模式
        double patternScore = profile.getClickPatternScore();
        if (patternScore > 0.95) {
            data.addViolation("autoclicker_ultra", 20);
            plugin.getAlertManager().alertStaff(
                    player, "自动点击器严格检测 (模式得分: " + String.format("%.1f", patternScore * 100) + "%)"
            );
        }
    }

    // 严格攻击距离检测
    private void checkStrictReach(Player player, EntityDamageByEntityEvent event,
                                  PlayerData data, CombatProfile profile) {
        double distance = player.getLocation().distance(event.getEntity().getLocation());

        if (distance > MAX_REACH_ULTRA) {
            data.addViolation("reach_ultra_strict", 15);
            plugin.getAlertManager().alertStaff(
                    player, "超严格长臂检测: " + String.format("%.2f", distance) + " 格"
            );
        }
    }

    // 完美瞄准检测
    private void checkPerfectAim(Player player, EntityDamageByEntityEvent event,
                                 PlayerData data, CombatProfile profile) {
        Location playerLoc = player.getLocation();
        Location targetLoc = event.getEntity().getLocation();

        double deltaX = targetLoc.getX() - playerLoc.getX();
        double deltaZ = targetLoc.getZ() - playerLoc.getZ();
        double idealYaw = Math.toDegrees(Math.atan2(-deltaX, deltaZ));
        double actualYaw = normalizeYaw(playerLoc.getYaw());
        idealYaw = normalizeYaw(idealYaw);

        double angleDiff = Math.abs(idealYaw - actualYaw);

        // 检测过于完美的瞄准
        if (angleDiff < 0.1) { // 0.1度精度
            data.addViolation("perfect_aim_strict", 10);
            plugin.getAlertManager().alertStaff(
                    player, "完美瞄准检测: " + String.format("%.2f", angleDiff) + "° 精度"
            );
        }

        // 检测角度锁定
        double aimConsistency = profile.getAimConsistency();
        if (aimConsistency > 0.98) {
            data.addViolation("aim_lock", 18);
            plugin.getAlertManager().alertStaff(
                    player, "角度锁定检测: " + String.format("%.1f", aimConsistency * 100) + "% 一致性"
            );
        }
    }

    // 严格 KillAura 检测
    private void checkKillAuraStrict(Player player, EntityDamageByEntityEvent event,
                                     PlayerData data, CombatProfile profile) {
        // 检测攻击多个实体
        int targetCount = profile.getRecentTargetCount();
        if (targetCount > 2) {
            data.addViolation("killaura_strict", 25);
            plugin.getAlertManager().alertStaff(
                    player, "KillAura 严格检测 (攻击 " + targetCount + " 个目标)"
            );
        }

        // 检测攻击角度变化
        double angleVariance = profile.getAttackAngleVariance();
        if (angleVariance < 5.0) { // 角度变化过小
            data.addViolation("killaura_angles", 15);
            plugin.getAlertManager().alertStaff(
                    player, "KillAura 角度检测: " + String.format("%.1f", angleVariance) + "° 方差"
            );
        }
    }

    // 严格 TriggerBot 检测
    private void checkTriggerBotStrict(Player player, EntityDamageByEntityEvent event,
                                       PlayerData data, CombatProfile profile) {
        // 检测反应时间
        long reactionTime = profile.getAverageReactionTime();
        if (reactionTime < 50) { // 50ms 反应时间
            data.addViolation("triggerbot_strict", 20);
            plugin.getAlertManager().alertStaff(
                    player, "TriggerBot 检测: " + reactionTime + "ms 反应时间"
            );
        }

        // 检测攻击时机
        double timingScore = profile.getAttackTimingScore();
        if (timingScore > 0.95) {
            data.addViolation("triggerbot_timing", 15);
            plugin.getAlertManager().alertStaff(
                    player, "TriggerBot 时机检测: " + String.format("%.1f", timingScore * 100) + "%"
            );
        }
    }

    // 严格自动格挡检测
    private void checkAutoBlockStrict(Player player, EntityDamageByEntityEvent event,
                                      PlayerData data, CombatProfile profile) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        boolean isBlocking = hand.getType().toString().contains("SHIELD") &&
                player.isBlocking();

        if (isBlocking) {
            long blockTime = profile.getBlockReactionTime();
            if (blockTime < 30) { // 30ms 格挡反应
                data.addViolation("autoblock_strict", 12);
                plugin.getAlertManager().alertStaff(
                        player, "自动格挡检测: " + blockTime + "ms 反应时间"
                );
            }
        }
    }

    // 穿墙攻击检测
    private void checkHitThroughWalls(Player player, EntityDamageByEntityEvent event,
                                      PlayerData data, CombatProfile profile) {
        if (!player.hasLineOfSight(event.getEntity())) {
            data.addViolation("wall_hit_strict", 30);
            plugin.getAlertManager().alertStaff(player, "严格穿墙攻击检测");
            event.setCancelled(true);
        }
    }

    private double normalizeYaw(double yaw) {
        yaw %= 360.0;
        if (yaw < 0) yaw += 360.0;
        return yaw;
    }

    public void cleanup(UUID playerId) {
        combatProfiles.remove(playerId);
    }
}

// 战斗档案类
