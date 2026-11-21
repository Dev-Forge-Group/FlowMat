"""The Python cost is the backend's: every baseline in fixtures/baselines.json, which SequenceFixtureTest writes."""
import json
from decimal import Decimal
from pathlib import Path

from sequence_optimizer import cost

FIXTURES = Path(__file__).resolve().parent.parent / "fixtures"


def cases():
    snapshots = {case["id"]: case["snapshot"] for case in json.loads((FIXTURES / "snapshots.json").read_text())["cases"]}
    baselines = json.loads((FIXTURES / "baselines.json").read_text())["cases"]
    assert len(baselines) == len(snapshots)
    return [(snapshots[entry["id"]], entry) for entry in baselines]


def test_the_mirror_gives_the_backends_cost_for_the_plan_and_the_current_rule():
    for snapshot, entry in cases():
        for name in ("plan", "currentRule"):
            expected = entry[name]
            found = cost.of(snapshot, expected["order"])
            assert found.changeover_minutes == expected["changeoverMinutes"], (entry["id"], name)
            assert found.late_minutes == expected["lateMinutes"], (entry["id"], name)
            assert found.weighted_late_minutes == Decimal(expected["weightedLateMinutes"]), (entry["id"], name)
            assert found.objective == (None if expected["objective"] is None else Decimal(expected["objective"])), (entry["id"], name)
            assert found.unplaced == expected["unplaced"]
            ends = [None if p.end is None else p.end.astimezone(tz=None).isoformat() for p in found.placements]
            assert [None if p.end is None else int(p.end.timestamp()) for p in found.placements] == \
                [None if end is None else int(cost.parse_time(end).timestamp()) for end in expected["ends"]], (entry["id"], name, ends)
            assert [p.late_minutes for p in found.placements] == expected["late"], (entry["id"], name)


def test_an_order_names_every_job_once_with_the_running_ones_first():
    snapshot, _ = cases()[0]
    numbers = [job["workOrderNumber"] for job in snapshot["jobs"]]
    for wrong in (numbers[:-1], numbers + numbers[:1]):
        try:
            cost.of(snapshot, wrong)
        except ValueError:
            continue
        raise AssertionError("accepted " + repr(wrong))
