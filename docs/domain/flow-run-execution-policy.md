# Flow Run 노드 실행 정책 (시간 제한·재시도 간격·동시 실행 제한)

상태: **구현(2026-10-10, 세션 1 리드, §2 2cp) — 로컬 격리 검증만, 실제 API 브라우저 미검증.** 결정: [ADR-004](../architecture/adr/ADR-004-flow-run-execution-policy.md)(Accepted 2026-10-05)와 [DECISIONS-2026-10-05](../status/DECISIONS-2026-10-05.md) §8. 새 마이그레이션 **V67**(2026-10-16 개발 DB 적용). 이 문서는 ADR의 결정을 코드 규칙으로 옮긴 것이며 ADR이 정하지 않은 것(노드 실행기, 대기열, 에디터 편집 화면 등)은 하지 않는다.

## 바뀌지 않는 것

- 정책을 정하지 않은 노드와 이미 발행된 revision은 지금과 똑같이 돈다: 연결 정책 `retry`면 즉시 최대 3회 재시도, 시간 제한·동시 실행 제한 없음(ADR 결정 2).
- 서버는 단계를 대신 시작하지 않는다. 외부 실행자(사람·스크립트·CI)가 `start`·`complete`·`fail`을 보고한다(결정 3).
- 수동 실행(`execution_mode = manual`)과 생산 실행에 연결된 Flow Run에는 정책을 적용하지 않는다(결정 6).
- 실패 후 갈 길(stop·skip·retry)은 연결의 `failure_policy`가 정한다. 노드 정책은 "몇 번·얼마 뒤에·얼마나 오래·동시에 몇 개"만 정한다(결정 1).

## 규칙

| # | 규칙 |
|---|---|
| EP1 | **저장:** `process`에 열 6개(`timeout_seconds` 1–604 800, `retry_limit` 0–10, `retry_delay_seconds` 0–86 400, `retry_backoff` `fixed`/`exponential`, `max_retry_delay_seconds` exponential에서만·`retry_delay_seconds` 이상·604 800 이하, `concurrency_limit` 1–1 000)와 CHECK 제약, 낙관적 잠금용 `execution_policy_version`. 모두 NULL이 기본이며 NULL은 기본 동작(시간 제한 없음, 재시도 3회, 간격 0, fixed, 상한 없음, 동시 제한 없음)이다 |
| EP2 | **API:** `GET /processes/{processId}/execution-policy`(워크플로 읽기)와 `PUT …`(워크플로 쓰기, `expectedVersion` 필수). 저장은 노드 행 잠금 아래에서 하고, 다른 version이면 409(직전 version에 같은 값을 다시 보내면 응답 유실 재시도로 보고 지금 값을 돌려준다). 범위 밖 값은 400. 모든 값을 비우면 기본 동작으로 돌아간다. 에디터 편집 화면은 EP13(2026-10-11 사용자 결정) |
| EP3 | **발행 snapshot:** 발행 때 기본값이 아닌 정책이 있는 노드만 `nodePolicies`(processId + 6개 값)로 revision snapshot에 고정한다. 실행은 자기 revision의 snapshot만 읽는다. 정책을 바꾸면 새 revision을 발행해야 새 실행에 적용된다. `nodePolicies`가 없는 옛 snapshot은 기본 동작 |
| EP4 | **재시도 간격:** 그래프 실행에서 단계 시도 n이 실패하고 연결 정책이 `retry`이며 n ≤ `retryLimit`이면 재시도한다. n번째 재시도의 간격은 fixed면 `retryDelaySeconds`, exponential이면 `retryDelaySeconds × 2^(n−1)`을 `maxRetryDelaySeconds`(있으면)로 자른 값. 간격이 0이면 지금처럼 즉시 다음 시도(`step_retried`). 간격이 있으면 실패한 시도에 `retry_at = 실패 시각 + 간격`을 쓰고 단계를 `planned`로 되돌리며 `scheduled_at = retry_at`, 이벤트 `step_retry_scheduled {attemptNo, retryAt}`. 마지막 실패 코드·메시지는 단계에 남아 "재시도 대기"를 보여 준다. n > `retryLimit`이면 지금처럼 실행 실패 |
| EP5 | **재시도 대기의 시작:** 외부 실행자는 `retry_at` 이후 `start`로 다음 시도를 연다. 그 전 `start`는 409(`scheduledAt` 규칙). 재시도 대기 단계의 예정 시각을 `retry_at`보다 앞으로 당기는 `schedule`도 409 |
| EP6 | **동시 실행 제한:** 그래프 실행의 `start`(재시도 대기 포함)와 즉시 재시도는 같은 revision·같은 노드의 잠금(트랜잭션 advisory lock, revision+node 키) 안에서 그 revision의 진행 중 그래프 실행들에 있는 같은 노드의 running 단계 수를 센다. `concurrencyLimit` 이상이면 `start`는 409 `Node … already has N running steps (limit L).`이고 단계는 planned로 남는다. 즉시 재시도가 제한에 걸리면 시작하지 않고 planned로 돌린다(`scheduled_at` 없음, 이벤트 `step_retry_scheduled`에 `reason: concurrency_limit`). 다른 revision은 같은 노드여도 따로 센다. 물리 설비 capacity와 섞지 않는다 |
| EP7 | **시간 제한:** 그래프 실행에서 `timeoutSeconds`가 있는 노드의 시도가 시작되면 `flow_run_step_attempt.timeout_at = 시작 + timeoutSeconds`를 기록한다. 주기 작업(기본 15초, `app.flow-run.timeout-sweep-interval`)이 기한이 지난 running 시도를 찾고, 시도마다 별도 트랜잭션에서 실행 행을 잠근 뒤 아직 running이고 기한이 지났는지 **다시 확인**하고 나서만 `step_timed_out` 다음 `step_failed`(오류 코드 `TIMEOUT`, actor `system`)로 기록한다. 그다음은 외부가 실패를 보고한 것과 같다(EP4, stop이면 실행 실패도 system). 감시 작업이 둘 돌아도 한 번만 처리된다. 생산에 연결된 실행은 실패시키지 않고 늦음으로만 표시한다([Flow Run](flow-run.md) F12, 2026-10-18) |
| EP8 | **늦은 보고:** 시간 제한·재시도로 시도가 끝난 뒤의 `complete`·`fail`은 단계가 running이 아니면 409. 즉시 재시도로 새 시도가 열린 뒤 옛 실행자의 보고를 막도록 `complete`·`fail` 요청은 선택 필드 `attemptNo`를 받는다. 주면 지금 running 시도 번호와 달라도 409 `Attempt n is no longer running.` 안 주면 지금처럼 최신 running 시도 |
| EP9 | **기록:** 새 이벤트 종류 `step_retry_scheduled`·`step_timed_out`. 시도 목록의 `retryAt`이 채워진다. 이벤트 원장은 지우지 않는다 |
| EP10 | **화면:** 실행 화면(Runs의 Flow runs)은 재시도 대기 단계(planned + 마지막 실패 코드)에 "waiting to retry"를 보이고, 기존 Start·예정 시각 표시를 그대로 쓴다. 정책 편집은 에디터의 노드 인스펙터(EP13) |
| EP11 | **경계(ADR-002 Stage B):** 고치는 `FlowRunService`·`FlowRunStepService`는 workflow 저장소 대신 workflow 공개 Query(`WorkflowProductionQuery.findRevision`)로 revision snapshot을 읽는다 |
| EP13 | **편집 화면**(2026-10-11, 사용자 결정 "NodeInspector에 만든다", 프런트만): 워크플로 에디터에서 노드를 고르면 인스펙터 아래 **Execution policy**에 한 줄 요약(기본은 `No time limit · 3 retries at once · no concurrency limit`, 예 `Times out after 10 min · 3 retries, 30 s apart, doubling up to 5 min · at most 2 at once`). **Edit policy** → Time limit (s)·Retries·Retry delay (s)·Delay growth(기본/Same each time/Doubling)·Delay cap (s)(Doubling일 때만)·At most at once, 빈 칸은 기본. 보내기 전에 EP1과 같은 범위를 검사하고(예 `The time limit is a whole number between 1 second and 7 days (604800 s).`), 불러온 version으로 PUT(EP2). **Use defaults**는 모두 비워 저장. 저장 뒤 `Saved. New runs follow it once a revision is published.`(EP3). 서버가 거절하면(409 version·403 viewer) 메시지를 보이고 정책을 다시 읽는다 |
| EP12 | 개발 DB(5434)에는 V67을 적용하지 않는다. Testcontainers와 격리 복사본으로만 검증한다 |

## 검증 결과 (2026-10-10, 격리 복사본)

- `FlowRunExecutionPolicyIntegrationTest` 7건: 정책 저장 400·version·응답 유실 재전송·발행 고정 / 지연 재시도(planned·retryAt·이른 start 409·schedule 409·시각 뒤 2번째 시도·exponential 상한 15초) / retryLimit 0 / 시간 제한 감시 2개 동시 실행에도 `step_timed_out` 1건·system actor·즉시 재시도·늦은 attemptNo 1 보고 409 / stop 연결의 시간 제한은 실행 실패(system) / 동시 실행 제한 1(다른 revision은 별도) / 동시 start 2개 중 하나만 200.
- `FlowRunGraphPolicyTest` 3건(간격 계산·옛 snapshot 기본값·잘못된 snapshot 정책 409), 기존 `FlowRunGraphIntegrationTest`·`DataFlowRunIntegrationTest`·`FlowRunServiceTest`(공개 Query로 교체) 그대로 통과.
- 화면: `e2e/flow-run-retry-wait.spec.ts`(waiting to retry·Retry scheduled·Times out), 기존 flow-runs 모의 2건.
- 편집 화면(2026-10-11, EP13): `executionPolicyModel.test.ts` 2건(요약 문장·시간 표기, 서버와 같은 검사·요청 본문). 모의 E2E `e2e/execution-policy-editor.spec.ts`(노드 선택 → 기본 요약 → 0초 거절·보내지 않음 → 600·30·Doubling·300·2 저장 본문(`expectedVersion` 0)과 새 요약·발행 안내 → Use defaults 본문(`expectedVersion` 1)).
- 전체 백엔드 격리 실행 **1,253건 실패·오류 0**(2cp–2cs 포함), 프런트 단위 615·타입·lint, 전체 모의 E2E 129 통과·19 의도적 제외. ArchUnit 동결 저장소 54→48(2cp의 flowrun 2건 + 이미 고쳐진 InventoryServiceImpl·WorkOrderServiceImpl 4건 자동 축소 확인).
- 처음 실패 1건: 실패 응답의 `scheduledAt`이 나노초, DB `retryAt`은 마이크로초라 달랐다 → 실패 시각을 마이크로초로 자름.

## 검증 계획 (ADR "검증" 그대로)

- 정책 없는 그래프: 기존 `FlowRunGraphIntegrationTest`가 바뀌지 않고 통과(즉시 3회 재시도, stop·skip).
- `retryDelaySeconds 60`: 실패 → planned, `scheduledAt` = 시도의 `retryAt`, `step_retry_scheduled` → 그 전 `start` 409 → 시각 뒤 `start`로 2번째 시도. exponential 상한 계산 단위 테스트.
- `retryLimit 0`: 첫 실패에 연결 정책이 retry여도 실행 실패.
- 시간 제한: 기한 지난 시도가 TIMEOUT으로 실패하고 연결 정책 적용. 감시 작업 둘을 동시에 돌려도 실패 이벤트 하나. 늦은 `complete` 409.
- 동시 실행 제한 1: 같은 revision·node 두 실행 중 두 번째 start 409, 끝난 뒤 시작 가능. 동시 요청 중 하나만 성공. 다른 revision은 별도 한도이며 각 snapshot 정책 사용.
- 범위 밖 값 400, 생산 연결 실행에는 정책 미적용.
