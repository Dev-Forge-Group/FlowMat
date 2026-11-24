package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse.Flow;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse.Item;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse.Step;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse.Target;

/** A model's draft is checked before anyone sees it (docs/architecture/adr/ADR-047-process-draft-from-a-description.md). */
public class ProcessDraftCheckTest {

    public static ProcessDraftResponse milk() {
        return new ProcessDraftResponse("Melon milk", "Mix milk and syrup, then fill bottles.",
            List.of(new Item("MILK", "Raw milk", "material", "kg"), new Item("SYRUP", "Melon syrup", "material", "kg"),
                new Item("BOTTLE", "Bottle", "material", "ea"), new Item("MIX", "Mix", "semi", "kg"),
                new Item("MELON-MILK", "Melon milk", "product", "ea")),
            List.of(new Step("Mix", List.of(new Flow("MILK", 100), new Flow("SYRUP", 10)), List.of(new Flow("MIX", 110)), 30),
                new Step("Fill", List.of(new Flow("MIX", 0.2), new Flow("BOTTLE", 1)), List.of(new Flow("MELON-MILK", 1)), 0)),
            new Target("MELON-MILK", 1000));
    }

    @Test
    void aDraftWhoseStepsUseOnlyWhatCameBeforePasses() {
        assertThat(ProcessDraftCheck.problems(milk())).isEmpty();
    }

    @Test
    void aStepBeforeItsIngredientAnUnknownUnitAndAProductTargetMissingAreRefused() {
        ProcessDraftResponse draft = milk();
        ProcessDraftResponse reordered = new ProcessDraftResponse(draft.title(), draft.summary(),
            List.of(draft.items().get(0), draft.items().get(1), new Item("BOTTLE", "Bottle", "material", "box"),
                draft.items().get(3), draft.items().get(4)),
            List.of(draft.steps().get(1), draft.steps().get(0)), new Target("MIX", 1000));
        assertThat(ProcessDraftCheck.problems(reordered)).contains(
            "Item BOTTLE has unit box.",
            "Step Fill uses MIX before a step makes it.",
            "The target is not one of the products.");
    }
}
