package docrag.spring;

import docrag.PostgresResearchStore;
import docrag.ResearchChatbot;
import docrag.ResearchClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static docrag.ResearchClient.Job;

/**
 * Thin authenticated HTTP/job adapter; reusable conversation/research behavior lives in the library.
 */
@Controller
@ConditionalOnProperty(name = "research.enabled", havingValue = "true")
public class ResearchWebController {
    private final ResearchClient jobs;
    private final ResearchProperties properties;

    public ResearchWebController(ResearchClient jobs, ResearchProperties properties) {
        this.jobs = jobs;
        this.properties = properties;
    }

    @GetMapping("/research")
    public String oldDashboard() {
        return "redirect:" + properties.getLegacyDashboardRedirect();
    }

    @GetMapping("/api/research/chat/config")
    @ResponseBody
    public Map<String, Object> config(Principal owner, CsrfToken csrf) throws Exception {
        String ownerKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(owner.getName().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return Map.of("storageKey", "document-research-conversation-" + ownerKey, "csrfHeader", csrf.getHeaderName(), "csrfToken", csrf.getToken(),
                "maxFileBytes", properties.getMaxFileBytes(), "sharedDocuments", true);
    }

    @GetMapping("/api/research/conversations")
    @ResponseBody
    public List<ResearchClient.Conversation> conversations(Principal owner) throws Exception {
        return jobs.listConversations(owner.getName());
    }

    @PostMapping("/api/research/conversations")
    @ResponseBody
    public ResearchClient.Conversation create(Principal owner) throws Exception {
        return jobs.createConversation(owner.getName());
    }

    @GetMapping("/api/research/conversations/{id}")
    @ResponseBody
    public Map<String, Object> conversation(@PathVariable String id, Principal owner) throws Exception {
        var conversation = jobs.conversation(owner.getName(), id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("conversation", conversation);
        response.put("messages", jobs.history(owner.getName(), id));
        response.put("activeJob", jobs.activeJob(owner.getName(), id));
        return response;
    }

    @PostMapping("/api/research/conversations/{id}/messages")
    @ResponseBody
    public ResponseEntity<Job> message(@PathVariable String id, @RequestBody Message message, Principal owner) throws Exception {
        return ResponseEntity.accepted().body(jobs.submitMessage(owner.getName(), id, message.text(),
                message.context() == null ? List.of() : message.context().documentIds()));
    }

    @PostMapping(value = "/api/research/conversations/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseBody
    public ResponseEntity<Job> upload(@PathVariable String id, @RequestParam MultipartFile file, Principal owner) throws Exception {
        jobs.conversation(owner.getName(), id);
        jobs.requireCapacity(owner.getName());
        if (file.isEmpty() || file.getSize() > properties.getMaxFileBytes())
            throw new IllegalArgumentException("Invalid PDF size");
        Path path = jobs.archive().resolve(UUID.randomUUID() + ".pdf");
        try {
            try (var input = file.getInputStream(); var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
                byte[] header = input.readNBytes(5);
                if (!Arrays.equals(header, "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                    throw new IllegalArgumentException("Expected PDF content");
                output.write(header);
                long total = header.length;
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > properties.getMaxFileBytes()) throw new IllegalArgumentException("PDF exceeds limit");
                    output.write(buffer, 0, count);
                }
            }
            String name = file.getOriginalFilename();
            return ResponseEntity.accepted().body(jobs.submitUpload(owner.getName(), id, path, name == null ? "report.pdf" : name));
        } catch (Exception e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    @GetMapping("/api/research/documents")
    @ResponseBody
    public List<ResearchChatbot.Report> documents() throws Exception {
        return jobs.listDocuments();
    }

    @GetMapping("/api/research/documents/{id}/file")
    @ResponseBody
    public ResponseEntity<FileSystemResource> original(@PathVariable String id) throws Exception {
        Path file = jobs.originalDocument(id);
        Path archive = jobs.archive().toRealPath();
        if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(archive))
            throw new NoSuchFileException("Archived report not found");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("report.pdf").build().toString())
                .header("X-Content-Type-Options", "nosniff").body(new FileSystemResource(file));
    }

    @PostMapping("/api/research/conversations/{id}/turns/{turn}/retry")
    @ResponseBody
    public ResponseEntity<Job> retryTurn(@PathVariable String id, @PathVariable String turn, Principal owner) throws Exception {
        return ResponseEntity.accepted().body(jobs.retryTurn(owner.getName(), id, turn));
    }

    @GetMapping("/api/research/jobs/{id}")
    @ResponseBody
    public Job status(@PathVariable String id, Principal owner) throws IOException {
        return jobs.get(id, owner.getName());
    }

    @PostMapping("/api/research/jobs/{id}/cancel")
    @ResponseBody
    public Job cancel(@PathVariable String id, Principal owner) throws IOException {
        return jobs.cancel(id, owner.getName());
    }

    @PostMapping("/api/research/jobs/{id}/retry")
    @ResponseBody
    public ResponseEntity<Job> retry(@PathVariable String id, Principal owner) throws Exception {
        var previous = jobs.get(id, owner.getName());
        if (!previous.terminal()) throw new IllegalArgumentException("Job is active");
        if (previous.request().conversationId() != null)
            return ResponseEntity.accepted().body(jobs.retryTurn(owner.getName(), previous.request().conversationId(), previous.request().turnId()));
        if ("NEWS".equals(previous.request().kind()))
            return ResponseEntity.accepted().body(jobs.retryExternal(previous.request().documentId(), owner.getName()));
        throw new IllegalArgumentException("Start a new chat for an archived legacy request");
    }

    @PostMapping("/api/research/news/{id}/retry")
    @ResponseBody
    public ResponseEntity<Job> retryNews(@PathVariable Long id, Principal owner) throws Exception {
        return ResponseEntity.accepted().body(jobs.retryExternal(id.toString(), owner.getName()));
    }

    @GetMapping(value = "/api/research/jobs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, Principal owner) throws Exception {
        jobs.get(id, owner.getName());
        var emitter = new SseEmitter(properties.getJobTimeout().toMillis());
        var subscription = new AtomicReference<AutoCloseable>();
        Runnable cleanup = () -> {
            var value = subscription.getAndSet(null);
            if (value != null) try {
                value.close();
            } catch (Exception ignored) {
            }
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());
        subscription.set(jobs.subscribe(id, owner.getName(), job -> {
            try {
                emitter.send(SseEmitter.event().name("job").data(job));
                if (job.terminal()) emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }));
        if (jobs.get(id, owner.getName()).terminal()) cleanup.run();
        return emitter;
    }

    @ExceptionHandler(NoSuchFileException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> missing() {
        return ResponseEntity.status(404).body(Map.of("error", "Research resource not found"));
    }

    @ExceptionHandler(PostgresResearchStore.BusyConversationException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> active() {
        return ResponseEntity.status(409).body(Map.of("error", "Wait for or cancel the active conversation turn"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid research request"));
    }

    @ExceptionHandler(RejectedExecutionException.class)
    @ResponseBody
    public ResponseEntity<Map<String, String>> busy() {
        return ResponseEntity.status(429).header("Retry-After", "30").body(Map.of("error", "Research capacity exceeded", "code", "RESOURCE_LIMIT"));
    }

    public record Context(List<String> documentIds) {
    }

    public record Message(String text, Context context) {
    }
}

