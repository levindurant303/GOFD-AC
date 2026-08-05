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
import org.bukkit.entity.Player;
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

public class PlayerData {
    private final UUID playerId;
    private final Map<String, Integer> violationLevels;
    private final Map<String, Long> lastAlertTime;
    private long lastMoveTime;
    private int combatViolations = 0;
    private int movementViolations = 0;
    private int itemViolations = 0;
    private int totalViolations = 0;

    // 位置跟踪
    private double lastX, lastY, lastZ;
    private long lastPositionTime;
    private boolean positionInitialized;

    // 新增：攻击历史记录
    LinkedList<Long> attackTimestamps = new LinkedList<>();
    private LinkedList<Location> movementHistory = new LinkedList<>();

    public PlayerData(Player player) {
        this(player.getUniqueId());
    }

    public PlayerData(UUID playerId) {
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.violationLevels = new HashMap<>();
        this.lastAlertTime = new HashMap<>();
        this.lastMoveTime = System.currentTimeMillis();
    }

    public synchronized void addViolation(String type, int amount) {
        if (type == null || type.isBlank() || amount == 0) {
            return;
        }
        int current = violationLevels.getOrDefault(type, 0);
        int next = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) current + amount));
        int delta = next - current;
        violationLevels.put(type, next);
        totalViolations = (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) totalViolations + delta));

        // 统计分类违规
        if (type.startsWith("combat") || type.contains("cps") || type.contains("reach")) {
            combatViolations = boundedAdd(combatViolations, delta);
        } else if (type.startsWith("movement") || type.contains("speed") || type.contains("flight")) {
            movementViolations = boundedAdd(movementViolations, delta);
        } else if (type.startsWith("item")) {
            itemViolations = boundedAdd(itemViolations, delta);
        }
    }

    public synchronized void reduceViolationLevels() {
        // 每秒钟减少1点违规值，最低为0
        for (Map.Entry<String, Integer> entry : violationLevels.entrySet()) {
            if (entry.getValue() > 0) {
                entry.setValue(entry.getValue() - 1);
            }
        }
    }

    public synchronized int getViolationLevel(String type) {
        return violationLevels.getOrDefault(type, 0);
    }

    public synchronized HashMap<String, Integer> getViolationLevels() {
        return new HashMap<>(violationLevels);
    }

    // 位置追踪方法
    public synchronized void updatePosition(Location location) {
        Objects.requireNonNull(location, "location");
        this.lastX = location.getX();
        this.lastY = location.getY();
        this.lastZ = location.getZ();
        this.lastPositionTime = System.currentTimeMillis();
        this.positionInitialized = true;

        // 记录移动历史（最多50个点）
        movementHistory.add(location.clone());
        if (movementHistory.size() > 50) {
            movementHistory.removeFirst();
        }
    }

    public synchronized double getDistanceMoved(double x, double y, double z) {
        if (!positionInitialized) {
            return 0.0D;
        }
        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    // 新增：记录攻击时间
    public synchronized void recordAttack() {
        long currentTime = System.currentTimeMillis();
        attackTimestamps.add(currentTime);

        // 只保留最近5秒的攻击记录
        while (!attackTimestamps.isEmpty() && currentTime - attackTimestamps.getFirst() > 5000) {
            attackTimestamps.removeFirst();
        }
    }

    // 新增：计算CPS
    public synchronized double getCPS() {
        long currentTime = System.currentTimeMillis();
        // 计算最近1秒内的攻击次数
        int recent = 0;
        for (Long timestamp : attackTimestamps) {
            if (currentTime - timestamp < 1000) {
                recent++;
            }
        }
        return recent;
    }

    // 新增：获取移动历史
    public synchronized List<Location> getMovementHistory() {
        return new ArrayList<>(movementHistory);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    private static int boundedAdd(int current, int delta) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, (long) current + delta));
    }

    // Getter和Setter方法
    public synchronized long getLastMoveTime() { return lastMoveTime; }
    public synchronized void setLastMoveTime(long time) { this.lastMoveTime = time; }
    public synchronized int getCombatViolations() { return combatViolations; }
    public synchronized int getMovementViolations() { return movementViolations; }
    public synchronized int getItemViolations() { return itemViolations; }
    public synchronized int getTotalViolations() { return totalViolations; }
}
