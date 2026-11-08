# ADR-036: context 사이 계약 타입

작성일: 2026-11-07

## 상태

**Accepted (2026-11-07).** 근거: 사용자가 승인한 DevForge 이전 계획 5단계("bom API DTO를 `publicapi`로 옮긴 뒤 Gradle 모듈로 나눈다")의 남은 부분. [ADR-035](ADR-035-gradle-modules.md)로 context를 모듈로 나눈 뒤 진행했다.

**후속 (2026-11-08).** 사용자 결정("포트만 publicapi로"): 결정 6을 더했다. 공유 서비스는 그대로 둔다.

## 배경

- [ADR-002](ADR-002-module-dependency.md) Stage A와 [ADR-018](ADR-018-entity-boundary.md)은 다른 context의 repository와 entity만 막는다. 웹 계층 타입과 enum은 규칙 밖이었다.
- 그래서 모듈로 나눈 뒤에도 다음이 남아 있었다.
  - production·inventory가 bom의 웹 응답 DTO(`BomRequirementResponse`, `BomResponse`, `BomLineResponse`)와 `BomService`·`BomTree`를 직접 썼다.
  - production이 bom의 `BomStatus`를 썼다.
  - production·quality가 inventory의 `InventoryCommandService`·`InventoryMovement`와 `InventoryTransactionType`·`LotStatus` enum을 썼다.
- 이 상태에서는 bom의 HTTP 응답이나 inventory 내부 enum을 바꾸면 다른 context의 빌드가 깨진다. 저장소를 나누려면 이것들이 계약 타입이어야 한다.

## 결정

1. 다른 context는 한 context의 웹 계층(`api`) 타입을 쓰지 않는다. 함께 쓰는 타입은 `application.publicapi`에 둔다. 웹 API는 publicapi 타입을 그대로 응답해도 된다. HTTP 응답과 계약이 같은 타입이므로 JSON 모양은 바뀌지 않는다.
   - bom: `api.dto.response.BomRequirementResponse` → `application.publicapi.BomRequirement`, `BomResponse` → `BomView`, `BomLineResponse` → `BomLineView`.
2. 다른 context는 bom을 publicapi로만 읽는다.
   - `BomQuery`: `listBoms`, `getBom`, `calculateRequirements`, `requirementsForRun`. `BomService`가 이를 확장한다.
   - low-level code는 `BomStructureQuery.lowLevelCodes`, 줄 유형은 `BomLineView.material()`.
3. 상태는 publicapi view에 코드(문자열)로 넘긴다. inventory의 `LotView.lotStatus`가 이미 이렇게 하고 있었다. enum은 각 context의 `domain.enums`에 그대로 둔다. 다른 context가 필요한 판단은 view의 메서드로 둔다.
   - `BomView.approved()`, `BomRevisionView.approved()`·`retired()`
   - `LotView.usable()`·`quarantined()`·`closed()`
4. 다른 context의 재고 이동은 publicapi `StockMovementCommand`(`apply`, `reverseMovementsOf`)로만 한다.
   - `Kind`에는 다른 context가 실제로 하는 이동 6가지만 둔다: `RESERVE`, `RELEASE`, `PRODUCTION_INPUT`, `PRODUCTION_OUTPUT`, `ADJUSTMENT`, `QUARANTINE`.
   - 각각 이름이 같은 `InventoryTransactionType`으로 기록된다. 구현은 `InventoryCommandService`다.
5. [`ModuleBoundaryTest`](../../../flowmat_backend/src/test/java/org/myweb/flowmat/architecture/ModuleBoundaryTest.java)에 두 규칙을 더한다.
   - 다른 context의 `api` 패키지 사용 금지.
   - 다른 context의 `domain` 타입 사용 금지. entity는 ADR-018 규칙이 따로 본다.
   - 예외는 workflow의 정의 언어(`workflow.domain.contract`의 `PortSchema`, `workflow.domain.expression`의 `ConditionExpression`)다. flowrun은 workflow가 정의한 그래프를 그대로 실행하므로, 이 둘을 workflow가 공개한 언어로 본다.

6. (2026-11-08) 다른 context는 한 context의 `application`을 그 `publicapi`로만 쓴다.
   - 아래 context가 정의하고 위 context가 구현하는 포트도 publicapi에 둔다: catalog `ItemUsageCheck`·`ItemStockCheck`, inventory `PickWorkOrders`·`WorkOrderAllocations`, quality `ProductionRunQuery`와 그 view `ProductionRunView`·`ProductionRunRecordingView`. 이름과 메서드는 그대로이고 패키지만 옮겼다.
   - 예외는 여러 context가 함께 쓰는 서비스다: project `ProjectAccessService`, catalog `UnitConverter`·`ItemStatusRule`·`EquipmentScheduleService`, rule `FlowRuleEngineService`·`RuleEvaluationContext`·`RuleTarget`, production이 쓰는 inventory `LotService`. `ModuleBoundaryTest`가 이 목록 밖의 사용을 막는다. 목록에 더하려면 이 ADR에서 정한다.

## 결정하지 않은 것

- 결정 6의 공유 서비스를 publicapi 뒤로 옮길지. 옮기려면 인터페이스를 publicapi에 두고 구현은 그대로 두는 방식이 된다.
- workflow의 정의 언어를 publicapi나 별도 공유 모듈로 옮길지.

## 검증

- `ModuleBoundaryTest`의 새 두 규칙이 통과한다. production에 bom `BomStatus`와 bom 웹 DTO를 쓰는 클래스를 임시로 넣으면 두 규칙이 모두 실패한다.
- `StockMovementKindTest`: 모든 `Kind`가 같은 이름의 `InventoryTransactionType`이다.
- 결정 6의 규칙이 통과한다. production에 bom `BomTree`를 쓰는 클래스를 임시로 넣으면 실패한다.
- 백엔드 전체 테스트와 커버리지 기준.
