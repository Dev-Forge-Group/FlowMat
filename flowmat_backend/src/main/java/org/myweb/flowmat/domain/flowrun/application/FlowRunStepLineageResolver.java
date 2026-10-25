package org.myweb.flowmat.domain.flowrun.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Traverses one run's loaded steps, preserving their supplied sequence order. */
final class FlowRunStepLineageResolver {

    private final String stepId;
    private final List<FlowRunStep> steps;
    private final FlowRunStep selected;
    private final Map<String, List<String>> joined = new HashMap<>();
    private final Map<String, FlowRunStep> byId = new HashMap<>();
    private final Map<String, List<FlowRunStep>> children = new HashMap<>();

    FlowRunStepLineageResolver(String stepId, List<FlowRunStep> steps, List<FlowRunStepSource> sources) {
        this.stepId = stepId;
        this.steps = steps;
        // Join arrivals replace the scalar source; other steps keep their one source (ADR-007).
        for (FlowRunStepSource source : sources) {
            joined.computeIfAbsent(source.getStepId(), ignored -> new ArrayList<>()).add(source.getSourceStepId());
        }
        for (FlowRunStep step : steps) {
            byId.put(step.getStepId(), step);
        }
        for (FlowRunStep step : steps) {
            for (String parent : parents(step)) {
                children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(step);
            }
        }
        selected = byId.get(stepId);
        if (selected == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    FlowRunStep selected() {
        return selected;
    }

    List<FlowRunStep> ancestors() {
        Set<String> ancestorIds = new HashSet<>();
        ArrayDeque<String> up = new ArrayDeque<>(parents(selected));
        while (!up.isEmpty()) {
            String parentId = up.removeFirst();
            FlowRunStep parent = byId.get(parentId);
            if (parent == null || parentId.equals(stepId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Flow run step lineage is invalid.");
            }
            if (ancestorIds.add(parentId)) {
                up.addAll(parents(parent));
            }
        }
        return steps.stream().filter(step -> ancestorIds.contains(step.getStepId())).toList();
    }

    List<FlowRunStep> descendants() {
        Set<String> descendantIds = new HashSet<>();
        ArrayDeque<FlowRunStep> queue = new ArrayDeque<>(children.getOrDefault(stepId, List.of()));
        while (!queue.isEmpty()) {
            FlowRunStep child = queue.removeFirst();
            if (child.getStepId().equals(stepId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Flow run step lineage is invalid.");
            }
            // Branches that meet at a Join reach the same step more than once.
            if (descendantIds.add(child.getStepId())) {
                queue.addAll(children.getOrDefault(child.getStepId(), List.of()));
            }
        }
        return steps.stream().filter(step -> descendantIds.contains(step.getStepId())).toList();
    }

    private List<String> parents(FlowRunStep step) {
        List<String> arrivals = joined.get(step.getStepId());
        if (arrivals != null) {
            return arrivals;
        }
        return step.getSourceStepId() == null ? List.of() : List.of(step.getSourceStepId());
    }
}
