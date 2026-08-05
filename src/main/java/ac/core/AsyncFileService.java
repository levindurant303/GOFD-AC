package ac.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Serializes plugin file writes off the server thread. */
public final class AsyncFileService implements AutoCloseable {
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Consumer<Throwable> errorHandler;

    public AsyncFileService(String threadName, Consumer<Throwable> errorHandler) {
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadExecutor(factory);
    }

    public CompletableFuture<Void> append(Path path, String content) {
        return submit(() -> {
            createParent(path);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        });
    }

    public CompletableFuture<Void> write(Path path, String content) {
        return submit(() -> {
            createParent(path);
            Files.writeString(path, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        });
    }

    private CompletableFuture<Void> submit(IoAction action) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("File service is closed"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                errorHandler.accept(throwable);
                throw new RuntimeException(throwable);
            }
        }, executor);
    }

    private static void createParent(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws Exception;
    }
}
