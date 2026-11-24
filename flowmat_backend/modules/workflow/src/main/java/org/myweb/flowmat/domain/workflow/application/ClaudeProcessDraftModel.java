package org.myweb.flowmat.domain.workflow.application;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.StructuredMessage;
import com.anthropic.models.messages.StructuredMessageCreateParams;
import java.time.Duration;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * Drafts with Claude through the Messages API and a structured output in the draft's shape
 * (docs/architecture/adr/ADR-047-process-draft-from-a-description.md decision 2). Made only when drafting is turned on and
 * a key is set; the key comes from the environment and is never logged.
 */
@Component
@Conditional(ProcessDraftConfigured.class)
public class ClaudeProcessDraftModel implements ProcessDraftModel {

    static final String INSTRUCTIONS = """
        You draft a first manufacturing process for FlowMat, a tool where a small factory draws its process and plans \
        from a target. The person describes what they make. Answer with the process only, in the person's language.

        - Items: every material bought in, every intermediate a step makes and a later step uses (type semi), and the \
        end product (type product). Units are kg, g, t, l, ml, ea, m, cm or mm; count pieces in ea.
        - Steps run in the order listed. A step takes materials and what earlier steps made, and makes at least one \
        intermediate or the product. Each intermediate and the product is made by exactly one step. Give amounts per \
        batch, as a recipe or a work standard would, and the minutes a batch takes when you can judge them, else 0.
        - Keep it short: the 3 to 6 steps a person would name first, not every motion. Use plain names a worker would use.
        - The target is the product and a sensible first quantity to plan for.
        If the description is not about making something physical, still give the closest small process you can.
        """;

    private final AnthropicClient client;
    private final String model;

    public ClaudeProcessDraftModel(@Value("${app.ai.process-draft.api-key}") String apiKey,
        @Value("${app.ai.process-draft.model:claude-opus-5-5}") String model,
        @Value("${app.ai.process-draft.timeout:PT2M}") Duration timeout) {
        this.client = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).build();
        this.model = model;
    }

    /** The request: the instructions, the description as the one user message, and the draft's shape as the output. */
    static StructuredMessageCreateParams<ProcessDraftResponse> params(String model, String description) {
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(INSTRUCTIONS)
            .outputConfig(ProcessDraftResponse.class)
            .addUserMessage(description)
            .build();
    }

    @Override
    public ProcessDraftResponse draft(String description) {
        StructuredMessage<ProcessDraftResponse> message;
        try {
            message = client.messages().create(params(model, description));
        } catch (AnthropicException e) {
            throw new Unavailable("The drafting service did not answer; try again or start from a sample.", e);
        }
        if (message.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
            throw new Unavailable("The drafting service declined this description; start from a sample instead.", null);
        }
        return message.content().stream().flatMap(block -> block.text().stream()).findFirst()
            .map(text -> text.text())
            .orElseThrow(() -> new Unavailable("The drafting service gave no draft; try again or start from a sample.", null));
    }
}
