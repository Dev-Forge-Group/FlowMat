"""Compare the solver with the plan and the current rule on every fixture case (ADR-039 decisions 3 and 4).

Writes results/evaluation.md (the table) and results/evaluation.json (the same numbers). Today's best is, case by case,
the cheaper of the plan and the current rule (the load board's Q3, which looks at changeovers only and can make an order
much later). The rule for putting the solver into FlowMat: over the whole set its cost is at least 5% below today's best,
and no case is worse than it.

    python -m sequence_optimizer.evaluate [--time-limit 10]
"""
from __future__ import annotations

import argparse
import json
from decimal import Decimal
from pathlib import Path

from . import cost, solver

ROOT = Path(__file__).resolve().parent.parent
THRESHOLD = Decimal("0.05")


def evaluate(time_limit: float) -> dict:
    snapshots = {case["id"]: case for case in json.loads((ROOT / "fixtures" / "snapshots.json").read_text())["cases"]}
    baselines = json.loads((ROOT / "fixtures" / "baselines.json").read_text())["cases"]
    rows = []
    for entry in baselines:
        case = snapshots[entry["id"]]
        snapshot = case["snapshot"]
        plan = Decimal(entry["plan"]["objective"])
        rule = Decimal(entry["currentRule"]["objective"])
        best_name = "plan" if plan <= rule else "currentRule"
        best = min(plan, rule)
        answer = solver.solve(snapshot, hint=entry[best_name]["order"], time_limit=time_limit)
        found = cost.of(snapshot, answer.order)
        if found.objective != answer.objective:
            raise AssertionError(f"{entry['id']}: the model's objective {answer.objective} is not the cost {found.objective}")
        rows.append({
            "id": entry["id"], "calendar": case["calendar"], "jobs": len(snapshot["jobs"]),
            "items": len(snapshot["changeovers"]), "plan": str(plan), "currentRule": str(rule), "best": str(best),
            "bestIs": best_name, "planChangeoverMinutes": entry["plan"]["changeoverMinutes"], "planLateMinutes": entry["plan"]["lateMinutes"],
            "solver": str(found.objective), "solverChangeoverMinutes": found.changeover_minutes,
            "solverLateMinutes": found.late_minutes, "ruleChangeoverMinutes": entry["currentRule"]["changeoverMinutes"],
            "ruleLateMinutes": entry["currentRule"]["lateMinutes"], "status": answer.status,
            "seconds": round(answer.seconds, 2), "worse": found.objective > best,
        })
    total_rule = sum(Decimal(row["currentRule"]) for row in rows)
    total_solver = sum(Decimal(row["solver"]) for row in rows)
    total_plan = sum(Decimal(row["plan"]) for row in rows)
    total_best = sum(Decimal(row["best"]) for row in rows)
    gain = (total_best - total_solver) / total_best if total_best else Decimal(0)
    worse = [row["id"] for row in rows if row["worse"]]
    return {"timeLimitSeconds": time_limit, "cases": rows, "totals": {"plan": str(total_plan), "currentRule": str(total_rule),
            "best": str(total_best), "solver": str(total_solver), "gainOverBest": str(round(gain, 4))},
            "worse": worse, "meetsTheRule": gain >= THRESHOLD and not worse}


def percent(before: Decimal, after: Decimal) -> str:
    return "—" if before == 0 else f"{(before - after) / before * 100:.1f}%"


def markdown(result: dict) -> str:
    totals = result["totals"]
    lines = [
        "# 순서 풀이기 평가",
        "",
        "`python -m sequence_optimizer.evaluate`가 쓴다. 고정 입력은 `fixtures/snapshots.json`(시드 고정), 계획과 지금 규칙(부하표 Q3)의 "
        "비용은 백엔드가 쓴 `fixtures/baselines.json`이다. 비용 = 전환 분 + 늦어진 분 × 우선순위 가중치(urgent 4, high 2, normal 1, low 0.5). "
        f"풀이기는 OR-Tools CP-SAT, 한 사례 {result['timeLimitSeconds']:g}초 제한, 지금 규칙의 순서를 시작 해로 준다. 시간 열은 기계마다 다르다.",
        "",
        "## 합계",
        "",
        "| | 비용 합 | 오늘의 최선 대비 |",
        "|---|---:|---:|",
        f"| 계획 순서 | {totals['plan']} | |",
        f"| 지금 규칙(Q3) | {totals['currentRule']} | |",
        f"| 오늘의 최선(사례마다 계획과 Q3 중 싼 것) | {totals['best']} | |",
        f"| 풀이기 | {totals['solver']} | {percent(Decimal(totals['best']), Decimal(totals['solver']))} 적음 |",
        "",
        "Q3는 전환 시간만 보고 계획 끝을 보지 않아, 이 비용으로는 계획 순서보다 크게 나쁠 수 있다. 그래서 풀이기는 Q3가 아니라 "
        "사례마다 계획과 Q3 중 싼 쪽(오늘의 최선)과 비교하고, 그 순서를 시작 해로 준다.",
        "",
        f"넣는 기준(ADR-039 결정 4: 오늘의 최선보다 합이 5% 이상 적고, 나빠진 사례 없음): **{'채움' if result['meetsTheRule'] else '못 채움'}**. "
        f"나빠진 사례: {', '.join(result['worse']) if result['worse'] else '없음'}.",
        "",
        "## 사례별",
        "",
        "| 사례 | 달력 | 작업 | 품목 | 계획 (전환/지연 분) | Q3 (전환/지연 분) | 풀이기 (전환/지연 분) | 최선 대비 | 상태 | 초 |",
        "|---|---|---:|---:|---:|---:|---:|---:|---|---:|",
    ]
    for row in result["cases"]:
        lines.append(
            f"| {row['id']} | {row['calendar']} | {row['jobs']} | {row['items']} | "
            f"{row['plan']} ({row['planChangeoverMinutes']}/{row['planLateMinutes']}) | "
            f"{row['currentRule']} ({row['ruleChangeoverMinutes']}/{row['ruleLateMinutes']}) | "
            f"{row['solver']} ({row['solverChangeoverMinutes']}/{row['solverLateMinutes']}) | "
            f"{percent(Decimal(row['best']), Decimal(row['solver']))} | {row['status']} | {row['seconds']} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--time-limit", type=float, default=solver.TIME_LIMIT_SECONDS)
    arguments = parser.parse_args()
    result = evaluate(arguments.time_limit)
    (ROOT / "results").mkdir(exist_ok=True)
    (ROOT / "results" / "evaluation.json").write_text(json.dumps(result, indent=1) + "\n")
    (ROOT / "results" / "evaluation.md").write_text(markdown(result))
    print(json.dumps(result["totals"]), "meets the rule:", result["meetsTheRule"], "worse:", result["worse"])


if __name__ == "__main__":
    main()
