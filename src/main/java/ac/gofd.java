package ac;

import ac.core.AsyncFileService;
import ac.core.DetectionMetrics;
import ac.core.ModuleRegistry;
import ac.core.PluginSettings;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("deprecation")
public class gofd extends JavaPlugin implements Listener {

    private static volatile gofd instance;
    final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();
    private MovementDetector movementDetector;
    private CombatDetector combatDetector;
    private ItemDetector itemDetector;
    private ChatDetector chatDetector;
    private AlertManager alertManager;
    private DataManager dataManager;
    private StrictPunishmentManager punishmentManager;
    private EnvironmentDetector environmentDetector;
    private BackpackDetector backpackDetector;
    NetworkDetector networkDetector;
    private RandomCheckSystem randomCheckSystem;
    private DebugManager debugManager;
    private PerformanceMonitor performanceMonitor;
    private StrictMovementDetector strictMovementDetector;
    private StrictEnvironmentDetector strictEnvironmentDetector;
    private StrictCombatDetector strictCombatDetector;
    private WurstSpecificDetector wurstSpecificDetector;
    private ModuleRegistry moduleRegistry;
    private PluginSettings settings;
    private AsyncFileService fileService;
    private final DetectionMetrics metrics = new DetectionMetrics();
    private BukkitTask violationDecayTask;
    private BukkitTask autoSaveTask;

    // Cached compatibility accessors. The immutable settings snapshot is the source of truth.
    private boolean enableMovementChecks = true;
    private boolean enableCombatChecks = true;
    private boolean enableItemChecks = true;
    private boolean enableInteractionChecks = true;
    private boolean enablePacketChecks = true;
    private boolean enableInventoryChecks = true;
    private int alertThreshold = 10;

    private long startTimeNanos;

    @Override
    public void onEnable() {
        instance = this;
        startTimeNanos = System.nanoTime();

        // 初始化配置
        saveDefaultConfig();
        loadConfig();
        fileService = new AsyncFileService("gofdac-file-writer", throwable ->
                getLogger().warning("异步文件写入失败: " + throwable.getMessage()));

        // 初始化所有模块
        initializeModules();

        // 注册事件
        registerEvents();

        // 启动定时任务
        startTasks();

        getLogger().info("GOFDAC反作弊插件已启用 - beta0.5");
        getLogger().info("已加载 " + getModuleCount() + " 个检测模块");
    }

    private void initializeModules() {
        movementDetector = new MovementDetector(this);
        combatDetector = new CombatDetector(this);
        itemDetector = new ItemDetector(this);
        chatDetector = new ChatDetector(this);
        alertManager = new AlertManager(this);
        dataManager = new DataManager(this, fileService);
        punishmentManager = new StrictPunishmentManager(this);
        environmentDetector = new EnvironmentDetector(this);
        backpackDetector = new BackpackDetector(this);
        networkDetector = new NetworkDetector(this);
        randomCheckSystem = new RandomCheckSystem(this);
        debugManager = new DebugManager(this);
        performanceMonitor = new PerformanceMonitor(this);
        ensureStrictModules();
    }

    private void registerEvents() {
        ensureStrictModules();
        moduleRegistry = new ModuleRegistry(this);
        boolean enabled = settings.enabled();
        // Lifecycle hooks remain active even when detection is globally disabled so
        // player state is still loaded and persisted correctly.
        moduleRegistry.add("core", true, this, ignored -> { });
        moduleRegistry.add("movement", enabled && settings.movementEnabled(), movementDetector,
                movementDetector::cleanup);
        moduleRegistry.add("combat", enabled && settings.combatEnabled(), combatDetector,
                combatDetector::cleanup);
        moduleRegistry.add("items", enabled && settings.itemEnabled(), itemDetector,
                itemDetector::cleanup);
        moduleRegistry.add("chat", enabled, chatDetector, chatDetector::cleanup);
        moduleRegistry.add("environment", enabled && settings.environmentEnabled(), environmentDetector,
                environmentDetector::cleanup);
        moduleRegistry.add("inventory", enabled && settings.inventoryEnabled(), backpackDetector,
                backpackDetector::cleanup);
        moduleRegistry.add("network", enabled && settings.packetEnabled(), networkDetector,
                networkDetector::cleanup);

        // Strict modules are opt-in. They are intentionally isolated from the normal checks
        // because their punishment policy is materially more aggressive.
        if (enabled && settings.strictEnabled()) {
            moduleRegistry.add("strict-movement", true, strictMovementDetector,
                    strictMovementDetector::cleanup);
            moduleRegistry.add("strict-environment", true, strictEnvironmentDetector,
                    strictEnvironmentDetector::cleanup);
            moduleRegistry.add("strict-combat", true, strictCombatDetector,
                    strictCombatDetector::cleanup);
            moduleRegistry.add("wurst-specific", true, wurstSpecificDetector,
                    wurstSpecificDetector::cleanup);
        }
        moduleRegistry.start();
    }

    private void ensureStrictModules() {
        if (!settings.strictEnabled() || strictMovementDetector != null) {
            return;
        }
        strictMovementDetector = new StrictMovementDetector(this);
        strictEnvironmentDetector = new StrictEnvironmentDetector(this);
        strictCombatDetector = new StrictCombatDetector(this);
        wurstSpecificDetector = new WurstSpecificDetector(this);
    }

    private int getModuleCount() {
        return moduleRegistry == null ? 0 : moduleRegistry.activeIds().size();
    }

    @Override
    public void onDisable() {
        if (violationDecayTask != null) {
            violationDecayTask.cancel();
            violationDecayTask = null;
        }
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
            autoSaveTask = null;
        }
        if (moduleRegistry != null) {
            moduleRegistry.stop();
        }
        if (randomCheckSystem != null) {
            randomCheckSystem.stop();
        }
        if (performanceMonitor != null) {
            performanceMonitor.stop();
        }
        if (dataManager != null) {
            dataManager.saveAllPlayerData();
        }
        if (fileService != null) {
            fileService.close();
        }
        instance = null;
        getLogger().info("GOFDAC反作弊插件已禁用");
    }

    private void loadConfig() {
        FileConfiguration config = getConfig();

        enableMovementChecks = config.getBoolean("movement.enabled", true);
        enableCombatChecks = config.getBoolean("combat.enabled", true);
        enableItemChecks = config.getBoolean("items.enabled", true);
        alertThreshold = config.getInt("general.alert-threshold", 10);

        // 新增配置
        enableInteractionChecks = config.getBoolean("interaction.enabled", true);
        enablePacketChecks = config.getBoolean("packet.enabled", true);
        enableInventoryChecks = config.getBoolean("inventory.enabled", true);

        // 设置默认值
        config.addDefault("movement.max-walk-speed", 0.65);
        config.addDefault("movement.max-fly-speed", 1.5);
        config.addDefault("movement.max-vertical-speed", 0.6);
        config.addDefault("movement.max-jump-height", 1.5);
        config.addDefault("combat.max-cps", 15);
        config.addDefault("combat.max-reach", 4.5);
        config.addDefault("punishment.enabled", true);
        config.addDefault("punishment.auto-kick", true);
        config.addDefault("punishment.kick-threshold", 25);

        // 新增配置默认值
        config.addDefault("interaction.max-block-interact-distance", 6.0);
        config.addDefault("interaction.max-block-break-speed", 10);
        config.addDefault("packet.max-packet-rate", 1000);
        config.addDefault("inventory.max-click-speed", 20);
        config.addDefault("movement.impossible-movement-check", true);
        config.addDefault("combat.auto-block-check", true);
        config.addDefault("debug.enabled", false);
        config.addDefault("performance.monitoring", true);
        config.addDefault("random-checks.enabled", true);
        config.addDefault("random-checks.interval", 30);
        config.addDefault("environment.xray-detection", true);
        config.addDefault("environment.scaffold-detection", true);
        config.addDefault("modules.strict.enabled", false);

        config.options().copyDefaults(true);
        saveConfig();
        settings = PluginSettings.from(config);
        enableMovementChecks = settings.enabled() && settings.movementEnabled();
        enableCombatChecks = settings.enabled() && settings.combatEnabled();
        enableItemChecks = settings.enabled() && settings.itemEnabled();
        enableInteractionChecks = settings.enabled() && settings.interactionEnabled();
        enablePacketChecks = settings.enabled() && settings.packetEnabled();
        enableInventoryChecks = settings.enabled() && settings.inventoryEnabled();
        alertThreshold = settings.alertThreshold();
    }

    private void reloadRuntimeConfig() {
        if (moduleRegistry != null) {
            moduleRegistry.stop();
        }
        randomCheckSystem.stop();
        performanceMonitor.stop();

        reloadConfig();
        loadConfig();
        movementDetector.reloadSettings();
        combatDetector.reloadSettings();
        if (settings.strictEnabled()) {
            if (strictMovementDetector == null) {
                strictMovementDetector = new StrictMovementDetector(this);
                strictEnvironmentDetector = new StrictEnvironmentDetector(this);
                strictCombatDetector = new StrictCombatDetector(this);
            }
        } else {
            strictMovementDetector = null;
            strictEnvironmentDetector = null;
            strictCombatDetector = null;
        }
        registerEvents();
        scheduleAutoSave();

        performanceMonitor.start();
        if (settings.enabled() && settings.randomChecksEnabled()) {
            randomCheckSystem.start();
        }
    }

    private void startTasks() {
        // 每秒钟运行一次的数据清理任务
        violationDecayTask = getServer().getScheduler().runTaskTimer(this, () -> {
            for (PlayerData data : playerDataMap.values()) {
                data.reduceViolationLevels();
            }
        }, 20L, 20L);

        scheduleAutoSave();
        performanceMonitor.start();
        if (settings.enabled() && settings.randomChecksEnabled()) {
            randomCheckSystem.start();
        }
    }

    private void scheduleAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        autoSaveTask = getServer().getScheduler().runTaskTimer(this,
                dataManager::saveAllPlayerData,
                settings.autoSaveTicks(), settings.autoSaveTicks());
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String @NotNull [] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("acalerts")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "只有玩家可以切换警报显示");
                return true;
            }
            if (!sender.hasPermission("gofdac.alerts")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令");
                return true;
            }
            boolean hasAlerts = alertManager.toggleAlerts(player);
            sender.sendMessage(ChatColor.YELLOW + "反作弊警报已" + (hasAlerts ? "开启" : "关闭"));
            return true;
        }
        if (commandName.equals("acdebug")) {
            if (!sender.hasPermission("gofdac.debug")) {
                sender.sendMessage(ChatColor.RED + "你没有权限执行此命令");
                return true;
            }
            showDebugInfo(sender);
            return true;
        }
        if (commandName.equals("gofdac")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== GOFDAC 反作弊系统 beta0.5 ===");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac reload - 重载配置");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac stats [玩家] - 查看统计数据");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac alerts - 切换警报显示");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac reset <玩家> - 重置玩家数据");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac debug - 调试信息");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac performance - 性能统计");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac modules - 模块状态");
                sender.sendMessage(ChatColor.YELLOW + "/gofdac test <检测类型> - 测试检测系统");
                return true;
            }

            switch (args[0].toLowerCase()) {
                case "reload":
                    if (sender.hasPermission("gofdac.admin")) {
                        reloadRuntimeConfig();
                        sender.sendMessage(ChatColor.GREEN + "GOFDAC配置已重载");
                    } else {
                        sender.sendMessage(ChatColor.RED + "你没有权限执行此命令");
                    }
                    break;

                case "stats":
                    if (sender.hasPermission("gofdac.admin")) {
                        showStats(sender, args.length > 1 ? args[1] : null);
                    } else {
                        sender.sendMessage(ChatColor.RED + "你没有权限执行此命令");
                    }
                    break;

                case "alerts":
                    if (sender instanceof Player player) {
                        boolean hasAlerts = alertManager.toggleAlerts(player);
                        sender.sendMessage(ChatColor.YELLOW + "反作弊警报已" + (hasAlerts ? "开启" : "关闭"));
                    } else {
                        sender.sendMessage(ChatColor.RED + "只有玩家可以切换警报显示");
                    }
                    break;

                case "reset":
                    if (sender.hasPermission("gofdac.admin") && args.length > 1) {
                        Player target = Bukkit.getPlayer(args[1]);
                        if (target != null) {
                            resetPlayerData(target);
                            sender.sendMessage(ChatColor.GREEN + "已重置玩家 " + target.getName() + " 的反作弊数据");
                        } else {
                            sender.sendMessage(ChatColor.RED + "玩家未在线或不存在");
                        }
                    }
                    break;

                case "debug":
                    if (sender.hasPermission("gofdac.admin")) {
                        showDebugInfo(sender);
                    }
                    break;

                case "performance":
                    if (sender.hasPermission("gofdac.admin")) {
                        showPerformanceStats(sender);
                    }
                    break;

                case "modules":
                    if (sender.hasPermission("gofdac.admin")) {
                        showModuleStatus(sender);
                    }
                    break;

                case "test":
                    if (sender.hasPermission("gofdac.admin") && args.length > 1) {
                        runTest(sender, args[1]);
                    }
                    break;

                default:
                    sender.sendMessage(ChatColor.RED + "未知命令，使用 /gofdac 查看帮助");
            }
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String @NotNull [] args) {
        if (!command.getName().equalsIgnoreCase("gofdac")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            List<String> values = List.of("reload", "stats", "alerts", "reset", "debug",
                    "performance", "modules", "test");
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return List.of("movement", "combat", "inventory", "all");
        }
        return Collections.emptyList();
    }

    private void resetPlayerData(Player player) {
        UUID playerId = player.getUniqueId();
        PlayerData previous = playerDataMap.remove(playerId);
        if (moduleRegistry != null) {
            moduleRegistry.cleanup(playerId);
        }
        if (alertManager != null) {
            alertManager.cleanup(playerId);
        }
        if (previous != null) {
            playerDataMap.put(playerId, new PlayerData(player));
        }
    }

    private void showStats(CommandSender sender, String playerName) {
        if (playerName != null) {
            Player target = Bukkit.getPlayer(playerName);
            if (target != null) {
                PlayerData data = getPlayerData(target);
                sender.sendMessage(ChatColor.GOLD + "=== " + target.getName() + " 的反作弊数据 ===");
                sender.sendMessage(ChatColor.YELLOW + "移动违规: " + data.getMovementViolations());
                sender.sendMessage(ChatColor.YELLOW + "战斗违规: " + data.getCombatViolations());
                sender.sendMessage(ChatColor.YELLOW + "物品违规: " + data.getItemViolations());
                for (String type : data.getViolationLevels().keySet()) {
                    sender.sendMessage(ChatColor.YELLOW + type + ": VL " + data.getViolationLevel(type));
                }
            } else {
                sender.sendMessage(ChatColor.RED + "玩家未在线");
            }
        } else {
            sender.sendMessage(ChatColor.GOLD + "=== GOFDAC 统计信息 ===");
            sender.sendMessage(ChatColor.YELLOW + "在线玩家: " + playerDataMap.size());
            sender.sendMessage(ChatColor.YELLOW + "总移动检测: " + movementDetector.getTotalChecks());
            sender.sendMessage(ChatColor.YELLOW + "总战斗检测: " + combatDetector.getTotalChecks());
        }
    }

    // 显示调试信息
    private void showDebugInfo(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== GOFDAC 调试信息 ===");
        sender.sendMessage(ChatColor.YELLOW + "运行时间: " + formatUptime());
        sender.sendMessage(ChatColor.YELLOW + "总检测次数: " + metrics.totalDetections());
        sender.sendMessage(ChatColor.YELLOW + "误报次数: " + metrics.falsePositives());
        sender.sendMessage(ChatColor.YELLOW + "准确率: " + calculateAccuracy() + "%");
        sender.sendMessage(ChatColor.YELLOW + "在线玩家: " + Bukkit.getOnlinePlayers().size());
        sender.sendMessage(ChatColor.YELLOW + "内存使用: " + getMemoryUsage());
        sender.sendMessage(ChatColor.YELLOW + "调试模式: " + (isDebugMode() ? "开启" : "关闭"));

        // 显示各检测类型的统计
        sender.sendMessage(ChatColor.GOLD + "--- 检测统计 ---");
        for (Map.Entry<String, Long> entry : metrics.detectionSnapshot().entrySet()) {
            sender.sendMessage(ChatColor.YELLOW + entry.getKey() + ": " + entry.getValue());
        }
    }

    // 显示性能统计
    private void showPerformanceStats(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 性能统计 ===");
        sender.sendMessage(ChatColor.YELLOW + "平均检测延迟: " + performanceMonitor.getAverageDetectionTime() + "ms");
        sender.sendMessage(ChatColor.YELLOW + "最大检测延迟: " + performanceMonitor.getMaxDetectionTime() + "ms");
        sender.sendMessage(ChatColor.YELLOW + "事件处理/秒: " + performanceMonitor.getEventsPerSecond());
        sender.sendMessage(ChatColor.YELLOW + "内存占用: " + getMemoryUsage());
        sender.sendMessage(ChatColor.YELLOW + "CPU 使用率: " + performanceMonitor.getCPUUsage() + "%");

        // 显示各模块性能
        sender.sendMessage(ChatColor.GOLD + "--- 模块性能 ---");
        for (Map.Entry<String, Long> entry : metrics.performanceSnapshot().entrySet()) {
            String unit = entry.getKey().endsWith("_nanos") ? "ns" : "ms";
            sender.sendMessage(ChatColor.YELLOW + entry.getKey() + ": " + entry.getValue() + unit);
        }
    }

    // 显示模块状态
    private void showModuleStatus(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== 模块状态 ===");
        sender.sendMessage(getModuleStatus("移动检测", isEnableMovementChecks()));
        sender.sendMessage(getModuleStatus("战斗检测", isEnableCombatChecks()));
        sender.sendMessage(getModuleStatus("物品检测", isEnableItemChecks()));
        sender.sendMessage(getModuleStatus("交互检测", isEnableInteractionChecks()));
        sender.sendMessage(getModuleStatus("背包检测", isEnableInventoryChecks()));
        sender.sendMessage(getModuleStatus("网络检测", isEnablePacketChecks()));
        sender.sendMessage(getModuleStatus("环境检测", true));
        sender.sendMessage(getModuleStatus("随机检查", settings.randomChecksEnabled()));
        sender.sendMessage(getModuleStatus("调试系统", isDebugMode()));
    }

    // 运行测试
    private void runTest(CommandSender sender, String testType) {
        switch (testType.toLowerCase()) {
            case "movement":
                testMovementDetection(sender);
                break;
            case "combat":
                testCombatDetection(sender);
                break;
            case "inventory":
                testInventoryDetection(sender);
                break;
            case "all":
                runAllTests(sender);
                break;
            default:
                sender.sendMessage(ChatColor.RED + "未知测试类型: " + testType);
        }
    }

    // 测试移动检测
    private void testMovementDetection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "只有玩家可以执行此测试");
            return;
        }

        sender.sendMessage(ChatColor.GREEN + "开始移动检测测试...");

        // 模拟一些测试
        PlayerData data = getPlayerData(player);

        // 测试飞行检测
        data.addViolation("flight", 1);
        // 测试速度检测
        data.addViolation("speed", 1);

        sender.sendMessage(ChatColor.GREEN + "移动检测测试完成");
    }

    // 其他测试方法...
    private void testCombatDetection(CommandSender sender) {
        // 实现战斗检测测试
    }

    private void testInventoryDetection(CommandSender sender) {
        // 实现库存检测测试
    }

    private void runAllTests(CommandSender sender) {
        // 运行所有测试
    }

    // 工具方法
    private String formatUptime() {
        long seconds = (System.nanoTime() - startTimeNanos) / 1_000_000_000L;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        return String.format("%d天 %d小时 %d分钟 %d秒", days, hours % 24, minutes % 60, seconds % 60);
    }

    private double calculateAccuracy() {
        long detections = metrics.totalDetections();
        if (detections == 0) return 100.0;
        return 100.0 - ((double) metrics.falsePositives() / detections * 100);
    }

    private String getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        long max = runtime.maxMemory();
        return String.format("%.2fMB / %.2fMB (%.1f%%)",
                used / 1024.0 / 1024.0,
                max / 1024.0 / 1024.0,
                (double) used / max * 100);
    }

    private String getModuleStatus(String name, boolean enabled) {
        return ChatColor.YELLOW + name + ": " +
                (enabled ? ChatColor.GREEN + "启用" : ChatColor.RED + "禁用");
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        playerDataMap.computeIfAbsent(playerId, ignored -> {
            PlayerData loaded = dataManager.loadPlayerData(player);
            return loaded == null ? new PlayerData(player) : loaded;
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家退出时保存数据
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            dataManager.savePlayerData(player, data);
        }
        UUID playerId = player.getUniqueId();
        if (moduleRegistry != null) {
            moduleRegistry.cleanup(playerId);
        }
        if (alertManager != null) {
            alertManager.cleanup(playerId);
        }
        if (data == null) {
            playerDataMap.remove(playerId);
        } else {
            playerDataMap.remove(playerId, data);
        }
    }
    @SuppressWarnings("unused")
    public static gofd getInstance() {
        return instance;
    }

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerData(player));
    }

    public AlertManager getAlertManager() {
        return alertManager;
    }

    public StrictPunishmentManager getPunishmentManager() {
        return punishmentManager;
    }

    // 配置获取方法
    public boolean isEnableMovementChecks() { return enableMovementChecks; }
    public boolean isEnableCombatChecks() { return enableCombatChecks; }
    public boolean isEnableItemChecks() { return enableItemChecks; }
    public boolean isEnableInteractionChecks() { return enableInteractionChecks; }
    public boolean isEnablePacketChecks() { return enablePacketChecks; }
    public boolean isEnableInventoryChecks() { return enableInventoryChecks; }
    public int getAlertThreshold() { return alertThreshold; }
    public boolean isDebugMode() { return settings != null && settings.debugEnabled(); }
    public PluginSettings getSettings() { return settings; }
    public AsyncFileService getFileService() { return fileService; }
    public boolean isPerformanceMonitoringEnabled() {
        return settings != null && settings.performanceMonitoring();
    }
    public long getAlertCooldownMillis() {
        return settings == null ? 5000L : settings.alertCooldownMillis();
    }
    public ModuleRegistry getModuleRegistry() { return moduleRegistry; }

    // 统计方法
    public void recordDetection(String type) {
        metrics.recordDetection(type);
    }

    public void recordFalsePositive() {
        metrics.recordFalsePositive();
    }

    public void recordPerformance(String module, long time) {
        metrics.recordPerformance(module, time);
    }

    // Getter 方法
    public DebugManager getDebugManager() { return debugManager; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }
    public RandomCheckSystem getRandomCheckSystem() { return randomCheckSystem; }
    public NetworkDetector getNetworkDetector() { return networkDetector; }
    public EnvironmentDetector getEnvironmentDetector() { return environmentDetector; }
    public BackpackDetector getBackpackDetector() { return backpackDetector; }
}
