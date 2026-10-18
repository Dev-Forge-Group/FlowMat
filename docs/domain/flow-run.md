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
- 단계: `step_created`, `step_scheduled`(연결 지연이 정하면 reason `connection_delay`, GR-50. 작업지시 설비의 근무 밖이라 미뤘으면 `readyAt`·`equipmentId`, F13), `step_started`, `step_completed`, `step_failed`, `step_skipped`, `step_retried`, `step_cancelled`
- 연결: `connection_filtered`(지나가지 않은 연결, reason `condition_false`·게이트웨이의 `exclusive_not_chosen`·`default_not_needed`·Split의 0인 몫 `split_empty`·손실로 도착이 0인 `loss_empty`), `gateway_routed`(게이트웨이가 고른 연결, reason `first_match`·`default`, Split은 `split`과 그 몫 `quantity`), `transfer_loss`(연결 손실: 보낸·손실률·손실·도착 수량·단위, GR-41), `join_arrived`(Join 단계에 갈래가 도착: 연결·출처 단계·도착 번호)

## 화면

Runs 화면의 Flow Runs 탭(`FlowRunsPanel`)

## 검증

- 통합 테스트: `FlowRunIntegrationTest`(생산 실행 연결 포함), `FlowRunStepIntegrationTest`, `FlowRunGraphIntegrationTest`, `FlowRunExecutionPolicyIntegrationTest`(2cp), `ExclusiveGatewayIntegrationTest`(2eb), `JoinGatewayIntegrationTest`(2ef), `SplitGatewayIntegrationTest`(2eh), `ConnectionLossIntegrationTest`(2ei), `ConnectionDelayIntegrationTest`(2ej), `ShiftAwareDelayIntegrationTest`(2et)
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
