package docrag.spring;

import docrag.PostgresResearchStore;
import docrag.ResearchClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResearchWebControllerTest {
    @TempDir
    Path archive;

    @Test
    void preservesJobSubmissionOwnershipErrorsAndUploadWireContract() throws Exception {
        var client = mock(ResearchClient.class);
        var properties = new ResearchProperties();
        var controller = new ResearchWebController(client, properties);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        Principal alice = () -> "alice";
        String conversation = UUID.randomUUID().toString(), jobId = UUID.randomUUID().toString();
        var job = new ResearchClient.Job(jobId, "alice", new ResearchClient.Request("CHAT", null, List.of(), null, null, null, null, null, null, conversation, "turn"), ResearchClient.State.QUEUED, "now", "now", null, null);
        when(client.submitMessage("alice", conversation, "Revenue?", List.of())).thenReturn(job);
        mvc.perform(post("/api/research/conversations/" + conversation + "/messages").principal(alice).contentType("application/json").content("{\"text\":\"Revenue?\"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(jobId)).andExpect(jsonPath("$.state").value("QUEUED"));
        when(client.history("alice", conversation)).thenReturn(List.of());
        when(client.conversation("alice", conversation)).thenReturn(new ResearchClient.Conversation(conversation, "Title", null, null, null, "now"));
        mvc.perform(get("/api/research/conversations/" + conversation).principal(alice)).andExpect(status().isOk()).andExpect(jsonPath("$.conversation.id").value(conversation)).andExpect(jsonPath("$.messages").isArray());
        when(client.conversation("bob", conversation)).thenThrow(new java.nio.file.NoSuchFileException("private"));
        mvc.perform(get("/api/research/conversations/" + conversation).principal(() -> "bob")).andExpect(status().isNotFound());
        when(client.hasCapacity()).thenReturn(true);
        when(client.archive()).thenReturn(archive);
        when(client.submitUpload(eq("alice"), eq(conversation), any(Path.class), eq("report.pdf"))).thenReturn(job);
        mvc.perform(multipart("/api/research/conversations/" + conversation + "/documents").file(new MockMultipartFile("file", "report.pdf", "application/pdf", "%PDF-fixture".getBytes())).principal(alice))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(jobId));
        when(client.submitMessage("alice", conversation, "Busy", List.of())).thenThrow(new PostgresResearchStore.BusyConversationException());
        mvc.perform(post("/api/research/conversations/" + conversation + "/messages").principal(alice).contentType("application/json").content("{\"text\":\"Busy\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void configContainsBrowserSettingsWithoutInfrastructureCredentials() throws Exception {
        var p = new ResearchProperties();
        p.setOllamaUrl("http://private-model.invalid");
        var response = new ResearchWebController(mock(ResearchClient.class), p).config(() -> "alice", new DefaultCsrfToken("X-CSRF", "_csrf", "fixture-token"));
        org.junit.jupiter.api.Assertions.assertEquals("fixture-token", response.get("csrfToken"));
        org.junit.jupiter.api.Assertions.assertEquals(true, response.get("sharedDocuments"));
        org.junit.jupiter.api.Assertions.assertFalse(response.toString().contains("private-model"));
    }
}
