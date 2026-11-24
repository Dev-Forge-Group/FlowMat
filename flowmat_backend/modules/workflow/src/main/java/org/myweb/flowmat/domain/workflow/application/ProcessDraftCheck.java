package org.myweb.flowmat.domain.workflow.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessDraftResponse;

/**
 * What a model's draft must be before anyone sees it (docs/architecture/adr/ADR-047-process-draft-from-a-description.md
 * decision 3): sizes and names within limits, known units and types, every port on a known item, a product as the target,
 * and steps in an order where each uses only materials and what an earlier step made, each intermediate or product made by
 * exactly one step. The problems in the order found; none means the draft can be offered.
 */
public final class ProcessDraftCheck {

    static final Set<String> UNITS = Set.of("kg", "g", "t", "l", "ml", "ea", "m", "cm", "mm");
    static final Set<String> TYPES = Set.of("material", "semi", "product");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9-]{0,19}");
    private static final double MAX_QUANTITY = 1_000_000;

    private ProcessDraftCheck() {
    }

    public static List<String> problems(ProcessDraftResponse draft) {
        List<String> problems = new ArrayList<>();
        if (draft == null) return List.of("The draft is empty.");
        text(problems, "title", draft.title(), 60);
        if (draft.summary() != null && draft.summary().length() > 200) problems.add("The summary is over 200 characters.");
        List<ProcessDraftResponse.Item> items = draft.items() == null ? List.of() : draft.items();
        List<ProcessDraftResponse.Step> steps = draft.steps() == null ? List.of() : draft.steps();
        if (items.size() < 2 || items.size() > 30) problems.add("A draft has 2 to 30 items, not " + items.size() + ".");
        if (steps.isEmpty() || steps.size() > 12) problems.add("A draft has 1 to 12 steps, not " + steps.size() + ".");

        Map<String, String> types = new HashMap<>();
        for (ProcessDraftResponse.Item item : items) {
            if (item == null || item.code() == null || !CODE.matcher(item.code()).matches()) {
                problems.add("Item code " + (item == null ? null : item.code()) + " is not upper-case letters, digits and dashes.");
                continue;
            }
            if (types.put(item.code(), item.type()) != null) problems.add("Item " + item.code() + " is listed twice.");
            text(problems, "name of " + item.code(), item.name(), 60);
            if (!TYPES.contains(item.type())) problems.add("Item " + item.code() + " has type " + item.type() + ".");
            if (!UNITS.contains(item.unit())) problems.add("Item " + item.code() + " has unit " + item.unit() + ".");
        }

        Set<String> names = new HashSet<>();
        Map<String, Integer> madeBy = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            ProcessDraftResponse.Step step = steps.get(index);
            if (step == null) {
                problems.add("Step " + (index + 1) + " is empty.");
                continue;
            }
            text(problems, "step " + (index + 1) + " name", step.name(), 50);
            if (step.name() != null && !names.add(step.name())) problems.add("Step " + step.name() + " is listed twice.");
            if (step.minutes() < 0 || step.minutes() > 10_080) problems.add("Step " + step.name() + " takes " + step.minutes() + " minutes.");
            List<ProcessDraftResponse.Flow> inputs = step.inputs() == null ? List.of() : step.inputs();
            List<ProcessDraftResponse.Flow> outputs = step.outputs() == null ? List.of() : step.outputs();
            if (outputs.isEmpty()) problems.add("Step " + step.name() + " makes nothing.");
            for (ProcessDraftResponse.Flow input : inputs) {
                if (!flow(problems, step, input, types)) continue;
                String type = types.get(input.item());
                if ("product".equals(type)) problems.add("Step " + step.name() + " uses the product " + input.item() + ".");
                if ("semi".equals(type) && (!madeBy.containsKey(input.item()) || madeBy.get(input.item()) >= index)) {
                    problems.add("Step " + step.name() + " uses " + input.item() + " before a step makes it.");
                }
            }
            for (ProcessDraftResponse.Flow output : outputs) {
                if (!flow(problems, step, output, types)) continue;
                if ("material".equals(types.get(output.item()))) {
                    problems.add("Step " + step.name() + " makes the material " + output.item() + ".");
                } else if (madeBy.putIfAbsent(output.item(), index) != null) {
                    problems.add(output.item() + " is made by more than one step.");
                }
            }
        }
        types.forEach((code, type) -> {
            if (("semi".equals(type) || "product".equals(type)) && !madeBy.containsKey(code)) {
                problems.add("No step makes " + code + ".");
            }
        });

        ProcessDraftResponse.Target target = draft.target();
        if (target == null || !"product".equals(types.get(target.item()))) {
            problems.add("The target is not one of the products.");
        } else if (!(target.quantity() > 0) || target.quantity() > MAX_QUANTITY) {
            problems.add("The target quantity " + target.quantity() + " is not more than 0 and at most 1,000,000.");
        }
        return problems;
    }

    private static boolean flow(List<String> problems, ProcessDraftResponse.Step step, ProcessDraftResponse.Flow flow,
        Map<String, String> types) {
        if (flow == null || !types.containsKey(flow.item())) {
            problems.add("Step " + step.name() + " names an item that is not listed: " + (flow == null ? null : flow.item()) + ".");
            return false;
        }
        if (!(flow.quantity() > 0) || flow.quantity() > MAX_QUANTITY) {
            problems.add("Step " + step.name() + " has " + flow.quantity() + " of " + flow.item() + ".");
            return false;
        }
        return true;
    }

    private static void text(List<String> problems, String what, String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            problems.add("The " + what + " is empty or over " + max + " characters.");
        }
    }
}
