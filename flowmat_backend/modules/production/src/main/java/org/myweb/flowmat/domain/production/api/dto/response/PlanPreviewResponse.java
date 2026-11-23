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
 * @param limits stock shortages first, then the longest equipment step, then resource limits the plan's average draw is
 *     above (ADR-046 decision 5), then what the graph itself limits
 * @param basis what the plan read, which a confirmation checks again
 * @param resources what the energy and water rows add up to by kind and unit (ADR-046 decision 4)
 * @param costs the known cost of the rows by kind, with how many rows have no unit cost (ADR-046 decision 4)
 * @param totalCost what {@code costs} add up to: the plan's resource cost, not equipment or labor
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
    Basis basis,
    /** When the last step ends; null when a step's equipment has too little working time in a year. */
    java.time.OffsetDateTime finishAt,
    List<Resource> resources,
    List<Cost> costs,
    BigDecimal totalCost
) {

    /**
     * @param at the port (or node) where the item comes in; the first one when several ports take the same item
     * @param shortage what the needed amount is above usable stock, 0 when there is enough; null without an item, and for
     *     energy and water, which are not stocked (ADR-046 decision 2)
     * @param trace how the target became this amount, step by step ({@code factor} multiplies the target into it)
     * @param resourceType the start port's resource type, lower case; {@code material} when it has none (ADR-046 decision 1)
     * @param unitCost the item's cost of one of its units; null without an item or a known cost (ADR-046 decision 3)
     * @param cost {@code needed × unitCost}, 4 decimals HALF_UP; null when either is unknown
     */
    public record Requirement(String at, String processId, String itemId, String itemName, BigDecimal needed,
        BigDecimal available, BigDecimal shortage, String unit, List<TraceStep> trace, String resourceType,
        BigDecimal unitCost, BigDecimal cost) {
    }

    /** The energy or water rows of one kind and unit added up; {@code cost} counts the rows whose cost is known. */
    public record Resource(String resourceType, String unit, BigDecimal needed, BigDecimal cost) {
    }

    /** The known cost of a kind's rows; {@code unknown} rows have no unit cost and are left out. */
    public record Cost(String resourceType, BigDecimal cost, int unknown) {
    }

    /** {@code at} is a port, node or connection; {@code processId} the node it belongs to (a connection's source). */
    public record TraceStep(String at, String processId, String rule, BigDecimal from, BigDecimal to, BigDecimal factor) {
    }

    /**
     * {@code minutes}: the node's time requirements in minutes, null when it has none. {@code startAt} and {@code endAt}: when
     * it runs, in its equipment's working time (ADR-043 decision 5).
     */
    public record Node(String processId, BigDecimal batches, BigDecimal through, String unit, BigDecimal minutes,
        List<Port> ports, List<Execution> execution, java.time.OffsetDateTime startAt, java.time.OffsetDateTime endAt) {

        public Node placed(PlanSchedulePlace place) {
            return new Node(processId, batches, through, unit, minutes, ports, execution, place.startAt(), place.endAt());
        }
    }

    /** Where a node falls in time. */
    public record PlanSchedulePlace(java.time.OffsetDateTime startAt, java.time.OffsetDateTime endAt) {
    }

    public record Port(String processIoId, String direction, BigDecimal quantity, String unit) {
    }

    public record Execution(String kind, String ref, BigDecimal amount, String unit) {
    }

    public record Surplus(String at, String kind, BigDecimal quantity, String unit) {
    }

    /**
     * {@code kind}: {@code stock}, {@code equipment-time}, {@code resource-capacity} ({@code at} is the resource type), or one
     * of the graph's (split-fixed, exclusive, capacity, ...).
     */
    public record Limit(String kind, String at, String processId, String message) {
    }

    public record Assumption(String kind, String at, String value) {
    }

    /** The revision and each item's usable stock the plan read. */
    public record Basis(String revisionId, Map<String, BigDecimal> available) {
    }
}
