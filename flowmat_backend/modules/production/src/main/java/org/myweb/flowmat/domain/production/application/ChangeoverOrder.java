package org.myweb.flowmat.domain.production.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntBiFunction;

/**
 * The order of one equipment's work orders that changes over least (docs/domain/equipment-load.md "전환 순서 제안"). Up
 * to {@link #EXACT_UP_TO} movable orders every order is tried, the plan's first, and only a strictly smaller total
 * replaces the best so far, so ties keep the order nearest the plan. Beyond that the nearest next item is taken each time.
 */
final class ChangeoverOrder {

    static final int EXACT_UP_TO = 8;

    /** Minutes in planned order; the suggested order (indexes into the plan) and its minutes only when that is less. */
    record Plan(int plannedMinutes, Integer suggestedMinutes, List<Integer> suggestedOrder) {
    }

    private ChangeoverOrder() {
    }

    /**
     * @param items   the target item of each order, in planned order
     * @param first   indexes of the orders that stay at the front, in planned order (already running)
     * @param before  the item the equipment runs before the first order; null when none
     * @param minutes changeover minutes from one item to the next, 0 when none applies
     */
    static Plan plan(List<String> items, Set<Integer> first, String before, ToIntBiFunction<String, String> minutes) {
        List<Integer> planned = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            planned.add(index);
        }
        int plannedMinutes = total(planned, items, before, minutes);
        List<Integer> head = planned.stream().filter(first::contains).toList();
        List<Integer> rest = planned.stream().filter(index -> !first.contains(index)).toList();
        String start = head.isEmpty() ? before : items.get(head.get(head.size() - 1));
        List<Integer> best = rest.size() <= EXACT_UP_TO ? exact(rest, items, start, minutes) : nearest(rest, items, start, minutes);
        List<Integer> suggested = new ArrayList<>(head);
        suggested.addAll(best);
        int suggestedMinutes = total(suggested, items, before, minutes);
        return suggestedMinutes < plannedMinutes
            ? new Plan(plannedMinutes, suggestedMinutes, List.copyOf(suggested))
            : new Plan(plannedMinutes, null, null);
    }

    private static int total(List<Integer> order, List<String> items, String before, ToIntBiFunction<String, String> minutes) {
        int sum = 0;
        String previous = before;
        for (int index : order) {
            sum += cost(previous, items.get(index), minutes);
            previous = items.get(index);
        }
        return sum;
    }

    private static int cost(String from, String to, ToIntBiFunction<String, String> minutes) {
        return from == null ? 0 : minutes.applyAsInt(from, to);
    }

    /** Every order of {@code rest}, the plan's own first; a later one wins only with strictly fewer minutes. */
    private static List<Integer> exact(List<Integer> rest, List<String> items, String start, ToIntBiFunction<String, String> minutes) {
        Search search = new Search(rest, items, minutes, new ArrayList<>(rest), total(rest, items, start, minutes));
        search.extend(new ArrayList<>(), new boolean[rest.size()], start, 0);
        return search.best;
    }

    private static final class Search {

        private final List<Integer> rest;
        private final List<String> items;
        private final ToIntBiFunction<String, String> minutes;
        private List<Integer> best;
        private int bestMinutes;

        private Search(List<Integer> rest, List<String> items, ToIntBiFunction<String, String> minutes, List<Integer> best,
                       int bestMinutes) {
            this.rest = rest;
            this.items = items;
            this.minutes = minutes;
            this.best = best;
            this.bestMinutes = bestMinutes;
        }

        private void extend(List<Integer> order, boolean[] used, String previous, int sofar) {
            // Minutes never fall, so a start already at the best cannot end strictly below it.
            if (sofar >= bestMinutes) {
                return;
            }
            if (order.size() == rest.size()) {
                best = List.copyOf(order);
                bestMinutes = sofar;
                return;
            }
            for (int position = 0; position < rest.size(); position++) {
                if (used[position]) {
                    continue;
                }
                int index = rest.get(position);
                used[position] = true;
                order.add(index);
                extend(order, used, items.get(index), sofar + cost(previous, items.get(index), minutes));
                order.remove(order.size() - 1);
                used[position] = false;
            }
        }
    }

    /** From the start, the order whose item changes over least next, the earliest in the plan on a tie. */
    private static List<Integer> nearest(List<Integer> rest, List<String> items, String start, ToIntBiFunction<String, String> minutes) {
        List<Integer> left = new ArrayList<>(rest);
        List<Integer> order = new ArrayList<>();
        String previous = start;
        while (!left.isEmpty()) {
            int pick = 0;
            int pickMinutes = Integer.MAX_VALUE;
            for (int position = 0; position < left.size(); position++) {
                int next = cost(previous, items.get(left.get(position)), minutes);
                if (next < pickMinutes) {
                    pick = position;
                    pickMinutes = next;
                }
            }
            int index = left.remove(pick);
            order.add(index);
            previous = items.get(index);
        }
        return order;
    }
}
