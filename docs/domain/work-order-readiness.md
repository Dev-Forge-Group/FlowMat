# 작업지시 실행 준비 점검 (Readiness)

상태: **구현(2026-09-24).** 출처는 [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) MES-04, kuaigeyun의 WorkOrder Readiness 개념이다. 코드는 가져오지 않았다. FlowMat 데이터로 따로 구현했다.

## 목적

작업지시로 실행을 시작하기 전에 "지금 돌릴 수 있는가"를 한 번에 보여 준다. **읽기 전용**이고 아무것도 예약하지 않는다. 점검한 뒤 실행을 시작하기 전에 재고가 바뀔 수 있다. 실제 차감과 검증은 지금처럼 실행을 기록할 때 서버가 한다.

## API

`GET /work-orders/{workOrderId}/readiness`. 프로젝트 읽기 권한이 필요하다.

```json
{
  "workOrderId": "...",
  "ready": false,
  "remainingQuantity": 250,
  "checks": [{ "code": "materials", "status": "fail", "message": "Short of FLOUR (need 50 kg, available 30)." }],
  "materials": [{ "itemId": "...", "itemCode": "FLOUR", "requiredQuantity": 50, "unit": "kg",
                  "availableQuantity": 30, "shortageQuantity": 20, "lotTracked": false, "usableLots": 0 }]
}
```

`ready`는 `fail`이 하나도 없을 때 `true`다. `warn`은 막지 않는다.

## 점검 항목

| code | fail | warn | ok |
|---|---|---|---|
| `status` | draft(승인 필요), completed·cancelled(더 이상 실행 없음) | – | approved·in_progress |
| `workflow` | 워크플로 미지정 | 발행된 revision 없음 | 최신 발행 revision 번호 |
| `target` | – | 대상 품목 없음 | (표시 안 함) |
| `quantity` | – | 목표 수량 없음, 이미 다 생산함 | 남은 수량 |
| `bom` | BOM이 없어짐, 승인 안 됨 | BOM 없음 | 승인된 revision |
| `materials` | 부족한 자재(필요·가용 수량 표시), 소요량 계산 실패 | – | 남은 수량만큼 재고 있음 |
| `expiry` | – | 가용량에 넣은 LOT 중 **7일 안에 만료**되는 것. 먼저 쓰라는 안내다. 최대 3개를 보이고, 날짜 수는 `app.stock-alert.expiry-warning-days` | (표시 안 함) |
| `cost` | – | – | 남은 수량의 **예상 재료비**([재료비](material-cost.md)). 단가 없는 재료가 있으면 그렇다고 적음 |
| `equipment` | 붙인 설비가 삭제됨·비활성·정비 중 | 계획 기간 없음, 필요 시간 > 가용 시간 | 필요 시간 ≤ 가용 시간. 필요 시간은 시간당 산출 × 효율로 센다. 효율이 100%가 아니면 문장에 `at 80% efficiency`가 붙는다([설비](equipment.md) E9). **설비를 붙인 작업지시만** 본다([설비 달력](equipment-schedule.md)) |
| `schedule` | – | 같은 설비에서 승인·진행 중인 다른 작업지시와 계획 기간이 겹침. 여러 작업지시를 함께 하는 설비는 보지 않는다([설비](equipment.md) E13). 최대 N개면 N개가 함께 도는 순간만 본다(E17) | (표시 안 함) |
| `changeover` | – | – | 같은 설비의 앞 작업지시에서 넘어오는 전환 시간([설비 전환 시간](equipment-changeover.md)). `equipment`의 필요 시간에 더한다 |
| `crew` | – | 인력을 적었는데 계획 기간 없음, 누군가 교대가 없거나 그 기간에 일하지 않음 | 모두 그 기간에 일함(사람마다 시간). **인력을 적은 작업지시만** 본다([작업자 달력](worker-calendar.md) WC13) |
| `crew_schedule` | – | 인력 중 누군가가 승인·진행 중인 다른 작업지시와 같은 때 잡혀 몫의 합이 100%를 넘음. 교대가 있으면 그 근무 시간만, 없으면 작업지시 기간으로 본다(WC38) | (표시 안 함). [작업자 달력](worker-calendar.md) WC21 |

## 계산 규칙

- **남은 수량** = 목표 수량 − 이 작업지시의 끝난 실행들의 `actual_output_qty` 합. 작업지시 화면의 생산량과 같은 기준이다. 끝난 실행을 보정하면 바로 반영된다.
- **필요량** = 남은 수량으로 계산한 BOM 소요량(`BomService.requirementsForRun`) − 이 작업지시의 **끝나지 않은 실행(pending·running)이 이미 기록한 투입**. 0보다 작아지지 않는다. 투입은 계획 행과 취소를 빼고 자재 단위로 바꿔 센다(`OpenRunInputs`). 그 재고는 이미 줄었으니 두 번 세지 않는다. 반올림은 계약서 §5와 같다(4자리, HALF_UP).
- **가용량** = 품목의 재고 행마다 `quantity − reserved_quantity`를 더한 값. 다음 행은 뺀다.
  - 격리(`quarantined`)된 재고 행
  - 종료(`closed`)된 LOT의 행
  - 유효기한이 지난 LOT의 행([LOT 유효기한](lot-expiry.md))
- **usableLots** = LOT 관리 품목에서 가용량이 0보다 큰 LOT 수.
- 이 작업지시에 [할당](stock-allocation.md)된 재고(열린 할당의 남은 양)는 그 재고 행의 가용량에 더한다(2026-09-27). 할당은 예약이라 재고 행의 가용에서 이미 빠져 있다.
- 서비스에 트랜잭션은 없다. 소요량 계산이 실패해도 롤백하지 않고 `materials` 실패 항목으로 보고한다.

## 화면

실행 화면 → Work Orders 탭 → 작업지시 행의 **Readiness** 버튼. 완료·취소된 작업지시에는 없다.
- 요약 한 줄: "Not ready: 1 problem", "Ready, with 1 warning", "Ready to run".
- 점검 목록은 실패, 경고, 정상 순서다.
- 자재 표에는 필요, 가용, 부족, LOT 수가 나온다.
- 부족한 자재가 반제품(자기 승인 BOM이 있는 품목)이면 **Sub-assembly** 칸에 **Make N**이 나온다. 누르면 새 작업지시 폼을 그 반제품으로 채운다(2026-10-03, [다단계 BOM](multi-level-bom.md) "준비 점검의 반제품 작업지시").
- **Check again**은 다시 조회한다.

실행 화면 → 실행 시작 폼에서 **Work order**를 고르면(2026-10-03) 그 아래에 요약 한 줄과 정상이 아닌 점검이 나온다. 실패는 빨강, 경고는 주황이다(`StartReadiness`, `aria-label="Start readiness"`). 시작을 막지는 않는다. 실 화면 `e2e/bom-lot-flow.spec.ts`가 작업지시를 고른 뒤 요약이 보이는지 확인한다.
- 설비가 붙은 작업지시는 점검 위에 **Plan dates**가 있다. 가장 이른 계획 시작과 끝을 제안하고, 초안이면 그대로 저장한다([설비 달력](equipment-schedule.md) "계획 기간 제안").
- 점검 위 **Crew** 줄은 작업지시에 필요한 사람과 계획 기간 안의 그 사람들 시간이다. **Choose crew**로 바꾼다([작업자 달력](worker-calendar.md) WC15).

## 검증

- `WorkOrderReadinessIntegrationTest` 2건
  - draft 작업지시와 BOM이면 실패
  - 승인 뒤 밀가루 부족을 표시하고, 격리된 LOT는 가용량에서 뺌
  - 입고하면 준비됨
  - 200 생산한 실행이 끝나면 남은 수량 50, 필요량 10으로 바뀜
  - BOM과 수량이 없는 작업지시는 경고만
- `MaterialRequirementIntegrationTest`: 실행이 밀가루 4 kg을 투입한 작업지시의 준비 점검 필요량 10 → 6, 부족 없음
- `LotExpiryIntegrationTest`: 만료 LOT 제외, 2일 뒤 만료 LOT → `expiry` 경고(준비됨은 유지), 단가 없는 재료 → `cost` 메시지에 표시
- `readinessModel.test.ts` 2건
- 실 화면에서 부족 표시 확인

## 이후

- 열린 작업지시 전체의 자재 합계는 [자재 소요](material-requirements.md)에서 본다. 남은 수량과 가용량 규칙은 같다.

- ~~실행 시작 화면(Start)에서 같은 점검을 보여 주기~~ → 위 "화면"의 실행 시작 폼(2026-10-03)
- ~~설비·달력·용량 점검은 벤치마크 P1(capacity/calendar)이 들어오면 항목을 추가합니다.~~ → [설비 달력·정지 시간](equipment-schedule.md)으로 구현(2026-09-27, V32): `equipment`·`schedule` 항목
