# 계획 대 실제

상태: **구현(2026-11-23).** 확정한 계획([ADR-043](../architecture/adr/ADR-043-plan-backwards-from-a-target.md) 결정 7)과 그 작업지시의 실행을 비교한다. 읽기만 한다. **새 마이그레이션 없음.**

왜: 파일럿의 계획이 실제와 얼마나 다른지 첫 주부터 잰다([로드맵](../status/ROADMAP-2026-11-23.md) §5의 4번, §11 "계획이 실제와 다르다"). 차이의 원인(손실, 효율, 대기)은 계획 입력으로 되돌린다.

## 규칙

| # | 규칙 |
|---|---|
| PA1 | 계획은 확정할 때의 판(`revisionId`)과 목표로 다시 만든다. 필요량과 단계 시간은 판과 목표만으로 정해지고, 재고는 바꾸지 않는다. 목표는 포트 자체 단위로 본다(Plan 패널이 보내는 단위). 배타 게이트웨이의 선택은 확정에 남지 않으므로, 선택이 필요한 흐름은 계획을 다시 만들지 못할 수 있다. 그때는 `complete=false`와 이유를 낸다 |
| PA2 | 실제는 그 작업지시의 **끝난 실행**만 센다. 진행 중인 실행이 쓴 것은 빼고 `openRuns`로 알린다. 반만 한 실행이 절약처럼 보이지 않게 하려는 것이다. 취소된 실행은 세지 않는다 |
| PA3 | 비교할 계획은 만든 양에 맞춘다: `planned = plannedForTarget × output / target`. 1,000개 중 500개를 만들었으면 500개의 계획과 비교한다. 만든 양이 0이면 `planned`와 차이는 없다(null) |
| PA4 | 재료의 `used`는 취소하지 않은 투입 기록의 실제 수량을 품목 단위로 바꿔 더한다(실행 재료 사용량과 같은 기록, [재료비](material-cost.md)). 단위가 바뀌지 않는 기록이 있으면 그 줄의 `used`는 없고 `complete=false`다. 계획에 없던 품목을 썼으면 계획 0인 줄로 보인다(차이 %는 없음) |
| PA5 | 단계의 `actual`은 끝난 실행마다 그 공정 단계의 시작부터 끝까지를 분으로 더한다. `measured`는 더한 단계의 수다. 게이트웨이는 세지 않는다. 계획 분이 없는(시간 요건이 없는) 공정은 실제만 보인다 |
| PA6 | 차이는 `(실제 − 계획) / 계획 × 100`, 소수 한 자리다. 목표는 재료 ±5%, 시간 ±15%(로드맵 §5의 4번)이고 `within`이 그 안인지 말한다. 목표는 판정 2(2027-05-21)에서 고친다 |
| PA7 | 권한: 작업지시 프로젝트의 읽기 권한. 확정에서 나오지 않은 작업지시는 404다 |

## API

- `GET /workflows/{workflowId}/plan-confirmations`: 그 흐름의 확정 계획, 최신부터. `[{requestId, revisionId, targetPortId, targetConnectionId, targetQuantity, workOrderId, purchaseOrderIds, confirmedAt}]`.
- `GET /work-orders/{workOrderId}/plan-actuals`: `{target, targetUnit, output, finishedRuns, openRuns, materialTolerancePercent, timeTolerancePercent, materials[], steps[], complete, notes[]}`.
  - `materials[]`: `{itemId, itemName, unit, plannedForTarget, planned, used, variancePercent, within}`
  - `steps[]`: `{processId, processName, plannedForTarget, planned, actual, measured, variancePercent, within}`

## 화면

편집기의 Plan 패널 아래 **Confirmed plans**에 확정 계획이 보인다. **Plan vs actual**을 누르면 다음이 나온다.

- 만든 양과 목표
- 재료별 계획·사용·차이
- 단계별 계획 분·실제 분·차이

목표를 벗어난 줄은 빨갛게 "off"로 표시한다.

## 확인

- 통합 테스트 `ProductionPlanIntegrationTest.aConfirmedPlanIsComparedWithItsFinishedRuns`: 빵 10개 계획(밀가루 20 kg, 30분) 중 5개를 10.6 kg·16분에 만들면 재료 +6.0%(목표 밖), 시간 +6.7%(목표 안). 밖의 사람은 403, 손으로 만든 작업지시는 404.
- 모의 E2E `plan-panel.spec.ts`: 확정 뒤 목록과 비교 표.

## 하지 않은 것

- 차이의 원인 나누기(손실·효율·대기). 파일럿 자료가 쌓이면 정한다.
- 배타 게이트웨이 선택을 확정에 남기기(마이그레이션이 필요하다).
- 설비·인력 근무 시간 기준의 시간 비교. 지금은 단계 시작부터 끝까지의 벽시계 시간이다.
