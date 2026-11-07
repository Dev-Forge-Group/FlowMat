# 도메인 지도

> **현행 문서** · 최종 확인 2026-10-02(코드 `45f5df0` + 작업 트리), 의존 방향 2026-11-07 · 패키지나 책임이 바뀌면 같은 변경에서 고친다.
> 이전 판(2026-09-24): [archive/2026-09-architecture/enterprise-domain-map.md](../archive/2026-09-architecture/enterprise-domain-map.md)

백엔드 `flowmat_backend`의 bounded context와 각자의 책임, 서로의 의존 규칙을 한곳에 모은다. 결정의 근거는 [ADR-002](adr/ADR-002-module-dependency.md)(모듈 의존)와 [ADR-003](adr/ADR-003-resource-port-contract.md)(Core와 도메인 확장)이다. 기능별 구현 여부는 [CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md)가 기준이다.

## 1. Bounded context

모두 `org.myweb.flowmat.domain.<context>` 아래에 있고 `api`·`application`·`domain`·`repository`로 나뉜다. 다른 context는 `application.publicapi`로만 쓴다(ADR-002). 2026-11-07부터 context마다 Gradle 모듈 `flowmat_backend/modules/<context>`에 있다. `user`와 `global`(websocket 제외)은 `modules/platform`에 함께 있다([ADR-035](adr/ADR-035-gradle-modules.md)).

| 구분 | context | 책임 | 주요 엔티티 | 공개 API(`application.publicapi`) |
|---|---|---|---|---|
| Core 정의 | `workflow` | 공정 그래프 정의, 포트·연결 계약, 검증, 발행 revision. 하위 `editor`(도형 편집 문서), `collab`(실시간 동기화), `annotation` | Workflow, Process, ProcessIo, ProcessConnection, WorkflowRevision, ProcessTemplate, WorkflowTemplate | 없음 |
| Core 실행 | `flowrun` | 범용 실행 기록: 실행·단계·시도·이벤트, 그래프 라우팅과 실패 정책 | FlowRun, FlowRunStep, FlowRunStepAttempt, FlowRunEvent | `FlowRunCommand`(연결 실행 시작·종료) |
| Core 규칙 | `rule` | 실행 규칙 정의와 평가 | FlowRule | 없음 |
| 제조 기준정보 | `catalog` | 품목, 단위·환산, 설비, 설비 달력·정지·전환, 프로젝트 휴일 | Item, UnitMaster, Equipment, EquipmentSchedule(달력 판), EquipmentCalendar, EquipmentDowntime, EquipmentChangeover, ProjectHoliday | `CatalogQuery`(품목 조회, 단위 코드 확인, 설비 조회, 가장 이른 가용 구간, 전환 시간) |
| 제조 계획 | `bom` | BOM 작성·승인·revision, 전개·역전개·원가 누적 | BomHeader, BomLine | 없음 |
| 제조 재고 | `inventory` | 재고 행, 거래 원장, LOT·계보, 위치, 창고 작업, 재고 경보 | Inventory, InventoryTransaction, LotMaster, LotTrace, StorageLocation, WarehouseTask, StockAlert | `LotQuery`(LOT 조회), `StockQuery`(원장 이동량, 가용 수량) (2026-10-03) |
| 제조 실행 | `production` | 작업지시, 생산 실행과 투입·산출, 보정 전표, 재고 할당, 작업 지침, 준비 점검, 간이 MRP, 설비 부하 | WorkOrder, WorkOrderItem, ProductionRun, ProductionRunItem, RunStateSnapshot, ProductionRunCorrection, StockAllocation, WorkInstruction, RunInstructionCheck | `WorkOrderQuery`(작업지시 조회) (2026-10-03). 실행 조회는 quality가 정의한 `ProductionRunQuery`를 구현한다(§4) |
| 제조 품질 | `quality` | 검사·불량, 검사 기준, 부적합·시정 조치 | QualityInspection, DefectLog, InspectionStandard, Nonconformity, CorrectiveAction | 없음 |
| 플랫폼 | `project` | 데이터 격리·접근권한 집행 경계, 멤버·초대 | Project, ProjectMember, ProjectInvite | `ProjectMemberQuery`(활성 구성원 여부, 2026-10-03). `ProjectAccessService`는 여러 context가 직접 쓴다 |
| 플랫폼 | `user` | 계정, 인증, 역할·권한, 로그인 이력 | User, Role, RolePermission, UserRole, SocialAccount, UserLoginHistory | 없음 |
| 플랫폼 | `payment` | 요금제·구독·결제의 기본 API(`PlanController`, `PaymentController`) | Plan, PlanPricing, Subscription, Payment, Coupon, Promotion | 없음 |

`global`(보안·설정·예외·ID)과 `batch`(정기 작업)는 bounded context가 아니다.

없는 것: Organization·Site·거래처([ADR-001](adr/ADR-001-organization-project-boundary.md)에 따라 아직 만들지 않음), `domain/erp`·`mes`·`scm`·`wms` 같은 대형 패키지(만들지 않음).

## 2. Core와 도메인 확장

[ADR-003](adr/ADR-003-resource-port-contract.md) 결정 10을 따른다.

- **Core**: `workflow`·`flowrun`·`rule`. 업종 개념을 넣지 않는다. 포트의 의미는 `resourceType`과 `schemaJson`으로 나타내고, `itemId`는 Catalog와 연결될 때만 쓰는 선택적 binding이다.
- **제조 확장**: `catalog`·`bom`·`inventory`·`production`·`quality`. 재고 부수효과는 여기서만 일어난다.
- **플랫폼**: `project`·`user`·`payment`.
- FlowRun의 범용성은 비제조 vertical slice로 검증될 때까지 목표다(ADR-003 결정 8).

## 3. 기업 시스템 개념과의 대응

FlowMat은 아래 제품을 각각 복제하지 않는다. 필요한 개념만 기존 context에 넣는다.

| 개념 | FlowMat에서 맡는 곳 | 지금 있는 것 | 없는 것 |
|---|---|---|---|
| ERP 기준정보 | `catalog` | 품목 상세·상태·CSV, 단위 환산, 설비 정보 | 거래처, 사업장(ADR-001 보류) |
| ERP 재고 회계 | `inventory`, `bom` | 재료비·재고 금액, 원가 누적 | 단가 이력, 통화, 총계정원장 |
| MRP / SCM 계획 | `bom`, `production` | 다단계 전개, 간이 MRP, 만들 수 있는 양, 재주문 목록 | ~~구매·발주~~ → 아래 구매(2026-10-24), 리드타임, 공급 계획 |
| 구매 | `purchasing` | 발주서(초안 → 주문 → 줄마다 입고, 닫기·취소), 입고는 inventory 공개 명령([ADR-027](adr/ADR-027-purchasing.md), 2fm) | 공급처 목록, 단가·금액, 승인, 반품, 자재 소요 예정 공급 |
| MES 실행 | `production`, `flowrun` | 작업지시, 실행 기록, revision 고정, 보정, 작업 지침, 준비 점검 | 노드 자동 실행, 작업자 배정 |
| APS / 용량 | `catalog`, `production` | 설비 달력(교대 여러 개)·정지·휴일, 전환 시간, 부하표, 작업지시 계획 기간 제안 | 순서 최적화, 여러 작업지시 한꺼번에 배치 |
| WMS | `inventory` | 위치 목록, 이동, 할당, Putaway·Pick, 실사 | 모바일 스캔 작업, 위치 용량 |
| QMS | `quality` | 검사·불량, 검사 기준, NCR·CAPA | 효과 확인, 알림 |
| BPM / Workflow | `workflow`, `flowrun`, `rule` | 정의·발행·검증, 실행 단계·시도·이벤트, 조건 라우팅 | BPMN, 실행기, 시간 제한 |

## 4. 의존 규칙과 지금의 위반

- 다른 context의 repository를 직접 쓰지 않는다. 새 위반은 [`ModuleBoundaryTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)가 막는다.
- 다른 context의 `domain.entity`도 새로 쓰지 않는다. 2026-10-19의 39줄은 동결돼 있고 줄이기만 한다([ADR-018](adr/ADR-018-entity-boundary.md)).
- 위반은 2026-10-03에 84건이었고 동결해 두었다. 그때 많은 방향은 다음과 같았다.
  - inventory → catalog 20
  - production → catalog 16
  - production → inventory 12
  - production → workflow 6
  - production → bom 5
  - quality → catalog 5
- 2026-10-18에 마지막 7건까지 없애고([ADR-012](adr/ADR-012-workflow-access-boundary.md)) 동결을 풀었다. 이제 위반이 하나라도 생기면 테스트가 실패한다.
- Stage B(위반이 있는 파일을 고칠 때 공개 API로 바꾸기)는 2026-10-18에 끝났다(ADR-002).
- 다른 context의 `application` 클래스(`UnitConverter`, `ProjectAccessService` 등)를 직접 쓰는 것은 아직 막지 않는다(ADR-002 보류 항목).
- context 사이 의존은 한 방향만 둔다(2026-11-07부터 `ModuleBoundaryTest`가 순환을 막는다). 각 context를 따로 Gradle 모듈로 뗄 수 있게 하기 위해서다. 지금 가능한 순서는 다음과 같다. 오른쪽 context는 왼쪽을 쓸 수 있고, 왼쪽은 오른쪽을 모른다.

  `user` < `project` < `notification` < `catalog` < `bom`·`connector` < `workflow` < `flowrun`·`rule` < `inventory`·`payment` < `quality` < `purchasing`·`workforce` < `production`

- 왼쪽 context가 오른쪽 context의 기능이 필요하면, 왼쪽 context가 인터페이스를 정의하고 오른쪽 context가 구현한다(`ItemUsageCheck`와 같은 방식). 2026-11-07에 순환을 만들던 의존 3개(catalog → inventory, inventory → production, quality → production)를 이렇게 끊었다.

  | 정의한 곳 | 인터페이스 | 구현한 곳 | 이전 방식 |
  |---|---|---|---|
  | catalog | `ItemStockCheck`(LOT 관리 전환 전 재고 행 유무) | inventory `StockItemStockCheck` | catalog가 inventory `StockQuery.hasStockRecords` 호출 |
  | inventory | `PickWorkOrders`(피킹할 작업지시 조회) | production `InventoryPickWorkOrders` | inventory가 production `WorkOrderQuery` 호출 |
  | inventory | `WorkOrderAllocations`(작업지시 할당 잠금·이동) | production `WorkOrderAllocationsImpl` | production 공개 API `AllocationTransferCommand` |
  | quality | `ProductionRunQuery`(검사·추적용 실행 조회) | production `ProductionRunQueryImpl` | production 공개 API |

## 5. 새 기능을 어디에 둘지

다음 순서로 묻는다. 답하기 어려우면 구현하지 말고 [결정 인계](decision-handoff.md) §3의 보류 항목인지 확인한다.

1. 정의인가, 계획인가, 실행 기록인가? ([실행 모델](execution-model.md))
2. 업종과 무관한가? 무관하면 Core, 아니면 해당 제조 context.
3. 기존 context 책임 안에 들어가는가? 들어가면 거기에 둔다. 새 context는 책임 경계가 분명할 때만 만든다.
4. 다른 context의 데이터가 필요한가? 필요하면 그 context의 공개 API를 쓰거나 만든다.
5. Project 경계 안의 데이터인가? (지금 모든 업무 데이터는 `project_id`로 격리한다)
