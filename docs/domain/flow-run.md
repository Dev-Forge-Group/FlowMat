# Flow Run (범용 실행 기록)

상태: **구현(V23 2026-09-24 실행, V26 단계·시도·이벤트, V27 단계 취소, V39 그래프 실행, V40 출처 단계, V85 Exclusive 게이트웨이, V88 Join 게이트웨이, V89 Split 게이트웨이).** 2026-10-02에 코드에서 다시 정리한 현행 문서다. 설계 검토 근거는 [보고서 I](../archive/2026-09-handoff/reports/I-reference-architecture.md)(보관).

FlowRun을 장기적으로 도메인 독립적인 실행 Core로 쓴다. 지금 구조는 이를 지원할 기반을 갖췄지만, 비제조 흐름으로 검증될 때까지 범용 실행 능력은 목표로 취급한다([ADR-003](../architecture/adr/ADR-003-resource-port-contract.md) 결정 8·9).

## 모델

| 테이블 | 담는 것 |
|---|---|
| `flow_run` | 프로젝트, 워크플로, 발행 revision(필수), 실행 종류(`actual`·`simulation`·`test`·`dry_run`), 실행 방식(`manual`·`graph`), 상태, 입력·출력 payload, 시작·끝 시각, 요청자, 연결된 생산 실행(`production_run_id`, 있으면) |
| `flow_run_step` | 노드(공정) id, 순번, 상태, 예정 시각, 입력·출력 snapshot, 출처 연결·출처 단계(V40) |
| `flow_run_step_attempt` | 단계의 시도 번호와 상태 |
| `flow_run_event` | 이벤트 종류, payload, 행위자, 시각. 지우지 않는 원장 |
| `flow_run_step_source` | Join 단계의 도착: 출처 단계·연결·도착 번호·시각(V88, GR-21) |

## 상태

| 대상 | 흐름 |
|---|---|
| 실행 | running → finished / failed / cancelled |
| 단계 | planned → running → completed / failed / skipped / cancelled |
| 시도 | running → completed / failed |

실행이 failed·cancelled로 끝나면 진행 중이던 단계도 같이 닫힌다. 실패로 끝나면 running 단계는 failed, 나머지는 cancelled가 된다.

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| F1 | 시작에는 발행(published) revision이 필요하다([워크플로 발행 revision](workflow-revision.md) V5·V7) | 404 / 409 |
| F2 | 실행 종류는 넷 중 하나다. 종류에 따른 재고 영향은 생산 실행 쪽이 정한다([실행 모델](../architecture/execution-model.md) §7) | 400 `Unknown flow run type.` |
| F3 | 그래프 실행은 입력이 JSON 객체여야 하고, 진입 연결이 없는 노드마다 planned 단계를 만든다. 순환·잘못된 참조가 있는 revision은 거절한다 | 400 / 409 |
| F4 | 단계 결과는 외부가 보고한다. 단계를 스스로 실행하지 않는다. 예외: Exclusive·Join·Split 게이트웨이 단계는 서버가 지나간다(GR-17·GR-25·GR-33, [ADR-006](../architecture/adr/ADR-006-operation-types.md) 결정 4, [ADR-007](../architecture/adr/ADR-007-join-gateway.md) 결정 6, [ADR-008](../architecture/adr/ADR-008-quantity-split.md) 결정 8) | |
| F5 | planned 단계만 예정 시각을 바꿀 수 있고, 예정 시각 전에는 시작하지 않는다 | 409 |
| F6 | 그래프 단계 완료의 라우팅(조건·용량·포트 검증식·스키마·실패 정책)은 [그래프 실행 계약](process-port-connection-contract.md#2026-09-30-그래프-실행-계약-v39) GR-01~GR-12, Exclusive 게이트웨이는 [GR-13~GR-20](process-port-connection-contract.md#2026-10-17-exclusive-게이트웨이-adr-006-v85), Join 게이트웨이는 [GR-21~GR-28](process-port-connection-contract.md#2026-10-17-join-게이트웨이-adr-007-v88), Split 게이트웨이는 [GR-29~GR-36](process-port-connection-contract.md#2026-10-17-split-게이트웨이-adr-008-v89), 연결 손실률은 [GR-37~GR-44](process-port-connection-contract.md#2026-10-17-연결-손실률-adr-009-마이그레이션-없음), 연결 지연은 [GR-45~GR-52](process-port-connection-contract.md#2026-10-17-연결-지연-adr-010-마이그레이션-없음)을 따른다 | |
| F7 | 실행 마감은 모든 단계가 completed나 skipped일 때만 된다 | 409 `Every recorded step must complete or be skipped before the flow run can finish.` |
| F8 | 생산 실행과 연결된 Flow Run은 이 API로 마감·취소·실패 처리할 수 없다. 생산 실행 API가 `FlowRunCommand`로 다룬다([실행 모델](../architecture/execution-model.md) §6) | 409 `Linked production runs must be managed through the production run API.` |
| F9 | 끝난 실행은 다시 바꾸지 않는다 | 409 `Flow run has already ended.` |
| F10 | 생산 실행의 공정 단계(2026-10-17, [ADR-011](../architecture/adr/ADR-011-production-steps.md)): 발행 revision이 있는 생산 실행이 시작되면 연결 Flow Run이 그래프로 시작하고 뿌리 노드마다 단계를 둔다. revision이 그래프로 돌 수 없으면(공정 없음, 순환) 지금처럼 단계 없이 두고 `steps_unavailable` 이벤트를 남긴다. 단계는 생산 실행 API로만 시작, 완료, 실패한다. 연결 규칙은 그래프 실행과 같다 | 409 `Production-linked steps must be recorded through the production run API.` |
| F11 | 생산 실행을 마감할 때 진행 중 단계가 있으면 409다. 시작하지 않은 단계는 취소한다(`RUN_FINISHED`, `step_cancelled` reason `run_finished`). 단계가 `stop` 정책으로 실패해 Flow Run이 이미 끝났으면 생산 실행은 그대로 마감되고, Flow Run은 실패로 남는다 | 409 `Step #1 Mix is still running; complete or fail it before finishing the run.` |
| F12 | 생산 단계의 시간 제한(2026-10-18, 사용자 결정 "늦음 표시만", V92): 공정에 시간 제한(EP7)이 있으면 생산 단계도 시작할 때 기한(`timeoutAt`)을 갖는다. 기한이 지나면 단계를 실패시키지 않고 늦음으로만 표시한다(`overdueAt`, `step_overdue` 이벤트 한 번). 단계는 진행 중 그대로이고 완료할 수 있다. 사람이 하는 작업이 늦었다고 실행 경로가 바뀌지 않게 하려는 것이다 | 없음 |
| F13 | 근무 시간에 맞춘 연결 지연(2026-10-18, 2et, [ADR-015](../architecture/adr/ADR-015-shift-aware-delay.md), 사용자 결정 "근무 시간에 맞춘 지연"·"작업지시 설비 달력"): 생산 실행에 연결된 Flow Run에서 연결 지연이 끝난 시각이 작업지시 설비의 가용 시간 밖이면, 일 단계의 `scheduledAt`을 다음 가용 시간의 시작으로 미룬다. 작업지시나 설비가 없거나 그 날짜에 달력이 없으면 그대로다. 게이트웨이는 받은 시각을 그대로 넘긴다. 미룬 단계도 지연이 끝났고 지금 설비가 가용 시간이면(그날 교대로 둔 야근 포함) 시작할 수 있다 | 409 `Step cannot start before its scheduledAt.` |
| F14 | 인력 근무에도 맞춘 연결 지연(2026-10-18, 2eu, [ADR-016](../architecture/adr/ADR-016-crew-aware-delay.md), 사용자 지시 "지금 구현된 기능과 연관된것 부터"): F13의 시각을 작업지시 인력 중 **몫 100%이고 교대가 있는 사람이 모두 일하는** 때에도 맞춘다(계획 제안 WC24·WC39와 같은 사람). 설비가 없어도 그런 인력이 있으면 인력만으로 미룬다. 몫 100% 미만인 사람과 교대가 없는 사람은 시각을 정하지 않는다. 바로 시작은 지연이 끝났고 지금 설비와 그 인력이 모두 일할 때다(사람의 야근은 그날의 날짜별 교대) | 409 `Step cannot start before its scheduledAt.` |
| F15 | 기다리는 이유(2026-10-18, 2ew, 마이그레이션 없음): 생산 단계 응답(`GET /production-runs/{id}/steps`와 start·complete·fail)에 `held {readyAt, equipmentId, equipmentCode, crew[]}`. F13·F14로 미뤘을 때만 있다. 단계의 마지막 `step_scheduled`에 `readyAt`이 있으면 채우고, 없으면 null이다. 설비 코드는 같은 프로젝트의 설비에서 찾는다. 화면 Process steps는 아직 시작할 수 없는 계획 단계에 `· from <날짜 시각> (waits for PRESS, kim)` | |
| F16 | 정확한 소수(2026-10-18, 오류 담당 수정안을 리드가 반영, 마이그레이션 없음): 단계 보고의 `outputSnapshot`, 단계 만들기의 `inputSnapshot`, 저장된 단계·실행 JSON을 읽을 때 소수를 이진 부동소수점으로 바꾸지 않는다. 그래서 `999999999999999.9999` 같은 큰 소수가 조건(`quantity = 999999999999999.9999`), Join 합계, 받은 snapshot에서 그대로다. 전에는 double로 읽혀 `1.0E15`가 되어 조건이 맞지 않고 작은 도착분이 합계에서 사라졌다 | |
| F17 | 큰 지수 거절(2026-10-19, 오류 담당 보고를 리드가 반영): 정확한 소수(F16)로 읽은 snapshot에서 지수가 Jackson의 scale 한도(절댓값 100 000)를 넘는 수(`1e100000000` 같은 것)는 거절한다. 그대로 두면 조건·Join 합계·손실 계산이 몇 분씩 걸렸다. 단계 API와 생산 단계 API의 읽을 수 없는 본문은 500이 아니라 400이다. 생산 단계 완료(`POST /production-runs/{id}/steps/{stepId}/complete`)의 `outputSnapshot`도 이제 정확한 소수다 | 400 `inputSnapshot and outputSnapshot must be valid JSON with numbers within the safe conversion limits.` / `outputSnapshot must be valid JSON with numbers within the safe conversion limits.` |
| F18 | 근무 시간으로 세는 지연(2026-10-19, 2fa, V99, [ADR-022](../architecture/adr/ADR-022-working-time-delay.md)): 연결의 `delayBasis`가 `working`이면 F13·F14의 근무 시간을 지연만큼 쌓은 때가 예정 시각이다([연결 계약](process-port-connection-contract.md) GR-53–GR-58). 생산 실행이 아니거나 시간을 정할 설비·인력이 없으면 모든 시간으로 센다 | 409 `Step cannot start before its scheduledAt.` |

## API

| 요청 | 설명 |
|---|---|
| `POST /flow-runs` | 수동 실행 시작 |
| `POST /flow-runs/graph` | 그래프 실행 시작(F3) |
| `GET /flow-runs?workflowId=`, `GET /flow-runs/{flowRunId}` | 조회 |
| `POST /flow-runs/{flowRunId}/finish`·`/cancel`·`/fail` | 마감(F7)·취소(사유 필수)·실패 |
| `POST /flow-runs/{flowRunId}/steps`, `GET …/steps` | 수동 단계 추가, 목록 |
| `PUT …/steps/{stepId}/schedule` | 예정 시각(F5) |
| `POST …/steps/{stepId}/start`·`/complete`·`/fail`·`/retry` | 단계 결과 보고 |
| `POST …/steps/{stepId}/preview` | 완료 전 연결별 라우팅 미리보기(GR-10) |
| `GET …/steps/{stepId}/lineage`, `GET …/steps/{stepId}/attempts` | 출처 단계 체인(Join은 여러 출처, GR-28), 시도 목록 |
| `GET /flow-runs/{flowRunId}/events` | 이벤트 원장 |

## 이벤트

- 실행: `run_started`, `run_finished`, `run_cancelled`, `run_failed`
- 단계: `step_created`, `step_scheduled`(연결 지연이 정하면 reason `connection_delay`, GR-50. 작업지시 설비·인력의 근무 밖이라 미뤘으면 `readyAt`·`equipmentId`·`crew`, F13·F14), `step_started`, `step_completed`, `step_failed`, `step_skipped`, `step_retried`, `step_cancelled`
- 연결: `connection_filtered`(지나가지 않은 연결, reason `condition_false`·게이트웨이의 `exclusive_not_chosen`·`default_not_needed`·Split의 0인 몫 `split_empty`·손실로 도착이 0인 `loss_empty`), `gateway_routed`(게이트웨이가 고른 연결, reason `first_match`·`default`, Split은 `split`과 그 몫 `quantity`), `transfer_loss`(연결 손실: 보낸·손실률·손실·도착 수량·단위, GR-41), `join_arrived`(Join 단계에 갈래가 도착: 연결·출처 단계·도착 번호)

## 화면

Runs 화면의 Flow Runs 탭(`FlowRunsPanel`)

## 검증

- 통합 테스트: `FlowRunIntegrationTest`(생산 실행 연결 포함), `FlowRunStepIntegrationTest`, `FlowRunGraphIntegrationTest`, `FlowRunExecutionPolicyIntegrationTest`(2cp), `ExclusiveGatewayIntegrationTest`(2eb), `JoinGatewayIntegrationTest`(2ef), `SplitGatewayIntegrationTest`(2eh), `ConnectionLossIntegrationTest`(2ei), `ConnectionDelayIntegrationTest`(2ej), `ShiftAwareDelayIntegrationTest`(2et), `CrewAwareDelayIntegrationTest`(2eu)
- 2eu(2026-10-18, 격리 복사본) `CrewAwareDelayIntegrationTest` 2건: 설비 없는 작업지시에 kim(몫 100%, 매일 한 시간 교대, 지금부터 2–3시간 뒤) → Bake `scheduledAt`이 kim의 교대 시작, `step_scheduled`에 `readyAt`·`crew` [kim], `equipmentId` 없음 → 바로 시작 409 → 오늘 kim의 종일 날짜별 교대(야근)를 두면 시작. 종일 달력 프레스 + lee(100%, 한 시간 교대) + park(50%, 그다음 한 시간) → lee의 교대 시작, `crew` [lee]만, `equipmentId` 프레스. 교대 없는 choi(100%)만 있는 작업지시는 `scheduledAt` = 완료 + 지연, `readyAt` 없음. 지연·인력·생산 단계 관련 17건, 전체 백엔드 1,513건(256클래스) 통과(실패 0), 프런트 Vitest 677건·typecheck·빌드, 모의 e2e 185건 통과(실패·flaky 0, 실제 API용 38건 조건부 제외).
- 2eu 정정(2026-10-18): 2026-10-18 정정(오류 담당 보고): 처음 구현은 설비와 사람을 번갈아 옮기는 탐색을 200바퀴에서 멈춰, 엇갈리는 날이 이어지다 300일 뒤에야 함께 일하는 경우 `readyAt`으로 돌아갔다. 엇갈리는 1년을 끝까지 훑는 최악은 12초였다. 지금은 창마다 설비의 시작 가능 구간을 한 번 구하고 사람마다 근무 구간과 교차해 첫 시각을 쓴다(최악 0.2초). 오류 담당의 `LinkedProductionWorkingTimeRegressionIntegrationTest` 7건(판 경계, 인력 몫, 조기 시작 권한, 지연 자체의 하한)과 넘겨받은 `LinkedWorkingTimeLongHorizonRegressionIntegrationTest` 2건(150일·300일 뒤 첫 공동 근무)이 통과한다. 설비 달력·부하표·계획 제안·준비 점검 테스트도 같이 돌렸다(계산의 `Shifts`가 달력 있는 날의 교대와 달력 없는 날을 나눠 담게 바뀜). 전체 백엔드 1,544건(260클래스) 통과(실패 0, 오류 담당의 새 회귀 27건과 장기 탐색 2건 포함), 프런트 Vitest 677건·typecheck·빌드, 모의 e2e 185건 통과(실패·flaky 0, 실제 API용 38건 조건부 제외).
- 2ew(2026-10-18): `CrewAwareDelayIntegrationTest`가 생산 단계 응답의 `held`도 본다. 인력만이면 `readyAt`·`crew` [kim]·`equipmentId` null, 설비와 인력이면 `equipmentId`·`equipmentCode`(CREW-…)·`crew` [lee], 미루지 않은 단계는 null. `runStepsModel.test.ts`의 `stepWaitsFor`(설비 코드, 사람, 코드 없는 설비, 시작할 수 있으면 빈 값). 전체 백엔드 1,544건(260클래스) 통과(실패 0), 프런트 Vitest 678건·typecheck·빌드, 모의 e2e 184건 통과·1건 실패(bom-by-products, 단독 3회 통과, 2ew와 무관해 오류 담당에게 넘김)·실제 API용 38건 조건부 제외.
- F16(2026-10-18): `JoinGatewayAcceptanceIntegrationTest`에 오류 담당 수정안의 회귀 2건. 큰 소수가 조건을 맞추고 받는 단계의 `inputSnapshot`에 그대로 있음, 999999999999999.9998 + 0.0001의 Join 합계가 999999999999999.9999. 게이트웨이·지연·Flow Run·생산 단계·모듈 경계 114건(21클래스), 전체 백엔드 1,565건(262클래스, 실패 0) 통과(실패 0).
- F17(2026-10-19): 오류 담당의 `ExactSnapshotMagnitudeRegressionTest`(중첩 수치까지 scale 한도), `ExactSnapshotInputHttpRegressionTest`(단계·생산 단계 API의 400), `ProductionStepExactSnapshotRegressionIntegrationTest`(생산 단계 완료의 큰 수 `999999999999999.9999`와 `0.1234567890123456789`가 출력·저장·다음 단계 입력에서 그대로)를 원본에 넣었다. 전체 백엔드 1,643건(271클래스, 실패 0).
- F18(2026-10-19): `WorkingTimeDelayIntegrationTest` 3건([연결 계약](process-port-connection-contract.md) GR-58 아래 검증). 기존 `ShiftAwareDelayIntegrationTest`·`CrewAwareDelayIntegrationTest`와 오류 담당의 근무 시간 회귀 9건도 그대로 통과한다(`ProductionRunWorkingTime`이 창마다 구간을 한 번 구하는 부분을 `workingStart`와 `afterWorking`이 함께 쓰게 나눔). 전체 백엔드 1,647건(272클래스, 실패 0).
- 2et(2026-10-18, 격리 복사본) `ShiftAwareDelayIntegrationTest` 2건: 매일 한 시간 교대(지금부터 2–3시간 뒤)인 설비의 작업지시로 생산 실행 → Mix 완료(지연 0.01초) → Bake `scheduledAt`이 교대 시작, `step_scheduled`에 `readyAt`(완료 + 지연)·`equipmentId` → 바로 시작 409 → 오늘 날짜에 종일 날짜별 교대(야근)를 두면 시작. 종일 달력 설비와 설비 없는 작업지시는 `scheduledAt` = 완료 + 지연, `readyAt` 없음. 지연·Flow Run·생산 단계·설비 달력 관련 105건, 전체 백엔드 1,511건(255클래스) 통과(실패 0), 프런트 Vitest 676건·typecheck·빌드, 모의 e2e 185건 통과(실패·flaky 0, 실제 API용 38건 조건부 제외).
- 단위 테스트: `FlowRunServiceTest`, `FlowRunGraphTest`, `FlowRunGraphPolicyTest`(2cp), `LinkedFlowRunServiceTest`
- 비제조 흐름: `DataFlowRunIntegrationTest`(2026-10-03). 새 프로젝트에서 Item 없는 포트로 File → Transform → Data를 `actual`로 끝까지 돌리고, 제조 행(품목·재고·LOT·BOM·작업지시·생산 실행)이 생기지 않음을 확인한다

## 생산 실행의 공정 단계

2026-10-17 추가(2el, V91). 근거는 [ADR-011](../architecture/adr/ADR-011-production-steps.md)이다.

| API | 하는 일 |
|---|---|
| `GET /production-runs/{id}/steps` | 단계 목록. 단계마다 공정 이름(`nodeName`), 게이트웨이 여부, 입력과 출력, 그 공정의 필수 지침 단계 수와 확인한 수가 있다. 단계가 없는 실행은 빈 목록이다 |
| `POST …/steps/{stepId}/start` | 시작. 생산 실행이 열려 있어야 한다 |
| `POST …/steps/{stepId}/complete` | 완료. 본문에 `outputSnapshot`이 없으면, 그 공정에 기록된 산출(취소된 것 빼고)이 한 품목, 한 단위일 때 그 합으로 채운다. 아니면 빈 출력이다 |
| `POST …/steps/{stepId}/fail` | `{errorCode, errorMessage}`. 연결의 실패 정책이 그래프 실행처럼 적용된다 |

- 단계는 재고를 움직이지 않는다. 재고와 LOT 계보는 지금처럼 투입·산출 기록에서만 생긴다.
- 끝난 생산 실행의 단계를 바꾸려 하면 409 `Run … is finished; its process steps can no longer change.`, 단계가 없는 실행이면 409 `This run has no process steps: …`이다.
- 공정에 시간 제한이 있으면 기한이 지난 단계는 늦음으로만 표시한다(F12). 화면에는 기한 전에 `due by 14:05`, 지나면 `late since 14:05`가 보인다.
- 화면: 실행 상세의 **Process steps**에 단계마다 상태, `made 98 kg` 또는 `gets 98 kg`, `0 of 1 instruction steps confirmed`, **Start**와 **Complete**가 보인다.

검증(로컬): `ProductionRunStepsIntegrationTest` 4건.

- Mix → Bake 실행에서 Mix에 98 kg을 기록하고 완료하면 출력이 98 kg이 되고, Bake가 98 kg을 받는다.
- Bake의 지침 단계를 확인하기 전에는 Bake를 완료할 수 없고, 확인하면 완료된다. 지침의 새 revision도 공정을 그대로 갖는다.
- 진행 중 단계가 있으면 마감이 409이고, 시작 전 단계는 마감 때 취소된다. `stop`으로 실패한 실행도 마감된다.
- 공정이 없는 revision은 단계 없이 시작하고 `steps_unavailable`을 남긴다.
- 시간 제한 60초인 Mix가 기한을 넘기면, 감시 작업을 두 번 돌려도 `step_overdue`는 한 번이다. 단계는 진행 중이고 Flow Run도 진행 중이며, 그 뒤 완료된다.

관련 백엔드 176건, 전체 백엔드 1,460건 실패 0. 프런트 Vitest(`runStepsModel.test.ts`, 지침 단계 폼)와 모의 E2E `run-steps.spec.ts`, `instruction-process.spec.ts`도 통과했다.

## 아직 없는 것

시간 제한·재시도 횟수와 간격·동시 실행 제한은 [ADR-004](../architecture/adr/ADR-004-flow-run-execution-policy.md)(Accepted 2026-10-05)대로 [노드 실행 정책](flow-run-execution-policy.md)에 구현했다(2026-10-10, §2 2cp, V67, 로컬 검증만). 다음은 아직 없다.

- 노드 자동 실행기(서버는 단계를 시작하지 않는다)
- 정책 편집 화면, 수동 실행·생산 연결 실행의 정책, 제한 초과 대기열
- ~~여러 진입 연결의 합류~~ → Join 게이트웨이(2ef, [ADR-007](../architecture/adr/ADR-007-join-gateway.md), GR-21–GR-28)
- ~~수량 분할~~ → Split 게이트웨이(2eh, [ADR-008](../architecture/adr/ADR-008-quantity-split.md), GR-29–GR-36). 연결 손실률은 2ei([ADR-009](../architecture/adr/ADR-009-connection-loss-rate.md), GR-37–GR-44). 연결 지연은 2ej([ADR-010](../architecture/adr/ADR-010-connection-delay.md), GR-45–GR-52). 개수 단위의 정수 나눔·흐름률은 아직
