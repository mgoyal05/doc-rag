package docrag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.*;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;

import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Native AI Services guardrails enclose each stateless invocation, including its bounded tool loop.
 */
final class NativeGuardrails {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private NativeGuardrails() {
    }

    static String run(String system, String prompt, Function<ChatRequest, ChatResponse> invocation,
                      Consumer<JsonNode> validation) {
        Research.required(system, "system instructions");
        ChatModel boundary = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                // Financial {{F1}} slots are literal policy text, not AI Services template variables.
                var messages = new ArrayList<ChatMessage>();
                messages.add(SystemMessage.from(system));
                messages.addAll(request.messages());
                return invocation.apply(request.toBuilder().messages(messages).build());
            }
        };
        InputGuardrail input = new InputGuardrail() {
            @Override
            public InputGuardrailResult validate(UserMessage message) {
                String text = message.singleText();
                return text == null || text.isBlank() || text.length() > 64000 || text.indexOf('\0') >= 0
                        ? fatal("INVALID_INPUT") : success();
            }
        };
        OutputGuardrail output = new OutputGuardrail() {
            @Override
            public OutputGuardrailResult validate(AiMessage message) {
                try {
                    if (message.hasToolExecutionRequests() || message.text() == null || message.text().length() > 64000)
                        return fatal("INVALID_RESPONSE");
                    JsonNode root = JSON.readTree(message.text());
                    if (root == null || !root.isObject()) return fatal("INVALID_RESPONSE");
                    validation.accept(root);
                    return success();
                } catch (Exception invalid) {
                    return fatal("INVALID_RESPONSE");
                }
            }
        };
        try {
            return AiServices.builder(Assistant.class).chatModel(boundary)
                    .inputGuardrails(input).outputGuardrails(output)
                    .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(0).build())
                    .build().reply(prompt);
        } catch (InputGuardrailException failure) {
            throw new ResearchFailure(ResearchFailure.Code.INVALID_INPUT);
        } catch (OutputGuardrailException failure) {
            throw new ResearchFailure(ResearchFailure.Code.INVALID_RESPONSE);
        }
    }

    public interface Assistant {
        String reply(String input);
    }
}
