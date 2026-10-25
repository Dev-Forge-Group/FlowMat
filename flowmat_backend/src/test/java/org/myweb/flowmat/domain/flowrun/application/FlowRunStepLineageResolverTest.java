package org.myweb.flowmat.domain.flowrun.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStep;
import org.myweb.flowmat.domain.flowrun.domain.entity.FlowRunStepSource;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

class FlowRunStepLineageResolverTest {

    @Test
    void isolatedStepHasNoRelatives() {
        FlowRunStep selected = step("selected", null);
        var lineage = new FlowRunStepLineageResolver("selected", List.of(selected), List.of());

        assertSame(selected, lineage.selected());
        assertTrue(lineage.ancestors().isEmpty());
        assertTrue(lineage.descendants().isEmpty());
    }

    @Test
    void relativesKeepTheSuppliedStepOrderRatherThanTraversalOrder() {
        FlowRunStep root = step("root", null);
        FlowRunStep parent = step("parent", "root");
        FlowRunStep selected = step("selected", "parent");
        FlowRunStep child = step("child", "selected");
        FlowRunStep tail = step("tail", "child");
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(root, parent, selected, child, tail), List.of());

        assertEquals(List.of("root", "parent"), ids(lineage.ancestors()));
        assertEquals(List.of("child", "tail"), ids(lineage.descendants()));
    }

    @Test
    void joinedBranchesShareAncestorsAndDescendantsWithoutDuplicates() {
        List<FlowRunStep> steps = List.of(step("root", null), step("left", "root"),
            step("right", "root"), step("selected", null), step("first", "selected"),
            step("second", "selected"), step("joined", null), step("tail", "joined"));
        List<FlowRunStepSource> sources = List.of(source("selected", "right"), source("selected", "left"),
            source("joined", "second"), source("joined", "first"));
        var lineage = new FlowRunStepLineageResolver("selected", steps, sources);

        assertEquals(List.of("root", "left", "right"), ids(lineage.ancestors()));
        assertEquals(List.of("first", "second", "joined", "tail"), ids(lineage.descendants()));
    }

    @Test
    void joinArrivalsReplaceTheLegacySourceEvenWhenThatSourceIsMissing() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("root", null), step("selected", "missing-legacy-source")),
            List.of(source("selected", "root")));

        assertEquals(List.of("root"), ids(lineage.ancestors()));
        assertTrue(lineage.descendants().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownSelectionIsNotFoundWithOrWithoutOtherSteps(boolean hasOtherStep) {
        List<FlowRunStep> steps = hasOtherStep ? List.of(step("other", null)) : List.of();

        BusinessException error = assertThrows(BusinessException.class,
            () -> new FlowRunStepLineageResolver("missing", steps, List.of()));

        assertEquals(ErrorCode.NOT_FOUND, error.getErrorCode());
    }

    @Test
    void aMissingReachableAncestorKeepsTheExistingConflict() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("selected", "missing")), List.of());

        assertInvalid(assertThrows(BusinessException.class, lineage::ancestors));
    }

    @ParameterizedTest
    @ValueSource(strings = {"self", "pair", "join"})
    void pathsReturningToTheSelectedStepRemainInvalid(String shape) {
        FlowRunStep selected = step("selected", "self".equals(shape) ? "selected" : "parent");
        List<FlowRunStep> steps = "self".equals(shape) ? List.of(selected)
            : List.of(selected, step("parent", "selected"));
        List<FlowRunStepSource> sources = "join".equals(shape)
            ? List.of(source("selected", "parent"), source("selected", "selected")) : List.of();
        var lineage = new FlowRunStepLineageResolver("selected", steps, sources);

        assertInvalid(assertThrows(BusinessException.class, lineage::ancestors));
        assertInvalid(assertThrows(BusinessException.class, lineage::descendants));
    }

    @Test
    void disconnectedMalformedComponentsDoNotInvalidateTheSelectedLineage() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("root", null), step("selected", "root"), step("broken", "missing"),
                step("cycle-a", "cycle-b"), step("cycle-b", "cycle-a")), List.of());

        assertEquals(List.of("root"), ids(lineage.ancestors()));
        assertTrue(lineage.descendants().isEmpty());
    }

    @Test
    void anAncestorCycleNotReturningToTheSelectionTerminatesWithoutNewValidation() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("a", "b"), step("b", "a"), step("selected", "a")), List.of());

        assertEquals(List.of("a", "b"), ids(lineage.ancestors()));
        assertTrue(lineage.descendants().isEmpty());
    }

    @Test
    void aDescendantCycleNotReturningToTheSelectionTerminatesWithoutDuplicates() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("selected", null), step("a", "selected"), step("b", null), step("c", "b")),
            List.of(source("b", "a"), source("b", "c")));

        assertTrue(lineage.ancestors().isEmpty());
        assertEquals(List.of("a", "b", "c"), ids(lineage.descendants()));
    }

    @Test
    void aMissingAlternateParentOfADescendantJoinIsNotAnAncestorError() {
        var lineage = new FlowRunStepLineageResolver("selected",
            List.of(step("selected", null), step("join", null)),
            List.of(source("join", "selected"), source("join", "missing")));

        assertTrue(lineage.ancestors().isEmpty());
        assertEquals(List.of("join"), ids(lineage.descendants()));
    }

    @Test
    void repeatedNodeIdsStillRepresentDifferentExecutionSteps() {
        FlowRunStep first = step("first", null);
        FlowRunStep selected = step("selected", "first");
        FlowRunStep last = step("last", "selected");
        first.setNodeId("same-node");
        selected.setNodeId("same-node");
        last.setNodeId("same-node");
        var lineage = new FlowRunStepLineageResolver("selected", List.of(first, selected, last), List.of());

        assertEquals(List.of("first"), ids(lineage.ancestors()));
        assertEquals(List.of("last"), ids(lineage.descendants()));
    }

    private static FlowRunStep step(String id, String parentId) {
        FlowRunStep step = new FlowRunStep();
        step.setStepId(id);
        step.setSourceStepId(parentId);
        return step;
    }

    private static FlowRunStepSource source(String stepId, String parentId) {
        FlowRunStepSource source = new FlowRunStepSource();
        source.setStepId(stepId);
        source.setSourceStepId(parentId);
        return source;
    }

    private static List<String> ids(List<FlowRunStep> steps) {
        return steps.stream().map(FlowRunStep::getStepId).toList();
    }

    private static void assertInvalid(BusinessException error) {
        assertEquals(ErrorCode.CONFLICT, error.getErrorCode());
        assertEquals("Flow run step lineage is invalid.", error.getMessage());
    }
}
