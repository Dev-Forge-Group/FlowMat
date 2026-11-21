package org.myweb.flowmat.domain.workflow.domain.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class PortSchemaTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void keepsNamesInOrderSoTheFirstMismatchIsAlwaysTheSame() throws Exception {
        PortSchema input = PortSchema.parse(mapper.readTree("""
            {"type":"object","properties":{"zone":{"type":"string"},"lot":{"type":"string"},"batch":{"type":"number"}},
             "required":["zone","lot","batch"]}
            """));
        PortSchema output = PortSchema.parse(mapper.readTree("""
            {"type":"object","properties":{"zone":{"type":"number"},"lot":{"type":"number"},"batch":{"type":"string"}}}
            """));

        assertThat(List.copyOf(input.properties().keySet())).containsExactly("batch", "lot", "zone");
        assertThat(List.copyOf(input.required())).containsExactly("batch", "lot", "zone");
        assertThatThrownBy(() -> PortSchema.requireCompatible(output, input)).hasMessage("Target port needs batch (number).");
    }
}
