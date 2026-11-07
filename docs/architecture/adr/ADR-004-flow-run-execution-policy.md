# ADR-004: Flow Run 실행 정책 (시간 제한·재시도 간격·동시 실행 제한)

작성일: 2026-10-03

## 상태

**Accepted (2026-10-05).** 사용자 수정 승인. 동시성 범위는 workflowRevision + node이며 alert-only·서버 자동 실행·대기열은 도입하지 않는다. [최종 결정](../../status/DECISIONS-2026-10-05.md) §8. 구현 완료를 뜻하지 않는다.

상태 이력:
- 2026-10-03 작성: Proposed.
- 2026-10-05 사용자 승인: Accepted, concurrency scope를 workflowRevision + node로 수정.

근거: [벤치마크](../../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-RUN-005, BPM-07 Conductor(`TaskDef`의 `retryCount`·`retryLogic`·`retryDelaySeconds`·`timeoutSeconds`·`timeoutPolicy`·`concurrentExecLimit`), BPM-08 Temporal(`retry_at`, 분산 런타임은 들이지 않음). [ADR-003](ADR-003-resource-port-contract.md) 결정 9-(b)("노드 실행기는 별도 ADR에서 FM-RUN-005와 함께"), [결정 인계](../decision-handoff.md) §3 보류 행, 2026-09-24 인계의 D7.

## 수용 기준 (2026-10-05 사용자 확정)

- [x] 실행 정책은 노드, 실패 정책은 연결.
- [x] TIMEOUT을 failed로 기록하고 연결 실패 정책 적용. alert-only는 첫 버전에서 제외.
- [x] 지연 재시도는 planned + scheduledAt. 시각 이후 외부 executor가 start, 서버가 자동 시작하지 않음.
- [x] 동시성은 workflowRevision + node. 초과 start는 409, 대기열 없음.
- [x] 외부 보고 모델에 정책을 먼저 적용. 노드 실행기는 별도 결정.

## 배경 (2026-10-03 코드 기준)

- Flow Run은 노드를 스스로 실행하지 않는다. 외부(사용자·API 호출자)가 단계를 `start`·`complete`·`fail`·`retry`로 보고한다([실행 모델](../execution-model.md) §5).
- 실패 정책은 **연결**에 있다. `process_connection.failure_policy`(V25, CHECK `stop`·`skip`·`retry`, 기본 `stop`). 그래프 실행에서 단계가 실패하면 그 단계를 만든 진입 연결의 정책을 따른다(포트·연결 계약 GR-05).
- `retry`는 [`FlowRunStepService`](../../../flowmat_backend/modules/flowrun/src/main/java/org/myweb/flowmat/domain/flowrun/application/FlowRunStepService.java)의 상수 `MAX_GRAPH_RETRIES = 3`으로, 처음 시도 뒤 **즉시** 최대 3회 새 시도(`running`)를 연다. 간격도 횟수 설정도 없다. 수동 실행은 사용자가 `POST …/retry`로 직접 다시 시도한다.
- 단계의 `scheduled_at`(V26): 그 시각 전에는 `start`가 409 `Step cannot start before its scheduledAt.`
- 시도의 `retry_at`(V26): 열은 있고 응답에도 나오지만 **아무 코드도 쓰지 않는다**(항상 null).
- 시간 제한이 없다. 외부가 보고하지 않으면 단계는 계속 `running`이다.
- 동시성: 실행 행의 비관적 잠금이 **한 실행 안의** 상태 전이와 순번 할당을 직렬화한다. 실행이나 노드별 running 단계 수 제한은 없다.
- 스케줄러: `@EnableScheduling`이 켜져 있고 재고 경보 배치·워크플로 접속 정리가 `@Scheduled`를 쓴다. 재고 경보는 행마다 잠금 아래에서 다시 확인하므로 인스턴스가 여럿이어도 한 번만 처리된다. Flow Run에는 스케줄러가 없다.
- 이벤트 기록기는 사용자 없이 기록하면 `actor_type = system`으로 남긴다(V26 CHECK `user`·`system`).

## 결정

1. **실행 정책은 노드(Process)에, 실패 후 갈 길은 연결에 둔다.**
   - 시간 제한·재시도 횟수·간격·동시 실행 제한은 "이 노드를 실행하는 방법"이라 노드 정의에 둔다(Conductor `TaskDef`와 같은 자리).
   - `failure_policy`(stop·skip·retry)는 지금처럼 연결에 남긴다. "재시도할지"는 연결이, "몇 번·얼마 뒤에"는 노드가 정한다.
   - 정책은 발행 revision snapshot에 들어가 실행 중에는 바뀌지 않는다(ADR-003 결정 5와 같은 원칙). 바꾸려면 새 revision을 발행한다.

2. **기본값은 지금 동작과 같다.** 정책을 정하지 않은 노드와 이미 발행된 revision은 지금과 똑같이 돈다.

   | 설정 | 뜻 | 기본값 | 범위(제안) |
   |---|---|---|---|
   | `timeoutSeconds` | 시도 하나가 `running`으로 있을 수 있는 최대 시간 | 없음(제한 없음) | 1 ~ 604 800(7일) |
   | `retryLimit` | 처음 시도 뒤 최대 재시도 횟수(연결 정책이 `retry`일 때) | 3 | 0 ~ 10 |
   | `retryDelaySeconds` | 첫 재시도까지 기다리는 시간 | 0(즉시) | 0 ~ 86 400 |
   | `retryBackoff` | `fixed`(매번 같은 간격) 또는 `exponential`(n번째 재시도는 간격 × 2^(n−1)) | `fixed` | |
   | `maxRetryDelaySeconds` | `exponential`의 상한 | 없음 | `retryDelaySeconds` 이상 |
   | `concurrencyLimit` | 같은 workflowRevision의 모든 실행에서 이 노드가 동시에 `running`일 수 있는 단계 수 | 없음(제한 없음) | 1 ~ 1 000 |

   - 저장은 노드 열 + CHECK 제약으로 한다(연결의 `failure_policy`와 같은 방식). 마이그레이션 번호는 Accepted 뒤의 빈 번호다.
   - 범위 밖 값은 노드 저장 시 400으로 막는다. 이미 저장된 그래프를 깨지 않도록 새 열은 모두 null 또는 기본값을 가진다.

3. **재시도 간격은 외부 보고 모델 위에서 지킨다.** 서버가 시도를 대신 시작하지 않는다.
   - 연결 정책이 `retry`이고 남은 횟수가 있으면:
     - 간격이 0인 기존 정책의 동작은 유지한다. 지연 정책에서는 아래처럼 planned + scheduledAt만 기록하며 서버가 시각에 자동으로 start하지 않는다.
     - 간격이 있으면 실패한 시도에 `retry_at`(= 지금 + 간격)을 쓰고, 단계를 `planned`로 되돌리며 `scheduled_at = retry_at`으로 둔다. 이벤트 `step_retry_scheduled {attemptNo, retryAt}`.
   - 외부 실행자는 `retry_at` 이후 `start`로 다음 시도를 연다. 그 전의 `start`는 기존 규칙대로 409다.
   - 이미 있는 두 열(`retry_at`, `scheduled_at`)과 기존 규칙만 쓰므로 스케줄러가 필요 없다.

4. **시간 제한은 서버가 감시한다.**
   - 주기 작업(`@Scheduled`)이 `started_at + timeoutSeconds`가 지난 `running` 시도를 찾는다.
   - 실행 행을 잠근 뒤 그 시도가 아직 `running`이고 기한이 지났는지 **다시 확인**하고 나서만 실패로 기록한다(오류 코드 `TIMEOUT`, actor `system`, 이벤트 `step_timed_out` 다음 `step_failed`). 인스턴스가 여럿이어도 한 번만 처리된다(재고 경보 배치와 같은 방식). 별도 분산 잠금 라이브러리는 들이지 않는다.
   - 그다음은 외부가 실패를 보고한 것과 같다. 연결 정책(stop·skip·retry)과 결정 3의 간격이 그대로 적용된다.
   - 시간 제한 뒤 늦게 온 `complete`·`fail`은 시도가 이미 끝났으므로 409다(지금 `runningAttempt` 규칙).

5. **동시 실행 제한은 `start`에서 막는다. 대기열은 없다.**
   - `start`(재시도 시작 포함) 때, 같은 workflowRevision의 running 실행들에서 같은 노드의 `running` 단계 수가 `concurrencyLimit` 이상이면 409 `Node … already has N running steps (limit N).` 단계는 `planned`로 남고, 외부 실행자가 나중에 다시 시작한다.
   - 같은 revision·node의 start는 하나의 잠금 경계 안에서 running 수를 세고 시작한다. 다른 revision은 같은 제한에 합산하지 않는다. 잠금 구현은 별도 설계하며 물리 설비의 global capacity와 혼합하지 않는다.
   - 결정 3의 즉시 재시도(간격 0)가 제한에 걸리면 즉시 재시도 대신 결정 3의 `planned` 경로로 돌린다(`scheduled_at` 없음).

6. **첫 범위는 일반 그래프 실행이다.**
   - 생산 실행에 연결된 Flow Run은 단계를 생산 API로만 기록하므로 제외한다.
   - 수동 실행(`execution_mode = manual`)은 지금 동작을 유지한다(아래 "결정하지 않은 것").

7. **외부 워크플로 엔진(Temporal·Conductor 서버)은 들이지 않는다.** 개념만 가져오고 지금의 Flow Run·Step·Attempt·Event 위에 얹는다(벤치마크 BPM-08 "보류").

## 결정하지 않은 것

- 노드 종류별 실행기와 그 SPI(ADR-003 9-(b))
- 수동 실행에 시간 제한·동시 실행 제한을 적용할지
- 제한 초과 시 대기열(자동으로 기다렸다가 시작)
- 실행 전체 시간 제한(Conductor `WorkflowDef.timeoutSeconds`), 응답 없음 감지(heartbeat, Conductor `responseTimeoutSeconds`)
- `alert`(알림만) 시간 제한 처리와 알림을 받을 곳
- 에디터에서 노드 실행 정책을 편집하는 화면
- 루프·fork/join·보상(compensation)

## 결과

- 좋은 점
  - 기본값이 지금 동작과 같아 기존 그래프와 테스트가 그대로 통과한다.
  - 재시도 간격은 이미 있는 열과 규칙으로 표현되고, 서버가 새로 갖는 주기 작업은 시간 제한 감시 하나다.
  - 실행기를 정하기 전에도 외부 실행자(사람·스크립트·CI)가 정책의 이득을 본다.
- 비용
  - 시간 제한 감시가 주기적으로 `running` 시도를 조회한다. `flow_run_step_attempt(status, started_at)` 인덱스가 필요할 수 있다.
  - 같은 workflowRevision·node의 제한 검사와 start는 직렬화가 필요하다.
  - 단계가 `failed`를 거치지 않고 `planned`로 돌아가므로, 화면과 이벤트 해석이 "재시도 대기"를 알아야 한다.

## 검증 (Accepted 뒤 구현할 때)

- 정책 없는 그래프: 기존 `FlowRunGraphIntegrationTest`가 바뀌지 않고 통과한다(즉시 3회 재시도, stop·skip).
- `retryDelaySeconds 60`: 실패 → 단계 `planned`, `scheduledAt` = 시도의 `retryAt`, 이벤트 `step_retry_scheduled` → 그 전 `start` 409 → 시각 뒤 `start`로 2번째 시도. `exponential` 상한 계산 단위 테스트.
- `retryLimit 0`: 첫 실패에 연결 정책이 `retry`여도 실행 실패.
- 시간 제한: 기한이 지난 시도가 `TIMEOUT`으로 실패하고 연결 정책이 적용된다. 감시 작업 두 개를 동시에 돌려도 실패 이벤트가 하나다. 늦은 `complete`는 409.
- 동시 실행 제한 1: 같은 revision·node의 두 실행 중 두 번째 start는 409, 종료 후 시작 가능. 동시 요청 하나만 성공. 다른 revision의 같은 node는 별도 한도이며 기존 snapshot의 정책을 사용한다.
- 범위 밖 값 저장 400, 생산 연결 실행에는 정책이 적용되지 않음.
