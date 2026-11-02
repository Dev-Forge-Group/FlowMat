# 열린 작업지시의 자재 소요 (간이 MRP)

상태: **구현(2026-09-25).** 새 테이블·마이그레이션 없음. 읽기 전용이며 아무것도 예약하지 않습니다.

## 목적

작업지시 하나의 준비 점검([Readiness](work-order-readiness.md))을 넘어, **열린 작업지시 전체**가 앞으로 쓸 자재를 합쳐 지금 쓸 수 있는 재고와 비교합니다. 무엇을 얼마나 더 들여와야 하는지 한 번에 봅니다.

## API

`GET /material-requirements?projectId=` — 읽기 권한

- `orders`: 셈에 들어간 작업지시 수
- `lines[]`: 자재별 필요량(`required`), 쓸 수 있는 양(`usable`), 부족량(`shortage` = 필요 − 쓸 수 있는 양, 0 미만 없음), 필요로 하는 작업지시(`orders[]`: 제목·필요량). 부족량이 큰 순서
- `problems[]`: BOM 소요량을 계산하지 못한 작업지시와 이유(목록에서 빠짐)

## 계산 규칙

| # | 규칙 | 근거 |
|---|---|---|
| M1 | 대상: **승인(approved)·진행 중(in_progress)**, BOM과 목표 수량이 있는 작업지시 | 준비 점검의 `acceptsRuns`와 같음 |
| M2 | 남은 수량 = 목표 − 그 작업지시의 **끝난 실행**들의 실제 생산량. 0 이하면 뺌 | 준비 점검의 남은 수량과 같음 |
| M3 | 필요량 = BOM 소요량 계산(`requirementsForRun`)을 남은 수량으로 한 값, 자재 단위, 자재별 합 | 계약서 §5와 같은 반올림 |
| M4 | 쓸 수 있는 양 = 활성 재고 행의 가용량(보유 − 예약) 합. 격리 행, 종료·만료 LOT 행 제외 | 재발주 목록·준비 점검과 같은 기준 |
| M5 | 끝나지 않은 실행(pending·running)이 이미 기록한 투입(계획 행·취소 제외, 자재 단위로 환산)은 그 작업지시의 필요량에서 뺌(0 미만 없음) | 그 재고는 이미 줄었는데 남은 수량은 아직 그 몫을 세므로, 빼지 않으면 부족이 두 번 잡힘 |
| M6 | 서비스에 트랜잭션을 두지 않음. 소요량 계산 실패는 `problems`로 보고 | 준비 점검과 같음 |
| M10 | **대체 자재**(2026-11-02, [ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md)): 필요량은 주 자재로만 센다. 끝나지 않은 실행이 투입한 대체 자재는 주 자재 몫(주 자재 소요량 ÷ 대체 자재 소요량)으로 M5처럼 뺀다 | 계획은 주 자재, 대체는 사람이 고른다(결정 5·6) |
| M11 | **선택 자재**(2026-11-02, [ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md)): 각 작업지시의 필요량은 그 작업지시가 고른 선택 자재를 넣는다(팬텀 안 포함). 고르지 않은 선택 자재는 넣지 않는다. 하위 반제품 전개(작업지시 없음)는 선택 자재를 넣지 않는다 | 결정 6·7 |

## 화면

재고 → Stock 탭, 재발주 목록 아래 **Open work order needs**
- 제목 줄: "N open work orders: K materials short"(빨강) 또는 "all M materials covered"
- 부족한 자재가 있으면 표가 펼쳐져 있고, 없으면 "Show all materials"로 펼칩니다. 열: 자재, 필요, 쓸 수 있는 양, 부족(자재에 [구매 단위](item-details.md)가 있으면 아래에 올림한 구매 단위 수, 예 `2 bag`), 필요로 하는 작업지시(제목과 양)
- 계산하지 못한 작업지시는 "Not counted: …"로 알려 줍니다.
- **Download CSV**(2026-09-26): 구매 목록 `work-order-needs-YYYY-MM-DD.csv`, 열 `item_code,item_name,unit,needed,usable,short,purchase_unit,packs,item_status,work_orders,being_made,made_here,left_after,safety_stock,under_safety,on_order`(`being_made`·`made_here`는 [다단계 BOM](multi-level-bom.md), `left_after`·`safety_stock`·`under_safety`는 아래 "주문 뒤 남는 양", 마지막 `on_order`는 [구매 발주](purchasing.md) PO17). 모든 자재(부족 큰 순), 부족분을 구매 단위로 올린 수(`packs`, 부족이 없거나 구매 단위가 없으면 빈 칸), 품목 상태(active가 아니면 재주문하지 않음), 필요로 하는 작업지시(`Bread 30; Buns 10`). `stockAlertModel.test.ts`의 `needsCsv`. 실 화면(2026-09-26): 열린 작업지시 15건에서 받은 파일 BOM 포함, 헤더와 15줄, 첫 줄 `FLR-…,…,kg,50,30,20,,,active,Readiness … 50`, 콘솔 오류 0
- **Draft a purchase order for N short material(s)**(2026-10-24, 2fp): 부족한 사 오는 자재(자기 BOM 없음, 활성)를 구매 단위로 올린 양으로 초안 발주서 하나에([구매 발주](purchasing.md) PO12). 반제품은 위 **Draft N work orders for short sub-assemblies**
  - 2026-10-24 2ga부터 품목의 평소 공급처마다 초안 하나(예정일은 납기로), 평소 공급처가 없는 줄은 물어본 공급처로([구매 발주](purchasing.md) PO30)
- **Runs short** 열(2026-10-24, 2fy, 맨 끝): 작업지시를 계획 시작일 순으로, 반제품 작업지시가 만드는 양은 그 계획 종료일에(2gi, 응답 줄의 `supplies[]`: 작업지시·남은 양), 주문 중인 발주를 예정일에 넣어 처음 모자라는 날·작업지시·양. 합계로는 덮여도 발주가 늦게 오면 주황으로 보이고 제목 끝에 ` · N short before … arrive`([구매 발주](purchasing.md) PO25)
- 열린 작업지시가 없으면 숨깁니다. 재고가 움직이면 새로 고쳐지고, 탭을 열 때마다 새로 받아 작업지시 변경도 반영됩니다.
- 반제품이 부족하면 **Draft N work orders for short sub-assemblies**(2026-10-03): 모든 단계의 부족한 반제품을 초안 작업지시로 한 번에([다단계 BOM](multi-level-bom.md) "모든 단계 한 번에")

## 주문 뒤 남는 양 (2026-10-03, 커밋 전)

프런트만. 서버 응답은 그대로입니다. [재고 경보](stock-alert.md) "이후"의 "부족 예상 경보"를 이 표에서 답합니다: 지금은 안전재고 위라 재주문 목록에 없지만, 열린 작업지시를 다 만들고 나면 안전재고 밑으로 내려가는 자재.

| # | 규칙 | 근거 |
|---|---|---|
| M7 | 남는 양 = 쓸 수 있는 양 + 예정 공급 − 필요(0 미만 없음, 소수 넷째 자리). 모자라는 부분은 이미 부족량 | 부족량과 같은 세 값 |
| M8 | 품목 안전재고가 있고(0 초과) 남는 양이 그보다 작으면 "안전재고 밑", 그 차이를 보여 줌. 안전재고가 없으면 표시 없음 | 재주문 목록의 안전재고 기준과 같음 |
| M9 | 제목 줄의 "N left under safety stock"은 **부족하지 않은** 자재만 셈 | 부족한 자재는 이미 "K materials short"로 셈 |

- 표에 **Left after** 열(쓸 수 있는 양 + Being made − Needed). 안전재고 밑이면 주황으로 `4 under safety 8`
- 제목 줄 끝에 ` · 1 left under safety stock`(주황). 부족은 없고 안전재고 밑만 있어도 표가 펼쳐짐
- CSV 끝 열 `left_after`, `safety_stock`(없으면 빈 칸), `under_safety`
- 검증: `stockAlertModel.test.ts`의 `leftAfterOrders`(10 − 6 → 4, 안전재고 8에 4 밑, 예정 공급 5면 9, 부족이면 0과 안전재고 전부, 소수, 안전재고 없음·0)와 `needsCsv` 끝 세 열. 가짜 API E2E `e2e/multi-level-bom.spec.ts`: 설탕 필요 6·쓸 수 있는 10·안전재고 8 → 제목 `3 materials short · 1 left under safety stock`, 설탕 줄 Left after `4`와 `4 under safety 8`

## 검증

- `MaterialRequirementIntegrationTest` 1건: BOM 10 ea당 밀가루 5 kg·소금 200 g. 승인한 작업지시 20·10, 초안 100 → 밀가루 필요 15, 쓸 수 있는 12, 부족 3, 두 작업지시(초안 제외). 소금 필요 0.6, 부족 0. 첫 작업지시의 실행이 밀가루 4 kg을 투입하면 재고 8, 첫 작업지시 필요 6, 합계 11, 부족 3 그대로. 외부인 403
- 실 화면(2026-09-25): 목표 20 작업지시(밀가루 5 kg/10 ea)와 밀가루 재고 3 → 해당 줄 `10 kg / 3 / 7 / MRP order … 10`. 콘솔 오류 0

## 지금 만들 수 있는 양

상태: **구현(2026-09-26).** 새 테이블·마이그레이션 없음. 읽기 전용.

BOM 하나를 두고 "지금 재고로 제품을 얼마나 만들 수 있나"와 "어느 자재가 먼저 떨어지나"를 답합니다.

`GET /boms/{bomId}/buildable` — 그 BOM 프로젝트의 읽기 권한

- `targetUnit`: 제품 단위. `baseQuantity`(BOM 한 배치가 만드는 양)와 모든 만들 수 있는 양이 이 단위
- `buildable`: 자재별 만들 수 있는 양 중 가장 작은 값. 자재가 없는 BOM이면 `null`
- `limitingItemId`: 가장 먼저 떨어지는 자재
- `lines[]`: 자재, 자재 단위, 한 배치 필요량(`perBatch`), 쓸 수 있는 양(`usable`), 그 자재만 봤을 때 만들 수 있는 양(`buildable`)

`GET /boms/buildable?projectId=` — 읽기 권한. 프로젝트의 **승인된** BOM 전부를 같은 방식으로, 재고는 한 번만 읽어 계산합니다. 자재나 단위가 사라져 계산하지 못한 BOM은 실패시키지 않고 `problem`에 이유를 담아 돌려줍니다(나머지는 계속).

| # | 규칙 | 근거 |
|---|---|---|
| B1 | 자재별 만들 수 있는 양 = 쓸 수 있는 양 ÷ 한 배치 필요량 × 배치 크기 | 한 배치 필요량은 BOM 소요량 계산을 배치 크기로 한 값. 1개 기준으로 구하면 아주 작은 필요량이 반올림으로 사라짐 |
| B2 | 쓸 수 있는 양은 M4와 같음(격리 행, 종료·만료 LOT 행 제외, 보유 − 예약, 0 미만 없음) | `UsableStock` 하나를 간이 MRP와 함께 씀 |
| B3 | 내림. 제품 단위가 개수(`count`, 예 `ea`)면 정수로, 아니면 소수 넷째 자리까지 | 마지막 한 개의 일부는 만들 수 없음 |
| B4 | 필요량이 반올림으로 0이 된 자재는 제한하지 않음(`buildable` null) | 0으로 나누지 않음 |
| B5 | 열린 작업지시가 이미 쓸 몫은 빼지 않음 | 이 BOM만 놓고 본 답. 여러 작업지시의 합은 위 간이 MRP가 답함 |
| B6 | 한 배치 필요량은 줄 스크랩률을 곱한 값이고, 대체 자재 줄과 선택 자재 줄은 세지 않는다(주 자재·필수 자재로 계획) | [ADR-031](../architecture/adr/ADR-031-bom-scrap-rate.md) 결정 4, [ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md) 결정 5, [ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md) 결정 7. 2026-11-02 정정: 그 전에는 팬텀 줄이 있는 BOM만 스크랩을 반영했다 |

화면: 재고 → BOMs 탭 목록에서 승인된 BOM 줄마다 **can make 6 ea**(0이면 빨강, 마우스를 올리면 먼저 떨어지는 자재, 계산 못 하면 "can't tell"과 이유). 초안이 아닌 BOM을 열면 소요량 계산 위에 **Can make now: 6 ea · (자재) runs out first**(0이면 빨강). **Use**를 누르면 그 양으로 소요량을 계산합니다. 소요량 표에 자재마다 `3 in stock`이 붙고, 모자라면 빨강으로 `· 7 short`. 재고가 움직이면 새로 고쳐집니다(`['inventories', projectId]` 아래 키). 품목 **Details**의 BOMs 줄에도 그 품목의 승인된 BOM이 만들 수 있는 양이 붙습니다(`· stock can make 6 ea now (MF-… runs out first)`, 0이면 빨강). 생산 → Work Orders의 작업지시 폼에서 BOM을 고르면 수량 아래에 "Stock can make 6 ea now (… runs out first)."가 나오고, 목표 수량이 그보다 크면 주황으로 "; the rest needs more material."이 붙습니다(초안·승인 대기 BOM도 같은 계산).

검증

- `BuildableQuantityIntegrationTest` 1건: 빵 10 ea당 밀가루 5 kg·소금 200 g, 밀가루 12.4·소금 1 → 24 ea(밀가루 24.8에서 내림), 제한 자재 밀가루, 소금 줄 50. kg 제품(반죽 2 kg당 밀가루 1.5 kg) → 16.5333, 재고 없는 효모를 더하면 0·제한 자재 효모. 빵 BOM 승인 뒤 프로젝트 목록에 24로 나오고 초안 반죽은 없음. 자재 없는 BOM → `null`. 외부인 403(둘 다)
- 간이 MRP·준비 점검 통합 테스트가 `UsableStock`으로 옮긴 뒤에도 통과
- 실 화면(2026-09-26): 목록 줄 `can make 6 ea`(툴팁 `MF-… runs out first`). 10 ea당 밀가루 5 kg, 재고 3 → `Can make now: 6 ea · MF-… runs out first`, Use → 6, 표 `3 kg / 3 in stock`. 20으로 바꾸면 `10 kg / 3 in stock · 7 short`(빨강). 콘솔 오류 0

## 다단계(2026-09-27)

[다단계 BOM](multi-level-bom.md) 이후 열린 작업지시 자재 소요는 **예정 공급**(그 품목을 만드는 승인·진행 중 작업지시의 남은 수량)을 부족에서 빼고, 자기 승인 BOM이 있는 자재(반제품)는 가용·예정 공급으로 모자라는 만큼을 low-level code 순으로 그 BOM의 자재 필요로 전개합니다(`via` 표시). 응답 줄에 `plannedSupply`, `madeHere`, 필요에 `viaItemId`·`viaItemCode`가 붙었습니다. 반제품이 없는 프로젝트의 결과는 전과 같습니다.

승인·진행 중 작업지시에 [할당](stock-allocation.md)된 재고(열린 할당의 남은 양)는 품목별 가용량에 더합니다(2026-09-27). 그 재고는 예약이라 재고 행의 가용에서는 빠지지만, 합계에 이미 들어 있는 그 작업지시들의 필요를 위한 것이기 때문입니다.

## 이후

- 발주(구매) 연계: 구매 도메인은 2026-10-24 [구매 발주](purchasing.md)(2fm)로 생겼다. 주문한 발주의 남은 양은 줄의 `onOrder`로 부족에서 빠지고 화면 **On order** 열(Left after 뒤)에 보인다([구매 발주](purchasing.md) PO8, 2fn). CSV에는 2026-10-24 2fu부터 마지막 열 `on_order`로 나오고(PO17) `left_after`에도 들어간다
