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

public class StrictMovementDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, MovementProfile> movementProfiles = new HashMap<>();
    private final HashMap<UUID, Integer> consecutiveViolations = new HashMap<>();

    // 极严格的阈值
    private final double MAX_WALK_SPEED_ULTRA = 0.5;
    private final double MAX_FLY_SPEED_ULTRA = 1.0;
    private final double MAX_VERTICAL_SPEED_ULTRA = 0.3;
    private final double MAX_JUMP_HEIGHT_ULTRA = 1.2;
    private final double MAX_ACCELERATION = 1.5;

    // Wurst 特定检测
    private final int WURST_FLIGHT_CONSECUTIVE_THRESHOLD = 2;
    private final int NO_FALL_CONSECUTIVE_THRESHOLD = 3;

    public StrictMovementDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!plugin.isEnableMovementChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        PlayerData data = plugin.getPlayerData(player);
        MovementProfile profile = movementProfiles.computeIfAbsent(
                player.getUniqueId(), k -> new MovementProfile()
        );

        // 执行所有严格检测
        checkUltraStrictFlight(player, event, data, profile);
        checkNoFallStrict(player, event, data, profile);
        checkSpeedStrict(player, event, data, profile);
        checkGroundSpoofing(player, event, data, profile);
        checkLiquidWalkStrict(player, event, data, profile);
        checkVehicleFly(player, event, data, profile);
        checkPhase(player, event, data, profile);
        checkTimer(player, event, data, profile);

        profile.update(event.getTo());
    }

    // 超严格飞行检测
    private void checkUltraStrictFlight(Player player, PlayerMoveEvent event,
                                        PlayerData data, MovementProfile profile) {
        if (player.getGameMode() == GameMode.CREATIVE || player.isGliding() ||
                player.isInsideVehicle() || player.isSwimming()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        double verticalChange = to.getY() - from.getY();

        // 检测任何异常垂直移动
        if (!player.isOnGround() && !player.isFlying()) {
            // 检测悬浮
            if (Math.abs(verticalChange) < 0.001 && profile.getAirTime() > 20) {
                data.addViolation("hover_strict", 8);
                plugin.getAlertManager().alertStaff(
                        player, "严格悬停检测 (空中时间: " + profile.getAirTime() + " ticks)"
                );
            }

            // 检测异常平滑的垂直移动（Wurst Flight）
            if (Math.abs(verticalChange) > 0.1) {
                double verticalConsistency = profile.getVerticalConsistency();
                if (verticalConsistency > 0.95) {
                    int violations = consecutiveViolations.getOrDefault(
                            player.getUniqueId(), 0) + 1;
                    consecutiveViolations.put(player.getUniqueId(), violations);

                    if (violations >= WURST_FLIGHT_CONSECUTIVE_THRESHOLD) {
                        data.addViolation("wurst_flight_strict", 15);
                        plugin.getAlertManager().alertStaff(
                                player, "Wurst Flight 严格检测 (一致性: " +
                                        String.format("%.1f", verticalConsistency * 100) + "%)"
                        );
                    }
                }
            }

            // 检测空中转向能力
            checkAirControl(player, event, data, profile);
        } else {
            consecutiveViolations.put(player.getUniqueId(), 0);
        }
    }

    // 严格 NoFall 检测
    private void checkNoFallStrict(Player player, PlayerMoveEvent event,
                                   PlayerData data, MovementProfile profile) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getY() > to.getY()) {
            double fallDistance = from.getY() - to.getY();

            // 任何从高处落下但没有摔落伤害都视为违规
            if (fallDistance > 3 && player.getFallDistance() < 0.5) {
                int violations = data.getViolationLevel("strict_nofall") + 1;
                data.addViolation("strict_nofall", 1);

                if (violations >= NO_FALL_CONSECUTIVE_THRESHOLD) {
                    data.addViolation("nofall_cheating", 20);
                    plugin.getAlertManager().alertStaff(
                            player, "NoFall 作弊检测 (连续 " + violations + " 次无摔落伤害)"
                    );
                }
            }
        }
    }

    // 严格速度检测
    private void checkSpeedStrict(Player player, PlayerMoveEvent event,
                                  PlayerData data, MovementProfile profile) {
        Location from = event.getFrom();
        Location to = event.getTo();

        double horizontalDistance = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) +
                        Math.pow(to.getZ() - from.getZ(), 2)
        );

        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDiff == 0) return;

        double speed = horizontalDistance / (timeDiff / 1000.0);
        double maxAllowedSpeed = calculateUltraMaxSpeed(player);

        // 极小的容差（5%）
        if (speed > maxAllowedSpeed * 1.05) {
            data.addViolation("speed_ultra_strict", 10);
            plugin.getAlertManager().alertStaff(
                    player, "超严格速度检测 (速度: " + String.format("%.3f", speed) +
                            ", 限制: " + String.format("%.3f", maxAllowedSpeed) + ")"
            );
        }

        // 检测加速度
        double acceleration = profile.getCurrentAcceleration();
        if (acceleration > MAX_ACCELERATION) {
            data.addViolation("impossible_acceleration", 12);
            plugin.getAlertManager().alertStaff(
                    player, "不可能加速度: " + String.format("%.2f", acceleration)
            );
        }
    }

    // 地面欺骗检测
    private void checkGroundSpoofing(Player player, PlayerMoveEvent event,
                                     PlayerData data, MovementProfile profile) {
        boolean serverOnGround = isOnGround(player);
        boolean clientOnGround = player.isOnGround();

        // 服务端和客户端地面状态不一致
        if (serverOnGround != clientOnGround) {
            data.addViolation("ground_spoofing", 15);
            plugin.getAlertManager().alertStaff(
                    player, "地面状态欺骗 (服务端: " + serverOnGround +
                            ", 客户端: " + clientOnGround + ")"
            );
        }
    }

    // 严格液体行走检测
    private void checkLiquidWalkStrict(Player player, PlayerMoveEvent event,
                                       PlayerData data, MovementProfile profile) {
        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();
        Material feet = loc.getBlock().getType();

        boolean inLiquid = below == Material.WATER || below == Material.LAVA ||
                feet == Material.WATER || feet == Material.LAVA;

        if (inLiquid && !player.isSwimming() && !isInBoat(player)) {
            // 检测液体中移动速度
            double speed = profile.getCurrentSpeed();
            if (speed > 0.2) {
                data.addViolation("liquid_walk_strict", 10);
                plugin.getAlertManager().alertStaff(
                        player, "液体行走严格检测 (速度: " + String.format("%.2f", speed) + ")"
                );
            }
        }
    }

    // 载具飞行检测
    private void checkVehicleFly(Player player, PlayerMoveEvent event,
                                 PlayerData data, MovementProfile profile) {
        if (!player.isInsideVehicle()) return;

        Vehicle vehicle = (Vehicle) player.getVehicle();
        Location vehicleLoc = vehicle.getLocation();

        // 检查载具是否在合理高度
        double groundLevel = findGroundLevel(vehicleLoc);
        if (vehicleLoc.getY() > groundLevel + 5) {
            data.addViolation("vehicle_fly", 15);
            plugin.getAlertManager().alertStaff(player, "载具飞行检测");
        }
    }

    // 穿墙检测
    private void checkPhase(Player player, PlayerMoveEvent event,
                            PlayerData data, MovementProfile profile) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 检测通过墙壁
        if (hasWallBetween(from, to)) {
            data.addViolation("phasing", 25);
            plugin.getAlertManager().alertStaff(player, "穿墙检测");
            event.setTo(from); // 传回原位置
        }
    }

    // Timer 检测
    private void checkTimer(Player player, PlayerMoveEvent event,
                            PlayerData data, MovementProfile profile) {
        // 检测异常的时间间隔模式
        double timeConsistency = profile.getTimeConsistency();
        if (timeConsistency > 0.98) { // 过于规律的时间间隔
            data.addViolation("timer_cheat", 18);
            plugin.getAlertManager().alertStaff(
                    player, "Timer 作弊检测 (时间一致性: " +
                            String.format("%.1f", timeConsistency * 100) + "%)"
            );
        }
    }

    // 空中控制检测
    private void checkAirControl(Player player, PlayerMoveEvent event,
                                 PlayerData data, MovementProfile profile) {
        if (!player.isOnGround() && !player.isFlying()) {
            double airControl = profile.getAirControlEfficiency();
            if (airControl > 0.8) { // 空中控制效率过高
                data.addViolation("air_control", 10);
                if (data.getViolationLevel("air_control") > 5) {
                    plugin.getAlertManager().alertStaff(
                            player, "异常空中控制: " + String.format("%.1f", airControl * 100) + "%"
                    );
                }
            }
        }
    }

    // 工具方法
    private double calculateUltraMaxSpeed(Player player) {
        double baseSpeed = player.isFlying() ? MAX_FLY_SPEED_ULTRA : MAX_WALK_SPEED_ULTRA;

        // 极严格的药水效果计算
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().equals(PotionEffectType.SPEED)) {
                    baseSpeed *= (1.0 + 0.1 * (effect.getAmplifier() + 1)); // 极低的加成
                }
            }
        }

        return Math.min(baseSpeed, MAX_WALK_SPEED_ULTRA * 1.1); // 绝对上限
    }

    private boolean isOnGround(Player player) {
        Location loc = player.getLocation();
        Location below = loc.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid();
    }

    private boolean isInBoat(Player player) {
        return player.isInsideVehicle() &&
                player.getVehicle().getType().toString().contains("BOAT");
    }

    private double findGroundLevel(Location loc) {
        for (int y = (int) loc.getY(); y > 0; y--) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType().isSolid()) {
                return y + 1;
            }
        }
        return 0;
    }

    private boolean hasWallBetween(Location from, Location to) {
        Vector direction = to.toVector().subtract(from.toVector());
        double distance = direction.length();
        direction.normalize();

        for (double d = 0.1; d < distance; d += 0.1) {
            Location checkLoc = from.clone().add(direction.clone().multiply(d));
            if (checkLoc.getBlock().getType().isSolid()) {
                return true;
            }
        }
        return false;
    }

    public void cleanup(UUID playerId) {
        movementProfiles.remove(playerId);
        consecutiveViolations.remove(playerId);
    }
}

// 移动档案类
