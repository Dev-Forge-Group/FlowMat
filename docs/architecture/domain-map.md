# 도메인 지도

> **현행 문서** · 모듈·공개 API·의존 규칙 확인 2026-11-09(코드 `f33a5bd`), 모듈 카탈로그 2026-11-13 · 패키지나 책임이 바뀌면 같은 변경에서 고친다. 기능별 상세 상태는 [CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md)가 기준이다.
> 이전 판(2026-09-24): [archive/2026-09-architecture/enterprise-domain-map.md](../archive/2026-09-architecture/enterprise-domain-map.md)

백엔드 `flowmat_backend`의 bounded context와 각자의 책임, 서로의 의존 규칙을 한곳에 모은다. 결정의 근거는 [ADR-002](adr/ADR-002-module-dependency.md)(모듈 의존)와 [ADR-003](adr/ADR-003-resource-port-contract.md)(Core와 도메인 확장)이다. 기능별 구현 여부는 [CURRENT_CAPABILITIES](../status/CURRENT_CAPABILITIES.md)가 기준이다.

## 1. Bounded context

모두 `org.myweb.flowmat.domain.<context>` 아래에 있고 `api`·`application`·`domain`·`repository`로 나뉜다. 다른 context는 `application.publicapi`로만 쓴다(ADR-002). 2026-11-07부터 context마다 Gradle 모듈 `flowmat_backend/modules/<context>`에 있다. `user`와 `global`(websocket 제외)은 `modules/platform`에 함께 있다([ADR-035](adr/ADR-035-gradle-modules.md)).

| 구분 | context | 책임 | 주요 엔티티 | 공개 API(`application.publicapi`) |
|---|---|---|---|---|
| Core 정의 | `workflow` | 공정 그래프 정의, 포트·연결 계약, 검증, 발행 revision. 하위 `editor`(도형 편집 문서), `collab`(실시간 동기화), `annotation` | Workflow, Process, ProcessIo, ProcessConnection, WorkflowRevision, ProcessTemplate, WorkflowTemplate | `WorkflowAccess`, `WorkflowRunQuery`, `WorkflowProductionQuery`, `WorkflowProcessOrder`와 공개 view |
| Core 실행 | `flowrun` | 범용 실행 기록: 실행·단계·시도·이벤트, 그래프 라우팅과 실패 정책 | FlowRun, FlowRunStep, FlowRunStepAttempt, FlowRunEvent | `FlowRunCommand`(연결 실행 시작·종료) |
| Core 규칙 | `rule` | 실행 규칙 정의와 평가 | FlowRule | `FlowRuleEngineService`(실행 전 규칙 검사, 2026-11-08) |
| 외부 연동 | `connector` | HTTP 프로필·판, 매핑, 요청 실행·취소 | ConnectorProfile, ConnectorProfileVersion | `ConnectorProfileQuery`, `ConnectorMappingQuery`, `ConnectorHttpGateway`, `CancellableConnectorHttpGateway`와 실행 계약 |
| 제조 기준정보 | `catalog` | 품목, 단위·환산, 설비, 설비 달력·정지·전환, 프로젝트 휴일 | Item, UnitMaster, Equipment, EquipmentSchedule(달력 판), EquipmentCalendar, EquipmentDowntime, EquipmentChangeover, ProjectHoliday | `CatalogQuery`(품목 조회, 단위 코드 확인, 설비 조회, 가장 이른 가용 구간, 전환 시간), `UnitConversion`(품목 단위 환산)·`ItemStatusRule`(품목 상태 규칙, 2026-11-08) |
| 제조 계획 | `bom` | BOM 작성·승인·revision, 전개·역전개·원가 누적 | BomHeader, BomLine | `BomQuery`, `BomRevisionQuery`, `BomStructureQuery`, `BomOutputQuery`와 공개 view·`BomRequirement` |
| 제조 재고 | `inventory` | 재고 행, 거래 원장, LOT·계보, 위치, 창고 작업, 재고 경보 | Inventory, InventoryTransaction, LotMaster, LotTrace, StorageLocation, WarehouseTask, StockAlert | `LotQuery`(LOT 조회), `StockQuery`(원장 이동량, 가용 수량) (2026-10-03), `StockMovementCommand`(재고 이동)·`LotTraceCommand`(LOT 계보) (2026-11-07~08) |
| 제조 실행 | `production` | 작업지시, 생산 실행과 투입·산출, 보정 전표, 재고 할당, 작업 지침, 준비 점검, 간이 MRP, 설비 부하 | WorkOrder, WorkOrderItem, ProductionRun, ProductionRunItem, RunStateSnapshot, ProductionRunCorrection, StockAllocation, WorkInstruction, RunInstructionCheck | `WorkOrderQuery`(작업지시 조회) (2026-10-03). 실행 조회는 quality가 정의한 `ProductionRunQuery`를 구현한다(§4) |
| 제조 품질 | `quality` | 검사·불량, 검사 기준, 부적합·시정 조치 | QualityInspection, DefectLog, InspectionStandard, Nonconformity, CorrectiveAction | `InspectionStandardQuery`, `NonconformityQuery`, `RunInspectionCommand`. 조회 포트 `ProductionRunQuery`는 production이 구현한다(§4) |
| 구매 | `purchasing` | 공급처·품목별 조건, 발주·승인·입고·반품·공급처 대변 | Supplier, SupplierItem, PurchaseOrder, PurchaseOrderLine | `PurchaseSupplyQuery`(주문 중 공급 조회) |
| 인력·근무 | `workforce` | 작업자 달력·순환 근무표, 근태, 인건비 단가·급여 계산 | WorkerShift, WorkerRota, WorkerAttendance | `WorkerCalendarQuery`, `WorkerAttendanceQuery`, `WorkerLaborRateQuery`, `WorkerPayQuery`와 공개 view |
| 플랫폼 | `project` | 조직 metadata, 프로젝트 데이터 격리·접근권한 집행 경계, 멤버·초대 | Organization, OrganizationMember, Project, ProjectMember, ProjectInvite | `ProjectMemberQuery`, `ProjectAccess`, `ProjectCalendarQuery`, `ProjectWorkflowCommand` |
| 플랫폼 | `user` | 계정, 인증, 역할·권한, 로그인 이력(`modules/platform`) | User, Role, RolePermission, UserRole, SocialAccount, UserLoginHistory | `UserQuery`, `UserView` |
| 플랫폼 | `notification` | 앱 안 알림, 읽음 상태, 프로젝트별 구독 | Notification, NotificationSubscription | `NotificationCommand`, `NotificationSubscriptionQuery`, `NotificationSubscriptionView` |
| 플랫폼 | `payment` | 요금제·구독·결제의 기본 API(`PlanController`, `PaymentController`) | Plan, PlanPricing, Subscription, Payment, Coupon, Promotion | 없음 |

`global`(보안·설정·예외·ID)과 `batch`(정기 작업)는 bounded context가 아니다.

이 표는 손으로 고치는 설명이라 늦을 수 있다. 모듈 목록, 모듈 사이 의존, 공개 API가 있는지, 데이터 소유는 테스트가 코드·DB와 대조하는 [`modules.yml`](../../modules.yml)이 기준이다(§6).

Organization은 [ADR-001](adr/ADR-001-organization-project-boundary.md)에 따라 project 안에 구현돼 있고, 공급처는 purchasing이 소유한다. 독립 Site 엔티티와 CRM context는 없다. 보관 위치의 `site` 계층은 inventory의 `StorageLocation` 모델이다. `domain/erp`·`mes`·`scm`·`wms` 같은 대형 패키지는 만들지 않는다.

## 2. Core와 도메인 확장

[ADR-003](adr/ADR-003-resource-port-contract.md) 결정 10을 따른다.

- **Core**: `workflow`·`flowrun`·`rule`. 업종 개념을 넣지 않는다. 포트의 의미는 `resourceType`과 `schemaJson`으로 나타내고, `itemId`는 Catalog와 연결될 때만 쓰는 선택적 binding이다.
- **제조·업무 확장**: `catalog`·`bom`·`inventory`·`production`·`quality`·`purchasing`·`workforce`. 재고 원장의 변경은 inventory의 명령 계약을 통해 수행한다.
- **외부 연동**: `connector`. workflow가 실행 binding을 정의하고 flowrun이 공개 HTTP 실행 계약을 사용한다.
- **플랫폼**: `project`·`user`·`payment`·`notification`.
- FlowRun의 범용성은 비제조 vertical slice로 검증될 때까지 목표다(ADR-003 결정 8).

## 3. 기업 시스템 개념과의 대응

FlowMat은 아래 제품을 각각 복제하지 않는다. 필요한 개념만 기존 context에 넣는다.

| 개념 | FlowMat에서 맡는 곳 | 지금 있는 것 | 남은 범위·경계 |
|---|---|---|---|
| ERP 기준정보 | `catalog` | 품목 상세·상태·CSV, 단위 환산, 설비 정보 | 거래처 전체 모델과 독립 사업장은 없음. 공급처는 purchasing에 있음 |
| ERP 재고 회계 | `inventory`, `bom`, `catalog` | 재료비·재고 금액, 원가 누적, 품목 단가 이력 | 다중 통화·총계정원장 없음 |
| MRP / SCM 계획 | `bom`, `production`, `purchasing` | 다단계 전개, 간이 MRP, 만들 수 있는 양, 재주문 목록, 발주 예정 공급·부족 시점 | 범용 공급망 계획 전체를 구현한 것은 아님 |
| 구매 | `purchasing` | 공급처·단가, 단계별 승인, 발주·입고·반품·대변, 예정 공급. 입고·반품은 inventory 공개 명령([ADR-027](adr/ADR-027-purchasing.md)) | 발주서 전송 등 상세 잔여 범위는 [구매 계약](../domain/purchasing.md) 기준 |
| MES 실행 | `production`, `flowrun`, `workforce` | 작업지시, 실행 기록, revision 고정, 보정, 작업 지침, 준비 점검, 필요한 인력과 실행 작업자 기록 | 범용 작업 자동 실행기는 없음 |
| APS / 용량 | `catalog`, `production`, `workforce` | 설비·인력 달력, 전환 시간, 부하표, 한 건·여러 건의 일정 제안, 전환 시간 합을 줄이는 순서 제안 | 납기·부하 균형을 함께 보는 최적화는 남음 |
| WMS | `inventory` | 위치 목록, 이동, 할당, Putaway·Pick, 실사, 휴대 기기용 스캔 화면 | 위치 용량 모델 없음 |
| QMS | `quality`, `notification` | 검사·불량, 검사 기준, NCR·CAPA, 효과 확인, 담당·기한 알림 | 상세 검증 범위는 [현재 기능 기준선](../status/CURRENT_CAPABILITIES.md) 기준 |
| BPM / Workflow | `workflow`, `flowrun`, `rule`, `connector` | 정의·발행·검증, 실행 단계·시도·이벤트, 조건 라우팅, 시간 제한·재시도·동시 실행 제한, 명시적 HTTP 단계 실행 | BPMN·범용 자동 실행기·예약 반복 실행은 없음 |

## 4. 의존 규칙과 지금의 위반

- 다른 context의 repository를 직접 쓰지 않는다. 새 위반은 [`ModuleBoundaryTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)가 막는다.
- 다른 context의 `domain.entity`도 쓰지 않는다. 2026-10-19에 동결한 39줄은 2026-10-25에 모두 없어져, 새로 쓰면 바로 실패한다([ADR-018](adr/ADR-018-entity-boundary.md)).
- 다른 context의 웹 계층(`api`) 타입과 entity 밖의 `domain` 타입(enum 등)도 쓰지 않는다. 함께 쓰는 타입은 `publicapi`에 두고, 상태는 view의 코드와 메서드로 넘긴다. 예외는 flowrun이 실행하는 workflow의 정의 언어(`PortSchema`, `ConditionExpression`)다([ADR-036](adr/ADR-036-cross-context-contract-types.md)).
- 다른 context의 `application`도 `publicapi`로만 쓴다. 여러 context가 쓰는 접근 검사(`ProjectAccess`)·단위 변환(`UnitConversion`) 같은 서비스도 마찬가지다([ADR-036](adr/ADR-036-cross-context-contract-types.md) 결정 6).
- 위반은 2026-10-03에 84건이었고 동결해 두었다. 그때 많은 방향은 다음과 같았다.
  - inventory → catalog 20
  - production → catalog 16
  - production → inventory 12
  - production → workflow 6
  - production → bom 5
  - quality → catalog 5
- 2026-10-18에 마지막 7건까지 없애고([ADR-012](adr/ADR-012-workflow-access-boundary.md)) 동결을 풀었다. 이제 위반이 하나라도 생기면 테스트가 실패한다.
- Stage B(위반이 있는 파일을 고칠 때 공개 API로 바꾸기)는 2026-10-18에 끝났다(ADR-002).
- ADR-002에 남아 있던 다른 context의 `application` 직접 접근 허용은 ADR-036 결정 6으로 대체됐다. `UnitConverter`·`ProjectAccessService` 같은 구현 대신 `UnitConversion`·`ProjectAccess` 공개 계약을 사용하며, 위반은 테스트가 바로 막는다.
- context 사이 의존은 한 방향만 둔다(2026-11-07부터 `ModuleBoundaryTest`가 순환을 막는다). 각 context를 따로 Gradle 모듈로 뗄 수 있게 하기 위해서다. 지금 가능한 순서는 다음과 같다. 오른쪽 context는 왼쪽을 쓸 수 있고, 왼쪽은 오른쪽을 모른다.

  `user` < `project` < `notification` < `catalog` < `bom`·`connector` < `workflow` < `flowrun`·`rule` < `inventory`·`payment` < `quality` < `purchasing`·`workforce` < `production`

- 왼쪽 context가 오른쪽 context의 기능이 필요하면, 왼쪽 context가 인터페이스를 정의하고 오른쪽 context가 구현한다(`ItemUsageCheck`와 같은 방식). 이 인터페이스는 정의한 context의 `application.publicapi`에 둔다([ADR-036](adr/ADR-036-cross-context-contract-types.md) 결정 6). 2026-11-07에 순환을 만들던 의존 3개(catalog → inventory, inventory → production, quality → production)를 이렇게 끊었다.

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

## 6. 모듈 카탈로그와 데이터 묶임

저장소 루트의 [`modules.yml`](../../modules.yml)이 모듈 카탈로그다. 모듈마다 책임, 경로, 의존(`depends_on`), 공개 계약이 있는지, 외래키로 가리키는 모듈(`data.foreign_keys_to`), 추출 상태(`status`)와 후보 저장소를 적는다. 실행 설정이 아니라 설명과 검증의 기준이다.

- [`ModuleCatalogTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleCatalogTest.java): 모듈 목록은 `settings.gradle`, `depends_on`은 각 모듈의 `build.gradle`, `contract`는 `publicapi` 패키지와 같아야 한다. 적힌 경로가 있어야 하고, 후보 저장소는 `repositories`에 있어야 한다.
- [`ModuleCatalogDataTest`](../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleCatalogDataTest.java): 마이그레이션을 모두 적용한 DB에서 본다. 테이블은 그것을 매핑한 엔티티의 모듈이 소유한다. 테이블마다 소유 모듈이 하나이거나 `unowned_tables`에 있어야 하고, 모듈 사이 외래키는 `data.foreign_keys_to`와 같아야 한다.
- 그래서 모듈을 더하거나, 모듈 의존을 바꾸거나, 다른 모듈의 테이블로 외래키를 새로 걸면 같은 변경에서 `modules.yml`을 고쳐야 테스트가 통과한다.

2026-11-13에 확인한 데이터 묶임(V133까지, 테이블 135개)은 다음과 같다.

- 123개 테이블은 소유 모듈이 하나씩 있다. 나머지 12개는 V1이 만들었지만 엔티티도 쓰는 코드도 없고 행도 없다(`unowned_tables`).
- 외래키로 이어진 테이블 쌍은 127개다. 84개는 한 모듈 안, 22개는 `project`·`platform`으로 가고, 21개가 업무 모듈 사이다.
- 업무 모듈 사이 외래키는 `production`·`inventory`·`quality`·`flowrun`·`workflow`·`catalog`·`connector` 일곱 모듈을 한 덩어리로 묶는다. DB를 모듈별로 나누려면 이 외래키부터 없애야 한다.
- 그중 넷은 코드 의존과 반대 방향이다. 코드에서는 `production`이 위에 있지만, 테이블은 아래 모듈이 `production`을 가리킨다.

  | 가리키는 테이블 | 가리켜지는 테이블 | 코드 의존 |
  |---|---|---|
  | flowrun `flow_run` | production `production_run` | production → flowrun |
  | inventory `warehouse_task` | production `stock_allocation`, `work_order` | production → inventory |
  | quality `nonconformity` | production `production_run` | production → quality |

  `production`도 `inventory`를 가리키므로(`stock_allocation` → `inventory`·`lot_master`) 두 모듈의 테이블은 서로 묶여 있다.
- `bom`·`purchasing`·`rule`·`payment`·`platform`의 테이블은 다른 모듈로 가는 외래키가 없다. 다른 모듈의 행은 id 값으로만 가리킨다. 외래키가 없다고 독립인 것은 아니다. id로 가리키는 행의 정합성과, 같은 트랜잭션에서 부르는 공개 명령(예: 구매 입고의 재고 반영)은 따로 봐야 한다.

후보 저장소(`candidate_repository`)는 [분리 후보](extraction-candidates.md)의 묶음을 따르며, 제안이고 결정이 아니다. `status`가 `internal`인 모듈의 원본은 이 저장소이고, 후보 저장소에 같은 코드를 두지 않는다.
