# ADR-018: 단계 C — 다른 도메인의 엔티티를 쓰지 않는다

작성일: 2026-10-18

## 상태

**Accepted (2026-10-19). 줄여 가기 끝(2026-10-25): 0줄, 동결을 풀어 새 의존은 바로 실패한다. 로컬(커밋 전).** 사용자 지시 "8. ADR-018 Accepted Stage C baseline 측정 ArchUnit freeze rule 추가 신규 cross-domain Entity dependency 금지"(2026-10-19)로 규칙을 켜는 시점을 "동결로 먼저"로 정했다. 이 지시는 2026-10-18 초안(조사와 설계만)의 권장안 3("0곳이 된 뒤 켠다")과 이번 주 사전 결정(WORKBOARD §3 "Stage C: 이번 주는 의존 현황 조사와 설계까지")을 대신한다. **줄여 가기(2026-10-24~):** 사용자가 2026-10-24 줄여 가기를 시작하라고 했고 시작점은 리드에게 맡겼다. 권장안 2의 순서대로 2gl–2gr에서 39줄을 **0줄**로 줄였고, 결정 4대로 동결을 풀었다(2gs): 다른 도메인 엔티티를 쓰면 `ModuleBoundaryTest`가 바로 실패한다. 동작은 바꾸지 않는 리팩터이고 단계마다 전체 회귀가 통과했다(2gl–2gp 2,141건, 2gq–2gs 2,140건, 2gt 2,143건(314클래스) 실패·오류·제외 0). 받는 쪽이 없어진 Facts(json) API는 모두 지웠다(2gp·2gq·2gt).

근거: [ADR-002](ADR-002-module-dependency.md)(모듈 의존, "Stage C는 별도 결정"), [ADR-012](ADR-012-workflow-access-boundary.md)(단계 B 끝, 동결 목록 0줄), [주간 계획](../../status/WEEK-PLAN-2026-10-19.md) "다음 기능 후보".

## 결정 (2026-10-19)

| # | 결정 | 누가 |
|---|---|---|
| 1 | 규칙: `ModuleBoundaryTest.entitiesStayInsideTheirBoundedContext`. `domain.<문맥>`의 클래스가 다른 문맥의 `domain.entity` 클래스에 의존하면 위반이다. 바이트코드의 의존(import한 타입, 전체 이름으로 쓴 타입, 필드·매개변수·반환 타입, 메서드 호출, `Item.class` 같은 참조)을 본다. `global`·`batch`는 단계 A처럼 보지 않는다 | 사용자(2026-10-19) |
| 2 | 동결: 2026-10-19의 39줄을 `src/test/resources/archunit_store`에 동결한다(ArchUnit `FreezingArchRule`). 새 의존은 바로 실패하고, 고친 줄은 다음 실행에서 저장소에서 빠진다(목록은 줄기만 한다). `archunit.properties`의 `allowStoreCreation=false`라 저장소가 없으면 모두 다시 동결하지 않고 실패한다(ADR-002 단계 A와 같은 설정) | 사용자(2026-10-19) |
| 3 | 한 줄은 "최상위 클래스 uses 엔티티"다. 안쪽 클래스(`$1`, 안의 record)는 바깥 클래스로 세고 시그니처·줄 번호는 넣지 않아, 줄만 옮긴 수정은 동결 목록을 바꾸지 않는다. repository 규칙(단계 B, 동결 없음)도 같은 조건을 쓴다 | 사용자 사전 위임(2026-10-18) |
| 4 | 줄여 갈 때의 방식은 A(가진 쪽의 공개 view), 순서는 권장안 2다. 0줄이 되면 동결을 풀고 바로 실패하게 바꾼다(ADR-012처럼) | 사용자 사전 위임(2026-10-18) |
| 5 | 흐름 규칙의 사실(`RuleEvaluationContext.facts`)에는 가진 쪽의 엔티티 대신 가진 쪽이 만든 읽기 전용 지도를 넣는다. 지도는 예전 엔티티의 JSON 속성 그대로다(이름·값이 같다). 규칙 경로(`item.storageCondition` 등)는 사용자가 쓴 것이라 view로 줄이면 view에 없는 속성의 규칙이 조용히 꺼진다. 첫째는 품목(`CatalogItemRuleFactsQuery`, 2gm). 작업흐름·공정·재고 사실도 같은 방식으로 옮긴다 | 리드(2026-10-25, 사전 위임 "새 설계는 권장안으로") |

## 기준선 (2026-10-19, `86949f0`)

| 쓰는 쪽 → 가진 쪽 | ArchUnit 줄 | import |
|---|---|---|
| production → inventory | 11 | 11 |
| inventory → catalog | 8 | 8 |
| production → catalog | 6 | 6 |
| production → workflow | 5 | 4 |
| production → bom | 4 | 3 |
| workflow → project | 2 | 2 |
| bom → catalog | 1 | 1 |
| inventory → project | 1 | 1 |
| quality → project | 1 | 1 |
| 합계 | **39줄(21클래스)** | 37곳(20파일, 2026-10-18과 같음) |

import 없이 잡힌 2줄: `ProductionPlanningReferences`가 `Workflow`를 전체 이름(`org.myweb.flowmat.domain.workflow.domain.entity.Workflow`)으로 쓰고, `WorkOrderBomSelection`이 다른 클래스가 돌려준 `BomHeader`의 메서드를 import 없이 부른다. import 세기로는 보이지 않는 결합이라 규칙은 바이트코드로 본다.

확인(격리 복사본에서만): quality에 catalog `Item`과 `ItemRepository`를 쓰는 탐침 클래스를 넣으면 두 규칙 모두 실패하고 저장소는 바뀌지 않는다. 탐침을 지우면 통과한다.

## 지금 (2026-10-18 조사)

다른 도메인의 `domain.entity`를 import하는 곳은 **37곳, 20파일**이다. 조사하면서 쓰이지 않던 import 2줄(flowrun → `Workflow`, inventory → `UnitMaster`)은 지웠다.

| 쓰는 쪽 → 가진 쪽 | import | 파일 |
|---|---|---|
| production → inventory | 11 | 6 |
| inventory → catalog | 8 | 8 |
| production → catalog | 6 | 6 |
| production → workflow | 4 | 2 |
| production → bom | 3 | 3 |
| workflow → project | 2 | 2 |
| bom → catalog | 1 | 1 |
| inventory → project | 1 | 1 |
| quality → project | 1 | 1 |

엔티티로는 catalog `Item`이 가장 많고(15), inventory `Inventory`·`LotMaster`, project `Project`, bom `BomHeader`, workflow `Workflow`·`Process`·`ProcessIo`가 뒤를 잇는다.

원인은 셋이다.

| # | 원인 | 곳 |
|---|---|---|
| R1 | Facts(json) 공개 API 4개(`CatalogItemFactsQuery`, `StockFactsQuery`, `BomPlanningQuery`, `WorkflowProductionQuery`)가 엔티티를 JSON으로 넘기고, 받는 쪽이 그 JSON을 가진 쪽의 엔티티 클래스로 되돌린다(`readFacts(…, Workflow.class)`, `mapper.readValue(…, Item.class)`) | production, inventory 대부분 |
| R2 | `ProjectAccessService.requireProjectReadAccess`·`requireProjectWriteAccess`가 `Project` 엔티티를 돌려준다 | workflow 2, inventory 1, quality 1 |
| R3 | catalog의 품목 삭제 검사 SPI `ItemUsageCheck.whyInUse(Item)`가 엔티티를 넘긴다 | bom 1, inventory 1 |

R1은 가진 쪽이 엔티티 필드를 바꾸면 받는 쪽이 컴파일은 되지만 조용히 빈 값을 읽을 수 있다. 단계 B가 막은 repository 사용과 같은 종류의 결합이다.

## 선택지

| 안 | 내용 | 좋은 점 | 나쁜 점 |
|---|---|---|---|
| A. 가진 쪽의 공개 view | Facts(json) 대신 가진 도메인이 typed view record를 준다(단계 B와 같은 방식). catalog에는 이미 `CatalogItemView`가 있다 | 필드가 바뀌면 컴파일에서 드러난다. 단계 B와 같은 모양 | 공개 API를 더 만든다 |
| B. 받는 쪽의 record | 받는 쪽마다 필요한 필드만 담은 record로 JSON을 읽는다 | 가진 쪽은 그대로 | JSON 모양을 받는 쪽이 알아야 하고, 필드가 바뀌어도 드러나지 않는다 |
| C. 그대로 둔다 | 문서로만 막는다 | 할 일이 없다 | 결합이 남는다 |

## 권장안

1. 방식은 **A**.
2. 순서는 작은 것부터다. (1) R2: 접근 검사는 확인만 하고, 프로젝트 값이 필요하면 project 공개 API에서 읽는다(4곳) (2) R3: `ItemUsageCheck`가 `CatalogItemView`를 받는다(2곳) (3) inventory → catalog: `CatalogItemFactsQuery`를 `CatalogQuery` view로(8파일) (4) production: bom·workflow·catalog·inventory(나머지). 가장 큰 파일은 `ProductionRunServiceImpl`이다.
3. 규칙: `ModuleBoundaryTest`에 "다른 도메인 `domain.entity` import 금지"를 **0곳이 된 뒤** 켠다. 동결 목록은 다시 만들지 않는다(ADR-012에서 지웠다). (2026-10-19 정정: 사용자 지시로 동결을 먼저 켰다. 결정 1·2)
4. 각 단계는 동작을 바꾸지 않는 리팩터다. 단계마다 전체 회귀를 돌린다. 받는 쪽이 모두 옮겨 간 Facts(json) API는 지운다.
5. 시점: 이번 주(10-19–10-25) 범위 밖이다. 다음 주 이후, 사용자가 정한 때 시작한다. (2026-10-19: 동결은 켰고, 줄여 가기의 시작은 아직 정하지 않았다. 2026-10-24: 사용자가 시작을 지시했다)

## 수용 기준 (2026-10-19)

- [x] 기준선: 39줄(21클래스), import 37곳(20파일)
- [x] 규칙을 켜는 시점: 동결로 먼저(사용자, 결정 1·2)
- [x] 새 의존 금지: 탐침이 실패하고 저장소는 그대로
- [x] 방식: A, 순서: 권장안 2(사용자 사전 위임, 결정 4)
- [x] 줄여 가기 끝: 0줄, 동결 해제(2026-10-25, 2gr·2gs)
- [x] 받는 쪽이 모두 옮겨 간 Facts(json) API 지우기(2gp·2gq·2gt)

## 줄여 가기 (2026-10-24~)

| 단계 | 바꾼 것 | 동결 줄 | 확인(격리본) |
|---|---|---|---|
| 2gl (10-24) | R2: 접근 검사가 돌려준 `Project`를 쥐지 않는다(workflow 2, inventory 1, quality 1). 검사는 그 id로 찾았으므로 요청의 id를 쓴다. R3: `ItemUsageCheck.whyInUse(CatalogItemView)`(bom 1, inventory 1) | 39 → 33 | 관련 백엔드 30클래스 |
| 2gm (10-25) | inventory → catalog: `InventoryCatalogReferences`가 `CatalogQuery` view를 준다(같은 저장소 조회·필터·순서). view에 `safetyStockQty`·`lotReleaseRequiredYn`을 더했다. 규칙 사실은 결정 5(`CatalogItemRuleFactsQuery`) | 33 → 26 | 컴파일, `ModuleBoundaryTest`, `InventoryServiceImplTest`, `OpenRunInputsTest`, `FlowRuleExpressionEvaluatorTest`. 새 `ItemRuleFactsIntegrationTest`는 Docker 대기 |
| 2gn (10-25) | production → catalog: 같은 방식(`ProductionPlanningReferences`, `ProductionRunServiceImpl`, `RunInputAllocationService`, `StockAllocationService`, `WorkOrderReadinessService`, `WorkOrderServiceImpl`). 실행 시작·투입 기록 규칙의 `item`도 결정 5 | 26 → 20 | 컴파일, `ModuleBoundaryTest`, 생산 단위 테스트 5클래스(규칙 사실 확인 1건 추가) |
| 2go (10-25) | production → bom: 새 `BomRevisionView`와 `BomRevisionQuery.findActive`(예전 Facts와 같은 조회, 접근 검사는 부르는 쪽). `ProductionPlanningReferences`, `WorkOrderBomSelection`, `WorkOrderReadinessService`, `WorkOrderServiceImpl` | 20 → 16 | 컴파일, `ModuleBoundaryTest`, 생산 단위 테스트 전부(11클래스) |
| 2gp (10-25) | production → inventory: 새 `StockView`·`StockViewQuery`(같은 조회, 같은 잠금과 다시 읽기), `LotView`에 `expiryDate`·`receivedAt`·`isExpiredOn`. 투입 기록 규칙의 `inventory`는 새 `StockRuleFactsQuery`(결정 5). `ProductionPlanningReferences`, `ProductionRunServiceImpl`, `RunInputAllocationService`, `StockAllocationService`, `UsableStock`, `WorkOrderReadinessService` | 16 → 5 | 컴파일, `ModuleBoundaryTest`, 생산 11·inventory 3 단위 클래스(재고 규칙 사실 확인 1건 추가), 새 `BomRevisionQueryFindActiveTest` |
| 2gq (10-25) | Facts API 정리: `CatalogItemFactsQuery`·`StockFactsQuery`와 구현을 지우고, 그 API의 회귀 테스트를 view와 규칙 사실로 옮겼다(새 `StockViewRegressionIntegrationTest` 7건: 다른 트랜잭션 뒤 다시 읽기, 버전 없는 바뀜도 보임, 부르는 쪽 커밋까지 잠금, 트랜잭션 없이 잠그면 거절, 정밀도·날짜, 한 번에 읽기). 품목 일괄 조회의 쿼리 수는 1건과 12건이 같아야 한다(단위도 읽어 상수 3) | 5 → 5 | `ModuleBoundaryTest`, `StockViewRegressionIntegrationTest`, `FactsProxySerializationIntegrationTest`, `ProductionReferenceFactsIntegrationTest`, `ItemRuleFactsIntegrationTest` |
| 2gr (10-25) | production → workflow: workflow 새 공개 조회 `WorkflowRunQuery`(`WorkflowRunView`·`ProcessRunView`·`ProcessIoRunView`, 각 view에 규칙 사실 지도). 같은 조회·같은 잠금. 게시 개정 스냅숏 항목을 view로 바꾸는 일도 workflow로 옮겼다. `ProductionPlanningReferences`, `ProductionRunServiceImpl`, `WorkOrderServiceImpl` | 5 → 0 | 컴파일, `ModuleBoundaryTest`, 생산 단위 11클래스 |
| 2gs (10-25) | 결정 4: 동결을 풀었다(`ModuleBoundaryTest`의 `FreezingArchRule`, `archunit_store/`, `archunit.properties`). 탐침으로 바로 실패하는지 확인 | 0 | 탐침 실패·통과, 2gq–2gs 전체 백엔드 2,140건(313클래스) 실패·오류·제외 0 |
| 2gt (10-25) | `WorkflowProductionQuery`에서 `Facts`와 작업흐름·공정·포트 조회를 지웠다(개정 조회만 남음). 작업 지시의 공정 이름은 `WorkflowRunQuery`로. 프록시·"규칙이 보던 그대로" 검사는 run view의 규칙 사실로 옮기고, 스냅숏 항목 읽기 단위 테스트(`WorkflowRunQuerySnapshotTest`)를 더했다 | 0 | 관련 48클래스 408건, 전체 백엔드 2,143건(314클래스) 실패·오류·제외 0 |
| 2gu (10-25) | 후속: 실행 기록이 게시 개정 스냅숏 JSON을 직접 읽던 일을 workflow로 옮겼다(`WorkflowRunQuery.revisionProcess`·`revisionProcessIo`, `ProductionRunServiceImpl`에서 JSON 파싱과 `ObjectMapper`가 빠짐). 오류 담당의 `WorkflowRunViewRegressionIntegrationTest`가 쓰는 `snapshotProcess`·`snapshotProcessIo`(JsonNode)는 그대로 둔다 | 0 | 관련 30클래스 206건, 전체 백엔드 2,144건(314클래스) 실패·오류·제외 0 |

- 남은 줄: 0(2gr). 마지막 5줄은 production → workflow였다. 실행은 게시된 개정 스냅숏 항목을 `Process`·`ProcessIo` 엔티티로 읽어 규칙 사실에 넣었다. 이제 스냅숏 항목을 view로 바꾸는 일(schemaJson 객체를 글자로 두는 처리 포함)은 workflow의 `WorkflowRunQuery.snapshotProcess`·`snapshotProcessIo`가 한다. 포트·연결 담당의 스냅숏 서비스(`WorkflowRevisionService`·`WorkflowRevisionSnapshot`)는 건드리지 않았다.
- 스냅숏 결합도 걷었다(2gu): production은 게시 개정의 스냅숏 글을 열지 않고 `WorkflowRunQuery.revisionProcess`·`revisionProcessIo`에 넘긴다. 항목 찾기와 필드 이름(`processes`·`processIos`)은 workflow만 안다. 실행 쪽의 확인 순서와 오류 문구는 그대로다. 달라진 것은 하나: 깨진 스냅숏 항목은 예전에는 확인 뒤, 이제는 찾을 때 `IllegalStateException`이 된다(정상 데이터에서는 같다).
- Facts API: 받는 쪽이 없어진 `BomPlanningQuery`(2gp), `CatalogItemFactsQuery`·`StockFactsQuery`(2gq)를 지웠다. 그 API만 보던 테스트의 검사는 옮겼다: 잠금·다시 읽기·정밀도·쿼리 수는 view로(`StockViewRegressionIntegrationTest`), 프록시와 "규칙이 보던 그대로"는 규칙 사실로(`FactsProxySerializationIntegrationTest`, `ProductionReferenceFactsIntegrationTest`는 규칙 사실 지도가 예전 경로 entity → JSON → 사본 → 지도와 같은지 본다). `WorkflowProductionQuery`의 작업흐름·공정·포트 조회와 `Facts` record도 지웠다(2gt). 그 API는 개정 조회(`Revision`)만 남고 production·flowrun이 쓴다. 작업 지시의 공정 이름도 `WorkflowRunQuery.findProcess`로 읽는다(`ProcessRunView`에 `projectId`·`processName`). 이로써 Facts(json) API는 남지 않았다.
- 2gm의 처음 판은 규칙 사실에도 view를 넣었다. 그러면 view에 없는 품목 속성을 읽는 규칙이 조용히 꺼진다(경로가 풀리지 않으면 평가기는 경로 글자를 그대로 비교한다). 기존 테스트로는 드러나지 않아 결정 5로 고치고 통합 테스트를 더했다.
- 전체 회귀: 2026-10-25 00:36부터 Docker 엔진이 응답하지 않아(C 드라이브가 0.9GB까지 찬 뒤) 단계마다 돌리지 못했다. 사용자가 01:55에 Docker Desktop을 다시 시작한 뒤 2gl–2gp를 한 번에 돌렸다: 백엔드 2,141건(313클래스) 실패·오류·제외 0(격리본, 02:08). 2gq는 관련 통합 5클래스.

## 결정하지 않은 것

- ~~줄여 가기(엔티티를 view로 바꾸는 치환)를 시작할 때~~ 2026-10-24 사용자가 시작을 지시했다(시작점은 리드 재량)
- api 계층(DTO)의 엔티티 사용 정리
- 테스트 코드의 다른 도메인 엔티티 사용

## 결과 (권장안대로 할 때)

- 좋은 점: 엔티티 필드를 바꿔도 다른 도메인이 조용히 깨지지 않는다. 모듈 경계가 repository에 이어 엔티티까지 닫힌다.
- 동결(2026-10-19): 새 코드는 다른 도메인 엔티티를 쓸 수 없다. 기존 39줄은 고칠 때 줄이고, 저장소 파일은 줄어든 대로 커밋한다.
- 동결 해제(2026-10-25, 2gs): 0줄이 되어 `FreezingArchRule`을 걷고 `archunit_store/`·`archunit.properties`를 지웠다. 복사본에 quality → catalog `Item` 탐침을 넣으면 규칙이 바로 실패하고(저장소를 다시 만들지 않는다) 탐침을 지우면 통과한다.
- 위험: production 쪽 변경이 크다. 리팩터 중 동작이 바뀌지 않게 단계마다 전체 회귀가 필요하다.
