# 설비 정보

상태: **구현.** 2026-09-26에 V1 `equipment` 테이블에 있던 열을 엔티티, API, 화면에 연결했다. 새 테이블은 만들지 않았다. 상태 이력은 2026-10-03에 V51 `equipment_status_history`로 더했다.

## 목적

설비 목록에는 코드, 이름, 종류, 상태만 있었다. 어느 회사의 어떤 모델인지, 어디에 있는지, 시간당 얼마를 만들고 얼마를 쓰는지 적을 곳이 없었다. 테이블에는 처음부터 그 열이 있었다.

## 필드

| 필드 | 열 | 뜻 |
|---|---|---|
| `manufacturer` | `manufacturer` varchar(100) | 제조사 |
| `modelName` | `model_name` varchar(100) | 모델 |
| `serialNo` | `serial_no` varchar(100) | 일련번호 |
| `capacityPerHour` | `capacity_per_hour` numeric(14,4) | 시간당 산출량. 단위는 프로젝트가 그 설비의 일을 세는 단위 |
| `powerKwh` | `power_kwh` numeric(10,4) | 가동 1시간당 전력(kWh) |
| `waterLiter` | `water_liter` numeric(14,4) | 가동 1시간당 물(L) |
| `location` | `location` varchar(100) | 놓인 곳. 자유 입력 |
| `efficiencyPercent` | `efficiency_percent` numeric(5,2), V84 | 시간당 산출 가운데 실제로 내는 비율. 0 초과 100 이하, 비우면 100. 아래 "효율" |
| `concurrentWorkOrders` | `concurrent_work_orders_yn` char(1), V86 | 작업지시 여럿을 함께 하는 설비인지. 기본은 아니오. 아래 "동시 작업" |
| `concurrentLimit` | `concurrent_work_orders_limit` integer, V90 | 동시 작업 설비가 한 번에 받는 작업지시 수. 2–99, 비우면 제한 없음. 아래 "동시 작업 수" |

## API

기존 `/equipments` 요청에 `details` 객체가 붙는다.

| 요청 | `details` |
|---|---|
| `POST /equipments` | 선택. 없으면 모두 비어 있다 |
| `PUT /equipments/{id}` | 없으면 그대로 둔다. **있으면 모든 필드를 바꾼다.** 빠진 필드, `null`, 빈 문자열은 지워진다 |
| `GET /equipments`, `GET /equipments/{id}` | 항상 있다. 기록되지 않은 필드는 `null` |

## 규칙

| # | 규칙 | 이유 |
|---|---|---|
| E1 | `details`는 통째로 보낸다. 부분 수정은 없다 | 숫자를 지울 방법이 필요하다. 품목 단가처럼 "빠지면 그대로"로 하면 한 번 넣은 숫자를 지울 수 없다 |
| E2 | 글자는 앞뒤 공백을 떼고, 비면 `null` | 목록에 빈 칸과 "—"가 섞이지 않게 |
| E3 | 숫자는 0 이상, 소수 4자리까지, 정수부는 열 크기까지(전력 6자리, 나머지 10자리). 어기면 400과 필드 이름이 든 메시지 | 열에 담기지 않는 값이 500으로 끝나지 않게 |
| E4 | 글자는 100자까지 | 열 크기와 같다 |

## 화면

재고 → Equipment 탭.

- 표 열: Code, Name, Type, Status, **Maker / model**(아래에 `S/N …`), **Location**, **Per hour**, Actions. Per hour에는 기록된 것만 `120.5 out · 4 kWh · 30 L · 85% efficient`처럼 보인다.
- 표 위에서 거를 수 있다. 코드, 이름, 종류, 제조사, 모델, 일련번호, 위치로 찾고 상태(any, active, inactive, maintenance)로 거르며, "N of M"을 보인다.
- **Download CSV**는 거른 목록을 그대로 `equipment-YYYY-MM-DD.csv`로 받는다. 열은 `code,name,type,status,manufacturer,model,serial_no,location,capacity_per_hour,power_kwh,water_liter`이다(`filterEquipment`, `equipmentCsv`).
- 추가·수정 폼의 **Details (optional)**: Manufacturer, Model, Serial no., Location, Capacity / hour, Efficiency %, Power kWh, Water L. 수정할 때는 저장된 값이 채워지고, 칸을 비우고 저장하면 지워진다. 숫자가 아니거나 음수면 보내기 전에 필드 이름과 함께 알려 준다.

## 상태 이력

2026-10-03, V51 `equipment_status_history`. 개발 DB에 적용했다.

| 열 | 뜻 |
|---|---|
| `previous_status` | 바뀌기 전 상태. 설비를 추가할 때 남긴 첫 줄은 `null` |
| `equipment_status` | 바뀐 상태(`active`, `inactive`, `maintenance`) |
| `note` | 바꾼 까닭. 200자까지, 앞뒤 공백을 떼고 비면 `null` |
| `changed_by`, `changed_at` | 누가, 언제 |

| # | 규칙 | 이유 |
|---|---|---|
| E5 | 설비를 추가하면 첫 상태를 한 줄 남긴다 | 이력만 보고 처음 상태를 알 수 있게 |
| E6 | `PUT /equipments/{id}`에서 상태가 실제로 바뀔 때만 남긴다. 같은 상태를 다시 보내거나 이름·상세만 바꾸면 남기지 않는다. 요청의 `statusNote`는 상태가 바뀔 때만 쓰인다 | 이력이 상태 변경의 기록이 되게 |
| E7 | 이력은 V51부터다. 그 전에 만든 설비에는 첫 줄이 없다 | 과거 상태는 알 수 없다 |

- API: `PUT /equipments/{id}`의 선택 필드 `statusNote`, `GET /equipments/{id}/status-history`(새것부터, 읽기 권한, 없는 설비는 404).
- 화면: 수정 폼에서 Status를 바꾸면 **Status note** 칸이 나온다. 폼 아래 **Status history**에 최근 8줄이 `active → maintenance · Bearing replaced`처럼 누가, 언제와 함께 보인다. 첫 줄은 `added as active`이다.
- 이것은 가동 기록이 아니다. 어느 실행이 언제 설비를 썼는지는 아래 "남은 범위"의 설비·실행 연결이 생겨야 한다.

## 검증

- `EquipmentServiceImplTest`: `details` 없이 수정하면 제조사가 남고, 있으면 빈 값은 지우고 공백은 뗀다. 음수 전력은 400이고 저장하지 않는다.
- `EquipmentDetailsIntegrationTest`(실제 Postgres): 생성과 다시 읽기, 이름만 바꿀 때 상세 유지, `details` 일부만 보내면 나머지가 지워지는 것, 음수·자릿수 초과 400.
- `EquipmentStatusHistoryIntegrationTest`: 추가 → maintenance(메모) → 같은 상태 → 이름만 → active로 이력 3줄. 구성원이 아니면 403, 없는 설비는 404.
- 프런트 `equipmentModel.test.ts`(폼 변환, 오류 메시지, 표시, `statusChangeText`)와 실제 API E2E `e2e/equipment-schedule.spec.ts`(상태 메모 저장 뒤 이력 두 줄).
- 브라우저(개발 DB)에서 상세와 함께 추가, 수정, 음수 전력 거절, 삭제까지 확인했다. 콘솔 오류는 없었다.

## 효율

2026-10-14에 사용자가 정했다("Equipment efficiency"). 기본 100%로 기존 계산을 지키고, 계획은 시간당 산출 × 효율로 센다. 출처는 벤치마크 FM-PLAN-002 Capacity Resource(`efficiency`)다. 한 번에 작업지시 하나만 하는지는 효율과 따로 다룬다(아래 "동시 작업"). 마이그레이션 **V84**, 개발 DB 적용.

| # | 규칙 | 오류 |
|---|---|---|
| E8 | `details.efficiencyPercent`(V84 `equipment.efficiency_percent` numeric(5,2)): 시간당 산출 가운데 실제로 내는 비율이다. 0 초과 100 이하, 소수 2자리까지. 비우면 100이라 이전 계획이 그대로다. `details`를 통째로 보내므로(E1) 빠지면 지워진다 | 400 `efficiencyPercent must be above 0 and at most 100.`, `efficiencyPercent takes at most 2 decimals.` |
| E9 | **실제 산출 = 시간당 산출 × 효율.** 계획 기간 제안([P1](equipment-schedule.md)), 부하표의 필요 시간, 준비 점검의 `equipment` 필요 시간, 작업지시 가동 계획이 모두 이 값을 쓴다. **전환 시간은 효율로 늘리거나 줄이지 않는다.** 응답에는 `efficiencyPercent`(100이면 null)를 함께 주고, `capacityPerHour`는 정격 그대로 준다 | |
| E10 | 지난 실적과 확정 원가는 다시 계산하지 않는다. 실행의 가동 시간, setup, 원가 기록과 완료된 실행의 실제 분·원가는 그대로다. 효율을 바꾸면 그 뒤에 읽는 계획 값만 달라진다 | |
| E11 | 화면: 추가·수정 폼 **Efficiency %**(범위를 벗어나면 보내기 전에 `Efficiency % must be above 0 and at most 100.`), 목록 **Per hour**의 `85% efficient`(100이면 없음), 부하표 설비 칸 `80% efficient`, 준비 점검 `… needs 2.5 h at 80% efficiency …`, 가동 비교 계획 `120 min at 10/h × 80%`. 효율의 기간별 이력과 품목마다 다른 효율은 하지 않는다 | |

검증(로컬): `EquipmentEfficiencyIntegrationTest`. 시간당 10, 효율 80%인 설비에서 16개는 생산 2시간에 전환 30분을 더해 2.5시간이 필요하다. 계획 기간 제안, 준비 점검 경고, 부하표, 가동 비교가 모두 그 값을 쓰고, 효율을 비우면 생산 1.6시간으로 돌아간다. 0, 100.5, 소수 3자리는 400이다. 관련 백엔드 50건이 통과했다.

## 동시 작업

2026-10-17에 사용자가 정했다("Concurrent equipment flag"). 설비마다 켜고 끄는 스위치 하나다. 끄면(기본) 지금처럼 한 번에 작업지시 하나다. 출처는 벤치마크 FM-PLAN-002 Capacity Resource(`constrained`)다. 마이그레이션 **V86**, 개발 DB 적용.

| # | 규칙 | 오류 |
|---|---|---|
| E12 | `details.concurrentWorkOrders`(V86 `equipment.concurrent_work_orders_yn`): 켜면 이 설비에서 작업지시 여럿이 함께 돈다(오븐, 탱크 등). JSON `true`와 `false`만 받는다. `details`를 통째로 보내므로(E1) 빠지면 꺼진다. 시간당 산출과 효율은 작업지시 하나의 속도다 | 400 `Send the equipment as JSON, with numbers as numbers and concurrentWorkOrders as true or false.` |
| E13 | 켠 설비에서는 계획 기간 제안([설비 달력](equipment-schedule.md) P4, P9)이 승인·진행 중이거나 앞에서 놓인 작업지시를 비켜 가지 않는다(`movedPast` 없음). 인력이 겹치는 것은 그대로 비켜 간다(WC25). [준비 점검](work-order-readiness.md)의 `schedule` 경고도 없다 | |
| E14 | 켠 설비의 [부하표](equipment-load.md): 계획이 가용을 넘어도 **과부하가 아니다**(`overloaded` false). 부하율은 그대로 세므로 100%를 넘을 수 있다. 타임라인은 겹치는 막대를 충돌로 표시하지 않는다. 응답에 `concurrentWorkOrders` | |
| E15 | 전환 시간은 그대로 계획 시작 순서의 앞 작업지시로 센다([전환 시간](equipment-changeover.md)). 화면: 추가·수정 폼 **Runs several work orders at once**(설명 포함), 목록 상태 칸 `side by side`, 부하표 설비 칸 `runs orders side by side`. 동시에 하는 수는 E16–E19에서 정했다. 함께 돌 때의 용량 나눔은 하지 않는다 | |

검증(로컬): `ConcurrentEquipmentIntegrationTest`. 동시 작업을 켠 설비에 같은 시간의 승인 작업지시 둘을 두면 계획 제안이 비켜 가지 않고, 준비 점검 경고가 없고, 부하율 200%여도 과부하가 아니다. 스위치를 끄면 셋 다 원래대로 돌아간다. `"yes"`는 400이다. 관련 백엔드 153건, 프런트 테스트 2건, 모의 E2E `concurrent-equipment.spec.ts`가 통과했다.

## 동시 작업 수

2026-10-17에 사용자가 정했다("Equipment concurrency N"). 동시 작업 설비(E12)에 "한 번에 최대 N개"를 둘 수 있다. 비우면 지금처럼 제한이 없고, 스위치를 끄면 지금처럼 한 번에 하나다. 시간당 산출과 효율은 그대로 작업지시 하나의 속도다. 마이그레이션 **V90**, 개발 DB 미적용.

| # | 규칙 | 오류 |
|---|---|---|
| E16 | `details.concurrentLimit`(V90 `equipment.concurrent_work_orders_limit`, DB 제약 `ck_equipment_concurrent_work_orders_limit`): `concurrentWorkOrders`가 true일 때만 둔다. 2–99의 정수이고, 빠지거나 null이면 제한 없음이다. `details`를 통째로 보내므로(E1) 빠지면 지워진다. 응답과 공개 API(`CatalogEquipmentView.concurrentLimit`)에 있고, `maxConcurrent()`는 1, N, 또는 제한 없음(null)을 준다 | 400 `concurrentLimit is a whole number from 2 to 99.`(1, 100, 2.5, `"3"`), `concurrentLimit needs concurrentWorkOrders.` |
| E17 | 계획 제안(P4, P9)과 준비 점검: 같은 설비의 승인·진행 중 작업지시가 어느 순간 N개 함께 돌면 그때 설비가 꽉 찬 것이다. 계획 제안은 꽉 찬 첫 순간에 돌던 작업지시 가운데 가장 먼저 끝나는 것의 끝부터 다시 찾고, 그 작업지시를 `movedPast`에 넣는다. 꽉 차지 않으면 겹쳐도 그대로 둔다. 준비 점검 `schedule` 경고는 꽉 찬 순간에 돌던 작업지시만 든다: `Equipment X runs at most N work orders at once and is also planned for A, B in this window.` 인력은 그대로 비켜 간다(WC25) | |
| E18 | [부하표](equipment-load.md): 설비가 받을 수 있는 시간은 가용 시간 × N이다. 부하율 = 계획 ÷ (가용 × N) × 100이고, 계획이 가용 × N을 넘으면 과부하다. 응답에 `concurrentLimit`. 타임라인은 어느 순간 N개를 넘게 겹치는 막대만 충돌로 표시한다. 제한 없는 동시 작업 설비는 E14 그대로다 | |
| E19 | 화면: 추가·수정 폼에서 **Runs several work orders at once**를 켜면 **At most at once** 칸이 나온다. 비우면 `no limit`이고, 2–99 정수가 아니면 보내기 전에 `At most at once is a whole number from 2 to 99.`를 보인다. 목록 상태 칸은 `side by side · up to 2`, 부하표 설비 칸은 `runs up to 2 orders side by side`. 함께 돌 때 각자 느려지는 용량 나눔은 하지 않는다 | |

검증(로컬): `ConcurrentEquipmentLimitIntegrationTest`. 최대 2인 설비에 08–10, 08–11 승인 작업지시 둘과 08–10 초안 하나를 두었다.

- 둘째는 08:00 그대로 들어가고, 셋째는 첫째가 끝나는 10시부터 제안된다(`movedPast`에 첫째만).
- 준비 점검 경고는 셋째에만 나온다.
- 08–10 부하율은 100%이고 과부하가 아니다. 셋째를 승인하면 150%로 과부하가 된다. 최대를 비우면 300%, 과부하 아님으로 돌아간다.
- 1, 100, 2.5, `"3"`과 스위치 없는 최대는 400이다.

전체 백엔드 1,443건이 통과했다. V90 제약은 일회용 Postgres에서 따로 확인했다(개발 DB 5434가 멈춰 있었다). 프런트 테스트 2건과 모의 E2E `concurrent-equipment.spec.ts`의 둘째 테스트도 통과했다.

## 남은 범위

- 설비와 공정·실행의 연결이 없다. 어느 실행이 어느 설비를 얼마나 썼는지 모른다. 시간당 전력과 물은 그 연결이 생기면 에너지 원가 계산에 쓸 수 있다.
- 상태 이력은 V51부터다(위 "상태 이력"). 실행별 설비 사용 시간은 설비·실행 연결이 생겨야 한다.
- 교대 달력, 정비·고장으로 인한 정지 시간, 작업지시 설비 점검은 [설비 달력·정지 시간](equipment-schedule.md)에 있다(2026-09-27, V32).

## 현재 시간당 계획 원가 (2026-10-08)

[시간당 원가](equipment-setup-cost.md)는 Schedule에서 고치고, 부하표에서 작업지시의 전환 추정을 읽는다. 원가는 details와 따로 저장하므로 이름이나 설비 상세를 바꿔도 지워지지 않는다. 모름(null)과 알려진 0을 구분한다. 실제 사용 시간과 실행 원가 기록은 아직 하지 않는다.

- 품목의 색상, 금형 같은 여러 [setup 속성 규칙](setup-attributes.md)은 Schedule에서 우선순위와 전환 분을 고친다. 품목 Details에는 그에 맞는 속성을 저장한다. 기존의 품목 쌍 규칙, 기본 규칙과 함께 적용된다.
