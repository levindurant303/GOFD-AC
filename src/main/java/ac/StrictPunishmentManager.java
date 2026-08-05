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

public class StrictPunishmentManager {
    private final gofd plugin;
    private final Set<UUID> bannedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> tempBans = new ConcurrentHashMap<>();

    // 惩罚阈值
    private final int KICK_THRESHOLD = 15;
    private final int TEMP_BAN_THRESHOLD = 25;
    private final int PERMA_BAN_THRESHOLD = 50;

    public StrictPunishmentManager(gofd plugin) {
        this.plugin = plugin;
    }

    public void checkStrictPunishment(Player player, PlayerData data) {
        if (plugin.getSettings() == null || !plugin.getSettings().punishmentEnabled()) {
            return;
        }
        int totalVL = data.getTotalViolations();

        // 基于总违规值的惩罚
        if (plugin.getSettings().autoBan() && totalVL > plugin.getSettings().banThreshold()) {
            permabanPlayer(player, "多次严重违规行为");
        } else if (plugin.getSettings().autoKick() && totalVL > TEMP_BAN_THRESHOLD) {
            tempBanPlayer(player, "严重违规行为", 1440); // 24小时
        } else if (plugin.getSettings().autoKick() && totalVL > plugin.getSettings().kickThreshold()) {
            kickPlayer(player, "违规行为检测");
        }

        // 基于特定违规类型的惩罚
        checkSpecificViolations(player, data);
    }

    private void checkSpecificViolations(Player player, PlayerData data) {
        // 飞行作弊 - 直接封禁
        if (data.getViolationLevel("wurst_flight_strict") > 5) {
            permabanPlayer(player, "飞行作弊");
            return;
        }

        // KillAura - 直接封禁
        if (data.getViolationLevel("killaura_strict") > 3) {
            permabanPlayer(player, "KillAura作弊");
            return;
        }

        // 穿墙 - 直接封禁
        if (data.getViolationLevel("wall_hit_strict") > 2) {
            permabanPlayer(player, "穿墙作弊");
            return;
        }

        // NoFall - 临时封禁
        if (data.getViolationLevel("nofall_cheating") > 5) {
            tempBanPlayer(player, "NoFall作弊", 720); // 12小时
            return;
        }

        // 自动点击器 - 临时封禁
        if (data.getViolationLevel("autoclicker_ultra") > 3) {
            tempBanPlayer(player, "自动点击器", 360); // 6小时
            return;
        }
    }

    private void kickPlayer(Player player, String reason) {
        if (!bannedPlayers.contains(player.getUniqueId())) {
            player.kick(Component.text("你已被踢出服务器\n原因: " + reason).color(NamedTextColor.RED));
            plugin.getAlertManager().alertStaff(player, "已踢出玩家 - " + reason);
        }
    }

    private void tempBanPlayer(Player player, String reason, int minutes) {
        UUID playerId = player.getUniqueId();

        if (!bannedPlayers.contains(playerId)) {
            tempBans.put(playerId, System.currentTimeMillis() + (minutes * 60 * 1000));
            player.kick(Component.text("你已被临时封禁\n原因: " + reason +
                    "\n解封时间: " + minutes + "分钟后").color(NamedTextColor.RED));

            plugin.getAlertManager().alertStaff(player,
                    "已临时封禁玩家 (" + minutes + "分钟) - " + reason);
        }
    }

    private void permabanPlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();

        if (!bannedPlayers.contains(playerId)) {
            bannedPlayers.add(playerId);
            player.kick(Component.text("你已被永久封禁\n原因: " + reason).color(NamedTextColor.RED));

            plugin.getAlertManager().alertStaff(player, "已永久封禁玩家 - " + reason);

            // 记录到封禁列表
            recordBan(player, reason);
        }
    }

    private void recordBan(Player player, String reason) {
        String timestamp = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.now());
        String logEntry = String.format("[%s] %s (%s) - %s%n",
                timestamp, player.getName(), player.getUniqueId(), reason);
        plugin.getFileService().append(
                new File(plugin.getDataFolder(), "bans.log").toPath(), logEntry);
    }

    // 检查玩家是否被封禁
    public boolean isPlayerBanned(Player player) {
        UUID playerId = player.getUniqueId();

        if (bannedPlayers.contains(playerId)) {
            return true;
        }

        // 检查临时封禁
        Long unbanTime = tempBans.get(playerId);
        if (unbanTime != null) {
            if (System.currentTimeMillis() < unbanTime) {
                return true;
            } else {
                tempBans.remove(playerId); // 封禁时间已过
            }
        }

        return false;
    }
}
