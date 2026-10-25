# 설비 부하표(Load board)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-PLAN-002 Capacity Resource(`constrained`), [설비 달력](equipment-schedule.md)·[전환 시간](equipment-changeover.md)의 남은 범위 "설비 부하표". 마이그레이션 없음(읽기 전용).

## 왜 필요한가

준비 점검은 작업지시 하나씩 봅니다. 한 주에 어느 설비가 넘치고 어느 설비가 비는지는 모든 작업지시를 설비별로 모아야 보입니다.

## 계산

기간 [from, to)(최대 92일)에서 설비마다:
- **가용 시간**: [설비 달력](equipment-schedule.md)의 교대에서 정지 시간을 뺀 값(달력이 없으면 기간 전체)
- **작업지시**: 그 설비에 붙은 초안·승인·진행 중 작업지시 중 계획 기간이 기간과 겹치는 것. 완료·취소는 제외
- **필요 시간** = 남은 수량 ÷ 시간당 산출(소수 2자리 올림) + [전환 시간](equipment-changeover.md)(앞 작업지시 기준, 준비 점검과 같은 `EquipmentSequence` 규칙). 남은 수량은 목표 − 끝난 실행의 실제 산출(작업지시 목록과 같은 기준). 이미 다 만들었으면 0
- **기간 안 시간**: 작업지시가 기간 안에 다 들어가면 필요 시간 전부. 기간 밖으로 나가면 **그 설비의 가용 시간 비율**로 나눔(작업지시 기간의 가용 시간 중 기간 안에 있는 몫). 작업지시 기간에 가용 시간이 전혀 없거나 366일을 넘으면 시계 시간 비율
- **계획** = 승인·진행 중 작업지시의 기간 안 시간 합. **초안**은 따로 합산(부하율에 넣지 않음)
- **부하율** = 계획 ÷ 가용 × 100(소수 1자리). 가용 시간이 0이면 없음. 계획 > 가용이면 **과부하**(여러 작업지시를 함께 하는 설비는 아님, [설비](equipment.md) E14). 최대 N개인 동시 작업 설비는 가용 × N으로 센다(E18)
- 계획 기간이 없는 열린 작업지시 수(`unplannedOrders`), 목표 수량이나 시간당 산출이 없어 시간을 셀 수 없는 작업지시 수(`unmeasuredOrders`)를 따로 알림

예: 09–17시 월–금 설비(시간당 10), 2030-01-07 주(가용 40시간)
- 승인 100개 월–화 → 10시간
- 초안 50개 수 → 초안 5시간
- 승인 300개 목~다음 주 화, 앞 작업지시에서 60분 전환 → 필요 31시간, 작업지시 기간 가용 32시간 중 16시간이 이 주 → 15.5시간
- 계획 25.5시간, 부하율 63.8%. 승인 200개 월–금을 더하면 45.5시간, 113.8%, 과부하

## API

`GET /equipment-load?projectId=&from=&to=`(읽기 권한). `from`·`to`는 시각대 포함 ISO. 없는 값·시각대 없는 값·to ≤ from·92일 초과 400.

```json
{ "from": "…", "to": "…", "equipment": [{
  "equipmentId": "…", "equipmentCode": "LINE-1", "equipmentName": "…", "equipmentStatus": "active",
  "capacityPerHour": 10, "calendarSet": true, "availableHours": 40.00, "downtimeHours": 0.00,
  "plannedHours": 25.50, "draftHours": 5.00, "loadPercent": 63.8, "overloaded": false,
  "unplannedOrders": 1, "unmeasuredOrders": 1,
  "orders": [{ "workOrderNumber": "WO-…", "workOrderStatus": "approved", "targetItemCode": "BLUE",
               "plannedStartAt": "…", "plannedEndAt": "…", "remainingQuantity": 300,
               "changeoverMinutes": 60, "neededHours": 31.00, "hoursInWindow": 15.50 }] }] }
```

설비는 코드(없으면 이름) 순, 작업지시는 계획 시작 순입니다.

## 화면

재고 → Equipment 탭 아래 **Load by week**(펼칠 때만 불러옴)
- **Previous week / Week of(날짜) / Next week / This week / Refresh**. 주는 현지 월요일 0시부터 7일
- 표: 설비(상태·달력 없음 표시), 가용(정지 시간), 계획, 초안, 부하(막대와 `63.8%`, 넘치면 `· Overloaded` 빨강, 85% 이상 주황), 작업지시(`WO-1 · RED · 10 h`, `WO-2 · draft · 5 h`, `WO-3 · BLUE · 15.5 of 31 h · incl. 60 min changeover`, 셀 수 없으면 `hours unknown`)와 계획 기간 없는·셀 수 없는 작업지시 수

**Timeline**(2026-10-03, 표 아래): 같은 주의 같은 행으로 설비마다 띠 하나. 요일 7칸(`Mon 01-07` …), 작업지시마다 계획 기간을 그 주로 잘라 막대로(왼쪽·폭은 주에 대한 비율, 최소 0.5%). 겹치는 막대는 빈 줄(lane)로 내려 서로 가리지 않음. 초안은 점선, **승인·진행 중 작업지시끼리 계획 기간이 겹치면 빨간 테두리**(동시 작업 설비는 없음, 준비 점검의 `schedule`과 같은 기준, 초안은 겹쳐도 표시 안 함). 막대에 마우스를 올리면 주문 줄과 전체 계획 기간(`WO-1 · RED · 10 h · Mon 01-07 09:00 → Tue 01-08 17:00`). 읽기 전용(`timelineBars`, 서버 변경 없음)

## 전환 순서 제안 (2026-10-03, 커밋 전)

마이그레이션 없음. 읽기 전용이며 작업지시 날짜를 바꾸지 않습니다(승인된 작업지시의 날짜 변경은 WORKBOARD §3의 결정 대기).

| # | 규칙 | 이유 |
|---|---|---|
| Q1 | 대상: 그 주(창)에 보이는 열린 작업지시 중 목표 품목이 있는 것, 초안 포함, 계획 시작 순 | 부하표에 보이는 일과 같은 범위 |
| Q2 | 계획 순서의 전환 합 = 앞 품목 → 다음 품목의 [전환 시간](equipment-changeover.md)(규칙 없으면 0) 합. 첫 작업지시 앞은 그 설비에서 그보다 먼저 시작하는 승인·진행·완료 작업지시의 품목 | 기존 작업지시별 전환 시간과 같은 규칙 조회 |
| Q3 | 진행 중(in_progress) 작업지시는 맨 앞에 고정, 나머지 순서를 바꿔 봄. 8개까지는 모든 순서를 계획에 가까운 것부터 보고 **더 작을 때만** 바꿈(같으면 계획 유지), 넘으면 매번 전환이 가장 작은 다음 품목 | 결과가 늘 같고, 이미 돌고 있는 일은 옮기지 않음 |
| Q4 | 작업지시가 둘 미만이거나 계획 순서의 전환 합이 0이면 없음(`changeovers: null`) | 더 줄일 것이 없음 |

- API: `GET /equipment-load` 응답 줄(`equipment[]`)에 `changeovers: { plannedMinutes, suggestedMinutes, suggestedOrder }`(제안이 없으면 뒤의 둘은 null, `suggestedOrder`는 작업지시 번호)
- 화면: 부하표 작업지시 칸 아래 `Changeovers 3 h as planned · 1 h as WO-0002, WO-0001, WO-0003`(제안이 있으면 주황)
- 같은 변경에서 `EquipmentLoadService`의 `EquipmentRepository`·`ItemRepository` 2줄을 `CatalogQuery`로 옮김(ADR-002 Stage B, 새 공개 API `findProjectEquipments`·`equipmentWindow`와 `EquipmentWindow`, 동결 86 → 84)
- 검증: `ChangeoverOrderTest` 6건(같은 품목 모으기 75 → 30·순서 0,2,1, 같으면 제안 없음, 앞 품목 반영, 진행 중 고정, 9개 이상 가까운 품목, 전환 시간 없음). `EquipmentLoadIntegrationTest` 둘째(A 월·B 화·A 수 초안, A→B 30·B→A 45 → 계획 75, 제안 30·A1, A2, B, 다음 주 없음)와 기존 첫째 그대로. `equipmentLoadModel.test.ts` `changeoverPlanText`. 실 API E2E `e2e/equipment-changeover.spec.ts` 끝: 규칙 2 h·1 h, 작업지시 셋 → `Changeovers 3 h as planned · 1 h as <Later>, <Earlier>, <Third>`, `e2e/equipment-load.spec.ts` 그대로 통과

## 검증

- 타임라인(2026-10-03): `equipmentLoadModel.test.ts` `dayLabels`·`timelineBars`(주 밖 시작은 0에서, 주 밖 주문 제외, 같은 시작은 먼저 끝나는 것부터, lane 쌓기, 승인·진행끼리만 겹침 표시, 초안 제외, 빈 목록 lane 1). 실 화면 `e2e/equipment-load.spec.ts`: 승인 두 건이 겹치는 주에 띠 항목 3개, 첫 주문 `· clashes`, 초안 `· draft`

- `EquipmentLoadIntegrationTest`: 위 예 그대로 — 가용 40, 계획 25.5, 초안 5, 부하 63.8, 과부하 아님, 계획 기간 없음 1, 수량 없음 1, 표시 작업지시 4건(다음 달 것 제외), 기간 밖으로 나가는 작업지시의 전환 60분·필요 31·기간 안 15.5. 달력 없는 설비 168시간·0%. 200개 추가 → 45.5·113.8%·과부하. 거꾸로 된 기간·92일 초과·시각대 없는 값·projectId 없음 400, 외부인 403
- 준비 점검의 앞 작업지시 규칙을 `EquipmentSequence`로 옮긴 뒤 `EquipmentChangeoverIntegrationTest`·`EquipmentScheduleIntegrationTest`·`WorkOrderReadinessIntegrationTest` 통과. 전체 백엔드 443건 통과
- `equipmentLoadModel.test.ts` 3건: 주 시작(현지 월요일 0시, 일요일은 그 주, 월요일은 새 주), 주 이동·날짜 입력, 부하 색·표시, 작업지시 줄
- 실 화면 `e2e/equipment-load.spec.ts`(REAL_API_E2E, `timezoneId: Asia/Seoul`, CI browser-e2e에 추가): 설비(시간당 10, 09–17 월–금)·승인 100·초안 50 → Load by week에서 2030-01-09 주 선택 → `40 h`, `25%`, `WO-… · 10 h`, `draft · 5 h` → 400 승인 추가 후 Refresh → `125% · Overloaded` → Next week에서 그 작업지시 없음·`0%`. 끝나면 작업지시 취소, 설비 삭제

## 작업지시 옮기기 (2026-10-23, 2fi)

화면만 바꿨다(새 API·마이그레이션 없음). 세부는 사용자 사전 위임(2026-10-18)의 권장안이다.

| # | 규칙 | 이유 |
|---|---|---|
| M1 | 부하표의 작업지시 줄마다 **Move**. 새 시작(브라우저 시각의 날짜·시각)을 고르면 계획 길이를 그대로 두고 끝도 같이 옮긴다. 시작이 같거나 읽을 수 없으면 저장하지 않는다 | 끌기보다 정확하고, 길이는 계획 기간 제안이 정한 그대로다 |
| M2 | 초안: 프로젝트 소유자·editor. 지금 초안 저장(`PUT /work-orders/{id}`)으로 다른 칸은 그대로 보내고 날짜만 바꾼다(여러 작업지시 계획의 초안 적용과 같은 방법) | 초안 수정 권한과 같다 |
| M3 | 승인·진행 중: 프로젝트 소유자만, **사유** 필수. 소유자의 일정 변경(`POST /work-orders/{id}/reschedules`, [설비 일정](equipment-schedule.md) P17)을 읽은 날짜와 함께 보내므로, 그사이 날짜가 바뀌었으면 409로 덮어쓰지 않는다 | 승인 작업지시의 날짜 변경 규칙(정책 C)을 그대로 쓴다 |
| M4 | 저장하면 부하표와 작업지시 목록을 다시 읽는다. viewer와 완료·취소 작업지시에는 버튼이 없다(서버도 막는다) | |

- 검증: `equipmentLoadModel.test.ts`(새 시작으로 길이 유지, 같은 시작·빈 값·잘못된 값은 저장 안 함), 모의 e2e `equipment-load-move.spec.ts`(소유자가 초안을 옮기면 다른 칸 그대로 `PUT`, 승인 작업지시는 사유 없으면 막고 사유와 함께 일정 변경 `POST`(읽은 날짜 포함), editor는 초안만 **Move**).

## 이후

- ~~설비별 날짜 칸(간트)으로 보기~~ → 위 Timeline(2026-10-03)
- ~~작업지시를 끌어 계획 기간 옮기기~~ → 아래 "작업지시 옮기기"(2026-10-23, 2fi). 끌기 대신 새 시작을 고르는 방식
- ~~작업자 교대 부하(사람 자원)~~ → 작업자 부하표(2026-10-14, 2dw, [작업자 달력](worker-calendar.md) WC17–WC23)
- 기간 자동 제안(작업지시 하나는 [계획 기간 제안](equipment-schedule.md)에 있음). ~~순서 최적화~~ → 위 "전환 순서 제안"(2026-10-03, 제안만, 적용은 날짜 변경 결정 뒤)

## 전환 비용 추정 (2026-10-08)

각 작업지시 아래 **Setup estimate**는 정확한 전환 분 수 × 현재 설비 시간당 원가 / 60이다. 원가나 전환 시간이 미상이면 unknown, 0분이면 0이다. 작업지시 전체 전환에 대한 현재 원가 추정으로 표시한다. 주간 시간 배분·재료비·실제 실행 원가를 바꾸지 않는다. 원가는 Equipment → Schedule → Equipment hourly cost에서 편집한다. [계약·버전/재시도·검증](equipment-setup-cost.md).

전환 규칙 자체의 수정/삭제는 설비 행 잠금 뒤 최신 상태를 읽는다(§2 2bq). 삭제된 규칙을 뒤늦은 수정이 되살리거나, 삭제가 직전 수정의 시간을 덮어쓰는 두 경합을 `EquipmentChangeoverConcurrencyIntegrationTest`에서 재현·방어했다.

## 속성 규칙과 편집 뒤 재조회 (2026-10-08)

- 전환 시간과 순서 제안은 [setup 속성 규칙](setup-attributes.md)의 품목 쌍 → 속성 → 기존 wildcard/default 선택을 사용한다. 현재 조건의 계획 제안이며 과거 실행 조건을 고정한 값은 아니다.
- 품목 속성/설비 속성 규칙뿐 아니라 기존 품목 쌍·기본 규칙 add/update/remove도 작업지시 준비 점검·기간 계획·부하표를 함께 갱신한다(§2 2bu). 열린 부하표에서 전환 분을 바꾸면 별도 전환 비용 추정도 다시 읽는다.
- 기존 전환 규칙이 없는 안내는 속성 규칙의 존재와 구분한다. 과거 실 API E2E의 `No changeover times set` 접근성 텍스트도 유지한다.
