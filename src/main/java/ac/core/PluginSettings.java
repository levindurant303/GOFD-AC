package ac.core;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable runtime configuration. A new snapshot is published on reload. */
public record PluginSettings(
        boolean enabled,
        boolean movementEnabled,
        boolean combatEnabled,
        boolean itemEnabled,
        boolean interactionEnabled,
        boolean environmentEnabled,
        boolean packetEnabled,
        boolean inventoryEnabled,
        int alertThreshold,
        long alertCooldownMillis,
        int autoSaveMinutes,
        boolean randomChecksEnabled,
        int randomCheckIntervalSeconds,
        boolean debugEnabled,
        boolean performanceMonitoring,
        boolean strictEnabled,
        boolean punishmentEnabled,
        boolean autoKick,
        int kickThreshold,
        boolean autoBan,
        int banThreshold,
        double maxWalkSpeed,
        double maxFlySpeed,
        double maxVerticalSpeed,
        double maxJumpHeight,
        int maxCps,
        double maxReach
) {
    public static PluginSettings from(FileConfiguration config) {
        return new PluginSettings(
                config.getBoolean("general.enabled", true),
                config.getBoolean("movement.enabled", true),
                config.getBoolean("combat.enabled", true),
                config.getBoolean("items.enabled", true),
                config.getBoolean("interaction.enabled", true),
                config.getBoolean("environment.enabled", true),
                config.getBoolean("packet.enabled", true),
                config.getBoolean("inventory.enabled", true),
                positive(config.getInt("general.alert-threshold", 10), 10),
                Math.max(0L, config.getLong("alerts.cooldown", 5000L)),
                positive(config.getInt("general.auto-save-interval", 5), 5),
                config.getBoolean("random-checks.enabled", true),
                positive(config.getInt("random-checks.interval", 30), 30),
                config.getBoolean("debug.enabled", false),
                config.getBoolean("performance.monitoring", true),
                config.getBoolean("modules.strict.enabled", false),
                config.getBoolean("punishment.enabled", true),
                config.getBoolean("punishment.auto-kick", true),
                positive(config.getInt("punishment.kick-threshold", 25), 25),
                config.getBoolean("punishment.auto-ban.enabled", false),
                positive(config.getInt("punishment.auto-ban.ban-threshold", 100), 100),
                positive(config.getDouble("movement.max-walk-speed", 0.65), 0.65),
                positive(config.getDouble("movement.max-fly-speed", 1.5), 1.5),
                positive(config.getDouble("movement.max-vertical-speed", 0.6), 0.6),
                positive(config.getDouble("movement.max-jump-height", 1.5), 1.5),
                positive(config.getInt("combat.max-cps", 15), 15),
                positive(config.getDouble("combat.max-reach", 4.5), 4.5)
        );
    }

    public long autoSaveTicks() {
        long boundedMinutes = Math.min(autoSaveMinutes, Long.MAX_VALUE / 1200L);
        return Math.max(20L, boundedMinutes * 1200L);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static double positive(double value, double fallback) {
        return Double.isFinite(value) && value > 0.0D ? value : fallback;
    }
}
