# 재고 경보 (최소·최대 재고)

상태: **구현(2026-09-24).** 출처는 [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md)의 WMS/ERP 재주문점(reorder point) 개념이다. 코드는 가져오지 않았다. V1에 미리 만들어 둔 `stock_alert` 테이블과 빈 `StockAlertBatchService`를 채웠다. **새 마이그레이션 없음.**

## 목적

재고 행마다 이미 있는 최소(`min_threshold`)·최대(`max_threshold`) 값을 기준으로, 벗어난 행을 **경보로 남기고 한곳에서 보여 준다.** 재고 표의 `Low`/`Over` 표시는 지금 상태만 보여 준다. 경보는 **언제 시작해서 언제 풀렸는지** 이력을 남긴다.

## 규칙

| 종류 | 열리는 조건 | 기준값 / 현재값 | 심각도 |
|---|---|---|---|
| `low` | 최소 > 0이고 **가용량 < 최소** | 최소 / 가용량 | 가용량 0이면 `critical`, 아니면 `warning` |
| `over` | 최대가 있고 **보유량 > 최대** | 최대 / 보유량 | `info` |
| `expiry` | 보유량 > 0이고 LOT 유효기한까지 **남은 날 ≤ 경고 기간**(기본 7일, `app.stock-alert.expiry-warning-days`) | 경고 기간(일) / 남은 날(만료 후 음수) | 만료됐으면 `critical`, 아니면 `warning` |

- 계산은 재고 표의 `stockLevel`(Low/Over)과 같다. 격리된 재고도 같은 기준으로 본다.
- 한 행에 종류별로 **열린 경보는 하나**다. 조건이 이어지는 동안에는 같은 경보의 현재값과 심각도만 갱신한다.
- 조건이 풀리면 **자동으로 닫힌다**(`resolved_by = system`). 사람이 닫는 기능은 없다. 조건이 남았는데 닫으면 바로 다시 열려야 하기 때문이다.
- 행을 삭제하면 그 행의 열린 경보는 닫힌다.

## 언제 다시 계산하나

1. **재고 이동 때:** `InventoryCommandService.record`. 모든 이동(입출고, 예약, 격리, 생산 투입·산출, 역분개, 보정)이 이력을 남기는 곳이다. 이동과 **같은 트랜잭션, 같은 행 잠금 안**에서 계산하므로 두 이동이 동시에 경보를 두 개 여는 일이 없다.
2. **재고 행 수정·삭제 때:** 수량이 그대로여도(최소·최대만 바꿔도) 다시 계산한다(`refreshAlerts`).
3. **주기 점검:** `StockAlertBatchService.sweep()`
   - 기본 5분마다, 앱 시작 5분 뒤부터 돈다(`app.stock-alert.sweep-interval`).
   - 기준값이 있는 행, 유효기한이 있는 LOT 재고 행, 열린 경보가 있는 행을 행 잠금 후 하나씩 다시 계산한다.
   - 서비스를 거치지 않고 바뀐 값과, 날짜가 지나며 바뀌는 `expiry` 경보를 따라잡는다.
   - 한 행이 실패해도 나머지는 계속한다.

경보 계산은 재고 이동과 한 트랜잭션이다. 경보 저장이 실패하면 그 이동도 롤백된다. 경보가 빠진 재고 변화가 생기지 않게 하려고 이렇게 했다.

## API

`GET /stock-alerts?projectId=&openOnly=true|false`. 프로젝트 읽기 권한이 필요하다.
- `openOnly=true`(기본): 열린 경보 전부
- `false`: 열린 것과 닫힌 것, 최신 200건

## 화면

재고 → Stock 탭 맨 위
- "N stock alerts" 목록이 나온다. 열린 경보가 먼저고, 그 안에서는 심각도 순이다.
- 열린 경보가 없으면 "No open stock alerts" 한 줄만 보인다.
- **History**를 누르면 닫힌 경보까지 보인다.
- 위치 목록이 있으면 **Alerts at place**를 고를 수 있다(2026-10-03, 프런트만). 그 위치와 안의 위치에 있는 재고 행의 경보만 남긴다(대소문자 무시, `stockAlertModel.alertsWithin`). 제목은 `1 stock alert at WH-A`, `No open stock alerts at LOOSE`처럼 바뀐다. 서버 경보는 지금처럼 재고 행마다라 계산은 그대로다
- 경보의 **Movements**를 누르면 그 재고 행의 이동 이력을 열고 그 위치로 스크롤한다.
- 재고를 바꾸는 화면 동작은 모두 재고 목록(`['inventories', projectId]`)을 새로 고친다. 경보 조회 키가 그 아래(`['inventories', projectId, 'alerts', …]`)에 있어 경보도 함께 새로 고쳐진다.

## DB

V1 `stock_alert`에는 FK·CHECK·유일 제약이 없다. "행·종류별 열린 경보 하나"는 **재고 행 잠금으로 지킨다.** DB 제약으로 올리려면 새 마이그레이션이 필요하다. 후보는 다음과 같다.
- `(inventory_id, alert_type) WHERE resolved_yn = 'N'` 부분 유일 인덱스
- `alert_type`·`severity` CHECK
- `inventory_id` FK

## 검증

- `StockAlertIntegrationTest` 3건
  - 출고로 가용 5 < 최소 10 → `low`/`warning` 열림. 추가 출고로 0 → **같은 경보**가 `critical`로 갱신. 입고로 닫힘(이력에 남음).
  - 최대 초과 → `over`. 폼에서 최소·최대만 바꿔도 다시 계산. 외부인 403.
  - 빈 행에 최소 5 → `critical`, 행 삭제 → 닫힘.
  - DB에 직접 쓴 최소값을 sweep이 따라잡고, 되돌리면 sweep이 닫음.
  - 3일 뒤 만료 LOT 재고 → `expiry`/`warning`(남은 날 3, 기간 7), 어제 만료 → `critical`(-1). 30일 뒤는 경보 없음. 만료 재고를 출고하면 닫힘.
- 기존 `InventoryCommandIntegrationTest` 10건, `InventoryServiceImplTest` 10건 통과
- `stockAlertModel.test.ts` 2건
- 실 화면: 경보 표시 → Movements 클릭 시 이력이 화면에 들어옴 → 입고 후 열린 목록에서 사라지고 History에 closed로 남음. 콘솔 오류 0

## 재주문 목록 (품목 합계 기준)

행별 경보와 별도로, **품목 전체**가 안전재고 밑으로 내려갔는지 본다.
- **안전재고·리드타임:** 품목의 `safety_stock_qty`, `lead_time_days`(V1부터 있던 열)를 품목 화면에서 입력한다. 비우거나 0이면 감시하지 않는다. 음수는 400이다.
- **API:** `GET /stock-alerts/reorder?projectId=`. 읽기 권한이 필요하다.
  - 쓸 수 있는 재고 합계 < 안전재고인 품목만 돌려준다.
  - active가 아닌 품목(inactive·discontinued, [품목 상태](item-status.md))은 새 재고를 받지 않으므로 목록에 넣지 않는다(2026-09-26).
  - 모자란 비율(부족량 ÷ 안전재고)이 큰 순서다.
- **쓸 수 있는 재고:** 활성 행의 가용량(보유 − 예약)을 더한다. 격리된 행, 종료되거나 유효기한이 지난 LOT의 행은 뺀다. 작업지시 준비 점검과 같은 기준이다.
- **저장하지 않는다.** 요청할 때마다 계산하므로 경보 이력은 없다. `stock_alert.inventory_id`가 NOT NULL이라 품목 단위 경보를 그 테이블에 넣을 수 없어서 이렇게 했다.
- **화면:** Stock 탭 맨 위 "N items below safety stock" 표다. 열은 쓸 수 있는 양, 안전재고, 부족량, 리드타임, **Lasts**, **Suggested order**다. 부족한 품목이 없으면 숨긴다.
  - **Lasts:** 최근 30일 소비 속도로 지금 쓸 수 있는 재고가 며칠 가는지([재고 흐름 분석](stock-analysis.md)의 소진 일수). 리드타임보다 짧으면 주황이다. 30일 동안 안 쓰였으면 `not used`.
  - **Suggested order:** 부족량 + 일평균 소비 × 리드타임. 주문이 도착했을 때 안전재고로 돌아가는 양이다. 소비나 리드타임을 모르면 부족량만이다.
  - 품목에 [구매 단위](item-details.md)가 있으면 제안 아래에 `→ 2 bag (50 kg)`처럼 **올림한 구매 단위 수와 실제 주문량**이 보인다.
  - **Order value:** 주문량(구매 단위가 있으면 올린 양) × 품목 단가([재료비](material-cost.md)). 단가가 없거나 0이면 `-`. 표 위에 합계 "suggested orders ≈ N"과 단가 없는 품목 수가 보인다.
  - **Download CSV:** 구매 목록 `reorder-YYYY-MM-DD.csv`, 열 `item_code,item_name,unit,usable,safety_stock,short,lead_time_days,daily_use,suggested_order,unit_cost,order_value`(모르는 값은 빈 칸).
  - **On order**·**Draft purchase order**(2026-10-24, 2fn): 주문한 발주의 남은 양과, 제안 주문량에서 그것을 뺀 초안 발주서([구매 발주](purchasing.md) PO8–PO9).
  - 분석 조회는 부족한 품목이 있을 때만 보낸다.
- **검증:** `StockAlertIntegrationTest`의 재주문 테스트
  - 안전재고 20에 쓸 수 있는 8 + 격리 5 → 부족 12
  - 만료 LOT 30만 가진 품목 → 쓸 수 있는 0, 먼저 나옴
  - 충분한 품목은 나오지 않음
  - 음수 안전재고 400
- `stockAlertModel.test.ts`의 `suggestedOrder` 2건: 부족 5 + 2/일 × 10일 = 25, 소비·리드타임 없으면 5. `orderValue`(25 × 1.2 = 30, 단가 0·없음은 null), `reorderCsv`(쉼표 든 이름, 모르는 값 빈 칸). 브라우저: 표에 Order value 열, 위에 "suggested orders ≈ 560 (2 without a unit cost)", CSV `reorder-….csv` 헤더·9줄, 단가 없는 품목의 가치는 `-`/빈 칸
- 실 화면(2026-09-25): 안전재고 50, 리드타임 10일, 30 입고 후 15 출고 → `15 kg / 50 / 35 / 10 d / 30 days / 40 kg`, 콘솔 오류 0

## 위치별 재주문 (2026-10-11, V73)

품목 전체 기준(위)과 별도로, [위치 목록](storage-location.md)의 **한 위치**에 품목별 안전재고를 두고 그 위치에서 모자란 품목을 본다. 창고마다 따로 채워야 하는 재고(현장 창고, 라인 옆 보관)를 위한 것이다. 개발 DB에는 V73을 적용하지 않았다.

| # | 규칙 | 위반 응답 |
|---|---|---|
| PS1 | **저장:** `place_safety_stock`(품목, 위치 `location_id` → `storage_location` FK, `safety_stock_qty` numeric(14,4) > 0 CHECK, 만든·고친 사람·시각). (품목, 위치)마다 하나(유일 제약). 품목 안전재고(`item.safety_stock_qty`)와 따로이며 서로 더하거나 대신하지 않는다 | |
| PS2 | **정하기·지우기:** `PUT /stock-alerts/place-safety-stocks {projectId, itemId, locationId, safetyStockQty}`(쓰기 권한). 0보다 크면 넣거나 고치고, 비우거나 0이면 지운다. 음수·소수 다섯째 자리·10^10 이상은 400. 프로젝트의 품목·위치여야 하고, 정할 때는 품목이 active이고 위치가 활성이어야 한다. 응답은 프로젝트의 목록 전체 | 400 `The safety stock cannot be negative.` / 400 `The item was not found in this project.` / 400 `The place is not in this project's location list.` / 409 `Item … is inactive; it takes no new stock, so it is not watched.` / 409 `Location … is inactive; activate it first.` / 400 형식 `The request body is not valid JSON, or safetyStockQty is not a number.` |
| PS3 | **목록:** `GET /stock-alerts/place-safety-stocks?projectId=`(읽기). 위치 코드, 품목 코드 순. 목록에서 지운 위치의 행은 남지만 보이지 않는다 | |
| PS4 | **위치의 재주문 목록:** `GET /stock-alerts/reorder?projectId=&location=WH-A`(읽기). 그 위치(대소문자 무시, A5와 같은 찾기)에 **바로** 안전재고를 둔 품목만, 쓸 수 있는 재고는 그 위치와 **안의 모든 위치**의 행만 센다(격리·종료·만료 LOT 제외는 품목 기준과 같음). 모자란 품목만, 부족 비율 큰 순. 각 줄 `location`은 목록의 철자. `location`이 없으면 지금의 품목 기준 목록(`location: null`) | 400 `Location … is not in this project's location list.` |
| PS5 | **화면:** Stock 탭의 **Below safety stock**에 위치 목록이 있으면 **Reorder at**(`all places (item safety stock)` 또는 위치 경로). 위치를 고르면 제목 `1 item below safety stock at WH-A`(없으면 `No items below their safety stock at WH-A`), Lasts·제안량은 그 위치의 30일 소비(A5), CSV `reorder-WH-A-YYYY-MM-DD.csv`. 아래 **Safety stock at WH-A**: 그 위치의 품목별 안전재고 목록과 **Remove**, **Item to watch**·**Safety stock here**·**Save**(0이면 지움). 위치 목록이 없는 프로젝트는 지금처럼 모자란 품목이 있을 때만 보인다 | 화면 `Pick the item to watch.` |
| PS6 | **경계:** 저장하는 경보(`stock_alert`)는 만들지 않는다(품목 기준 목록과 같이 요청 때 계산). 계산 공유는 inventory 안의 `ReorderLines`(품목 기준 목록도 같은 계산으로 옮김, 동작 같음) | |

검증: `PlaceSafetyStockIntegrationTest` — 자기 프로젝트에 WH-A(안에 BIN-A1)·WH-B, BOLT 10 @bin-a1·50 @WH-B, NUT 3 @WH-A. 음수 400, 소수 다섯째 자리 400, 없는 품목·위치 400, inactive 품목 409, 숫자 아닌 값 400 → BOLT@WH-A 20·NUT@WH-A 2·BOLT@WH-B 30 → `wh-a` 목록 BOLT(쓸 수 있는 10, 부족 10, `location` WH-A)만, WH-B 없음, 품목 기준 목록 없음, 없는 위치 400 → BOLT@WH-A 5면 없음 → 0으로 지우기 → 외부인 읽기·쓰기 403. 재고 경보·재고 분석·아키텍처·마이그레이션과 함께 14건 실패 0, 전체 백엔드 1,271건 실패 0(2dc–2df 포함). `stockAlertModel.test.ts` 3건(위치 선택지, 제목·파일 이름, 요청 검사). 모의 E2E `e2e/place-reorder.spec.ts`(선택지 경로, WH-A 목록, 위치 안전재고 목록·빈 저장 경고·NUT 저장 본문·BOLT 지우기 본문과 목록 갱신)

## 이후

- ~~알림 연동: 열린 경보를 `notification`으로 보내기~~ → 프로젝트 소유자에게(2026-10-11, [알림](notification.md) NT7). 남은 것: 구성원별 구독
- ~~작업지시 준비 점검(readiness)과 연결: 부족 예상 경보~~ → [열린 작업지시 자재 소요](material-requirements.md) "주문 뒤 남는 양"(2026-10-03, 프런트만): 열린 작업지시를 다 만들고 나면 안전재고 밑으로 내려가는 자재를 Open work order needs에 표시. 저장하는 경보(`stock_alert`)는 아님
