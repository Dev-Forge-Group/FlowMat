# 창고 작업 (Putaway / Pick)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-WMS-002 Putaway/Pick/Transfer 중 **Putaway·Pick**(Transfer는 [재고 이동](stock-transfer.md)으로 이미 구현). 코드는 가져오지 않았습니다. **새 마이그레이션 V31** (`warehouse_task`). 2026-10-03에 **V49**(작업자 배정 `assigned_to`, W7)가 더해졌습니다.

## 왜 필요한가

재고 이동은 누르는 순간 바로 옮겨집니다. 현장에서는 보통 "무엇을 어디로 옮길지"를 먼저 정해 작업자에게 주고, 작업자가 실제로 옮긴 뒤 완료합니다.
- 입고된 물건을 선반에 넣기(Putaway)
- 작업지시 자재를 생산 라인으로 꺼내기(Pick)

계획과 실행이 나뉘어 있어야 누가 무엇을 아직 안 했는지 보이고, 같은 재고를 두 번 계획하지 않습니다.

## 원칙

- 작업은 **계획**입니다. 만들 때 재고는 움직이지 않습니다.
- **완료**하면 보통의 재고 이동(`transfer_out`/`transfer_in`, [재고 이동](stock-transfer.md))을 기록합니다. 재고 수량은 계속 재고 명령으로만 바뀝니다(벤치마크 원칙).
- 도착 위치는 [보관 위치 목록](storage-location.md)의 규칙을 따릅니다. 목록이 있는 프로젝트는 활성 목록 위치만 됩니다.

## 모델

`warehouse_task`: `task_no`(프로젝트별 `WT-0001`), `task_type`(putaway·pick), `status`(open·done·cancelled), 원본 재고 행 `inventory_id`와 그 품목·LOT, `quantity`(>0), `from_location`(만들 때의 위치), `to_location`, `work_order_id`(피킹 목록이 작업지시에서 나왔을 때), 메모, 만든 사람·시각, 끝낸 사람·시각, 완료한 이동 `transfer_id`, 취소 이유, 맡은 사람 `assigned_to`(V49, 없으면 아무도 안 맡음).

DB 제약: 종류·상태 CHECK, 수량 > 0, `(status = 'open') = (finished_at IS NULL)`, 완료면 `transfer_id` 필수, `(project_id, task_no)` 유일.

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| W1 | 한 재고 행의 **자유 수량** = 가용량 − 그 행의 열린 작업 수량. 작업은 자유 수량까지만 | 409 `Only 4 of this record is free to move (10 available, 6 already in open tasks).` |
| W2 | 도착 위치는 필수이고, 행의 현재 위치와 같으면 안 됨(대소문자 무시) | 400 |
| W3 | 완료 = 재고 이동(`requestId`는 `warehouse-task:<작업 ID>`). 이동이 거절되면(가용 부족, 격리 등) 작업도 열린 채 남음 | 이동 규칙대로 |
| W4 | 열린 작업만 완료·취소. 취소에는 이유 필요 | 409 / 400 |
| W16 | 완료에 `expectedQuantity`를 보내면 프로젝트·작업 잠금 뒤 현재 열린 작업 잔량과 숫자로 비교한다. 다르면 이동·작업 분할 없이 거절한다. 부분 완료 폼은 열었을 때의 잔량을 유지하며, 응답 유실 뒤 같은 폼의 재전송에 새 잔량을 자동 적용하지 않는다. | 409 |
| W5 | 번호 매기기·계획·완료(부분 포함)·취소·배정과 위치 수정·삭제는 같은 프로젝트 advisory lock(`warehouse-task\|<프로젝트>`)을 먼저 잡음. 그 뒤 위치 코드·재고·작업 행을 잠그고 상태를 읽음. 동시 계획의 중복과 위치 변경·완료의 교착, 기다리던 계획의 옛 출발 위치 저장을 막음(2026-10-04) | |
| W6 | 완료에 수량을 주면 그만큼만 옮김(2026-10-03). 옮긴 부분은 **새 번호의 done 작업**(같은 행·품목·LOT·출발·도착·작업지시, 메모 `Part of WT-0003`, 이동 `requestId`는 새 작업의 것)이 되고, 원래 작업은 남은 수량으로 열린 채 남음. 수량이 없거나 작업 수량과 같으면 지금처럼 전부. 응답은 끝난 작업(부분이면 새 작업) | 0 이하·작업 수량 초과 400 `Move more than 0 and at most 6 for task WT-0003.` |
| W7 | **작업자 배정**(2026-10-03, V49): 열린 작업에 맡을 사람을 정함. 프로젝트 소유자나 활성 구성원만, 비우면 아무도 안 맡음. 부분 완료(W6)로 나온 작업도 같은 사람. 목록은 맡은 사람으로 거를 수 있음. 알림·자동 배분은 없음 | 400 `kim is not a member of this project.` / 끝난 작업 409 |
| W8 | **스캔으로 완료**(2026-10-03, 2026-10-05 W12 서버 확인 추가): 품목(바코드·SKU·코드)을 스캔하면 그 품목의 열린 작업(나에게 배정된 것 먼저)을 찾고, 도착 위치를 스캔해 작업의 도착과 같을 때만(대소문자·앞뒤 공백 무시) 완료. 다른 위치면 완료하지 않고 알림. 완료는 평소의 완료(W3)와 같은 API | 화면 `That is DOCK; WT-0003 goes to LINE.` |
| W9 | **스캐너 화면**(2026-10-03, 프런트만): Tasks 탭 `?view=scanner`(주소로 남아 휴대 기기 즐겨찾기로 열 수 있음)는 스캔 상자와 **내 열린 작업**(나에게 배정된 것, 그다음 배정 없는 것; 다른 사람의 작업은 뺌)만 보여 준다. 글자·버튼이 크고(18px, 높이 44px) 한 줄씩 쌓이며 Scan item에 바로 입력된다. 계획·피킹 목록 폼은 없다 | 휴대 기기에서 스크롤 없이 스캔만 하도록 |
| W10 | 피킹 위치의 준비 수량과 피킹 후보는 같은 사용 가능 기준: available·가용량 > 0이고 LOT가 있으면 available 또는 reserved·만료되지 않음. 만료일 당일까지 포함하고 다음 날부터 제외([LOT 유효기한](lot-expiry.md) E1). 만료 LOT가 이미 피킹 위치에 있어도 필요한 피킹 수량을 줄이지 않음(2026-10-04) | |
| W11 | 열린 피킹의 준비 수량은 출발 재고 행별로 합친 작업 수량과 그 행의 현재 가용량 중 작은 값. W10처럼 사용 가능한 재고만, 아직 피킹 위치 밖인 행만 계산해 이미 도착한 재고와 두 번 세지 않음(2026-10-05). 만료·격리된 LOT의 작업은 준비 수량에서 제외하되 자동 취소·수량 변경은 하지 않음 | |
| W12 | 스캐너 완료 요청은 스캔한 위치를 `expectedToLocation`으로 보냄(2026-10-05). 서버는 W5 프로젝트 잠금과 쓰기 권한 확인 후 현재 작업의 도착 코드와 대소문자·앞뒤 공백을 무시해 비교. 다르면 이동·부분 작업 생성 없이 거절. 생략·null은 기존 일반 완료와 같은 동작. 명시한 값은 비어 있지 않고 최대 100자 | 빈 값·길이 초과 400, 불일치 409 `expectedToLocation does not match task WT-0001 destination NEW. Scan its destination again.` |

## 피킹 목록

`POST /warehouse-tasks/pick-list` — `{projectId, stagingLocation, workOrderId? , quantity?, lines?: [{itemId, quantity}], note?}`
- **작업지시**를 주면: 승인·진행 중이고 BOM과 목표 수량이 있어야 함(아니면 409/400). 필요량은 BOM 소요량(`requirementsForRun`, 품목 재고 단위)이고 `quantity`를 비우면 목표 수량 전체
- **품목 줄**을 주면 그대로(재고 단위). 둘 다 주거나 둘 다 없으면 400
- 품목마다: 필요량 − 이미 **피킹 위치에 있는** 가용 재고 − 그 위치로 가는 **열린 피킹**을 뺀 만큼만 새로 계획. 다시 눌러도 중복 계획하지 않음
- 후보 재고: 피킹 위치가 아닌 곳, 상태 available, 가용 > 0, LOT는 사용 가능하고 만료되지 않은 것. **유효기한 이른 LOT 먼저**, 같으면 오래된 행 먼저([LOT 유효기한](lot-expiry.md) FEFO와 같은 순서). 각 행의 자유 수량까지만
- 품목의 재고 행은 id 순서로 잠근 뒤 읽음(계획 중에 다른 이동이 같은 행을 비우지 못함)
- 응답: 만든 작업과 품목별 `required`, `atStaging`, `alreadyPlanned`, `plannedNow`, `shortage`

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /warehouse-tasks?projectId=&status=&workOrderId=&assignedTo=` | 읽기 | 최신순. 응답에 `assignedTo` |
| `POST /warehouse-tasks` | 쓰기 | `{projectId, taskType?(putaway), inventoryId, quantity, toLocation, note?}` |
| `POST /warehouse-tasks/pick-list` | 쓰기 | 위 피킹 목록 |
| `POST /warehouse-tasks/{id}/complete` | 쓰기 | 이동 기록 후 done. 본문 `{quantity?, expectedToLocation?, expectedQuantity?}`(W6·W12·W16) |
| `POST /warehouse-tasks/{id}/cancel` | 쓰기 | `{reason}` |
| `PUT /warehouse-tasks/{id}/assignee` | 쓰기 | `{assignedTo}`(빈 값·null은 비움, W7) |

## 화면

재고 → **Tasks** 탭
- 작업 표(상태 거르기 Open 기본·All·Done·Cancelled, 2026-10-03부터 **Assigned to** 거르기 Anyone's·Mine·Not assigned): 번호, 종류, 품목·LOT, 수량, 출발, 도착, 작업지시, **Assigned**(열린 작업은 선택칸 `Assignee of WT-0001`: `—`와 로그인한 사람·활성 구성원, 끝난 작업은 이름), 상태(메모·취소 이유도), **Done** / **Part…**(옮길 수량 입력 → **Move**, 0 이하·초과는 화면에서 거절) / **Cancel**(이유 입력)
- 작업 표 위 **Scan to do a task**(2026-10-03, W8): **Scan item** → 열린 작업이 여럿이면 **Tasks for this item** 버튼으로 고름 → `WT-0003: move 4 BOLT from DOCK to LINE.` → **Scan place** → **Done here**. 스캐너가 글자를 치고 Enter를 누르는 방식이면 그대로 쓰임
- 상태·배정 선택 옆 **Scanner view**(W9) → 큰 스캔 상자와 **My open tasks (N)** 카드(`WT-0003 · pick`, `4 BOLT · LOT L1`, `DOCK → LINE`, 배정 없으면 `· not assigned`), **Full view**로 돌아감. 재고 화면의 탭 줄은 화면보다 길면 줄을 바꿔 쌓음(전에는 1280px 창에서도 페이지가 가로로 넘쳤음), 여백은 좁은 화면에서 줄어듦. 760px 이하에서는 화면보다 넓은 탭(표)이 페이지 전체를 넓히지 않고 재고 화면 안에서 가로로 스크롤됨(`pages/inventory/ui/inventory.css`, 2026-10-03): 375px에서 13개 탭 모두 문서 너비 375(전에는 Stock 1187, Items 919 등)
- **Putaway** 폼: 재고 행(품목 · LOT · 위치 · 자유 수량, 자유 수량이 있는 available 행만), 수량, 도착(위치 목록 제안), 메모
- **Pick list** 폼: 작업지시(승인·진행 중이고 BOM이 있는 것) 또는 품목 줄, 피킹 위치. 결과로 "N picks planned." 과 모자란 품목(`Short: …`), 품목별 계획 표
- 완료하면 재고·LOT·이력·위치 목록 화면도 다시 불러옴

- 2026-10-05 스캐너 보완(W8·W9·W12): 선택한 품목·작업 ID로 최신 열린 작업을 다시 읽어 도착 위치·남은 수량·배정을 갱신하며, 끝났거나 취소·제외된 작업은 완료 입력을 감추고 재스캔 안내. 품목 Enter → 도착 위치에 포커스, 완료 중 입력·선택 잠금, 휴대 기기 화면은 완료 후 품목 입력에 포커스 복귀. 완료 알림의 수량·위치는 서버가 실제 완료한 응답으로 표시. 거절된 완료 요청 뒤 작업 목록을 다시 읽어 새 위치를 스캔할 수 있음.

## 검증

- 부분 완료(2026-10-03): `WarehouseTaskIntegrationTest` 셋째 — 10개 행에서 6 계획 → 0·7 400 → 2 → 새 번호 done 작업(수량 2, 메모 `Part of WT-…`, 출발·도착 그대로, 이동 있음), 행 8, 원래 작업 열린 채 4 → 수량 없이 완료 → 행 4 → 3 계획을 수량 3으로 완료하면 나뉘지 않음. `warehouseTaskModel.test.ts` `partQuantity`. 실 화면 `e2e/warehouse-tasks.spec.ts` 끝: 피킹 하나에 **Part…** → 초과 수량 거절 문장 → 1 → 열린 줄 수량 1 감소, Done 거르기에 `Part of WT-…` 줄. 이때 `WarehouseTaskService`의 다른 도메인 저장소 2줄을 공개 API로 옮김(ADR-002 Stage B: `CatalogQuery`, 새 production `WorkOrderQuery`). `ModuleBoundaryTest` 동결 목록 98 → 96

- `WarehouseTaskIntegrationTest` 2건
  - Putaway: 10개 행에서 6개 계획 → 5개 추가 계획 409(자유 4), 같은 위치(대소문자만 다름) 400, 계획만으로는 재고 10 그대로 → 완료하면 이동 기록, 행 4 → 다시 완료·취소 409, 이유 없는 취소 400, 취소, open 거르기에서 빠짐, 외부인 403
  - Pick: 유효기한 60일·30일 LOT 각 6kg, 소금 1kg. BOM(빵 10개당 밀가루 5kg, 소금 200g)과 승인된 작업지시 20개 → 30일 LOT 6, 60일 LOT 4, 소금 0.4 계획. 초안 작업지시 409. 다시 요청하면 계획 0(이미 계획 10). 30일 LOT 피킹 완료 후 다시 요청하면 `atStaging` 6, 이미 계획 4, 새 계획 0. 품목 줄(소금 5) → 0.6 계획, 부족 4. 줄도 작업지시도 없으면 400. 작업지시로 거르면 3건
- 전체 백엔드 436건 중 새 실패 없음(실패 1건은 다른 작업자의 진행 중인 포트 테스트)
- `warehouseTaskModel.test.ts` 5건(자유 수량, 옮길 수 있는 행, 피킹 가능한 작업지시, 요청 본문, 부족 표시)
- 작업자 배정(2026-10-03, V49): `WarehouseTaskIntegrationTest` 넷째 — 구성원 아닌 사람 400 → 앞뒤 공백 둔 demo-owner 200 → `assignedTo`로 거르면 나오고 다른 사람이면 빠짐 → 외부인 403 → 2만 부분 완료한 작업도 같은 사람 → 빈 값으로 비움 → 완료 후 배정 409. V49는 세션 DB에서 트랜잭션 안에 먼저 돌려 본 뒤 적용. `warehouseTaskModel.test.ts` `assigneeChoices`(활성 구성원·나·지금 맡은 사람, 정렬)·`tasksFor`(Mine·Not assigned). 실 화면 `e2e/warehouse-tasks.spec.ts`: 피킹 2건 중 첫째를 demo-owner에게 → **Mine**이면 1건, **Not assigned**면 그 작업 빠지고 1건
- 스캔으로 완료(2026-10-03, W8): `warehouseTaskModel.test.ts` `tasksForScan`(열린 것만, 그 품목만, 내 것 먼저)·`scannedPlaceFits`(대소문자·공백 무시). 실 화면 `e2e/warehouse-tasks.spec.ts` 끝: 소문자 품목 코드 스캔 → 피킹 2건 중 하나 → DOCK 스캔은 `goes to LINE-…` 알림 → 소문자 LINE 스캔 → `moved to LINE-…`, 열린 피킹 1건
- 스캐너 화면(2026-10-03, W9): `warehouseTaskModel.test.ts` `scannerTasks`(내 것, 그다음 배정 없는 것, 다른 사람·끝난 것 제외, 로그인 정보 없으면 배정 없는 것만). 실 화면 `e2e/warehouse-tasks.spec.ts` 끝: **Scanner view** → 주소에 `view=scanner`, Scan item에 포커스, My open tasks에 그 품목, New putaway 폼 없음 → **Full view**. 375px 화면에서 문서 너비 375(전에는 1143), 콘솔 오류 0
- 실 화면 `e2e/warehouse-tasks.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가, BOM을 만들지 않고 품목 줄 사용): 10개 행 → Putaway 6 계획(재고 10 그대로) → Done → 행 4, Done 거르기에 done → 품목 줄 12를 라인으로 → "2 picks planned."·"Short: … 2" → 열린 피킹 2건. 끝나면 남은 작업 취소

- 2026-10-04 W5: 위치 변경·창고 완료 동시성 통합 9건 통과(수정 전 9건 실패, PostgreSQL 교착 40P01 확인).
- 2026-10-04 W10: `WarehousePickReadinessIntegrationTest` 3건 통과. 만료 LOT가 LINE에 4kg 있고 STORE에 사용 가능한 6kg이 있으면, 필요량 8kg에서 준비 0·계획 6·부족 2로 계산. 만료일 당일과 다음 날 만료 예정이면 준비 4·계획 4·부족 0. 만료 사례는 수정 전 준비 4로 계산돼 실패했습니다.

- 2026-10-05 W11: `WarehousePickReadinessIntegrationTest` 총 7건 통과(W10 3건 + W11 4건). 열린 피킹 4kg의 출발 LOT가 만료·격리되면 준비 예정 0, 대체 재고 6kg을 계획하고 필요량 8kg 중 부족 2kg. 2kg 출고 뒤에는 준비 예정 2·추가 계획 6·부족 0. 정상 LOT는 준비 예정 4·추가 계획 4. 반복 요청에서 새 작업은 없고 기존 작업은 열린 채 보존. 수정 전 만료·격리·일부 출고 3개 사례가 실패했습니다. 전체 백엔드 1,016건 실패 0, 라인 커버리지 81.01%와 기준 통과.

- 2026-10-05 W8·W9·W12: `warehouseTaskModel.test.ts` 선택 검증 4건 추가(해당 파일 15건), `e2e/task-scan-refresh.spec.ts` 가짜 API 6건 추가. 위치 변경·부분 완료·취소·Enter 포커스·입력 잠금·409 후 최신 작업 복구·서버 완료 수량을 확인합니다. 수정 전 브라우저에서 옛 위치·수량·취소 선택 3건, 포커스 1건, 409 복구 1건, 완료 응답 수량 1건을 재현했습니다. `WarehouseTaskScanConfirmationIntegrationTest` 7건은 전체/부분 완료의 옛 위치 거절과 무변경, 재스캔 완료, 코드 정규화·기존 요청 호환, 빈 값·길이 검증, 외부인 거절을 확인합니다(수정 전 4건 실패).

- 최종 검증(2026-10-05, 2ar–2as): 백엔드 전체 1,023건 실패·오류·건너뜀 0, 라인 커버리지 81.02%와 기준 통과. 프런트 전체 421건·타입 검사·린트·빌드, 관련 가짜 API E2E 7건(스캐너 6 + 위치 캐시 1) 통과. 실 API 전체 E2E 재실행 결과와는 구분합니다.

## 완료 응답 유실과 잔량 확인 (W16, 2026-11-10)

부분 완료는 원래 작업을 열린 채 남기므로, 잔량 6에서 2를 옮긴 응답을 잃고 `{quantity: 2}`를 다시 보내면 추가로 2가 이동할 수 있다. UI는 완료 요청에 사용자가 본 작업 잔량 `expectedQuantity`를 포함한다. 부분 완료 폼은 **열었을 때**의 잔량을 고정한다. 요청 중 또는 오류 뒤 목록이 갱신돼도 같은 폼의 재전송은 원래 값을 유지한다.

예를 들어 `{quantity: 2, expectedQuantity: 6}`이 성공한 뒤 같은 요청을 다시 보내면, 현재 잔량 4와 달라 409이며 이동·새 작업·원장은 추가되지 않는다. 사용자는 최신 작업 목록과 완료 이력을 확인하고 **Back → Part…**로 폼을 다시 열어 다음 이동을 시작한다. 전체 완료와 스캐너도 화면에 표시된 잔량을 보내며, 이미 끝난 작업은 기존처럼 409다. 위치 확인 `expectedToLocation`은 별도로 유지한다.

`expectedQuantity`는 선택 입력이며 0.0001~9999999999.9999 범위다. 반올림하지 않고 숫자로 비교하므로 `6`과 `6.00000`은 같다. 본문·필드를 생략하는 기존 API 요청은 호환된다. **기존 클라이언트의 부분 완료를 자동 재시도해도 안전하다는 뜻은 아니다.** 이 검사는 감소하는 작업 잔량을 확인하는 것이며, requestId를 저장해 이전 응답을 재현하는 멱등 API는 아니다. 결과를 모르면 먼저 조회·대사한 뒤 다음 의도를 시작한다. 스키마 변경은 없다.

`WarehouseTaskQuantityConfirmationIntegrationTest`는 응답 유실 후 동일 요청·다른 작업자의 선행 부분 완료·동시 요청·입력 범위·권한·숫자 scale·기존 요청을 실제 PostgreSQL/Redis와 JWT/MockMvc에서 검증한다. `PurchaseWarehouseJourneyIntegrationTest`는 별도 프로젝트에서 승인→부분 입고→putaway→LOT·원장·반품 대사를 연결한다. MockMvc 검증과 브라우저 mock 검증을 실제 네트워크 브라우저 여정과 구별한다.

## 이후

- ~~작업자 배정~~ → W7(2026-10-03, V49). ~~스캔으로 행·위치 확인~~ → W8(2026-10-03, 같은 화면의 스캔 상자). ~~휴대 기기 전용 화면 배치~~ → W9(2026-10-03). ~~위치 바코드~~ → [보관 위치 목록](storage-location.md) L10 위치 라벨(2026-10-03, 코드의 Code 128 바코드). ~~배정 알림~~ → [알림](notification.md) NT2(2026-10-11, V74)
- ~~부분 완료(일부만 옮기고 나머지 열린 채로)~~ → W6(2026-10-03)
- ~~실행 시작 화면에서 피킹 위치의 재고를 투입 후보로 먼저 보여 주기~~ → 2026-10-03: 작업지시가 있는 실행의 기록 폼에서, 그 작업지시의 **완료된 피킹**이 간 위치(대소문자 무시)에 있는 재고 행을 투입 후보 맨 앞에 두고 `· picked for this order`를 붙임. 각 묶음 안은 FEFO 그대로이고, 가장 먼저 만료되는 LOT이 여럿이면 피킹된 쪽에 `use first`. 화면이 이미 불러오는 완료 작업 목록으로 계산(서버 변경 없음, `stagingPlacesFor`·`inputLotOptions`). 검증: `warehouseTaskModel.test.ts`·`correctionModel.test.ts` 단위 테스트, 실행 상세를 쓰는 실 화면 E2E 3개(작업 지침·검사 기준·LOT 계보) 통과. 피킹에는 BOM이 있는 작업지시가 필요한데 dev DB에 BOM을 넣지 않기로 해서 이 흐름의 실 화면 E2E는 없음
- ~~할당 재고를 먼저 집는 피킹~~ → 2bm으로 구현(2026-10-05). 아래 W13–W15 참조.

## 예약 우선 피킹 (2026-10-05, V54)

- W13: 자기 작업지시의 열린 할당부터 FEFO로 계획하고 부족한 양만 자유 재고에서 계획한다. `allocationId`로 할당을 고정하며 다른 작업지시 예약은 사용하지 않는다. 같은 할당의 기존 열린 작업을 차감해 재요청을 중복하지 않는다.
- W14: 예약 작업 완료는 예약과 할당을 함께 옮기는 동일 트랜잭션이다. 부분 완료도 allocationId를 보존한다. 할당이 해제됐으면 409로 거절하고 원래 작업은 열린 채 보존한다. 예약 작업을 가용량에서 다시 빼지 않는다.
- W15: 부분 완료 수량은 HALF_UP 소수 4자리로 한 번 정규화한다. 0으로 반올림되는 양은 400. 같은 양을 작업 분할·재고 이동·할당 분할에 사용한다.

검증: 예약 피킹 통합 6건, 전체 백엔드 1,099건/실패 0·커버리지 기준·빌드, 프런트 473건·타입·기존 lint·빌드, 모의 API 예약 피킹 2건·관련 기능 18건 통과. V54는 SQL 격리 BEGIN/ROLLBACK 검증 후 추가했으며 개발 DB에는 미적용.
