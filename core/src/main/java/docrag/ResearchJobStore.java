package docrag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static docrag.ResearchClient.*;

/**
 * Host-owned durable job snapshots. One application instance owns this directory.
 */
final class ResearchJobStore implements AutoCloseable {
    private final Path directory;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final ObjectMapper json = new ObjectMapper();
    private final List<Job> recovered = new ArrayList<>();

    public ResearchJobStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        lockChannel = FileChannel.open(this.directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("Research jobs directory is already in use");
        } catch (Exception e) {
            lockChannel.close();
            throw e;
        }
        try (var files = Files.newDirectoryStream(this.directory, "*.json")) {
            int count = 0;
            for (Path file : files) {
                if (++count > 10000)
                    throw new IOException("Research job archive exceeds 10000 files; archive completed job snapshots before startup");
                Job job = json.readValue(file.toFile(), Job.class);
                if (!job.terminal()) {
                    var interrupted = job.state(State.INTERRUPTED, null, "Application restarted; submit an explicit retry");
                    save(interrupted);
                    recovered.add(interrupted);
                }
            }
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public synchronized Job create(String owner, Request request) throws IOException {
        String now = Instant.now().toString();
        Job job = new Job(UUID.randomUUID().toString(), owner, request, State.QUEUED, now, now, null, null);
        save(job);
        return job;
    }

    public List<Job> recovered() {
        return List.copyOf(recovered);
    }

    public synchronized Job get(String id) throws IOException {
        UUID.fromString(id);
        Path file = directory.resolve(id + ".json");
        if (!Files.isRegularFile(file)) throw new NoSuchFileException("Research job not found");
        return json.readValue(file.toFile(), Job.class);
    }

    public synchronized Job update(String id, State state, Research.Answer answer, String message) throws IOException {
        Job existing = get(id);
        if (existing.terminal()) return existing;
        Job next = existing.state(state, answer, message);
        save(next);
        return next;
    }

    private void save(Job job) throws IOException {
        Path temp = directory.resolve(job.id() + ".tmp");
        Files.write(temp, json.writeValueAsBytes(job), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        Files.move(temp, directory.resolve(job.id() + ".json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public void close() throws IOException {
        try {
            if (lock.isValid()) lock.release();
        } finally {
            lockChannel.close();
        }
    }

}

