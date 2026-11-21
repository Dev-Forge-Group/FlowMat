# sequence-optimizer

설비 하나의 한 주 작업지시를 어떤 순서로 돌릴지 고르는 풀이기와 그 평가다([ADR-039](../../docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md), FM-AI). 서버는 아직 이것을 쓰지 않는다. 제품에 넣는 것(3단계)은 아래 평가 기준을 채운 뒤에 따로 한다.

## 무엇을 줄이나

비용 = 전환 분 + 늦어진 분 × 우선순위 가중치(urgent 4, high 2, normal 1, low 0.5).

- 작업은 창 시작부터 차례로 돈다. 앞 품목에서의 전환을 먼저 하고, 그다음 생산을 한다. 둘 다 설비 달력의 가용 시간을 채운다.
- 늦어진 분은 끝이 계획 끝을 넘은 만큼이고, 분 단위로 올림한다. 작업지시에 납기 열이 없어서 계획 끝을 약속으로 본다.
- 진행 중인 작업은 맨 앞에 고정한다.

계산은 백엔드의 `SequenceCost`가 정본이다. `sequence_optimizer/cost.py`는 그 거울이다.

## 파일

| 파일 | 내용 | 누가 쓰나 |
|---|---|---|
| `fixtures/snapshots.json` | 고정 시드로 만든 40개 사례. `/equipment-load/sequence-snapshot` 응답과 같은 모양이고 기준값은 없다 | `python -m sequence_optimizer.generate` |
| `fixtures/baselines.json` | 각 사례의 계획 순서와 지금 규칙(부하표 Q3)의 순서, 비용, 작업마다의 끝과 늦어진 분 | 백엔드 `SequenceFixtureTest` |
| `results/evaluation.md`, `.json` | 사례마다 계획, Q3, 풀이기의 비용과 걸린 시간, 합계, 기준을 채웠는지 | `python -m sequence_optimizer.evaluate` |

- `cost.py`: 비용의 거울. `tests/test_cost.py`가 `baselines.json`과 초 단위까지 같은지 본다.
- `solver.py`: OR-Tools CP-SAT.
  - 일한 시간 축 위의 순회(circuit)로 순서를 고른다. 끝 시각은 지나간 쉬는 시간을 더해 시계 시각으로 바꾼 뒤 계획 끝과 비교한다. 모형의 목적 값이 거울의 비용과 같다.
  - 한 사례 10초 제한. 오늘의 최선(계획과 Q3 중 싼 것)의 순서를 시작 해로 준다.
- `tests/test_solver.py`: 작업 6개인 사례 8개에서 모든 순서를 다 본 최선과 같은지 본다. 묶음 앞 6개에서는 계획과 Q3보다 나쁘지 않은지, 진행 중 작업이 맨 앞인지 본다.

## 실행

```bash
python -m pip install -r requirements.txt
```

```bash
python -m pytest -q tests
```

```bash
python -m sequence_optimizer.evaluate
```

사례를 바꿨으면 묶음을 다시 만들고, 백엔드가 기준값을 다시 쓰게 한 뒤 평가를 다시 돌린다. 백엔드 명령은 저장소 루트의 `flowmat_backend`에서 돌린다.

```bash
python -m sequence_optimizer.generate
```

```bash
./gradlew test --tests '*SequenceFixtureTest' -Dsequence.fixtures.update=true
```

CI는 `.github/workflows/sequence-optimizer.yml`이다. `baselines.json`이 백엔드의 계산과 맞는지는 `backend.yml`의 `SequenceFixtureTest`가 본다.

## 결과 (2026-11-21)

[평가 표](results/evaluation.md). 합성 묶음 40개에서 합계는 다음과 같다.

- 계획 순서 49,095
- Q3 634,252.5
- 오늘의 최선 48,195
- 풀이기 33,352.5

풀이기는 오늘의 최선보다 30.8% 적고, 나빠진 사례가 없다. 그래서 ADR-039 결정 4의 기준을 채운다. 34개는 최적을 증명했고 6개(작업 16~20개)는 10초 제한에서 찾은 가장 나은 답이다.

Q3는 전환 시간만 줄이고 계획 끝을 보지 않는다. 그래서 이 비용으로는 계획 순서의 13배였다. 부하표의 전환 순서 제안을 그대로 따르면 많이 늦어질 수 있다는 뜻이다.

이 결과는 합성 사례에서 나왔다. 계획 끝은 계획 순서의 끝을 몇 시간 앞뒤로 옮겨 만들었고, 가중치는 정한 값이다. 실제 프로젝트의 스냅숏으로 같은 표를 다시 만들어 보는 것이 3단계 전의 일이다.
