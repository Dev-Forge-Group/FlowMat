# 설비 시간당 원가와 전환 비용 추정

상태: **시간당 계획 원가 편집·전환 비용 추정 구현(2026-10-08, §2 2bs). 실행 실제 setup 시간·원가 snapshot 구현(2026-10-10, §2 2cd, 로컬 검증만).** 근거 정책은 [2026-10-05 결정 §4](../status/DECISIONS-2026-10-05.md#4-setup비용부산물). 다차원 속성 규칙은 [setup 속성 계약](setup-attributes.md)에 구현했다. 실제 설비 사용 시간과 실행 비용 snapshot은 후속 구현이다.

## 계약

| 규칙 | 동작 |
|---|---|
| SC1 | 설비의 현재 시간당 계획 원가는 별도 `equipment_hourly_cost`에 저장한다. 기존 설비 상세 편집·CSV가 원가를 지우지 않는다. 기존 설비는 원가 미상이며 0을 임의로 채우지 않는다 |
| SC2 | `GET /equipments/{id}/hourly-cost`: Project read. `{equipmentId, hourlyCost, version, updatedBy, updatedAt}`. 저장 기록이 없으면 null·version 0. 삭제된/없는 설비 404, 외부인 403 |
| SC3 | `PUT`은 Project write. `{hourlyCost: number|null, expectedVersion: integer}` 두 필드 필수. null은 명시적 해제, 0은 알려진 원가. 음수·10자리 정수 초과·소수 4자리 초과·잘못된 숫자/JSON 400, 필드 이름을 메시지에 표시. 작성자와 UTC 시각은 서버에서 정한다 |
| SC4 | 설비 행 잠금 뒤 삭제 여부와 저장 버전을 확인한다. 같은 버전의 서로 다른 동시 변경은 하나만 저장, 다른 요청은 409 `expectedVersion changed`. 버전 최대값은 409. 이전 버전 + 같은 작성자 + 같은 값의 바로 직전 저장 재시도는 현재 결과를 반환하고 버전을 늘리지 않는다 |
| SC5 | `EquipmentCostQuery`는 활성 설비와 프로젝트를 대조하고 알려진 원가만 반환한다. 빈 입력은 null 키 조회 가능한 빈 Map. production은 공개 Query를 통해 일괄 조회하여 다른 도메인 Repository를 참조하지 않는다 |
| SC6 | 부하표 각 작업지시의 `setupCostEstimate = changeoverMinutes × 현재 hourlyCost / 60`, 최종 소수 4자리 HALF_UP. 시간 표시의 반올림된 시간(예: 1분→0.02h)을 금액에 쓰지 않는다. 전환 0분은 금액 0, 전환 시간/원가 미상은 null |
| SC7 | 표시 금액은 **그 줄 작업지시의 전체 전환 추정**이다. 주간 가용 시간 비율로 나누지 않는다. 재료비·실제 실행 원가·날짜 변경·자동 실행에는 연결하지 않는다 |
| SC8 | Schedule의 **Equipment hourly cost**에서 현재 원가를 편집한다. 입력 값과 로드한 버전은 조회 갱신으로 덮어쓰지 않는다. 409 후 명시적 **Reload current rate**로 편집을 버리고 새 버전을 읽는다. 미확인 저장은 입력 잠금·원래 값/버전 재시도. 편집 없이 저장한 경우에도 요청 snapshot을 만든다 |
| SC9 | 저장 응답의 설비 ID·버전·금액을 검증한 뒤 조회 캐시를 갱신한다. 저장 전 진행 중인 옛 조회를 취소한다. 불완전 성공 응답은 미확인으로 표시하며 자동 PUT 재송신은 하지 않는다 |

## 데이터와 적용

- **V56__equipment_hourly_cost.sql**: 설비 FK·numeric(14,4) 비음수 CHECK·단조 버전·작성자/UTC 시각. 포트 없는 전용 컨테이너에서 V1 기준 스키마에 `BEGIN → CREATE → 존재 1 → ROLLBACK → 존재 0` 리허설 후 파일을 추가했다. 기존 V1–V55는 수정하지 않았다.
- 개발·세션 DB에는 적용하지 않았다. 새 스키마는 Testcontainers에서만 적용·검증했다.
- 소프트 삭제된 설비의 원가 기록은 보존하며 API와 공개 Query에서 제외한다. 기록된 현재 원가는 실제 비용 snapshot이나 전체 가격 변경 이력이 아니다.

## 검증

- `EquipmentHourlyCostIntegrationTest` 6건: 저장/0/해제/재생, 입력 경계와 외부 접근, 동시 수정, 정확한 분 산식, viewer/editor와 프로젝트 격리, JSON·버전 최대값.
- `equipment-hourly-cost.spec.ts`: 모의 API 정상·응답 유실·503·편집 없는 해제/재조회. 원래 expectedVersion, 입력 잠금, 409 뒤 편집 보존/명시적 새로고침, 별도 전환 추정 확인. 개발 DB 호출 없음.
- 조회/저장 hook와 입력 모델 19건, 측정 라인·분기·함수 100%. 전체 결과는 [WORKBOARD](../status/WORKBOARD.md) §1에 기록한다.

## 이어서 구현할 범위

- 다차원 setup 속성 규칙은 [setup 속성 계약](setup-attributes.md)에 연결됐다.
- ~~실제 설비 사용 시간·원가 snapshot~~ → 아래 "실행 실제 setup"(2cd). 계획 원가를 실제 원가로 표시하지 않는다.
- 현재 달력 날짜는 기존 시간대 동작을 유지한다. 프로젝트 시간대 적용은 별도 허가 대기 범위다.

## 실행 실제 setup (2026-10-10, 리드, §2 2cd)

상태: **구현 — 로컬 격리 검증만.** [결정](../status/DECISIONS-2026-10-05.md) §4 "setupCost는 setup 시간 × 설비 시간당 원가. Material Cost에 합치지 않는다. 계획은 현재 설비 원가의 estimate, 실제 원가는 실제 시간·비용 snapshot이 필요하다"의 실제 쪽 구현이다. AS1–AS6은 리드의 구현 선택이다. 새 마이그레이션 **V61** `production_run_setup`(2026-10-16 개발 DB 적용).

| # | 규칙 | 오류 |
|---|---|---|
| AS1 | `POST /production-runs/{id}/setups` `{requestId, equipmentId?, setupMinutes, note?}`, Project write, 열린 실행(pending·running)만. 설비 생략 시 실행의 작업지시 설비. 분은 정수 1–1440, 메모 500자 | 설비 없음·다른 프로젝트·범위 밖 400, 끝난 실행 400 `already finished` |
| AS2 | 기록 순간 설비의 시간당 원가와 그 **버전**을 행에 복사한다. 원가 = 분 × 원가 / 60, 소수 4자리 HALF_UP. 원가가 없으면 원가·금액 null. 뒤의 원가 변경은 이미 기록한 setup을 바꾸지 않는다 | |
| AS3 | `requestId`(UUID)는 실행 안에서 유일. 같은 작성자·같은 분·메모·설비의 재송신은 현재 상태를 돌려준다. 내용이 다르면 409 | 409 `requestId already belongs to a different setup.` |
| AS4 | `POST …/setups/{setupId}/cancel` `{reason}`: 열린 실행에서만, 사유 필수, 행은 지우지 않고 취소 표시(누가·언제·왜). 이미 취소된 것을 다시 취소하면 그대로 | 사유 없음 400 |
| AS5 | `GET /production-runs/{id}/setups`(Project read): 취소되지 않은 setup의 분·알려진 금액 합계, 원가 미상이 있으면 `costComplete=false`, 작업지시 설비 `defaultEquipmentId`, 모든 행(취소 포함). **재료비(`/cost`)에 더하지 않는다** | |
| AS6 | 기록·취소는 실행 행 잠금(`findForUpdate`) 아래에서 해서 마감과 겹치지 않는다. 실행 상세 **Setup (actual)**: 설비(기본 작업지시 설비)·분·메모 기록, 응답 유실 뒤 같은 입력은 같은 requestId로 **Retry setup**, 취소는 사유 입력. 끝난 실행은 읽기만(없으면 패널 숨김) | |

공개 API: `EquipmentCostQuery.findHourlyRate(projectId, equipmentId)`가 원가와 버전을 함께 돌려준다(production은 catalog 저장소를 직접 읽지 않는다).

검증(2026-10-10, 격리 복사본): `RunSetupCostIntegrationTest` 2건(작업지시 설비 기본값, 30/h 45분 22.5 → 원가 60으로 바뀐 뒤 15분 15·이전 행 유지, 같은 키 재송신·다른 내용 409, 원가 없는 설비 known subtotal, 재료비 불변, 취소 사유·합계 제외, viewer 읽기·쓰기 403·외부인 403, 마감 뒤 거절, 입력 검증·없는 실행 404), 설비·실행 원가·경계 테스트 묶음 60건 실패 0. 프런트 타입·lint, 모의 E2E `run-setup-cost.spec.ts`(503 뒤 같은 requestId 재시도, 취소) 포함 기본 모의 E2E 120 통과·16 의도적 제외.

~~작업지시별 계획 추정과 실제의 비교 화면~~ → AS11–AS12(2cy). ~~폐기 처리비 별도 설계~~ → [부산물](bom-by-products.md) WD1–WD9. ~~설비 사용 시간(가동)의 실제 원가~~ → 아래 ER1–ER9(2dk V75, 2dl).

## 실행 설비 가동 시간 (2026-10-12, 리드, §2 2dk)

상태: **구현 — 로컬 격리 검증만.** 사용자 결정(2026-10-12): 가동 시간은 **기록**하고 실행 시간으로 미리 채우며, 비용은 setup처럼 **재료비와 따로** 보인다. 새 마이그레이션 **V75** `production_run_equipment_time`(2026-10-16 개발 DB 적용). 실제 setup(AS1–AS6)과 같은 모양이다.

| # | 규칙 | 오류 |
|---|---|---|
| ER1 | 저장: 실행 × 설비의 가동 분(1–10 080, 7일), 기록 순간 설비 시간당 원가·버전, 금액(분 × 원가 / 60, 소수 4자리 HALF_UP, 원가 미상이면 원가·금액 null), 실행 안에서 유일한 `requestId`, 메모 500자, 취소 표시(사람·시각·사유). 행은 지우지 않는다 | |
| ER2 | `POST /production-runs/{id}/equipment-time` `{requestId, equipmentId?, runningMinutes, note?}`(Project write, 열린 실행만, 실행 행 잠금). 설비를 비우면 작업지시 설비. 같은 작성자·같은 내용 재송신은 지금 상태, 다른 내용은 409. 원가는 기록 순간 값으로 고정(AS2와 같음) | 400 분 범위·설비 없음·끝난 실행 `already finished` / 409 `requestId already belongs to different running time.` / 400 형식 `requestId must be a UUID and runningMinutes a whole number.` |
| ER3 | `POST …/equipment-time/{id}/cancel` `{reason}`: 열린 실행에서만, 사유 필수, 다시 취소하면 그대로 | 400 `Give a reason when cancelling running time.` |
| ER4 | `GET …/equipment-time`(Project read): 취소되지 않은 분·알려진 금액 합계, `costComplete`, `defaultEquipmentId`, 모든 행, **`suggestedMinutes`** = 실행 시작(`created_at`)부터 마감(열린 동안은 지금)까지의 분에서 셈에 드는 setup 분과 이미 기록한 가동 분을 뺀 값(같은 분에 setup과 가동을 두 번 매기지 않게), 남은 것이 없으면 null, 최대 10 080 | |
| ER5 | 재료비(`/cost`)·단위 원가에 더하지 않는다(setup과 같은 원칙, DECISIONS §4) | |
| ER6 | 화면: 실행 상세 **Equipment time (actual)**(setup 패널 아래): 설비(기본 작업지시 설비)·분(제안값으로 채움, 안내 `Suggested from the run's time so far, less recorded setups and running time.`)·메모, **Record running time**, 응답 유실 뒤 같은 입력은 같은 requestId로 **Retry running time**, 행마다 **Cancel time**(사유). 합계 `100 min · Running cost: 50`, 원가 없는 설비는 `Known running cost`. 끝난 실행은 기록하지 않고 보정을 요청한다(ER12) | |
| ER7 | 범위 밖: ~~끝난 실행의 가동 시간 보정(AS7 같은 보정 줄)~~ → ER10–ER12(2dm V76), 가동 원가를 단위 원가에 넣는 것 | |
| ER8 | `GET /work-orders/{id}/running-comparison`(Project read, 2dl): **계획**은 목표 수량 ÷ 설비 시간당 산출(`capacityPerHour`) × 60분(올림, 작업지시 계획 제안과 같은 셈) × 설비의 **현재** 원가, **실제**는 그 작업지시의 물리 실행들이 기록한 가동 분·금액 합(기록 당시 원가, ER2). 설비·산출·목표가 없으면 계획 null, 원가 미상은 금액 null·`actualCostComplete=false`. 재료비에 더하지 않는다 | |
| ER9 | 화면: 작업지시 **Readiness** 펼침의 **Running time**(setup 비교 아래): `Planned: 60 min at 10/h · 30 at today's rate`, `Actual: 50 min · 25 over 1 run`, 차이(`10 min less than planned`). 계획·실제가 모두 없으면 숨김 | |

검증(2026-10-12, 격리 복사본): `RunEquipmentTimeIntegrationTest` — 원가 30 설비·작업지시·실행, 시작을 2시간 앞당기고 setup 20분 → 제안 100 → 90분 기록(원가 30·금액 45, 제안 10) → 같은 키 재송신 1행·다른 내용 409 → 원가 60으로 바꾼 뒤 10분은 10, 첫 행 45 유지, 제안 없음 → 0분·10 081분·UUID 아닌 키 400 → 사유 없는 취소 400, 취소 뒤 합계 90분·45 → 재료비 0 그대로, 외부인 403, 마감 뒤 기록 400. 둘째(2dl): 산출 10/h·원가 30 설비, 목표 5 → 계획 30분·15, 실행에서 40분 기록 → 실제 1실행·40분·20, 설비 없는 작업지시는 계획 null, 외부인 403. setup·아키텍처·마이그레이션과 함께 13건 실패 0(2dk 시점), 전체 백엔드 1,294건 중 실패 6(모두 오류 담당의 새 RED FactsProxySerializationIntegrationTest, 이 작업과 무관), 2dl 뒤 가동·setup·아키텍처 6건 실패 0. 모의 E2E `e2e/run-equipment-time.spec.ts`(제안 100과 안내, 기본 설비, 기록 본문, 행 `30/h`, 합계 `100 min · Running cost: 50`, 제안이 없어지면 빈 칸), `e2e/sub-assembly-order.spec.ts` 확장(Running time 계획·실제·차이).

입력 보존(2026-10-19, 오류 담당 보고를 리드가 반영): 실행 상세의 **Equipment time (actual)**·**Setup (actual)** 기록과 끝난 실행의 보정 요청은 응답이 오기 전에 바꾼 설비·분·메모·사유·취소 선택을 응답 뒤에도 그대로 둔다. 바꾸지 않았으면 전처럼 비운다(작업자 시간 LB12와 같은 방식). 오류 담당의 모의 e2e `equipment-time-input-regression.spec.ts`와 기존 setup·가동 시간·보정 화면 e2e 19건이 통과한다.

## 끝난 실행의 가동 시간 보정 (2026-10-12, 리드, §2 2dm)

상태: **구현 — 로컬 격리 검증만.** 사용자 결정 "가동 원가는 setup처럼 따로"(2026-10-12)를 끝난 실행까지 잇는다. 새 마이그레이션 **V76**(가동 행 원가 기준·보정 줄 종류, 2026-10-16 개발 DB 적용). 아래 setup 보정(AS7–AS10)과 같은 모양이다.

| # | 규칙 | 오류 |
|---|---|---|
| ER10 | [완료 실행 보정](production-run-correction.md) 줄에 `cancel_running_time`(`targetRunEquipmentTimeId`)과 `add_running_time`(`equipmentId`, `runningMinutes` 1–10 080)을 더한다. 설비를 비우면 작업지시 설비(ER2와 같음). 요청 때 검사: 없는 가동 404, 이미 취소됨 409, 같은 가동 두 번 400, 분 범위·없는 설비·작업지시 설비도 없음 400. 열린 실행은 보정하지 않고 ER2–ER3으로 직접 고친다 | 400 `runningMinutes must be whole minutes from 1 to 10080.` / `Choose the equipment of the running time to add; this run's work order has none.` / `The same running time is cancelled twice.` / 409 `This running time was already cancelled by …` |
| ER11 | 승인 때 setup 줄과 함께 재고 이동 뒤에 반영. 취소는 사유 `Correction #N: 사유`, 추가는 원래 마감 시각의 설비 원가(AS8의 원가 이력)로 금액을 매기고 행의 `rateBasis`를 `historical`로, 이력이 그 시각을 덮지 못하면 AS9처럼 `estimated`(마감 뒤 처음 원가를 정한 설비는 원가·금액 null). 메모도 `Correction #N: 사유`. 같은 보정 줄은 두 번 더하지 않는다(줄 ID에서 만든 requestId). 직접 기록한 행은 `recorded`. 응답 줄에 `targetRunEquipmentTimeId`·`runningMinutes`·`createdRunEquipmentTimeId` | |
| ER12 | 화면: 끝난 실행의 **Equipment time (actual)**(기록이 있거나 아직 세지 않은 실행 시간이 있으면 보인다)에 접힌 **Request a running time correction**: 취소할 행 체크(`Cancel OVEN-1 60 min`), **Add running time on**(기본 작업지시 설비, `No running time to add`로 끈다)·**Minutes**(ER4 제안으로 채움, 안내 `Suggested from the run's time, less recorded setups and running time.`)·**Reason**, **Request correction** → `Requested correction #N; the project owner approves it under Corrections.` 보정 목록 문장은 `Cancel running time`·`Add 30 min of running time on OVEN-1`. 행에 `rate at the run's finish`·`estimated rate`. 승인하면 가동 패널을 다시 읽는다 | |
| ER13 | 범위 밖: 가동 원가를 단위 원가에 넣는 것(ER5 그대로) | |

검증(2026-10-12, 격리 복사본): `RunEquipmentTimeCorrectionIntegrationTest` — 원가 30 설비로 45분 기록(`recorded`) → 마감 → 마감 뒤 직접 기록 400 → 원가 90으로 올림, 마감 뒤 처음 원가를 정한 둘째 설비 → 첫 행 취소 + 20분 + 둘째 설비 10분 보정 요청, 승인 전 합계 45 그대로 → 승인: 취소 사유·추가 메모 `Correction #1: …`, 추가 20분은 원가 30·금액 10·`historical`, 둘째 설비는 `estimated`·금액 null, 합계 30분·`costComplete=false`, 재료비 0 그대로. 둘째: 열린 실행 보정 400, 없는 가동 404, 취소된 가동 409, 0분·10 081분·없는 설비·설비 없음 400, 같은 가동 두 번 400, 거절된 요청은 보정을 남기지 않음. 가동·setup·보정·아키텍처·마이그레이션 20건 실패 0. 프런트 typecheck·lint, `correctionModel.test.ts` 10건. 모의 E2E `e2e/run-equipment-time-correction.spec.ts` 2건(제안·기본 설비로 채운 요청 본문과 보정 목록 문장, 추가 없이 취소만), `run-equipment-time.spec.ts`·`run-setup-cost.spec.ts`와 함께 통과.

## 끝난 실행의 setup 보정 (2026-10-10, 리드, §2 2cl)

상태: **구현 — 로컬 격리 검증만.** 새 마이그레이션 **V66**(설비 원가 이력·setup 원가 기준·보정 줄 종류, 2026-10-16 개발 DB 적용). [완료 실행 보정](production-run-correction.md)의 요청(쓰기)·owner 승인(=즉시 반영) 흐름을 그대로 쓴다(§6). AS7–AS10은 리드의 구현 선택이다.

| # | 규칙 | 이유 |
|---|---|---|
| AS7 | 보정 줄에 `cancel_setup`(`targetRunSetupId`)과 `add_setup`(`equipmentId`, `setupMinutes` 1–1440)을 더한다. 요청 때 검사(없는 setup 404, 이미 취소됨 409, 같은 setup 두 번 400, 설비·분 400), 승인 때 재고 줄 뒤에 반영. 취소 사유는 `Correction #n: <사유>`, 행은 지우지 않는다. V22의 종류 CHECK는 고치지 않고 V66에서 새로 건다 | 끝난 실행은 setup을 직접 바꾸지 않고 보정으로만 |
| AS8 | 보정으로 더한 setup은 **실행의 원래 마감 시각의 설비 원가**를 쓴다(§1 "완료 실행 보정은 원래 마감 단가"와 같은 원칙). 원가 이력이 그 시각을 덮지 않으면(첫 변경 전·마감 시각 없음) 처음 알려진 값으로 `estimated` | 보정 시점의 원가로 과거 실행 금액이 바뀌지 않게 |
| AS9 | 설비 시간당 원가가 **바뀔 때마다** `equipment_hourly_cost_history`에 이전/새 값·버전·작성자·UTC 시각을 남긴다(V66부터). 공개 `EquipmentCostQuery.findHourlyRateAt(projectId, equipmentId, at)` | AS8의 근거 |
| AS10 | setup 행의 `rateBasis`: `recorded`(기록 때 원가, 기존 모든 행), `historical`(보정, 마감 시각 이력), `estimated`(보정, 이력이 덮지 않음). 화면에 `rate at the run's finish`·`estimated rate` 표시 | 금액의 근거를 보이게 |
| AS11 | `GET /work-orders/{id}/setup-comparison`(Project read, 2026-10-10 2cy): **계획**은 부하표와 같은 전환 추정(같은 설비의 앞 작업지시 → 이 작업지시의 전환 분 × 설비의 **현재** 원가, SC6), **실제**는 그 작업지시의 물리 실행들이 기록한 setup 분·원가 합(기록 당시 원가, AS2·AS10). 설비·품목·앞 작업지시가 없으면 계획 null, 원가 미상은 금액 null·`actualCostComplete=false`. 재료비에 더하지 않는다 | 작업지시별로 계획한 전환과 실제 setup을 나란히 |
| AS12 | 화면: 작업지시 **Readiness** 펼침의 **Setup**: `Planned: 20 min · 10 at today's rate`, `Actual: 45 min · 22.5 over 1 run`, 차이(`25 min more than planned`). 계획·실제가 모두 없으면 숨김 | |

화면: 끝난 실행의 **Setup (actual)** 아래 접힌 **Request a setup correction**(취소할 setup 선택·더할 설비/분·사유 → 보정 요청). 승인은 기존 Corrections에서 하고, 승인되면 setup 목록을 다시 읽는다. 보정 줄 설명 `Cancel a setup`·`Add a setup of 20 min`.

검증(2026-10-10, 격리 복사본): `RunSetupCorrectionIntegrationTest` 2건(원가 30 → 마감 → 90으로 변경 뒤 보정한 20분은 30 기준 10·historical, 마감 뒤에 처음 원가를 정한 설비는 estimated·금액 미상, 취소 사유, 합계·불완전 표시 / 없는 setup 404·0분 400·없는 설비 400·같은 setup 두 번 400), setup·보정·설비·경계 묶음 104건 실패 0. 프런트 타입·lint·runs 단위 48, 모의 E2E(끝난 실행의 보정 요청) 포함 기본 모의 E2E 124 통과·17 의도적 제외.

~~설비 원가 이력 화면, 보정 승인 화면에서 setup 줄의 설비 이름 표시~~ → §2 2cm(2026-10-10): `GET /equipments/{id}/hourly-cost/history`, Schedule 원가 패널의 최근 변경, 보정 줄 설비 이름.
