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

public class ChatDetector implements Listener {
    private final gofd plugin;
    private final Map<UUID, java.util.concurrent.ConcurrentLinkedDeque<Long>> chatHistory = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastMessage = new ConcurrentHashMap<>();

    // 敏感词过滤
    private final Set<String> blockedWords = Set.of(
            "www.", ".com", ".net", ".org", "discord.gg", "作弊", "hack"
    );

    public ChatDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase(Locale.ROOT);

        // 检测刷屏
        if (checkSpam(player)) {
            event.setCancelled(true);
            applyViolation(player, "chat_spam", 2, "聊天刷屏嫌疑", "发言过于频繁，请稍后再试");
            return;
        }

        // 检测广告
        if (checkAdvertisement(message)) {
            event.setCancelled(true);
            applyViolation(player, "chat_advertisement", 3,
                    "发送广告: " + message, "请勿发送广告信息");
            return;
        }

        // 检测重复消息
        if (checkRepeatMessage(player, message)) {
            event.setCancelled(true);
            applyViolation(player, "chat_repeat", 1, null, "请勿重复发送相同消息");
            return;
        }

        // 记录聊天历史
        recordChat(player);
        lastMessage.put(player.getUniqueId(), message);
    }

    private boolean checkSpam(Player player) {
        UUID playerId = player.getUniqueId();
        java.util.concurrent.ConcurrentLinkedDeque<Long> history = chatHistory.computeIfAbsent(
                playerId, ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        long currentTime = System.currentTimeMillis();

        // 清理超过10秒的记录
        while (true) {
            Long first = history.peekFirst();
            if (first == null || currentTime - first <= 10000) {
                break;
            }
            history.pollFirst();
        }

        // 检查10秒内是否超过5条消息
        return history.size() >= 5;
    }

    private boolean checkAdvertisement(String message) {
        return blockedWords.stream().anyMatch(message::contains);
    }

    private boolean checkRepeatMessage(Player player, String message) {
        String lastMsg = lastMessage.get(player.getUniqueId());
        return lastMsg != null && lastMsg.equals(message);
    }

    private void recordChat(Player player) {
        UUID playerId = player.getUniqueId();
        java.util.concurrent.ConcurrentLinkedDeque<Long> history = chatHistory.computeIfAbsent(
                playerId, ignored -> new java.util.concurrent.ConcurrentLinkedDeque<>());
        history.add(System.currentTimeMillis());
    }

    private void applyViolation(Player player, String type, int amount, String alert, String feedback) {
        UUID playerId = player.getUniqueId();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !plugin.isEnabled()) {
                return;
            }
            if (feedback != null) {
                current.sendMessage(ChatColor.RED + feedback);
            }
            plugin.getPlayerData(current).addViolation(type, amount);
            if (alert != null) {
                plugin.getAlertManager().alertStaff(current, alert);
            }
        });
    }

    public void cleanup(UUID playerId) {
        chatHistory.remove(playerId);
        lastMessage.remove(playerId);
    }
}
