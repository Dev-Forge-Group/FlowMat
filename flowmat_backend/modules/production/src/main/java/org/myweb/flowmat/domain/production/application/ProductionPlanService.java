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

    public PlanPreviewResponse preview(String workflowId, PlanPreviewRequest request) {
        WorkflowAccessView workflow = workflowAccess.requireRead(workflowId);
        WorkflowProductionQuery.Revision revision = revision(workflow.workflowId(), request.revisionId());
        JsonNode snapshot = snapshot(revision);
        FlowDemand.Graph graph = FlowDemandGraphs.fromSnapshot(snapshot, Map.of());

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
        Map<String, BigDecimal> usable = itemIds.isEmpty() ? Map.of() : usableStock.byItem(workflow.projectId(), itemIds);
        List<PlanPreviewResponse.Requirement> requirements = requirements(result, items, usable, owner, table);
        List<PlanPreviewResponse.Node> nodes = result.nodes().stream().map(ProductionPlanService::node).toList();
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
        requirements.stream().filter(row -> row.itemId() != null).forEach(row -> basis.put(row.itemId(), row.available()));
        return new PlanPreviewResponse(revision.workflowRevisionId(), result.target(), result.unit(), result.feasible(),
            buildable(result, requirements, items, targetItem(graph, target)), requirements, nodes,
            result.surplus().stream().map(s -> new PlanPreviewResponse.Surplus(s.at(), s.kind(), s.quantity(), s.unit())).toList(),
            List.copyOf(limits),
            result.assumptions().stream().map(a -> new PlanPreviewResponse.Assumption(a.kind(), a.at(), a.value())).toList(),
            new PlanPreviewResponse.Basis(revision.workflowRevisionId(), basis));
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
        Map<String, BigDecimal> usable, Map<String, String> owner, UnitConversion.Table table) {
        Map<String, PlanPreviewResponse.Requirement> byItem = new LinkedHashMap<>();
        List<PlanPreviewResponse.Requirement> rows = new ArrayList<>();
        for (FlowDemand.Requirement start : result.requirements()) {
            List<PlanPreviewResponse.TraceStep> trace = start.trace().stream().map(step -> new PlanPreviewResponse.TraceStep(
                step.at(), owner.get(step.at()), step.rule(), step.from(), step.to(), step.factor())).toList();
            CatalogItemView item = start.ref() == null ? null : items.get(start.ref());
            if (item == null) {
                rows.add(new PlanPreviewResponse.Requirement(start.at(), start.nodeId(), start.ref(), null, start.quantity(), null,
                    null, start.unit(), trace));
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
            BigDecimal available = usable.getOrDefault(item.itemId(), BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal shortage = needed.subtract(available).max(BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP);
            byItem.put(item.itemId(), new PlanPreviewResponse.Requirement(earlier == null ? start.at() : earlier.at(),
                earlier == null ? start.nodeId() : earlier.processId(), item.itemId(), item.itemName(), needed, available, shortage,
                item.unitCode(), trace));
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
            if (row.itemId() == null || row.needed().signum() == 0) continue;
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
            node.execution().stream().map(e -> new PlanPreviewResponse.Execution(e.kind(), e.ref(), e.amount(), e.unit())).toList());
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
