package ac.core;

import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

/** Owns module registration and the per-player cleanup lifecycle. */
public final class ModuleRegistry implements AutoCloseable {
    public record Definition(String id, boolean enabled, Listener listener, Consumer<UUID> cleanup) {
        public Definition {
            id = Objects.requireNonNull(id, "id");
            listener = Objects.requireNonNull(listener, "listener");
            cleanup = cleanup == null ? ignored -> { } : cleanup;
        }
    }

    private final JavaPlugin plugin;
    private final List<Definition> definitions = new ArrayList<>();
    private final List<Definition> active = new ArrayList<>();
    private boolean started;

    public ModuleRegistry(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void add(String id, boolean enabled, Listener listener, Consumer<UUID> cleanup) {
        if (started) {
            throw new IllegalStateException("Modules cannot be added after the registry starts");
        }
        definitions.add(new Definition(id, enabled, listener, cleanup));
    }

    public void start() {
        if (started) {
            return;
        }
        for (Definition definition : definitions) {
            if (!definition.enabled()) {
                continue;
            }
            plugin.getServer().getPluginManager().registerEvents(definition.listener(), plugin);
            active.add(definition);
        }
        started = true;
    }

    public void cleanup(UUID playerId) {
        for (Definition definition : active) {
            definition.cleanup().accept(playerId);
        }
    }

    public Set<String> activeIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Definition definition : active) {
            ids.add(definition.id());
        }
        return Collections.unmodifiableSet(ids);
    }

    public List<Definition> definitions() {
        return Collections.unmodifiableList(definitions);
    }

    public void stop() {
        for (Definition definition : active) {
            HandlerList.unregisterAll(definition.listener());
        }
        active.clear();
        started = false;
    }

    @Override
    public void close() {
        stop();
    }
}
