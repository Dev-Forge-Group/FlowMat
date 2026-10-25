# 재고 실사 (Stock count)

상태: **구현(2026-09-24).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) WMS 계열의 cycle count. 코드는 가져오지 않았습니다. **새 마이그레이션·새 거래 유형 없음.** 기존 `adjustment` 거래로 기록합니다.

## 왜 필요한가

실사 결과를 행마다 Adjust로 하나씩 넣으면 다음 문제가 생깁니다:
- 중간에 실패하면 일부만 반영됩니다.
- 실사하는 동안 다른 이동이 있었는지 알 수 없습니다.
- 사람이 "몇을 더하거나 뺄지"를 계산해야 합니다.

실사는 **센 수량(절대값)**을 입력하면 서버가 차이를 계산해 한 번에 반영합니다.

## API

`POST /inventory-counts` — 프로젝트 쓰기 권한

```json
{ "projectId": "...", "requestId": "uuid", "note": "월말 실사",
  "lines": [ { "inventoryId": "...", "countedQuantity": 8, "expectedQuantity": 10 } ] }
```

응답: `{ countId, adjusted, unchanged, lines: [{ inventoryId, quantityBefore, countedQuantity, difference, inventoryTransactionId }] }`

## 규칙

| # | 규칙 | 이유 |
|---|---|---|
| C1 | 모든 줄을 **한 트랜잭션**에서 처리. 한 줄이라도 거절되면 전부 롤백 | 반쯤 반영된 실사가 남지 않음 |
| C2 | 각 행을 **먼저 잠그고**(`findForUpdate`) 현재 수량과 센 수량의 차이를 계산 | 동시에 들어온 이동 때문에 결과가 센 수량과 달라지지 않음 |
| C3 | `expectedQuantity`(센 사람이 본 수량)가 현재 수량과 다르면 **409, 다시 세도록 안내** | 실사 중에 움직인 재고를 옛 숫자로 덮어쓰지 않음 |
| C4 | 차이가 0인 행은 거래를 남기지 않음(`unchanged`) | |
| C5 | 차이는 `adjustment` 거래로, `referenceType = "inventory_count"`, `referenceId = countId`, 메모 `Stock count: …` | 이력에서 한 실사로 묶어 볼 수 있음 |
| C6 | 센 수량 < 예약량이면 409 | 예약을 조용히 깨지 않음. 먼저 예약 해제 |
| C7 | 음수, 같은 행 두 번 → 400 | |
| C8 | 각 줄 거래의 `request_id`는 `requestId:inventoryId`. 같은 `requestId`로 다시 보내면 첫 결과를 돌려주고 아무것도 바꾸지 않음 | 두 번 눌러도 한 번만 |
| C9 | 행은 `inventory_id` 순서로 잠금 | 겹치는 두 실사가 교착되지 않음 |
| C10 | 격리된 재고도 실사 가능 | `adjustment`는 격리로 막히지 않는 기존 규칙과 같음 |
| C11 | 화면의 `expectedQuantity`는 각 행에 처음 값을 입력하거나 CSV 값을 불러올 때 보이던 장부 수량으로 고정(2026-10-05). 자동 재조회·계속 편집·CSV 덮어쓰기에도 유지하고 0도 보존. 입력을 비우거나 **Clear counts**를 누른 뒤 새로 세면 새 기준 사용 | 조회 갱신이 C3의 동시 변경 검증을 무력화하지 않음 |
| C12 | 새 실사에서 값이 입력된 행이 재조회에서 사라졌으면 화면에서 실사 요청 전체를 막음. 누락 행을 빼고 나머지만 전송하지 않음. **Clear counts**로 초기화한 뒤 남은 행을 다시 셈 | C1의 일괄 적용을 화면에서도 지키고, 숨은 입력 때문에 일부만 조정되지 않음 |
| C13 | 미확인 실사는 같은 프로젝트·메모·행별 센 수량·기준 수량이면 같은 키로 재시도(2026-10-05). ID 순서 전송으로 재조회 순서 변경 대응. 값 편집 또는 성공 확인 뒤 새 제출은 새 명령 | 저장 후 응답 유실을 C8 재생으로 복구. 키는 열린 화면 메모리에만 유지 |
| C14 | 제출 결과가 미확인일 때는 당시 보낸 모든 줄과 메모를 그대로 재전송. 행이 삭제돼도 일부 줄만 재구성하지 않음. 확인 전 수량·메모·CSV 입력 잠금, Clear counts는 명시적 새 실사 시작 | C12는 새 요청의 누락 방어이며 기존 요청 재생을 막지 않음. 서버가 재생·거절 여부 판단 |
| C15 | CSV에서 목록에 있는 같은 재고 ID의 센 수량이 두 번 나오면 파일 전체를 거절(동일 수량도 포함). 빈 counted 줄은 제외. 읽기 중 적용 금지, Clear counts는 읽기를 취소하고 늦은 완료·실패 무시 | 마지막 줄 덮어쓰기와 취소 뒤 입력 복원을 방지. 읽기 실패는 기존 입력을 보존하고 안내 |
| C16 | 실사 CSV의 inventory_id·counted 열은 각각 하나만 허용(대소문자·주변 공백 무시). 중복이면 기존 입력을 바꾸지 않고 오류 표시 | 숨은 두 번째 ID·수량의 임의 선택 방지 |

경보([재고 경보](stock-alert.md))와 LOT 상태는 다른 이동과 똑같이 다시 계산됩니다.

## 화면

재고 → **Count** 탭
- 필터(품목·LOT·위치)로 셀 행을 좁힙니다.
- 행마다 센 수량을 입력하면 차이(−빨강, +초록)가 바로 보입니다.
- **Apply count (N)**으로 입력한 행만 반영하고, "N records adjusted, M already right"를 보여 줍니다.
- 업무 검증 거절(400·409)은 서버 메시지를 보여 줍니다. 연결 실패·HTTP 500 이상은 저장 여부가 미확인이므로 같은 요청을 재시도합니다.

### 입력 중 재고 갱신 (2026-10-05, C11·C12)

- 처음 입력·CSV 불러오기 때의 장부 수량을 행별로 유지합니다. 새 수량이 조회되면 **Stock changed. Clear this count and recount.**를 보여 주고 차이는 처음 기준으로 표시합니다. 옛 기준으로 전송하면 서버 C3가 409로 거절합니다.
- 입력했던 행이 없어졌으면 **A counted stock record is no longer available. Clear counts and recount the remaining records.**로 전체 요청을 막습니다. 다른 행도 적용되지 않습니다.
- **Clear counts**는 입력·기준·이전 오류·결과를 비우며 메모는 남깁니다. 특정 행의 입력만 비워 다시 셀 수도 있습니다. 적용 중에는 수량·메모·파일 불러오기·초기화 입력을 잠급니다.
- CSV의 기준은 파일을 화면에 불러올 때의 장부 수량입니다. 장기 오프라인 실사 계획은 아래 Proposed 메모의 결정 대기 사항입니다.

### 실사 응답 유실 복구(2026-10-05, C13)

HTTP 500 이상도 미확인 결과로 취급하고 같은 입력의 재시도를 안내합니다(2026-10-05, §2 2ay). 400·409와 입력 검증 오류는 재실사 안내를 유지합니다.

첫 입력 기준(C11)을 바꾸지 않고 같은 값을 다시 보내 저장 결과를 복구합니다. 연결 오류에는 먼저 Apply count를 재시도하라고 안내하며, 변경 행의 기존 재실사 안내도 미확인 상태에서는 재시도 안내로 바뀝니다. 공통 요청 비교는 중첩 객체의 모든 필드와 배열 순서를 포함합니다. 평면 비교를 실사에 재사용하면 서로 다른 수량이 같은 키로 합쳐지는 문제 2건을 단위 테스트로 재현하고 수정했습니다.

- 실사 mutation 3건·중첩 비교 3건 추가. 명령·FEFO·실사 단위 16건 및 대상 라인 93.33%·분기 90.90% 통과(기존 이력 query 포함). 전체 프런트 443건·타입·린트·빌드 통과.
- 가짜 API `count-command-retry.spec.ts` 2건: 두 행 저장 뒤 응답 유실, 재조회·역순 반환 포함. 수정 전 새 키로 409가 나고 결과 복구 실패, 수정 후 원래 결과 재생·추가 조정 없음. 기존 `count-refresh.spec.ts` 3건도 함께 통과.
- **서버의 기존 한계:** C8은 원장 거래가 있는 실사를 찾습니다. 차이 0인 행은 재생 응답에서 빠지고 모두 차이 0인 실사는 원래 countId를 찾지 못합니다. 완전한 응답 재생에는 변경 없는 행도 포함한 결과 저장이 필요합니다. 이번 프런트 수정은 이 서버 정책을 바꾸지 않았습니다.
- 페이지를 다시 열면 키가 유지되지 않습니다. 실제 DB·서버·마이그레이션 변경 없음.

## 마지막 실사 (순환 실사)

차이가 없던 행은 원장에 아무것도 남지 않으므로, 원장만으로는 "그 행을 언제 셌는지" 알 수 없습니다. 그래서 실사를 적용할 때 **센 모든 행**(차이가 있든 없든)에 V1부터 있던 `inventory.last_checked_at`·`last_checked_by`를 적습니다(새 마이그레이션 없음).

- 적는 방법: 실사 트랜잭션 끝에 센 행 전부를 한 번의 UPDATE로(`InventoryRepository.markChecked`). 수량·상태는 물론 **`version`도 바꾸지 않습니다** — 그대로였던 행 때문에 누군가 열어 둔 조정 폼이 409가 되지 않게. 차이가 있던 행은 조정 이동이 이미 `version`을 올립니다.
- 실사가 거절되면(예약 아래로 셈, 세는 동안 바뀜 등) 트랜잭션 전체가 롤백되어 아무 행에도 적히지 않습니다.
- 응답: `GET /inventories`·`/inventories/{id}`에 `lastCheckedAt`(없으면 null), `lastCheckedBy`(사용자 ID).
- 화면: Count 탭 표에 **Last counted**(현지 날짜, 없으면 never, 마우스를 올리면 누가 셌는지). 표 위 **Due for a count (N)**을 켜면 셀 때가 된 행만 보여 줘, 오늘 셀 곳을 고를 수 있습니다(`countDue`). 셀 때는 품목의 **ABC 등급**(최근 90일 소비 금액, [재고 흐름 분석](stock-analysis.md))으로 정합니다: A 30일, B 90일, C 180일, 등급 없는 품목(단가·소비 없음) 30일(`ABC_COUNT_DAYS`, `countIntervalDays`). 한 번도 안 센 행은 늘 대상입니다. Last counted 칸에 마우스를 올리면 주기와 등급이 나옵니다. 브라우저: "Due for a count (69)", 등급 있는 행은 "counted every 90 days (class B)"·"180 days (class C)", 없는 행 30일. 콘솔 오류 없음
- 검증: `InventoryLastCountedIntegrationTest` — 5·3·1 세 행 중 5를 5로, 3을 2로 센 실사 → 두 행 모두 `lastCheckedAt`·`lastCheckedBy`(demo-owner)가 적히고, 그대로였던 행의 `version`은 그대로, 세지 않은 행은 null. `countModel.test.ts`의 `countDue`(없음·30일 넘음·안 넘음·일수 지정)와 날짜 표시. 브라우저: 새 행(4 kg)을 4로 센 실사 → "0 records adjusted, 1 already right", 그 행 Last counted `never` → 오늘 날짜(툴팁 `by demo-owner`), "Not counted in 30 days" 55 → 54. 콘솔 오류 없음

## 실사표 (종이·스프레드시트로 세기)

Count 탭 위 **Count sheet** 묶음:
- **Download count sheet:** 지금 보이는 행(검색·"Not counted in 30 days" 거르기 그대로)을 `count-sheet-YYYY-MM-DD.csv`로 받습니다. 열 `inventory_id,item,lot,location,on_hand,counted`, `counted`는 비어 있음. **blind**를 켜면 `on_hand` 열을 빼서 세는 사람이 장부 수량에 끌리지 않게 합니다.
- **Load counted sheet:** 채운 파일을 올리면 `inventory_id`로 행을 찾아 Counted 칸에 채웁니다. `counted`가 빈 줄은 세지 않은 것으로 보고, 목록에 없는 행(삭제됐거나 다른 프로젝트)은 건너뛰고 개수를 알려 줍니다("Loaded 2 counts; 1 record(s) not in the list skipped. Check them, then apply."). 필요한 열이 없거나 센 값이 하나도 없으면 오류.
- 올린 값은 바로 적용되지 않습니다. 화면에서 차이를 확인하고 **Apply count**를 눌러야 평소 실사처럼 한 번에 적용됩니다(숫자가 아니면 그때 거절). 파일은 서버로 가지 않습니다.
- `countSheetModel.test.ts` 6건(중복 3건 추가): 시트 내보내기(쉼표 든 위치 따옴표, blind 헤더), 채운 시트 읽기(빈 칸·목록에 없는 행 건너뜀), 열 없음·값 없음 오류
- 브라우저: 두 행(5·3)을 거른 뒤 blind 시트 받기(`inventory_id,item,lot,location,counted`, 2줄) → 5와 2를 채워 올리기 → "Loaded 2 counts", Counted 칸 5·2, 차이 -1 → 적용 "1 record adjusted, 1 already right", 재고 5·2. 콘솔 오류 없음

## 실사 이력

`GET /inventory-counts?projectId=` — 읽기 권한. 최근 실사 20건, 최신순.
- 원장의 `adjustment` 중 참조가 `inventory_count`인 것을 실사 ID별로 묶습니다. 새 테이블은 없습니다.
- 차이가 없던 행은 조정되지 않았으므로 목록에 없습니다. 모든 행이 그대로였던 실사는 이력에 나오지 않습니다.
- 실사마다: 시각과 기록자(첫 조정), 메모, 바뀐 행 수, 늘어난 양, 줄어든 양, **오늘 단가**로 본 금액 변화(부호 포함). 단가 없는 품목이 있으면 `valueComplete=false`이고 금액에서 빠집니다.
- 행마다: 품목, 위치, LOT, 차이.
- 나중에 역분개한 조정도 그대로 보입니다. 실사에서 그 차이를 찾은 사실은 바뀌지 않기 때문입니다.
- 화면: Count 탭 아래 **Past counts**. 한 줄 요약(시각 · 기록자 · N records changed +X / -Y · value today ±Z · at the count ±W(아래 CH4) · 메모)을 펼치면 행별 차이가 나옵니다. 실사를 적용하면 바로 새로 고쳐집니다.

### 실사 당시 단가 금액 (2026-10-12, 리드, §2 2dp)

사용자 결정(2026-10-12): **둘 다 보인다.** 오늘 단가 금액은 그대로 두고 실사 당시 단가 금액을 옆에 더한다. 끝난 실행의 D+(마감 시각의 이력 단가)와 같은 규칙이다. 마이그레이션 없음.

| # | 규칙 |
|---|---|
| CH1 | 응답 `valueAtCount`: 실사(첫 조정 시각)의 단가로 본 차이 금액, 부호 포함, 소수 4자리. 단가는 catalog 공개 API `CatalogUnitCostQuery.findUnitCostsAt`이 V50 단가 이력에서 그 시각 값으로 준다 |
| CH2 | `valueAtCountComplete=false`: 그 시각 단가를 모르는(없음·0) 품목이 있어 금액에서 빠졌다 |
| CH3 | `valueAtCountEstimated=true`: 금액에 든 단가 중 이력이 그 시각을 덮지 못한 것이 있다(첫 기록 변경 전이면 그 변경의 이전 값, 이력이 없으면 지금 값, 같은 시각에 다른 값이 둘이면 지금 값) |
| CH4 | 화면 **Past counts** 요약: `value today ±X · at the count ±Y`, 추정이면 `(estimated)`, 모르는 단가가 있었으면 `(some items had no unit cost then)`. 필드가 없는 응답이면 오늘 값만 |

검증(2026-10-12, 격리 복사본): `InventoryCountHistoryIntegrationTest` 확장 — 기존 실사 뒤 밀가루 단가 2 → 5: 마지막 실사는 오늘 +5·당시 +2(완전, 추정 아님), 첫 실사는 오늘 -10·당시 -4(소금 단가가 없어 불완전). 실사·실사 이력·단가 이력·모듈 경계 관련 19건 실패 0. 모의 E2E `e2e/count-history-value.spec.ts`(두 금액, 추정·모르는 단가 안내).

## 검증

- `InventoryCountIntegrationTest` 2건
  - 10·5·7에 8·5·9 → 2건 조정, 1건 그대로(거래 없음). 이력에 `adjustment −2`, `inventory_count`, 같은 `countId`. 같은 `requestId` 재시도 시 같은 `countId`에 재고 불변.
  - 예약 4인 행을 2로 세면 409이고, 앞 줄도 롤백. 실사 중 변동(9로 알았는데 10) 409. 같은 행 두 번, 음수 400.
- `countModel.test.ts` 4건
- 실 화면: 10 → 7(차이 −3 표시), 5 → 5(0) → 적용 → "1 record adjusted, 1 already right", 이력 `adjustment -3 Stock count: UI check`. 콘솔 오류 0
- `InventoryCountHistoryIntegrationTest` 1건: 밀가루(단가 2) 10→8, 소금(단가 없음) 5→7 실사(메모 month end) → 바뀐 행 2, +2/-2, 금액 -4, 불완전. 차이 없는 실사는 이력에 없음. 밀가루 8→9 실사가 맨 위, +1, 금액 +2. 외부인 403
- 실 화면(2026-09-25): Count 탭에서 12인 행을 11로 실사(메모 browser check) → Past counts 맨 위 `1 record changed +0 / -1 · value 0 (some items have no unit cost) · Stock count: browser check`, 펼치면 `-1 kg`. 콘솔 오류 0

### 화면 갱신 회귀 (2026-10-05, §2 2at)

- `countModel.test.ts` 13건(6건 추가), 실사표 기존 3건 함께 통과. 기준 10→재조회 12에서 기준 10 유지, 기준 0 유지, 사라진 입력 행 거절, 편집의 불변성, 비우기→다시 세기, CSV 병합을 검증합니다. 수정 전 기준 변경·0·사라진 행 3개 업무 재현이 실패했습니다.
- `e2e/count-refresh.spec.ts` 가짜 API 3건: 직접 입력·CSV 입력 뒤 30초 캐시가 지난 재조회로 수량 10→12 → 옛 기준 10 전송·409·재고 그대로 → Clear counts 후 다시 세면 기준 12로 정상 적용. 입력한 두 행 중 하나가 사라지면 API 요청 0·다른 행 수량 그대로, 초기화 후 남은 행 실사 성공. 수정 전 세 경로가 잘못 적용됐습니다.
- 브라우저 재현의 최초 설정은 visibility 이벤트를 document에 보내 기다림이 초과했습니다. 설치된 query 라이브러리가 window에서 듣는 것을 확인하고 테스트 설정을 고친 뒤 위 업무 실패 3건을 재현했습니다.
- 전체 프런트 427건·타입 검사·린트·빌드와 위 브라우저 3건 통과. 변경한 `countModel.ts` 커버리지: 라인·문장·함수 100%, 분기 97.77%(80% 기준 모두 통과). 서버·API·마이그레이션 변경 없음. 실제 DB 요청·실제 로그인 없이 검증했습니다.

### 미확인 행 삭제·CSV 입력 회귀 (2026-10-05, §2 2az–2ba)

- `count-command-retry.spec.ts` 6건: 연결 오류·503 각각 기본·재정렬·행 삭제 후 원래 두 줄과 키 재전송. 삭제 2건은 수정 전 C12가 재생까지 막는 실패를 재현했습니다. 미확인 상태의 입력·메모·CSV 잠금을 검증합니다. 기존 `count-refresh.spec.ts` 3건은 새 실사 누락 거절 유지.
- `countSheetModel.test.ts` 6건: 다른 수량·동일 수량의 중복 행 거절, 빈 줄 중복 허용. 수정 전 중복 2건 실패. `count-sheet-input.spec.ts` 3건: 중복 업로드 뒤 기존 입력 보존, 읽기 중 적용 잠금·취소·다음 파일과 늦은 완료 분리, 읽기 실패 안내·기존 입력 적용. 수정 전 3건 실패.
- 전체 프런트 449건 실패 0·타입 검사·린트·빌드와 관련 가짜 API 브라우저 12건 통과. TypeScript 대상 환경에 맞춰 `hasOwnProperty.call` 사용. 시트 모델 커버리지 라인 100%·분기 84.61%·문장 96.66%·함수 100%(80% 기준 통과). 서버·API·DB·마이그레이션은 변경하지 않았습니다. C13의 거래 없는 실사 재생 한계는 그대로입니다.

## 2026-10-05 확정: 블라인드 실사

**Accepted (정책).** A 승인 + 계획별 blind 옵션. 계획 시작 서버 snapshot, 변동 행 재실사. blind 기준 수량 열람은 Project owner만. 아래 메모는 검토 이력이며, 구현은 다음 절에 기록한다. [최종 결정](../status/DECISIONS-2026-10-05.md)이 아래 예전 선택지보다 우선한다.

## 결정 메모: 블라인드 실사와 실사 계획 (Proposed, 2026-10-03)

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §4 "블라인드 실사·실사 계획"을 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

**지금 코드에서 막히는 점**
- C3: 실사 중 재고가 움직였는지는 센 사람이 보낸 `expectedQuantity`(그가 본 화면의 수량)로 안다.
- 블라인드 실사는 그 수량을 보여 주지 않으므로, 기준 수량을 **서버가** 따로 갖고 있어야 한다. 실사표(CSV)도 지금은 `expected` 열을 담는다.
- 누가 수량을 볼 수 있는지는 역할 문제다. 역할별 권한은 D3(권한 매트릭스)가 아직 대기라서 지금은 프로젝트 소유자와 구성원만 구분한다.

| 안 | 내용 | 실사 중 이동 | 약점 |
|---|---|---|---|
| A | 실사 계획: 시작 때 대상 행(위치·품목)의 기준 수량을 서버에 저장하고 센 사람 화면·API에는 숨김. 제출 때 현재 ≠ 기준인 줄은 C3처럼 409, 그 줄만 다시 세기 | 그 줄을 다시 셈 | 새 표 둘(V52 후보: `count_plan`, `count_plan_line`). 바쁜 위치는 다시 세는 줄이 많음 |
| B | A + 이동을 빼서 계산: 조정량 = 센 수량 − 기준. 그 사이 이동은 현재 수량에 이미 들어 있다고 봄 | 다시 세지 않음 | 언제 셌는지(이동 전인지 후인지) 모르면 이동을 두 번 세거나 빠뜨림. 줄마다 센 시각이 필요 |
| C | A + 위치 잠그기: 계획이 열린 동안 그 위치의 입출고·이동을 409로 막음 | 일어나지 않음 | 실사 동안 그 위치의 작업이 멈춤 |

**권장안: A.** 지금 규칙(C2·C3)을 그대로 쓰고 기준만 서버로 옮긴다. 정확한 실사가 필요한 위치에는 나중에 C를 계획별 선택으로 더한다. → **2026-10-12 사용자 결정: C(계획이 열린 동안 위치 잠금)는 넣지 않는다.** 실사 중 움직인 행은 지금처럼 다시 센다.
- 사용자가 정할 것:
  - 블라인드를 계획마다 고를지, 늘 블라인드일지
  - 기준·차이를 누가 볼지. D3 전에는 "계획을 만든 사람과 프로젝트 소유자"를 잠정안으로 쓸 수 있다
  - 담당자 배정을 창고 작업(W7)처럼 둘지
- 영향받는 코드: `InventoryCountService`(C1–C10 유지, 기준 출처만 계획 줄로), 새 엔티티·저장소·마이그레이션, `CountPanel`·실사표(블라인드면 `expected` 열 제외), `CountHistory`(계획 번호).

## 이후

- ~~실사 계획(대상 위치·품목, 블라인드 실사: 현재 수량을 숨김)은 새 테이블이 필요합니다.~~ → 아래 "서버 실사 계획"(2bk, V53). 담당자 배정은 2026-10-24 2ge(아래 P9–P10, V114).
- ~~차이 금액을 실사 당시 단가로 보기~~ → 아래 "실사 당시 단가 금액" CH1–CH4(2dp). 오늘 단가 금액도 그대로 보인다.

### 중복 CSV 열 회귀 (2026-10-05, §2 2be)

실사·품목·일괄 입고 CSV에서 같은 필드로 해석되는 중복 열을 거절합니다. 별칭과 대소문자 차이도 포함하고, 오류에는 원래 열 이름을 씁니다. 실사 헤더 2건·품목 별칭 3건·입고 별칭 3건은 수정 전 잘못된 ID·수량·단가·바코드·위치 선택을 재현했습니다. 관련 모델 21건·라인 100%·분기 80.80%·80% 기준 통과. 실사·입고 가짜 API 10건(중복 열 2건 포함)과 전체 프런트 457건·타입·린트·빌드 통과. 서버 계약과 내보내기 형식은 그대로입니다.
## 서버 실사 계획 (2026-10-05, §2 2bk)

V53 `inventory_count_plan`/`inventory_count_plan_line`. 격리 psql BEGIN/ROLLBACK 리허설 후 추가했고 개발 DB에는 적용하지 않았다. 기존 즉시 실사·CSV 계약은 유지한다.

| 규칙 | 동작 |
|---|---|
| P1 시작 | 대상 재고 ID 1~500개, UUID requestId, 계획별 blind, note 최대 500. 수량·재고 version·품목·LOT·위치를 서버에서 읽는다. snapshot은 변경하지 않는다. |
| P2 권한 | viewer 읽기, editor/owner 생성·측정·재계수·제출. 기준/재계수 checkpoint 수량은 모든 비 owner 응답에서 null(계획 생성자도 예외 없음). 기존 일반 재고 읽기 권한은 변경하지 않는다. |
| P3 측정 | countedQuantity 0 이상 numeric(14,4), 서버 HALF_UP. expectedEntryVersion으로 다른 측정 덮어쓰기를 409로 거절. 같은 작성자의 같은 값 재전송은 원래 결과를 읽는다. |
| P4 제출 | 계획→재고 ID 순 잠금. 수량 또는 version이 checkpoint와 다르면 409, 변동 행에 requiresRecount를 먼저 커밋한다. 변동 없는 행의 측정은 유지하고 재고는 어느 행도 조정하지 않는다. |
| P5 재계수 | 표시된 행만 재계수 시작. 최신 수량/version을 새 checkpoint로 받고 측정만 지운다. 원래 snapshot은 유지한다. 이후 재고가 다시 변하면 다시 재계수한다. |
| P6 저장 | 모든 측정이 있고 모든 checkpoint가 유효해야 기존 재고 명령으로 일괄 적용. 예약보다 적은 수량은 전체 409. 조정과 submitted/countId 기록을 한 트랜잭션에 저장한다. |
| P7 재송신 | 생성 키를 잠가 서로 다른 대상의 동시 UUID 재사용도 409. 제출은 계획 행 잠금으로 직렬화, submitted는 조정을 재실행하지 않는다. 거래 없는 실사도 계획에는 제출 결과가 남는다. |
| P8 화면 | Inventory → Count plans. 블라인드 계획은 수량 미표시, 일반 계획도 owner만 기준 표시. 측정 저장 뒤 변경한 미저장 값은 제출을 막는다. 실패 결과 미확인 시 입력을 잠그고 원래 생성 키/측정 version/제출을 그대로 재시도한다. |
| P9 담당자 | (2026-10-24, 2ge, V114, 사용자 위임 "의사결정도 recommend 로 계속 알아서 밀고나가"로 창고 작업 W7과 같게) `POST /inventory-count-plans/{id}/assignee {assignedTo}`: 쓰기 권한, 제출 전 계획만(409), 프로젝트 소유자나 활성 구성원만(400), 비우면 아무도 아님. 응답의 `assignedTo`. 담당자라고 기준 수량을 보지 않는다(P2 그대로, 소유자만) |
| P10 알림 | 새 담당자에게 앱 안 `count_plan`(`Count plan Aisle 3 is yours`, 본문 `A blind count of 1 stock record.`, ref `count_plan` → 재고 → Count plans). 스스로 맡거나 같은 사람에게 다시 주면 알리지 않는다([알림](notification.md) NT2). 화면: 고른 계획의 **Assigned to**(No one·활성 구성원, 내 것은 `(me)`), 목록의 내 계획은 ` · yours` |

API: `GET/POST /inventory-count-plans`, `GET /inventory-count-plans/{id}`, `PUT /{id}/lines/{lineId}`, `POST /{id}/lines/{lineId}/recount`, `POST /{id}/submit`. 목록은 최신 50개; 이전 계획은 ID 조회 가능. 모든 경로 프로젝트 접근을 재검증한다. 다른 계획의 lineId는 404, 잘못된 JSON·UUID·수량·중복 대상은 400, 충돌·재계수·submitted 수정은 409.

검증: 실사 계획 통합 14건 포함 전체 백엔드 1,084건(실패·오류·건너뜀 0), 라인 81.29%·분기 67.65%·커버리지 기준·빌드 통과. 화면 모델 3건·전체 프런트 472건·타입 검사·기존 JS/JSX lint·빌드 및 모킹 브라우저 6건 통과. 미저장 수정 제출은 수정 전 브라우저 실패를 재현했다. 기존 DB/서버/BOM에는 쓰지 않았다.

Owner의 blind 기준량은 화면에서 기본 숨기며 Review baseline quantities를 직접 선택해야 보인다. 다른 역할에는 해당 제어와 수량을 제공하지 않는다. 모의 API 6건과 관련 기능 합계 18건 재확인.
