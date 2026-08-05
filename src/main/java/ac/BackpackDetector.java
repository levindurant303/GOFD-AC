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

public class BackpackDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastInventoryClick = new HashMap<>();
    private final HashMap<UUID, Integer> clickCount = new HashMap<>();
    private final HashMap<UUID, Long> lastShiftClick = new HashMap<>();
    private final HashMap<UUID, Integer> shiftClickCount = new HashMap<>();
    private final HashMap<UUID, Long> lastItemMove = new HashMap<>();
    private final HashMap<UUID, Long> lastInventoryScan = new HashMap<>();
    private final HashMap<UUID, Set<Material>> suspiciousItemsFound = new HashMap<>();

    // 可疑物品列表
    private final Set<Material> suspiciousItems = Set.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.STRUCTURE_VOID
    );

    public BackpackDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        if (player.hasPermission("gofdac.bypass")) return;

        checkClickSpeed(player, event);
        checkFastMove(player, event);
        checkIllegalTransfer(player, event);
        checkAutoSort(player, event);
        checkSuspiciousInventory(player, event);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        checkSuspiciousItems(player, event.getInventory());
    }

    // 点击速度检测 - 检测背包异常点击速度
    private void checkClickSpeed(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastClick = lastInventoryClick.get(playerId);

        if (lastClick != null) {
            long timeSinceLastClick = currentTime - lastClick;

            if (timeSinceLastClick < 50) { // 20 CPS
                int count = clickCount.getOrDefault(playerId, 0) + 1;
                clickCount.put(playerId, count);

                if (count > 10) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("inventory_click_speed", 4);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "背包异常点击速度: " + count + " 次连续快速点击"
                    );
                }
            } else {
                clickCount.put(playerId, 1);
            }
        } else {
            clickCount.put(playerId, 1);
        }

        lastInventoryClick.put(playerId, currentTime);
    }

    // 快速移动检测 - 检测Shift快速移动物品
    private void checkFastMove(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        if (event.isShiftClick()) {
            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            Long lastShift = lastShiftClick.get(playerId);

            if (lastShift != null) {
                long timeSinceLastShift = currentTime - lastShift;

                if (timeSinceLastShift < 100) { // 100ms内连续Shift点击
                    int count = shiftClickCount.getOrDefault(playerId, 0) + 1;
                    shiftClickCount.put(playerId, count);

                    if (count > 5) {
                        PlayerData data = plugin.getPlayerData(player);
                        data.addViolation("fast_shift_click", 3);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "快速Shift移动物品: " + count + " 次连续"
                        );
                    }
                } else {
                    shiftClickCount.put(playerId, 1);
                }
            } else {
                shiftClickCount.put(playerId, 1);
            }

            lastShiftClick.put(playerId, currentTime);
        }
    }

    // 非法转移检测 - 检测尝试转移非法物品
    private void checkIllegalTransfer(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        ItemStack currentItem = event.getCurrentItem();
        ItemStack cursorItem = event.getCursor();

        // 检查当前物品
        if (currentItem != null && isIllegalItem(currentItem)) {
            handleIllegalItem(player, currentItem, "inventory_click");
            event.setCancelled(true);
        }

        // 检查光标物品
        if (cursorItem != null && isIllegalItem(cursorItem)) {
            handleIllegalItem(player, cursorItem, "cursor_item");
            event.setCancelled(true);
        }

        // 检测创造模式物品转移到生存模式
        if (player.getGameMode() == GameMode.SURVIVAL) {
            if (isCreativeOnlyItem(currentItem) || isCreativeOnlyItem(cursorItem)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("creative_item_transfer", 10);
                plugin.getAlertManager().alertStaff(player, "尝试转移创造模式专属物品");
                event.setCancelled(true);
            }
        }
    }

    // 自动整理检测 - 检测自动整理作弊
    private void checkAutoSort(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        // 检测异常快速的物品整理模式
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastMove = lastItemMove.get(playerId);

        if (lastMove != null) {
            long timeSinceLastMove = currentTime - lastMove;

            // 检测过于规律的移动间隔（自动整理特征）
            if (timeSinceLastMove > 0 && timeSinceLastMove < 80) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("auto_sort_pattern", 2);

                if (data.getViolationLevel("auto_sort_pattern") > 15) {
                    plugin.getAlertManager().alertStaff(player, "疑似自动整理作弊");
                }
            }
        }

        lastItemMove.put(playerId, currentTime);

        // 检测特定排序模式
        if (isSuspiciousSortPattern(event)) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("suspicious_sort", 3);
        }
    }

    // 可疑背包检测 - 检测背包中的可疑物品
    private void checkSuspiciousInventory(Player player, org.bukkit.event.inventory.InventoryClickEvent event) {
        // 定期检查整个背包中的可疑物品
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        long lastScan = lastInventoryScan.getOrDefault(playerId, 0L);
        if (now - lastScan >= 10000L) { // 每10秒检查一次
            lastInventoryScan.put(playerId, now);
            checkAllSuspiciousItems(player);
        }
    }

    // 检查所有可疑物品
    private void checkAllSuspiciousItems(Player player) {
        Set<Material> foundItems = new HashSet<>();

        // 检查主背包
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isSuspiciousItem(item)) {
                foundItems.add(item.getType());
            }
        }

        // 检查末影箱
        if (player.getEnderChest() != null) {
            for (ItemStack item : player.getEnderChest().getContents()) {
                if (item != null && isSuspiciousItem(item)) {
                    foundItems.add(item.getType());
                }
            }
        }

        if (!foundItems.isEmpty()) {
            Set<Material> previouslyFound = suspiciousItemsFound.getOrDefault(
                    player.getUniqueId(), new HashSet<>());

            // 检查新发现的可疑物品
            for (Material material : foundItems) {
                if (!previouslyFound.contains(material)) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("suspicious_item_found", 5);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "发现可疑物品: " + material
                    );
                }
            }

            suspiciousItemsFound.put(player.getUniqueId(), foundItems);
        }
    }

    // 关闭库存时检查可疑物品
    private void checkSuspiciousItems(Player player, org.bukkit.inventory.Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (item != null && isSuspiciousItem(item)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_item_in_inventory", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "库存中发现可疑物品: " + item.getType()
                );
                break;
            }
        }
    }

    // 工具方法
    private boolean isIllegalItem(ItemStack item) {
        if (item == null) return false;

        // 检测非法附魔
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry :
                    item.getEnchantments().entrySet()) {
                if (entry.getValue() > entry.getKey().getMaxLevel() + 10) {
                    return true;
                }
            }
        }

        // 检测非法堆叠
        if (item.getAmount() > item.getMaxStackSize() * 2) {
            return true;
        }

        return false;
    }

    private boolean isCreativeOnlyItem(ItemStack item) {
        if (item == null) return false;

        Material material = item.getType();
        return material == Material.COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW || material == Material.BARRIER ||
                material.toString().contains("SPAWN_EGG");
    }

    private boolean isSuspiciousItem(ItemStack item) {
        return suspiciousItems.contains(item.getType());
    }

    private boolean isSuspiciousSortPattern(org.bukkit.event.inventory.InventoryClickEvent event) {
        // 检测特定的自动整理模式
        // 例如：所有物品按特定顺序排列
        return false;
    }

    private void handleIllegalItem(Player player, ItemStack item, String context) {
        PlayerData data = plugin.getPlayerData(player);
        data.addViolation("illegal_item_" + context, 8);
        plugin.getAlertManager().alertStaff(
                player,
                "非法物品操作: " + item.getType() + " (上下文: " + context + ")"
        );

        // 记录物品信息以便进一步调查
        plugin.getLogger().warning("玩家 " + player.getName() + " 操作非法物品: " +
                item.getType() + " x" + item.getAmount());
    }

    public void cleanup(UUID playerId) {
        lastInventoryClick.remove(playerId);
        clickCount.remove(playerId);
        lastShiftClick.remove(playerId);
        shiftClickCount.remove(playerId);
        lastItemMove.remove(playerId);
        lastInventoryScan.remove(playerId);
        suspiciousItemsFound.remove(playerId);
    }
}
