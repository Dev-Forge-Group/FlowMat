"""The solver's answers: never worse than the current rule, optimal where every order can be tried."""
import itertools
import json
from pathlib import Path

from sequence_optimizer import cost, generate, solver

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


def test_on_small_cases_the_solver_finds_the_best_of_every_order():
    for number in range(1, 9):
        snapshot = generate.case(__import__("random").Random(1000 + number), number)["snapshot"]
        snapshot["jobs"] = snapshot["jobs"][:6]
        numbers = [job["workOrderNumber"] for job in snapshot["jobs"]]
        fixed = [n for n, job in zip(numbers, snapshot["jobs"]) if job["fixed"]]
        rest = [n for n in numbers if n not in fixed]
        best = min(cost.of(snapshot, fixed + list(order)).objective for order in itertools.permutations(rest))
        answer = solver.solve(snapshot, hint=numbers, time_limit=20)
        assert answer.status == "OPTIMAL"
        assert cost.of(snapshot, answer.order).objective == best == answer.objective


def test_the_solver_is_never_worse_than_the_plan_or_the_current_rule_and_keeps_running_jobs_first():
    snapshots = {case["id"]: case["snapshot"] for case in json.loads((FIXTURES / "snapshots.json").read_text())["cases"]}
    baselines = json.loads((FIXTURES / "baselines.json").read_text())["cases"][:6]
    for entry in baselines:
        snapshot = snapshots[entry["id"]]
        plan = cost.of(snapshot, entry["plan"]["order"]).objective
        rule = cost.of(snapshot, entry["currentRule"]["order"]).objective
        hint = entry["plan"]["order"] if plan <= rule else entry["currentRule"]["order"]
        answer = solver.solve(snapshot, hint=hint, time_limit=5)
        found = cost.of(snapshot, answer.order)
        assert found.objective <= min(plan, rule)
        assert found.objective == answer.objective
        fixed = [job["workOrderNumber"] for job in snapshot["jobs"] if job["fixed"]]
        assert answer.order[:len(fixed)] == fixed


def test_saved_api_answers_are_evaluated_with_their_own_baselines(tmp_path):
    from sequence_optimizer import evaluate
    case = json.loads((FIXTURES / "snapshots.json").read_text())["cases"][2]
    entry = json.loads((FIXTURES / "baselines.json").read_text())["cases"][2]
    snapshot = dict(case["snapshot"])
    for name in ("plan", "currentRule"):
        snapshot[name] = {key: entry[name][key] for key in ("order", "changeoverMinutes", "lateMinutes", "objective")}
    saved = tmp_path / "press.json"
    saved.write_text(json.dumps({"success": True, "data": snapshot}))
    result = evaluate.evaluate(5, evaluate.snapshot_cases([saved]))
    assert result["cases"][0]["id"] == "press"
    assert result["worse"] == []
    snapshot["plan"]["objective"] = "1"
    saved.write_text(json.dumps(snapshot))
    try:
        evaluate.snapshot_cases([saved])
    except AssertionError:
        return
    raise AssertionError("a snapshot whose cost is not the mirror's was accepted")
