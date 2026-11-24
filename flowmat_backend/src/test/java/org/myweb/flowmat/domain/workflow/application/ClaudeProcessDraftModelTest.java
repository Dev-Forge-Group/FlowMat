package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The request to Claude builds offline: the draft's shape is a structured output the SDK accepts (ADR-047 decision 2). */
class ClaudeProcessDraftModelTest {

    @Test
    void theRequestCarriesTheDescriptionAndTheDraftShape() {
        var params = ClaudeProcessDraftModel.params("claude-opus-5-5", "We bottle melon milk.");
        assertThat(params.rawParams().model().asString()).isEqualTo("claude-opus-5-5");
        assertThat(params.rawParams().messages()).hasSize(1);
        assertThat(params.rawParams().outputConfig()).isPresent();
    }
}
