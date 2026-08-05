# GOFDAC beta0.5

GOFDAC is a Paper 1.21.4 anti-cheat plugin targeting Java 17.

## Architecture

- `ac.gofd`: plugin lifecycle and command routing
- `ac.core.ModuleRegistry`: listener registration and per-player cleanup
- `ac.core.PluginSettings`: immutable configuration snapshots
- `ac.core.AsyncFileService`: ordered off-thread persistence
- `ac.core.DetectionMetrics`: thread-safe counters
- `ac.*Detector`: independent movement, combat, item, chat, environment,
  inventory, and network modules
- `PlayerData` / `DataManager`: bounded player state and persistence

Strict checks are isolated from normal checks and are disabled by default. Enable
them explicitly with `modules.strict.enabled: true` after tuning the thresholds
for the server.

## Build

```shell
mvn clean package
```

The deployable plugin is written to `target/GOFDAC-0.5.jar`.

## Performance Model

- Movement-only work is skipped for rotation-only packets.
- Runtime configuration is cached and replaced atomically on reload.
- Alert, ban, debug, packet, and player-data writes use one ordered I/O queue.
- All per-player module caches are cleared on quit and reset.
- Performance sampling uses bounded arrays and nanosecond timing.
