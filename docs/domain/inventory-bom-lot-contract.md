# 재고 · BOM · LOT 1차 계약서

전문가 설계 결정(2026-09-23)을 구현 기준으로 옮긴 문서다. 설계가 열어 둔 부분은 **[구현 결정]**으로 표시했다. 검토 뒤 바꿀 부분이 있으면 이 문서를 먼저 고치고 코드를 맞춘다.

### 구현 결정 검토 현황 (D2, 2026-10-05)

**D2 상태: ACCEPTED — 확인 15 / 15(조건부 승인 1건 포함, 2026-10-31 원문 대조).** 15번째 품목 삭제 방어는 2026-10-19 주간 사전 결정([WORKBOARD](../status/WORKBOARD.md) "품목 삭제 방어")으로 확정·유지됐고, 2026-10-31 사용자 위임("의사결정 최적으로 내려봐" → "ㄱ")으로 15건이 모두 이 계약서의 절에 대응함을 대조해 집계를 올렸다(아래 "원문 대조"). [최종 사용자 결정](../status/DECISIONS-2026-10-05.md) §6이 예전 대기 표시보다 우선한다. 승인된 새 정책 중 유효일·복수 draft·LOT 검사 정책·선택 step 계보는 아직 구현 전이며 아래 기존 구현과의 차이를 명시했다.

- 사용자는 결정 관점 15/15 종료를 지시했지만, 앞선 보고는 실제 §4 품목 삭제 방어를 완료 실행 보정으로 잘못 집계했다. 보정은 별도로 정식 승인됐고, 삭제 방어를 임의 승인할 근거로 쓰지 않는다. 해당 한 건은 2026-10-19 주간 사전 결정으로 확정·유지됐다(집계 반영 2026-10-31).
- **구현 상태 ≠ 결정 상태.** 정책 승인으로 구현 완료를 표시하지 않는다. CHECK NOT VALID의 운영 데이터 정리·VALIDATE 완료 계획 조건은 유지한다.

| 상태 | 결정 |
|---|---|
| 기존 승인 유지 | 양수 quantity + 유형, 생산 거래 재고 API 역분개 금지, 빈 재고 행만 삭제, revision 새 행, 계획/실적 행 분리, 기존 1차 제한, LOT 전체 격리, lot_manage_yn 변경 제한 |
| 조건부 승인 유지 | CHECK NOT VALID — 운영 데이터 정리·VALIDATE 계획 필요 |
| 승인·정책 대체 | 단일 승인·자동 retire 대신 기간 비중첩 다중 승인; 여러 draft·pending 하나; 품목 검사 정책에 따른 초기 LOT 상태 |
| 승인 | LOT 종료/재개 Project owner; run 필수·step/process 선택 계보 |
| 별도 승인 | 완료 실행 보정은 editor 요청·owner 승인·재고/실적/계보 한 트랜잭션. D2 원문 15건 집계와 구분 |
| 확정·유지(2026-10-19) | §4 품목 삭제: 재고 행(빈 행 포함) 또는 폐기되지 않은 BOM 참조가 있으면 owner 요청도 409 |

**원문 대조(2026-10-31):** 위 15건과 이 계약서의 절(완료 실행 보정은 별도 승인이라 빼고 센다).

| # | 결정 | 절 |
|---|---|---|
| 1 | 양수 quantity + 거래 유형 | §2 요청·거래 유형 |
| 2 | 생산 거래는 재고 API로 역분개하지 않음 | §2 거래 유형, §3 |
| 3 | 빈 재고 행만 삭제 | §4 |
| 4 | 승인본 수정은 새 revision 행 | §5 상태 전이 |
| 5 | 계획 행과 실적 행 분리 | §3 생산 실행 기록 정정, §5 생산 시작 snapshot |
| 6 | 기존 1차 제한 | §1, §5 승인 검증 |
| 7 | 한 행 격리는 LOT 전체 격리 | §6 구현 결정 |
| 8 | 재고 있는 품목의 lot_manage_yn 변경 제한 | §6 규칙·구현 결정 |
| 9 | CHECK `NOT VALID`(조건부: 운영 데이터 정리·VALIDATE 계획) | §1 |
| 10 | 기간이 겹치지 않는 다중 승인(단일 승인·자동 retire 대체) | §5 기타 구현 결정 |
| 11 | 여러 draft, pending 하나 | §5 상태 전이 |
| 12 | 품목 검사 정책에 따른 초기 LOT 상태 | §6 구현 결정 |
| 13 | LOT 종료·재개는 Project owner | §6 상태·API |
| 14 | run 필수, step/process 선택 계보 | §3, §6 계보 |
| 15 | 품목 삭제 방어(재고 행·폐기되지 않은 BOM이 남으면 409) | §4 품목 삭제, `ItemDeleteGuardIntegrationTest` |

---

## 1. 재고 불변식

모든 정상 거래 뒤에 다음이 늘 참이어야 한다.

```
quantity           >= 0
reserved_quantity  >= 0
available_quantity  = quantity - reserved_quantity   (>= 0)
reserved_quantity  <= quantity
```

- 서버는 조건부 UPDATE 한 번으로 "변경 + 불변식 검사"를 원자적으로 한다. 조건을 만족하지 않으면 0행이 바뀌고 요청은 거절된다.
- DB에도 같은 CHECK 제약을 둡니다(V17). **[구현 결정 — 조건부 승인 2026-09-24]** 운영 DB의 기존 행을 막지 않도록 `NOT VALID`로 추가합니다. 승인 조건은 운영 데이터 정리와 `VALIDATE CONSTRAINT` 완료 계획입니다. 그 전까지 새 행과 변경된 행만 검사되고 기존 행은 규칙을 어길 수 있으므로, 이 제약을 기존 데이터의 보증으로 쓰면 안 됩니다. `VALIDATE`는 적용된 V17을 고치지 않고 새 버전 migration으로 추가합니다.
- 마이너스 재고 예외(관리자 강제 조정)는 1차 범위에서 뺀다. `allowNegativeStock`은 코드에서 늘 false다.

## 2. 재고 이동 명령 — `POST /inventory-transactions`

이력 등록 API가 아니라 **재고 변경 명령**이다. 한 요청이 검증, 재고 변경, 거래 저장을 한 트랜잭션으로 처리한다.

### 요청

| 필드 | 필수 | 설명 |
|---|---|---|
| `inventoryId` | ✓ | 대상 재고 행 |
| `transactionType` | ✓ | 아래 표의 값만 허용 |
| `quantity` | ✓ (quarantine류 제외) | **양수** 크기. 방향은 유형이 정함 |
| `direction` | adjustment만 | `increase` / `decrease` |
| `requestId` | ✓ | 클라이언트가 만든 멱등 키 (최대 100자) |
| `referenceType`, `referenceId`, `note` | | 참고 정보 |

**[구현 결정 — 승인 2026-09-24]** 설계 예시의 `quantityDelta`(부호 포함) 대신 `quantity`(양수) + 유형으로 방향을 정합니다. 부호 실수로 입고가 출고가 되는 일을 막기 위해서입니다.

서버가 계산하는 값: `reservedDelta`, `availableDelta`, `*After`, `projectId`, `itemId`, `lotId`, `createdBy`(인증 사용자). 요청 본문에 작성자 필드는 없다.

### 거래 유형

| 유형 | quantity | reserved | 외부 API | 비고 |
|---|---|---|---|---|
| `receipt` | +q | | ✓ | 입고 |
| `issue` | −q | | ✓ | 출고. 가용량 초과 금지 |
| `production_input` | −q | | 내부 | 생산 투입 |
| `production_output` | +q | | 내부 | 생산 산출 |
| `reserve` | | +q | ✓ | 가용량 초과 금지 |
| `release` | | −q | ✓ | 예약량 초과 금지 |
| `adjustment` | ±q | | ✓ | 결과 음수 금지 |
| `reversal` | 원거래 반대 | 원거래 반대 | 전용 엔드포인트 | §3 |
| `quarantine` | | | ✓ | 재고 행과 LOT를 격리 상태로 |
| `unquarantine` | | | ✓ | 격리 해제 |
| `transfer_out` / `transfer_in` | −q / +q | | 전용 엔드포인트 | 위치 간 이동의 두 거래. 개별 역분개 불가([재고 이동](stock-transfer.md)) |

- 격리된 재고에서는 `issue`, `production_input`, `reserve`, `transfer_out`이 거절된다.
- 유효기한이 지난 LOT의 재고에서는 `production_input`, `reserve`가 거절된다([LOT 유효기한](lot-expiry.md)).
- 기존 이력의 유형 이름은 V17에서 새 이름으로 바꾼다: `create→receipt`, `adjust→adjustment`, `run_input→production_input`, `run_output→production_output`, `delete→adjustment`.

### 멱등성

- `(project_id, request_id)` UNIQUE.
- 같은 `requestId` + 같은 내용이면 저장된 결과를 그대로 돌려준다(재고는 다시 바뀌지 않음).
- 같은 `requestId` + 다른 내용이면 409.
- 동시에 같은 키로 두 요청이 들어와 UNIQUE에 걸리면 뒤 요청은 409를 받는다. 다시 보내면 저장된 결과를 받는다.

#### 화면의 미확인 요청 재시도(2026-10-05)

HTTP 500 이상도 저장됐는지 알 수 없으므로 같은 값으로 다시 보내라고 안내한다(2026-10-05, §2 2ay). 400·409는 업무 거절 안내 그대로다.

Stock → History의 입고·출고·예약·해제·조정과 위치 이동은 응답을 받기 전까지 같은 요청 내용에 같은 `requestId`를 쓴다. 서버가 저장한 뒤 연결만 끊겨도, 같은 내용으로 다시 보내면 이전 결과를 받고 재고는 두 번 바뀌지 않는다. 입력이 달라지면 다른 명령이라 새 키를 쓴다. 성공 응답을 본 뒤 같은 내용을 다시 넣어도 새 명령이다.

- 저장 중 유형·수량·단위 입력·메모·도착지·방향·다른 LOT 사용을 잠근다. 연결 오류에는 결과를 알 수 없으니 같은 값으로 다시 보내라는 안내가 나온다.
- 키는 그 화면의 메모리에만 있다. History를 닫거나 페이지를 다시 열면 사라지므로, 먼저 같은 값으로 다시 보내 결과를 확인한다. 영구 저장, 새 서버 정책, 마이그레이션은 더하지 않았다.
- `stockCommand.test.ts` 6건(응답 유실, 성공 뒤 새 명령, 변경 내용, 미확인 요청 간 전환, 필드 순서·선택값 생략, 동시 제출)과 가짜 API `stock-command-retry.spec.ts` 2건(입고·이동 저장 후 응답 유실, 입력 잠금, 재시도 한 번 반영, 성공 뒤 새 거래). 고치기 전에는 두 브라우저 테스트가 서로 다른 키를 보내 실패했고, 고친 뒤 통과했다.

### 오류

| 상황 | 상태 |
|---|---|
| 유형 목록에 없음, quantity ≤ 0, direction 누락 | 400 |
| 가용/예약 부족, 격리 재고 출고 | 409 |
| requestId 충돌 | 409 |
| 쓰기 권한 없음 | 403 |

## 3. 역분개 — `POST /inventory-transactions/{id}/reversal`

요청: `{ "requestId": "...", "reason": "..." }` (둘 다 필수)

- 원거래는 지우지 않고 반대 방향 거래를 더한다.
- `transaction_type = reversal`, `reference_type = inventory_transaction`, `reference_id = 원거래 ID`, `note = 사유`, `created_by = 역분개한 사용자`. 원거래 작성자는 원거래 행에 남는다.
- 원거래 하나당 성공한 역분개는 하나(부분 UNIQUE 인덱스).
- 역분개도 현재 재고 정책을 다시 검사한다. 예: 입고 10을 역분개하려는데 이미 6을 출고했으면 거절된다.
- 역분개할 수 없는 유형: `reversal`, `quarantine`, `unquarantine`, **`production_input`, `production_output`**.
  - **[구현 결정 — 승인 2026-09-24]** 생산 거래를 재고 화면에서 역분개하면 재고만 돌아오고 생산 실행 기록과 LOT 계보는 그대로 남아 서로 어긋납니다. 생산 거래는 생산 실행에서 정정해야 하며, 이 API는 400과 안내 메시지를 돌려줍니다.
- 같은 품목·위치·LOT의 재고 행은 하나뿐이다(V17 유니크 인덱스). 두 번째 생성은 "LOT X already has a stock record at Y. Receive into that record instead." 409로 거절한다.

### 생산 실행 기록 정정 — `POST /production-runs/{runId}/items/{itemId}/cancel`

요청: `{ "reason": "..." }` (필수)

- **진행 중인 실행의 수동 기록만** 취소할 수 있다. BOM 계획 행은 계획이라 취소 대상이 아니다(400). 끝난 실행은 거절한다(400). 이미 취소된 항목은 409.
- 한 트랜잭션에서 차례로 처리한다. 어느 단계에서 실패해도 세 가지 모두 원래대로 남는다(`RunItemCancelRollbackIntegrationTest`).
  1. 그 항목이 만든 재고 거래를 역분개한다(`reversal`, 사유 포함). **현재 재고 정책을 다시 검사**하므로 산출물이 이미 쓰였으면 409로 거절되고 아무것도 바뀌지 않는다.
  2. 항목에 취소 표시(`cancelled_yn`, 누가·언제·왜, V19). 행은 감사 기록으로 남고, 실행 화면의 취소 행에 누가·언제·왜가 보인다.
  3. 실행의 LOT 계보를 지우고 남은(취소되지 않은) 투입·산출로 다시 잇는다. 취소된 산출이 그 LOT의 유일한 산출이었으면 LOT의 "생산 실행" 표시도 지운다.
- 취소된 항목은 실행 합계와 계획 대비 기록량에서 빠진다.
- **끝난 실행에서는 이 취소를 쓸 수 없다.** 재고 조정 거래로 대신 고치면 재고만 바뀌고 생산 실적과 LOT 계보는 그대로라 다시 어긋난다. 끝난 실행은 **보정 전표**로 고친다(`/production-runs/{runId}/corrections`, [보정 절차](production-run-correction.md)): 쓰기 권한자가 사유와 함께 요청하고, 프로젝트 owner가 승인하면 기록 무효화·추가, 산출 수량 변경, 재고, LOT 계보가 한 트랜잭션에서 반영된다. 원래 행은 남는다(V22).

## 4. 재고 행 직접 수정 (`PUT /inventories/{id}`)과 삭제

- 수정은 지금처럼 절대값 조정이다. §1 불변식을 검사하고 `adjustment`로 기록한다. `expectedVersion` 충돌은 409(기존과 같음).
- **[구현 결정 — 승인 2026-09-24]** 삭제는 수량과 예약량이 모두 0일 때만 허용합니다. 남은 재고를 조용히 없애는 경로를 막기 위해서입니다.

### 품목 삭제

- **[구현 결정 — 세션 1, 2026-09-24, 확정·유지 2026-10-19]** 품목(`DELETE /items/{id}`, 소유자)은 아래가 남아 있으면 **409**로 거절합니다. 둘 다 해당하면 이유를 함께 보여 줍니다.
  - **재고 행**(빈 행 포함): 그 행과 이력이 보이지 않는 품목을 가리키게 되므로. 먼저 출고하고 빈 행을 삭제합니다.
  - **폐기되지 않은 BOM**(초안, 승인 대기, 승인)이 그 품목을 만들거나 재료로 씀: 소요량 계산과 실행 시작이 깨지므로. 먼저 BOM을 폐기(retire)하거나 초안을 삭제합니다.
- 권한 검사가 먼저다(외부인은 여전히 403).
- 다른 도메인이 `ItemUsageCheck`를 구현하면 검사가 늘어난다. 품목 도메인은 그 도메인을 알 필요가 없다.
- 품목 화면은 거절 이유를 그대로 보여 준다.
- 검증: `ItemDeleteGuardIntegrationTest` 2건(재고+승인 BOM 두 이유 → 재고 정리 뒤 BOM 이유만 → 폐기 뒤 삭제, 초안 BOM의 대상 품목 → 초안 삭제 뒤 삭제), `ProjectAccessIntegrationTest` 73건 그대로 통과.

---

## 5. BOM

### 상태 전이

```
draft ──submit──▶ pending_approval ──approve──▶ approved ──retire──▶ retired
  ▲                     │
  └──────reject─────────┘
```

| 동작 | 권한 | 조건 |
|---|---|---|
| 생성 / 라인 추가·삭제 | 쓰기 | `draft`일 때만 |
| submit | 쓰기 | `draft`, 라인 1개 이상 |
| approve / reject | 프로젝트 owner | `pending_approval` |
| retire | 프로젝트 owner | `approved` |
| 새 revision | 쓰기 | 원본이 `approved` 또는 `retired`. 라인을 복사한 새 `draft` 생성 |

- **[구현 결정 — 승인 2026-09-24]** revision은 새 `bom_header` 행입니다(새 `bom_id`). 같은 `(project_id, target_item_id)` 안에서 `bom_version`이 1씩 증가하며 UNIQUE입니다.
- 승인된 revision의 header·line은 어떤 API로도 바뀌지 않는다.

### 승인 검증 (approve 시점)

1. 대상 품목이 존재하고 같은 프로젝트
2. 기준 수량 > 0
3. 모든 라인 수량 > 0
4. 자재 ≠ 대상 품목
5. 자재 단위가 자재 품목의 기준 단위로 환산 가능(`UnitConverter`)
6. **[구현 결정 — 승인 2026-09-24]** 같은 자재 품목 중복 금지. `substitute_group`, `optional_yn = Y`, `scrap_rate ≠ 0`은 1차에서 거절
7. ~~다단계 금지: 어떤 자재도 현재 `approved` BOM의 대상 품목이면 안 됨~~ → **2026-09-27 [다단계 BOM](multi-level-bom.md)으로 대체**: 자재가 자기 승인 BOM을 가져도 되지만, 승인 BOM을 따라 내려가 이 BOM의 대상 품목에 닿으면(순환) 거절, 10단계 초과 거절. 생산 실행은 여전히 바로 아래 자재만 투입
8. **2026-09-27 [부산물·폐기물 줄](bom-by-products.md)**: 줄 종류 `material`·`by_product`·`waste`(V34 CHECK). 1–7의 검사는 모든 줄에, 순환·깊이 검사와 소요량·만들 수 있는 양은 자재 줄에만

### 소요량

```
필요량(라인 단위) = 생산 수량 / 기준 수량 × 라인 수량
필요량(품목 단위) = 위 값을 UnitConverter로 자재 품목의 단위로 환산
```

예: 기준 100 ea, 라인 20 kg, 생산 250 ea → 50 kg.

`GET /boms/{id}/requirements?quantity=250`로 미리 계산해 볼 수 있다.

### 생산 시작 snapshot

`POST /production-runs/start`에 `bomId`를 주면(또는 작업지시에 `bomId`가 있으면):

- BOM은 `approved`여야 하고 대상 품목이 실행 대상 품목과 같아야 한다.
- `production_run.bom_id`, `bom_version`, `bom_base_quantity`(V17 신규)를 저장한다.
- 라인마다 `production_run_item`(direction=input, `quantity_source = bom`)을 만들고 `planned_qty`(품목 단위), `unit`, `conversion_rate`(V17 신규, 라인 단위 → 품목 단위)를 고정한다.
- 그 뒤 BOM이 retire되거나 새 revision이 생겨도 이 값은 바뀌지 않는다.
- **[구현 결정 — 승인 2026-09-24]** snapshot으로 만든 계획 행(`quantity_source = bom`)은 계획값입니다. 실제 투입은 지금처럼 `POST /production-runs/{id}/items`로 따로 기록합니다(`quantity_source = manual`). 계획 대비 실적 비교는 두 종류의 행을 품목별로 합쳐서 봅니다.

### 작업지시와 BOM

- 작업지시는 `bomId`를 가질 수 있다(생성·수정 모두, draft일 때만). BOM은 같은 프로젝트여야 하고, 대상 품목이 있으면 그 품목의 BOM이어야 한다. 대상 품목이 비어 있으면 BOM의 품목으로 채운다. `retired` revision은 고를 수 없다.
- **작업지시를 승인할 때 BOM도 `approved`여야 한다.** 승인된 작업지시는 곧바로 실행을 시작할 수 있어야 하기 때문이다.
- 작업지시로 실행을 시작하면 요청에 `bomId`가 없어도 작업지시의 BOM으로 계획을 고정한다(§5 생산 시작 snapshot).

### 기타 구현 결정

- **[구현 결정 — 정책 대체 승인 2026-10-05, 구현 2026-10-10(로컬 검증)]** 한 품목에 유효기간이 겹치지 않는 여러 approved revision을 허용합니다. 기간 overlap이면 승인을 거절(409)하고 새 승인으로 이전 revision을 자동 retired시키지 않습니다. 날짜 없는 전개·원가·역전개·MRP는 프로젝트 오늘의 revision, 순환·깊이 검사는 모든 approved revision을 씁니다([다단계 BOM](multi-level-bom.md) M1–M3).
- **[구현 결정 — 정책 대체 승인 2026-10-05, 구현 완료 2026-10-06]** 여러 draft revision을 허용하고, 같은 품목의 pending approval 하나만 허용합니다. 이미 pending인 다른 revision이 있으면 제출은 409이며 초안을 유지합니다. 반려하면 대기 슬롯이 풀립니다. 삭제한 revision 번호는 재사용하지 않습니다. 승인 그래프는 프로젝트별로 직렬 검증하여 서로 순환하는 BOM 둘이 동시에 승인될 수 없고, CSV 자재 검증은 해당 초안 잠금 뒤 현재 줄을 읽습니다.
- **[확정 2026-09-24]** BOM 및 생산 소요량의 표준 저장·반영 정밀도는 **소수점 4자리, `RoundingMode.HALF_UP`**입니다. 내부 계산은 충분한 정밀도(비율 12자리, 중간값 8자리)로 하고 저장·재고 반영 시점에만 4자리로 맞춥니다. 올림(CEILING)은 쓰지 않습니다. 자재 여유분은 반올림 규칙이 아니라 향후 스크랩률·손실률·안전재고·발주단위 같은 별도 정책으로 처리합니다.
- 승인 검증은 submit 때 한 번, approve 때 다시 한 번 한다(그 사이 품목·단위가 바뀔 수 있으므로). 문제는 한 메시지에 모두 나열한다.

---

## 6. LOT

### 상태

| 상태 | 투입·출고 | 예약 | 비고 |
|---|---|---|---|
| `available` | ✓ | ✓ | |
| `reserved` | ✓ | ✓ | 예약량 > 0이면 표시용 |
| `quarantined` | ✗ | ✗ | `quarantine` 거래로 진입 |
| `inspection_pending` | ✗ | ✗ | 검사가 필요한 품목의 새 LOT. 재고 행은 격리로 생기고 Release LOT로만 풀림([lot-release](lot-release.md)) |
| `consumed` | ✗ | ✗ | 수량 0이 되면 자동 |
| `closed` | ✗ | ✗ | 수동 종료. Project owner만 재개(`POST /lots/{id}/reopen`) |

### 규칙

- `(project_id, lot_no)` UNIQUE.
- 품목의 `lot_manage_yn = Y`이면 재고 생성·생산 투입/산출에 LOT가 필수다. `N`이면 `lot_id = null`만 된다.
- 재고 행 = item + location + lot. 한 재고 행에는 한 LOT만 있고, LOT를 바꾸는 수정은 막는다(역분개 뒤 새 거래).
- 다른 프로젝트·다른 품목의 LOT는 참조할 수 없다.

### API

| 엔드포인트 | 설명 |
|---|---|
| `POST /lots` | LOT 생성 (`projectId`, `itemId`, `lotNo`, 선택: `receivedAt`, `expiryDate`) |
| `GET /lots?projectId=&itemId=` | 목록 |
| `GET /lots/{id}` | 단건 |
| `POST /lots/{id}/close` | 종료 |
| `POST /lots/{id}/reopen` | 재개(Project owner) |
| `POST /lots/{id}/release` | 검사 대기 해제(Project write, 필수 검사 통과 시) |
| `GET /lots/{id}/trace?direction=backward\|forward` | 역추적(원재료 쪽) / 정추적(완제품 쪽), 전 단계 재귀 |

### 계보

생산 실행에서 LOT 있는 산출을 기록하면, 그 실행에서 이미 기록된 LOT 투입마다 `lot_trace(parent = 투입 LOT, child = 산출 LOT)`를 만든다. 산출 뒤에 투입이 추가되면 그때도 이어 붙인다.

### 구현 결정

- **[구현 결정 — 승인 2026-10-05]** 계보는 runId 필수, stepId/processId 선택입니다. 기존 실행 단위 기록을 유지합니다. 현재 코드는 실행의 모든 투입 LOT를 산출 LOT에 연결하며, 선택 step/process 확장은 아직 구현 전입니다.
- **[구현 결정 — 승인 2026-09-24]** LOT가 있는 재고 행 하나를 격리하면 **그 LOT의 모든 재고 행과 LOT 자체**가 격리됩니다. 해제도 LOT 전체에 적용되고, 해제 후 LOT 상태는 재고량으로 다시 계산합니다(available / reserved / consumed).
- **[구현 결정 — 정책 대체 승인 2026-10-05, 구현 2026-10-09(로컬 검증)]** 검사/Release 정책이 없는 품목은 AVAILABLE, 필요한 품목은 QUARANTINE / INSPECTION_PENDING으로 시작합니다. 호환 기본값은 AVAILABLE입니다. 품목 `lot_release_required_yn`(V60)이 `Y`이면 새 LOT는 `inspection_pending`이고, 해제·격리 전환·재개 규칙은 [lot-release.md](lot-release.md) R1–R6에 있습니다. 원격 CI·실 API 브라우저·보안 검토는 남았습니다.
- **동시 이동의 LOT 상태(2026-10-02):** 거래는 자기 재고 행만 잠근 채 LOT 상태를 계산하므로, 같은 LOT의 다른 행에서 동시에 이동하면 서로의 옛 수량을 보고 잔량 0인데 `available`로 남을 수 있었다. 이제 각 거래가 커밋된 **뒤** 새 트랜잭션에서 LOT 행만 잠그고(`LotStatusResync`, `lot_master … FOR UPDATE`) 한 번 더 계산한다. 마지막 재계산이 커밋된 변경을 모두 본다. 재고 행을 잡지 않으므로 행을 먼저 잠그는 거래(FEFO·실사·이동)와 교착이 없다. 거래 안의 계산도 그대로 두어 응답은 지금처럼 바로 반영된다. 한 거래가 같은 LOT의 행 여럿을 움직여도 재계산은 LOT당 한 번이다. 재계산이 실패하면 거래는 이미 커밋됐으므로 경고 로그만 남긴다. 검증: `InventoryTransferIntegrationTest`에서 5 kg 행 둘을 같은 순간 출고 → `consumed`(4회 반복). 재계산을 끄면 `available`로 실패하는 것을 확인함
- **[구현 결정 — 승인 2026-10-05]** closed LOT는 모든 재고 이동과 직접 수정이 거절됩니다. 종료는 보유량이 0일 때만, 종료/재개 권한은 Project owner만입니다. Organization OWNER/ADMIN이라는 이유만으로 허용하지 않습니다.
- **[구현 결정 — 승인 2026-09-24]** 품목의 `lot_manage_yn`은 그 품목의 재고 행이 하나도 없을 때만 바꿀 수 있습니다.
- LOT 관리 품목을 생산 투입·산출로 기록할 때는 재고 행(= LOT)을 반드시 골라야 한다. 실행 항목에는 그 LOT가 `lot_id`로 남는다.
- 계보 조회는 최대 50단계까지 따라간다(순환 데이터 방어).

---

## 7. 실시간 협업 (점검 기준)

DB가 진실의 원천이다. 저장 흐름은 `행 잠금 → version 비교 → 409 또는 저장 → version 증가 → commit → commit 이후 STOMP 발행`. Yjs는 본 코드에 넣지 않는다. 점검 결과는 §8에 적는다.

## 8. 구현 현황

| 항목 | 상태 | 검증 |
|---|---|---|
| §1 불변식 (조건부 UPDATE + V17 CHECK `NOT VALID`) | 구현 | `InventoryConcurrencyIntegrationTest` (30개 스레드 초과 차감 → 20건만 성공, 음수 없음) |
| §2 명령 API · 유형 enum · 멱등성 · 작성자=인증 사용자 | 구현 | `InventoryCommandIntegrationTest` 10건 |
| §3 역분개 (1회 제한, 현재 정책 재검사) | 구현 | 같은 테스트 |
| §4 PUT 불변식 · 품목 변경 금지 · 잔량 있는 행 삭제 금지 | 구현 | `InventoryServiceImplTest` |
| 생산 투입·산출 → 명령 서비스 경유 | 구현 | `ProductionRunServiceImplTest` |
| §5 BOM 상태 전이 · 승인 검증 · 소요량 · 생산 시작 snapshot | 구현 | `BomIntegrationTest` 7건 (설계 예시 250/100×20=50 kg, g→kg 환산, 승인 전 사용 거부, 승인본 수정 거부, 새 revision 승인 후에도 기존 실행 계획값 유지, 문제 일괄 보고, 다단계 거부, 반려 사유 필수) |
| §6 LOT 생성 · 필수 선택 · 격리 · 종료 · 계보 정/역추적 | 구현 | `LotIntegrationTest` 6건 (LOT 필수/금지, 다른 품목 LOT 거부, 한 행 격리 → LOT 전체 격리 → 생산 투입 거부 → 해제 후 투입, LOT 없이 생산 기록 거부, 2단계 실행 정·역추적, 빈 LOT만 종료 후 이동 거부) |
| 권한 (외부인 403) | 구현 | `ProjectAccessIntegrationTest` 73건 (`/boms`, `/lots` 추가) |
| 작업지시 ↔ BOM (선택, 승인 조건, 실행 시 자동 적용) | 구현 | `BomIntegrationTest.aWorkOrderCarriesItsBomIntoTheRuns` |
| 화면: Stock 탭 이동 입력(입고·출고·예약·해제·조정)과 역분개(사유 필수, 1회) | 구현 | `stockModel.test.ts` 3건, E2E에서 출고 30 → 가용 70, 초과 출고 거부, 역분개 후 100 복귀 |
| 화면: 실행 상세의 BOM 계획 행 "Record"(남은 양으로 폼 채움)와 계획 대비 기록량 | 구현 | `runPlan.test.ts` 2건, E2E에서 계획 50 kg → Record → LOT 지정 기록 → 계획 행 실적 50 |
| 생산 실행 기록 정정(취소: 재고 역분개 + 취소 표시 + 계보 재구성) | 구현 | `LotIntegrationTest` 4건(투입 취소 → 재고 복귀·계보 제거·재취소 409, 투입 2·산출 2 중 하나씩 취소해도 남은 계보 유지, 쓰인 산출 취소 409, 끝난 실행 400), `RunItemCancelRollbackIntegrationTest`(계보 단계 실패 시 재고·취소 표시·계보 모두 원상태), `runPlan.test.ts`(취소 행 누가·언제·왜 문구), E2E에서 투입 취소 → 취소 행 툴팁·원재료 LOT 40 복귀·반죽 LOT "Made from" 비움 |
| 끝난 실행 보정 전표(요청 → owner 승인 = 반영, V22) | 구현 | `RunCorrectionIntegrationTest` 4건(투입 LOT 교체 → 재고·계보·기록 함께 이동·재승인 409, 산출 수량 보정, 하류 실행이 쓴 산출 무효화 409·실행 번호 안내·변화 없음, 진행 중 실행 400·대기 전표 1개·editor 승인 403·거절 사유 필수), `correctionModel.test.ts` 5건, 실 화면에서 요청·승인·취소 행·산출 2→5 확인. 설계: [production-run-correction.md](production-run-correction.md) |
| 실 API E2E `e2e/lot-genealogy.spec.ts` | 구현 | 원재료 LOT 투입 10 kg + 반죽 LOT 산출 12 kg → 반죽 LOT "Made from"에 원재료 LOT(used 10), 원재료 LOT "Used in"에 반죽 LOT, 원재료 재고 40 → 30 |
| 실 API E2E `e2e/bom-lot-flow.spec.ts` (`REAL_API_E2E=1`) | 구현 | 품목·LOT·격리·이동·역분개·BOM 승인·소요량·BOM 실행·작업지시 BOM 전 과정, 로컬 반복 통과 |
| 화면: 품목 LOT 추적 설정, BOMs 탭(작성·승인·폐기·새 revision·소요량), LOTs 탭(등록·종료·정/역추적), Stock 탭(LOT 선택·격리/해제), 생산 시작 시 BOM 선택, 실행 상세의 BOM 계획 행·LOT 선택 | 구현 | 브라우저 확인(2026-09-24): 품목 → LOT 등록 → LOT 재고 100 kg → 격리·해제 → BOM 20,000 g/100 ea 승인 → 250 ea 소요량 50 kg → BOM으로 실행 시작(계획 행 50 kg) → LOT 지정 투입. 콘솔 오류 0. `bomModel.test.ts` 4건 |
| §6 격리의 다른 입구: 불합격 검사가 LOT 격리(같은 재고 명령 경로, 참조 `quality_inspection`) | 구현 | `QualityIntegrationTest` 4건, [품질 검사](quality-inspection.md) |
| 재고 경보: 모든 이동(`record`)과 같은 트랜잭션에서 최소·최대 벗어남을 경보로 열고 닫음 | 구현 | `StockAlertIntegrationTest` 2건, [재고 경보](stock-alert.md) |
| §6 LOT 유효기한: 만료일 다음 날부터 `production_input`·`reserve` 409, 출고·조정은 허용 | 구현 | `LotExpiryIntegrationTest` 2건, [LOT 유효기한](lot-expiry.md) |
| §2 위치 간 이동(`POST /inventory-transfers`, `transfer_out`/`transfer_in` 한 트랜잭션, 도착 행 재사용·생성, 멱등) | 구현 | `InventoryTransferIntegrationTest` 2건, [재고 이동](stock-transfer.md) |
| 재고 실사(`POST /inventory-counts`, 센 수량으로 여러 행을 한 트랜잭션에서 `adjustment`, 실사 중 변동 409) | 구현 | `InventoryCountIntegrationTest` 2건, [재고 실사](stock-count.md) |
| §5 BOM 역전개(where-used): `GET /boms/where-used?projectId=&itemId=`, 승인 → 승인 대기 → 초안 → 폐기 순, BOMs 탭 "Where is a material used?" | 구현 | `BomWhereUsedIntegrationTest` 1건, 실 화면 확인 |
| §5 BOM revision 비교: BOM 상세의 "Compare with"로 같은 제품의 다른 revision과 자재별 추가·삭제·변경을 봄(옛 revision이 왼쪽). 수량은 **제품 1단위당**으로 비교하므로 기준 수량과 자재를 같은 비율로 바꾸면 변경 아님. 기준 단위가 바뀌면 적힌 수량 그대로 비교. 같은 자재의 같은 단위 라인은 합침. 아래에 두 revision의 **재료비**를 같은 제품 수량(새 revision 기준 수량이 제품 단위면 그 수량, 아니면 1)으로 소요량 API로 계산해 차이와 비율을 보여 줌(단가 없는 자재가 있으면 표시) | 구현 | `bomModel.test.ts`의 `compareBoms` 3건·`costChange` 1건, 실 화면: `Material cost for 10 ea: v1 10.2 → v2 14.2 (+4, +39.2%)` |
| §2 재고 흐름 분석: 기간 소비(출고·생산 투입, 역분개 제외), 소진 일수, 정체 일수, ABC 등급을 DB 집계로 계산(`GET /stock-analysis`) | 구현 | `StockAnalysisIntegrationTest`, `StockMovementAnalysisServiceTest`, [재고 흐름 분석](stock-analysis.md) |
| §3 과거 시점 재고(`GET /inventory-snapshots`, 행마다 마지막 이동의 `quantity_after`, `DISTINCT ON`)와 기간 수불(`GET /stock-movement-summary`, 기초 + 입고 + 산출 − 출고 − 투입 ± 이동·보정 = 기말) | 구현 | `StockSnapshotIntegrationTest`, `StockMovementSummaryIntegrationTest`, [재고 원장](stock-ledger.md) |
| §2 재고 일괄 입고(`POST /inventories/import`): 행마다 같은 품목·위치·LOT 행에 `receipt` 또는 새 행, 모르는 LOT는 등록, 전부 아니면 없음 | 구현 | `StockImportIntegrationTest`, [재고 일괄 입고](stock-import.md) |
| §4 품목 일괄 등록·수정(`POST /items/import`, 코드 기준, 재고 있는 품목의 LOT 관리 변경 금지 유지)과 품목 코드 중복 등록 409 | 구현 | `ItemImportIntegrationTest`, `ItemCodeIntegrationTest`, [품목 가져오기](item-import.md) |
| §5 초안 BOM 자재 CSV(`POST /boms/{id}/lines/import`, 승인 검증 중 한 줄 규칙을 미리 적용) | 구현 | `BomLineImportIntegrationTest`, [품목 가져오기](item-import.md) "BOM 자재" |
| §2 실사 이력(`GET /inventory-counts`, 실사별 조정 묶음과 금액 변화) | 구현 | `InventoryCountHistoryIntegrationTest`, [재고 실사](stock-count.md) |
| §6 LOT 리콜: 정방향 계보의 LOT별 보유·출고(`GET /lots/{id}/recall`)와 일괄 격리(`POST …/recall/quarantine`, LOT마다 평소 격리 이동, 참조 `lot_recall`) | 구현 | `LotRecallIntegrationTest`, [LOT 리콜](lot-recall.md) |
| §5 BOM 복사(`POST /boms/{id}/copy`, 다른 제품의 첫 초안으로 기준 수량·자재·순서를 그대로, 메모 `Copied from …`). 같은 제품(→ 새 revision), 자기 자재, 이미 BOM이 있는 제품은 거절. BOM 상세의 "Copy to another product"는 BOM 없는 제품만 보여 줌 | 구현 | `BomCopyIntegrationTest`, 실 화면: 복사 후 새 초안 v1이 열림 |
| §7 협업 점검 | 미착수 | |
