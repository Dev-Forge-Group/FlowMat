package org.myweb.flowmat.domain.production.api.dto.response;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A plan for a target, read only (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decisions 5-7): nothing is
 * reserved or written by asking.
 *
 * @param feasible false when the graph cannot bring the target as planned ({@code limits} say why); a stock shortage does not
 *     make a plan infeasible
 * @param buildable what usable stock could make of the target now, in the target's unit: whole units for a counted item;
 *     null when no start has an item
 * @param requirements what the starts need, by item in the item's unit; starts without an item come one by one
 * @param limits stock shortages first, then the longest equipment step, then what the graph itself limits
 * @param basis what the plan read, which a confirmation checks again
 */
public record PlanPreviewResponse(
    String revisionId,
    BigDecimal target,
    String targetUnit,
    boolean feasible,
    BigDecimal buildable,
    List<Requirement> requirements,
    List<Node> nodes,
    List<Surplus> surplus,
    List<Limit> limits,
    List<Assumption> assumptions,
    Basis basis
) {

    /**
     * @param at the port (or node) where the item comes in; the first one when several ports take the same item
     * @param shortage what the needed amount is above usable stock, 0 when there is enough; null without an item
     * @param trace how the target became this amount, step by step ({@code factor} multiplies the target into it)
     */
    public record Requirement(String at, String processId, String itemId, String itemName, BigDecimal needed,
        BigDecimal available, BigDecimal shortage, String unit, List<TraceStep> trace) {
    }

    /** {@code at} is a port, node or connection; {@code processId} the node it belongs to (a connection's source). */
    public record TraceStep(String at, String processId, String rule, BigDecimal from, BigDecimal to, BigDecimal factor) {
    }

    /** {@code minutes}: the node's time requirements in minutes, null when it has none. */
    public record Node(String processId, BigDecimal batches, BigDecimal through, String unit, BigDecimal minutes,
        List<Port> ports, List<Execution> execution) {
    }

    public record Port(String processIoId, String direction, BigDecimal quantity, String unit) {
    }

    public record Execution(String kind, String ref, BigDecimal amount, String unit) {
    }

    public record Surplus(String at, String kind, BigDecimal quantity, String unit) {
    }

    /** {@code kind}: {@code stock}, {@code equipment-time}, or one of the graph's (split-fixed, exclusive, capacity, ...). */
    public record Limit(String kind, String at, String processId, String message) {
    }

    public record Assumption(String kind, String at, String value) {
    }

    /** The revision and each item's usable stock the plan read. */
    public record Basis(String revisionId, Map<String, BigDecimal> available) {
    }
}
