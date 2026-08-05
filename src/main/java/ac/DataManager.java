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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DataManager {
    private final gofd plugin;
    private final Path dataFolder;
    private final ac.core.AsyncFileService fileService;

    public DataManager(gofd plugin, ac.core.AsyncFileService fileService) {
        this.plugin = plugin;
        this.fileService = fileService;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata").toPath();
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create player data directory", exception);
        }
    }

    public PlayerData loadPlayerData(Player player) {
        Path file = dataFolder.resolve(player.getUniqueId() + ".yml");
        if (!Files.exists(file)) {
            return null;
        }

        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file.toFile());
            PlayerData data = new PlayerData(player);

            // 加载违规数据
            if (config.contains("violations")) {
                for (String key : config.getConfigurationSection("violations").getKeys(false)) {
                    data.addViolation(key, config.getInt("violations." + key));
                }
            }

            return data;
        } catch (Exception e) {
            plugin.getLogger().warning("加载玩家数据失败: " + player.getName());
            return null;
        }
    }

    public void savePlayerData(Player player, PlayerData data) {
        FileConfiguration config = new YamlConfiguration();

        // 保存违规数据
        for (Map.Entry<String, Integer> entry : data.getViolationLevels().entrySet()) {
            config.set("violations." + entry.getKey(), entry.getValue());
        }

        config.set("lastSaved", System.currentTimeMillis());
        config.set("playerName", player.getName());
        String serialized = config.saveToString();
        fileService.write(dataFolder.resolve(player.getUniqueId() + ".yml"), serialized);
    }

    public void saveAllPlayerData() {
        for (Map.Entry<UUID, PlayerData> entry : plugin.playerDataMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                savePlayerData(player, entry.getValue());
            }
        }
        plugin.getLogger().fine("Queued player data snapshots for asynchronous persistence");
    }
}
