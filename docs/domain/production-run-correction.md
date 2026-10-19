# 종료된 생산 실행의 보정 절차

상태: **1차 설계 확정(2026-09-24), 구현함.** 결정 근거는 §9에 있습니다.
관련: [재고·BOM·LOT 계약서](inventory-bom-lot-contract.md) §3 "생산 실행 기록 정정"

---

## 1. 풀려는 문제

- 진행 중인 실행의 잘못된 기록은 **항목 취소**로 고칩니다. 재고 역분개, 취소 표시, LOT 계보 재구성이 한 트랜잭션에서 처리됩니다.
- **끝난 실행**은 고칠 방법이 없었습니다. 재고 조정 거래로 대신 고치면 재고만 바뀌고 생산 실적과 LOT 계보는 그대로라 서로 어긋납니다.
- 끝난 실행의 오류 예:
  - 투입 LOT를 잘못 골랐음
  - 투입·산출 수량을 잘못 입력했음
  - 기록을 빠뜨렸음
  - 종료 때 입력한 산출 수량(`actualOutputQty`)이 틀렸음

## 2. 보정 전 동작 (2026-09-24 코드 기준)

| 대상 | 종료 시 변화 | 종료 후 영향 |
|---|---|---|
| `production_run` | `run_status = finished`, `actual_output_qty`, `finished_by` | 다시 열 수 없음. 항목 기록·취소 거절(400) |
| `production_run_item` | 없음 | 수정·취소 불가 |
| 재고 거래 | 기록할 때마다 이미 반영됨 | 재고 API로 역분개 불가(생산 거래는 역분개 금지) |
| LOT 계보 | 기록할 때마다 이미 연결됨 | 바꿀 방법 없음 |
| 작업지시 | 생산량 = 끝난 실행들의 `actual_output_qty` 합(조회할 때 계산) | 실행이 모두 끝나야 작업지시 완료 가능 |

`actual_output_qty`(종료 때 사람이 입력)와 산출 항목의 합은 서로 검증되지 않습니다. 이 불일치는 보정으로 둘 다 고칠 수 있게 두고, 자동 검증은 뒤로 미룹니다(§9 Q6).

## 3. 원칙

1. **원래 기록은 바꾸지도 지우지도 않습니다.** 보정은 새 기록(보정 전표)으로 추가합니다.
2. **실적, 재고, 계보는 한 번에 보정합니다.** 한 트랜잭션에서 모두 반영되거나 하나도 반영되지 않습니다.
3. **재고 정책은 보정 때 다시 검사합니다.** 마이너스 재고 금지, 격리·종료 LOT 금지가 그대로 적용됩니다.
4. **누가, 언제, 왜 보정했는지 남기고 승인을 거칩니다.**
5. **보정 거래의 날짜는 반영 시각입니다.** 과거 날짜로 소급하지 않습니다.

## 4. 모델 (V22)

### `production_run_correction` (보정 전표)

| 컬럼 | 설명 |
|---|---|
| `production_run_correction_id` | PK |
| `project_id`, `production_run_id` | 대상 실행(`finished`만) |
| `correction_no` | 실행 안에서 1부터 증가. `(production_run_id, correction_no)` UNIQUE |
| `status` | `pending_approval` → `applied` / `rejected`. 한 실행에 `pending_approval`은 하나만(부분 UNIQUE) |
| `reason` | 필수, 최대 500자 |
| `requested_by`, `requested_at` | 요청자 |
| `decided_by`, `decided_at`, `decision_note` | 승인·거절자. 거절 사유 필수 |
| `applied_at` | 반영 시각(= 보정 재고 거래 시각) |

1차에서는 `draft`를 두지 않습니다. 요청하면 바로 `pending_approval`입니다.

### `production_run_correction_line` (보정 내용)

| `line_kind` | 의미 | 값 |
|---|---|---|
| `void_item` | 기존 기록 무효화 | `target_run_item_id` |
| `add_item` | 빠뜨렸거나 바로잡은 기록 추가 | `direction`, `item_id`, `inventory_id`(LOT 품목은 필수), `qty`, `unit` |
| `set_output_qty` | 실행의 산출 수량 변경 | `before_qty`(요청 시점 값), `after_qty` |
| `cancel_setup`·`add_setup` | 실제 setup 취소·추가 | `target_run_setup_id` / `equipment_id`, `setup_minutes`, 반영 뒤 `created_run_setup_id` — [설비 원가](equipment-setup-cost.md) AS7–AS10(V66) |
| `cancel_running_time`·`add_running_time` | 설비 가동 시간 취소·추가 | `target_run_equipment_time_id` / `equipment_id`, `running_minutes`, 반영 뒤 `created_run_equipment_time_id` — [설비 원가](equipment-setup-cost.md) ER10–ER12(V76) |
| `cancel_labor`·`add_labor` | 작업자 시간 취소·추가 | `target_run_labor_id` / `labor_user_id`, `worked_minutes`, 반영 뒤 `created_run_labor_id` — [작업자 달력](worker-calendar.md) LB9–LB11(V97). 보정 본문이 JSON이 아니면 400(2026-10-19) |

- 수량 변경은 `void_item` + `add_item` 한 쌍으로 표현합니다. 기존 행을 고치지 않으므로 전후 기록이 모두 남습니다.
- 반영되면 `add_item` 줄의 `created_run_item_id`에 새로 만든 실행 항목을 기록합니다.
- `set_output_qty`는 전표당 하나까지이고, 전표에는 줄이 하나 이상 있어야 합니다.

### 기존 테이블 변경

- `production_run_item.production_run_correction_id`: 보정으로 추가된 행의 전표입니다. 이 행의 `quantity_source`는 `correction`입니다.
- `production_run_item.cancelled_by_correction_id`: 보정으로 무효화된 행의 전표입니다. 무효화 자체는 V19의 취소 컬럼(`cancelled_yn`, `cancelled_by`, `cancelled_at`, `cancel_reason`)을 그대로 씁니다.

## 5. API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /production-runs/{runId}/corrections` | 읽기 | 전표와 줄, 최신순 |
| `POST /production-runs/{runId}/corrections` | 쓰기 | `{ reason, lines[] }`. 실행이 `finished`이고 대기 중인 전표가 없어야 함. 줄은 요청 시점에 한 번 검증 |
| `POST /production-runs/{runId}/corrections/{id}/approve` | 프로젝트 owner | 승인과 동시에 반영(§6) |
| `POST /production-runs/{runId}/corrections/{id}/reject` | 프로젝트 owner | `{ note }` 필수 |

## 6. 반영 처리 — 한 트랜잭션

1. **잠금과 확인:** 실행 행을 잠급니다(`SELECT … FOR UPDATE`). 전표가 `pending_approval`인지 다시 확인합니다. `void_item` 대상은 이 실행의 취소되지 않은 수동·보정 기록이어야 합니다. `set_output_qty`의 `before_qty`가 현재 값과 다르면 409입니다.
2. **무효화:** `void_item`마다 이미 쓰인 산출 LOT인지 먼저 확인합니다(§7). 그다음 `reverseMovementsOf("production_run_item", 대상 ID, …)`로 재고를 되돌리고, 취소 표시와 `cancelled_by_correction_id`를 기록합니다.
3. **추가:** `add_item`마다 새 실행 항목(`quantity_source = correction`)과 생산 재고 거래를 만듭니다. 기존 기록 경로와 같은 검증(LOT 필수, 단위 환산, 격리·종료 LOT 거절, 재고 부족 거절)을 거칩니다.
4. **산출 수량:** `actual_output_qty`를 바꿉니다. 작업지시 생산량은 조회할 때 다시 계산됩니다.
5. **계보 재구성:** 실행의 LOT 계보를 남은 기록으로 다시 연결하고, 더 이상 이 실행이 만들지 않은 LOT의 "생산 실행" 표시를 지웁니다.
6. **마무리:** 전표를 `applied`로 바꾸고 승인자와 반영 시각을 기록합니다.

어느 단계에서 실패하든 전체를 되돌리고, 전표는 `pending_approval`로 남습니다.

## 7. 막는 경우

| 경우 | 처리 |
|---|---|
| 무효화할 산출 LOT의 재고가 이미 쓰였음 | 409. 그 LOT를 투입한 뒤 실행이 있으면 실행 번호를 메시지에 넣어 "그 실행을 먼저 보정" 하라고 안내 |
| 종료·격리 LOT에 대한 이동 | 기존 규칙대로 거절 |
| 실행이 `finished`가 아님 | 400. 진행 중이면 항목 취소를 씀 |
| BOM 계획 행이나 이미 무효화된 기록을 무효화 | 400 / 409 |
| 같은 실행에 대기 중인 전표가 이미 있음 | 409 |
| 이미 처리된 전표를 다시 승인·거절 | 409 |

## 8. 화면

- **실행 상세(끝난 실행):** "Corrections" 섹션을 둡니다.
  - 전표 목록: 번호, 상태, 사유, 요청자·승인자, 시각, 줄
  - owner에게는 대기 중인 전표에 Approve / Reject 버튼
  - 새 전표 작성 폼: 무효화할 기록 선택, 추가할 기록, 산출 수량
- 기록 표에서 보정으로 무효화된 행은 기존 취소 행처럼 표시하고, 보정으로 추가된 행에는 `correction` 표시를 붙입니다.

## 9. 결정 (2026-09-24)

| # | 질문 | 결정 | 이유 |
|---|---|---|---|
| Q1 | 요청·승인 권한 | 쓰기 권한자가 요청, **프로젝트 owner가 승인**. 요청자 본인의 승인은 허용하되 기록에 남김 | owner가 한 명뿐인 프로젝트에서 보정이 막히지 않게. 역할 분리 강화는 D3에서 조정 |
| Q2 | 승인과 반영 | 승인 = 즉시 반영 | 승인과 반영 사이에 재고가 바뀌는 틈을 없앰 |
| Q3 | 하류에서 이미 쓴 산출 | 막고 하류 실행부터 보정하게 안내 | 연쇄 보정은 범위가 크고, 마이너스 재고 허용은 계약서와 충돌 |
| Q4 | 보정 거래 날짜 | 반영 시각 | 소급은 이력과 기간 집계를 흔듦 |
| Q5 | 완료된 작업지시의 실행 | 허용 | 작업지시 생산량은 조회 시 다시 계산되고, 보정 이력은 실행에 남음 |
| Q6 | `actual_output_qty`와 산출 기록 | 따로 두고 둘 다 보정 가능 | 자동 계산·불일치 경고는 D7 실행 단계 모델과 함께 검토 |
| Q7 | 규칙 평가 | 1차에서는 보정 때 평가하지 않음 | 규칙이 바뀌어 과거 실행을 못 고치는 일을 막음. 경고 표시는 이후 과제 |
| Q8 | 진행 중 취소와의 관계 | 진행 중 취소는 그대로, 전표는 종료 후 전용 | 진행 중 정정은 가볍게 유지 |
| Q9 | D7과의 관계 | 줄을 항목 단위로 둠 | 단계 모델이 들어오면 줄에 단계 참조를 추가 |

## 10. 범위 밖

- 여러 실행을 한 전표로 연쇄 보정
- 재고 기간 마감과 원가 재계산
- 보정의 보정(필요하면 새 전표로 반대 방향 줄을 넣음)
- 보정 줄의 공정·공정 IO 지정(1차는 품목 단위)
