# 재고 흐름 분석 (소비, 소진 일수, 정체 재고)

상태: **구현(2026-09-25).** 새 테이블·마이그레이션 없음. 읽기 전용입니다.

## 목적

품목마다 "최근에 얼마나 쓰였고, 지금 있는 재고로 며칠 버티고, 얼마나 오래 안 움직였나"를 한 표로 봅니다.
- 발주 판단: 안전재고([재발주 목록](stock-alert.md))는 고정 기준이고, 여기서는 실제 소비 속도로 봅니다.
- 정체 재고: 오래 안 쓰인 재고와 그 금액([재료비](material-cost.md)의 단가)을 찾습니다.

## API

`GET /stock-analysis?projectId=&days=30&location=` — 프로젝트 읽기 권한
- `days`: 소비를 셀 기간(일), 1~365, 기본 30. 범위를 벗어나면 400.
- 응답: `days`, `from`(기간 시작, 끝은 지금), `lines[]`(품목 코드순)

| 필드 | 뜻 |
|---|---|
| `onHandQuantity` | 모든 재고 행의 보유량 합 |
| `usableQuantity` | 가용량(보유 − 예약) 합. 격리 행, 종료·만료 LOT 행은 뺌(재발주 목록과 같은 기준) |
| `consumedQuantity` | 기간 안의 **출고(`issue`)와 생산 투입(`production_input`)** 합. 역분개된 거래는 뺌 |
| `averageDailyConsumption` | 소비 ÷ 기간 일수(소수 4자리) |
| `daysOfCover` | 가용량 × 기간 ÷ 소비(소수 1자리). 소비가 없으면 null |
| `coverBelowLeadTime` | 소진 일수 < 품목 리드타임이면 true. 지금 발주해도 도착 전에 떨어짐 |
| `lastConsumedAt`, `lastReceivedAt` | 마지막 소비, 마지막 입고(`receipt`, `production_output`). 역분개된 것은 뺌 |
| `idleDays` | 마지막 소비 뒤 지난 날수. 한 번도 안 쓰였으면 첫 입고 뒤 날수. 둘 다 없으면 null |
| `stockValue` | 보유량 × 단가. 단가가 없으면 null |
| `consumedValue` | 기간 소비 × 단가. 단가가 없으면 null |
| `abcClass` | 기간 소비 금액 기준 ABC 등급(아래). 단가가 없으면 null |

보유량이 있거나 기간 안에 소비가 있는 품목만 나옵니다.

## 결정

| # | 결정 | 이유 |
|---|---|---|
| A1 | 소비는 출고와 생산 투입만. 이동(`transfer_*`), 조정, 예약·격리는 소비가 아님 | 이동은 위치만 바뀌고, 조정은 실사·폐기 보정이라 수요가 아님 |
| A2 | 역분개된 출고는 기간·마지막 소비 계산에서 모두 뺌 | 잘못 입력한 출고가 소비로 잡히지 않게 |
| A3 | 소진 일수는 반올림한 일평균이 아니라 가용량 × 기간 ÷ 소비로 계산 | 일평균 반올림 오차가 커지지 않게 |
| A4 | 품목별 합계는 DB에서 한 번에 집계(`GROUP BY item_id`, 역분개된 거래는 `NOT EXISTS`로 뺌). 원장 전체를 불러오지 않음 | 이력이 늘어도 품목 수만큼만 옮김. 더 커지면 `(project_id, transaction_type, created_at)` 인덱스(새 마이그레이션)가 다음 후보 |
| A5 | **한 위치만**(2026-10-03): `location`을 주면 [위치 목록](storage-location.md)의 그 위치(대소문자 무시)와 **그 안의 모든 위치**에 있는 재고 행만 셈. 보유·가용은 그 행들의 합, 소비·마지막 입고는 그 행들의 거래(같은 DB 집계에 재고 행 위치 조건을 붙임), ABC 등급도 그 범위 안에서 매김. 응답 `location`은 목록에 적힌 코드. 목록에 없는 위치는 400 `Location X is not in this project's location list.` | 창고·구역마다 정체 재고와 소진 일수를 따로 봄. 이동(transfer)은 소비가 아니라는 A1은 그대로라, 다른 창고로 옮겨 간 양은 그 창고의 소비가 아님 |

## ABC 등급

단가가 있는 품목을 기간 소비 금액이 큰 순서로 세웁니다. 각 품목 **앞에 선 품목들의 금액 합**이 전체의
- 80% 미만이면 **A**
- 95% 미만이면 **B**
- 그 밖은 **C**

그래서 1위 품목은 늘 A입니다. 예: 70·15·10·5 → A·A·B·C(두 번째 품목은 앞이 70%라 A).
- 소비가 없는 품목은 C, 기간 안에 아무것도 안 쓰였으면 모두 C입니다.
- 단가가 없는 품목은 등급이 없습니다(비율 계산에서도 빠짐).
- 등급은 프로젝트 전체 품목을 기준으로 매기므로, 화면에서 정체 필터로 줄여도 바뀌지 않습니다.

## 화면

재고 → **Analysis** 탭
- 선택: 소비 기간(최근 7·30·90·180·365일), 정렬(Idle longest / Runs out first / Used most / Most value used), 표시(전체 / 30·60·90일 이상 정체).
- 요약 한 줄: 30일(또는 고른 정체 기준) 이상 정체된 품목 수와 금액, 리드타임 안에 소진될 품목 수, A 등급 품목 수.
- 표: 품목, 보유, 가용, 기간 소비, 일평균, 소진 일수(Lasts), 정체(Idle), 금액, 등급(Class). 등급 칸 툴팁에 소비 금액을 적습니다.
  - 리드타임보다 먼저 떨어지는 품목은 소진 일수를 주황 굵게 표시하고, 툴팁에 리드타임을 적습니다.
  - 정체 칸 툴팁에 마지막 소비 또는 마지막 입고 시각을 적습니다.
- 위치 목록이 있으면 **Place** 선택(2026-10-03): `all places` 또는 목록의 위치(경로로 표시). 고르면 그 위치와 안의 위치만 계산하고(A5) 설명 줄에 `Only stock at WH-A and the places inside it.`
- 재고가 움직이면(`['inventories', projectId]` 새로 고침) 따라 갱신됩니다.
- Stock 탭의 재발주 목록도 이 분석(30일)으로 소진 일수와 권장 주문량을 보여 줍니다([재고 경보](stock-alert.md)).

## 재고 금액 추이 (Stock value by month)

Analysis 탭 위 **Stock value by month**(접힘, 펼칠 때만 불러옴)
- 최근 6개월 각 **말일 끝(현지 23:59:59.999)**과 오늘 끝의 재고 금액, 앞 달보다 늘거나 준 금액, 재고 행 수를 보여 줍니다.
- 시점마다 [과거 시점 재고](stock-ledger.md)(`GET /inventory-snapshots`)를 한 번씩 부릅니다. 조회 키가 "Stock on a date"와 같아 같은 시점은 캐시를 함께 씁니다.
- 금액은 **오늘 단가**입니다(단가 이력 없음). 그래서 추이는 가격이 아니라 수량의 움직임을 보여 줍니다. 단가 없는 품목이 있으면 그렇다고 적습니다.
- 시점은 날짜로만 정해져 하루 동안 같습니다. `valueTrendModel.test.ts` 3건(연말을 넘는 말일, 하루 동안 같은 시점, 증감).
- 실 화면(2026-09-26): 오늘 줄 828, 같은 날 "Stock on a date" 요약 `value 828`과 같음. 이 세션 DB는 9월 자료뿐이라 이전 달은 0. 콘솔 오류 0

## 폐기·손실 (Waste)

`GET /stock-waste?projectId=&days=30` — 읽기 권한. 최근 N일(1~365, 기본 30) 동안 재고를 **잃은** 움직임을 이유별로 더합니다.

| 이유 | 원장에서 | 비고 |
|---|---|---|
| expired | 참조 `expiry_write_off`(만료 재고 폐기, [LOT 유효기한](lot-expiry.md)) | |
| defects | 참조 `defect_log`(불량 해결 때 폐기, [품질](quality-inspection.md)) | 조정(adjustment) 이동 |
| count losses | 참조 `inventory_count` 중 수량이 줄어든 것([실사](stock-count.md)) | 실사로 늘어난 양은 손실을 상쇄하지 않음 |

- 수량이 줄어든 움직임만 셉니다. **역분개된 움직임은 뺍니다**(언제 역분개했든, `reversal`이 가리키는 원본). 역분개 행 자체도 세지 않습니다. 합산은 DB에서 품목별로 합니다(`InventoryTransactionRepository.findItemWaste`), 기간이 길어도 원장 행을 메모리에 올리지 않습니다.
- 공급처 반품(참조 `purchase_return`, [구매 발주](purchasing.md) PO26)도 감소 조정이지만 잃은 것이 아니라 셀 이유에 들지 않습니다(2026-10-24).
- 품목별 줄: 이유별 수량(품목 단위), 합계, 금액(오늘 단가, 없으면 null). 합계 금액과 이유별 금액, `valueComplete`. 금액이 큰 순.
- 화면: Analysis 탭 맨 위 **Waste**(기간 30·90·365일, 금액 요약, 품목 표, **Download CSV** `waste-<N>d-YYYY-MM-DD.csv`, 열 `item_code,item_name,unit,expired,defects,count_losses,total,value`). 재고 키 아래 캐시라 재고가 움직이면 새로 고쳐짐.
- 품목 Details 패널의 "Last 30 days" 줄에도 그 품목의 손실이 붙습니다(`· lost 1 kg (1 short at counts)`, 금액이 있으면 `worth …`). 손실이 없으면 붙지 않습니다.
- 검증: `StockWasteIntegrationTest` — 만료 LOT 3 kg 폐기(단가 2 → 6), 설탕(단가 1) 실사 10→8(손실 2)·5→6(이득, 제외)·3→1 후 역분개(제외), 불량 폐기 1 → 설탕 손실 2·불량 1·합계 3·금액 3. days=0 → 400. `wasteModel.test.ts`(CSV). 브라우저: 세션 DB에서 value 12(만료 12, 단가 없는 품목 안내), 품목 4줄(만료 2, 실사 손실 2), 365일 전환, CSV 4줄. 콘솔 오류 없음

## 위치 간 이동 (Moves between places)

2026-10-03(커밋 전). 새 테이블·마이그레이션 없음. 읽기 전용, 저장하지 않음.

`GET /stock-analysis/transfers?projectId=&days=30` — 읽기 권한. 최근 N일(1~365, 기본 30, 벗어나면 400, `projectId` 없으면 400) 동안 [재고 이동](stock-transfer.md)을 경로(출발 위치 → 도착 위치)와 품목별로 더합니다.

| # | 규칙 | 이유 |
|---|---|---|
| A6 | 이동 하나 = `transfer_out` 거래 하나. 같은 `referenceId`의 `transfer_in`과 이어 그 두 재고 행의 위치를 경로로 씀. Stock 탭 이동과 [창고 작업](warehouse-task.md) 완료가 모두 이 이동이라 함께 셈 | 이동 기록(T1)이 늘 두 거래라서 |
| A7 | 위치는 재고 행의 **지금** 위치(앞뒤 공백 뗌, 비면 "위치 없음" null). 대소문자가 다른 위치는 다른 경로 | 재고 행 위치는 이동으로 바뀌지 않음(T2·T3). 재고가 있는 위치의 코드는 바꿀 수 없음(L7) |
| A8 | DB에서 경로·품목별로 한 번에 집계(`GROUP BY`), 이동 수(`moves`)와 품목 단위 수량 합 | 이력이 늘어도 경로 × 품목 수만큼만 |

- 응답: `days`, `from`, `to`, `routes[]`(이동 수 많은 순, 같으면 출발·도착 이름순): `fromLocation`, `toLocation`, `moves`, `items[]`(이동 수 많은 순: 품목 코드·이름·단위, `quantity`, `moves`)
- 화면: Analysis 탭 **Moves between places**(접힘, 펼칠 때만 불러옴). 기간 30·90·365일, **Busiest:** 이동이 가장 많은 위치 셋(`SHELF 1 out · 3 in`), 표 **Moves by route**(`DOCK → SHELF`, 이동 수, `BOLT 5 kg (2), NUT 5 ea`). 이동이 없으면 "No stock moved between places in this period."
- 검증: `StockTransferAnalysisIntegrationTest` — 부두 → 선반 볼트 3·2, 너트 5, 선반 → 라인 볼트 1 → 부두→선반 이동 3, 첫 품목 볼트 5 kg·이동 2, 너트 5, 선반→라인 1. 0일 400, 프로젝트 없음 400, 외부인 403. 같은 변경에서 새 서비스는 품목을 `CatalogQuery.findItems`로 읽음(동결 위반 늘지 않음). `transferAnalysisModel.test.ts`(`routeText`·`routeItemsText`·`placeTraffic`). 실 API E2E `e2e/storage-locations.spec.ts`: 흩어진 위치 → 통으로 2 kg 이동 후 표 줄 `loose-… → B-…`에 `LOC-… 2 kg`, Busiest에 `loose-… 1 out · 0 in`, `B-… 0 out · 1 in`

## 검증

- `StockAnalysisIntegrationTest` 1건(실제 Postgres)
  - 100 입고 → 40일 전 출고 10(날짜를 뒤로 돌림), 출고 20, 출고 5 후 역분개
  - 30일: 보유 70, 소비 20, 일평균 0.6667, 소진 105일(리드타임 200 → true), 정체 0, 금액 140
  - 50일 전 입고만 있는 소금: 소비 0, 소진 null, 정체 50
  - 밀가루 소비 금액 40, 단가 없는 소금은 소비 금액·등급 null
  - 재고·소비 없는 품목은 빠짐. 60일이면 소비 30, 소진 140. `days` 0·366은 400, 외부인 403
- 위치별(2026-10-03, A5) `StockAnalysisIntegrationTest` 둘째(자기 프로젝트): WH-A 안에 BIN-A1, WH-B. BIN-A1(`bin-a1`로 넣음)에 10·출고 4, WH-B에 50·출고 20 → 전체 보유 36·소비 24, `wh-a`면 응답 `WH-A`·보유 6·소비 4, WH-B면 30·20, 목록에 없는 위치 400. 같은 변경에서 `StockMovementAnalysisService`가 `ItemRepository`·`UnitMasterRepository` 대신 `CatalogQuery.findProjectItems`를 씀(ADR-002 Stage B, `CatalogItemView`에 `leadTimeDays` 추가, 동결 91 → 89). 실 화면 `e2e/storage-locations.spec.ts`: Analysis 탭에서 그 품목 보유 `6 kg` → Place로 창고를 고르면 `1 kg`
- `StockMovementAnalysisServiceTest` 2건(ABC 등급): 70·15·10·5·0 → A·A·B·C·C, 95·5 → A·C, 전부 0 → C, 빈 입력
- e2e `inventory-reports.spec.ts`(REAL_API_E2E): API로 자료를 만든 뒤 Quality 탭(불합격 항목, 불량 종결), Analysis 탭(30 days), Movements → Stock on a date(15 kg), Stock 탭 재발주(30 days, 40 kg)를 실제 화면으로 확인
- `stockAnalysisModel.test.ts` 7건: 정렬 4종(모름은 뒤로), 정체 필터, 요약 합계(A 등급 수 포함), 표시 문구
- 실 화면(2026-09-25): 기름 100 입고, 10·20 출고 → `70 kg / 70 kg / 30 kg / 1 kg / 70 days / today / 210`. 7일로 바꾸면 일평균 4.2857, 16일. 정렬과 정체 필터 동작, 기름은 A 등급, 콘솔 오류 0

## 이후

- 요일·계절 변동을 반영한 예측은 필요해지면 추가. ABC 기준(80/95%)을 프로젝트 설정으로 바꾸는 것도 그때
- ~~위치별(창고별) 분석~~ → A5(2026-10-03). ~~위치 간 이동량 분석~~ → 위 "위치 간 이동"(2026-10-03). ~~재발주 목록·소진 일수를 창고별로~~ → [재고 경보](stock-alert.md) PS1–PS6(2026-10-11, V73, 위치별 안전재고와 위치의 재주문 목록. 소진 일수는 그 위치의 A5 분석)
