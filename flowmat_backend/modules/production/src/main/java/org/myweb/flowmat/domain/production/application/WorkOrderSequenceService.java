package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderBatchPlanRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderSequenceResponse;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.springframework.stereotype.Service;

/**
 * Suggests the order in which to plan several work orders so each piece of equipment changes over as little as possible
 * (docs/domain/equipment-schedule.md P13-P16; user decision 2026-10-12 "fewest changeovers"). The orders are grouped by
 * equipment; within one, the order with the fewest changeover minutes after the order already planned before the start
 * wins, ties going to the higher priority, then the earlier planned end, then the order given. Read only.
 */
@Service
@RequiredArgsConstructor
public class WorkOrderSequenceService {

    private static final String NOT_DELETED = "N";
    /** Up to this many orders on one equipment are ordered exactly; more are ordered nearest first. */
    static final int EXACT_LIMIT = 12;
    private static final List<String> PRIORITIES = List.of("urgent", "high", "normal", "low");

    private final WorkOrderPlanService workOrderPlanService;
    private final WorkOrderRepository workOrderRepository;
    private final CatalogQuery catalogQuery;

    public WorkOrderSequenceResponse suggestOrder(WorkOrderBatchPlanRequest request) {
        List<WorkOrder> orders = workOrderPlanService.batchOrders(request);
        OffsetDateTime requested = WorkOrderPlanService.parseFrom(request.from());
        OffsetDateTime start = requested != null ? requested : OffsetDateTime.now(ZoneOffset.UTC);
        Set<String> batch = orders.stream().map(WorkOrder::getWorkOrderId).collect(Collectors.toUnmodifiableSet());
        Map<String, List<WorkOrder>> byEquipment = new LinkedHashMap<>();
        List<WorkOrder> loose = new ArrayList<>();
        for (WorkOrder order : orders) {
            if (order.getEquipmentId() == null) {
                loose.add(order);
            } else {
                byEquipment.computeIfAbsent(order.getEquipmentId(), id -> new ArrayList<>()).add(order);
            }
        }

        List<String> sequence = new ArrayList<>();
        List<WorkOrderSequenceResponse.Group> groups = new ArrayList<>();
        int total = 0;
        int givenTotal = 0;
        for (Map.Entry<String, List<WorkOrder>> entry : byEquipment.entrySet()) {
            String equipmentId = entry.getKey();
            List<WorkOrder> others = workOrderRepository.findAllByEquipmentIdAndDeletedYn(equipmentId, NOT_DELETED).stream()
                .filter(other -> !batch.contains(other.getWorkOrderId()))
                .toList();
            // The first order changes over from the one already planned before the start, as a single suggestion does (P5).
            WorkOrder before = EquipmentSequence.previous(start, others);
            String startItem = before == null ? null : before.getTargetItemId();
            List<WorkOrder> ranked = entry.getValue().stream()
                .sorted(Comparator.comparingInt(WorkOrderSequenceService::priorityRank)
                    .thenComparing(WorkOrder::getPlannedEndAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
            Map<String, Integer> known = new HashMap<>();
            int n = ranked.size();
            int[] first = new int[n];
            int[][] cost = new int[n][n];
            for (int i = 0; i < n; i++) {
                first[i] = changeover(equipmentId, startItem, ranked.get(i).getTargetItemId(), known);
                for (int j = 0; j < n; j++) {
                    cost[i][j] = i == j ? 0 : changeover(equipmentId, ranked.get(i).getTargetItemId(),
                        ranked.get(j).getTargetItemId(), known);
                }
            }
            List<String> suggested = new ArrayList<>();
            int minutes = 0;
            int last = -1;
            for (int index : order(first, cost)) {
                minutes += last < 0 ? first[index] : cost[last][index];
                suggested.add(ranked.get(index).getWorkOrderId());
                last = index;
            }
            int given = 0;
            String previousItem = startItem;
            for (WorkOrder order : entry.getValue()) {
                given += changeover(equipmentId, previousItem, order.getTargetItemId(), known);
                previousItem = order.getTargetItemId();
            }
            groups.add(new WorkOrderSequenceResponse.Group(equipmentId, before == null ? null : before.getWorkOrderNumber(),
                List.copyOf(suggested), minutes, given));
            sequence.addAll(suggested);
            total += minutes;
            givenTotal += given;
        }
        loose.forEach(order -> sequence.add(order.getWorkOrderId()));
        return new WorkOrderSequenceResponse(List.copyOf(sequence), total, givenTotal, List.copyOf(groups));
    }

    /**
     * The visiting order of n orders with the fewest total minutes: {@code first[j]} from the order planned before them,
     * {@code cost[i][j]} from order i to order j. Exact up to {@link #EXACT_LIMIT} orders, otherwise nearest first. Among
     * equal totals the lowest indices come first, so the caller's ranking breaks ties.
     */
    static int[] order(int[] first, int[][] cost) {
        int n = first.length;
        if (n == 0) {
            return new int[0];
        }
        if (n > EXACT_LIMIT) {
            return nearestFirst(first, cost);
        }
        int full = (1 << n) - 1;
        // rest[mask][last]: the fewest minutes to visit the orders outside mask, having visited mask and ended at last.
        int[][] rest = new int[1 << n][n];
        for (int mask = full - 1; mask >= 1; mask--) {
            for (int last = 0; last < n; last++) {
                if ((mask & (1 << last)) == 0) {
                    continue;
                }
                int best = Integer.MAX_VALUE;
                for (int next = 0; next < n; next++) {
                    if ((mask & (1 << next)) == 0) {
                        best = Math.min(best, cost[last][next] + rest[mask | (1 << next)][next]);
                    }
                }
                rest[mask][last] = best;
            }
        }
        int target = Integer.MAX_VALUE;
        for (int next = 0; next < n; next++) {
            target = Math.min(target, first[next] + rest[1 << next][next]);
        }
        int[] picked = new int[n];
        int mask = 0;
        int last = -1;
        for (int step = 0; step < n; step++) {
            for (int next = 0; next < n; next++) {
                if ((mask & (1 << next)) != 0) {
                    continue;
                }
                int move = last < 0 ? first[next] : cost[last][next];
                if (move + rest[mask | (1 << next)][next] == target) {
                    picked[step] = next;
                    target -= move;
                    mask |= 1 << next;
                    last = next;
                    break;
                }
            }
        }
        return picked;
    }

    /** Each step goes to the cheapest order not yet taken; the lowest index wins a tie. */
    static int[] nearestFirst(int[] first, int[][] cost) {
        int n = first.length;
        boolean[] taken = new boolean[n];
        int[] picked = new int[n];
        int last = -1;
        for (int step = 0; step < n; step++) {
            int best = -1;
            int bestMove = Integer.MAX_VALUE;
            for (int next = 0; next < n; next++) {
                int move = last < 0 ? first[next] : cost[last][next];
                if (!taken[next] && move < bestMove) {
                    best = next;
                    bestMove = move;
                }
            }
            taken[best] = true;
            picked[step] = best;
            last = best;
        }
        return picked;
    }

    /** The equipment's changeover minutes from one item to another; zero without either item or a rule (as P5). */
    private int changeover(String equipmentId, String fromItemId, String toItemId, Map<String, Integer> known) {
        if (fromItemId == null || toItemId == null) {
            return 0;
        }
        return known.computeIfAbsent(fromItemId + "->" + toItemId,
            key -> catalogQuery.changeoverMinutes(equipmentId, fromItemId, toItemId).orElse(0));
    }

    private static int priorityRank(WorkOrder order) {
        int rank = PRIORITIES.indexOf(order.getPriority() == null ? "normal" : order.getPriority());
        return rank < 0 ? PRIORITIES.indexOf("normal") : rank;
    }
}
