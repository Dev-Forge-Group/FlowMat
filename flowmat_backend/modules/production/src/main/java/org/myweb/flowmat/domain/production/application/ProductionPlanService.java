package org.myweb.flowmat.domain.production.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.UnitConversion;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemandGraphs;
import org.myweb.flowmat.domain.production.api.dto.request.PlanPreviewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.PlanPreviewResponse;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccess;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowAccessView;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * The manufacturing plan for a target (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md, the manufacturing
 * layer): the common layer's needs ({@link FlowDemand}) against usable stock by item, what stock could make now, each node's
 * time, and what limits the plan. Read only: it reserves and writes nothing.
 */
@Service
@RequiredArgsConstructor
public class ProductionPlanService {

    private static final int SCALE = 4;
    private static final MathContext MC = MathContext.DECIMAL128;

    private final WorkflowAccess workflowAccess;
    private final WorkflowProductionQuery revisions;
    private final CatalogQuery catalogQuery;
    private final UnitConversion unitConversion;
    private final UsableStock usableStock;
    private final ObjectMapper objectMapper;
    private final PlanSchedule schedule;

    public PlanPreviewResponse preview(String workflowId, PlanPreviewRequest request) {
        WorkflowAccessView workflow = workflowAccess.requireRead(workflowId);
        WorkflowProductionQuery.Revision revision = revision(workflow.workflowId(), request.revisionId());
        JsonNode snapshot = snapshot(revision);
        FlowDemand.Graph graph = FlowDemandGraphs.fromSnapshot(snapshot);

        Set<String> itemIds = graph.ports().stream().map(FlowDemand.Port::ref).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, CatalogItemView> items = itemIds.isEmpty() ? Map.of() : catalogQuery.findItems(itemIds);
        UnitConversion.Table table = unitConversion.table();
        FlowDemand.Result result;
        PlanPreviewRequest.Target target = request.target();
        try {
            result = FlowDemand.plan(new FlowDemand.Request(graph,
                new FlowDemand.Target(target.processIoId(), target.connectionId(), target.quantity(), target.unit()),
                request.exclusiveChoices(), units(items.values(), table)));
        } catch (FlowDemand.Refusal refusal) {
            throw new BusinessException(ErrorCode.CONFLICT, refusal.getMessage());
        }

        Map<String, String> owner = owners(graph);
        Map<String, String> resourceTypes = resourceTypes(snapshot);
        Map<String, BigDecimal> usable = itemIds.isEmpty() ? Map.of() : usableStock.byItem(workflow.projectId(), itemIds);
        List<PlanPreviewResponse.Requirement> requirements = requirements(result, items, usable, owner, table, resourceTypes);
        List<PlanPreviewResponse.Node> nodes = placed(workflow.projectId(), graph, snapshot,
            result.nodes().stream().map(ProductionPlanService::node).toList(), request.startAt());
        List<PlanPreviewResponse.Limit> limits = new ArrayList<>();
        requirements.stream()
            .filter(row -> row.shortage() != null && row.shortage().signum() > 0)
            .sorted(Comparator.comparing(PlanPreviewResponse.Requirement::shortage).reversed())
            .forEach(row -> limits.add(new PlanPreviewResponse.Limit("stock", row.at(), row.processId(),
                row.itemName() + " is short by " + plain(row.shortage()) + " " + row.unit() + ".")));
        longestEquipmentStep(result, nodes).ifPresent(limits::add);
        result.limits().forEach(limit -> limits.add(new PlanPreviewResponse.Limit(limit.kind(), limit.at(), owner.get(limit.at()),
            limit.message())));

        Map<String, BigDecimal> basis = new TreeMap<>();
        requirements.stream().filter(row -> row.itemId() != null && row.available() != null)
            .forEach(row -> basis.put(row.itemId(), row.available()));
        return new PlanPreviewResponse(revision.workflowRevisionId(), result.target(), result.unit(), result.feasible(),
            buildable(result, requirements, items, targetItem(graph, target)), requirements, nodes,
            result.surplus().stream().map(s -> new PlanPreviewResponse.Surplus(s.at(), s.kind(), s.quantity(), s.unit())).toList(),
            List.copyOf(limits),
            result.assumptions().stream().map(a -> new PlanPreviewResponse.Assumption(a.kind(), a.at(), a.value())).toList(),
            new PlanPreviewResponse.Basis(revision.workflowRevisionId(), basis),
            nodes.stream().anyMatch(node -> node.minutes() != null && node.endAt() == null) ? null
                : nodes.stream().map(PlanPreviewResponse.Node::endAt).filter(Objects::nonNull).max(java.util.Comparator.naturalOrder())
                    .orElse(null),
            resources(requirements), costs(requirements),
            costs(requirements).stream().map(PlanPreviewResponse.Cost::cost).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** Energy and water are drawn as used, not kept in stock (ADR-046 decision 2). */
    private static final Set<String> NOT_STOCKED = Set.of("energy", "water");

    /** Each port's resource type from the revision, lower case; ports without one are left out (material). */
    private static Map<String, String> resourceTypes(JsonNode snapshot) {
        Map<String, String> types = new HashMap<>();
        snapshot.path("processIos").forEach(port -> {
            String type = port.path("resourceType").asText("").trim().toLowerCase(Locale.ROOT);
            if (!type.isEmpty() && port.hasNonNull("processIoId")) types.put(port.path("processIoId").asText(), type);
        });
        return types;
    }

    /** The energy and water rows by kind and unit, in the order they come (ADR-046 decision 4). */
    private static List<PlanPreviewResponse.Resource> resources(List<PlanPreviewResponse.Requirement> requirements) {
        Map<String, PlanPreviewResponse.Resource> byKind = new LinkedHashMap<>();
        for (PlanPreviewResponse.Requirement row : requirements) {
            if (!NOT_STOCKED.contains(row.resourceType())) continue;
            String key = row.resourceType() + "\u0000" + row.unit();
            PlanPreviewResponse.Resource earlier = byKind.get(key);
            BigDecimal cost = row.cost() == null ? BigDecimal.ZERO : row.cost();
            byKind.put(key, new PlanPreviewResponse.Resource(row.resourceType(), row.unit(),
                earlier == null ? row.needed() : earlier.needed().add(row.needed()),
                earlier == null ? cost.setScale(SCALE, RoundingMode.HALF_UP) : earlier.cost().add(cost)));
        }
        return List.copyOf(byKind.values());
    }

    /** The known cost of the rows by kind, with how many have none (ADR-046 decision 4). */
    private static List<PlanPreviewResponse.Cost> costs(List<PlanPreviewResponse.Requirement> requirements) {
        Map<String, BigDecimal> known = new LinkedHashMap<>();
        Map<String, Integer> unknown = new HashMap<>();
        for (PlanPreviewResponse.Requirement row : requirements) {
            known.merge(row.resourceType(), row.cost() == null ? BigDecimal.ZERO : row.cost(), BigDecimal::add);
            if (row.cost() == null) unknown.merge(row.resourceType(), 1, Integer::sum);
        }
        return known.entrySet().stream().map(entry -> new PlanPreviewResponse.Cost(entry.getKey(),
            entry.getValue().setScale(SCALE, RoundingMode.HALF_UP), unknown.getOrDefault(entry.getKey(), 0))).toList();
    }

    /** The item at a target of a revision the caller has already planned. */
    public String targetItemOf(String workflowId, String revisionId, PlanPreviewRequest.Target target) {
        return revisions.findRevision(revisionId, workflowId)
            .map(revision -> targetItem(FlowDemandGraphs.fromSnapshot(snapshot(revision)), target)).orElse(null);
    }

    /** The nodes placed in time from {@code startAt} (now when left out), in the plan's order (ADR-043 decision 5). */
    private List<PlanPreviewResponse.Node> placed(String projectId, FlowDemand.Graph graph, JsonNode snapshot,
        List<PlanPreviewResponse.Node> nodes, java.time.OffsetDateTime startAt) {
        Map<String, BigDecimal> minutes = new HashMap<>();
        nodes.forEach(node -> { if (node.minutes() != null) minutes.put(node.processId(), node.minutes()); });
        java.time.Instant from = startAt == null ? java.time.Instant.now() : startAt.toInstant();
        Map<String, PlanSchedule.Placed> placed = schedule.place(projectId, graph, snapshot,
            nodes.stream().map(PlanPreviewResponse.Node::processId).toList(), minutes, from);
        java.time.ZoneOffset offset = startAt == null ? java.time.ZoneOffset.UTC : startAt.getOffset();
        return nodes.stream().map(node -> {
            PlanSchedule.Placed at = placed.get(node.processId());
            return at == null ? node : node.placed(new PlanPreviewResponse.PlanSchedulePlace(
                at.start() == null ? null : at.start().atOffset(offset), at.end() == null ? null : at.end().atOffset(offset)));
        }).toList();
    }

    private WorkflowProductionQuery.Revision revision(String workflowId, String revisionId) {
        if (revisionId == null || revisionId.isBlank()) {
            return revisions.latestPublishedRevision(workflowId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "Publish the workflow to plan it."));
        }
        WorkflowProductionQuery.Revision revision = revisions.findRevision(revisionId.trim(), workflowId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "Workflow revision was not found for this workflow."));
        if (!"published".equals(revision.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Retired workflow revisions cannot be planned.");
        }
        return revision;
    }

    private JsonNode snapshot(WorkflowProductionQuery.Revision revision) {
        try {
            return objectMapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(revision.snapshotJson());
        } catch (IOException unreadable) {
            throw new IllegalStateException("Stored workflow revision snapshot is invalid.", unreadable);
        }
    }

    /** Unit codes the plan's items use, converted through the unit master; any other pair cannot be converted. */
    private static FlowDemand.Units units(Iterable<CatalogItemView> items, UnitConversion.Table table) {
        Map<String, String> unitIds = new HashMap<>();
        for (CatalogItemView item : items) {
            if (item.unitCode() != null && item.unitId() != null) unitIds.putIfAbsent(item.unitCode().toLowerCase(Locale.ROOT), item.unitId());
        }
        return (amount, from, to) -> {
            String unitId = unitIds.get(to.toLowerCase(Locale.ROOT));
            if (unitId == null) return Optional.empty();
            try {
                return Optional.ofNullable(table.toItemUnit(amount, from, unitId).quantity());
            } catch (BusinessException cannot) {
                return Optional.empty();
            }
        };
    }

    /** The node each port, node and connection id belongs to; a connection's is its source. */
    private static Map<String, String> owners(FlowDemand.Graph graph) {
        Map<String, String> owner = new HashMap<>();
        graph.nodes().forEach(node -> owner.put(node.id(), node.id()));
        graph.ports().forEach(port -> owner.put(port.id(), port.nodeId()));
        graph.connections().forEach(connection -> owner.put(connection.id(), connection.from()));
        return owner;
    }

    /** The starts' needs by item in the item's unit, rounded up, against usable stock; starts without an item one by one. */
    private List<PlanPreviewResponse.Requirement> requirements(FlowDemand.Result result, Map<String, CatalogItemView> items,
        Map<String, BigDecimal> usable, Map<String, String> owner, UnitConversion.Table table, Map<String, String> resourceTypes) {
        Map<String, PlanPreviewResponse.Requirement> byItem = new LinkedHashMap<>();
        List<PlanPreviewResponse.Requirement> rows = new ArrayList<>();
        for (FlowDemand.Requirement start : result.requirements()) {
            List<PlanPreviewResponse.TraceStep> trace = start.trace().stream().map(step -> new PlanPreviewResponse.TraceStep(
                step.at(), owner.get(step.at()), step.rule(), step.from(), step.to(), step.factor())).toList();
            CatalogItemView item = start.ref() == null ? null : items.get(start.ref());
            String resourceType = resourceTypes.getOrDefault(start.at(), "material");
            if (item == null) {
                rows.add(new PlanPreviewResponse.Requirement(start.at(), start.nodeId(), start.ref(), null, start.quantity(), null,
                    null, start.unit(), trace, resourceType, null, null));
                continue;
            }
            BigDecimal needed = start.unit() == null ? start.quantity()
                : table.toItemUnit(start.quantity(), start.unit(), item.unitId()).quantity();
            needed = needed.setScale(SCALE, RoundingMode.CEILING);
            PlanPreviewResponse.Requirement earlier = byItem.get(item.itemId());
            if (earlier != null) {
                needed = needed.add(earlier.needed());
                trace = earlier.trace();
            }
            // The first port's kind decides for an item that comes in at several (ADR-046 decision 1).
            String kind = earlier == null ? resourceType : earlier.resourceType();
            boolean stocked = !NOT_STOCKED.contains(kind);
            BigDecimal available = stocked
                ? usable.getOrDefault(item.itemId(), BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal shortage = stocked
                ? needed.subtract(available).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP) : null;
            BigDecimal unitCost = item.unitCost() == null || item.unitCost().signum() <= 0 ? null : item.unitCost();
            BigDecimal cost = unitCost == null ? null : needed.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP);
            byItem.put(item.itemId(), new PlanPreviewResponse.Requirement(earlier == null ? start.at() : earlier.at(),
                earlier == null ? start.nodeId() : earlier.processId(), item.itemId(), item.itemName(), needed, available, shortage,
                item.unitCode(), trace, kind, unitCost, cost));
        }
        List<PlanPreviewResponse.Requirement> all = new ArrayList<>(byItem.values());
        all.addAll(rows);
        return all;
    }

    /**
     * What usable stock could make now (ADR-043 decision 5, as BOMs' buildable): for each item, usable ÷ what one unit of the
     * target needs, the least of them, rounded down; whole units when the target's item is counted.
     */
    private static BigDecimal buildable(FlowDemand.Result result, List<PlanPreviewResponse.Requirement> requirements,
        Map<String, CatalogItemView> items, String targetItem) {
        BigDecimal least = null;
        for (PlanPreviewResponse.Requirement row : requirements) {
            if (row.itemId() == null || row.available() == null || row.needed().signum() == 0) continue;
            BigDecimal allows = row.available().multiply(result.target(), MC).divide(row.needed(), MC);
            least = least == null ? allows : least.min(allows);
        }
        if (least == null) return null;
        CatalogItemView item = targetItem == null ? null : items.get(targetItem);
        return least.setScale(item != null && "count".equals(item.unitType()) ? 0 : SCALE, RoundingMode.FLOOR);
    }

    /** The item at the target: its port's, or for a connection the port it leaves from. */
    private static String targetItem(FlowDemand.Graph graph, PlanPreviewRequest.Target target) {
        String portId = target.processIoId() != null ? target.processIoId() : graph.connections().stream()
            .filter(connection -> connection.id().equals(target.connectionId())).map(FlowDemand.Connection::fromPort)
            .filter(Objects::nonNull).findFirst().orElse(null);
        return graph.ports().stream().filter(port -> port.id().equals(portId)).map(FlowDemand.Port::ref)
            .filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** The node with the most minutes among those that take equipment: the time the plan waits on (ADR-043 decision 5). */
    private static Optional<PlanPreviewResponse.Limit> longestEquipmentStep(FlowDemand.Result result, List<PlanPreviewResponse.Node> nodes) {
        Set<String> withEquipment = result.nodes().stream()
            .filter(node -> node.execution().stream().anyMatch(total -> "equipment".equals(total.kind())))
            .map(FlowDemand.NodeDemand::nodeId).collect(Collectors.toSet());
        return nodes.stream().filter(node -> withEquipment.contains(node.processId()) && node.minutes() != null)
            .max(Comparator.comparing(PlanPreviewResponse.Node::minutes))
            .map(node -> new PlanPreviewResponse.Limit("equipment-time", node.processId(), node.processId(),
                "The longest equipment step takes " + plain(node.minutes()) + " minutes."));
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** A node of the plan with its time requirements in minutes. */
    private static PlanPreviewResponse.Node node(FlowDemand.NodeDemand node) {
        BigDecimal minutes = null;
        for (FlowDemand.ExecutionTotal total : node.execution()) {
            if (!"time".equals(total.kind())) continue;
            BigDecimal inMinutes = minutes(total.amount(), total.unit());
            minutes = minutes == null ? inMinutes : minutes.add(inMinutes);
        }
        return new PlanPreviewResponse.Node(node.nodeId(), node.batches(), node.through(), node.unit(),
            minutes == null ? null : minutes.setScale(SCALE, RoundingMode.CEILING),
            node.ports().stream().map(p -> new PlanPreviewResponse.Port(p.portId(), p.direction(), p.quantity(), p.unit())).toList(),
            node.execution().stream().map(e -> new PlanPreviewResponse.Execution(e.kind(), e.ref(), e.amount(), e.unit())).toList(), null, null);
    }

    private static BigDecimal minutes(BigDecimal amount, String unit) {
        String code = unit == null ? "min" : unit.toLowerCase(Locale.ROOT);
        return switch (code) {
            case "s", "sec", "second", "seconds" -> amount.divide(BigDecimal.valueOf(60), MC);
            case "h", "hr", "hour", "hours" -> amount.multiply(BigDecimal.valueOf(60));
            default -> amount;
        };
    }
}
