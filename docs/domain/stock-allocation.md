# 작업지시 재고 할당 (Reservation / Allocation / Consumption)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-WMS-003 Reservation/Allocation(reservation, allocation, consumption 구분). **새 마이그레이션 V36** (`stock_allocation`).

## 왜 필요한가

지금까지 예약(Reserve)은 재고 행의 예약 수량만 늘렸고 **누구를 위한 예약인지** 남지 않았습니다. 그래서
- 작업지시에 쓸 자재를 미리 잡아 두면 그 작업지시의 준비 점검·자재 소요에서도 "부족"으로 보였고,
- 그 작업지시의 실행이 예약된 재고를 쓰려면 먼저 손으로 예약을 풀어야 했습니다.

할당은 예약에 **작업지시**를 붙이고, 그 작업지시의 실행이 재고를 쓸 때 예약을 사용(consumption)으로 바꿉니다.

## 모델

`stock_allocation`: 작업지시 하나 × 재고 행 하나의 예약. 할당 수량, 사용한 수량, 돌려준 수량, `status`(open·closed), 만든 사람·시각, 닫힌 시각. **남은 양** = 할당 − 사용 − 반환. DB 제약: 수량 > 0, 사용 + 반환 ≤ 할당, 닫혔으면 사용 + 반환 = 할당.

재고 수량은 계속 재고 명령으로만 바뀝니다. 할당은 `reserve`, 반환·사용 전환은 `release` 이동으로 남고(참조 `stock_allocation`·할당 ID), 이동 원장에서 보입니다.

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| A1 | 승인·진행 중 작업지시에만 할당 | 409 `Work order WO-… is draft; allocate for an approved or started order.` |
| A2 | 할당할 양: 요청 줄(품목·수량, 품목 단위)이 있으면 그대로. 없으면 작업지시 BOM으로 남은 수량(목표 − 끝난 실행 산출)의 소요량 − 끝나지 않은 실행이 이미 투입한 양. BOM도 수량도 없고 줄도 없으면 | 400 `… has no BOM and quantity; give the lines to allocate.` |
| A3 | 이미 열린 할당이 있는 만큼은 다시 잡지 않음(같은 요청을 다시 해도 추가 없음) | |
| A4 | 후보 재고: 상태 available, 가용 > 0, LOT는 사용 가능하고 만료 안 된 것. **유효기한 이른 LOT 먼저**, 같으면 오래된 행 먼저([피킹 목록](warehouse-task.md)과 같은 순서). 품목 재고 행을 id 순으로 잠그고, 프로젝트별 advisory lock 아래에서 계획 | |
| A5 | 모자라면 할당한 만큼만 하고 부족을 알림(`plan[].shortage`) | |
| A6 | **대체 자재**(2026-11-02, [ADR-032](../architecture/adr/ADR-032-bom-substitute-materials.md) 결정 6): BOM으로 잡을 때, 끝나지 않은 실행이 투입한 대체 자재와 이 작업지시에 이미 열린 대체 자재 할당을 주 자재 몫(주 자재 소요량 ÷ 대체 자재 소요량)으로 센다(`allocatedBefore`). 자동 대체는 없다. 대체 자재는 줄을 지정해 할당한다 | |
| A7 | **선택 자재**(2026-11-02, [ADR-033](../architecture/adr/ADR-033-bom-optional-materials.md) 결정 6): BOM으로 잡을 때 작업지시가 고른 선택 자재를 넣는다. 고르지 않은 선택 자재는 잡지 않는다 | |
| C1 | 작업지시의 실행이 재고 행에서 투입을 기록하면, 그 작업지시의 그 행 열린 할당을 오래된 것부터 투입량만큼 먼저 풀고(`release`) 사용으로 셈. 같은 트랜잭션이라 투입이 거절되면(가용 부족 등) 할당도 그대로 | 투입 규칙대로 |
| C2 | 다른 작업지시(또는 작업지시 없는 실행)는 할당된 재고를 쓸 수 없음(가용에서 빠져 있음) | 409 `Not enough available stock …` |
| R1 | 반환: 한 건(`…/{allocationId}/release`) 또는 모두(`…/release`). 닫힌 할당 반환은 409. 완료·취소된 작업지시도 반환 가능 | |
| R3 | 작업지시를 **완료하거나 취소하면** 열린 할당의 남은 양을 같은 트랜잭션에서 자동으로 반환 | |
| R2 | 실행 투입을 취소하면 재고는 돌아오고, 묻지 않으면 **다시 할당되지 않음**(자유 재고). 물으면 R4 | |
| R4 | **다시 할당**(2026-10-24, 2fk): `POST /production-runs/{id}/items/{itemId}/cancel` `{reason, reallocate: true}`. 작업지시의 물리 실행이 재고 행에서 집은 투입만. 그 투입이 집은 양 전부(품목 단위로 바꾼 소수 4자리)를 같은 재고 행에 그 작업지시의 **새 할당**으로 잡는다(이동 원장 `reserve`, 메모 `Allocated to work order WO-… again: run item … was cancelled`). A4처럼 재고 상태 available, LOT 사용 가능·만료 전이어야 한다. 취소·재고 되돌림·할당이 한 트랜잭션이라 다시 할당할 수 없으면 취소도 하지 않는다. 작업지시·할당 잠금을 재고보다 먼저 건다(C1과 같은 순서) | 400 산출·재고 행 없는 투입·작업지시 없는 실행·시뮬레이션 실행 / 409 작업지시가 승인·진행 중이 아님, 재고 행을 쓸 수 없음 |

준비 점검과 간이 MRP는 할당을 반영합니다.
- **준비 점검**: 그 작업지시에 할당된 재고는 그 작업지시의 가용량에 셈(재고 행 단위로 가용 + 자기 할당).
- **간이 MRP**: 승인·진행 중 작업지시의 열린 할당을 품목별로 가용량에 더함(그 작업지시들의 필요가 이미 합계에 들어 있으므로). 반제품 전개의 가용에도 더함.

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /work-orders/{id}/allocations` | 읽기 | `{workOrderId, workOrderNumber, workOrderStatus, allocations[{allocationId, inventoryId, itemId, itemCode, lotId, lotNo, location, quantity, consumedQuantity, releasedQuantity, remaining, status, createdBy, createdAt}], plan: []}` 오래된 순 |
| `POST /work-orders/{id}/allocations` | 쓰기 | `{lines?: [{itemId, quantity}]}`. 응답 `plan[]`: 품목별 `needed`, `allocatedBefore`, `allocatedNow`, `shortage` |
| `POST /work-orders/{id}/allocations/{allocationId}/release` | 쓰기 | 남은 양 반환 |
| `POST /work-orders/{id}/allocations/release` | 쓰기 | 열린 할당 모두 반환 |

## 화면

실행 → Work Orders → **Readiness** 아래 **Allocated stock**
- BOM이 있는 승인·진행 중 작업지시면 **Allocate materials**(BOM 기준). 결과 `Allocated FLOUR 8. Short: FLOUR 2.`
- 표: 자재 · LOT · 위치, 할당, 사용, 반환, 남음, 상태, 열린 할당의 **Release**. 열린 것이 있으면 **Release all**
- 바꾸면 재고·준비 점검을 다시 불러옴
- 실행 상세의 투입 **Cancel**(R4): 사유를 물은 뒤, 작업지시의 물리 실행이 재고 행에서 집은 투입이면 `Allocate the returned stock to the work order again?`(OK는 다시 할당, Cancel은 자유 재고). 다시 할당하면 `Cancelled. The returned stock is allocated to the work order again.`, 할당·준비 점검을 다시 불러옴

## 검증

- `StockAllocationIntegrationTest` 2건(실제 Postgres)
  - 줄 할당: 6 kg·10 kg 행에서 8 kg → 오래된 행 6 + 새 행 2, 예약 6·2. 초안 작업지시 409, BOM·줄 없음 400. 같은 요청 반복 → 추가 0. 20 kg → 8 추가·부족 4, 그 할당 반환 → 예약 2로. 닫힌 할당 반환 409. 다른 작업지시의 실행이 할당 행에서 투입 409. 자기 실행이 4 투입 → 사용 4·남음 2, 행 수량 2·예약 2. 5 투입 → 409이고 할당 그대로. 2 투입 → 할당 닫힘. 모두 반환 → 새 행 예약 0. 외부인 403
  - BOM 할당: 빵 20개 = 밀가루 10 kg, 재고 8 → 8 할당·부족 2. 준비 점검 가용 8·부족 2(자기 할당 반영), 간이 MRP 가용 8·부족 2
- `ProductionRunServiceImplTest`에 할당 서비스 mock 추가. 전체 백엔드 451건 통과
- `allocationModel.test.ts` 2건
- 실 화면 `e2e/stock-allocations.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가, BOM 없음 — 줄 할당 API 사용): 10 kg 행, 작업지시 승인, 6 할당(예약 6) → 작업지시 실행이 4 투입 → Readiness 아래 할당 줄 6/4·open → Release → closed, 행 수량 6·예약 0. 끝나면 실행 마감·작업지시 완료

## 2026-10-05 확정: 예약 재고 피킹

**Accepted (정책).** B 승인. inventory가 이동하고 production 공개 AllocationCommand로 예약 유지·부분 분할, 재고와 할당 한 트랜잭션. 아래 메모는 검토 이력이며 구현 전이다. [최종 결정](../status/DECISIONS-2026-10-05.md)이 아래 예전 선택지보다 우선한다.

## 결정 메모: 할당된 재고를 먼저 집는 피킹 (Proposed, 2026-10-03)

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §4 "할당된 재고를 먼저 집는 피킹, 투입 취소 때 재할당"의 의존 방향을 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

**지금 코드 (2026-10-03 확인)**
- 할당(`stock_allocation`, V36)은 production에 있고 **재고 행 하나**(`inventory_id`)를 가리킨다. 할당한 양은 그 행의 예약이다.
- 피킹(`WarehouseTaskService`, inventory)은 가용(보유 − 예약)만 후보로 삼는다. 이동(T4)도 가용까지만 옮긴다. 그래서 할당된 재고는 피킹 후보에서 빠지고, 옮기려면 먼저 반환해야 한다.
- 의존은 이미 양쪽으로 있다.
  - inventory → production: `WarehouseTaskService`가 공개 API `WorkOrderQuery`를 쓴다.
  - production → inventory: `StockAllocationService`가 `InventoryRepository`·`LotMasterRepository`(동결 위반)와 `InventoryCommandService`를 직접 쓴다.
- [ADR-002](../architecture/adr/ADR-002-module-dependency.md) 결정 3: 지금 결과가 필요한 호출은 동기 Query/Command, 사건 알림은 Domain Event. Domain Event의 형식과 발행 위치는 아직 정하지 않았다.

할당된 재고를 집으려면 두 가지가 한 트랜잭션에서 함께 일어나야 한다.
1. inventory: 예약을 함께 옮기는 이동(출발 행 보유·예약 −q, 도착 행 보유·예약 +q)
2. production: 그 할당이 도착 행을 가리키게 바꾸기(일부만 옮기면 할당을 둘로 나눔)

| 안 | 누가 부르나 | 필요한 공개 API | 약점 |
|---|---|---|---|
| A | production이 지휘: 할당 서비스가 inventory 공개 Command(예약 이동)를 부른 뒤 자기 할당을 고침. 피킹 작업 완료는 production을 부름 | inventory `StockCommand.moveReserved`, production `AllocationCommand` | 피킹(inventory)이 완료 때 production을 불러야 해서 결국 양방향 |
| B | inventory가 지휘: 피킹 작업 완료가 자기 안에서 예약까지 옮긴 뒤 production 공개 Command로 할당을 새 행에 옮김. 피킹 계획은 production 공개 Query로 그 작업지시의 열린 할당을 먼저 후보로 둠 | production `AllocationQuery.openAllocations(workOrderId)`, `AllocationCommand.moveAllocation(allocationId, toInventoryId, quantity)` | inventory → production 호출이 하나 더 늘어남 |
| C | 사건으로: inventory가 "예약과 함께 옮김" Domain Event를 내고 production이 같은 트랜잭션에서 받아 할당을 고침 | 이벤트 형식 | ADR-002가 정하지 않은 Domain Event 형식을 먼저 정해야 함 |

**권장안: B.**
- 피킹·이동·행 잠금은 inventory 책임이고, 이미 inventory → production 공개 API(`WorkOrderQuery`) 방향이 있다. 같은 방향으로 Query/Command 하나씩만 더하면 된다.
- production 쪽 할당은 행 id와 수량만 받으므로 inventory 저장 구현을 모른다.
- 투입 취소 때 재할당(R2)도 같은 `AllocationCommand`로 열 수 있다.
- 남는 반대 방향(production → inventory, 할당 때 행을 잠그고 고르는 일)은 `StockAllocationService`를 고칠 때 Stage B로 inventory 공개 API(후보 조회·예약 Command)로 옮긴다. 이때 공개 API끼리 서로 부르게 되는데, ADR-002는 repository만 막으므로 규칙 위반은 아니다. 다만 순환 호출을 피하려면 한 트랜잭션 안에서 inventory → production → inventory로 되돌아오지 않게 한다.
- 영향받는 코드: `WarehouseTaskService`(피킹 계획·완료), `InventoryTransferService` 또는 새 예약 이동, production `application.publicapi`에 `AllocationQuery`·`AllocationCommand`와 구현, `StockAllocationRepository`, 피킹 목록 화면의 "allocated" 표시.

## 이후

- ~~작업지시 완료·취소 때 열린 할당 자동 반환~~ → R3로 구현(2026-09-27). `StockAllocationIntegrationTest` 셋째 건: 3 할당 후 취소 → 예약 0·반환 3, 5 할당·2 사용 후 실행 마감·완료 → 예약 0·사용 2·반환 3
- ~~피킹 목록이 자기 할당 재고를 먼저 집기~~ → 2bm으로 구현(2026-10-05). 예약 유지·부분 이동·해제 후 거절.
- ~~LOT 나눠 투입(`RunInputAllocationService`)이 자기 할당 재고를 후보로 보기~~ → 구현됨(2026-10-02 문서 반영). 실행 작업지시의 열린 할당 수량을 그 재고 행의 쓸 수 있는 양에 더해 유효기한 순으로 나눈다. 만료 LOT는 할당돼 있어도 쓰지 않는다. 검증: `RunInputAllocationServiceTest`(`anActualRunCanTakeItsOwnReservedStockInExpiryOrder`, `anExpiredLotDoesNotBecomeUsableBecauseItWasReservedForTheOrder`)
- ~~투입 취소 시 재할당 선택~~ → R4(2026-10-24, 2fk). 끝난 실행 보정의 재할당은 아직(작업지시가 완료되면 R3로 모두 반환되므로 진행 중 작업지시의 보정만 해당)

## 투입 취소 때 다시 할당 (2026-10-24, 2fk)

사용자 위임(2026-10-24, "의사결정도 recommend 로 계속 알아서 밀고나가")에 따라 리드가 고른 권장안이다. 마이그레이션 없음.

1. 기본은 지금처럼 자유 재고(R2)다. 다시 할당은 취소할 때 요청한 경우만(기존 동작과 화면 흐름을 깨지 않는다).
2. 양은 그 투입이 집은 양 전부다. 할당에서 몇을 집었는지는 투입 줄에 남지 않아(C1은 할당 쪽 `consumed`만 늘린다) 따로 알 수 없고, 그 작업지시의 실행이 쓰려고 집은 재고이므로 작업지시에 돌려준다.
3. 원래 할당을 되살리지 않고 새 할당을 만든다. 원래 할당의 사용·반환 이력과 닫힌 상태는 그대로 남는다.
4. 다 잡을 수 없으면 일부만 잡지 않고 거절하며, 취소도 하지 않는다(409). 그때는 다시 할당 없이 취소한다.
5. 화면은 사유를 묻는 창 다음에 확인 창 하나. 작업지시 없는 실행·시뮬레이션·산출에는 묻지 않는다.

- 검증: `RunInputReallocationIntegrationTest` — 10 kg 행에 작업지시 6 할당 → 실행이 4 투입(보유 6·예약 2) → 산출 줄 다시 할당 400(취소 안 됨), 작업지시 없는 실행의 투입 400(보유 5 그대로), 다시 할당 없이 취소 → 보유 6 → 4 kg 투입을 다시 할당하며 취소 → 보유 10·예약 6, 할당 둘(처음 것 사용 4·남음 2, 새 것 4 open, 같은 행) → 같은 실행이 2 투입(보유 8·예약 4) → 다시 할당 없이 취소하면 예약 4 그대로(R2) → 이미 취소한 줄 409, 외부인 403. `runPlan.test.ts`(`canAllocateAgain`), 모의 e2e `run-input-reallocate.spec.ts`(확인하면 `reallocate: true`와 안내, 거절하면 `{reason}`만, 작업지시 없는 실행은 사유만 물음). 관련 백엔드 51건/7클래스(새 `RunInputReallocationIntegrationTest` 1, `StockAllocationIntegrationTest` 3, `ProductionRunServiceImplTest` 25, 예약 이동·창고 작업 20, `ModuleBoundaryTest` 2), 프런트 typecheck·lint·Vitest 709건(`runPlan.test.ts` +2), 모의 e2e `run-input-reallocate.spec.ts` 3건과 실행 상세 근처 6건 통과(격리본). 전체 백엔드 2,127건(300클래스) 실패·오류·제외 0(커버리지 검사 생략).

## 예약을 유지하는 위치 이동 (2026-10-05, §2 2bl)

`POST /allocated-stock-transfers` 요청: projectId, workOrderId, allocationId, fromInventoryId, toLocation, quantity(양수, 소수 4자리), UUID requestId. editor/owner만 실행한다. Inventory가 movement를 지휘하고 production의 공개 `AllocationTransferCommand`를 부른다. 다른 context 저장소 직접 참조를 추가하지 않는다.

- 자기 작업지시의 열린 할당·승인/진행 상태·같은 출발 재고·남은 양을 확인한다. 만료/비가용 LOT·격리 재고는 거절한다.
- 출발 보유/예약 −q, 도착 보유/예약 +q. 자유 수량은 두 위치에서 변하지 않는다. 양쪽 원장과 할당 수정은 같은 트랜잭션이다.
- 미사용 할당 전체를 옮기면 ID와 원래 양을 유지하며 목적 재고로 연결한다. 일부를 옮기거나 소비/반환 이력이 있으면 출발 할당의 양을 q 줄이고 목적 할당 q를 새로 만든다. 남은 예약이 0인 출발 이력은 닫는다. consumed/released는 출발 이력에 남고, 합계 allocated는 보존된다. 실제 이동 경로·수량·작성자는 이동 원장으로 추적한다.
- requestId 재생은 원래 출발·수량·목적·할당·작성자·예약 delta까지 일치해야 한다. 일반 재고 이동이 같은 키/메모를 쓴 것을 예약 이동으로 재생하지 않는다. 같은 이동 동시 재송신도 한 번만 적용한다.
- 위치 코드 잠금 → 작업지시 → 프로젝트 할당 잠금 → 두 재고 ID 순 잠금. 출발 재고만 먼저 잠그지 않는다. 생산 해제와 반대 방향 이동의 잠금 순서를 맞춘다.
- 화면: Work Orders → Readiness → Allocated stock → Move allocated. 목적/수량과 서버 거절 표시, 결과 미확인 시 입력·할당 변경을 잠그고 원래 요청을 재전송한다. 성공 시 재고·할당·준비 점검·원장·LOT를 갱신한다.

수동 이동 검증: 관련 통합 9건, 전체 백엔드 1,093건(실패·오류·건너뜀 0), 라인 81.34%·분기 67.60%·커버리지 기준·빌드, 프런트 472·타입·기존 JS/JSX lint·빌드, 가짜 API E2E 3건 통과. 이후 자동 피킹 연결 중이며 최종 회귀 수치는 아래 후속 절에 남긴다. 개발 DB/BOM 변경 없이 검증했다.

## 자동 예약 우선 피킹 (2026-10-05, §2 2bm, 완료)

V54는 warehouse_task의 allocation_id만 추가해 어느 할당을 옮길지 고정한다. V53 기준의 전용 DB에서 SQL 후보를 BEGIN/ROLLBACK으로 검증했고 개발 DB에 적용하지 않았다. 기존 작업은 null로 자유 재고 이동을 유지한다.

작업지시 피킹 목록은 자기 할당 재고를 먼저, 그다음 자유 재고를 FEFO로 선택한다. 다른 작업지시 예약은 준비량에 넣지 않는다. 이미 계획한 같은 할당 수량을 빼므로 중복 계획하지 않는다. 작업 완료/부분 완료는 위 예약 유지 이동을 사용하며, 할당이 해제됐다면 409로 거절하고 자유 재고 이동으로 바꾸지 않는다. 예약 작업은 자유 재고 계획 수량에서 빼지 않는다. 화면에는 Reserved for work order를 표시하고 완료 후 할당·준비 점검도 갱신한다.

관련 통합 6건: 예약 우선·부분 완료·중복 계획·해제 거절·타 작업지시 예약·만료 LOT·소수 정밀도. 0.0001 미만으로 반올림되는 수량은 400, HALF_UP 4자리 수량을 작업·원장·할당 모두에 한 번 적용한다. 전체 백엔드 1,099건 실패·오류·건너뜀 0, 라인 81.39%·분기 67.74%·커버리지 기준·빌드 통과. 프런트 473건·타입·기존 JS/JSX lint·빌드 통과. 예약 피킹 모의 API E2E 2건 및 실사·예약 이동·원가·일정 합계 18건 통과.
