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

public class ItemDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastItemUse = new HashMap<>();
    private final HashMap<UUID, Long> lastConsumeTime = new HashMap<>();
    private final HashMap<UUID, Integer> consumeCount = new HashMap<>();
    private final HashMap<UUID, Map<Material, Integer>> foodSourceCount = new HashMap<>();
    private final Set<Material> suspiciousFoods = Set.of(
            Material.ENCHANTED_GOLDEN_APPLE, Material.GOLDEN_APPLE, Material.POTION
    );

    public ItemDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        // 检测快速使用物品
        checkFastUse(player, item);

        // 检测非法物品
        checkIllegalItems(player, item);

        // 检测自动喝药
        checkAutoPotion(player, item);

        // 新增检测
        checkSuspiciousEnchantments(player, item);
        checkIllegalStacks(player, item);
        checkCustomPotions(player, item);
        checkConsumptionSpeed(player, item);
        checkSuspiciousFood(player, item);
    }

    private void checkFastUse(Player player, ItemStack item) {
        if (item.getType().isEdible() || item.getType() == Material.POTION) {
            long currentTime = System.currentTimeMillis();
            Long lastUse = lastItemUse.get(player.getUniqueId());

            if (lastUse != null && currentTime - lastUse < 500) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("fast_use", 2);
                plugin.getAlertManager().alertStaff(player, "快速使用物品嫌疑");
            }

            lastItemUse.put(player.getUniqueId(), currentTime);
        }
    }

    private void checkIllegalItems(Player player, ItemStack item) {
        // 检测非法附魔
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry :
                    item.getEnchantments().entrySet()) {
                if (entry.getValue() > entry.getKey().getMaxLevel()) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("illegal_enchant", 5);
                    plugin.getAlertManager().alertStaff(player,
                            "非法附魔: " + entry.getKey().getKey().getKey() + " " + entry.getValue());
                }
            }
        }
    }

    private void checkAutoPotion(Player player, ItemStack item) {
        if (item.getType().isEdible() || item.getType() == Material.POTION ||
                item.getType() == Material.SPLASH_POTION || item.getType() == Material.LINGERING_POTION) {
            // 检测自动喝药模式（这里可以实现更复杂的模式检测）
            PlayerData data = plugin.getPlayerData(player);
            if (data.getViolationLevel("auto_potion") > 10) {
                plugin.getAlertManager().alertStaff(player, "疑似自动喝药");
            }
        }
    }

    // 可疑附魔检测 - 检测不兼容的附魔组合
    private void checkSuspiciousEnchantments(Player player, ItemStack item) {
        if (!item.hasItemMeta() || !item.getItemMeta().hasEnchants()) return;

        Map<org.bukkit.enchantments.Enchantment, Integer> enchants = item.getEnchantments();
        List<String> incompatiblePairs = new ArrayList<>();

        // 检测不兼容的附魔组合
        for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry1 : enchants.entrySet()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry2 : enchants.entrySet()) {
                if (entry1.getKey() != entry2.getKey() &&
                        areEnchantmentsIncompatible(entry1.getKey(), entry2.getKey())) {
                    incompatiblePairs.add(entry1.getKey().getKey().getKey() + " + " +
                            entry2.getKey().getKey().getKey());
                }
            }
        }

        if (!incompatiblePairs.isEmpty()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("incompatible_enchants", 10);
            plugin.getAlertManager().alertStaff(
                    player,
                    "不兼容附魔组合: " + String.join(", ", incompatiblePairs)
            );
        }

        // 检测异常附魔等级
        for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : enchants.entrySet()) {
            int level = entry.getValue();
            int maxLevel = entry.getKey().getMaxLevel();

            if (level > maxLevel + 5) { // 允许少量超出
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("impossible_enchant_level", 15);
                plugin.getAlertManager().alertStaff(
                        player,
                        "不可能附魔等级: " + entry.getKey().getKey().getKey() + " " + level
                );
            }
        }
    }

    // 非法堆叠检测 - 检测超过最大堆叠数量的物品
    private void checkIllegalStacks(Player player, ItemStack item) {
        if (item.getAmount() > item.getMaxStackSize()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("illegal_stack_size", 8);
            plugin.getAlertManager().alertStaff(
                    player,
                    "非法物品堆叠: " + item.getType() + " x" + item.getAmount() +
                            " (最大: " + item.getMaxStackSize() + ")"
            );

            // 自动修复堆叠
            item.setAmount(item.getMaxStackSize());
        }

        // 检测不可堆叠物品的堆叠
        if (item.getMaxStackSize() == 1 && item.getAmount() > 1) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("unstackable_stacked", 12);
            plugin.getAlertManager().alertStaff(
                    player,
                    "不可堆叠物品堆叠: " + item.getType() + " x" + item.getAmount()
            );
        }
    }

    // 自定义药水检测 - 检测异常的药水效果
    private void checkCustomPotions(Player player, ItemStack item) {
        if (item.getType() != Material.POTION && item.getType() != Material.SPLASH_POTION &&
                item.getType() != Material.LINGERING_POTION && item.getType() != Material.TIPPED_ARROW) {
            return;
        }

        org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();

        // 检测自定义药水效果
        if (meta.hasCustomEffects()) {
            for (PotionEffect effect : meta.getCustomEffects()) {
                // 检测异常药水效果
                if (effect.getAmplifier() > 5) { // 等级过高
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("custom_potion_effect", 6);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "自定义药水效果: " + effect.getType().getName() +
                                    " " + (effect.getAmplifier() + 1)
                    );
                }

                // 检测异常持续时间
                if (effect.getDuration() > 20 * 60 * 10) { // 超过10分钟
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("long_potion_duration", 4);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "异常药水时长: " + effect.getType().getName() +
                                    " " + (effect.getDuration() / 20) + "秒"
                    );
                }
            }
        }
    }

    // 消耗速度检测 - 检测快速消耗物品
    private void checkConsumptionSpeed(Player player, ItemStack item) {
        if (!item.getType().isEdible() && item.getType() != Material.POTION) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastConsume = lastConsumeTime.get(playerId);

        if (lastConsume != null) {
            long timeSinceLastConsume = currentTime - lastConsume;

            if (timeSinceLastConsume < 500) { // 500ms内连续消耗
                int count = consumeCount.getOrDefault(playerId, 0) + 1;
                consumeCount.put(playerId, count);

                if (count > 3) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_consumption", 4);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "快速消耗物品: " + item.getType() + " (" + count + "次连续)"
                    );
                }
            } else {
                consumeCount.put(playerId, 1);
            }
        } else {
            consumeCount.put(playerId, 1);
        }

        lastConsumeTime.put(playerId, currentTime);
    }

    // 可疑食物检测 - 检测非常规食物来源
    private void checkSuspiciousFood(Player player, ItemStack item) {
        if (!item.getType().isEdible()) return;

        Material foodType = item.getType();

        // 检测稀有食物的大量消耗
        if (suspiciousFoods.contains(foodType)) {
            Map<Material, Integer> foodCounts = foodSourceCount.getOrDefault(
                    player.getUniqueId(), new HashMap<>());

            int count = foodCounts.getOrDefault(foodType, 0) + 1;
            foodCounts.put(foodType, count);
            foodSourceCount.put(player.getUniqueId(), foodCounts);

            if (count > 10) { // 短时间内消耗过多稀有食物
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_food_consumption", 5);
                plugin.getAlertManager().alertStaff(
                        player,
                        "可疑食物消耗: " + foodType + " x" + count
                );
            }
        }
    }

    // 工具方法
    private boolean areEnchantmentsIncompatible(org.bukkit.enchantments.Enchantment ench1,
                                                org.bukkit.enchantments.Enchantment ench2) {
        // 定义不兼容的附魔组合
        Set<String> incompatiblePairs = Set.of(
                "PROTECTION_ENVIRONMENTAL:PROTECTION_PROJECTILE",
                "PROTECTION_ENVIRONMENTAL:PROTECTION_FIRE",
                "PROTECTION_ENVIRONMENTAL:PROTECTION_EXPLOSIONS",
                "DAMAGE_ALL:DAMAGE_ARTHROPODS",
                "DAMAGE_ALL:DAMAGE_UNDEAD",
                "DIG_SPEED:SILK_TOUCH"
        );

        String pair1 = ench1.getKey().getKey() + ":" + ench2.getKey().getKey();
        String pair2 = ench2.getKey().getKey() + ":" + ench1.getKey().getKey();

        return incompatiblePairs.contains(pair1) || incompatiblePairs.contains(pair2);
    }

    public void cleanup(UUID playerId) {
        lastItemUse.remove(playerId);
        lastConsumeTime.remove(playerId);
        consumeCount.remove(playerId);
        foodSourceCount.remove(playerId);
    }
}
