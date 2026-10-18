# 재고 이동 원장 (Movements 탭)

상태: **구현(2026-09-24), 서버 쪽 거르기·쪽 나누기 추가(2026-09-25).** 기존 `GET /inventory-transactions?projectId=`는 그대로 둔다. 원장은 새 검색 API를 쓴다.

## API

`GET /inventory-transactions/search`. 프로젝트 읽기 권한이 필요하다.

| 매개변수 | 뜻 |
|---|---|
| `projectId` | 필수 |
| `type`, `itemId` | 같은 값만 |
| `from`, `to` | ISO-8601 시각. `from` 포함, `to` 미포함. 화면은 현지 날짜를 그날 0시와 다음 날 0시로 바꿔 보냄 |
| `text` | 메모, 참조 유형·ID, 기록자에서 대소문자 무시 부분 일치. `%`·`_`는 글자 그대로 |
| `limit` | 1~500, 기본 100. 벗어나면 400 |
| `cursor` | 앞 페이지의 `nextCursor`. 잘못된 값은 400 |

응답은 `{ items: [...], nextCursor }`이고, 순서는 `(created_at, id)` 최신순이다.
- **키셋 페이징**이다. 커서는 앞 페이지 마지막 행의 `(시각, id)`다. 그래서 페이지 사이에 새 이동이 기록돼도 행이 겹치거나 밀리지 않는다.
- 총 건수는 세지 않는다.
- 공용 `PageResponse`는 offset·총계 방식이라 쓰지 않았다.

## 목적

지금까지 재고 이력은 **행 하나씩**(Stock 탭 → History)만 볼 수 있었다. 원장은 프로젝트의 **모든 재고 이동**을 한 목록에서 걸러 보고 CSV로 내보내는 화면이다. 다음을 확인할 때 쓴다.
- 감사: 누가, 언제
- 실사 대조
- 이동·보정 추적

## 화면

재고 → **Movements** 탭

- **거르기**
  - 유형(목록에 실제로 있는 유형만 보임), 품목, 기간(From–To, 현지 날짜 기준, 양 끝 포함)
  - 검색: 메모, 참조 유형·ID, 기록자
  - **Clear**는 한 번에 푼다
- **목록**
  - 열: 시각, 유형, 품목, 위치(LOT), 수량 변화(예약 변화), 이후 수량, 참조, 메모, 기록자
  - 최신순으로 200건씩 보인다. **Show more**로 더 본다.
- **Download CSV (N)**
  - **지금 걸러진 그대로** 받는다. 파일 이름은 `stock-movements-YYYY-MM-DD.csv`다.
  - 열: `time,type,item,lot,place,quantity_change,reserved_change,quantity_after,reference_type,reference_id,note,recorded_by`
  - 따옴표는 RFC 4180 규칙을 따른다. 쉼표·따옴표·줄바꿈이 든 값은 감싸고, `"`는 `""`로 쓴다. 스프레드시트가 한글을 바로 읽도록 BOM을 붙인다.
  - `time`은 서버 시각(ISO-8601) 그대로다.

재고를 바꾸는 화면 동작은 모두 `['inventory-transactions']`를 새로 고친다. 원장 조회 키(`['inventory-transactions', 'project', projectId]`)가 그 아래에 있어 함께 갱신된다.

## 과거 시점 재고 (Stock on a date)

`GET /inventory-snapshots?projectId=&at=`. 프로젝트 읽기 권한이 필요하다. `at`(ISO 일시)이 없으면 400이다.

**계산**
- 모든 이동은 그 뒤 수량(`quantity_after`, `reserved_after`)을 남긴다. 그래서 재고 행의 `at` 시점 재고는 **그때까지 마지막 이동이 남긴 수량**이다.
- 한 번도 움직이지 않은 행(원장 이전 행, 직접 넣은 행)은 만들어진 뒤로 지금 수량 그대로다(`fromLedger=false`).
- 그 뒤 삭제된 행도 그 시점에 재고가 있었으면 나온다.
- 보유와 예약이 모두 0인 행은 뺀다.
- 금액은 **오늘 단가**로 계산한다. 단가 이력이 없기 때문이다. 단가가 없는 품목은 합계에서 빠지고 `valueComplete=false`다.
- 행마다 마지막 이동은 DB에서 `DISTINCT ON (inventory_id) … ORDER BY inventory_id, created_at DESC, inventory_transaction_id DESC`로 한 줄씩 골라 온다. 원장 전체를 불러오지 않는다. 같은 순간의 이동은 ID로 갈린다. 임의지만 늘 같은 결과다. 이력이 아주 많아지면 `(project_id, inventory_id, created_at)` 인덱스(새 마이그레이션)가 다음 후보다.

**화면:** Movements 탭 위의 **Ledger / Stock on a date** 전환
- 날짜를 고르면 그날 **현지 기준 하루 끝(23:59:59.999)**의 재고가 나온다. 오늘을 고르면 지금까지다.
  - 시각을 날짜로만 정하므로 조회 키가 렌더링마다 바뀌지 않는다.
- 요약: 행 수, 품목 수, 금액 합계.
- **Group by:**
  - item(기본): 품목별 행 수·보유·예약·금액
  - item group: 품목 그룹별 행 수·품목 수·금액. 그룹은 [품목 상세](item-details.md)의 그룹이고, 그룹 없는 품목은 맨 끝 `no group`
  - location: 위치별 행 수·품목 수·금액. 품목마다 단위가 달라 수량 합은 없다. 위치 없는 행은 맨 끝
  - record: 행별 위치·LOT
  - 그룹은 지금 품목의 그룹으로 묶는다. 그룹 변경 이력은 없다. `snapshotModel.test.ts`의 `totalsByLocation`·`totalsByItemGroup`.
- **Download CSV**: `stock-on-YYYY-MM-DD.csv`, 열 `item_code,item_name,location,lot,quantity,reserved,unit,value`. 따옴표 규칙과 BOM은 원장 CSV와 같다.

## 기간 수불 (Summary by item)

`GET /stock-movement-summary?projectId=&from=&to=`. 읽기 권한이 필요하다. `from`·`to`(ISO 일시)가 없거나 `from ≥ to`면 400이다.

- **기초(opening):** `from` 시점 재고의 품목별 합. 계산은 위 "과거 시점 재고"와 같다.
- **기말(closing):** `to` 시점 재고의 품목별 합.
- **그 사이(`from` 초과 ~ `to` 이하)의 원장 이동을 종류별로 더한다.**
  - 입고(`receipt`), 생산 산출(`production_output`)
  - 출고(`issue`, 양수로), 생산 투입(`production_input`, 양수로)
  - 이동(`transfer_in` − `transfer_out`). 품목 안에서는 0이다
  - 보정(`adjustment`·`reversal`, 부호 그대로)
  - 예약·해제·격리처럼 수량이 바뀌지 않는 이동은 뺀다.
- **unexplained** = 기말 − (기초 + 입고 + 산출 − 출고 − 투입 + 이동 + 보정). 원장이 모든 변경을 담으면 0이다. 원장 이전 행처럼 원장 밖에서 바뀐 행이 있을 때만 0이 아니다.
- 모든 값이 0인 품목은 뺀다. 순서는 품목 코드순이다.
- 화면: Movements 탭 → **Summary by item**
  - 기간은 From–To 날짜이고 양 끝 날을 포함한다(현지 0시 ~ 23:59:59.999). 기본값은 이번 달 1일부터 오늘까지다.
  - 표에서 0은 `-`로 보인다. 들어온 것은 초록, 나간 것은 빨강이다.
  - 맞지 않는 품목이 있으면 경고 줄과 기말 옆 `*`가 나온다. 툴팁에 차이가 보인다.
  - **Download CSV:** `stock-summary-<from>-to-<to>.csv`, 열 `item_code,item_name,unit,opening,received,produced,issued,consumed,transferred,corrected,closing`.

## 한계

- 목록은 100건씩이다. **Show more**로 다음 페이지를 불러온다.
- 검색어는 입력이 300ms 멈추면 서버에 보낸다.
- CSV는 현재 거르기의 **모든 페이지**를 500건씩 받아 만든다. 25,000건을 넘으면 최신 25,000건만 받고, 화면에 거르기를 좁히라고 안내한다.
- **V44**(2026-10-03): `idx_inventory_transaction_project_created (project_id, created_at, inventory_transaction_id)`. 프로젝트 안에서 시각 범위로 읽는 질의에 쓴다. 이동 검색의 키셋 페이지(최신순, 같은 시각은 id), 기간 수불, 과거 시점 재고, 분석 기간이 그렇다. 단일 열 `project_id` 인덱스는 그대로 둔다. 세션 DB에서 트랜잭션 안에 먼저 만들어 보고 롤백한 뒤 적용했다. 이동 검색·기간 수불·과거 시점·분석 통합 테스트가 새 DB에서 통과했다
- 위치 열은 재고 행의 **지금** 위치다. 행을 지운 뒤에는 비어 보인다.

## 검증

- `InventoryLedgerSearchIntegrationTest` 2건
  - 5건을 2건씩 넘기면 3페이지이고, 전체 조회와 같은 순서에 중복이 없음
  - 유형·검색어(대소문자 무시, `_`·`%` 글자 그대로)·기간 거르기
  - 잘못된 커서·limit 400, 외부인 403
- `ledgerModel.test.ts` 3건: 현지 날짜 → 시각 범위(`to`는 다음 날 0시), 유형 목록, CSV 따옴표와 BOM
- `StockSnapshotIntegrationTest` 1건
  - 입고 10 직후 시점: 10, 금액 20. 뒤에 생긴 이동 행과 직접 넣은 행은 없음.
  - 지금: 출고 4·예약 1·이동 2 뒤 4(예약 1), 이동한 곳 2, 움직인 적 없는 행 7(`fromLedger=false`).
  - 입고 전 시점: 그 품목 없음. `at` 없음 400, 외부인 403.
- `StockMovementSummaryIntegrationTest` 1건: 기초 10 → 출고 3(뒤에 역분개), 입고 5, 다른 선반으로 2 이동 → 입고 5, 출고 3, 이동 0, 보정 +3, 기말 15, unexplained 0. 품목이 생기기 전부터 보면 기초 0·입고 15. 거꾸로 된 기간·`to` 없음 400, 외부인 403
- `movementSummaryModel.test.ts` 3건: 기본 기간(이번 달), 양 끝 포함 경계와 거꾸로 된 기간, CSV
- 실 화면(2026-09-25, 수불): 10 등록·3 출고·5 입고한 품목 → `- / 15 / - / 3 / - / - / - / 12`, 이 기간 25개 품목 모두 맞음(경고 없음), 콘솔 오류 0
- `snapshotModel.test.ts` 3건: 하루 끝 시각, 품목별 합계(단가 없는 행이 섞이면 금액 null), CSV
- 실 화면(2026-09-25): 소금 10 입고(세션 DB에서 이틀 전으로 옮김) 뒤 오늘 4 출고 → 오늘 `6 kg / 12`, 어제 `10 kg / 20`. 행별 보기, CSV 파일 이름 `stock-on-2026-09-24.csv`, Ledger로 돌아가기 확인. 콘솔 오류 0
- 실 화면: 입고 → 출고(메모 `To line "2", urgent`) → 이동 후 해당 품목으로 거르면 4건(`transfer_in`, `transfer_out`, `issue`, `receipt`). 검색 `urgent`는 1건. CSV는 헤더와 4행이고 메모 따옴표가 올바르게 처리됨. 콘솔 오류 0
