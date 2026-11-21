"""The order of a snapshot's jobs that costs least, by OR-Tools CP-SAT (ADR-039 decision 3).

The model works on the equipment's working time: second 0 is the window's start and only available seconds count, so
jobs run back to back without gaps, as the cost assumes. A circuit through a start node and the jobs picks the order; each
job's end on the working axis is the end before it plus its changeover from the item before and its production. Its end
on the clock is that plus every gap between slots it has passed, and it is late by the minutes, rounded up, that the clock
end passes its planned end. The objective is the cost's own (changeover minutes + minutes late x priority weight), doubled
so the weights are whole numbers. The current rule's order is the hint, so the answer is never worse than it; the answer
is checked with the cost mirror all the same.
"""
from __future__ import annotations

import time
from dataclasses import dataclass
from decimal import Decimal

from ortools.sat.python import cp_model

from . import cost

TIME_LIMIT_SECONDS = 10.0


@dataclass(frozen=True)
class Answer:
    order: list[str]
    status: str
    seconds: float
    objective: Decimal | None


def _working_axis(snapshot: dict):
    """Each slot's end on the working axis and the clock seconds of gaps passed by then; and the axis's length."""
    start = cost.parse_time(snapshot["from"])
    boundaries = []  # (working seconds at the slot's end, clock seconds from start at the slot's end)
    worked = 0
    for slot_start, slot_end in cost.slots(snapshot):
        if slot_end <= start:
            continue
        begin = max(slot_start, start)
        worked += int((slot_end - begin).total_seconds())
        boundaries.append((worked, int((slot_end - start).total_seconds()), int((begin - start).total_seconds())))
    return boundaries, worked


def solve(snapshot: dict, hint: list[str] | None = None, time_limit: float = TIME_LIMIT_SECONDS, workers: int = 8) -> Answer:
    jobs = snapshot["jobs"]
    count = len(jobs)
    numbers = [job["workOrderNumber"] for job in jobs]
    started = time.perf_counter()
    if count == 0:
        return Answer([], "OPTIMAL", 0.0, Decimal(0))
    boundaries, total = _working_axis(snapshot)
    origin = cost.parse_time(snapshot["from"])
    model = cp_model.CpModel()

    # Nodes: 0 is the start, job i is node i + 1.
    arcs = {}
    def arc(a: int, b: int):
        literal = model.NewBoolVar(f"x{a}_{b}")
        arcs[(a, b)] = literal
        return literal
    circuit = []
    for b in range(1, count + 1):
        circuit.append((0, b, arc(0, b)))
        circuit.append((b, 0, arc(b, 0)))
        for a in range(1, count + 1):
            if a != b:
                circuit.append((a, b, arc(a, b)))
    model.AddCircuit(circuit)

    # Running jobs stay first, in their order.
    fixed = [index for index, job in enumerate(jobs) if job["fixed"]]
    for position, index in enumerate(fixed):
        previous = 0 if position == 0 else fixed[position - 1] + 1
        model.Add(arcs[(previous, index + 1)] == 1)

    ends = [model.NewIntVar(0, total, f"end{i}") for i in range(count)]
    late = []
    change_terms = []
    late_terms = []
    for b in range(count):
        job = jobs[b]
        production = int(job["productionSeconds"])
        first = cost.changeover(snapshot, snapshot.get("itemBefore"), job["item"])
        model.Add(ends[b] == first * 60 + production).OnlyEnforceIf(arcs[(0, b + 1)])
        change_terms.append(first * arcs[(0, b + 1)])
        for a in range(count):
            if a == b:
                continue
            minutes = cost.changeover(snapshot, jobs[a]["item"], job["item"])
            model.Add(ends[b] == ends[a] + minutes * 60 + production).OnlyEnforceIf(arcs[(a + 1, b + 1)])
            if minutes:
                change_terms.append(minutes * arcs[(a + 1, b + 1)])
        # Clock end = working end + the gaps before the slot it ends in (ending exactly at a slot's end stays in it).
        gaps = []
        for index, (worked, clock_end, _) in enumerate(boundaries[:-1]):
            gap = boundaries[index + 1][2] - clock_end
            if gap <= 0:
                continue
            past = model.NewBoolVar(f"past{b}_{index}")
            model.Add(ends[b] >= worked + 1).OnlyEnforceIf(past)
            model.Add(ends[b] <= worked).OnlyEnforceIf(past.Not())
            gaps.append(gap * past)
        first_offset = boundaries[0][2] if boundaries else 0
        clock_end = ends[b] + first_offset + sum(gaps)
        due = int((cost.parse_time(job["plannedEnd"]) - origin).total_seconds())
        minutes_late = model.NewIntVar(0, total // 60 + 2 * 24 * 60 * 30, f"late{b}")
        model.Add(minutes_late * 60 >= clock_end - due)
        late.append(minutes_late)
        double_weight = int(cost.weight(snapshot, job.get("priority")) * 2)
        late_terms.append(double_weight * minutes_late)
    change_weight = int(Decimal(str(snapshot["weights"]["changeoverMinute"])) * 2)
    model.Minimize(change_weight * sum(change_terms) + sum(late_terms))

    if hint:
        position = {number: index for index, number in enumerate(hint)}
        order = sorted(range(count), key=lambda index: position[numbers[index]])
        previous = 0
        for index in order:
            for (a, b), literal in arcs.items():
                if b == index + 1:
                    model.AddHint(literal, 1 if a == previous else 0)
            previous = index + 1
        for (a, b), literal in arcs.items():
            if b == 0:
                model.AddHint(literal, 1 if a == previous else 0)

    solver = cp_model.CpSolver()
    solver.parameters.max_time_in_seconds = time_limit
    solver.parameters.num_workers = workers
    solver.parameters.random_seed = 1
    status = solver.Solve(model)
    elapsed = time.perf_counter() - started
    if status not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
        return Answer(list(hint or numbers), solver.StatusName(status), elapsed, None)
    order = []
    node = 0
    while True:
        node = next(b for (a, b), literal in arcs.items() if a == node and solver.Value(literal))
        if node == 0:
            break
        order.append(numbers[node - 1])
    return Answer(order, solver.StatusName(status), elapsed, Decimal(int(solver.ObjectiveValue())) / 2)
