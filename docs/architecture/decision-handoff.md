# FlowMat 아키텍처 결정 인계

> **2026-10-05 사용자 최종 결정:** [결정 기록](../status/DECISIONS-2026-10-05.md)이 아래 예전 Proposed·결정 대기 표시보다 우선한다. ADR-001/004 Accepted, ADR-002/003 원칙 유지, ADR-005 후속 계측·검증 결정. 정책 승인과 현재 코드 구현을 구분한다.

기준일 2026-10-02 · 코드 기준 `45f5df0`(main, 마이그레이션 V1–V41)

이 문서는 다음 Agent가 **무엇이 확정됐고 무엇을 아직 결정하면 안 되는지** 알기 위한 인계서다. 구현 지시서가 아니다. 이 문서만 보고 마이그레이션을 만들지 않는다. 조직 테이블도 ADR 승인이 먼저다.

상세 논의와 근거는 [FlowMat_Architecture_refactoring_Handoff.md](../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md)(이하 "원문", §번호)에 있다. 두 문서가 다르면 이 문서를 따른다.

## 1. 한 문장

FlowMat은 업종이 아니라 흐름을 모델링하고 실행한다. 제조·재고·BOM·품질·데이터·업무 같은 업종 개념은 Core가 아니라 도메인 확장으로 둔다. (원문 §1, §2, §55)

## 2. 확정된 결정

결정 상태(2026-10-02 의사결정자 검토):

| 묶음 | ADR | 상태 |
|---|---|---|
| A1–A3 조직 경계 | [ADR-001](adr/ADR-001-organization-project-boundary.md) | **Accepted**(2026-10-05 사용자 최종 결정). Phase 1(V64 조직 표, `project.organization_id` NULL 허용)과 Phase 2(V65 개인 조직 backfill)가 들어갔다. NOT NULL 전환은 §3 |
| M1–M2 모듈 경계 | [ADR-002](adr/ADR-002-module-dependency.md) | **Accepted** |
| R1–R5, E1–E2 Port 계약·실행 | [ADR-003](adr/ADR-003-resource-port-contract.md) | 원칙 **Accepted**. 정확한 `resourceType` 값 목록은 **Experimental** registry |
| P1–P2 금지 | 원문 §39·§48·§54 | 확정 |
| FM-RUN-005 실행 정책 | [ADR-004](adr/ADR-004-flow-run-execution-policy.md) | **Accepted**(2026-10-05 사용자 최종 결정) |
| 운영 관리 포트 | [ADR-029](adr/ADR-029-management-port.md) | **Accepted**(2026-11-01 사용자 최종 결정 D3) |
| 얼굴 로그인 기본 OFF | [ADR-030](adr/ADR-030-face-login-off.md) | **Accepted**(2026-11-01 사용자 최종 결정 D4) |
| BOM 줄 스크랩률 | [ADR-031](adr/ADR-031-bom-scrap-rate.md) | **Accepted**(2026-11-02, 2026-10-24 사용자 위임으로 권장안) |
| BOM 대체 자재 | [ADR-032](adr/ADR-032-bom-substitute-materials.md) | **Accepted**(2026-11-02, 2026-10-24 사용자 위임으로 권장안) |
| BOM 선택 자재 | [ADR-033](adr/ADR-033-bom-optional-materials.md) | **Accepted**(2026-11-02, 2026-10-24 사용자 위임으로 권장안, V133 개발 DB 미적용) |
| BOM 줄 실제 손실 집계 | [ADR-034](adr/ADR-034-bom-scrap-performance.md) | **Accepted**(2026-11-02, 2026-10-24 사용자 위임으로 권장안) |

ADR-001~003(Architecture Decision Freeze)과 재고·BOM·LOT 계약의 구현 결정(D2)은 별개의 결정 트랙이다. D2가 남아 있어도 아키텍처 결정을 막지 않는다([WORKBOARD](../status/WORKBOARD.md) §3).

| ID | 결정 | 지금 코드에서 뜻하는 것 | 원문 |
|---|---|---|---|
| A1 | **Organization은 상위 소유 및 계정 그룹 경계이고, Project는 현재의 데이터 격리 및 접근권한 집행 경계다. Organization membership은 별도 정책이 도입되기 전까지 Project 접근권한을 암묵적으로 부여하지 않는다.** (사용자 확정 문장) | organization 테이블은 아직 없다. 업무 데이터는 `project_id`로 격리하고, 접근은 `ProjectAccessService`의 프로젝트 멤버십(viewer/editor/owner)과 시스템 역할로만 판정한다. 조직 OWNER/ADMIN이라도 프로젝트 멤버가 아니면 볼 수 없다. | §11–14 |
| A2 | 모든 `project_id`를 `organization_id`로 바꾸지 않는다. 조직은 `데이터 → Project → Organization`으로 따라간다. | 기존 테이블의 `project_id`는 그대로 둔다. | §14, §48 |
| A3 | Company → Factory → Project 같은 고정 계층을 만들지 않는다. Site와 BusinessPartner는 Organization 아래의 별도 모델이고, Project와는 선택 관계다. | 지금 만들지 않는다(§3 보류 표). | §9, §10, §15, §16 |
| M1 | Repository는 자기 bounded context 안에서만 쓴다. 다른 도메인은 그 도메인의 `application.publicapi`(Query/Command, 불변 View DTO)로 접근한다. 지금 결과가 필요하면 동기 Query/Command를, 사건 알림이면 Domain Event를 쓴다. | 기존 위반은 100줄이다(§4). 기준 구현으로 catalog `CatalogQuery`와 flowrun `FlowRunCommand`가 생겼다. | §19–23 |
| M2 | 기존 위반은 한 번에 고치지 않는다. 새 코드부터 막고(ArchUnit 기준선), 기존 코드는 손댈 때 바꾼다. | Stage A가 `ModuleBoundaryTest`로 적용됐다(다음 작업 3). | §25, §48 |
| R1 | `ProcessIo`는 Process가 받거나 내보내는 **Port Contract**다. | 필드는 §4 참조. | §29 |
| R2 | `resourceType`은 포트로 흐르는 대상(Port Resource)의 의미다. DB enum으로 고정하지 않고 애플리케이션 목록으로 관리하며, 목록 밖 값은 **경고**로 처리한다(거절하지 않음). 정확한 값 목록은 Experimental registry다. 노동·설비·연산·기술·시간 같은 실행 요건은 Port Resource가 아니다. | 저장은 자유 문자열 그대로이고, registry(`ResourceTypes`) 밖이면 검증 API가 `RESOURCE_TYPE_UNKNOWN` 경고를 낸다(다음 작업 4). `labor`는 registry에 없다 | §30 |
| R3 | `itemId`는 범용 Resource ID가 아니라 **Catalog Item과 연결될 때만 쓰는 선택적 binding**이다. 데이터·파일·API 포트는 `itemId` 없이 `schemaJson`으로 계약한다. | | §31 |
| R4 | `schemaJson`은 정의 시점 계약이다. 실행값은 `FlowRunStep`의 input/output snapshot에 저장한다. | 이미 이렇게 동작한다. | §32 |
| R5 | Resource 슈퍼테이블을 만들지 않는다. 공통화는 의미가 실제로 같은 곳에서만 한다. | | §27, §43 |
| E1 | **FlowRun을 장기적으로 도메인 독립적인 실행 Core로 사용한다. 현재 구조는 이를 지원할 기반을 갖추고 있으나, 비제조 vertical slice가 검증될 때까지 범용 실행 능력은 아키텍처 목표로 취급한다.** (사용자 확정 문장) ProductionRun은 제조 확장이다. | `flow_run.production_run_id` 연결은 호환용으로 두고 DB 관계를 뒤집지 않는다. "FlowRun은 범용이다"를 이미 증명된 사실로 문서나 코드 주석에 쓰지 않는다. | §40 |
| E2 | 정의(Definition)와 실행(Execution)을 분리한다. Simulation은 같은 정의를 쓰지만 재고를 움직이지 않는다. | `ProductionRun.affectsPhysicalState`, FlowRun `runType`(actual·simulation·test·dry_run). | [execution-model.md](execution-model.md) |
| P1 | `domain/erp`·`mes`·`scm`·`wms` 같은 대형 패키지를 만들지 않는다. 책임 경계가 분명할 때만 새 bounded context를 만든다. | | §39 |
| P2 | 지금 하지 않는다: 전면 리팩토링, microservice, Kafka/RabbitMQ, Outbox, ERP 전체(GL·AR·AP·급여·세무), 에디터 전면 교체, 제조 기능을 Core로 끌어올리기. | | §48, §54 |

## 3. 아직 결정하지 않은 것 — 임의로 정하지 않는다

아래는 사용자 결정이나 별도 ADR이 필요하다. 구현 중 필요해 보여도 Agent가 정하지 말고 보고한다.

| 항목 | 지금 상태 | 언제·어떻게 정하나 |
|---|---|---|
| 조직 역할의 프로젝트 접근 상속(조직 ADMIN이 조직의 모든 프로젝트를 보는가) | 상속 없음(A1) | 별도 ADR, 사용자 결정 |
| Tenant 격리 정책(조직 간 격리 수준, 조직 단위 조회) | 없음 | 여러 조직 운영이 확정될 때(원문 §51) |
| `project.organization_id` NOT NULL 전환 | NULL 허용 그대로. 새 프로젝트는 `OrganizationService.forNewProject`가 늘 조직을 채우고(개인 조직을 찾거나 만들고, 속하지 않은 조직은 403), 기존 프로젝트는 V65가 채웠다. 테스트 8개 파일이 조직 없이 프로젝트를 직접 넣는다 | **2026-10-31 사용자 위임 결정("의사결정 최적으로 내려봐" → "ㄱ"): 지금은 하지 않는다.** 제약이 막는 것은 raw SQL뿐이고, 접근 판정은 A1대로 프로젝트 멤버십만 본다. 조직 단위로 데이터를 가르는 첫 기능(조직 목록·과금·테넌트 격리)이 들어올 때, 실제 DB에서 OR8 점검 쿼리(null 0건)를 돌리고 한 마이그레이션에서 남은 null을 V65 방식으로 채운 뒤 NOT NULL로 바꾸며 직접 넣는 테스트도 함께 고친다 |
| Site·BusinessPartner·project_site·project_partner 테이블 | 없음 | 실제 ERP/CRM/SCM 요구가 생길 때(원문 Phase 6). 첫 후보: 출고 거래에 거래처가 없어 LOT 리콜에서 출고처를 못 보여주는 문제([현재 기능](../status/CURRENT_CAPABILITIES.md) "LOT 리콜" 행) |
| Port binding 저장 구조(`bindingType`/`bindingId`, polymorphic 테이블) | 개념만 있음 | 비제조 실사용 검증 후(원문 §42) |
| 데이터·파일·API 포트의 `quantity`·`unit` | 둘 다 필수라 의미 없는 값(`quantity 0`, `ea`)을 넣는다 | 데이터 흐름을 실제로 쓰기 시작할 때(ADR-003 결정하지 않은 것) |
| FlowRun 범용성을 "검증됨"으로 바꿀지 | 9-(a) 통합 테스트 통과(2026-10-03, 외부 실행자가 결과를 보고하는 방식). 실제 기능은 모두 제조이고 노드 실행기는 없다. 결정 8은 "목표" | **2026-10-31 사용자 위임 결정("의사결정 최적으로 내려봐" → "ㄱ"): "목표"로 둔다.** E1 문장이 비제조 vertical slice 검증을 조건으로 달았고, 올려도 기능상 얻는 것이 없다. 전환 기준: 비제조 흐름 하나(예: 문서 승인, 데이터 변환)를 편집기에서 정의·발행하고 화면에서 단계 시작·완료까지 실제로 돌려 결과를 확인하면 "검증됨"으로 올린다 |
| 공통 Resource contract | 없음(R5) | 두 종류 이상의 비제조 흐름에서 같은 검증·lifecycle이 반복될 때(원문 §51) |
| 노드 실행기(Flow Run이 노드를 스스로 실행)와 실행 정책(시간 제한·재시도 간격·동시 실행 제한, FM-RUN-005) | 외부 보고 방식만 있음, 연결 실패 시 고정 3회 즉시 재시도 | 실행 정책은 [ADR-004](adr/ADR-004-flow-run-execution-policy.md) **Proposed**(2026-10-03): 정책은 노드에, 기본값은 지금 동작, 재시도 간격은 외부 보고 모델 위에서, 시간 제한만 서버 감시. 수용 기준 5개를 의사결정자가 닫아야 구현한다. 노드 실행기는 여전히 별도 결정(원문 §45 보완 (b), 2026-09-24 인계의 D7) |
| 노드 종류(Operation Type)·배타 선택·합류(FM-PLAN-001) | Exclusive 게이트웨이(2eb, V85 로컬 검증, [연결 계약](../domain/process-port-connection-contract.md) GR-13–GR-20). Join 게이트웨이(2ef, V88 로컬 검증, GR-21–GR-28). Split 게이트웨이(2eh, V89 로컬 검증, GR-29–GR-36). 그 밖의 노드는 통과한 연결마다 별도 단계(GR-04) | [ADR-006](adr/ADR-006-operation-types.md) **Accepted**(2026-10-17 사용자 결정): 게이트웨이 노드, 1단계 Exclusive. Join은 [ADR-007](adr/ADR-007-join-gateway.md) **Accepted**(2026-10-17 사용자 결정): 도달 가능성 기준 한 번 합류. 2ef 로컬 구현(V88). 수량 분할은 [ADR-008](adr/ADR-008-quantity-split.md) **Accepted**(2026-10-17 사용자 결정): Split 게이트웨이. 2eh 로컬 구현(V89) |
| 연결 손실률(`lossRate`, FM-FLOW-002) | 새 발행본(`schemaVersion` 2)부터 연결을 지날 때 뺀다(2ei, 로컬 검증, GR-37–GR-44). 저장은 비율 0 ≤ r < 1, 편집기는 %. 예전 발행본은 그대로 | [ADR-009](adr/ADR-009-connection-loss-rate.md) **Accepted**(2026-10-17 사용자 결정 "Accept as recommended"): 연결을 지날 때 빼기, 새 발행본부터. 2ei 로컬 구현(마이그레이션 없음) |
| 연결 지연(`delayTimeSec`, FM-FLOW-002) | 새 발행본(`schemaVersion` 3)부터 다음 단계의 예정 시각이 된다(2ej, 로컬 검증, GR-45–GR-52). 게이트웨이는 받은 시각을 넘기고 Join은 가장 늦은 도착, 앞당기기 409. 예전 발행본은 그대로 | [ADR-010](adr/ADR-010-connection-delay.md) **Accepted**(2026-10-17 사용자 결정 "Accept as recommended"): 다음 단계의 예정 시각(바닥), 새 발행본부터. 2ej 로컬 구현(마이그레이션 없음) |
| 생산 실행의 공정 단계와 노드별 지침 | 새로 시작하는 생산 실행은 공정 단계를 생산 실행 API로 시작하고 완료한다(2el, 로컬 검증). 지침 단계는 공정을 가질 수 있다. 시간 제한을 넘긴 단계는 늦음으로만 표시한다(2en, V92) | [ADR-011](adr/ADR-011-production-steps.md) **Accepted**(2026-10-17 사용자 결정 "Accept as recommended"): 연결 Flow Run을 그래프로 돌린다. 2el 로컬 구현(V91). 시간 제한은 2026-10-18 사용자 결정 "늦음 표시만"(2en, V92) |
| 남은 모듈 경계 7줄 | 2026-10-18에 0줄이 됐다(2eq). 워크플로 단위 접근 검사는 workflow의 `WorkflowAccessService`에 있다 | [ADR-012](adr/ADR-012-workflow-access-boundary.md) **Accepted**(2026-10-18 사용자 결정 "Accept as recommended"): 접근 검사를 workflow로 옮긴다. 2eq 로컬 구현, 동결 목록 0줄 |
| 근무표 이력 | 작업자의 주간 교대와 순환 근무표가 시작일이 있는 판에 묶여, 바꿔도 지난 날짜 계산이 그대로다(2er) | [ADR-013](adr/ADR-013-rota-history.md) **Accepted**(2026-10-18 사용자 결정 "Accept as recommended"): 작업자 근무표를 시작일이 있는 판으로 나눈다. 2er 로컬 구현(V93) |
| 근무 시간에 맞춘 연결 지연 | 연결 지연은 근무와 상관없이 그대로 더한다(ADR-010) | 2026-10-18 사용자 결정(사전): 생산 실행의 단계는 작업지시 설비 달력에 맞춰 미룬다. 설비·달력이 없거나 생산에 연결되지 않은 실행은 그대로. [ADR-015](adr/ADR-015-shift-aware-delay.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2et |
| 인력 근무에 맞춘 연결 지연 | 생산 단계의 지연은 작업지시 설비 근무에만 맞춘다(ADR-015) | 2026-10-18 사용자 지시("지금 구현된 기능과 연관된것 부터")로 골랐다. [ADR-016](adr/ADR-016-crew-aware-delay.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2eu |
| 판을 바꾼 사유 | 근무표 판과 설비 달력 판에는 시작일만 있다(ADR-013·014) | 2026-10-18 사용자 지시("지금 구현된 기능과 연관된것 부터")로 골랐다. [ADR-017](adr/ADR-017-version-reasons.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2ev(V95) |
| 실행 작업자 시간 | 실행에는 설비 시간만 기록했다(작업자 달력 WC10·WC16 범위 밖) | 2026-10-18 사용자 지시("지금 구현된 기능과 연관된것 부터")로 골랐다. [ADR-019](adr/ADR-019-run-labor.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2ex(V96) |
| 끝난 실행의 작업자 시간 보정 | 끝난 실행의 작업자 시간은 바꿀 수 없었다(ADR-019 결정하지 않은 것) | [ADR-020](adr/ADR-020-labor-correction.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2ey(V97) |
| 생산 단계의 작업자 시간 | 작업자 시간은 실행 전체에만 기록했다(ADR-019 결정하지 않은 것) | [ADR-021](adr/ADR-021-labor-step.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2ez(V98) |
| 근무 시간으로 세는 연결 지연 | 지연은 모든 시간으로 셌다(ADR-015 결정하지 않은 것) | [ADR-022](adr/ADR-022-working-time-delay.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2fa(V99) |
| 단계 작업자 시간의 보정과 공정별 합계 | 보정 줄은 단계를 받지 않았고, 작업지시 대비는 사람별뿐이었다(ADR-021 결정하지 않은 것) | [ADR-023](adr/ADR-023-labor-step-followups.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2fb(V100) |
| 판의 저장 이력 | 판은 마지막 저장만 남았다(ADR-017 결정하지 않은 것) | [ADR-024](adr/ADR-024-version-save-history.md) **Accepted**(세부는 사용자 사전 위임), 로컬 구현 2fc(V101). 확장 2fe(2026-10-23, V102 커밋 전): 날짜별 교대·쉬는 날·정지 |
| 작업자 인건비 | 작업자 단가가 없어 작업자 시간에 금액이 없다(ADR-019 결정하지 않은 것) | [ADR-025](adr/ADR-025-worker-labor-cost.md) **Accepted**(2026-10-23, 핵심 셋은 사용자, 세부는 사전 위임), 로컬 구현 2ff(V103, 커밋 전) |
| 출퇴근 기록과 작업자 시간 대조 | 출퇴근 자료가 없어 적은 작업자 시간이 실제 근무와 맞는지 모른다(ADR-019 결정하지 않은 것) | [ADR-026](adr/ADR-026-attendance.md) **Accepted**(2026-10-24, 사용자 "의사결정도 recommend 로 계속 알아서 밀고나가"로 권장안 A, 2fj 로컬 구현. 보존 기간·내보내기는 미정) |
| 외부 웹훅 | 없음 | 2026-10-18 사용자 결정(사전): HMAC-SHA256 서명, 5번까지 재시도, 프로젝트 소유자만 등록, Outbox. 고를 때 ADR로 정리 |
| 설비 달력 이력 | 설비의 주간 교대는 한 벌이라, 바꾸거나 지우면 지난 날짜 계산도 바뀐다 | [ADR-014](adr/ADR-014-equipment-calendar-history.md) **Accepted**(2026-10-18 사용자 결정 "Accept as recommended"): 설비 교대도 시작일이 있는 판으로 나눈다. 로컬 구현 2es(V94, 커밋 전) |
| 실행 정책 편집 화면 | 2026-10-11 사용자 결정: 노드 인스펙터(구현 2di, [EP13](../domain/flow-run-execution-policy.md)) | 정해짐 — ADR-004 "결정하지 않은 것"의 이 항목은 이 결정으로 닫는다(ADR 본문은 고치지 않음) |
| 실사 차이 금액의 단가 기준 | 2026-10-12 사용자 결정: 오늘 단가와 실사 당시 이력 단가를 둘 다 보인다(구현 2dp, [CH1–CH4](../domain/stock-count.md)) | 정해짐 — DECISIONS-2026-10-05 §1의 "실사 금액 정책까지 자동 변경하지 않는다"가 남긴 항목을 이 결정으로 닫는다(DECISIONS 본문은 고치지 않음) |
| 작업지시 순서 제안·실사 계획의 위치 잠금 | 2026-10-12 사용자 결정: 순서는 설비별 전환 최소로 제안(구현 2dq, [P13–P16](../domain/equipment-schedule.md)). 실사 계획의 위치 잠금(실사 결정 메모의 C)은 넣지 않는다 | 정해짐. 납기·부하를 함께 보는 최적화는 따로 정할 일 |
| 승인 요청·결정 알림 | 2026-10-13 사용자 결정: BOM 제출·완료 실행 보정 요청은 소유자에게, 승인·반려는 제출·요청한 사람에게 앱 안 알림(구현 2dr, [NT8](../domain/notification.md)) | 정해짐. 구독 설정이 필요한 퍼지는 알림은 여전히 정하지 않았다 |
| 퍼지는 알림의 구독 | 2026-10-13 사용자 결정: 각자 프로젝트마다 고른다(재고 경보, 지난 NCR 효과 확인, N일 안에 마감되는 작업지시). 구현 2dt, [NS1–NS9](../domain/notification.md) | 정해짐. 메일·Slack·남의 선택 바꾸기는 정하지 않았다 |
| 다른 도메인 Entity import 금지(ArchUnit Stage C) | **0줄, 동결 해제(2026-10-25).** 기준선 39줄(2026-10-19)을 공개 view로 바꿔 줄였다. `archunit_store`가 없어 repository·엔티티 두 규칙 모두 새 위반에서 바로 실패한다 | [ADR-018](adr/ADR-018-entity-boundary.md) **Accepted**(2026-10-19) |
| Domain Event 발행 방식과 Outbox | 없음 | 첫 비동기 외부 연동 때(원문 Phase 7) |
| 편집 협업 충돌 모델(문서 단위 409 / 요소 patch / CRDT) | 문서 단위 409 | 사용자 결정(2026-09-24 D1, [C5 비교](../archive/2026-09-handoff/reports/C-collab-model-comparison.md)) |
| 권한 매트릭스 확정 | 초안 | 사용자 결정(2026-09-24 D3, [C4 초안](../archive/2026-09-handoff/reports/C-permission-matrix.md)). 조직 ADR과 함께 보는 것이 좋다 |

## 4. 현재 코드 사실 (2026-10-02 확인)

- 마이그레이션은 커밋 `45f5df0`까지 V1–V41이고, 커밋 전 작업 트리에 V42(포트 Item 선택)·V43(설비 교대 여러 개)·V44(재고 이동 원장 인덱스)·V45(설비 날짜별 교대)·V46(작업 지침 확인 취소 기록)·V47(작업 지침 단계 값 한계)·V48(부적합 효과 확인)·V49(창고 작업자 배정)·V50(품목 단가 이력)·V51(설비 상태 이력)이 있다. 모두 세션 DB에 적용돼 고칠 수 없다. 다음 빈 번호는 V52이지만, 작업 직전에 최신 main에서 다시 확인한다. 적용된 마이그레이션은 주석·공백까지 수정하지 않는다.
- 다른 bounded context의 repository import는 도메인 간 84줄이다(`global`·`batch` 제외, 2026-10-03, ArchUnit 동결 목록과 같은 수). 많은 순서는 inventory→catalog 20, production→catalog 16, production→inventory 12, production→workflow 6, production→bom 5, quality→catalog 5이다.
- 기준 구현(다음 작업 2) 전에는 105줄이었다. 다음 7줄을 공개 API로 바꿨다.
  - `BomWhereUsedTreeService`·`BomCostRollupService`의 `ItemRepository`·`UnitMasterRepository` 4줄 → [`CatalogQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/catalog/application/publicapi/CatalogQuery.java)
  - `ProductionFlowRunAdapter`의 `FlowRunRepository` 1줄 → [`FlowRunCommand`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/flowrun/application/publicapi/FlowRunCommand.java)
  - Stage B(2026-10-03, V42 작업 중 고친 파일): `ProcessIoServiceImpl`의 `ItemRepository`, `WorkflowValidationService`의 `UnitMasterRepository` 2줄 → `CatalogQuery`(`findActiveItem`·`isKnownUnitCode`)
  - Stage B(2026-10-03, 창고 작업 부분 완료 중 고친 파일): `WarehouseTaskService`의 `ItemRepository`·`WorkOrderRepository` 2줄 → `CatalogQuery`(`findProjectItem`·`findItems`)와 새 production 공개 API [`WorkOrderQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/production/application/publicapi/WorkOrderQuery.java)(`findProjectWorkOrder`·`findWorkOrders`, 불변 `WorkOrderView`). inventory가 production의 엔티티·enum을 더는 import하지 않는다
  - Stage B(2026-10-03, 작업 지침 단계 값 한계 V47 중 고친 파일): `WorkInstructionService`의 `ItemRepository` 1줄 → `CatalogQuery`(`findProjectItem`·`findItems`). 동결 96 → 95
  - Stage B(2026-10-03, NCR 효과 확인 준비로 고친 파일): `NonconformityService`의 4줄(`ItemRepository`·`LotMasterRepository`·`ProductionRunRepository`·`ProjectMemberRepository`) → `CatalogQuery`와 새 공개 API [`LotQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/inventory/application/publicapi/LotQuery.java)(`findProjectLot`·`findLots`), [`ProductionRunQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/production/application/publicapi/ProductionRunQuery.java)(`findProjectRun`·`findRuns`), [`ProjectMemberQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/project/application/publicapi/ProjectMemberQuery.java)(`isActiveMember`, 거절하지 않고 예/아니오만). 동결 95 → 91. 같이 공개 API의 빈 결과를 `Map.of()` 대신 `Collections.emptyMap()`으로 바꿈(없는 id인 null로 찾으면 `Map.of()`는 NPE를 냄)
  - Stage B(2026-10-03, 위치별 재고 분석 A5 중 고친 파일): `StockMovementAnalysisService`의 `ItemRepository`·`UnitMasterRepository` 2줄 → `CatalogQuery.findProjectItems`(새 연산, `CatalogItemView`에 `leadTimeDays` 추가). 동결 91 → 89
  - Stage B(2026-10-03, BOM 자재 CSV 종류 열 중 고친 파일): `BomLineImportService`의 `ItemRepository` 1줄 → `CatalogQuery.findProjectItems`(`CatalogItemView`에 `itemStatus`, `ItemStatusRule.isActive(String)`·`refusal(code, status, action)`). 동결 89 → 88
  - Stage B(2026-10-03, 설비 부하표 전환 순서 제안 중 고친 파일): `EquipmentLoadService`의 `EquipmentRepository`·`ItemRepository` 2줄 → `CatalogQuery`(새 `findProjectEquipments`·`equipmentWindow`, 뷰 `EquipmentWindow`). 동결 86 → 84
  - Stage B(2026-10-03, 품목 단가 이력 V50 중 고친 파일): `ItemServiceImpl`·`ItemImportService`의 `InventoryRepository` 2줄 → 새 공개 API [`StockQuery`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/inventory/application/publicapi/StockQuery.java)(`hasStockRecords`). 동결 88 → 86
  - 새 코드(2026-10-03 작업지시 계획 기간 제안)는 처음부터 `CatalogQuery`(`findProjectEquipment`·`earliestSlot`·`changeoverMinutes`)를 쓴다. 위반 수는 늘지 않았다.
  - Stage B(2026-10-17, 주간 계획을 앞당김): 서비스 18개의 catalog repository 29줄 → `CatalogQuery`. catalog 밖에서 catalog repository를 쓰는 곳은 없다. 동결 48 → 19. 공개 API에 `findActiveItems`, `findUnitType`을 더했고 `CatalogItemView`에 단위 종류, LOT 관리 여부, 구매 단위, 환산율을 더했다
  - Stage B(2026-10-18): `QualityServiceImpl`, `InspectionStandardService`의 production·inventory repository 6줄 → `ProductionRunQuery`(`findRun`, `findRecordings`), `LotQuery`, `StockRecordQuery`(`findLotStockIds`). 동결 19 → 13. `ProductionRunView`에 대상 품목, `LotView`에 상태와 만든 실행, `StockIdentity`에 위치를 더했다
  - Stage B(2026-10-18): `ProductionRunCorrectionServiceImpl`의 inventory repository 3줄 → `StockQuery`(`quantityMovedBy`, `availableQuantity`)와 `LotQuery`. `MaterialRequirementService`, `RunMaterialUsageService`의 bom repository 3줄 → 새 `BomStructureQuery`. 동결 13 → 7. 남은 7줄은 project ↔ workflow와 project → user다
  - ADR-012(2026-10-18, 2eq): 남은 7줄 → `WorkflowAccessService`와 공개 API `WorkflowAccess`, `ProjectWorkflowCommand`, `UserQuery`. 동결 0줄, freeze 해제
- ArchUnit(테스트 의존성 1.5.1)과 `ModuleBoundaryTest`가 있다. 동결 목록은 2026-10-18에 0줄이 되어 지웠다([ADR-012](adr/ADR-012-workflow-access-boundary.md)). 이제 테스트는 동결 없이 모든 위반에서 실패한다.
- (2026-10-19) 단계 C 규칙 `entitiesStayInsideTheirBoundedContext`가 더해졌다. 다른 도메인 엔티티 의존 39줄을 `src/test/resources/archunit_store`에 동결하고 새 의존은 실패한다([ADR-018](adr/ADR-018-entity-boundary.md)). repository 규칙은 그대로 동결 없이 실패한다.
- (2026-10-25) 엔티티 의존을 0줄로 줄여 동결을 풀었다([ADR-018](adr/ADR-018-entity-boundary.md)). 두 규칙 모두 동결 없이 실패한다.
- 포트의 Item은 선택이다(V42, 2026-10-03). 생성 API의 `itemId`는 선택이고, 수정 API의 `clearItem: true`가 연결을 지운다. Item 없는 포트에 생산 실행의 투입·산출을 기록하면 400이다(`itemId does not match the selected processIoId.`). `quantity`·`unit`은 여전히 필수다.
- `ProcessIo` 필드: `itemId`, `ioName`, `direction`, `ioType`, `role`, `resourceType`, `quantity`, `unit`, `formula`, `schemaJson`, `validationRule`, `colorScheme`, `requiredYn`, `allowShortageYn`. `resourceType`은 자유 문자열이고 비우면 `ioType`(기본 `material`)이 들어간다. 세션 DB의 포트 14개는 모두 `material`이었다. `Item`에도 `resourceCategory`·`resourceType` 열이 있지만 포트의 `resourceType`과는 별개다.
- Flow Run은 발행 revision을 고정하고, Step·Attempt·Event를 기록하며, 수동·그래프 단계 라우팅, 연결 조건·용량, 포트 스키마·검증식, stop/skip/retry 정책을 가진다. **노드를 스스로 실행하지는 않는다.** 외부가 단계를 `start`/`complete`하며 결과를 보고해야 진행된다. 상세: [포트·연결 계약](../domain/process-port-connection-contract.md).
- 기능별 구현 현황은 [CURRENT_CAPABILITIES.md](../status/CURRENT_CAPABILITIES.md)가 기준이다.

## 5. 원문 대비 보완 (2026-10-02)

원문에도 같은 내용을 `> 2026-10-02 수정/보완`으로 표시해 두었다.

1. **Organization/Project 문장 교체**(사용자): 원문의 "Organization은 최상위 소유/Tenant 경계"를 A1 문장으로 바꿨다(§11, §14, Invariant 1).
2. **FlowRun 문장 추가**(사용자): E1 문장(§40, Invariant 5).
3. **데이터 흐름 PoC 범위 정정**: Flow Run에는 Transform을 수행할 실행기가 없다. (a) 외부 실행자가 결과를 보고하는 통합 테스트는 지금 가능하며 "Core가 제조 독립적인가"를 이것으로 증명한다. (b) 노드 종류별 실행기는 별도 ADR로 정한다(§45).
4. **조직 역할과 프로젝트 권한**: Phase 1~3에서 조직 역할은 프로젝트 접근에 영향을 주지 않는다(§12).
5. **resourceType 호환**: 목록 밖 값은 경고로 처리한다(§30).
6. **마이그레이션 번호**: V41은 이미 쓰였다. 조직 마이그레이션은 V42 이후다(§52).
7. **2026-10-02 코드의 Rule 1 위반 4줄**: 동결하지 말고 먼저 고친다(§57). 같은 날 `CatalogQuery`로 고쳤다.

## 6. 다음 작업 순서

각 단계 전에 최신 main과 마이그레이션 번호를 확인한다. 브랜치 생성·커밋·푸시는 사용자만 한다.

| 순서 | 작업 | 완료 기준 |
|---|---|---|
| 1 | **완료(2026-10-02).** [ADR-001 Organization/Project 경계](adr/ADR-001-organization-project-boundary.md), [ADR-002 모듈 의존 규칙](adr/ADR-002-module-dependency.md), [ADR-003 Resource·Port 계약과 실행 Core](adr/ADR-003-resource-port-contract.md). 목차는 [adr/README.md](adr/README.md). | 3개 파일 작성, 상태 Accepted |
| 2 | **완료(2026-10-02).** catalog `application.publicapi.CatalogQuery`(`findProjectItem`·`findItems`, 불변 `CatalogItemView`)와 flowrun `application.publicapi.FlowRunCommand`(`startLinkedRun`·`finishLinkedRun`, `LinkedRun`). 실제로 쓰는 연산만 만들었고, 원문 예시의 `failLinkedRun`은 호출하는 곳이 없어 만들지 않았다. `BomTree.perProductUnit`도 Item 대신 단위 id를 받는다. | 해당 직접 참조 0줄, 기존 테스트 전부 통과 |
| 3 | **완료(2026-10-02).** [`ModuleBoundaryTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)(ArchUnit 1.5.1). 위반 단위는 "클래스 X가 다른 도메인의 repository R을 쓴다"이다. 시그니처와 줄 번호를 빼서, 무관한 수정으로는 동결 목록이 깨지지 않는다. 기존 위반 100건은 `src/test/resources/archunit_store/`에 동결했고, 고친 위반은 자동으로 빠진다. 임시 위반 클래스를 넣어 실패하는 것을 확인했다. | 새 위반에서 테스트 실패, 기존 위반 목록 고정 |
| 4 | **완료(2026-10-02).** [`ResourceTypes`](../../flowmat_backend/src/main/java/org/myweb/flowmat/domain/workflow/domain/contract/ResourceTypes.java). 목록 밖 값은 검증 API의 `RESOURCE_TYPE_UNKNOWN` 경고로만 보이고 발행은 막지 않는다. 처음에는 `labor`를 넣었으나 같은 날 의사결정자 검토로 뺐다(실행 요건이지 포트로 흐르는 대상이 아님). 포트 편집 화면 I/O Type에 `labor`가 남아 있어 그 포트는 경고를 받는다. 값 목록은 ADR-003의 Experimental registry다. | 기존 그래프 저장·발행이 그대로 통과 |
| 5 | **완료(2026-10-03).** V42(`process_io.item_id` NOT NULL 해제), 생성 API `itemId` 선택·수정 API `clearItem`, [`DataFlowRunIntegrationTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/DataFlowRunIntegrationTest.java)(File → Transform → Data, 새 프로젝트, `actual`). `quantity`·`unit`은 여전히 필수라 데이터 포트는 `quantity 0`·`ea`를 쓴다. 결정 8의 "목표 → 검증됨" 전환은 사용자 결정 대기(§3). | Item·Inventory·LOT·BOM·ProductionRun 행 없이 Flow Run 성공, Step·Attempt·Event 기록, 스키마 위반 시 검증 실패 경로 확인 |
| 6 | 조직 Phase 1~3(ADR-001이 수용 기준 4개를 닫고 Accepted가 된 뒤, 5번의 마이그레이션 다음 번호): `organization`·`organization_member`·`project.organization_id`(NULL) → 개인 조직 backfill → NOT NULL 검토. | 전환 전후로 모든 사용자의 프로젝트 접근 결과가 같음(권한 회귀 테스트) |

## 7. 작업 전 질문과 금지 규칙

- 새 기능이나 리팩토링 전에 원문 §53의 14개 질문에 답한다. 특히 다음 세 가지다. Core인가 Domain인가? 다른 bounded context의 Repository를 직접 참조하는가? 이 공통화는 의미가 같아서인가, 이름만 비슷해서인가?
- 원문 §54의 금지 목록(대형 도메인 패키지, Resource 슈퍼테이블, `project_id` 일괄 치환, microservice, MQ, 기존 참조 전수 리팩토링, 에디터 전면 교체, 제조 기능의 Core 승격)은 별도 ADR과 실사용 근거 없이 하지 않는다.
- 재검토 조건은 원문 §51을 따른다.
