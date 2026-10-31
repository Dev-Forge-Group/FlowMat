# 아키텍처 결정 기록 (ADR)

| ADR | 제목 | 상태 | 요약 |
|---|---|---|---|
| [ADR-001](ADR-001-organization-project-boundary.md) | Organization / Project 경계 | **Accepted** 2026-10-05 | 멤버십 분리·cross-project DENY·업무 데이터 override 없음. 조직 관리자 metadata만 허용, 탈퇴 시 프로젝트 멤버 비활성화·소유권 이전 보호 |
| [ADR-002](ADR-002-module-dependency.md) | 모듈 의존 규칙 | **Accepted** 2026-10-02 | 다른 도메인의 repository는 쓰지 않고 `application.publicapi`로만 접근한다. 기존 위반은 동결하고 손댈 때 고친다 |
| [ADR-003](ADR-003-resource-port-contract.md) | Resource·Port 계약과 실행 Core | **Accepted(원칙)** 2026-10-02 + Registry **Experimental** | ProcessIo는 Port Contract, `itemId`는 선택적 binding, `schemaJson`은 정의 시점 계약, 실행값은 FlowRunStep snapshot, Resource 슈퍼테이블 없음. 정확한 `resourceType` 값 목록은 Experimental registry. 후속 ADR-005에서 backend VALIDATED / 제품 PARTIALLY VALIDATED와 계측 필드 선택 정책을 분리 |
| [ADR-004](ADR-004-flow-run-execution-policy.md) | Flow Run 실행 정책(FM-RUN-005) | **Accepted** 2026-10-05 | 노드 정책·연결 실패 정책, TIMEOUT 실패, 외부 executor 지연 start, workflowRevision + node 동시성 |
| [ADR-005](ADR-005-port-measurement-and-validation.md) | Port 계측·검증 수준 후속 결정 | **Accepted** 2026-10-05 | 비제조 quantity/unit 선택, 실행 요건 분리, backend VALIDATED / 제품 PARTIALLY VALIDATED |
| [ADR-006](ADR-006-operation-types.md) | 노드 종류(Operation Type)와 분기·합류(FM-PLAN-001) | **Accepted** 2026-10-17(1단계 Exclusive 로컬 구현 2eb) | 일을 하지 않는 게이트웨이 노드, 1단계 Exclusive만(priority 순 첫 연결, 기본 연결 최대 하나, 없으면 실패), 게이트웨이만 서버가 완료(system 이벤트), 시간 정책은 계획 쪽. Join·Subflow·수량 분할은 후속(Join 미승인) |
| [ADR-007](ADR-007-join-gateway.md) | 합류(Join) 게이트웨이(FM-PLAN-001 후속) | **Accepted** 2026-10-17(로컬 구현 2ef) | 이번 실행에서 더 올 수 있는 갈래가 없을 때 한 번 합류(도달 가능성), 끊긴 갈래는 빼고 도착한 것으로 진행, 같은 연결의 여러 도착은 모아서 한 번, 여러 출처를 새 표에 기록, 서버가 system으로 완료 |
| [ADR-008](ADR-008-quantity-split.md) | 수량 분할(Split) 게이트웨이(FM-PLAN-001 후속) | **Accepted** 2026-10-17(로컬 구현 2eh) | Split 게이트웨이의 나가는 연결마다 몫(비율·고정·나머지), 소수 4자리·차이는 나머지가, 0인 몫은 단계 없음, 모자라면 보고 409·시스템 계기는 실행 실패, Split 연결엔 조건 없음, 서버가 system으로 완료 |
| [ADR-009](ADR-009-connection-loss-rate.md) | 연결 손실률(lossRate) 적용(FM-FLOW-002, ADR-008 후속) | **Accepted** 2026-10-17(로컬 구현 2ei) | 연결을 지날 때 보낸 수량 × (1 − 손실률), 비율 0 ≤ r < 1(편집기는 %), 도착 소수 4자리·손실 = 보낸 − 도착, 이벤트 `transfer_loss`, 새 발행본부터(`schemaVersion` 2), 새 마이그레이션 없음 |
| [ADR-010](ADR-010-connection-delay.md) | 연결 지연(delayTimeSec) 적용(FM-FLOW-002, ADR-009 후속) | **Accepted** 2026-10-17(로컬 구현 2ej) | 연결을 지나 만든 단계의 `scheduledAt` = 출발 완료 + 지연(게이트웨이는 받은 시각을 넘김, Join은 가장 늦은 도착), 앞당기기는 409(바닥), `step_scheduled` reason `connection_delay`, 새 발행본부터(`schemaVersion` 3), 새 마이그레이션 없음 |
| [ADR-011](ADR-011-production-steps.md) | 생산 실행의 공정 단계와 노드별 지침 | **Accepted** 2026-10-17(로컬 구현 2el) | 새로 시작하는 생산 실행의 연결 Flow Run을 그래프로 돌려 노드 단계를 둔다. 단계 API는 생산 실행 쪽, 출력은 노드에 기록된 산출로 채움, 재고는 단계가 움직이지 않음, 지침 단계에 노드를 적음, 진행 중 단계가 있으면 마감 409 |
| [ADR-012](ADR-012-workflow-access-boundary.md) | 워크플로 접근 검사와 남은 모듈 경계(ADR-002 후속) | **Accepted** 2026-10-18(로컬 구현 2eq) | 남은 동결 7줄을 없앤다. 워크플로 단위 접근 검사를 workflow로 옮기고, 현재 워크플로는 project 명령 API로, 초대의 사용자 조회는 user 공개 API로 바꾼다 |
| [ADR-013](ADR-013-rota-history.md) | 근무표 이력(적용 시작일) | **Accepted** 2026-10-18(로컬 구현 2er) | 작업자의 주간 교대와 순환 근무표를 시작일이 있는 판으로 나눠, 근무표를 바꿔도 지난 날짜 계산이 그대로이게 한다 |
| [ADR-014](ADR-014-equipment-calendar-history.md) | 설비 달력 이력(ADR-013 후속) | **Accepted** 2026-10-18(로컬 구현 2es) | 설비의 주간 교대도 시작일이 있는 판으로 나눈다. 달력 지우기는 그날부터 항상 가동, 첫 판 전은 지금처럼 항상 가동 |
| [ADR-015](ADR-015-shift-aware-delay.md) | 근무 시간에 맞춘 연결 지연(ADR-010 후속) | **Accepted** 2026-10-18(로컬 구현 2et) | 생산 실행의 단계는 연결 지연이 작업지시 설비의 근무 밖에 끝나면 다음 가용 시간의 시작으로 미룬다. 설비·달력이 없거나 생산에 연결되지 않은 실행은 그대로 |
| [ADR-016](ADR-016-crew-aware-delay.md) | 인력 근무에도 맞춘 연결 지연(ADR-015 후속) | **Accepted** 2026-10-18(로컬 구현 2eu) | 생산 단계의 지연 뒤 시각을 설비 가용 시간과 몫 100%인 교대 인력 모두의 근무 시간에 함께 맞춘다. 설비가 없으면 인력만으로 |
| [ADR-017](ADR-017-version-reasons.md) | 판을 바꾼 사유(ADR-013·014 후속) | **Accepted** 2026-10-18(로컬 구현 2ev) | 근무표 판과 설비 달력 판마다 선택 사유(200자)와 마지막 저장자·시각을 둔다. V95 |
| [ADR-018](ADR-018-entity-boundary.md) | 단계 C: 다른 도메인 엔티티를 쓰지 않는다(ADR-002 후속) | **Accepted** 2026-10-19(2026-10-25 0줄·동결 해제, 로컬) | 다른 도메인 엔티티 의존 기준선 39줄(21클래스, import 37곳 20파일)을 `ModuleBoundaryTest`에 동결했다. 새 의존은 실패하고 목록은 줄기만 한다. 원인은 Facts(json) 공개 API, `ProjectAccessService`의 `Project` 반환, `ItemUsageCheck`. 줄일 때는 가진 쪽의 공개 view로. 2026-10-24–25에 0줄(2gl–2gr)이 되어 동결을 풀었다(2gs): 새 의존은 바로 실패한다. Facts(json) API도 모두 지웠다(2gt). 흐름 규칙은 가진 쪽이 만든 속성 지도를 읽는다(결정 5) |
| [ADR-019](ADR-019-run-labor.md) | 실행 작업자 시간(ADR-013·016 후속) | **Accepted** 2026-10-19(로컬 구현 2ex) | 실행마다 사람·분 기록(열린 실행, 사유와 함께 취소, 금액 없음). 인력은 몫 × 실행 중 근무 시간으로 제안. 작업지시마다 계획 인력 시간 대 기록. V96 |
| [ADR-020](ADR-020-labor-correction.md) | 끝난 실행의 작업자 시간 보정(ADR-019 후속) | **Accepted** 2026-10-19(로컬 구현 2ey) | 실행 보정 줄 `cancel_labor`·`add_labor`(사람 필수), 소유자 승인 때 반영(`Correction #N: 사유`). 보정 본문 오류 500 → 400. V97 |
| [ADR-021](ADR-021-labor-step.md) | 생산 단계의 작업자 시간(ADR-019 후속) | **Accepted** 2026-10-19(로컬 구현 2ez) | 기록이 실행의 공정 단계를 고를 수 있다(공개 API로 확인, 외래 키 없음). 응답에 단계 목록·단계 이름, 화면은 시작한 단계의 시간 × 몫으로 분을 채움. V98 |
| [ADR-022](ADR-022-working-time-delay.md) | 근무 시간으로 세는 연결 지연(ADR-015 후속) | **Accepted** 2026-10-19(로컬 구현 2fa) | 연결마다 `delayBasis` elapsed/working. working은 작업지시 설비·인력이 함께 일한 시간을 지연만큼 쌓은 때가 예정 시각. 생산 실행이 아니거나 설비·인력이 없으면 모든 시간. schemaVersion 그대로. V99 |
| [ADR-023](ADR-023-labor-step-followups.md) | 단계 작업자 시간의 보정과 공정별 합계(ADR-021 후속) | **Accepted** 2026-10-19(로컬 구현 2fb) | 보정 줄 `add_labor`의 `stepId`(실행 기록과 같은 검사), 작업지시 대비에 공정별 실제 분과 단계 없는 분. V100. 이후 공정별 계획(2he, V124)·계획 기간 제안(2hf)·몫만큼 쌓기(2hg)·사람별 공정 계획(2hn, V131)·계획 제안의 사람별 몫(2hq)·공정 순서(2hr)·사람별 일정(2hs) |
| [ADR-024](ADR-024-version-save-history.md) | 판의 저장 이력(ADR-017 후속) | **Accepted** 2026-10-19(로컬 구현 2fc) | 근무표 판·설비 달력 판을 저장·지울 때마다 그때의 교대·사유·누가·언제를 한 줄로 남김(지운 판도). 조회 API 둘과 화면 Version history·Calendar history. V101. 확장(2026-10-23, 2fe, V102 커밋 전): 날짜별 교대·쉬는 날·설비 정지의 저장·지움도 같은 방식으로 남김 |
| [ADR-025](ADR-025-worker-labor-cost.md) | 작업자 인건비(ADR-019 후속) | **Accepted** 2026-10-23(로컬 구현 2ff, V103) | 소유자만 보는 금액, 프로젝트 × 작업자 단가, 적용 시작일이 있는 이력(지난 날짜 불변). 금액은 읽을 때 분 × 그날 단가로 계산하고 새 소유자 전용 API로만 낸다. 구현 2ff, V103. 인건비는 원가 합계에 넣지 않고(결정 10), 첫 단가만 처음부터 적용할 수 있다(결정 11, 2fh) |
| [ADR-026](ADR-026-attendance.md) | 출퇴근 기록과 작업자 시간 대조(ADR-019 후속) | **Accepted** 2026-10-24(사용자 위임으로 권장안 A, 로컬 구현 2fj, V104 커밋 전) | 본인·소유자만 출근·퇴근 시각을 적고 보며(겹치지 않음, 사유와 함께 취소) 날마다 실행 작업자 시간과 견준다. 보존 기간·내보내기는 미정 |
| [ADR-027](ADR-027-purchasing.md) | 구매 발주서와 입고(첫 단계) | **Accepted** 2026-10-24(사용자 위임으로 권장안 A, 로컬 구현 2fm, V106 커밋 전) | 새 `purchasing` 컨텍스트의 발주서(초안 → 주문 → 줄마다 입고, 닫기·취소). 입고는 inventory 공개 명령으로 재고 일괄 입고 한 줄과 같은 검사·저장 |
| [ADR-028](ADR-028-pay-premiums.md) | 연장·야간·휴일 할증(ADR-025·026 후속) | **Accepted** 2026-10-30(사용자 선택 뒤 권장안, 로컬 구현 2hh, V126 커밋 전) | 프로젝트마다 시작일이 있는 할증 이력(연장 기준 분·야간 창·휴일, 각 가산 %), 소유자만. 출퇴근으로 근무일마다 지급 분과 금액을 읽을 때 계산. 실행·작업지시 인건비에도 그날 지급 비율로 붙임(2hi). 주 단위 연장(2hj, V127), 쉬는 날 근무(2hk, V128), 휴일 연장(2hl, V129), 작업자마다 다른 규칙(2hm, V130), 작업자 시간의 시각(2ho, V132), 대비의 시각 날짜(2hp)·보정 날짜(2hu) |
| [editor ADR-0001](../../editor/adr-0001-flowmat-editor-core-boundary.md) | 에디터 Core 경계 | Accepted 2026-08-12 | 순수 TypeScript editor core를 React Flow와 분리한다 |

## 규칙

- 결정의 요약, 보류 항목, 다음 작업 순서는 [결정 인계](../decision-handoff.md)에 있다. 상세 근거는 [원문](../../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md)에 있다.
- 상태는 Proposed, Accepted, Superseded 중 하나다. 원칙은 Accepted이고 세부 목록만 실증 대상이면 그 부분을 별도 절로 떼어 Experimental로 표시한다(예: ADR-003 Resource Type Registry).
- Proposed ADR에는 수용 기준(체크리스트)을 둔다. 모두 닫히면 Accepted로 올리고 상태 이력에 날짜를 남긴다. Proposed ADR의 결정에 기대는 구현(예: 조직 마이그레이션)은 Accepted 전에 시작하지 않는다.
- 상태와 구현은 별개다. 코드가 이미 있다고 결정이 승인된 것은 아니고, 승인됐다고 구현된 것도 아니다.
- Accepted ADR의 결정을 바꿀 때는 기존 ADR을 고치지 않고 새 ADR을 쓴다. 기존 ADR의 상태는 `Superseded by ADR-xxx`로 바꾼다. 오타와 링크 수정은 예외다.
- 번호는 세 자리로 이어서 매긴다. 파일 이름에 주제를 넣는다.
- 각 ADR의 "결정하지 않은 것"은 별도 ADR이나 사용자 결정 없이 정하지 않는다.
