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

@SuppressWarnings("deprecation")
public class gofd extends JavaPlugin implements Listener {

    private static gofd instance;
    HashMap<UUID, PlayerData> playerDataMap;
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

    // 配置选项
    private boolean enableMovementChecks = true;
    private boolean enableCombatChecks = true;
    private boolean enableItemChecks = true;
    private boolean enableInteractionChecks = true;
    private boolean enablePacketChecks = true;
    private boolean enableInventoryChecks = true;
    private int alertThreshold = 10;

    // 调试统计字段
    private long totalDetections = 0;
    private long falsePositives = 0;
    private long startTime;
    private final Map<String, Long> detectionStats = new ConcurrentHashMap<>();
    private final Map<String, Long> performanceStats = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        instance = this;
        playerDataMap = new HashMap<>();
        startTime = System.currentTimeMillis();

        // 初始化配置
        saveDefaultConfig();
        loadConfig();

        // 初始化所有模块
        initializeModules();

        // 注册事件
        registerEvents();

        // 启动定时任务
        startTasks();

        getLogger().info("GOFDAC反作弊插件已启用 - beta0.2");
        getLogger().info("已加载 " + getModuleCount() + " 个检测模块");
    }

    private void initializeModules() {
        movementDetector = new MovementDetector(this);
        combatDetector = new CombatDetector(this);
        itemDetector = new ItemDetector(this);
        chatDetector = new ChatDetector(this);
        alertManager = new AlertManager(this);
        dataManager = new DataManager(this);
        punishmentManager = new StrictPunishmentManager(this);
        environmentDetector = new EnvironmentDetector(this);
        backpackDetector = new BackpackDetector(this);
        networkDetector = new NetworkDetector(this);
        randomCheckSystem = new RandomCheckSystem(this);
        debugManager = new DebugManager(this);
        performanceMonitor = new PerformanceMonitor(this);
    }

    private void registerEvents() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(movementDetector, this);
        getServer().getPluginManager().registerEvents(combatDetector, this);
        getServer().getPluginManager().registerEvents(itemDetector, this);
        getServer().getPluginManager().registerEvents(chatDetector, this);
        getServer().getPluginManager().registerEvents(environmentDetector, this);
        getServer().getPluginManager().registerEvents(backpackDetector, this);

        // 注册网络相关事件（如果适用）
        if (isEnablePacketChecks()) {
            // 这里可以注册数据包监听器
        }
    }

    private int getModuleCount() {
        return 12; // 当前模块数量
    }

    @Override
    public void onDisable() {
        // 保存玩家数据
        dataManager.saveAllPlayerData();
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

        config.options().copyDefaults(true);
        saveConfig();
    }

    private void startTasks() {
        // 每秒钟运行一次的数据清理任务
        new BukkitRunnable() {
            @Override
            public void run() {
                for (PlayerData data : playerDataMap.values()) {
                    data.reduceViolationLevels();
                }
            }
        }.runTaskTimer(this, 0L, 20L);

        // 每5分钟自动保存数据
        new BukkitRunnable() {
            @Override
            public void run() {
                dataManager.saveAllPlayerData();
            }
        }.runTaskTimer(this, 6000L, 6000L);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command command, @NotNull String label, String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("gofdac")) {
            if (args.length == 0) {
                sender.sendMessage(ChatColor.GOLD + "=== GOFDAC 反作弊系统 b0.2 ===");
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
                        reloadConfig();
                        loadConfig();
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
                    }
                    break;

                case "reset":
                    if (sender.hasPermission("gofdac.admin") && args.length > 1) {
                        Player target = Bukkit.getPlayer(args[1]);
                        if (target != null) {
                            playerDataMap.remove(target.getUniqueId());
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
        sender.sendMessage(ChatColor.YELLOW + "总检测次数: " + totalDetections);
        sender.sendMessage(ChatColor.YELLOW + "误报次数: " + falsePositives);
        sender.sendMessage(ChatColor.YELLOW + "准确率: " + calculateAccuracy() + "%");
        sender.sendMessage(ChatColor.YELLOW + "在线玩家: " + Bukkit.getOnlinePlayers().size());
        sender.sendMessage(ChatColor.YELLOW + "内存使用: " + getMemoryUsage());
        sender.sendMessage(ChatColor.YELLOW + "调试模式: " + (isDebugMode() ? "开启" : "关闭"));

        // 显示各检测类型的统计
        sender.sendMessage(ChatColor.GOLD + "--- 检测统计 ---");
        for (Map.Entry<String, Long> entry : detectionStats.entrySet()) {
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
        for (Map.Entry<String, Long> entry : performanceStats.entrySet()) {
            sender.sendMessage(ChatColor.YELLOW + entry.getKey() + ": " + entry.getValue() + "ms");
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
        sender.sendMessage(getModuleStatus("随机检查", true));
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
        long uptime = System.currentTimeMillis() - startTime;
        long seconds = uptime / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        return String.format("%d天 %d小时 %d分钟 %d秒", days, hours % 24, minutes % 60, seconds % 60);
    }

    private double calculateAccuracy() {
        if (totalDetections == 0) return 100.0;
        return 100.0 - ((double) falsePositives / totalDetections * 100);
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
    public void onPlayerQuit(PlayerQuitEvent event) {
        // 玩家退出时保存数据
        Player player = event.getPlayer();
        PlayerData data = playerDataMap.get(player.getUniqueId());
        if (data != null) {
            dataManager.savePlayerData(player, data);
        }
    }
    @SuppressWarnings("unused")
    public static gofd getInstance() {
        return instance;
    }

    public PlayerData getPlayerData(Player player) {
        return playerDataMap.computeIfAbsent(player.getUniqueId(), k -> {
            PlayerData data = dataManager.loadPlayerData(player);
            return data != null ? data : new PlayerData(player);
        });
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
    public boolean isDebugMode() { return getConfig().getBoolean("debug", false); }

    // 统计方法
    public void recordDetection(String type) {
        totalDetections++;
        detectionStats.put(type, detectionStats.getOrDefault(type, 0L) + 1);
    }

    public void recordFalsePositive() {
        falsePositives++;
    }

    public void recordPerformance(String module, long time) {
        performanceStats.put(module, time);
    }

    // Getter 方法
    public DebugManager getDebugManager() { return debugManager; }
    public PerformanceMonitor getPerformanceMonitor() { return performanceMonitor; }
    public RandomCheckSystem getRandomCheckSystem() { return randomCheckSystem; }
    public NetworkDetector getNetworkDetector() { return networkDetector; }
    public EnvironmentDetector getEnvironmentDetector() { return environmentDetector; }
    public BackpackDetector getBackpackDetector() { return backpackDetector; }
}

class PlayerData {
    private final Player player;
    private final UUID playerId;
    private HashMap<String, Integer> violationLevels;
    private HashMap<String, Long> lastAlertTime;
    private long lastMoveTime;
    private int combatViolations = 0;
    private int movementViolations = 0;
    private int itemViolations = 0;
    private int totalViolations = 0;

    // 位置跟踪
    private double lastX, lastY, lastZ;
    private long lastPositionTime;

    // 新增：攻击历史记录
    LinkedList<Long> attackTimestamps = new LinkedList<>();
    private LinkedList<Location> movementHistory = new LinkedList<>();

    public PlayerData(Player player) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.violationLevels = new HashMap<>();
        this.lastAlertTime = new HashMap<>();
        this.lastMoveTime = System.currentTimeMillis();
    }

    public void addViolation(String type, int amount) {
        int current = violationLevels.getOrDefault(type, 0);
        violationLevels.put(type, current + amount);
        totalViolations += amount;

        // 统计分类违规
        if (type.startsWith("combat") || type.contains("cps") || type.contains("reach")) {
            combatViolations += amount;
        } else if (type.startsWith("movement") || type.contains("speed") || type.contains("flight")) {
            movementViolations += amount;
        } else if (type.startsWith("item")) {
            itemViolations += amount;
        }
    }

    public void reduceViolationLevels() {
        // 每秒钟减少1点违规值，最低为0
        for (String type : violationLevels.keySet()) {
            int current = violationLevels.get(type);
            if (current > 0) {
                violationLevels.put(type, Math.max(0, current - 1));
            }
        }
    }

    public int getViolationLevel(String type) {
        return violationLevels.getOrDefault(type, 0);
    }

    public HashMap<String, Integer> getViolationLevels() {
        return new HashMap<>(violationLevels);
    }

    // 位置追踪方法
    public void updatePosition(double x, double y, double z) {
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
        this.lastPositionTime = System.currentTimeMillis();

        // 记录移动历史（最多50个点）
        movementHistory.add(new Location(player.getWorld(), x, y, z));
        if (movementHistory.size() > 50) {
            movementHistory.removeFirst();
        }
    }

    public double getDistanceMoved(double x, double y, double z) {
        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        return Math.sqrt(dx*dx + dy*dy + dz*dz);
    }

    // 新增：记录攻击时间
    public void recordAttack() {
        long currentTime = System.currentTimeMillis();
        attackTimestamps.add(currentTime);

        // 只保留最近5秒的攻击记录
        while (!attackTimestamps.isEmpty() && currentTime - attackTimestamps.getFirst() > 5000) {
            attackTimestamps.removeFirst();
        }
    }

    // 新增：计算CPS
    public double getCPS() {
        long currentTime = System.currentTimeMillis();
        // 计算最近1秒内的攻击次数
        return attackTimestamps.stream()
                .filter(timestamp -> currentTime - timestamp < 1000)
                .count();
    }

    // 新增：获取移动历史
    public List<Location> getMovementHistory() {
        return new ArrayList<>(movementHistory);
    }

    // Getter和Setter方法
    public long getLastMoveTime() { return lastMoveTime; }
    public void setLastMoveTime(long time) { this.lastMoveTime = time; }
    public int getCombatViolations() { return combatViolations; }
    public int getMovementViolations() { return movementViolations; }
    public int getItemViolations() { return itemViolations; }
    public int getTotalViolations() { return totalViolations; }
}

class MovementDetector implements Listener {

    private final gofd plugin;
    private final HashMap<UUID, Long> lastFlightCheck = new HashMap<>();
    private final HashMap<UUID, Integer> airTicks = new HashMap<>();
    private final HashMap<UUID, Location> lastLocations = new HashMap<>();
    private final HashMap<UUID, Double> lastHorizontalSpeed = new HashMap<>();
    private final HashMap<UUID, Long> lastLiquidMove = new HashMap<>();
    private final HashMap<UUID, Integer> randomCheckCounter = new HashMap<>();
    private final Random random = new Random();
    private final HashMap<UUID, List<Vector>> previousDirections = new HashMap<>();

    // 增强的移动检测配置
    private final double MAX_WALK_SPEED = 0.65;
    private final double MAX_FLY_SPEED = 1.5;
    private final double MAX_VERTICAL_SPEED = 0.6;
    private final double MAX_JUMP_HEIGHT = 1.5;
    private static final double MAX_VERTICAL_SPEED_STRICT = 10.0;
    private final long FLIGHT_CHECK_INTERVAL = 1000;
    private final int MAX_AIR_TICKS = 60;


    // 地面材料检测
    private final Set<Material> GROUND_MATERIALS = Set.of(
            Material.STONE, Material.DIRT, Material.GRASS_BLOCK, Material.SAND,
            Material.GRAVEL, Material.COBBLESTONE, Material.OAK_PLANKS,
            Material.BEDROCK, Material.ANDESITE, Material.DIORITE, Material.GRANITE
    );

    public MovementDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        long startTime = System.currentTimeMillis();

        try {
            if (!plugin.isEnableMovementChecks()) return;

            Player player = event.getPlayer();
            PlayerData data = plugin.getPlayerData(player);

            if (player.hasPermission("gofdac.bypass")) return;

            // 多维度检测
            checkFlight(player, event, data);
            checkSpeed(player, event, data);
            checkVerticalMovement(player, event, data);
            checkNoFall(player, event, data);
            checkJesus(player, event, data);
            checkSpider(player, event, data);
            checkStep(player, event, data);
            checkFastRotation(player, event, data);
            checkTeleport(player, event, data);

            // 新增检测
            checkGroundAcceleration(player, event, data);
            checkIrregularMovement(player, event, data);
            checkLiquidMovement(player, event, data);
            performRandomMovementCheck(player, event, data);

            // 更新位置历史
            updateLocationHistory(player, event.getTo());
            data.updatePosition(event.getTo().getX(), event.getTo().getY(), event.getTo().getZ());

            totalChecks++;
        } finally {
            long endTime = System.currentTimeMillis();
            plugin.getPerformanceMonitor().recordDetectionTime(endTime - startTime);
            plugin.getPerformanceMonitor().recordEvent();
        }
    }

    private void checkFlight(Player player, PlayerMoveEvent event, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.isGliding() ||
                player.isInsideVehicle() || player.isSwimming()) return;

        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        Location from = event.getFrom();
        Location to = event.getTo();

        // 垂直移动检测
        double verticalMovement = to.getY() - from.getY();

        // 飞行状态检测
        if (!player.isOnGround() && !player.isFlying()) {
            int airTime = airTicks.getOrDefault(playerId, 0) + 1;
            airTicks.put(playerId, airTime);

            // 检查是否应该落地
            if (shouldBeOnGround(player) && airTime > 20) {
                data.addViolation("flight", 3);
                int vl = data.getViolationLevel("flight");

                if (vl > plugin.getAlertThreshold()) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "飞行作弊嫌疑 (空中时间: " + airTime + " ticks, VL: " + vl + ")"
                    );
                }
            }

            // 检查异常悬浮
            if (Math.abs(verticalMovement) < 0.001 && airTime > 40) {
                data.addViolation("hover", 5);
                plugin.getAlertManager().alertStaff(
                        player,
                        "悬停作弊嫌疑 (VL: " + data.getViolationLevel("hover") + ")"
                );
            }
        } else {
            airTicks.put(playerId, 0);
        }

        // 垂直速度检测
        if (Math.abs(verticalMovement) > MAX_VERTICAL_SPEED && !hasJumpPotion(player)) {
            data.addViolation("vertical_speed", 2);
            plugin.getAlertManager().alertStaff(
                    player,
                    "异常垂直速度: " + String.format("%.2f", verticalMovement) +
                            " (VL: " + data.getViolationLevel("vertical_speed") + ")"
            );
        }
    }

    private void checkSpeed(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getWorld() != to.getWorld()) return;

        double horizontalDistance = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) +
                        Math.pow(to.getZ() - from.getZ(), 2)
        );

        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDiff == 0) return;

        double speed = horizontalDistance / (timeDiff / 1000.0);
        double maxAllowedSpeed = calculateMaxSpeed(player);

        if (speed > maxAllowedSpeed * 1.3) { // 30% 容差
            data.addViolation("speed", 2);
            int vl = data.getViolationLevel("speed");

            plugin.getAlertManager().alertStaff(
                    player,
                    "速度作弊嫌疑 (速度: " + String.format("%.2f", speed) +
                            ", 限制: " + String.format("%.2f", maxAllowedSpeed) + ", VL: " + vl + ")"
            );
        }

        data.setLastMoveTime(System.currentTimeMillis());
    }

    private void checkVerticalMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();
        double verticalChange = to.getY() - from.getY();

        // 检测异常跳跃高度
        if (verticalChange > MAX_JUMP_HEIGHT && !hasJumpPotion(player) &&
                !player.isOnGround() && !isOnSlabOrStairs(player)) {
            data.addViolation("high_jump", 4);
            plugin.getAlertManager().alertStaff(
                    player,
                    "异常跳跃高度: " + String.format("%.2f", verticalChange) +
                            " (VL: " + data.getViolationLevel("high_jump") + ")"
            );
        }
    }

    private void checkNoFall(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 检测NoFall作弊 - 玩家从高处落下但没有受到摔落伤害
        if (from.getY() > to.getY() + 4 && shouldTakeFallDamage(player, from.getY() - to.getY())) {
            double fallDistance = getFallDistance(player);
            if (fallDistance > 3 && player.getFallDistance() == 0) {
                data.addViolation("nofall", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "NoFall作弊嫌疑 (摔落距离: " + String.format("%.1f", fallDistance) +
                                ", VL: " + data.getViolationLevel("nofall") + ")"
                );
            }
        }
    }

    private void checkJesus(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测水上行走作弊
        Location loc = player.getLocation();
        Material below = loc.clone().subtract(0, 1, 0).getBlock().getType();

        if ((below == Material.WATER || below.toString().contains("WATER")) &&
                !player.isSwimming() && !isInBoat(player)) {
            data.addViolation("jesus", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "水上行走嫌疑 (VL: " + data.getViolationLevel("jesus") + ")"
            );
        }
    }

    private void checkSpider(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测爬墙作弊
        Location loc = player.getLocation();
        Block faceBlock = loc.getBlock();

        if (isClimbableBlock(faceBlock)) {
            // 正常情况
            return;
        }

        // 检查玩家是否在没有梯子的情况下垂直移动
        double verticalMovement = event.getTo().getY() - event.getFrom().getY();
        if (verticalMovement > 0.5 && !isClimbableBlock(faceBlock) &&
                !player.isOnGround() && !player.isFlying()) {
            data.addViolation("spider", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "爬墙作弊嫌疑 (VL: " + data.getViolationLevel("spider") + ")"
            );
        }
    }

    private void checkStep(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测Step作弊 - 自动走上高方块
        double verticalChange = event.getTo().getY() - event.getFrom().getY();

        if (verticalChange > 1.0 && !player.isOnGround()) {
            data.addViolation("step", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Step作弊嫌疑 (高度: " + String.format("%.2f", verticalChange) +
                            ", VL: " + data.getViolationLevel("step") + ")"
            );
        }
    }

    // 新增：快速转向检测
    private void checkFastRotation(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        float yawChange = Math.abs(to.getYaw() - from.getYaw());
        float pitchChange = Math.abs(to.getPitch() - from.getPitch());

        // 检测异常的角度变化
        if (yawChange > 90.0f && pitchChange > 45.0f) {
            data.addViolation("fast_rotation", 3);
            plugin.getAlertManager().alertStaff(player,
                    "快速转向嫌疑 (Yaw: " + String.format("%.1f", yawChange) +
                            ", Pitch: " + String.format("%.1f", pitchChange) + ")");
        }
    }

    // 新增：传送检测
    private void checkTeleport(Player player, PlayerMoveEvent event, PlayerData data) {
        double distance = event.getFrom().distance(event.getTo());
        if (distance > 10.0 && !player.isInsideVehicle()) {
            data.addViolation("teleport", 10);
            plugin.getAlertManager().alertStaff(player,
                    "疑似传送作弊 (距离: " + String.format("%.1f", distance) + " 格)");
        }
    }

    // 地面加速检测 - 检测异常的地面移动加速度
    private void checkGroundAcceleration(Player player, PlayerMoveEvent event, PlayerData data) {
        if (!player.isOnGround()) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        double horizontalDistance = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) +
                        Math.pow(to.getZ() - from.getZ(), 2)
        );

        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
        if (timeDiff == 0) return;

        double currentSpeed = horizontalDistance / (timeDiff / 1000.0);
        UUID playerId = player.getUniqueId();

        Double lastSpeed = lastHorizontalSpeed.get(playerId);
        if (lastSpeed != null) {
            double acceleration = Math.abs(currentSpeed - lastSpeed);
            double maxAcceleration = 2.0; // 最大允许加速度

            // 考虑速度药水效果
            if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                maxAcceleration += 0.5;
            }

            if (acceleration > maxAcceleration) {
                data.addViolation("ground_acceleration", 4);
                plugin.getAlertManager().alertStaff(
                        player,
                        "异常地面加速: " + String.format("%.2f", acceleration) +
                                " (VL: " + data.getViolationLevel("ground_acceleration") + ")"
                );
            }
        }

        lastHorizontalSpeed.put(playerId, currentSpeed);
    }

    // 不规则移动检测 - 检测Timer作弊导致的异常移动
    private void checkIrregularMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();

        // 检测异常平滑的移动（Timer特征）
        double distance = from.distance(to);
        long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();

        if (timeDiff > 0) {
            double speed = distance / (timeDiff / 1000.0);

            // 检查速度是否过于稳定（Timer作弊）
            if (speed > 0.1) {
                List<Double> recentSpeeds = getRecentMovementSpeeds(player);
                if (recentSpeeds.size() > 10) {
                    double variance = calculateVariance(recentSpeeds);
                    if (variance < 0.001) { // 方差过小，移动过于规律
                        data.addViolation("timer_movement", 6);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "疑似Timer作弊 (移动方差: " + String.format("%.6f", variance) + ")"
                        );
                    }
                }
            }
        }
    }

    // 液体移动检测 - 检测水中异常移动速度
    private void checkLiquidMovement(Player player, PlayerMoveEvent event, PlayerData data) {
        Location loc = player.getLocation();
        boolean inWater = loc.getBlock().getType() == Material.WATER ||
                loc.clone().subtract(0, 1, 0).getBlock().getType() == Material.WATER;
        boolean inLava = loc.getBlock().getType() == Material.LAVA ||
                loc.clone().subtract(0, 1, 0).getBlock().getType() == Material.LAVA;

        if (inWater || inLava) {
            Location from = event.getFrom();
            Location to = event.getTo();
            double horizontalDistance = Math.sqrt(
                    Math.pow(to.getX() - from.getX(), 2) +
                            Math.pow(to.getZ() - from.getZ(), 2)
            );

            long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();
            if (timeDiff > 0) {
                double speed = horizontalDistance / (timeDiff / 1000.0);
                double maxLiquidSpeed = inWater ? 0.3 : 0.15; // 水中和熔岩中的最大速度

                // 考虑水深游效果
                if (inWater && player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) {
                    maxLiquidSpeed *= 1.5;
                }

                if (speed > maxLiquidSpeed) {
                    data.addViolation("liquid_speed", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "液体中异常移动: " + String.format("%.2f", speed) +
                                    " (类型: " + (inWater ? "水" : "熔岩") + ")"
                    );
                }
            }

            // 检测液体中飞行
            if (!player.isSwimming() && player.getLocation().getY() > getLiquidSurface(loc)) {
                data.addViolation("liquid_flight", 5);
                plugin.getAlertManager().alertStaff(player, "液体中飞行嫌疑");
            }
        }
    }

    // 随机移动检查 - 防止作弊者预测检测模式
    private void performRandomMovementCheck(Player player, PlayerMoveEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        int counter = randomCheckCounter.getOrDefault(playerId, 0) + 1;
        randomCheckCounter.put(playerId, counter);

        // 每20-50次移动执行一次随机检查
        if (counter > random.nextInt(30) + 20) {
            randomCheckCounter.put(playerId, 0);

            // 随机选择一种检查类型
            int checkType = random.nextInt(4);
            switch (checkType) {
                case 0:
                    checkMicroMovements(player, event, data);
                    break;
                case 1:
                    checkMovementConsistency(player, event, data);
                    break;
                case 2:
                    checkPositionDesync(player, event, data);
                    break;
                case 3:
                    checkMovementPattern(player, event, data);
                    break;
            }
        }
    }

    // 微移动检测
    private void checkMicroMovements(Player player, PlayerMoveEvent event, PlayerData data) {
        Location from = event.getFrom();
        Location to = event.getTo();
        double distance = from.distance(to);

        // 检测异常小的移动（某些作弊特征）
        if (distance < 0.001 && distance > 0) {
            data.addViolation("micro_movements", 1);
            if (data.getViolationLevel("micro_movements") > 50) {
                plugin.getAlertManager().alertStaff(player, "异常微移动模式");
            }
        }
    }

    // 移动一致性检测
    private void checkMovementConsistency(Player player, PlayerMoveEvent event, PlayerData data) {
        List<Location> history = data.getMovementHistory();
        if (history.size() < 10) return;

        // 分析移动方向变化模式
        List<Double> directionChanges = new ArrayList<>();
        for (int i = 1; i < history.size() - 1; i++) {
            Location prev = history.get(i - 1);
            Location current = history.get(i);
            Location next = history.get(i + 1);

            double dir1 = Math.atan2(current.getZ() - prev.getZ(), current.getX() - prev.getX());
            double dir2 = Math.atan2(next.getZ() - current.getZ(), next.getX() - current.getX());
            double change = Math.abs(dir1 - dir2);

            directionChanges.add(change);
        }

        // 检测过于规律的移动模式
        double consistency = calculateConsistency(directionChanges);
        if (consistency > 0.9) {
            data.addViolation("movement_consistency", 2);
        }
    }

    // 位置同步检测
    private void checkPositionDesync(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测客户端和服务端位置不同步
        Location serverLocation = player.getLocation();
        Location clientLocation = event.getTo();

        double distance = serverLocation.distance(clientLocation);
        if (distance > 0.5) {
            data.addViolation("position_desync", 3);
            plugin.getAlertManager().alertStaff(
                    player,
                    "位置同步异常: " + String.format("%.2f", distance)
            );
        }
    }

    // 移动模式检测
    private void checkMovementPattern(Player player, PlayerMoveEvent event, PlayerData data) {
        // 检测作弊软件特有的移动模式
        List<Location> history = data.getMovementHistory();
        if (history.size() < 20) return;

        // 分析移动序列的统计特性
        int zigzagCount = countZigzagPatterns(history);
        if (zigzagCount > history.size() * 0.3) { // 30%以上是锯齿模式
            data.addViolation("suspicious_movement_pattern", 4);
            plugin.getAlertManager().alertStaff(player, "可疑移动模式: 锯齿运动");
        }
    }

    private boolean shouldBeOnGround(Player player) {
        Location loc = player.getLocation();
        Location below = loc.clone().subtract(0, 1, 0);
        return isSolidBlock(below.getBlock());
    }

    private boolean isSolidBlock(Block block) {
        return block.getType().isSolid() && !block.isLiquid() &&
                !block.getType().toString().contains("LEAVES") &&
                !block.getType().toString().contains("SIGN");
    }

    private boolean hasJumpPotion(Player player) {
        return player.hasPotionEffect(PotionEffectType.JUMP_BOOST);
    }

    private boolean isOnSlabOrStairs(Player player) {
        Location below = player.getLocation().subtract(0, 1, 0);
        Material material = below.getBlock().getType();
        return material.toString().contains("SLAB") || material.toString().contains("STAIRS");
    }

    private boolean shouldTakeFallDamage(Player player, double fallDistance) {
        return fallDistance > 3 && !player.isOnGround();
    }

    private double getFallDistance(Player player) {
        // 简化版摔落距离计算
        return player.getFallDistance();
    }

    private boolean isInBoat(Player player) {
        return player.isInsideVehicle() &&
                player.getVehicle() != null &&
                player.getVehicle().getType().toString().contains("BOAT");
    }

    private boolean isClimbableBlock(Block block) {
        Material material = block.getType();
        return material == Material.LADDER || material == Material.VINE ||
                material.toString().contains("SCAFFOLD");
    }

    private double calculateMaxSpeed(Player player) {
        double baseSpeed = player.isFlying() ? MAX_FLY_SPEED : MAX_WALK_SPEED;

        // 考虑药水效果
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            for (PotionEffect effect : player.getActivePotionEffects()) {
                if (effect.getType().equals(PotionEffectType.SPEED)) {
                    baseSpeed *= (1.0 + 0.2 * (effect.getAmplifier() + 1));
                }
            }
        }

        return baseSpeed;
    }

    private void updateLocationHistory(Player player, Location location) {
        lastLocations.put(player.getUniqueId(), location.clone());
    }

    // 工具方法
    private List<Double> getRecentMovementSpeeds(Player player) {
        // 实现获取最近移动速度的逻辑
        List<Double> speeds = new ArrayList<>();
        // 这里需要从PlayerData中获取历史速度数据
        return speeds;
    }

    private double calculateVariance(List<Double> values) {
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);
        return variance;
    }

    private double getLiquidSurface(Location loc) {
        // 简化实现，找到液体表面高度
        for (int y = (int) loc.getY(); y < loc.getWorld().getMaxHeight(); y++) {
            Block block = loc.getWorld().getBlockAt(loc.getBlockX(), y, loc.getBlockZ());
            if (block.getType() != Material.WATER && block.getType() != Material.LAVA) {
                return y - 1;
            }
        }
        return loc.getY();
    }

    private double calculateConsistency(List<Double> values) {
        if (values.isEmpty()) return 0;
        // 计算数值的一致性（1.0表示完全一致）
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double sumDiff = values.stream().mapToDouble(v -> Math.abs(v - mean)).sum();
        return 1.0 - (sumDiff / (values.size() * Math.PI));
    }
    // 更严格的飞行检测
    private void checkStrictFlight(Player player, PlayerMoveEvent event, PlayerData data) {
        if (player.getGameMode() == GameMode.CREATIVE || player.isGliding() ||
                player.isInsideVehicle() || player.isSwimming()) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        double verticalChange = to.getY() - from.getY();

        // 如果玩家不在梯子或藤蔓上，并且垂直移动超过一定速度，则标记
        if (!isClimbing(player) && Math.abs(verticalChange) > MAX_VERTICAL_SPEED_STRICT) {
            data.addViolation("strict_vertical_speed", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "严格垂直速度检测: " + String.format("%.2f", verticalChange)
            );
        }

        // 检测空中移动方向变化（飞行作弊往往有异常的方向变化）
        if (!player.isOnGround() && !player.isFlying()) {
            Vector fromVector = from.toVector();
            Vector toVector = to.toVector();
            Vector direction = toVector.subtract(fromVector);

            // 如果水平移动距离大于0，检查方向变化
            if (direction.length() > 0.1) {
                Vector horizontalDirection = new Vector(direction.getX(), 0, direction.getZ());
                Vector previousHorizontalDirection = getPreviousHorizontalDirection(player);

                if (previousHorizontalDirection != null) {
                    double angle = horizontalDirection.angle(previousHorizontalDirection);
                    // 如果角度变化过大，可能是飞行作弊
                    if (angle > Math.PI / 4) { // 45度
                        data.addViolation("strict_air_direction_change", 3);
                        if (data.getViolationLevel("strict_air_direction_change") > 5) {
                            plugin.getAlertManager().alertStaff(
                                    player,
                                    "空中异常方向变化: " + String.format("%.2f", Math.toDegrees(angle)) + "度"
                            );
                        }
                    }
                }
                setPreviousHorizontalDirection(player, horizontalDirection);
            }
        }
    }

    // 获取先前的水平方向
    private Vector getPreviousHorizontalDirection(Player player) {
        // 从存储中获取先前的方向
        return (Vector) previousDirections.get(player.getUniqueId());
    }

    // 设置先前的水平方向
    private void setPreviousHorizontalDirection(Player player, Vector direction) {
        previousDirections.put(player.getUniqueId(), (List<Vector>) direction);
    }

    // 判断玩家是否在爬梯子或藤蔓
    private boolean isClimbing(Player player) {
        Location loc = player.getLocation();
        Block block = loc.getBlock();
        return isClimbableBlock(block) || isClimbableBlock(block.getRelative(0, 1, 0));
    }

    private int countZigzagPatterns(List<Location> history) {
        int count = 0;
        for (int i = 2; i < history.size(); i++) {
            Location p1 = history.get(i - 2);
            Location p2 = history.get(i - 1);
            Location p3 = history.get(i);

            double angle1 = Math.atan2(p2.getZ() - p1.getZ(), p2.getX() - p1.getX());
            double angle2 = Math.atan2(p3.getZ() - p2.getZ(), p3.getX() - p2.getX());
            double angleDiff = Math.abs(angle1 - angle2);

            if (angleDiff > Math.PI / 4 && angleDiff < Math.PI * 3 / 4) { // 45-135度转折
                count++;
            }
        }
        return count;
    }

    private int totalChecks = 0;
    public int getTotalChecks() { return totalChecks; }
}

class CombatDetector implements Listener {

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
    private final int MAX_CPS = 12;
    private final double MAX_REACH = 4;
    private final double MAX_ANGLE_CHANGE = 60.0; // 最大角度变化
    private final long KILLAURA_CHECK_INTERVAL = 800; // 杀怒检测间隔

    public CombatDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        long startTime = System.currentTimeMillis();

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
            long endTime = System.currentTimeMillis();
            plugin.getPerformanceMonitor().recordDetectionTime(endTime - startTime);
            plugin.getPerformanceMonitor().recordEvent();
        }
    }

    @EventHandler
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

    @EventHandler
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
        Set<UUID> attacked = attackedEntities.getOrDefault(playerId, new HashSet<>());
        attacked.add(targetId);
        attackedEntities.put(playerId, attacked);

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

class ItemDetector implements Listener {
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

    @EventHandler
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
}

class ChatDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, LinkedList<Long>> chatHistory = new HashMap<>();
    private final HashMap<UUID, String> lastMessage = new HashMap<>();

    // 敏感词过滤
    private final Set<String> blockedWords = Set.of(
            "www.", ".com", ".net", ".org", "discord.gg", "作弊", "hack"
    );

    public ChatDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onAsyncPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().toLowerCase();

        // 检测刷屏
        if (checkSpam(player)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "发言过于频繁，请稍后再试");
            return;
        }

        // 检测广告
        if (checkAdvertisement(message)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请勿发送广告信息");
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("chat_advertisement", 3);
            plugin.getAlertManager().alertStaff(player, "发送广告: " + message);
            return;
        }

        // 检测重复消息
        if (checkRepeatMessage(player, message)) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "请勿重复发送相同消息");
            return;
        }

        // 记录聊天历史
        recordChat(player);
        lastMessage.put(player.getUniqueId(), message);
    }

    private boolean checkSpam(Player player) {
        UUID playerId = player.getUniqueId();
        LinkedList<Long> history = chatHistory.computeIfAbsent(playerId, k -> new LinkedList<>());
        long currentTime = System.currentTimeMillis();

        // 清理超过10秒的记录
        history.removeIf(time -> currentTime - time > 10000);

        // 检查10秒内是否超过5条消息
        if (history.size() >= 5) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("chat_spam", 2);
            plugin.getAlertManager().alertStaff(player, "聊天刷屏嫌疑");
            return true;
        }

        return false;
    }

    private boolean checkAdvertisement(String message) {
        return blockedWords.stream().anyMatch(message::contains);
    }

    private boolean checkRepeatMessage(Player player, String message) {
        String lastMsg = lastMessage.get(player.getUniqueId());
        if (lastMsg != null && lastMsg.equals(message)) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("chat_repeat", 1);
            return true;
        }
        return false;
    }

    private void recordChat(Player player) {
        UUID playerId = player.getUniqueId();
        LinkedList<Long> history = chatHistory.computeIfAbsent(playerId, k -> new LinkedList<>());
        history.add(System.currentTimeMillis());
    }
}

class AlertManager {
    private final gofd plugin;
    private final String ALERT_PREFIX = "&8[&cGOFDAC&8] &7";
    private final Set<UUID> disabledAlerts = ConcurrentHashMap.newKeySet();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final Map<String, Integer> alertStatistics = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAlertTime = new ConcurrentHashMap<>();
    private final int ALERT_COOLDOWN = 5000; // 5秒冷却

    public AlertManager(gofd plugin) {
        this.plugin = plugin;
    }

    public void alertStaff(Player player, String message) {
        // 检查警报冷却
        if (isOnCooldown(player)) {
            return;
        }

        // 记录最后警报时间
        lastAlertTime.put(player.getUniqueId(), System.currentTimeMillis());

        // 统计警报类型
        String alertType = extractAlertType(message);
        alertStatistics.put(alertType, alertStatistics.getOrDefault(alertType, 0) + 1);

        // 原有警报逻辑...
        String alertMessage = ALERT_PREFIX + "&e" + player.getName() + " &7- &f" + message;
        String consoleMessage = ChatColor.stripColor(alertMessage.replace("&", ""));

        // 后台控制台输出
        plugin.getLogger().warning(consoleMessage);

        // 记录到日志文件
        logToFile(player, message);

        // 发送给有权限的在线管理员
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("gofdac.alerts") &&
                    !staff.equals(player) &&
                    !disabledAlerts.contains(staff.getUniqueId())) {
                staff.sendMessage(ChatColor.translateAlternateColorCodes('&', alertMessage));
            }
        }

        // 记录检测
        plugin.recordDetection(alertType);

        // 检查是否需要惩罚
        PlayerData data = plugin.getPlayerData(player);
        plugin.getPunishmentManager().checkStrictPunishment(player, data);
    }

    private void logToFile(Player player, String message) {
        try {
            File logFile = new File(plugin.getDataFolder(), "alerts.log");
            if (!logFile.exists()) {
                logFile.createNewFile();
            }

            String logEntry = String.format("[%s] %s: %s\n",
                    dateFormat.format(new Date()),
                    player.getName(),
                    message);

            java.nio.file.Files.write(
                    logFile.toPath(),
                    logEntry.getBytes(),
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            plugin.getLogger().warning("无法写入警报日志: " + e.getMessage());
        }
    }

    public boolean toggleAlerts(Player player) {
        if (disabledAlerts.contains(player.getUniqueId())) {
            disabledAlerts.remove(player.getUniqueId());
            return true;
        } else {
            disabledAlerts.add(player.getUniqueId());
            return false;
        }
    }

    public void logDebug(String message) {
        if (plugin.isDebugMode()) {
            plugin.getLogger().info("[DEBUG] " + message);
        }
    }

    // 新增方法：获取警报统计
    public Map<String, Integer> getAlertStatistics() {
        return new HashMap<>(alertStatistics);
    }

    // 新增方法：重置统计
    public void resetStatistics() {
        alertStatistics.clear();
    }

    // 新增方法：检查冷却
    private boolean isOnCooldown(Player player) {
        Long lastAlert = lastAlertTime.get(player.getUniqueId());
        if (lastAlert == null) return false;

        return System.currentTimeMillis() - lastAlert < ALERT_COOLDOWN;
    }

    // 新增方法：提取警报类型
    private String extractAlertType(String message) {
        if (message.contains("飞行")) return "flight";
        if (message.contains("速度")) return "speed";
        if (message.contains("CPS")) return "cps";
        if (message.contains("长臂")) return "reach";
        if (message.contains("穿墙")) return "wall_hit";
        if (message.contains("KillAura")) return "killaura";
        if (message.contains("自瞄")) return "aimbot";
        return "other";
    }

    // 新增方法：生成统计报告
    public String generateStatisticsReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 警报统计 ===\n");

        int totalAlerts = alertStatistics.values().stream().mapToInt(Integer::intValue).sum();
        report.append("总警报数: ").append(totalAlerts).append("\n");

        for (Map.Entry<String, Integer> entry : alertStatistics.entrySet()) {
            double percentage = (double) entry.getValue() / totalAlerts * 100;
            report.append(String.format("%s: %d (%.1f%%)%n",
                    entry.getKey(), entry.getValue(), percentage));
        }

        return report.toString();
    }
}

class DataManager {
    private final gofd plugin;
    private final File dataFolder;

    public DataManager(gofd plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "playerdata");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    public PlayerData loadPlayerData(Player player) {
        File file = new File(dataFolder, player.getUniqueId() + ".yml");
        if (!file.exists()) {
            return null;
        }

        try {
            FileConfiguration config = YamlConfiguration.loadConfiguration(file);
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
        File file = new File(dataFolder, player.getUniqueId() + ".yml");
        FileConfiguration config = new YamlConfiguration();

        // 保存违规数据
        for (Map.Entry<String, Integer> entry : data.getViolationLevels().entrySet()) {
            config.set("violations." + entry.getKey(), entry.getValue());
        }

        config.set("lastSaved", System.currentTimeMillis());
        config.set("playerName", player.getName());

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("保存玩家数据失败: " + player.getName());
        }
    }

    public void saveAllPlayerData() {
        for (Map.Entry<UUID, PlayerData> entry : plugin.playerDataMap.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                savePlayerData(player, entry.getValue());
            }
        }
        plugin.getLogger().info("已保存所有玩家数据");
    }
}

class EnvironmentDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastBlockBreak = new HashMap<>();
    private final HashMap<UUID, Integer> blockBreakCount = new HashMap<>();
    private final HashMap<UUID, Long> lastBlockPlace = new HashMap<>();
    private final HashMap<UUID, Integer> blockPlaceCount = new HashMap<>();
    private final HashMap<UUID, Set<Location>> brokenBlocks = new HashMap<>();
    private final HashMap<UUID, Map<Material, Integer>> oreMiningStats = new HashMap<>();
    private final HashMap<UUID, Long> lastScaffoldPlace = new HashMap<>();
    private final HashMap<UUID, Integer> scaffoldCount = new HashMap<>();

    // 珍贵矿石列表
    private final Set<Material> valuableOres = Set.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE
    );

    // 可透视方块列表（X-Ray特征）
    private final Set<Material> transparentBlocks = Set.of(
            Material.STONE, Material.DEEPSLATE, Material.NETHERRACK,
            Material.END_STONE, Material.TUFF, Material.ANDESITE
    );

    public EnvironmentDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkBreakSpeed(player, event);
        checkNuker(player, event);
        checkXRay(player, event);
        checkInstaBreak(player, event);
        checkIllegalBreak(player, event);
    }

    @EventHandler
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkPlaceSpeed(player, event);
        checkScaffold(player, event);
        checkIllegalPlace(player, event);
        checkBlockReach(player, event);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkInteractReach(player, event);
        checkAutoTool(player, event);
    }

    // 破坏速度检测 - 检测异常方块破坏速度
    private void checkBreakSpeed(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastBreak = lastBlockBreak.get(playerId);

        if (lastBreak != null) {
            long timeSinceLastBreak = currentTime - lastBreak;

            // 检测连续破坏方块的速度
            if (timeSinceLastBreak < 100) { // 100ms内连续破坏
                int count = blockBreakCount.getOrDefault(playerId, 0) + 1;
                blockBreakCount.put(playerId, count);

                if (count > 5) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_break", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "快速破坏方块: " + count + " 次连续快速破坏"
                    );
                }
            } else {
                blockBreakCount.put(playerId, 1);
            }
        } else {
            blockBreakCount.put(playerId, 1);
        }

        lastBlockBreak.put(playerId, currentTime);

        // 检测特定方块的破坏速度
        checkSpecificBlockBreakSpeed(player, event);
    }

    // Nuker检测 - 检测同时破坏多个方块
    private void checkNuker(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        UUID playerId = player.getUniqueId();
        Set<Location> broken = brokenBlocks.getOrDefault(playerId, new HashSet<>());
        broken.add(event.getBlock().getLocation());

        // 检查短时间内破坏的方块数量
        if (broken.size() > 5) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("nuker", 8);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Nuker嫌疑: 短时间内破坏 " + broken.size() + " 个方块"
            );
            broken.clear();
        }

        brokenBlocks.put(playerId, broken);

        // 清理过时的记录
        new BukkitRunnable() {
            @Override
            public void run() {
                brokenBlocks.remove(playerId);
            }
        }.runTaskLater(plugin, 20 * 5); // 5秒后清理

        // 检测3x3区域破坏
        checkAreaBreak(player, event);
    }

    // X-Ray检测 - 检测直接挖掘珍贵矿石
    private void checkXRay(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        if (valuableOres.contains(material)) {
            // 记录矿石挖掘统计
            Map<Material, Integer> stats = oreMiningStats.getOrDefault(
                    player.getUniqueId(), new HashMap<>());

            int count = stats.getOrDefault(material, 0) + 1;
            stats.put(material, count);
            oreMiningStats.put(player.getUniqueId(), stats);

            // 检测矿石挖掘效率（X-Ray特征）
            double efficiency = calculateMiningEfficiency(player, block);
            if (efficiency > 0.8) { // 80%以上效率挖掘到矿石
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("xray_efficiency", 4);

                if (data.getViolationLevel("xray_efficiency") > 10) {
                    plugin.getAlertManager().alertStaff(
                            player,
                            "X-Ray效率嫌疑: " + String.format("%.1f", efficiency * 100) + "%"
                    );
                }
            }

            // 检测连续挖掘珍贵矿石
            checkConsecutiveOreMining(player, material);

            // 检测挖掘路径（是否直接朝向矿石）
            checkMiningDirection(player, block);
        }

        // 检测透视挖掘模式
        checkOreToWasteRatio(player, block);
    }

    // 瞬间破坏检测
    private void checkInstaBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检查不应该被瞬间破坏的方块
        if (material.getHardness() > 1.0) {
            long breakTime = calculateExpectedBreakTime(player, block);
            long actualTime = System.currentTimeMillis() - lastBlockBreak.getOrDefault(player.getUniqueId(), 0L);

            if (actualTime < breakTime * 0.1) { // 实际时间低于预期时间的10%
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("insta_break", 10);
                plugin.getAlertManager().alertStaff(
                        player,
                        "瞬间破坏: " + material + " (预期: " + breakTime + "ms, 实际: " + actualTime + "ms)"
                );
            }
        }
    }

    // 非法破坏检测
    private void checkIllegalBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检测破坏不可破坏的方块
        if (material == Material.BEDROCK || material == Material.END_PORTAL_FRAME ||
                material == Material.BARRIER || material.toString().contains("SPAWNER")) {

            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("illegal_break", 15);
            plugin.getAlertManager().alertStaff(
                    player,
                    "非法破坏: " + material
            );

            event.setCancelled(true);
        }

        // 检测在保护区域破坏
        if (isInProtectedRegion(block.getLocation())) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("protected_break", 8);
            plugin.getAlertManager().alertStaff(player, "在保护区域破坏方块");
        }
    }

    // 放置速度检测 - 检测异常方块放置速度
    private void checkPlaceSpeed(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastPlace = lastBlockPlace.get(playerId);

        if (lastPlace != null) {
            long timeSinceLastPlace = currentTime - lastPlace;

            if (timeSinceLastPlace < 100) { // 100ms内连续放置
                int count = blockPlaceCount.getOrDefault(playerId, 0) + 1;
                blockPlaceCount.put(playerId, count);

                if (count > 5) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_place", 3);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "快速放置方块: " + count + " 次连续快速放置"
                    );
                }
            } else {
                blockPlaceCount.put(playerId, 1);
            }
        } else {
            blockPlaceCount.put(playerId, 1);
        }

        lastBlockPlace.put(playerId, currentTime);
    }

    // Scaffold检测 - 检测自动搭路作弊
    private void checkScaffold(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        Block block = event.getBlock();
        Location blockLoc = block.getLocation();
        Location playerLoc = player.getLocation();

        // 检测脚下放置（搭路特征）
        Location feetLoc = playerLoc.clone().subtract(0, 1, 0);
        if (blockLoc.getBlockX() == feetLoc.getBlockX() &&
                blockLoc.getBlockZ() == feetLoc.getBlockZ() &&
                Math.abs(blockLoc.getY() - feetLoc.getY()) <= 2) {

            UUID playerId = player.getUniqueId();
            long currentTime = System.currentTimeMillis();
            Long lastScaffold = lastScaffoldPlace.get(playerId);

            if (lastScaffold != null) {
                long timeSinceLastScaffold = currentTime - lastScaffold;

                if (timeSinceLastScaffold < 200) { // 200ms内连续搭路
                    int count = scaffoldCount.getOrDefault(playerId, 0) + 1;
                    scaffoldCount.put(playerId, count);

                    if (count > 3) {
                        PlayerData data = plugin.getPlayerData(player);
                        data.addViolation("scaffold", 5);
                        plugin.getAlertManager().alertStaff(
                                player,
                                "Scaffold嫌疑: " + count + " 次连续搭路"
                        );
                    }
                } else {
                    scaffoldCount.put(playerId, 1);
                }
            } else {
                scaffoldCount.put(playerId, 1);
            }

            lastScaffoldPlace.put(playerId, currentTime);
        }

        // 检测搭路方向一致性
        checkScaffoldDirection(player, event);

        // 检测空中搭路
        checkAirPlace(player, event);
    }

    // 非法放置检测
    private void checkIllegalPlace(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 检测放置非法方块
        if (material == Material.BEDROCK || material == Material.BARRIER ||
                material.toString().contains("COMMAND") || material.toString().contains("STRUCTURE")) {

            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("illegal_place", 12);
            plugin.getAlertManager().alertStaff(
                    player,
                    "非法放置: " + material
            );

            event.setCancelled(true);
        }

        // 检测在异常位置放置
        if (block.getY() > 255 || block.getY() < -64) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("impossible_place", 8);
            plugin.getAlertManager().alertStaff(player, "在不可能位置放置方块");
            event.setCancelled(true);
        }
    }

    // 方块交互距离检测
    private void checkInteractReach(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        double distance = player.getLocation().distance(event.getClickedBlock().getLocation());
        double maxInteractDistance = plugin.getConfig().getDouble("interaction.max-block-interact-distance", 6.0);

        if (distance > maxInteractDistance) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("interact_reach", 4);
            plugin.getAlertManager().alertStaff(
                    player,
                    "超距交互: " + String.format("%.2f", distance) + " 格"
            );
        }
    }

    // 自动工具检测
    private void checkAutoTool(Player player, PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) return;

        // 检测异常快速的最佳工具切换
        ItemStack currentTool = player.getInventory().getItemInMainHand();
        Block targetBlock = event.getClickedBlock();

        // 检查是否使用了最佳工具
        Material bestTool = getBestToolForBlock(targetBlock.getType());
        if (bestTool != null && currentTool.getType() == bestTool) {
            // 记录工具切换模式
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("auto_tool_pattern", 1);

            if (data.getViolationLevel("auto_tool_pattern") > 20) {
                plugin.getAlertManager().alertStaff(player, "疑似自动工具切换");
            }
        }
    }

    // 方块放置距离检测
    private void checkBlockReach(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        double distance = player.getLocation().distance(event.getBlock().getLocation());
        double maxPlaceDistance = 6.0;

        if (distance > maxPlaceDistance) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("place_reach", 5);
            plugin.getAlertManager().alertStaff(
                    player,
                    "超距放置: " + String.format("%.2f", distance) + " 格"
            );
        }
    }

    // 工具方法
    private void checkSpecificBlockBreakSpeed(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();

        // 对特定方块进行更严格的检测
        if (material.getHardness() > 2.0) { // 硬方块
            long expectedTime = (long) (material.getHardness() * 1000);
            long actualTime = System.currentTimeMillis() - lastBlockBreak.getOrDefault(player.getUniqueId(), 0L);

            if (actualTime < expectedTime * 0.3) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("fast_hard_break", 6);
                plugin.getAlertManager().alertStaff(
                        player,
                        "快速破坏硬方块: " + material + " (时间: " + actualTime + "ms)"
                );
            }
        }
    }

    private void checkAreaBreak(Player player, org.bukkit.event.block.BlockBreakEvent event) {
        // 检测3x3区域破坏
        Block center = event.getBlock();
        int brokenInArea = 0;

        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                Block relative = center.getRelative(x, 0, z);
                if (relative.getType().isAir()) {
                    brokenInArea++;
                }
            }
        }

        if (brokenInArea >= 5) { // 3x3区域中5个以上方块被破坏
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("area_break", 7);
            plugin.getAlertManager().alertStaff(player, "区域破坏嫌疑 (3x3)");
        }
    }

    private double calculateMiningEfficiency(Player player, Block oreBlock) {
        // 计算挖掘效率（挖到矿石的比例）
        Map<Material, Integer> stats = oreMiningStats.get(player.getUniqueId());
        if (stats == null) return 0;

        int totalOres = stats.values().stream().mapToInt(Integer::intValue).sum();
        int totalBlocks = getTotalBlocksBroken(player);

        if (totalBlocks == 0) return 0;
        return (double) totalOres / totalBlocks;
    }

    private void checkConsecutiveOreMining(Player player, Material oreType) {
        // 检测连续挖掘同种矿石
        UUID playerId = player.getUniqueId();
        // 实现连续挖掘检测逻辑
    }

    private void checkMiningDirection(Player player, Block oreBlock) {
        // 检测挖掘方向（是否直接朝向矿石）
        Location playerLoc = player.getLocation();
        Location oreLoc = oreBlock.getLocation();

        Vector toOre = oreLoc.toVector().subtract(playerLoc.toVector()).normalize();
        Vector viewDirection = playerLoc.getDirection();

        double dot = toOre.dot(viewDirection);
        if (dot > 0.95) { // 几乎直接朝向矿石
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("direct_ore_targeting", 3);
        }
    }

    private void checkOreToWasteRatio(Player player, Block block) {
        // 检测矿石与废石的比例（X-Ray特征）
        // 实现比例分析逻辑
    }

    private long calculateExpectedBreakTime(Player player, Block block) {
        // 计算预期破坏时间（考虑工具、附魔等）
        float hardness = block.getType().getHardness();
        if (hardness <= 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        float speedMultiplier = getToolEfficiency(tool, block.getType());

        return (long) (hardness * 1000 / speedMultiplier);
    }

    private boolean isInProtectedRegion(Location location) {
        // 检测是否在保护区域内（需要与领地插件集成）
        return false;
    }

    private void checkScaffoldDirection(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        // 检测搭路方向的一致性
        Vector movementDir = player.getLocation().getDirection();
        Vector placeDir = event.getBlock().getLocation().toVector().subtract(player.getLocation().toVector()).normalize();

        double dot = movementDir.dot(placeDir);
        if (dot > 0.8) { // 放置方向与移动方向高度一致
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("scaffold_direction", 2);
        }
    }

    private void checkAirPlace(Player player, org.bukkit.event.block.BlockPlaceEvent event) {
        // 检测空中放置方块
        Block block = event.getBlock();
        Block below = block.getRelative(0, -1, 0);

        if (below.getType().isAir() && !player.isOnGround()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("air_place", 4);
            plugin.getAlertManager().alertStaff(player, "空中放置方块嫌疑");
        }
    }

    private Material getBestToolForBlock(Material blockType) {
        // 返回挖掘该方块的最佳工具
        // 简化实现
        if (blockType.toString().contains("ORE")) {
            return Material.IRON_PICKAXE;
        }
        if (blockType.toString().contains("LOG")) {
            return Material.IRON_AXE;
        }
        return null;
    }

    private float getToolEfficiency(ItemStack tool, Material blockType) {
        // 计算工具效率
        if (tool == null) return 1.0f;

        float efficiency = 1.0f;

        // 检查工具类型
        if (isRightToolForBlock(tool.getType(), blockType)) {
            efficiency *= 5.0f; // 正确工具的基础倍率
        }

        // 检查效率附魔
        if (tool.hasItemMeta() && tool.getItemMeta().hasEnchants()) {
            org.bukkit.enchantments.Enchantment enchant = org.bukkit.enchantments.Enchantment.EFFICIENCY;
            if (tool.getItemMeta().hasEnchant(enchant)) {
                efficiency += tool.getItemMeta().getEnchantLevel(enchant) * 0.5f;
            }
        }

        return efficiency;
    }

    private boolean isRightToolForBlock(Material tool, Material block) {
        // 检查工具是否适合挖掘该方块
        if (tool.toString().contains("PICKAXE") && block.toString().contains("ORE")) return true;
        if (tool.toString().contains("AXE") && block.toString().contains("LOG")) return true;
        if (tool.toString().contains("SHOVEL") && block.toString().contains("DIRT")) return true;
        return false;
    }

    private int getTotalBlocksBroken(Player player) {
        // 获取总破坏方块数（需要从统计数据中获取）
        return 100; // 示例值
    }
}

class BackpackDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastInventoryClick = new HashMap<>();
    private final HashMap<UUID, Integer> clickCount = new HashMap<>();
    private final HashMap<UUID, Long> lastShiftClick = new HashMap<>();
    private final HashMap<UUID, Integer> shiftClickCount = new HashMap<>();
    private final HashMap<UUID, Long> lastItemMove = new HashMap<>();
    private final HashMap<UUID, Set<Material>> suspiciousItemsFound = new HashMap<>();

    // 可疑物品列表
    private final Set<Material> suspiciousItems = Set.of(
            Material.BEDROCK, Material.BARRIER, Material.COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK, Material.JIGSAW, Material.STRUCTURE_VOID
    );

    public BackpackDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
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

    @EventHandler
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
        if (System.currentTimeMillis() % 10000 < 50) { // 每10秒检查一次
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
}

class NetworkDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastPacketTime = new HashMap<>();
    private final HashMap<UUID, Integer> packetCount = new HashMap<>();
    private final HashMap<UUID, Long> lastMovementPacket = new HashMap<>();
    private final HashMap<UUID, Integer> movementPacketCount = new HashMap<>();
    private final HashMap<UUID, Set<String>> suspiciousPackets = new HashMap<>();
    private final HashMap<UUID, Long> lastKeepAlive = new HashMap<>();

    // 可疑数据包模式
    private final Set<String> suspiciousPatterns = Set.of(
            "Invalid payload", "Bad packet", "Out of order"
    );

    public NetworkDetector(gofd plugin) {
        this.plugin = plugin;
    }

    // 数据包洪水检测 - 检测异常数据包频率
    public void checkPacketFlood(Player player, String packetType) {
        if (!plugin.isEnablePacketChecks()) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 通用数据包频率检测
        Long lastPacket = lastPacketTime.get(playerId);
        if (lastPacket != null) {
            long timeSinceLastPacket = currentTime - lastPacket;

            if (timeSinceLastPacket < 10) { // 100包/秒
                int count = packetCount.getOrDefault(playerId, 0) + 1;
                packetCount.put(playerId, count);

                if (count > 100) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("packet_flood", 6);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "数据包洪水: " + packetType + " (" + count + " 包/秒)"
                    );
                }
            } else {
                packetCount.put(playerId, 0);
            }
        }

        lastPacketTime.put(playerId, currentTime);

        // 特定类型数据包检测
        if (packetType.equals("Movement")) {
            checkMovementPackets(player);
        } else if (packetType.equals("Inventory")) {
            checkInventoryPackets(player);
        }
    }

    // 可疑数据包检测 - 检测异常数据包内容
    public void checkSuspiciousPacket(Player player, String packetData) {
        if (!plugin.isEnablePacketChecks()) return;

        // 检测可疑数据包内容
        for (String pattern : suspiciousPatterns) {
            if (packetData.contains(pattern)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_packet", 8);
                plugin.getAlertManager().alertStaff(
                        player,
                        "可疑数据包: " + pattern + " - " + packetData.substring(0, Math.min(50, packetData.length()))
                );
                break;
            }
        }

        // 检测数据包注入
        if (isPacketInjection(packetData)) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("packet_injection", 15);
            plugin.getAlertManager().alertStaff(player, "数据包注入嫌疑");
        }

        // 记录可疑数据包
        recordSuspiciousPacket(player, packetData);
    }

    // 移动数据包检测
    private void checkMovementPackets(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        Long lastMovePacket = lastMovementPacket.get(playerId);
        if (lastMovePacket != null) {
            long timeSinceLastMove = currentTime - lastMovePacket;

            if (timeSinceLastMove < 5) { // 200包/秒
                int count = movementPacketCount.getOrDefault(playerId, 0) + 1;
                movementPacketCount.put(playerId, count);

                if (count > 200) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("movement_packet_flood", 5);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "移动数据包洪水: " + count + " 包/秒"
                    );
                }
            } else {
                movementPacketCount.put(playerId, 0);
            }
        }

        lastMovementPacket.put(playerId, currentTime);
    }

    // 库存数据包检测
    private void checkInventoryPackets(Player player) {
        // 检测库存操作数据包频率
        // 实现库存数据包检测逻辑
    }

    // 保持连接检测
    public void checkKeepAlive(Player player) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastAlive = lastKeepAlive.get(playerId);

        if (lastAlive != null) {
            long timeSinceLastAlive = currentTime - lastAlive;

            // 检测异常的保持连接间隔
            if (timeSinceLastAlive > 30000) { // 30秒无响应
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("keep_alive_anomaly", 3);
                plugin.getAlertManager().alertStaff(
                        player,
                        "保持连接异常: " + (timeSinceLastAlive / 1000) + " 秒无响应"
                );
            }
        }

        lastKeepAlive.put(playerId, currentTime);
    }

    // 延迟检测
    public void checkLatencyAnomaly(Player player) {
        int ping = getPing(player);

        // 检测异常延迟模式
        if (ping < 1) { // 不可能的低延迟
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("impossible_latency", 10);
            plugin.getAlertManager().alertStaff(player, "不可能的低延迟: " + ping + "ms");
        }

        // 检测延迟波动（可能使用延迟欺骗）
        checkLatencyFluctuation(player, ping);
    }

    // 数据包顺序检测
    public void checkPacketOrder(Player player, String packetType, long sequence) {
        // 检测数据包顺序异常
        // 实现数据包序列检测逻辑
    }

    // 工具方法
    private boolean isPacketInjection(String packetData) {
        // 检测数据包注入特征
        return packetData.contains("nbt") ||
                packetData.contains("entity_metadata") ||
                packetData.length() > 10000; // 过大的数据包
    }

    private void recordSuspiciousPacket(Player player, String packetData) {
        UUID playerId = player.getUniqueId();
        Set<String> packets = suspiciousPackets.getOrDefault(playerId, new HashSet<>());

        // 只记录前100个字符
        String shortened = packetData.substring(0, Math.min(100, packetData.length()));
        packets.add(shortened);

        if (packets.size() > 10) {
            // 记录到文件以便进一步分析
            logSuspiciousPackets(player, packets);
            packets.clear();
        }

        suspiciousPackets.put(playerId, packets);
    }

    private void logSuspiciousPackets(Player player, Set<String> packets) {
        try {
            File logFile = new File(plugin.getDataFolder(), "suspicious_packets.log");
            if (!logFile.exists()) {
                logFile.createNewFile();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String logEntry = String.format("[%s] %s: %s\n",
                    timestamp, player.getName(), String.join(" | ", packets));

            java.nio.file.Files.write(
                    logFile.toPath(),
                    logEntry.getBytes(),
                    java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            plugin.getLogger().warning("无法写入可疑数据包日志: " + e.getMessage());
        }
    }

    private int getPing(Player player) {
        // 获取玩家延迟
        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            return (int) entityPlayer.getClass().getField("ping").get(entityPlayer);
        } catch (Exception e) {
            return 0;
        }
    }

    private void checkLatencyFluctuation(Player player, int currentPing) {
        // 检测延迟波动模式
        // 实现延迟波动检测逻辑
    }
}

class RandomCheckSystem {
    private final gofd plugin;
    private final Random random = new Random();
    private final HashMap<UUID, Integer> checkCounters = new HashMap<>();
    private final HashMap<UUID, Long> lastRandomCheck = new HashMap<>();

    // 检查类型权重
    private final Map<String, Integer> checkWeights = Map.of(
            "MOVEMENT", 25,
            "COMBAT", 20,
            "INVENTORY", 15,
            "ENVIRONMENT", 15,
            "NETWORK", 10,
            "ITEM", 10,
            "CHAT", 5
    );

    public RandomCheckSystem(gofd plugin) {
        this.plugin = plugin;
        startRandomChecks();
    }

    private void startRandomChecks() {
        // 每30秒执行一次随机检查
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // 每个玩家有15%的几率被随机检查
                    if (random.nextDouble() < 0.15) {
                        performRandomCheck(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20 * 30, 20 * 30); // 30秒间隔

        // 每5分钟执行一次深度随机检查
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    // 每个玩家有5%的几率被深度检查
                    if (random.nextDouble() < 0.05) {
                        performDeepRandomCheck(player);
                    }
                }
            }
        }.runTaskTimer(plugin, 20 * 60 * 5, 20 * 60 * 5); // 5分钟间隔
    }

    // 执行随机检查
    private void performRandomCheck(Player player) {
        if (player.hasPermission("gofdac.bypass")) return;

        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastCheck = lastRandomCheck.get(playerId);

        // 防止过于频繁的检查
        if (lastCheck != null && currentTime - lastCheck < 10000) {
            return;
        }

        lastRandomCheck.put(playerId, currentTime);

        // 根据权重随机选择检查类型
        String checkType = getRandomCheckType();

        switch (checkType) {
            case "MOVEMENT":
                performRandomMovementCheck(player);
                break;
            case "COMBAT":
                performRandomCombatCheck(player);
                break;
            case "INVENTORY":
                performRandomInventoryCheck(player);
                break;
            case "ENVIRONMENT":
                performRandomEnvironmentCheck(player);
                break;
            case "NETWORK":
                performRandomNetworkCheck(player);
                break;
            case "ITEM":
                performRandomItemCheck(player);
                break;
            case "CHAT":
                performRandomChatCheck(player);
                break;
        }

        // 更新检查计数器
        int count = checkCounters.getOrDefault(playerId, 0) + 1;
        checkCounters.put(playerId, count);

        plugin.getAlertManager().logDebug("对玩家 " + player.getName() + " 执行随机检查: " + checkType);
    }

    // 执行深度随机检查
    private void performDeepRandomCheck(Player player) {
        if (player.hasPermission("gofdac.bypass")) return;

        // 执行所有类型的检查
        performRandomMovementCheck(player);
        performRandomCombatCheck(player);
        performRandomInventoryCheck(player);
        performRandomEnvironmentCheck(player);
        performRandomNetworkCheck(player);
        performRandomItemCheck(player);
        performRandomChatCheck(player);

        plugin.getAlertManager().logDebug("对玩家 " + player.getName() + " 执行深度随机检查");
    }

    // 随机移动检查
    private void performRandomMovementCheck(Player player) {
        PlayerData data = plugin.getPlayerData(player);

        // 检查移动模式
        List<Location> movementHistory = data.getMovementHistory();
        if (movementHistory.size() > 10) {
            // 分析移动模式的随机性
            double randomness = calculateMovementRandomness(movementHistory);
            if (randomness < 0.1) { // 移动模式过于规律
                data.addViolation("patterned_movement", 2);
            }
        }

        // 检查当前速度
        Location currentLoc = player.getLocation();
        if (movementHistory.size() > 1) {
            Location lastLoc = movementHistory.get(movementHistory.size() - 2);
            double distance = currentLoc.distance(lastLoc);
            long timeDiff = System.currentTimeMillis() - data.getLastMoveTime();

            if (timeDiff > 0) {
                double speed = distance / (timeDiff / 1000.0);
                if (speed > 2.0 && player.isOnGround()) {
                    data.addViolation("random_check_speed", 3);
                }
            }
        }
    }

    // 随机战斗检查
    private void performRandomCombatCheck(Player player) {
        PlayerData data = plugin.getPlayerData(player);

        // 检查CPS
        double cps = data.getCPS();
        if (cps > 20) {
            data.addViolation("random_check_cps", 3);
        }

        // 检查攻击模式
        LinkedList<Long> attacks = data.attackTimestamps;
        if (attacks.size() > 5) {
            double regularity = calculateAttackRegularity(attacks);
            if (regularity > 0.9) {
                data.addViolation("random_check_attack_pattern", 2);
            }
        }
    }

    // 随机库存检查
    private void performRandomInventoryCheck(Player player) {
        // 检查库存中的可疑物品
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && isSuspiciousItem(item)) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("random_check_suspicious_item", 4);
                break;
            }
        }

        // 检查物品数量异常
        checkItemQuantityAnomalies(player);
    }

    // 随机环境检查
    private void performRandomEnvironmentCheck(Player player) {
        // 检查玩家位置是否异常
        Location loc = player.getLocation();
        if (loc.getY() < -64 || loc.getY() > 320) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("random_check_position", 5);
        }

        // 检查周围方块异常
        checkSurroundingBlocks(player);
    }

    // 随机网络检查
    private void performRandomNetworkCheck(Player player) {
        NetworkDetector networkDetector = plugin.networkDetector;
        if (networkDetector != null) {
            networkDetector.checkLatencyAnomaly(player);
            networkDetector.checkKeepAlive(player);
        }
    }

    // 随机物品检查
    private void performRandomItemCheck(Player player) {
        // 检查手持物品
        ItemStack handItem = player.getInventory().getItemInMainHand();
        if (handItem != null) {
            checkItemValidity(player, handItem);
        }

        // 检查装备
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            if (armor != null) {
                checkItemValidity(player, armor);
            }
        }
    }

    // 随机聊天检查
    private void performRandomChatCheck(Player player) {
        // 检查最近的聊天记录
        // 实现聊天记录分析
    }

    // 工具方法
    private String getRandomCheckType() {
        int totalWeight = checkWeights.values().stream().mapToInt(Integer::intValue).sum();
        int randomValue = random.nextInt(totalWeight);
        int currentWeight = 0;

        for (Map.Entry<String, Integer> entry : checkWeights.entrySet()) {
            currentWeight += entry.getValue();
            if (randomValue < currentWeight) {
                return entry.getKey();
            }
        }

        return "MOVEMENT"; // 默认值
    }

    private double calculateMovementRandomness(List<Location> movementHistory) {
        if (movementHistory.size() < 3) return 1.0;

        // 计算移动方向变化的随机性
        List<Double> directionChanges = new ArrayList<>();
        for (int i = 1; i < movementHistory.size() - 1; i++) {
            Location prev = movementHistory.get(i - 1);
            Location current = movementHistory.get(i);
            Location next = movementHistory.get(i + 1);

            double dir1 = Math.atan2(current.getZ() - prev.getZ(), current.getX() - prev.getX());
            double dir2 = Math.atan2(next.getZ() - current.getZ(), next.getX() - current.getX());
            double change = Math.abs(dir1 - dir2);

            directionChanges.add(change);
        }

        // 计算标准差（标准差越小，模式越规律）
        double mean = directionChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = directionChanges.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);

        return stdDev; // 返回随机性指标
    }

    private double calculateAttackRegularity(LinkedList<Long> attacks) {
        if (attacks.size() < 2) return 0;

        List<Long> intervals = new ArrayList<>();
        Iterator<Long> iterator = attacks.iterator();
        long prev = iterator.next();

        while (iterator.hasNext()) {
            long current = iterator.next();
            intervals.add(current - prev);
            prev = current;
        }

        // 计算间隔的一致性
        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double sumSqDiff = intervals.stream().mapToDouble(i -> Math.pow(i - mean, 2)).sum();
        double variance = sumSqDiff / intervals.size();
        double stdDev = Math.sqrt(variance);

        // 标准差越小，规律性越高
        return 1.0 - (stdDev / mean);
    }

    private boolean isSuspiciousItem(ItemStack item) {
        if (item == null) return false;

        // 检测非法附魔
        if (item.hasItemMeta() && item.getItemMeta().hasEnchants()) {
            for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry :
                    item.getEnchantments().entrySet()) {
                if (entry.getValue() > entry.getKey().getMaxLevel() + 5) {
                    return true;
                }
            }
        }

        // 检测非法堆叠
        if (item.getAmount() > item.getMaxStackSize()) {
            return true;
        }

        // 检测创造模式专属物品
        return isCreativeOnlyItem(item);
    }

    private boolean isCreativeOnlyItem(ItemStack item) {
        Material material = item.getType();
        return material == Material.COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW || material == Material.BARRIER ||
                material == Material.BEDROCK;
    }

    private void checkItemQuantityAnomalies(Player player) {
        // 检查物品数量异常（复制物品嫌疑）
        Map<Material, Integer> itemCounts = new HashMap<>();

        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null) {
                Material material = item.getType();
                int count = itemCounts.getOrDefault(material, 0) + item.getAmount();
                itemCounts.put(material, count);

                // 检查是否超过合理数量
                if (count > getReasonableQuantity(material)) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("suspicious_item_quantity", 6);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "可疑物品数量: " + material + " x" + count
                    );
                }
            }
        }
    }

    private int getReasonableQuantity(Material material) {
        // 返回该物品的合理数量上限
        if (material.toString().contains("DIAMOND")) return 64;
        if (material.toString().contains("EMERALD")) return 64;
        if (material.toString().contains("NETHERITE")) return 16;
        return 256; // 普通物品
    }

    private void checkSurroundingBlocks(Player player) {
        // 检查玩家周围的方块是否异常
        Location loc = player.getLocation();

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -3; z <= 3; z++) {
                    Block block = loc.clone().add(x, y, z).getBlock();
                    if (isSuspiciousBlock(block)) {
                        PlayerData data = plugin.getPlayerData(player);
                        data.addViolation("suspicious_surroundings", 3);
                        break;
                    }
                }
            }
        }
    }

    private boolean isSuspiciousBlock(Block block) {
        // 检测可疑方块（如命令方块、结构方块等）
        Material material = block.getType();
        return material == Material.COMMAND_BLOCK || material == Material.STRUCTURE_BLOCK ||
                material == Material.JIGSAW || material == Material.BARRIER;
    }

    private void checkItemValidity(Player player, ItemStack item) {
        // 检查物品有效性
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            String name = item.getItemMeta().getDisplayName();
            if (name.contains("作弊") || name.contains("hack") || name.contains("exploit")) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("suspicious_item_name", 4);
            }
        }
    }
}

class DebugManager {
    private final gofd plugin;
    private final Set<UUID> debugPlayers = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> debugCounters = new ConcurrentHashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    // 调试配置
    private boolean logToFile = true;
    private int maxLogEntries = 10000;

    public DebugManager(gofd plugin) {
        this.plugin = plugin;
    }

    // 记录调试信息
    public void logDebug(String message) {
        if (!plugin.isDebugMode()) return;

        String timestamp = dateFormat.format(new Date());
        String logMessage = String.format("[DEBUG] %s - %s", timestamp, message);

        // 控制台输出
        plugin.getLogger().info(logMessage);

        // 文件记录
        if (logToFile) {
            logToDebugFile(logMessage);
        }

        // 发送给调试玩家
        sendToDebugPlayers(logMessage);

        // 更新计数器
        updateDebugCounter("total_debug_messages");
    }

    // 记录检测详情
    public void logDetectionDetail(Player player, String checkType, String details) {
        if (!plugin.isDebugMode()) return;

        String message = String.format("检测详情 [%s] %s: %s",
                player.getName(), checkType, details);
        logDebug(message);

        updateDebugCounter("detection_" + checkType);
    }

    // 记录性能数据
    public void logPerformance(String operation, long time) {
        if (!plugin.isDebugMode()) return;

        String message = String.format("性能 [%s]: %dms", operation, time);
        logDebug(message);

        updateDebugCounter("performance_" + operation);
    }

    // 添加调试玩家
    public void addDebugPlayer(Player player) {
        debugPlayers.add(player.getUniqueId());
        logDebug("添加调试玩家: " + player.getName());
    }

    // 移除调试玩家
    public void removeDebugPlayer(Player player) {
        debugPlayers.remove(player.getUniqueId());
        logDebug("移除调试玩家: " + player.getName());
    }

    // 切换调试玩家状态
    public boolean toggleDebugPlayer(Player player) {
        if (debugPlayers.contains(player.getUniqueId())) {
            removeDebugPlayer(player);
            return false;
        } else {
            addDebugPlayer(player);
            return true;
        }
    }

    // 生成调试报告
    public String generateDebugReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== 调试报告 ===\n");
        report.append("调试玩家数量: ").append(debugPlayers.size()).append("\n");
        report.append("总调试消息: ").append(debugCounters.getOrDefault("total_debug_messages", 0)).append("\n");

        // 添加各检测类型的计数
        for (Map.Entry<String, Integer> entry : debugCounters.entrySet()) {
            if (entry.getKey().startsWith("detection_")) {
                report.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        return report.toString();
    }

    // 清理旧日志
    public void cleanupOldLogs() {
        // 实现日志文件清理
        try {
            File debugFile = new File(plugin.getDataFolder(), "debug.log");
            if (debugFile.exists() && debugFile.length() > 1024 * 1024) { // 1MB
                // 备份并清理日志文件
                backupDebugLog();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("清理调试日志失败: " + e.getMessage());
        }
    }

    // 私有方法
    private void logToDebugFile(String message) {
        try {
            File debugFile = new File(plugin.getDataFolder(), "debug.log");
            if (!debugFile.exists()) {
                debugFile.createNewFile();
            }

            Files.write(debugFile.toPath(), (message + "\n").getBytes(),
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("写入调试日志失败: " + e.getMessage());
        }
    }

    private void sendToDebugPlayers(String message) {
        for (UUID playerId : debugPlayers) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.GRAY + "[DEBUG] " + ChatColor.WHITE + message);
            }
        }
    }

    private void updateDebugCounter(String counter) {
        debugCounters.put(counter, debugCounters.getOrDefault(counter, 0) + 1);
    }

    private void backupDebugLog() {
        try {
            File debugFile = new File(plugin.getDataFolder(), "debug.log");
            File backupFile = new File(plugin.getDataFolder(),
                    "debug_backup_" + System.currentTimeMillis() + ".log");

            Files.move(debugFile.toPath(), backupFile.toPath());

            // 创建新的日志文件
            debugFile.createNewFile();

            logDebug("调试日志已备份: " + backupFile.getName());
        } catch (IOException e) {
            plugin.getLogger().warning("备份调试日志失败: " + e.getMessage());
        }
    }

    // Getter 方法
    public Set<UUID> getDebugPlayers() {
        return new HashSet<>(debugPlayers);
    }

    public Map<String, Integer> getDebugCounters() {
        return new HashMap<>(debugCounters);
    }
}

class PerformanceMonitor {
    private final gofd plugin;
    private final LinkedList<Long> detectionTimes = new LinkedList<>();
    private final LinkedList<Long> eventTimestamps = new LinkedList<>();
    private long lastCPUTime = 0;
    private long lastCPUTimestamp = 0;

    // 性能配置
    private final int MAX_SAMPLES = 1000;
    private final long SAMPLE_INTERVAL = 1000L; // 1秒

    public PerformanceMonitor(gofd plugin) {
        this.plugin = plugin;
        startMonitoring();
    }

    private void startMonitoring() {
        // 启动性能监控任务
        new BukkitRunnable() {
            @Override
            public void run() {
                cleanupOldSamples();
                monitorCPUUsage();
            }
        }.runTaskTimer(plugin, 20L, 20L); // 每秒执行一次
    }

    // 记录检测时间
    public void recordDetectionTime(long time) {
        synchronized (detectionTimes) {
            detectionTimes.add(time);
            if (detectionTimes.size() > MAX_SAMPLES) {
                detectionTimes.removeFirst();
            }
        }

        plugin.recordPerformance("last_detection", time);
    }

    // 记录事件时间戳
    public void recordEvent() {
        synchronized (eventTimestamps) {
            eventTimestamps.add(System.currentTimeMillis());
        }
    }

    // 获取平均检测时间
    public double getAverageDetectionTime() {
        synchronized (detectionTimes) {
            if (detectionTimes.isEmpty()) return 0.0;
            return detectionTimes.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }
    }

    // 获取最大检测时间
    public long getMaxDetectionTime() {
        synchronized (detectionTimes) {
            if (detectionTimes.isEmpty()) return 0;
            return detectionTimes.stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }

    // 获取事件处理速率
    public double getEventsPerSecond() {
        synchronized (eventTimestamps) {
            long currentTime = System.currentTimeMillis();
            // 计算最近5秒内的事件数量
            long count = eventTimestamps.stream()
                    .filter(timestamp -> currentTime - timestamp < 5000)
                    .count();
            return count / 5.0;
        }
    }

    // 获取CPU使用率
    public double getCPUUsage() {
        // 简化的CPU使用率计算
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        return (double) usedMemory / runtime.maxMemory() * 100;
    }

    // 清理旧样本
    private void cleanupOldSamples() {
        long currentTime = System.currentTimeMillis();

        synchronized (eventTimestamps) {
            eventTimestamps.removeIf(timestamp -> currentTime - timestamp > 30000); // 保留30秒
        }

        synchronized (detectionTimes) {
            if (detectionTimes.size() > MAX_SAMPLES) {
                detectionTimes.subList(0, detectionTimes.size() - MAX_SAMPLES).clear();
            }
        }
    }

    // 监控CPU使用率
    private void monitorCPUUsage() {
        long currentTime = System.currentTimeMillis();
        long currentCPUTime = Thread.activeCount(); // 简化的CPU指标

        if (lastCPUTimestamp > 0) {
            long timeDiff = currentTime - lastCPUTimestamp;
            long cpuDiff = currentCPUTime - lastCPUTime;

            // 计算CPU使用率变化
            double cpuUsage = (double) cpuDiff / timeDiff * 100;
            plugin.recordPerformance("cpu_usage", (long) cpuUsage);
        }

        lastCPUTime = currentCPUTime;
        lastCPUTimestamp = currentTime;
    }

    // 性能报告
    public String generatePerformanceReport() {
        return String.format(
                "性能报告: 平均延迟=%.2fms, 最大延迟=%dms, 事件/秒=%.1f, CPU=%.1f%%",
                getAverageDetectionTime(),
                getMaxDetectionTime(),
                getEventsPerSecond(),
                getCPUUsage()
        );
    }

    // 检查性能是否正常
    public boolean isPerformanceNormal() {
        double avgTime = getAverageDetectionTime();
        double eventsPerSecond = getEventsPerSecond();

        return avgTime < 10.0 && eventsPerSecond < 1000.0;
    }

    // 获取性能警告
    public List<String> getPerformanceWarnings() {
        List<String> warnings = new ArrayList<>();

        double avgTime = getAverageDetectionTime();
        if (avgTime > 10.0) {
            warnings.add("高检测延迟: " + String.format("%.2f", avgTime) + "ms");
        }

        double eventsPerSecond = getEventsPerSecond();
        if (eventsPerSecond > 1000.0) {
            warnings.add("高事件频率: " + String.format("%.1f", eventsPerSecond) + " 事件/秒");
        }

        double cpuUsage = getCPUUsage();
        if (cpuUsage > 80.0) {
            warnings.add("高CPU使用率: " + String.format("%.1f", cpuUsage) + "%");
        }

        return warnings;
    }
}

class WurstSpecificDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Integer> freecamViolations = new HashMap<>();
    private final HashMap<UUID, Location> lastValidLocation = new HashMap<>();
    private final HashMap<UUID, Long> lastFreecamCheck = new HashMap<>();

    public WurstSpecificDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkFreecam(player, event);
        checkXRayBehavior(player, event);
        checkDerp(player, event);
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        checkFastBreakWurst(player, event);
    }

    // Freecam 检测
    private void checkFreecam(Player player, PlayerMoveEvent event) {
        UUID playerId = player.getUniqueId();
        Location currentLoc = event.getTo();
        Location lastValid = lastValidLocation.get(playerId);

        if (lastValid != null) {
            double distance = currentLoc.distance(lastValid);

            // 检测异常的位置跳跃（Freecam 特征）
            if (distance > 10 && !player.isInsideVehicle()) {
                int violations = freecamViolations.getOrDefault(playerId, 0) + 1;
                freecamViolations.put(playerId, violations);

                if (violations > 1) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("freecam", 15);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "Freecam 嫌疑 (位置跳跃: " + String.format("%.1f", distance) + " 格)"
                    );
                }
            }
        }

        // 更新有效位置（只有当位置合理时）
        if (isValidLocation(player, currentLoc)) {
            lastValidLocation.put(playerId, currentLoc);
            freecamViolations.put(playerId, 0); // 重置违规计数
        }
    }

    // X-Ray 行为检测
    private void checkXRayBehavior(Player player, PlayerMoveEvent event) {
        // 检测玩家是否直接朝珍贵矿石挖掘
        Location loc = player.getLocation();
        Vector direction = loc.getDirection();

        // 检查视线方向上的方块
        for (int i = 1; i <= 10; i++) {
            Location checkLoc = loc.clone().add(direction.clone().multiply(i));
            Block block = checkLoc.getBlock();

            if (isValuableOre(block.getType())) {
                PlayerData data = plugin.getPlayerData(player);
                data.addViolation("xray_behavior", 8);
                plugin.getAlertManager().alertStaff(
                        player,
                        "X-Ray 行为嫌疑 (直接朝向: " + block.getType() + ")"
                );
                break;
            }

            // 遇到固体方块就停止检查
            if (block.getType().isSolid() && !isTransparentBlock(block.getType())) {
                break;
            }
        }
    }

    // Derp 检测（头部随机旋转）
    private void checkDerp(Player player, PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();

        float yawChange = Math.abs(to.getYaw() - from.getYaw());
        float pitchChange = Math.abs(to.getPitch() - from.getPitch());

        // 检测异常的头部旋转模式
        if ((yawChange > 90 || pitchChange > 45) &&
                !player.isInsideVehicle() && player.isOnGround()) {
            PlayerData data = plugin.getPlayerData(player);
            data.addViolation("derp", 6);
            plugin.getAlertManager().alertStaff(
                    player,
                    "Derp 嫌疑 (Yaw变化: " + String.format("%.1f", yawChange) +
                            ", Pitch变化: " + String.format("%.1f", pitchChange) + ")"
            );
        }
    }

    // Wurst 快速破坏检测
    private void checkFastBreakWurst(Player player, PlayerInteractEvent event) {
        if (event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null) {
                long expectedTime = getExpectedBreakTime(player, block);
                long actualTime = System.currentTimeMillis() - getLastBreakTime(player);

                // 检测异常快速的破坏
                if (actualTime < expectedTime * 0.3) {
                    PlayerData data = plugin.getPlayerData(player);
                    data.addViolation("fast_break_wurst", 10);
                    plugin.getAlertManager().alertStaff(
                            player,
                            "Wurst 快速破坏嫌疑 (预期: " + expectedTime + "ms, 实际: " + actualTime + "ms)"
                    );
                }
            }
        }
    }

    // 工具方法
    private boolean isValidLocation(Player player, Location loc) {
        // 检查位置是否合理（不在墙内、不在虚空等）
        if (loc.getY() < -64 || loc.getY() > 320) return false;

        // 检查是否在固体方块内
        Block feet = loc.getBlock();
        if (feet.getType().isSolid()) return false;

        Block head = loc.clone().add(0, 1, 0).getBlock();
        if (head.getType().isSolid()) return false;

        return true;
    }

    private boolean isValuableOre(Material material) {
        return material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE ||
                material == Material.EMERALD_ORE || material == Material.DEEPSLATE_EMERALD_ORE ||
                material == Material.ANCIENT_DEBRIS;
    }

    private boolean isTransparentBlock(Material material) {
        return material == Material.GLASS || material == Material.WATER ||
                material.toString().contains("LEAVES") || material.toString().contains("ICE");
    }

    private long getExpectedBreakTime(Player player, Block block) {
        // 计算预期破坏时间（简化版）
        float hardness = block.getType().getHardness();
        if (hardness <= 0) return 0;

        ItemStack tool = player.getInventory().getItemInMainHand();
        float speed = getToolSpeed(tool, block.getType());

        return (long) (hardness * 1000 / speed);
    }

    private float getToolSpeed(ItemStack tool, Material blockType) {
        // 计算工具速度（简化版）
        float speed = 1.0f;

        if (isRightToolForBlock(tool.getType(), blockType)) {
            speed = 5.0f;
        }

        if (tool.containsEnchantment(Enchantment.EFFICIENCY)) {
            speed += tool.getEnchantmentLevel(Enchantment.EFFICIENCY) * 0.5f;
        }

        return speed;
    }

    private boolean isRightToolForBlock(Material tool, Material block) {
        // 检查工具是否适合挖掘该方块
        if (tool.toString().contains("PICKAXE") && block.toString().contains("ORE")) return true;
        if (tool.toString().contains("AXE") && block.toString().contains("LOG")) return true;
        if (tool.toString().contains("SHOVEL") && block.toString().contains("DIRT")) return true;
        return false;
    }

    private long getLastBreakTime(Player player) {
        // 获取上次破坏时间（需要从数据中获取）
        return System.currentTimeMillis() - 1000; // 示例值
    }
}

class StrictMovementDetector implements Listener {
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

    @EventHandler
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
}

// 移动档案类
class MovementProfile {
    private final LinkedList<Location> locationHistory = new LinkedList<>();
    private final LinkedList<Long> timeHistory = new LinkedList<>();
    private final LinkedList<Double> verticalHistory = new LinkedList<>();
    private int airTicks = 0;
    private long lastUpdate = System.currentTimeMillis();

    public void update(Location newLocation) {
        long currentTime = System.currentTimeMillis();

        // 更新历史记录
        locationHistory.add(newLocation.clone());
        timeHistory.add(currentTime);

        if (locationHistory.size() > 1) {
            double verticalChange = newLocation.getY() - locationHistory.get(locationHistory.size() - 2).getY();
            verticalHistory.add(verticalChange);
        }

        // 限制历史记录大小
        if (locationHistory.size() > 50) {
            locationHistory.removeFirst();
            timeHistory.removeFirst();
            if (verticalHistory.size() > 0) verticalHistory.removeFirst();
        }

        // 更新空中时间
        if (isInAir(newLocation)) {
            airTicks++;
        } else {
            airTicks = 0;
        }

        lastUpdate = currentTime;
    }

    public int getAirTime() {
        return airTicks;
    }

    public double getVerticalConsistency() {
        if (verticalHistory.size() < 5) return 0;

        // 计算垂直移动的一致性
        double mean = verticalHistory.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = verticalHistory.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance, 1.0);
    }

    public double getCurrentSpeed() {
        if (locationHistory.size() < 2) return 0;

        Location current = locationHistory.getLast();
        Location previous = locationHistory.get(locationHistory.size() - 2);
        long timeDiff = timeHistory.getLast() - timeHistory.get(timeHistory.size() - 2);

        if (timeDiff == 0) return 0;

        double distance = current.distance(previous);
        return distance / (timeDiff / 1000.0);
    }

    public double getCurrentAcceleration() {
        if (locationHistory.size() < 3) return 0;

        double speed1 = getSpeedAt(locationHistory.size() - 2);
        double speed2 = getSpeedAt(locationHistory.size() - 1);
        long timeDiff = timeHistory.getLast() - timeHistory.get(timeHistory.size() - 2);

        if (timeDiff == 0) return 0;

        return Math.abs(speed2 - speed1) / (timeDiff / 1000.0);
    }

    public double getTimeConsistency() {
        if (timeHistory.size() < 10) return 0;

        // 计算时间间隔的一致性
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < timeHistory.size(); i++) {
            intervals.add(timeHistory.get(i) - timeHistory.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
                .mapToDouble(i -> Math.pow(i - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance / (mean * mean), 1.0);
    }

    public double getAirControlEfficiency() {
        if (locationHistory.size() < 10) return 0;

        // 计算空中移动的效率（正常玩家在空中控制能力有限）
        int airMoves = 0;
        int totalMoves = 0;

        for (int i = 1; i < locationHistory.size(); i++) {
            if (isInAir(locationHistory.get(i))) {
                totalMoves++;
                Location current = locationHistory.get(i);
                Location previous = locationHistory.get(i - 1);
                double distance = current.distance(previous);
                if (distance > 0.1) { // 有显著移动
                    airMoves++;
                }
            }
        }

        if (totalMoves == 0) return 0;
        return (double) airMoves / totalMoves;
    }

    private double getSpeedAt(int index) {
        if (index < 1 || index >= locationHistory.size()) return 0;

        Location current = locationHistory.get(index);
        Location previous = locationHistory.get(index - 1);
        long timeDiff = timeHistory.get(index) - timeHistory.get(index - 1);

        if (timeDiff == 0) return 0;

        double distance = current.distance(previous);
        return distance / (timeDiff / 1000.0);
    }

    private boolean isInAir(Location loc) {
        Location below = loc.clone().subtract(0, 0.1, 0);
        return !below.getBlock().getType().isSolid();
    }
}

class StrictPunishmentManager {
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
        int totalVL = data.getTotalViolations();

        // 基于总违规值的惩罚
        if (totalVL > PERMA_BAN_THRESHOLD) {
            permabanPlayer(player, "多次严重违规行为");
        } else if (totalVL > TEMP_BAN_THRESHOLD) {
            tempBanPlayer(player, "严重违规行为", 1440); // 24小时
        } else if (totalVL > KICK_THRESHOLD) {
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
            player.kickPlayer(String.valueOf(Component.text("你已被踢出服务器\n原因: " + reason).color(NamedTextColor.RED)));
            plugin.getAlertManager().alertStaff(player, "已踢出玩家 - " + reason);
        }
    }

    private void tempBanPlayer(Player player, String reason, int minutes) {
        UUID playerId = player.getUniqueId();

        if (!bannedPlayers.contains(playerId)) {
            tempBans.put(playerId, System.currentTimeMillis() + (minutes * 60 * 1000));
            player.kickPlayer(String.valueOf(Component.text("你已被临时封禁\n原因: " + reason +
                    "\n解封时间: " + minutes + "分钟后").color(NamedTextColor.RED)));

            plugin.getAlertManager().alertStaff(player,
                    "已临时封禁玩家 (" + minutes + "分钟) - " + reason);
        }
    }

    private void permabanPlayer(Player player, String reason) {
        UUID playerId = player.getUniqueId();

        if (!bannedPlayers.contains(playerId)) {
            bannedPlayers.add(playerId);
            player.kickPlayer(String.valueOf(Component.text("你已被永久封禁\n原因: " + reason).color(NamedTextColor.RED)));

            plugin.getAlertManager().alertStaff(player, "已永久封禁玩家 - " + reason);

            // 记录到封禁列表
            recordBan(player, reason);
        }
    }

    private void recordBan(Player player, String reason) {
        try {
            File banFile = new File(plugin.getDataFolder(), "bans.log");
            if (!banFile.exists()) {
                banFile.createNewFile();
            }

            String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
            String logEntry = String.format("[%s] %s (%s) - %s\n",
                    timestamp, player.getName(), player.getUniqueId(), reason);

            Files.write(banFile.toPath(), logEntry.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("无法写入封禁日志: " + e.getMessage());
        }
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

class StrictEnvironmentDetector implements Listener {
    private final gofd plugin;
    private final HashMap<UUID, Long> lastBlockBreak = new HashMap<>();
    private final HashMap<UUID, Integer> fastBreakCount = new HashMap<>();

    // 极严格阈值
    private final long MIN_BREAK_INTERVAL = 200; // 毫秒
    private final int MAX_BLOCKS_PER_SECOND = 3;

    public StrictEnvironmentDetector(gofd plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isEnableInteractionChecks()) return;

        Player player = event.getPlayer();
        if (player.hasPermission("gofdac.bypass")) return;

        PlayerData data = plugin.getPlayerData(player);

        checkUltraFastBreak(player, event, data);
        checkXRayStrict(player, event, data);
        checkNukerStrict(player, event, data);
        checkIllegalBreakStrict(player, event, data);
    }

    // 超快速破坏检测
    private void checkUltraFastBreak(Player player, BlockBreakEvent event, PlayerData data) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();
        Long lastBreak = lastBlockBreak.get(playerId);

        if (lastBreak != null) {
            long timeSinceLastBreak = currentTime - lastBreak;

            if (timeSinceLastBreak < MIN_BREAK_INTERVAL) {
                int count = fastBreakCount.getOrDefault(playerId, 0) + 1;
                fastBreakCount.put(playerId, count);

                if (count > 2) {
                    data.addViolation("ultra_fast_break", 15);
                    plugin.getAlertManager().alertStaff(
                            player, "超快速破坏检测: " + timeSinceLastBreak + "ms 间隔"
                    );
                }
            } else {
                fastBreakCount.put(playerId, 0);
            }
        }

        lastBlockBreak.put(playerId, currentTime);

        // 检测每秒破坏方块数
        checkBlocksPerSecond(player, data);
    }

    // 严格 X-Ray 检测
    private void checkXRayStrict(Player player, BlockBreakEvent event, PlayerData data) {
        Block block = event.getBlock();

        if (isValuableBlock(block.getType())) {
            // 检测挖掘路径是否直接朝向珍贵方块
            Location playerLoc = player.getLocation();
            Location blockLoc = block.getLocation();

            Vector toBlock = blockLoc.toVector().subtract(playerLoc.toVector()).normalize();
            Vector viewDir = playerLoc.getDirection();

            double dot = toBlock.dot(viewDir);

            if (dot > 0.98) { // 几乎直接朝向
                data.addViolation("xray_strict", 20);
                plugin.getAlertManager().alertStaff(
                        player, "严格 X-Ray 检测: 直接挖掘 " + block.getType()
                );
            }
        }
    }

    // 严格 Nuker 检测
    private void checkNukerStrict(Player player, BlockBreakEvent event, PlayerData data) {
        // 检测同时破坏多个方块
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // 这里需要记录破坏的方块位置和时间
        // 简化实现：检测破坏频率

        int breakCount = data.getViolationLevel("nuker_count") + 1;
        data.addViolation("nuker_count", 1);

        if (breakCount > MAX_BLOCKS_PER_SECOND) {
            data.addViolation("nuker_strict", 25);
            plugin.getAlertManager().alertStaff(
                    player, "严格 Nuker 检测: " + breakCount + " 方块/秒"
            );
            data.addViolation("nuker_count", -breakCount); // 重置计数
        }
    }

    // 严格非法破坏检测
    private void checkIllegalBreakStrict(Player player, BlockBreakEvent event, PlayerData data) {
        Block block = event.getBlock();

        // 检测破坏不可破坏的方块
        if (isUnbreakableBlock(block.getType())) {
            data.addViolation("illegal_break_strict", 30);
            plugin.getAlertManager().alertStaff(
                    player, "严格非法破坏: " + block.getType()
            );
            event.setCancelled(true);
        }

        // 检测在保护区域破坏
        if (isInProtectedArea(block.getLocation())) {
            data.addViolation("protected_break_strict", 20);
            plugin.getAlertManager().alertStaff(player, "保护区域破坏检测");
            event.setCancelled(true);
        }
    }

    // 每秒破坏方块数检测
    private void checkBlocksPerSecond(Player player, PlayerData data) {
        // 实现每秒破坏方块数统计
        // 需要记录时间窗口内的破坏次数
    }

    // 工具方法
    private boolean isValuableBlock(Material material) {
        return material == Material.DIAMOND_ORE || material == Material.DEEPSLATE_DIAMOND_ORE ||
                material == Material.EMERALD_ORE || material == Material.DEEPSLATE_EMERALD_ORE ||
                material == Material.ANCIENT_DEBRIS || material == Material.NETHERITE_BLOCK;
    }

    private boolean isUnbreakableBlock(Material material) {
        return material == Material.BEDROCK || material == Material.END_PORTAL_FRAME ||
                material == Material.BARRIER || material == Material.COMMAND_BLOCK;
    }

    private boolean isInProtectedArea(Location location) {
        // 检测是否在保护区域内
        // 需要与领地插件集成
        return false;
    }
}

class StrictCombatDetector implements Listener {
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

    @EventHandler
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
}

// 战斗档案类
class CombatProfile {
    private final LinkedList<Long> attackTimes = new LinkedList<>();
    private final LinkedList<Double> attackAngles = new LinkedList<>();
    private final LinkedList<UUID> recentTargets = new LinkedList<>();
    private final LinkedList<Long> reactionTimes = new LinkedList<>();
    private long lastAttackTime = 0;
    private double lastAimAngle = 0;

    public void recordAttack() {
        long currentTime = System.currentTimeMillis();
        attackTimes.add(currentTime);

        // 清理旧记录
        while (!attackTimes.isEmpty() && currentTime - attackTimes.getFirst() > 5000) {
            attackTimes.removeFirst();
        }

        lastAttackTime = currentTime;
    }

    public void recordTarget(UUID targetId) {
        recentTargets.add(targetId);
        // 只保留最近10个目标
        while (recentTargets.size() > 10) {
            recentTargets.removeFirst();
        }
    }

    public void recordAimAngle(double angle) {
        attackAngles.add(angle);
        while (attackAngles.size() > 20) {
            attackAngles.removeFirst();
        }
        lastAimAngle = angle;
    }

    public void recordReactionTime(long time) {
        reactionTimes.add(time);
        while (reactionTimes.size() > 10) {
            reactionTimes.removeFirst();
        }
    }

    public double getCurrentCPS() {
        if (attackTimes.isEmpty()) return 0;

        long currentTime = System.currentTimeMillis();
        long oneSecondAgo = currentTime - 1000;

        return attackTimes.stream()
                .filter(time -> time > oneSecondAgo)
                .count();
    }

    public double getClickPatternScore() {
        if (attackTimes.size() < 10) return 0;

        // 计算点击间隔的模式得分
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < attackTimes.size(); i++) {
            intervals.add(attackTimes.get(i) - attackTimes.get(i - 1));
        }

        double mean = intervals.stream().mapToLong(Long::longValue).average().orElse(0);
        double variance = intervals.stream()
                .mapToDouble(i -> Math.pow(i - mean, 2))
                .average().orElse(0);

        // 方差越小，模式得分越高
        return 1.0 - Math.min(variance / (mean * mean), 1.0);
    }

    public double getAimConsistency() {
        if (attackAngles.size() < 5) return 0;

        // 计算瞄准角度的一致性
        double mean = attackAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = attackAngles.stream()
                .mapToDouble(a -> Math.pow(a - mean, 2))
                .average().orElse(0);

        return 1.0 - Math.min(variance / 100.0, 1.0); // 假设最大方差为100
    }

    public int getRecentTargetCount() {
        long currentTime = System.currentTimeMillis();
        long fiveSecondsAgo = currentTime - 5000;

        // 需要从攻击记录中获取目标数量
        // 这里简化实现，实际需要记录攻击的目标
        return recentTargets.size();
    }

    public double getAttackAngleVariance() {
        if (attackAngles.size() < 3) return 180.0; // 默认最大值

        double mean = attackAngles.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return attackAngles.stream()
                .mapToDouble(a -> Math.pow(a - mean, 2))
                .average().orElse(0);
    }

    public long getAverageReactionTime() {
        if (reactionTimes.isEmpty()) return 200; // 默认反应时间

        return (long) reactionTimes.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    public double getAttackTimingScore() {
        // 计算攻击时机的完美程度
        // 简化实现
        return getClickPatternScore(); // 重用点击模式得分
    }

    public long getBlockReactionTime() {
        // 获取格挡反应时间
        // 需要记录格挡时间
        return 100; // 示例值
    }
}