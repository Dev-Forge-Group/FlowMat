# 재료비와 재고 금액

상태: **구현(2026-09-25).** V1부터 있던 `item.unit_cost` 열을 씁니다. **새 마이그레이션 없음.**

## 단가

- 품목의 **단가(`unitCost`)는 그 품목 자체 단위 1개의 값**입니다. 예를 들어 단위가 kg이면 1 kg의 값입니다.
- 품목 화면의 "Unit cost"에서 입력하고, `POST`·`PUT /items`로 저장합니다.
- 비우거나 0이면 "모름"으로 봅니다.
- 음수는 400입니다.
- `PUT`에서 빠진 값은 바꾸지 않습니다. 화면에서 비우면 0(모름)으로 보냅니다.

## 단가 이력 (2026-10-03, V50)

품목 단가가 바뀔 때마다 `item_cost_history`에 바뀌기 전·뒤 단가, 바꾼 사람·시각을 남깁니다. 이력은 V50부터이고, 그 전의 단가는 알 수 없습니다.

- 모든 품목 저장(화면 수정, 품목 CSV 가져오기, 반제품 원가 누적의 단가 적용)이 `ItemServiceImpl`을 거치므로 그곳에서 남깁니다. 단가가 그대로인 저장(같은 값, 단가를 빼고 보낸 수정)은 남기지 않습니다. 0과 없음은 둘 다 "모름"으로 보아 그 사이 변경도 남기지 않습니다
- `GET /items/{itemId}/cost-history`(읽기 권한): 최신 변경부터 `[{previousUnitCost, unitCost, changedBy, changedAt}]`
- 화면: 품목 **Details**의 **Unit cost history**(최근 5건, `unknown → 2`, `2 → 3.5` · 바꾼 사람 · 시각)
- 완료 실행은 아래 D+ 계약에 따라 마감 시각의 이력 단가를 쓴다. 현재 재고 금액은 현재 단가, 실사 차이 금액은 오늘 단가와 실사 당시 이력 단가를 함께 보인다(사용자 결정 2026-10-12, [재고 실사](stock-count.md) CH1–CH4, 2dp). 실행 마감 시점은 [최종 결정](../status/DECISIONS-2026-10-05.md)으로 확정됐다.

## BOM 재료비

`GET /boms/{id}/requirements?quantity=`의 응답에 원가가 붙습니다.

| 필드 | 뜻 |
|---|---|
| `lines[].unitCost` | 재료의 단가(품목 단위당). 모르면 null |
| `lines[].lineCost` | `requiredItemQuantity × unitCost`, 소수 4자리 반올림. 단가를 모르면 null |
| `materialCost` | 아는 줄의 합 |
| `costComplete` | 단가를 모르는 재료가 하나라도 있으면 false |

- 단가는 **재료 자체 단위**로 곱합니다. BOM 줄이 g이고 품목이 kg이면, 먼저 kg으로 바꾼 필요량(`requiredItemQuantity`)에 곱합니다. 소요량 계산과 같은 환산입니다.
- 단가를 모르는 재료를 0으로 치지 않습니다. 합계는 아는 것만 더하고, 화면에 "some materials have no unit cost"를 표시합니다.
- 화면: BOMs 탭 → BOM → "Materials needed to make N"
  - 각 재료 줄에 금액이 나오고, 단가가 없으면 `no cost`로 표시합니다.
  - 마지막 줄은 **Material cost** 합계입니다.
- 실행 시작 때 BOM 스냅샷, 작업지시 준비 점검은 금액 필드를 쓰지 않으며 영향이 없습니다.

## 재고 금액

Stock 탭 맨 위에 "Stock value X"를 보여 줍니다.
- 계산: 보유량이 있는 행마다 **보유량 × 품목 단가**를 더합니다.
- 단가 없는 품목의 행은 0으로 치지 않고, "N records not counted"로 따로 셉니다.
- 브라우저에서 계산하며(`stockModel.stockValue`), 서버에는 저장하지 않습니다.

## 실행 재료비

`GET /production-runs/{id}/cost` — 프로젝트 읽기 권한
- **대상 기록:** 취소되지 않았고 실제 수량이 있는 **투입** 기록만 셉니다.
- **계산:** 각 기록을 품목 자체 단위로 바꿔(3000 g → 3 kg) 품목별로 더하고, 단가를 곱합니다.
- **모르는 비용:** 단가가 없거나 단위를 바꿀 수 없는 품목은 금액을 null로 두고 `costComplete=false`로 표시합니다.
- **단위당 원가:** `costPerUnit = materialCost ÷ 실제 생산량`입니다. 비용이 완전하고 생산량이 있을 때만 나옵니다.
- **저장하지 않습니다.** 조회할 때의 단가로 계산하므로, 단가를 바꾸면 끝난 실행의 금액도 바뀝니다.
  - `production_run.total_material_cost` 같은 열은 실행 서비스를 건드려야 해서 채우지 않았습니다. 처음에는 다른 세션과 같이 쓰는 파일이라서였고, 2026-10-03 기준으로는 그 파일을 고치면 ADR-002 Stage B로 다른 도메인 저장소 6줄을 공개 API로 옮겨야 하기 때문입니다([WORKBOARD](../status/WORKBOARD.md) §4).
- **화면:** 실행 상세 오른쪽의 **Material cost**입니다.
  - 품목별 수량·금액과 합계를 보여 줍니다.
  - 아래에 "Per unit made: X (N made)"를 표시하고, 비용이 불완전하면 안내합니다.
  - 기록이 바뀌면(`['production-run-items', runId]` 새로 고침) 따라 갱신됩니다.

## 사용량 차이 (BOM 대비)

`GET /production-runs/{id}/material-usage` — 프로젝트 읽기 권한. 저장하지 않고 조회 때 계산합니다.
- **계획:** 실행이 시작될 때 BOM에서 만든 계획 행(`quantity_source='bom'`)입니다. 그래서 나중에 BOM을 개정해도 이 실행의 기준은 바뀌지 않습니다.
- **기준(standard):** 계획 × 기준 생산량 ÷ 계획 생산량.
  - 기준 생산량은 실제 생산량이 있으면 그 값(`basisIsActual=true`), 없으면(진행 중) 계획 생산량입니다.
  - 예: 20개 계획에 밀가루 10 kg, 16개를 만들고 끝났으면 기준 8 kg.
- **사용(actual):** 실행 재료비와 같은 기록(취소되지 않은, 실제 수량이 있는 투입)을 품목 단위로 바꿔 더한 값입니다. 단위를 바꿀 수 없는 기록이 있으면 null입니다.
- **차이:** 사용 − 기준, 비율은 차이 ÷ 기준 × 100(소수 2자리). 0보다 크면 BOM보다 많이 쓴 것입니다.
- **BOM에 없는 품목:** 기준 0, 계획 없음, 비율 없음(`inBom=false`).
- **선택 자재(2026-11-02, [ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md) 결정 8):** 작업지시가 고른 선택 자재는 실행 계획에 들어가 다른 BOM 줄과 같다. 고르지 않은 선택 자재를 썼으면 기준 0인 것은 BOM 밖 품목과 같고, `optional=true`로 "optional, not picked"라고 보인다(실행 BOM 자신의 줄만).
- **대체 자재(2026-11-02, [ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md) 결정 8):**
  - 실행 BOM이 승인한 대체 자재는 BOM 안 품목으로 센다(`inBom=true`, `substituteFor` = 주 자재). 화면에는 "substitute for 주 자재"로 나온다.
  - 주 자재의 기준을 묶음이 실제로 쓴 비율로 나눈다. 품목 j의 기준 = 주 자재 기준 × j 사용량 ÷ 묶음 사용량(주 자재로 환산한 합). 아무것도 안 썼으면 주 자재가 기준 전부다.
  - 예: 밀가루 4 kg 기준, 호밀은 2.5 kg이 밀가루 2 kg 몫. 밀가루 2 kg·호밀 2.5 kg을 쓰면 기준은 밀가루 2 kg·호밀 2.5 kg, 차이 0.
  - 실행 BOM 자신의 줄만 본다. 팬텀 BOM 안의 묶음은 아직 BOM 밖으로 보인다.
- **순서:** BOM의 자재 순서(승인된 revision이라 바뀌지 않음), 그다음 BOM에 없는 품목을 코드순. 실행 기록은 ID(무작위) 순으로 읽히므로 따로 정렬합니다.
- **차이 금액:** 차이 × 단가. 전체 합계 `varianceCost`와 `varianceCostComplete`가 있습니다. 차이가 있는데 단가가 없거나 사용량을 모르는 품목이 있으면 불완전입니다.
- **화면:** 실행 상세 **Material cost** 아래의 **Use against BOM vN**입니다.
  - BOM으로 시작한 실행에만 나옵니다.
  - 기준 설명 한 줄(끝난 실행이면 "Made 16 of 20 planned (80%)." 수율 포함), 품목별 Standard / Used / Difference, 차이 금액을 보여 줍니다.
  - 많이 쓴 줄은 주황, 적게 쓴 줄은 파랑입니다. 적게 쓴 것은 기록이 덜 된 것일 수도 있습니다.
  - 실행을 끝내면 새 생산량으로 바로 다시 계산됩니다. 끝내기가 `['production-run-items', runId]`를 새로 고치기 때문이며, 같은 이유로 단위당 원가도 바로 갱신됩니다.

## 줄별 실제 손실 (2026-11-02, [ADR-034](../architecture/adr/ADR-034-bom-scrap-performance.md))

`GET /boms/{bomId}/scrap-performance` — BOM 읽기 권한. 저장하지 않고 조회 때 계산한다. 같은 revision의 끝난 실행을 모아 줄 스크랩률(계획)과 실제 손실을 나란히 보인다.
- **대상:** 그 revision으로 시작해 끝난 물리 실행 가운데 계획·실제 생산량이 0보다 큰 것. revision 자신의 자재 줄에서 온 계획 행만(팬텀을 거친 행 제외, 선택 자재는 계획에 들어간 실행에서만).
- **양(실행마다 실제 생산량에 맞춤):** 계획량 = 계획 행 × 실제 ÷ 계획 생산량, 순량 = 계획량 ÷ (1 + 스크랩률), 실제량 = 취소되지 않은 실제 투입(대체 자재는 주 자재 몫으로).
- **실제 손실률** = 실제량 합 ÷ 순량 합 − 1. 예: 계획 5%, 두 실행에서 순량 6 kg에 6.5 kg을 쓰면 8.33%.
- **화면:** BOMs 탭의 승인·은퇴 revision 아래 **Scrap: planned vs actual**(끝난 실행이 있을 때). 계획보다 0.5%p 넘게 많이 잃으면 주황, 적게 잃으면 초록으로 차이를 보인다.

## 하지 않은 것

- **실행 원가 저장(`production_run.total_material_cost`, `cost_per_unit`):** 조회 때 계산만 하고 열에는 저장하지 않습니다(위 참고).
- **이력 이전의 단가·마감 날짜:** 정확한 근거가 없는 행은 ESTIMATED다. 실행의 D+ 계산은 아래 완료 기록을 참고한다. 실사 과거 금액은 별도 계약을 따른다.
- **통화:** 없습니다. 프로젝트 전체에서 한 통화로 가정합니다.

## 2026-10-05 확정: 실행 원가 D+

**Accepted (정책).** D+ 승인. 완료/보정은 원래 actualEndAt 단가, 진행 중은 현재 단가. 금액은 조회 계산, 마감 시각은 보정으로 변경하지 않는다. 정확한 과거 단가를 모르면 추정 표시. 2bx에서 새 마감 시각의 기록까지 구현했다. 아래 Proposed 메모는 당시 검토 이력이다. [최종 결정](../status/DECISIONS-2026-10-05.md)이 아래 예전 선택지보다 우선한다.

### D+ 1단계: 과거 단가 공개 Query (2026-10-05)

- `catalog.application.publicapi.CatalogUnitCostQuery.findUnitCostsAt(projectId, itemIds, basisAt)`를 구현했다. 품목·이력 각각 일괄 조회하고 다른 프로젝트·없는 품목은 제외한다. 삭제된 품목의 과거 참조는 유지한다.
- basisAt 없음: 현재 단가 CURRENT. 지정 시각 이하 마지막 변경: HISTORICAL. 최초 이력 전: 첫 변경의 previousUnitCost로 ESTIMATED. 이력 없음: 현재 단가 ESTIMATED.
- 단가 0/null은 기존 계약의 모름이며 과거의 모름을 현재 알려진 단가로 대체하지 않는다. 저장 시각이 같은 이력의 가격이 충돌하면 정확한 순서를 알 수 없어 현재 단가 ESTIMATED로 표시한다.
- 공개 API는 호출자가 프로젝트 접근 검사를 한 뒤 사용하는 내부 Query다. 새 HTTP endpoint는 없고, 마감 시각 기록·RunCostService 연결·화면 표시는 다음 단계다. 현재 실행 원가 endpoint는 아직 현재 단가로 계산한다.
- 격리 환경에서 12개 새 테스트의 구현 없음 compile RED를 확인하고 구현 뒤 같은 12개가 GREEN. 추가 경계 단위 3개와 Testcontainers 통합 3개 통과. 전체 백엔드 1,041건 실패·오류·건너뜀 0, 커버리지 기준 통과(라인 81.07%, 분기 67.31%). 새 조회 클래스 라인 100%·분기 95%. 격리 `build -x test`도 통과. 개발 DB는 사용하지 않았다.

## 결정 메모: 실행 원가 저장 시점 (Proposed, 2026-10-03)

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §3 "실행 원가 저장 시점"을 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

**지금 코드 (2026-10-03 확인)**
- `production_run.total_material_cost`·`cost_per_unit`(V1 열)은 `ProductionRun` 엔티티에 매핑돼 있지 않아 한 번도 쓰이지 않았다. 세션 DB의 끝난 실행 29개 모두 비어 있다.
- 마감 시각도 남지 않는다. `actual_end_at`(V1 열)도 매핑되지 않아 비어 있고, `updated_at`은 보정 때도 바뀐다.
- `GET /production-runs/{id}/cost`(`RunCostService`)는 조회 때마다 투입 기록을 품목 단위로 바꿔 **현재 단가**를 곱한다. 보정 전표로 바뀐 투입도 그대로 들어간다.
- 단가 이력(V50)은 2026-10-03부터만 있다. 그 전 단가는 첫 이력 행의 `previous_unit_cost`(그 직전 값)로만 알 수 있다.

| 안 | 무엇을 남기나 | 보정 뒤 | 필요한 일 | 약점 |
|---|---|---|---|---|
| A | 마감 때 그때 단가로 금액을 열에 고정 | 그대로 | 엔티티 매핑, 마감에서 계산·저장 | 보정 뒤 사용량과 금액이 어긋남 |
| B | A + 보정 반영 때 다시 계산, 단가는 마감 시각 기준 | 다시 계산 | A + 마감 시각 기록 + 시각 기준 단가 조회 | 저장 경로가 둘(마감·보정) |
| C | A + 보정 때 다시 계산, 단가는 보정 시각의 현재 단가 | 다시 계산 | A + 보정 서비스에서 재계산 | 보정이 있었던 실행만 금액이 단가 변경을 따라 흔들림 |
| D | 저장하지 않음. 조회 때 계산하되 단가는 마감 시각 기준(진행 중은 현재 단가) | 자동 반영 | 마감 시각 기록 + 시각 기준 단가 조회. 열은 나중에 캐시로 | 조회마다 이력 조회(실행의 품목 수만큼) |

**영향받는 코드**
- `ProductionRun` 엔티티.
- 마감 경로 `ProductionRunServiceImpl`. ADR-002 동결 위반이 많고 규칙 사실(rule fact) 모양 때문에 Stage B가 막혀 있다. 고치면 이 파일의 Stage B 범위를 함께 정해야 한다.
- B·C: `ProductionRunCorrectionServiceImpl`.
- `RunCostService`·`RunMaterialUsageService`(동결 2·3줄이라 고치면 Stage B), `CatalogQuery`에 시각 기준 단가 조회 추가.
- 실사 차이 금액(`InventoryCountHistoryService`): 같은 규칙을 쓰면 일관된다.

**권장안: D**, 보정 줄도 마감 시각 단가로 계산한다.
- 저장한 숫자가 낡지 않고, 보정(실행 보정 Q4: 거래 날짜는 반영 시각)이 자동으로 들어간다.
- 보정은 그 실행이 쓴 양을 바로잡는 것이므로 마감 때 단가로 보는 편이 같은 실행의 재료비로 일관된다.
- 실사 차이 금액에도 "그 시각의 단가" 한 규칙을 쓸 수 있다. 저장 열은 조회가 느려지면 캐시로 채운다.
- 먼저 할 일은 마감 시각 기록이다(`actual_end_at` 매핑과 마감 때 설정, V1 열이라 마이그레이션 없음). 이미 끝난 실행은 마감 시각이 없으므로 현재 단가로 계산하고 응답에 "추정"을 표시한다. V50 전 시각은 첫 이력 행의 `previous_unit_cost`, 이력이 없으면 현재 단가.

## 검증

- 단가 이력(2026-10-03, V50): `ItemCostHistoryIntegrationTest` — 단가 2로 만든 품목 → 같은 2·이름만 수정은 이력 없음 → 3.5 → 0 → 이력 3건(최신부터 3.5→0, 2→3.5, 없음→2, 바꾼 사람), 단가 없이 만든 품목에 0은 이력 없음, 외부인 403, 없는 품목 404. 같은 변경에서 `ItemServiceImpl`·`ItemImportService`가 `InventoryRepository` 대신 새 공개 API `StockQuery.hasStockRecords`를 씀(ADR-002 Stage B, 동결 88 → 86). V50은 세션 DB에서 트랜잭션 안에 먼저 돌려 음수 단가·없는 품목이 거절되는 것을 보고 롤백한 뒤 적용. `itemDetailModel.test.ts` `costChangeText`. 실 화면 `e2e/inventory-reports.spec.ts` 끝: 단가 2로 만든 품목의 **Details** → `unknown → 2`
- `BomCostIntegrationTest` 1건
  - 밀가루 20,000 g / 100 ea, 단가 2/kg → 250 ea에 50 kg, 100
  - 소금은 단가가 없어 null이고 `costComplete=false`, 합계 100
  - 음수 단가 400
  - 소금 단가 0.5를 넣으면 합계 101.25, `costComplete=true`
- `RunCostIntegrationTest` 2건
  - 사용량 차이: BOM 10 ea당 밀가루 5 kg·소금 200 g, 20개 계획.
    - 진행 중에는 기준 10 kg, 11 kg 사용 → +1(10%).
    - 16개로 끝내면 기준 8 kg → +3(37.5%), 금액 6. 소금 0.32 kg 기준에 0.4 kg → 25%, 단가 없음.
    - BOM에 없는 효모 0.1 kg → 기준 0, 비율 없음, 금액 1. 합계 7, 불완전.
  - 3000 g 투입 → 3 kg × 2 = 6
  - 취소한 2 kg 기록은 빠짐
  - 단가 없는 소금 → 불완전, 단위 원가 없음
  - 소금 단가 1을 넣으면 7, 4개에 1.75
  - 외부인 403
- 기존 `BomIntegrationTest` 8건, `WorkOrderReadinessIntegrationTest` 2건 통과
- `stockModel.test.ts`: 재고 금액 18.75, 단가 없는 행 2개
- `usageModel.test.ts` 5건: 차이 표시(부호, 비율, BOM 밖 품목, 모름), 색, 기준 설명, 수율(80%, 105%, 33.3%, 진행 중·계획 없음은 없음)
- 실 화면
  - 품목 수정에서 단가 2 저장
  - BOM 250 ea 계산에서 밀가루 줄 `50 kg (50,000 g) 100`, `Material cost 100`
  - Stock 탭 `Stock value 60 · 8 records not counted`
  - 실행 상세 `butter 0.5 kg 4, flour 5 kg 10, Total 14, Per unit made: 1.4 (10 made)`
  - 콘솔 오류 0
  - 사용량 차이(2026-09-25): 20개 계획 실행에 밀가루 11 kg 기록 → `10 kg / 11 kg / +1 kg (+10%)`, `+2`. 화면에서 16개로 끝내자 새로 고침 없이 `8 kg / 11 kg / +3 kg (+37.5%)`, `+6`. 콘솔 오류 0

### D+ 2단계: 실행 원가 조회·표시 (2026-10-05)

`RunCostService`의 현재 단가 직접 조회를 공개 `CatalogQuery`·`CatalogUnitCostQuery` 일괄 조회로 바꿨다. 기존 V1의 `production_run.actual_end_at` 열을 `ProductionRun.actualEndAt`에 연결했다. 새 마이그레이션·authoritative 금액 저장은 없다.

- 진행 중: `costBasis=CURRENT`, 기준 시각 null. 완료 실행에 원래 종료 시각이 있으면 그 시각 이하 단가 이력으로 계산하고 `costBasisAt`을 반환한다. 이후 단가 수정은 그 시각 원가를 바꾸지 않는다.
- 원래 종료 시각이 없는 완료 실행은 현재 단가로 추정하며 `costBasis=ESTIMATED`, `estimated=true`, `costBasisAt=null`이다. 날짜를 만들어 내지 않는다. 종료 시각은 있어도 특정 품목의 이력이 모자라면 그 줄과 전체가 ESTIMATED다.
- 단가가 과거에 모름(null/0)이었다면 현재 알려진 가격으로 바꾸지 않는다. 정확성 기준(`costBasis`)과 알려진 금액의 완전성(`costComplete`)은 별개다. 단가 미상 또는 환산 불가 행은 합계에서 빠지고 단위 원가가 null이다. 잘못된 과거 다른 프로젝트 품목 참조는 금액·품목 이름을 공개하지 않고 불완전하게 남긴다.
- 화면 **Material cost**에 기준 설명과 줄별 **estimated price**를 표시한다. 옛 API 응답에 기준이 없으면 **Price basis unavailable.**로 표시하며 현재/과거로 추정하지 않는다. 부산물·폐기물 가치는 아직 이 API에 더하거나 빼지 않는다.
- 검증: 신규 통합 6건·기존 원가 통합 3건·모듈 경계 1건 통과. 새 통합은 현재/종료 시각 가격, 이후 가격 변경, 모르는 과거 가격, 종료 시각 누락, 이력 누락, 잘못된 단위·다른 프로젝트 메타데이터를 확인한다. 화면 모델 5건·모의 API 브라우저 3건. 전체 백엔드 1,070건 실패·오류·건너뜀 0, 라인 81.18%·분기 67.52%·커버리지 기준·빌드 통과. 프런트 전체 469건·타입·기존 JS/JSX lint·빌드 통과. ArchUnit이 직접 저장소 참조 2개를 자동 제거해 동결 84→82로 줄였다(생성 결과만 복사, 수동 편집 없음).
- **당시 2bj의 한계(2026-10-05):** 이 시점에는 새 마감 시각 기록이 없었다. 사용자 수정 허가 후 2bx에서 마감 시각 및 소유 도메인 공개 Query로 해결했다. 최신 상태는 다음 절을 따른다.

## 2026-10-09 마감 시각 및 D+ 연결 완료 (2bx)

- 성공한 최초 finish 명령이 서버 UTC `actualEndAt`을 기록한다(V1 기존 열). PostgreSQL microseconds에 맞춰 응답과 재조회가 동일하다. 클라이언트 시각은 받지 않는다. 실패·권한 거절·재마감으로 시각을 고치지 않는다.
- 완료/보정 비용은 원래 마감 시각의 단가로 조회 계산한다. 마감 뒤 품목 단가가 바뀌어도 완료 원가에 반영하지 않는다. 마감 때 모르는 단가는 뒤의 단가로 채우지 않는다. 진행 중은 CURRENT, 날짜 없는 과거 완료 행은 ESTIMATED로 표시하며 임의로 backfill하지 않는다.
- 마감 서비스의 다른 도메인 repository 직접 조회/잠금은 소유 도메인의 공개 Query로 옮겼다. 규칙 fact의 기존 전체 JSON 모양과 audit 값을 보존한다. 공개 Query의 값은 불변 JSON snapshot/record이며 entity를 반환하지 않는다.
- 금액 저장·통화·실제 setup 시간 원가 snapshot은 이 작업에 포함하지 않는다. 기존 D+ 단가 Query의 ESTIMATED 경계는 그대로다.
- 검증: ProductionRunEndTimeIntegrationTest, ProductionReferenceFactsIntegrationTest, RunCostBasisIntegrationTest 및 마감 뒤 단가 변경을 검증하는 RunCostIntegrationTest.
