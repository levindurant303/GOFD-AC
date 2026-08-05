package ac.core;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class AsyncFileServiceTest {
    @Test
    public void writesAreSerializedOffCallerThread() throws Exception {
        Path directory = Files.createTempDirectory("gofdac-file-test");
        Path file = directory.resolve("nested").resolve("events.log");
        try (AsyncFileService service = new AsyncFileService("test-file-writer", throwable -> {
        })) {
            service.append(file, "one\n").get(2, TimeUnit.SECONDS);
            service.append(file, "two\n").get(2, TimeUnit.SECONDS);
        }

        assertEquals("one\ntwo\n", Files.readString(file));
        Files.deleteIfExists(file);
        Files.deleteIfExists(file.getParent());
        Files.deleteIfExists(directory);
    }
}
