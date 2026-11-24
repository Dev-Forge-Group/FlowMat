package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/**
 * A first process for a product someone described (docs/architecture/adr/ADR-047-process-draft-from-a-description.md): its
 * items and steps in order, each step's inputs and outputs per batch, and a target to plan for. Nothing is saved; the
 * editor builds it through the public API once the person accepts it. The same shape is the model's structured output.
 */
public record ProcessDraftResponse(
    @JsonPropertyDescription("Short name for the process, in the description's language, at most 60 characters") String title,
    @JsonPropertyDescription("One sentence on what the process makes and its main steps, at most 200 characters") String summary,
    @JsonPropertyDescription("Every material, intermediate (semi) and product the steps use, 2 to 30") List<Item> items,
    @JsonPropertyDescription("The steps in the order they run, 1 to 12; a step uses only materials and what earlier steps make")
    List<Step> steps,
    @JsonPropertyDescription("The product and how many of it to plan for first") Target target
) {

    public record Item(
        @JsonPropertyDescription("Upper-case code, letters, digits and dashes, at most 20, unique, e.g. MILK or MIX-1") String code,
        @JsonPropertyDescription("Name in the description's language, at most 60 characters") String name,
        @JsonPropertyDescription("material (bought), semi (made by a step and used by a later one) or product (the end result)")
        String type,
        @JsonPropertyDescription("One of kg, g, t, l, ml, ea, m, cm, mm") String unit
    ) {
    }

    public record Step(
        @JsonPropertyDescription("Name of the step, a verb or noun, at most 50 characters, unique") String name,
        @JsonPropertyDescription("What one batch of the step takes, by item code") List<Flow> inputs,
        @JsonPropertyDescription("What one batch of the step makes, by item code; at least one") List<Flow> outputs,
        @JsonPropertyDescription("Minutes one batch takes, 0 when unknown") int minutes
    ) {
    }

    public record Flow(
        @JsonPropertyDescription("An item code from items") String item,
        @JsonPropertyDescription("Amount per batch in the item's unit, more than 0") double quantity
    ) {
    }

    public record Target(
        @JsonPropertyDescription("The product's item code") String item,
        @JsonPropertyDescription("How many to plan for, more than 0") double quantity
    ) {
    }
}
