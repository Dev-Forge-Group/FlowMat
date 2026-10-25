# 설비 달력·정지 시간과 작업지시 설비 점검

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-PLAN-002 Capacity Resource, FM-PLAN-003 Calendar(설비 가동시간·교대·정비 시간). [작업지시 실행 준비 점검](work-order-readiness.md)의 남은 범위 "설비·달력·용량 점검". 코드는 가져오지 않았습니다. **새 마이그레이션 V32** (`equipment_calendar`, `equipment_downtime`, `work_order.equipment_id`). 기존 V1~V31은 바꾸지 않았습니다. 이후 **V41**(프로젝트 휴일, 2026-10-02)과 **V43**(설비당 교대 여러 개, 2026-10-03), **V45**(날짜별 교대, 2026-10-03)가 더해졌습니다.

## 왜 필요한가

설비에는 시간당 산출(`capacity_per_hour`, [설비 정보](equipment.md))이 있었지만, 언제 일하는지와 언제 멈추는지가 없었습니다. 그래서 작업지시의 계획 기간 안에 그 설비로 목표 수량을 만들 수 있는지 볼 수 없었습니다.
- 주간 교대만 도는 설비에 이틀짜리 계획을 잡으면 실제 가동은 16시간뿐입니다.
- 정비나 고장으로 멈추는 날이 있으면 그만큼 줄어듭니다.
- 같은 설비에 같은 시간대의 다른 작업지시가 잡혀 있어도 알 수 없었습니다.

## 모델

| 테이블 | 뜻 |
|---|---|
| `equipment_calendar` | **교대 한 줄**(V43부터 키는 `shift_id`, 설비당 최대 6줄). `equipment_id`, `shift_start`·`shift_end`(시각), `work_days`(ISO 요일 "1,2,3,4,5", 1 = 월요일), 수정한 사람·시각. **설비에 줄이 없으면 항상 가동**. V43 이전의 설비당 한 줄은 그대로 교대 하나가 됐습니다(`shift_id` = 설비 id) |
| `equipment_downtime` | 설비가 일할 수 없는 기간. `downtime_type`(maintenance·breakdown·other), `starts_at`·`ends_at`(시각대 포함), 이유(500자). 소프트 삭제 |
| `work_order.equipment_id` | 작업지시가 도는 설비(FK). 비우면 설비 점검 없음 |

- 교대 시각은 **계획 시간대**(`app.planning.time-zone`, 기본 `Asia/Seoul`)의 현지 시각입니다. 정지 시간은 시각대 포함 시점으로 저장합니다.
- 교대가 시작 시각 이전이나 같은 시각에 끝나면 **자정을 넘깁니다**(22:00–06:00 = 8시간). 시작과 끝이 같으면 **하루 종일**(24시간)입니다.
- `work_days`는 교대가 **시작하는** 요일입니다. 월요일 22:00–06:00은 화요일 06:00에 끝납니다.

DB 제약: 요일 형식 CHECK, 정지 종류 CHECK, `ends_at > starts_at`, 삭제 표시 CHECK.

## 휴일 (2026-10-02, V41)

`project_holiday`: 프로젝트의 쉬는 날(날짜, 이름 100자, 소프트 삭제). 프로젝트·날짜당 살아 있는 휴일 하나(부분 유일 인덱스).

| # | 규칙 |
|---|---|
| H1 | 휴일에는 **교대가 시작하지 않습니다**(달력이 있는 프로젝트의 모든 설비). 전날 저녁에 시작한 야간 교대는 휴일 새벽까지 그대로 돕니다 |
| H2 | 달력이 없는 설비(항상 가동)는 휴일의 영향을 받지 않습니다 |
| H3 | 근무 요일이 아닌 날의 휴일은 아무것도 빼지 않습니다. 가용 시간 응답의 `holidays`에는 **실제로 교대를 뺀** 휴일만 나옵니다 |
| H4 | 가용 시간을 쓰는 곳(가용 시간 API, 작업지시 준비 점검의 설비 항목, [설비 부하표](equipment-load.md), 생산 실행의 연결 지연([ADR-015](../architecture/adr/ADR-015-shift-aware-delay.md), 2026-10-18))에 모두 적용됩니다. 같은 계산(`EquipmentScheduleService.window`)을 쓰기 때문입니다 |

예: 09:00–17:00 월–금, 2032-03-01(월)부터 일주일 40시간 → 수요일 휴일이면 32시간(`holidays: ["2032-03-03"]`), 토요일 휴일은 변화 없음. 22:00–06:00 교대에서 수요일 휴일이면 수요일 하루 동안 화요일 교대의 0–6시 6시간만 남습니다.

## 교대 (2026-10-03, V43)

설비 하나에 교대를 여러 개 둘 수 있습니다. 하루 2·3교대(06:00–14:00, 14:00–22:00, 22:00–06:00)나 짧은 토요일 교대처럼 요일마다 다른 교대를 표현합니다. 출처: 벤치마크 FM-PLAN-003 Calendar.

| # | 규칙 | 위반 응답 |
|---|---|---|
| S1 | 달력 저장은 **교대 전체를 바꿉니다**(`{shifts: [...]}`). 교대 하나만 보내던 예전 본문(`{shiftStart, shiftEnd, workDays}`)도 교대 하나로 받습니다 | |
| S2 | 교대는 1~6개 | 400 `Add at least one shift, or remove the calendar.` / `A calendar can have at most 6 shifts.` |
| S3 | 같은 시간에 **겹치는 교대는 거절**합니다. 일주일 위에 모든 교대를 놓고(일요일 야간 교대는 월요일 새벽으로 넘어감) 겹치면 겹침이 시작되는 요일을 알려 줍니다. 맞닿기만 하는 교대(06:00–14:00과 14:00–22:00)는 됩니다 | 400 `Shifts 1 and 2 overlap on Wed.` |
| S4 | 교대가 여러 개면 교대별 오류 앞에 번호를 붙입니다 | 400 `Shift 2: shiftStart must be a time such as 08:30.` |
| S5 | 휴일은 그날 시작하는 **모든** 교대를 뺍니다. 가용 시간 응답의 `holidays`에는 그 날짜가 한 번만 나옵니다 | |
| S6 | 교대 전체 저장·달력 삭제는 설비별 트랜잭션 advisory lock(`equipment-calendar\|<설비 ID>`)을 잡은 뒤 현재 교대를 읽음(2026-10-04). 빈 달력도 잠금 대상. 기다리던 요청은 앞 요청이 확정한 교대 전체를 바꾸거나 지워, 두 요청의 교대가 섞이거나 삭제가 빠지지 않음. 다른 설비는 별도로 처리 | |
| S7 | 요일은 정수(2026-10-18, 오류 담당 보고, 마이그레이션 없음): 달력 저장의 `workDays`(예전 본문과 `shifts[]` 둘 다)와 날짜별 교대 기간의 `weekDays`는 정확한 정수만 받는다. `1.0`과 전부터 받던 정수 문자열(`"7"`)은 그대로 받고, `1.5`는 거절한다. 전에는 잘라서 1(월요일)로 저장했다. JSON이 아닌 본문은 500이 아니라 400이다. 거절된 요청은 아무것도 바꾸지 않는다 | 400 `workDays are ISO days of the week, 1 (Monday) to 7 (Sunday).` / `weekDays are 1 (Monday) to 7 (Sunday).` / `Send the calendar, day shifts and downtime as JSON, with days of the week as whole numbers from 1 (Monday) to 7 (Sunday).` |

예: 06:00–14:00·14:00–22:00 월–금과 08:00–12:00 토 → 주 84시간. 수요일이 휴일이면 그 주 68시간.

## 달력 판 (2026-10-18, 2es, V94)

설비의 주간 교대는 시작일이 있는 **판**에 묶입니다. 교대를 바꾸거나 달력을 지워도 그 전 날짜의 가용 시간, 준비 점검, 부하표, 계획 기간 제안은 그대로입니다. 근거: [ADR-014](../architecture/adr/ADR-014-equipment-calendar-history.md)(사용자 결정 2026-10-18 "Accept as recommended"), 작업자 쪽 [ADR-013](../architecture/adr/ADR-013-rota-history.md)과 같은 규칙입니다.

| # | 규칙 | 위반 응답 |
|---|---|---|
| CV1 | 판은 시작일(`effectiveFrom`)부터 다음 판이 시작하기 전날까지 적용됩니다. V94 전의 교대는 설비마다 "처음부터" 판(`effectiveFrom` null)이 됐습니다. 판은 설비당 하루 하나입니다 | |
| CV2 | 달력 저장(S1)에 `effectiveFrom`을 줄 수 있습니다. 빼면 프로젝트 기준 오늘입니다. 같은 날짜로 다시 저장하면 그 판을 고칩니다 | 오늘보다 앞이면 400 `effectiveFrom must be today (2026-10-18) or later; change a past date with that date's own shifts.` |
| CV3 | 달력 지우기도 `effectiveFrom`을 받습니다(빼면 오늘). 그날부터 교대 없는 판을 두어 항상 가동이 되고, 그 전 날짜는 교대가 그대로입니다 | CV2와 같음 |
| CV4 | 첫 판 전 날짜와 교대 없는 판의 날짜는 항상 가동입니다. 휴일(H2)과 날짜별 교대도 쓰이지 않습니다 | |
| CV5 | 날짜별 교대 저장(D2)은 요청한 날짜 중 하나라도 교대가 있는 판에 들어야 합니다. 다른 설비로 복사(D7)는 받는 설비에 오늘 판이나 앞으로 올 판에 교대가 있어야 합니다. 날짜별 교대는 판과 상관없이 그 날짜에 묶입니다(사용자 결정 2026-10-18 "날짜에만 묶어 둠") | 409 (D2·D7과 같은 메시지) |
| CV6 | 판 삭제는 아직 시작하지 않은 판만 됩니다. 시작한 판과 처음부터 판은 남습니다. 다른 설비의 판은 404 | 409 `This calendar version has started; save a new one from a later date instead.` |
| CV7 | 일정 응답의 `calendar`는 오늘 판입니다. `versions`에 모든 판이 이른 순으로 나옵니다(`scheduleId, effectiveFrom, current, alwaysRunning`). 가용 시간의 `calendarSet`은 기간 안에 교대가 있는 판의 날이 있을 때 true입니다 | |
| CV8 | 판 저장, 지우기, 삭제는 S6의 설비별 잠금 안에서 합니다 | |
| CV9 | 판 사유(2026-10-18, 2ev, V95, [ADR-017](../architecture/adr/ADR-017-version-reasons.md)): 달력 저장(`reason`)과 지우기(`?reason=`)에 선택 사유(앞뒤 공백 제거, 비면 없음, 200자, 날짜별 교대와 같음). 같은 날짜로 다시 저장하면 사유도 새로 보낸 것으로 바뀐다. `versions[]`와 판 조회에 `reason`, `updatedBy`, `updatedAt`. 저장할 수 없는 문자(NUL, 깨진 UTF-16)는 거절한다 | 400 `reason can be at most 200 characters.` / 400 `reason contains a character PostgreSQL cannot store.` |
| CV10 | **판 저장 이력**(2026-10-19, 2fc, V101, [ADR-024](../architecture/adr/ADR-024-version-save-history.md)): 달력 저장, 날짜부터 달력 지우기(교대 없는 저장, 그날부터 종일), 시작 전 판 지우기마다 한 줄: `scheduleId`, `effectiveFrom`, `action`(`saved`·`removed`), `reason`, 그때의 `shifts[]`(`shiftStart`, `shiftEnd`, `workDays`), `savedBy`, `savedAt`. 지운 판의 줄도 남는다 | |
| CV11 | `GET /equipments/{equipmentId}/calendar/history`(프로젝트 읽기, 최신 순 100줄까지). 화면 설비 일정의 **Calendar history**(접어 두고 열 때 불러옴): `2041-09-01 09:30 · demo-owner saved the version from 2041-09-16: around the clock` | 403 / 404 |
| CV12 | **날짜와 정지 이력**(2026-10-23, 2fe, V102, [ADR-024](../architecture/adr/ADR-024-version-save-history.md) 결정 7–12): 날짜별 교대 저장(D1·D6, 범위는 한 줄), 지우기(지우기 전에 가졌던 것), 다른 설비로 복사(D7, 받는 설비의 저장 한 줄, `copiedFrom`), 정지 추가·지우기마다 한 줄: `kind`(`day`·`downtime`), `action`(`saved`·`removed`), `firstDate`·`lastDate`(정지는 없음), `days[]`(`date`, `shifts[]`, 없으면 닫음, `reason`), `downtimeId`·`downtimeType`·`startsAt`·`endsAt`, `reason`, `copiedFrom`, `savedBy`, `savedAt` | |
| CV13 | `GET /equipments/{equipmentId}/day-history`(프로젝트 읽기, 최신 순 100줄까지). 화면 설비 일정의 **Date and downtime history**(접어 두고 열 때 불러옴, 조회가 거절되면 앞서 받은 줄을 숨김): `2041-09-01 11:00 · demo-owner removed maintenance 2041-09-05 08:00–2041-09-05 12:00 · Oil change`, `… saved 2041-09-03, copied from DRILL: no work` | 403 / 404 |
| CV14 | **이전 이력 보기**(2026-10-23, 2fg): `GET /equipments/{equipmentId}/day-history/search`(판 이력 검색 `…/calendar/history/search`와 같은 필터, `kind` `day`·`downtime`). 화면의 **Calendar history**·**Date and downtime history**도 최신 100줄 뒤 **Show older** | 400 / 403 / 404 |

예: 오늘 08:00–16:00 월–금을 저장하고, 2041-09-09부터 06:00–17:00, 2041-09-16부터 달력 지우기 → 2041-09-02 주 40시간, 09-09 주 55시간, 09-16 주 168시간. 2041-09-16 판을 지우면 그 주도 55시간입니다. 2041-09-17 날짜별 교대는 409, 2041-09-10 닫음은 저장되어 09-09 주가 44시간이 됩니다.

## 날짜별 교대 (2026-10-03, V45)

특정 날짜 하루만 설비의 교대를 바꿉니다. 그 날짜에 **시작하는** 교대를 달력 대신 그날의 교대로 씁니다. 출처: 벤치마크 FM-PLAN-003 Calendar(예외일). 설비별 휴일(닫음), 연말 단축 근무(짧게), 쉬는 날 특근(추가)을 모두 이것으로 표현합니다.

`equipment_day_override`: 설비·날짜당 한 줄(유일 제약). `shifts`는 `"06:00-14:00,14:00-18:00"`(최대 6개, CHECK), 빈 값은 그날 쉼. 메모 200자, 수정한 사람·시각.

| # | 규칙 | 위반 응답 |
|---|---|---|
| D1 | 날짜 저장은 그날의 교대 전체를 바꿉니다. 교대가 없으면 그날 닫음 | |
| D2 | 달력이 있는 설비만. 달력이 없으면 항상 가동이라 바꿀 교대가 없음(달력을 지우면 날짜별 교대는 남지만 쓰이지 않음) | 409 `Set the equipment's calendar first; …` |
| D3 | 하루 교대 1~6개, 서로 1초라도 겹치면 거절(야간 교대는 다음 날 새벽까지로 봄), 맞닿기는 허용. 시각은 분 단위 | 400 `Shifts 1 and 2 overlap.` / `A day can have at most 6 shifts.` / `shiftStart must be a time such as 08:30.` |
| D4 | 그날의 교대는 **프로젝트 휴일보다 우선**합니다(더 구체적인 설정). 휴일이어도 그날의 교대는 돌고, 가용 시간 응답의 `holidays`에도 나오지 않음. 전날 시작한 달력 야간 교대는 그대로 그날 새벽까지 돎 | |
| D5 | 지우면 그날은 다시 달력을 따름. 없는 날짜 지우기 404. 날짜 형식(`2030-01-09`)이 아니면 400 | 404 / 400 |
| D6 | **기간**(2026-10-03, 마이그레이션 없음): 저장에 `throughDate`를 주면 그 날짜부터 `throughDate`까지 모든 날짜에 같은 교대·메모를 둡니다(D1처럼 각 날짜를 바꿈). 한 번에 62일까지. `weekDays`(ISO, 1 = 월요일)를 주면 그 기간 중 그 요일만 바꿈(2026-10-03, 비우면 모든 날, 1~7 밖은 400 `weekDays are 1 (Monday) to 7 (Sunday).`, 남는 날이 없으면 400 `None of the dates fall on the chosen days of the week.`). 지우기에 `?through=`를 주면 그 사이 날짜별 교대를 모두 지우고, 하나도 없으면 404 | 400 `throughDate must not be before the date.` / `A range can cover at most 62 days.` / `throughDate must be a date such as 2030-01-09.` |
| D7 | **다른 설비로 복사**(2026-10-03): 이 설비의 날짜별 교대(`fromDate`·`throughDate` 사이, 빠진 쪽은 열림)를 같은 프로젝트의 다른 설비에 그대로 씁니다. 받는 설비의 같은 날짜는 바뀌고 다른 날짜는 남습니다. 받는 설비도 달력이 있어야 함(D2). 응답은 받는 설비의 일정 | 400 `Choose another equipment to copy to.` / `toEquipmentId is not an equipment of this project.` / `There are no day changes to copy.` / 409 `Set the calendar of EQ-2 first; …` |

가용 시간을 쓰는 모든 곳(가용 시간 API, 준비 점검, [부하표](equipment-load.md), 계획 기간 제안)이 같은 계산(`EquipmentScheduleService.shifts`)을 쓰므로 함께 바뀝니다. 예: 09:00–17:00 월–금, 2031-03-03 주 40시간 → 수요일 닫음 32 → 토요일 08–12 특근 36 → 화요일 06–14·14–18 40 → 목요일이 휴일이면 32, 목요일에 10–12를 두면 34.

## 가용 시간 계산

기간 [from, to)에서:
1. **근무 시간**: 달력이 없으면 기간 전체. 있으면 기간 전날부터 마지막 날까지 교대마다, 그 교대의 근무 요일마다 교대를 만들고, 기간 안으로 자릅니다(전날 시작한 야간 교대가 기간 안으로 들어오는 부분 포함). 겹치는 교대는 합칩니다.
2. **정지 시간**: 기간과 겹치는 정지 기간을 합친 뒤, **근무 시간과 겹치는 부분만** 셉니다. 교대 밖의 정지는 가용 시간을 줄이지 않습니다.
3. **가용 시간** = 근무 − 정지. 시간은 소수 2자리(HALF_UP)입니다.
4. **산출 가능량** = 가용 시간 × 시간당 산출(시간당 산출이 없으면 비움).

예: 09:00–17:00 월–금 달력, 2030-01-07(월) 00:00부터 일주일 → 근무 40시간. 화요일 12:00–20:00 정비 → 근무와 겹치는 12:00–17:00만 5시간 정지, 가용 35시간.

기간과 정지 한 건은 최대 366일입니다.

## 작업지시 설비 점검

`PUT /work-orders/{id}/equipment {equipmentId}`로 설비를 붙이거나(`null`이면 뗌) 바꿉니다. 다른 필드와 달리 **승인 뒤에도** 바꿀 수 있습니다(설비는 완료·취소 전까지 교체될 수 있음).

| # | 규칙 | 위반 응답 |
|---|---|---|
| E1 | 완료·취소된 작업지시는 설비를 바꿀 수 없음 | 409 `WO-… is completed; its equipment can no longer change.` |
| E2 | 같은 프로젝트의 삭제되지 않은 설비만 | 400 `The equipment is not in this project.` |
| E3 | 비활성(inactive) 설비는 붙일 수 없음. 정비 중(maintenance)은 붙일 수 있고 준비 점검에서 실패로 보임 | 409 `… is inactive; choose other equipment.` |
| E4 | 쓰기 권한 필요 | 403 |

[준비 점검](work-order-readiness.md)은 설비가 붙은 작업지시에만 다음을 더합니다. 설비가 없으면 결과는 전과 같습니다.

| code | fail | warn | ok |
|---|---|---|---|
| `equipment` | 설비가 삭제됨, 비활성, 정비 중 | 계획 시작·끝이 없음(또는 끝이 시작보다 앞섬), 계획 기간이 366일 초과, **필요 시간 > 가용 시간**, 가용 시간 0 | 필요 시간 ≤ 가용 시간(`needs 10 h of the 16 h available in the planned window`). 시간당 산출이 없으면 가용 시간만 표시 |
| `schedule` | – | 같은 설비에 **승인·진행 중인 다른 작업지시**의 계획 기간이 겹침(최대 3개 번호 표시) | (표시 안 함) |

- **필요 시간** = 남은 수량 ÷ 시간당 산출(소수 2자리, 올림). 남은 수량은 준비 점검의 같은 값입니다.
- 가용 시간 부족은 경고입니다. 계획 기간은 계획일 뿐이므로 실행 시작을 막지 않습니다. 설비를 쓸 수 없는 상태(비활성·정비 중·삭제)만 실패입니다.
- 메시지 예: `Equipment OVEN-1 needs 10 h for 100 but has only 8 h available in the planned window (8 h down).`

## 계획 기간 제안 (2026-10-03)

설비가 붙은 작업지시에 **가장 이른 계획 시작·끝**을 제안합니다. 읽기 전용이며 작업지시를 바꾸지 않습니다. 초안은 화면에서 그 날짜를 받아 저장할 수 있습니다. 출처: 벤치마크 FM-PLAN-003 Calendar, FM-PLAN-002 Capacity(유한 부하의 가장 단순한 형태).

| # | 규칙 | 거절 응답 |
|---|---|---|
| P1 | 필요 시간 = 남은 수량 ÷ (시간당 산출 × 효율, 2ea [설비](equipment.md) E9)(소수 2자리 올림) + 앞 작업지시에서의 전환 시간. 남은 수량과 전환 시간은 준비 점검과 같은 규칙 | 목표 수량 없음 409 `Set a target quantity first.`, 다 만듦 409, 시간당 산출 없음 409 `Equipment … has no capacity per hour, so how long the order takes is unknown.` |
| P2 | 시작은 `from` 이후 첫 가용 시각(교대 안, 휴일·정지 밖)이고, 끝은 그때부터 가용 시간이 필요 시간만큼 쌓이는 시각을 분 단위로 올린 것. 달력이 없으면 항상 가동 | `from`부터 366일 안에 모자라면 409 `Equipment … does not have N h available in the 366 days from …` |
| P3 | `from`을 안 주면 계획 시작이 아직 앞이면 그 시각, 아니면 지금(다음 분) | 시각대 없는 값 400 |
| P4 | 같은 설비에 **승인·진행 중** 작업지시의 계획 기간과 겹치면 그 작업지시가 끝나는 시각부터 다시 찾음(`movedPast`). 초안은 아직 계획이 아니라 비켜 가지 않음(준비 점검의 `schedule`과 같은 기준). 동시 작업 설비는 비켜 가지 않음([설비](equipment.md) E13) | |
| P5 | 전환 시간은 제안된 시작 직전에 시작하는 작업지시(승인·진행·완료) 기준. 비켜 간 뒤 앞 작업지시가 바뀌면 그 전환 시간으로 다시 계산 | |
| P6 | 설비가 없거나(공정 계획과 인력이 있으면 P21) 삭제·비활성이면, 또는 완료·취소된 작업지시면 제안하지 않음. 정비 중인 설비는 제안함(준비 점검이 실패로 보임) | 409 |
| P7 | 프로젝트 읽기 권한 | 403 |
| P8 | **여러 작업지시 한꺼번에**(2026-10-12, 2do): `POST /work-orders/plan-suggestions` `{projectId, workOrderIds(1–50, 각 한 번), from?}`. 준 **순서대로** 하나씩 P1–P6처럼 놓는다. `from`이 있으면 모두 그 시각부터, 없으면 각자 P3 | 400 `projectId is required.`·`Choose 1 to 50 work orders.`·`Name each work order once.`·`from` 형식 / 404 프로젝트 밖·없는 작업지시 / 403 |
| P9 | 앞에서 놓인 작업지시는 뒤의 것에 **승인된 계획처럼** 보인다(같은 설비의 겹침을 비켜 가고 `movedPast`에 번호, P5 전환 시간의 앞 작업지시). 요청에 든 작업지시의 **지금 계획 기간은 세지 않는다**(다시 계획하는 중이므로). 요청 밖의 승인·진행 중 작업지시는 P4 그대로 | |
| P10 | 놓을 수 없는 작업지시(설비 없음·산출 없음·목표 수량 없음·다 만듦·완료/취소·366일 안 시간 없음)는 그 줄에 `problem`(P1·P6의 409 문구)을 쓰고 나머지는 계속한다 | |
| P11 | 응답 `{from, lines: [{workOrderId, workOrderNumber, workOrderStatus, suggestion(P1–P7 응답과 같음)|null, problem|null}]}`. 아무것도 저장하지 않는다 | |
| P12 | 화면: 작업지시 표 아래 접힌 **Plan several orders**: 설비가 붙은 초안·승인 작업지시를 **누른 순서**로 체크(`1. WO-0002 …`), **Plan from**, **Suggest dates for N orders** → 줄마다 P–요약(`Mon 2030-01-07 09:00 → Mon 2030-01-07 14:00 · needs 5 h`, `· after WO-…`) 또는 문제. **Use these dates for N drafts**는 제안이 있는 **초안만** 그 순서대로 하나씩 저장(작업지시 수정 API, 편집 필드는 그대로), 하나가 거절되면 멈추고 앞에서 몇 개 저장했는지 알린다. 승인 작업지시는 `keeps its dates until the project owner reschedules it`(일정 변경은 owner reschedule, DECISIONS §2 C). 고른 것이 바뀌면 제안을 지운다 | |
| P13 | **순서 제안**(2026-10-12 사용자 결정 "전환 최소", 2dq): `POST /work-orders/plan-order` `{projectId, workOrderIds, from?}`(P8과 같은 검사·권한). 고른 작업지시를 설비별로 묶고(설비는 처음 나온 순서), 설비 없는 작업지시는 준 순서대로 맨 뒤 | P8과 같음 |
| P14 | 한 설비 안에서 **전환 시간 합이 가장 작은 순서**. 첫 작업지시는 `from`(없으면 지금) 전에 그 설비에 계획된 요청 밖 작업지시(P5와 같은 앞 작업지시)의 품목에서 전환한다. 전환 규칙이 없거나 품목이 없으면 0분 | |
| P15 | 합이 같으면 우선순위(urgent > high > normal > low), 계획 끝이 이른 것, 준 순서. 한 설비에 12개까지는 정확히(부분집합 동적 계획), 더 많으면 가장 가까운 것부터 고른다(근사) | |
| P16 | 응답 `{workOrderIds, changeoverMinutes, givenChangeoverMinutes, groups: [{equipmentId, after, workOrderIds, changeoverMinutes, givenChangeoverMinutes}]}`. 아무것도 저장하지 않는다. 화면 **Suggest order**(2개 이상 고르면): 체크 순서를 바꾸고 `Reordered for fewer changeovers: 20 min instead of 120 min.` 또는 `The ticked order already has the fewest changeovers: N min.`, 체크를 바꾸면 안내가 사라진다. 날짜는 그다음 **Suggest dates**(P8). 납기·부하 균형은 보지 않는다 | |
| P17 | **승인 작업지시 함께 일정 변경**(2026-10-13, 2ds, 정책 C): **Plan several orders**의 답에 승인 작업지시가 있고 보는 사람이 프로젝트 소유자면 **Reason** 한 칸과 **Reschedule N approved orders**. 답의 순서대로 하나씩 기존 owner reschedule(`POST /work-orders/{id}/reschedules`, 지금 기간을 `expected…`로 보내 그 사이 바뀌었으면 409, 같은 내용 재시도는 같은 `requestId`)로 제안 기간에 옮긴다. 하나가 거절되면 멈추고 `… N rescheduled before it.`, 다시 누르면 남은 것만 보낸다. 끝나면 `Rescheduled N approved orders.` 소유자가 아니면 `Only the project owner can reschedule the approved orders.` 서버 변경 없음 | 정책 C의 거절 그대로 |
| P18 | **인력**(2026-10-14, 2dx): 작업지시에 인력이 있으면 그 사람들이 모두 일하는 시간에만 생산 시간을 세고, 인력을 이미 잡은 다른 승인·진행 중 작업지시를 비킨다. 응답 `waitedForCrew`·`crewMovedPast`. [작업자 달력](worker-calendar.md) WC24–WC28 | 409 `… and the crew do not have N h available together …` |
| P19 | **효율**(2026-10-17, 2ea): 생산 시간은 시간당 산출 × 효율로 센다. 전환 시간은 효율로 늘리거나 줄이지 않는다. 응답 `efficiencyPercent`(100이면 null), `capacityPerHour`는 정격. [설비](equipment.md) E8–E11 | |
| P20 | **동시 작업**(2026-10-17, 2ec): 여러 작업지시를 함께 하는 설비는 P4·P9에서 겹치는 작업지시를 비켜 가지 않는다(`movedPast` 없음). 인력은 그대로 비켜 간다. 전환 시간은 그대로. [설비](equipment.md) E12–E15. 최대 N개(2ek)면 N개가 함께 도는 순간에만 비켜 가고, 그중 가장 먼저 끝나는 시각부터 다시 찾는다(E17) | |
| P21 | **공정 계획의 노동 시간**(2026-10-25, 2hf): 작업지시에 공정 계획이 있으면 남은 작업자 분을 인력의 몫으로 나눈 노동 시간을 세어, 필요 시간은 생산 시간과 노동 시간 중 긴 쪽 + 전환 시간이다. 설비가 없으면 인력의 시간에서만 놓는다. 응답 `laborMinutes`·`laborHours`. [작업자 달력](worker-calendar.md) LB25–LB26 | LB25의 409 |

예: 09:00–17:00 월–금, 시간당 10, 100개 → 10시간. 2030-01-07(월) 0시부터 → 월 09:00 → 화 11:00. 화 09–10시 정비 → 화 12:00. 월 13–15시에 승인된 작업지시가 있으면 15:00부터 → 수 10:00.

구현: production의 `WorkOrderPlanService`가 필요 시간과 비켜 가기를 계산하고, 설비 시간은 catalog 공개 API(`CatalogQuery`의 `findProjectEquipment`·`earliestSlot`·`changeoverMinutes`, [ADR-002](../architecture/adr/ADR-002-module-dependency.md))로 묻습니다. `EquipmentScheduleService.earliestSlot`은 가용 시간 계산과 같은 교대·휴일·정지 규칙을 씁니다.

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /equipments/{id}/schedule` | 읽기 | `{equipmentId, timeZone, calendar: {shifts: [{shiftId, shiftStart, shiftEnd, workDays, shiftHours}], weeklyHours, updatedBy, updatedAt} \| null, downtimes: [{downtimeId, downtimeType, startsAt, endsAt, hours, reason, createdBy, createdAt}]}`(정지는 최신 시작순) |
| `PUT /equipments/{id}/calendar` | 쓰기 | `{shifts: [{shiftStart: "06:00", shiftEnd: "14:00", workDays: [1,2,3,4,5]}, …], effectiveFrom?}`로 그 날짜(빼면 오늘)부터의 교대 전체를 바꿈(위 S1~S4, CV2). 예전 본문 `{shiftStart, shiftEnd, workDays}`도 교대 하나로 받음. 교대는 시작 시각순으로 돌려줌. 요일은 정렬·중복 제거. 잘못된 시각·빈 요일·1~7 밖 요일 400 |
| `DELETE /equipments/{id}/calendar?effectiveFrom=` | 쓰기 | 그 날짜(빼면 오늘)부터 항상 가동, 그 전 날짜는 교대 그대로(CV3). `reason=`은 그 판의 사유(CV9) |
| `GET /equipments/{id}/calendar/versions/{scheduleId}` | 읽기 | 판 하나 `{scheduleId, effectiveFrom, current, alwaysRunning, shifts, weeklyHours}`. 다른 설비의 판 404 |
| `DELETE /equipments/{id}/calendar/versions/{scheduleId}` | 쓰기 | 아직 시작하지 않은 판 삭제(CV6). 응답은 새 일정 |
| `PUT /equipments/{id}/days/{date}` | 쓰기 | `{shifts: [{shiftStart, shiftEnd}], reason?, throughDate?, weekDays?}`, 빈 목록은 그날 닫음(D1~D5), `throughDate`면 그 날까지 모든 날짜(D6). 응답 일정의 `days: [{date, shifts: [{shiftStart, shiftEnd, shiftHours}], closed, hours, reason, updatedBy, updatedAt}]`(날짜순) |
| `DELETE /equipments/{id}/days/{date}?through=` | 쓰기 | 그날(또는 `through`까지의 날짜별 교대 모두, D6) 달력으로 되돌림 |
| `POST /equipments/{id}/days/copy` | 쓰기 | `{toEquipmentId, fromDate?, throughDate?}` 날짜별 교대를 다른 설비로(D7). 응답은 받는 설비의 일정 |
| `POST /equipments/{id}/downtimes` | 쓰기 | `{downtimeType?(maintenance), startsAt, endsAt, reason?}`. 시각은 시각대 포함 ISO. 끝 ≤ 시작, 알 수 없는 종류, 366일 초과 400 |
| `DELETE /equipments/{id}/downtimes/{downtimeId}` | 쓰기 | 소프트 삭제. 다른 설비의 정지 404 |
| `GET /equipments/{id}/availability?from=&to=` | 읽기 | `{calendarSet, workingHours, downtimeHours, availableHours, capacityPerHour, capacity, holidays}`. 시각대 없는 값·to ≤ from·366일 초과 400 |
| `PUT /work-orders/{id}/equipment` | 쓰기 | 위 E1~E4. 응답은 작업지시(`equipmentId` 포함) |
| `GET /work-orders/{id}/plan-suggestion?from=` | 읽기 | 위 P1~P7. `{workOrderId, equipmentId, from, plannedStartAt, plannedEndAt, remainingQuantity, capacityPerHour, productionHours, changeoverHours, neededHours, changeoverFrom, movedPast}`. 시각은 계획 시간대 |
| `POST /work-orders/plan-suggestions` | 읽기 | 위 P8~P11. 본문 `{projectId, workOrderIds, from?}`, 응답 `{from, lines: [{workOrderId, workOrderNumber, workOrderStatus, suggestion, problem}]}`. 형식이 틀린 본문 400 `Send projectId, workOrderIds as a list of work order ids and from as text.` |
| `POST /work-orders/plan-order` | 읽기 | 위 P13~P16. 본문은 P8과 같고 응답 `{workOrderIds, changeoverMinutes, givenChangeoverMinutes, groups}` |
| `GET /holidays?projectId=` | 읽기 | 휴일 목록(날짜순) `[{holidayId, date, name, createdBy}]` |
| `POST /holidays` | 쓰기 | `{projectId, date: "2032-03-03", name?}`. 같은 날짜 409, 날짜 형식·누락 400, 이름 100자 초과 400. 응답은 전체 목록 |
| `DELETE /holidays/{holidayId}` | 쓰기 | 소프트 삭제, 없거나 이미 지운 휴일 404. 응답은 전체 목록 |

변경 응답은 모두 새 일정 전체를 돌려줍니다.

## 화면

재고 → **Equipment** 탭 → 설비 행의 **Schedule**
- 요약: `09:00–17:00 · Mon–Fri · 8 h a shift`(야간은 `(ends next day)`, 같은 시각은 `All day`, 달력 없으면 `No calendar: available around the clock.`. 교대가 여러 개면 `; `로 잇고 끝에 주 합계 `· 80 h a week`)와 `Next 7 days: 35 h available (5 h down) · can make 350.`
- **Calendar** 폼: 교대마다 한 줄(시작·끝 시각, 요일 체크, 기본 월–금). 첫 줄은 `Shift start`·`Shift end`, 다음 줄은 `Shift 2 start`처럼 번호. **Add shift**(마지막 교대가 끝나는 시각부터 8시간, 같은 요일. 6개까지), 교대가 둘 이상이면 줄마다 **Remove shift N**, **Save calendar**, 달력이 있으면 **Remove calendar**. 시간대·겹침 안내. 겹침 거절은 서버 메시지를 그대로 보여 줌
- **Applies from**(2026-10-18, 2es): 비우면 오늘. 오늘보다 앞이면 보내기 전에 `Pick today or a later date; change a past date with a day change.` 미래 날짜로 저장하면 `Calendar saved from 2041-09-09; until then the current calendar stays.`, 날짜를 고르면 지우기 버튼이 **Remove calendar from 2041-09-16**이 되고 `Calendar removed from …`. 화면의 오늘은 일정 응답의 `timeZone`으로 셉니다(브라우저 날짜가 아님). 이름은 ADR-014의 Starts on 대신 Applies from입니다. 같은 화면 정지 폼의 `Starts`와 겹치지 않게 했습니다(사용자 사전 위임 2026-10-18).
- **Calendar versions**(판이 둘 이상이거나 앞으로 올 판이 있을 때): `From the start · now`, `From 2041-09-09 · upcoming`(**Edit**·**Delete**), 교대 없는 판은 `· around the clock`(**Delete**만), 지난 판은 `· earlier`. **Edit**은 그 판의 교대와 날짜를 폼에 불러옵니다. **Change a day** 폼은 교대가 있는 판이 하나라도 있으면 보입니다.
- **Note**(2026-10-18, 2ev, 접근 이름 `Calendar version note`, 200자): 저장과 지우기에 판의 사유로 보낸다. 판 줄 끝에 ` · 사유`가 붙고, Edit은 사유도 불러온다. 정지 폼의 Reason과 이름이 겹치지 않게 Note로 했다(ADR-017 결정 6).
- **Change a day** 폼(달력이 있을 때, 2026-10-03): **Day**(날짜), **Closed all day**, 교대 줄(`From`·`Until`, 둘째부터 `From 2`…, **Add day shift**·**Remove day shift N**), **Through**(마지막 날짜, 선택: 주면 버튼이 **Save 3 days**, 그 옆 **Days of the week** 요일 체크박스로 고른 요일만), **Note**, **Save day**. 아래 **Day changes** 표: `Wed 2030-01-09`, `Closed` 또는 `09:00–12:00 · 3 h`, 메모, **Remove**(지난 날짜는 흐리게). 날짜별 교대가 둘 이상이면 **Clear days**(2026-10-03): **Back to the calendar from**·**through** 날짜와 **Clear these days**로 그 사이 날짜별 교대를 한 번에 지움(D6의 `?through=`). 다른 설비가 있으면 그 아래 **Copy day changes**: **Copy to**(프로젝트의 다른 설비), **Copy 5 day changes**(오늘부터의 날짜별 교대 수) → `Copied 5 day changes to EQ-2.`
- **Add downtime** 폼: 종류, 시작·끝(현지 날짜·시각), 이유
- 정지 표: 아직 끝나지 않은 것(빠른 순) 다음에 지난 것(흐리게), **Remove**
- 바꾸면 가용 시간과 열린 준비 점검을 다시 불러옵니다.
- 요약 끝에 그 7일 안에 교대를 뺀 휴일 수(`· 1 holiday off`).

재고 → **Equipment** 탭 아래 **Holidays**(접힘, `N upcoming`): 날짜·이름으로 추가, 날짜순 목록(요일 표시, 지난 날은 흐리게), **Remove**. 바꾸면 모든 설비의 가용 시간·준비 점검·부하표를 다시 불러옵니다.

실행 → Work Orders → **Readiness**
- 점검 목록 위에 **Equipment** 선택(비활성 설비는 지금 붙은 것만 보임). 바꾸면 작업지시 목록과 그 준비 점검을 다시 불러옵니다.
- 작업지시 행의 계획 날짜 아래에 `on 설비코드`.
- 설비가 붙은 작업지시는 **Plan from**(기본: 계획 시작이 아직 앞이면 그 시각, 아니면 다음 분)과 **Suggest dates** → `Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h`(전환 시간이 있으면 `(with 1.5 h changeover after WO-…)`, 비켜 간 작업지시는 `· after WO-…`). 초안이면 **Use these dates**(작업지시 수정 API로 편집 필드를 모두 그대로 보내고 날짜만 바꿈, 저장 뒤 준비 점검을 다시 불러옴), 아니면 `Only a draft's planned dates can change.` 거절은 서버 메시지를 그대로 보여 줌. 설비를 바꾸면 제안을 다시 불러옴
- 작업지시 표 아래 **Plan several orders**(2do, P12): 체크한 순서대로 제안하고, 초안만 **Use these dates for N drafts**로 함께 저장한다.
- 같은 곳의 **Suggest order**(2dq, P16): 고른 작업지시를 설비별 전환 시간이 가장 적은 순서로 바꾼다.
- 같은 곳에서 소유자는 승인 작업지시를 사유 하나로 함께 일정 변경한다(2ds, P17).

## 검증

- 2026-10-04 S6: `EquipmentCalendarConcurrencyIntegrationTest` 4건 통과. 빈 달력·기존 달력 각각에서, 앞 저장이 확정되기를 기다린 전체 교체·삭제를 검사합니다. 수정 전에는 빈 달력에서 두 교대가 섞이거나 삭제가 누락됐고, 기존 달력의 뒤 요청은 409였으며 4건 모두 실패했습니다. 수정 후 모두 200이며 최종 달력은 뒤 요청의 교대 하나 또는 삭제 상태입니다.
- 같은 변경의 전체 백엔드 1,012건 실패·오류·건너뜀 0, 커버리지 기준 통과. 개발 DB를 쓰지 않고 별도 빌드·Testcontainers DB에서 검사했으며 Windows 컴파일 환경 우회는 [WORKBOARD](../status/WORKBOARD.md) §1에 적었습니다.

- `EquipmentScheduleIntegrationTest` 2건(실제 Postgres)
  - 달력 없이 10시간 기간 → 가용 10, 산출 100. 09:00–17:00 요일 `[5,1,2,3,4,4]` → `[1..5]`, 8시간. 일주일 40시간. 화 12:00–20:00 정비 → 정지 5, 가용 35, 산출 350. 22:00–06:00 월요일 → 월 0시~화 12시 8시간, 화 0시~12시 6시간(전날 시작한 교대). 25:00·빈 요일·요일 8·끝 < 시작·종류 coffee·거꾸로 된 기간·366일 초과·시각대 없는 값 400. 외부인 403. 정지 삭제 후 다시 삭제 404, 달력 삭제 → 168시간
  - 준비 점검: 설비 없으면 `equipment` 항목 없음 → 붙이면 `needs 10 h of the 16 h` ok → 화요일 종일 고장 → `needs 10 h for 100 but has only 8 h … (8 h down)` warn → 같은 설비의 초안 작업지시는 `schedule` 없음, 승인하면 그 번호로 warn → 설비 정비 중 → fail·준비 안 됨 → 떼면 항목 없음. 비활성 설비 409, 없는 설비 400, 취소된 작업지시 409, 외부인 403
- 기존 `WorkOrderReadinessIntegrationTest`·`WorkOrderServiceImplTest`·`EquipmentServiceImplTest` 포함 전체 백엔드 440건 통과
- `equipmentScheduleModel.test.ts` 7건: 요일 묶음(`Mon–Fri`, `Mon–Wed, Sat`, `Every day`), 교대 요약(야간·종일), 달력 폼과 요청 본문, 정지 요청(현지 시각 → 시점, 끝 ≤ 시작 거절), 7일 기간과 가용 요약, 정지 나누기, 작업지시 설비 후보와 이름
- 휴일(같은 클래스 세 번째, 2032-03 주): 수요일 휴일·토요일 휴일 → 40 → 32시간, 산출 320, `holidays` 수요일 하나. 22:00–06:00 교대에서 수요일 하루 6시간. 같은 날짜 409, `03/03/2032`·날짜 없음 400, 외부인 403. 둘 다 지운 뒤 다시 지우기 404, 40시간·`holidays` 없음. 부하표·준비 점검 테스트도 통과
- `equipmentScheduleModel.test.ts`: 요약에 `· 1 holiday off`
- 교대 여러 개(2026-10-03, V43, 같은 클래스 네 번째): 06:00–14:00·14:00–22:00 월–금, 08:00–12:00 토 → 교대 3개(시작 시각순), 주 84시간, 월 12:00–16:00 기간 4시간(맞닿은 두 교대). 수요일 겹침·일요일 야간과 월요일 새벽 겹침 400(요일 이름), 둘째 교대 25:00 → `Shift 2: …`, 교대 0개·7개 400(실패 뒤에도 84시간 그대로). 2031-06-04(수) 휴일 → 68시간, `holidays` 하나. 예전 본문으로 저장 → 교대 1개, 삭제 → 항상 가동. V43은 세션 DB에서 트랜잭션 안에 먼저 돌려 기존 달력 10개가 교대 하나씩으로 바뀌는 것을 확인하고 롤백한 뒤 적용했다
- `equipmentScheduleModel.test.ts` 9건: 여러 교대 요약(`; `·주 합계), 다음 교대 제안(14:00 → 22:00, 22:00 → 06:00), 교대 번호 붙은 오류, 교대 0개·7개
- 실 화면(휴일, 2026-10-02): API로 설비(시간당 10, 09:00–17:00 월–금)를 만들고 Schedule 요약 `Next 7 days: 40 h available · can make 400.` → Holidays에서 2026-10-05(월) 추가, 줄 `2026-10-05 Mon …` → 요약 `32 h available · can make 320 · 1 holiday off.` → Remove → 40 h. 콘솔 오류 0. 설비 삭제로 정리
- 실 화면 `e2e/equipment-schedule.spec.ts`(REAL_API_E2E, `timezoneId: Asia/Seoul`, CI browser-e2e에 추가): 설비(시간당 10)·작업지시(100, 2030-01-07 09:00 → 01-08 17:00)를 API로 만들고 → Equipment 탭 Schedule에서 09:00–17:00 저장, 요약 `09:00–17:00 · Mon–Fri · 8 h a shift` → 화요일 종일 breakdown 추가, 표에 `24 h` → Work Orders Readiness에서 설비 선택, 행에 `on 코드`, `needs 10 h for 100 but has only 8 h … (8 h down)` → 정지를 지우고 Check again → `needs 10 h of the 16 h`. 이어서(V43) Schedule에서 **Add shift** → `Shift 2 start` 17:00 제안 → 16:00으로 바꿔 저장하면 `Shifts 1 and 2 overlap on Mon.` → 17:00으로 저장, 요약 `09:00–17:00 · Mon–Fri · 8 h a shift; 17:00–01:00 (ends next day) · Mon–Fri · 8 h a shift · 80 h a week` → Readiness `needs 10 h of the 24 h`. 끝나면 설비 떼기, 작업지시 취소, 설비 삭제
- 날짜별 교대(2026-10-03, V45, `EquipmentScheduleIntegrationTest` 다섯째): 달력 없을 때 409 → 위 "날짜별 교대"의 예 그대로 40 → 32 → 36 → 40, 응답 `days`(날짜순, `closed`, `hours` 12, 둘째 교대 14:00), 휴일 목요일 32·`holidays` 하나 → 목요일 10–12를 두면 34·`holidays` 없음, 겹침·25:00·7개·`07-03-2031` 400, 외부인 403, 토요일 지우기 → 30, 다시 지우기 404. V45는 세션 DB에서 트랜잭션 안에 먼저 만들어 정상 값·빈 값 저장과 형식 오류 거절을 확인하고 롤백한 뒤 적용했다. `equipmentScheduleModel.test.ts`: 날짜 요청 본문(닫음·교대·오류), 요일 이름, 요약. 실 화면 `e2e/equipment-days.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): 수요일 닫음(`Stock take`)·화요일 09–12 → 작업지시 준비 점검 `needs 10 h of the 11 h`
- 기간·복사(2026-10-03, D6·D7, `EquipmentScheduleIntegrationTest.daysChangeOverARangeAndCopyToOtherEquipment`): 2031-04-07~09 닫음 한 번에(`days` 3, 메모) → 주 16시간, 거꾸로 된 기간 400, 정확히 62일 저장 → `?through=`로 뒤 59일 지우기, 63일 400, 날짜 형식 400. 복사: 받는 설비 달력 없으면 409(설비 코드) → 달력·자기 목요일 닫음·토요일 13–15 → 09일부터 복사하면 받는 설비 `days` 3(복사된 수요일 `Line move`, 자기 목요일, 토요일은 08–12로 바뀜)·주 28시간, 범위 안에 없음 400, 거꾸로 400, 자기 자신 400, 없는 설비 400, 외부인 403. 기간 지우기 → 토요일만 남음, 다시 404, 거꾸로 400. `equipmentScheduleModel.test.ts`: 기간 본문(`throughDate`, 같은 날이면 생략), 62일 경계, `dayCount`, `copyableDays`. 실 화면 `e2e/equipment-days.spec.ts`: 2030-01-14 **Through** 01-16 Closed `Line move` → **Save 3 days** → 세 줄 → 둘째 설비로 **Copy 5 day changes** → `Copied 5 day changes to EQ-DAY2-…` → API로 받는 설비 날짜 5개 확인
- 기간 안 요일(2026-10-03): `EquipmentScheduleIntegrationTest.aRangeCanTakeOnlySomeDaysOfTheWeek` — 2031-05-05(월)부터 2주의 월·수·금 닫음 → 날짜별 교대 6개, 그 주 16시간, 요일 8은 400, 토·일 기간에 월요일만이면 400. `equipmentScheduleModel.test.ts` `rangeDates`·`dayCount`(월·수·금 2주 6일), 본문의 `weekDays`(7일이면 생략), 요일 없음·남는 날 없음 오류. 실 화면 `e2e/equipment-days.spec.ts`: 01-14~17에서 **Thu**를 빼 월~수 3일만 닫음(**Save 3 days**), 복사 뒤 **Clear days**로 01-14~16을 지우면 그 세 줄이 빠지고 01-09는 `Closed`로 남음
- S7(2026-10-18): `EquipmentScheduleIntegrationTest.daysOfTheWeekAreWholeNumbers`. 달력 `workDays` [1.5]와 `shifts[].workDays` [6.5], 기간 `weekDays` [7.5]는 400(요일 메시지), 끊긴 JSON은 400, 그 뒤 `[1.0, "7"]`은 월·일로 저장되고 날짜별 교대는 0개. 설비 일정·달력 판·부하·준비 점검 97건(21클래스, 오류 담당 12건 포함) 통과. 오류 담당의 `EquipmentDayWeekdayInputRegressionIntegrationTest`도 리드 격리본에서 12건 통과했고, 오류 담당이 리드 수정의 원본 코드로 다시 확인한 뒤 원본에 넣었다(10-18). 전체 백엔드 1,565건(262클래스, 실패 0) 통과(오류 담당 12건은 따로).
- 계획 기간 제안(2026-10-03) `WorkOrderPlanSuggestionIntegrationTest` 2건: 설비 없으면 409 → 09:00–17:00 월–금에서 100개 → 월 09:00 → 화 11:00(필요 10시간), `from` 없으면 계획 시작부터 같은 답, 월 16:00부터 → 수 10:00. 화 09–10시 정비 → 화 12:00. 같은 설비의 초안은 무시, 승인된 월 13–15시 작업지시 → 15:00부터 수 10:00, `movedPast`에 그 번호. `monday` 400, 외부인 403, 100 000개(10 000시간) 409. 둘째: 항상 가동 프레스에 빨강 → 파랑 90분 전환 규칙, 승인된 빨강 08–10시 → 파랑은 10:00부터 21:30(필요 11.5시간, `changeoverFrom` 빨강). 09:00부터 물으면 빨강을 비켜 같은 답, 07:00부터도 같음. 시간당 산출 없는 설비·목표 수량 없음·취소된 작업지시 409. `ModuleBoundaryTest`(새 repository 참조 없음)·`CatalogQueryImplTest` 통과
- `workOrderPlanModel.test.ts` 4건: 현지 시각 표시(`Mon 2030-01-07 09:00`), 기본 시작(앞에 있는 계획 시작, 아니면 다음 분), 요약(전환 시간·비켜 간 작업지시), 초안 저장 본문(편집 필드 유지)
- 실 화면 `e2e/work-order-plan.spec.ts`(REAL_API_E2E, `timezoneId: Asia/Seoul`, CI browser-e2e에 추가): API로 설비(시간당 10, 09:00–17:00 월–금)와 초안(100, 월 09–10시)을 만들고 설비를 붙임 → Readiness `needs 10 h for 100 but has only 1 h` → **Plan from** 기본값 `2030-01-07T09:00`을 00:00으로 바꿔 **Suggest dates** → `Mon 2030-01-07 09:00 → Tue 2030-01-08 11:00 · needs 10 h` → **Use these dates** → Readiness `needs 10 h of the 10 h`. 끝나면 설비 떼기, 작업지시 취소, 설비 삭제
- 여러 작업지시(2026-10-12, 2do) `WorkOrderBatchPlanIntegrationTest`: 09:00–17:00 월–금·시간당 10 설비, 빨강 → 파랑 60분 전환. [빨강 100, 설비 없음, 파랑 50] → 빨강 월 09:00–화 11:00, 설비 없음은 `problem`, 파랑은 빨강 뒤 화 11:00–17:00(전환 1 h, `changeoverFrom`·`movedPast` 빨강). [파랑, 빨강] → 파랑 월 09–14, 빨강 월 14:00–화 16:00(전환 규칙 없음). 빨강 승인 뒤 파랑만 → 승인된 빨강을 비켜 화 11:00, [파랑, 빨강] → 빨강 자기 기간은 세지 않아 파랑 월 09:00. `from` 없음 → 응답 `from` null, 저장 안 됨. 중복·빈 목록·51개·`from` 형식·`projectId` 없음·형식 틀린 본문 400, 없는 작업지시 404, 외부인 403. 계획 제안·준비 점검·부하표·모듈 경계 관련 13건 실패 0. `workOrderPlanModel.test.ts` +2(고를 수 있는 작업지시, 초안만 적용). 모의 E2E `e2e/work-order-batch-plan.spec.ts`(설비 없는 작업지시는 안 보임, 누른 순서·요청 본문, 줄 문장·문제·승인 안내, 초안 2개만 순서대로 저장).
- 순서 제안(2026-10-12, 2dq) `WorkOrderBatchPlanIntegrationTest` 둘째: A→B·B→C 10분, 나머지 60분인 설비. [C, 설비 없음, B, A] → A, B, C(20분, 준 순서 120분), 설비 없는 것은 맨 뒤, `after` 없음. 10:00 전에 승인된 C 작업지시가 있으면 C, A, B(70분, 준 순서 A, B, C는 80분), `after`가 그 번호. 07:00부터면 앞 작업지시가 없어 A, B, C. 중복 400, 외부인 403. `WorkOrderSequenceServiceTest`(정확한 순서·같은 합의 순위 유지·싼 첫걸음 함정·13개 근사). 배치·순서·단일 제안·모듈 경계 관련 7건 실패 0. `workOrderPlanModel.test.ts` +1(안내 문장). 모의 E2E `e2e/work-order-batch-plan.spec.ts` 둘째(하나만 고르면 꺼짐, 요청 본문, 새 순번, 체크를 바꾸면 안내 사라짐).
- 승인 함께 일정 변경(2026-10-13, 2ds, 프런트만): `workOrderPlanModel.test.ts` +2(승인만 고르기, 지금 기간을 기대값으로 한 변경 본문). 모의 E2E `e2e/work-order-batch-plan.spec.ts` 셋째 — 소유자, 승인 2건 제안 → 사유 없으면 버튼 꺼짐 → 둘째가 409로 거절되면 `… 1 rescheduled before it.` → 다시 누르면 둘째만 보내 `Rescheduled 2 approved orders.`, 첫 본문의 `expectedPlannedStartAt`·`plannedStartAt`·사유·`requestId`. 타입·lint 통과.
- 달력 판(2026-10-18, 2es, V94, 격리 복사본) `EquipmentCalendarHistoryIntegrationTest`: 달력 없던 설비에 오늘 08–16 저장 → 판 1(`current`), 2주 전은 `calendarSet` false·168시간 → 2041-09-09부터 06–16 저장 → 판 2, 응답 `calendar`는 오늘 판 그대로, 09-02 주 40·09-09 주 50 → 같은 날 06–17로 다시 저장 → 55 → 09-16부터 지우기 → 판 3(`alwaysRunning`), 168, 앞 주 55 그대로 → 09-17 날짜별 교대 409, 09-10 닫음 저장 → 44 → 판 조회(`weeklyHours` 55) → 어제 시작 400 → 시작한 판 삭제 409, 미래 판 삭제 → 판 2, 09-16 주 55, 지운 판 조회 404. 설비·계획 관련 85건(29클래스) 실패 0, 전체 백엔드 1,509건(254클래스) 통과(실패 0), 프런트 Vitest 675건·typecheck·빌드, 모의 e2e 185건 통과(실패·flaky 0, 실제 API용 38건 조건부 제외).
- V94 리허설: 일회용 postgres:16(`flowmat-v94-check`, 리드가 만들고 지움)에 V43 뒤 모양의 표와 합성 교대 4행(설비 2개, 달력 없는 설비 1개)을 넣고 V94를 한 트랜잭션으로 적용 → 교대 4행 그대로, 판 2개, 4행 모두 같은 설비의 판, 달력 없는 설비는 판 없음. 같은 날 판 중복과 판 없는 교대는 거절됩니다.
- 판 사유(2026-10-18, 2ev, V95) `VersionReasonIntegrationTest` 설비 쪽: 저장 사유와 `updatedBy`, 미래 날짜부터 지우기의 사유와 판 조회, 201자 400, 저장의 NUL·지우기의 반쪽 서로게이트 400(판 수 그대로). 모의 E2E `equipment-calendar-versions.spec.ts`: 판 줄 ` · New press`, Edit 뒤 Note, 저장 본문 `reason`. 전체 백엔드 1,544건(260클래스) 통과(실패 0, 오류 담당의 새 회귀 27건과 장기 탐색 2건 포함), 프런트 Vitest 677건·typecheck·빌드, 모의 e2e 185건 통과(실패·flaky 0, 실제 API용 38건 조건부 제외).
- 판 저장 이력(2026-10-19, 2fc, V101) `ScheduleVersionHistoryIntegrationTest` 둘째: 오늘 판 저장(Day) → 2041-09-09부터 두 교대(Two shifts) → 2041-09-16부터 달력 지우기(Overhaul) → 2041-09-09 판 지움 → 이력 4줄(removed 두 교대, saved 교대 없음 Overhaul, saved Two shifts, saved Day), 외부인 403. 모의 e2e `equipment-calendar-versions.spec.ts`에 **Calendar history** 두 줄. 전체 백엔드 1,724건(276클래스, 실패 0).
- 날짜와 정지 이력(2026-10-23, 2fe, V102) `DayChangeHistoryIntegrationTest` 둘째: 짧은 날 저장(Short day) → 지움 → 닫는 날(Closed) → 다른 설비로 복사 → 정지 추가·지움 → 이력 5줄(removed·saved downtime의 종류·시각·사유, saved 09-03 닫음, removed 09-02와 그때의 06:00–10:00, saved 09-02), 받은 설비 1줄(`copiedFrom`), 외부인 403. 모의 e2e `day-change-history.spec.ts` 둘째. 전체 백엔드 관련 179건/16클래스 통과(전체 실행은 커버리지 없이 따로).
- 이전 이력 보기(2026-10-23, 2fg) `DayHistorySearchIntegrationTest` 둘째: 닫는 날·정지 → `limit=1` 두 장, `kind=day`, 잘못된 `kind` 400, 외부인 403. 관련 백엔드 70건/4클래스, 프런트 typecheck·lint, 관련 모의 e2e 12건 통과(격리본).
- 프런트 `equipmentScheduleModel.test.ts` +3(판 줄 `now`·`upcoming`·`earlier`·`around the clock`, 시작일 확인과 판 불러오기, 프로젝트 날짜 `projectToday`). 모의 E2E `e2e/equipment-calendar-versions.spec.ts`: 판 목록 → 과거 날짜는 보내기 전에 거절 → 미래 판 **Edit** 후 그 날짜로 다시 저장 → 미래 날짜부터 **Remove calendar** → 판 삭제.

## 2026-10-05 확정: 승인 작업지시 일정

**Accepted (정책).** C 승인. owner 전용 reschedule, 시작 전 두 날짜 변경·시작 후 종료만 변경, 사유와 이전/새 값·변경자·시각 보존. owner 일정 변경은 2026-10-05 구현했다. 아래 Proposed 메모는 이전 검토 이력이다. [최종 결정](../status/DECISIONS-2026-10-05.md)이 아래 예전 선택지보다 우선한다.

## 결정 메모: 승인된 작업지시의 계획 날짜 변경 (Proposed, 2026-10-03)

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §3 "승인된 작업지시의 계획 날짜 변경"을 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

**지금 코드 (2026-10-03 확인)**
- `WorkOrderServiceImpl.updateWorkOrder`는 초안이 아니면 거절한다(`Only draft work orders can be edited`). 승인·취소는 프로젝트 owner만 한다.
- 설비 지정(`PUT /work-orders/{id}/equipment`)만 완료·취소 전까지 바꿀 수 있다.
- 계획 날짜를 쓰는 곳: 준비 점검의 설비 시간(위 "작업지시 설비 점검"), [설비 부하표](equipment-load.md)의 창 안 시간·타임라인, 앞 작업지시를 찾는 전환 시간, 전환 순서 제안, 위 "계획 기간 제안"의 **Use these dates**(초안만).
- 계획 날짜를 쓰지 않는 곳: 간이 MRP(M1), 재고 할당, 피킹 목록. 그래서 날짜를 바꿔도 할당·피킹은 그대로 남는다.

| 안 | 내용 | 기록 | 약점 |
|---|---|---|---|
| A | 지금처럼 초안만. 승인된 작업지시는 취소하고 새로 만듦 | 없음 | 할당·피킹·실행 연결이 끊기고 번호가 바뀜 |
| B | 일반 수정에서 승인·진행 중도 날짜만 허용(쓰기 권한) | 없음 | 승인한 계획이 말없이 바뀜 |
| C | 전용 일정 변경 명령 `POST /work-orders/{id}/reschedule`(새 시작·끝, 이유 필수). 승인과 같은 owner 권한. 진행 중은 끝 날짜만 | 새 표(V52 후보: 이전·새 날짜, 이유, 누가, 언제) | 마이그레이션 하나와 화면 하나가 늘어남 |
| D | 승인 되돌리기(approved → draft) 후 수정·재승인 | 상태 이력 | 상태 기계에 새 전이. 되돌린 동안 할당·실행 규칙이 애매해짐 |

**권장안: C.**
- "초안은 자유, 승인은 약속"이라는 지금 규칙을 유지하면서, 다시 계획하는 일만 이유와 함께 남긴다.
- 정해지면 **Use these dates**, 부하 타임라인 끌어 옮기기, 전환 순서 제안 적용을 승인된 작업지시에도 이 명령 하나로 열 수 있다.
- 영향받는 코드: `WorkOrderServiceImpl`(동결 3줄이라 고치면 Stage B), `WorkOrderController`, 새 엔티티·저장소·마이그레이션, `WorkOrdersPanel`, `EquipmentLoadBoard`.

## 이후

- ~~요일별로 다른 교대, 하루 여러 교대~~ → 위 "교대"(2026-10-03, V43). 휴일 달력은 위 "휴일"(V41)
- ~~설비별 휴일, 특정 날짜만의 교대 변경(예: 연말 단축 근무)~~ → 위 "날짜별 교대"(2026-10-03, V45)
- ~~여러 날짜를 한꺼번에 바꾸기(기간 지정), 날짜별 교대를 다른 설비에 복사~~ → D6·D7(2026-10-03, 마이그레이션 없음). 기간 안 요일 고르기와 화면에서 기간 지우기(**Clear days**)도 2026-10-03
- ~~설비 부하표(설비별 기간 안 작업지시 필요 시간 합계와 가용 시간 비교, FM-PLAN-002 `constrained`)~~ → [설비 부하표](equipment-load.md)(2026-09-27)
- 전환 시간 → [설비 전환 시간](equipment-changeover.md)(2026-09-27, V33)
- ~~작업지시 계획 기간 자동 제안(가용 시간을 채우는 가장 이른 끝 시각)~~ → 위 "계획 기간 제안"(2026-10-03)
- ~~승인된 작업지시의 날짜 바꾸기, 여러 작업지시를 한꺼번에 배치하는 순서 최적화~~ → 아래 "승인 일정 변경 구현"(2026-10-05), P8–P17(2do·2dq·2ds), 부하표의 **Move**([설비 부하표](equipment-load.md) M1–M4, 2026-10-23)
- ~~실행 시작 시 설비 기록과 실제 가동 시간 집계~~ → 실행 설비 가동 시간([설비 원가](equipment-setup-cost.md) ER1–ER13)
- ~~작업자 교대(설비가 아닌 사람 자원)~~ → [작업자 달력](worker-calendar.md)(2026-10-13~, 2du 이후)

### 승인 일정 변경 구현 (2026-10-05)

- `POST /work-orders/{id}/reschedules`: owner만 승인·진행 중 작업지시의 계획 날짜를 변경한다. 일반 초안 수정 API는 그대로 초안 전용이다. 완료·취소·초안은 이 명령에 409다.
- 입력: `requestId`(UUID), `expectedPlannedStartAt`, `expectedPlannedEndAt`(화면에서 읽은 이전 값), `plannedStartAt`, `plannedEndAt`, `reason`(필수, 앞뒤 공백 제거 후 1–1000 Unicode 문자). 날짜는 ISO 시각 또는 명시적 null이며 null은 미계획으로 되돌린다. 실행 시작 후 시작일은 null 해제를 포함해 고정한다. 종료가 시작보다 이르면 400, 변하지 않은 계획도 400이다.
- 같은 작업지시 행 잠금으로 실행 시작·승인·다른 일정 변경과 직렬화한다. 이전 날짜가 달라졌으면 409이며 덮어쓰지 않는다. 시각은 offset과 관계없이 같은 instant를 비교하고 PostgreSQL 정밀도에 맞춰 microsecond까지 보존한다.
- V52 `work_order_reschedule`은 이전/새 날짜·사유·로그인 작성자·변경 시각을 같은 트랜잭션에 추가한다. 이력 저장 실패는 작업지시 날짜 변경도 되돌린다. 기존 작업지시와 기존 마이그레이션은 바꾸지 않는다.
- `requestId`는 작업지시별 유일하다. 동일 작성자의 동일 명령 재송신은 기존 이력을 반환한다. 다른 내용에 키를 재사용하면 409다. 이후 다른 일정 변경이 있었더라도 재송신은 기존 명령을 다시 적용하지 않고 원래 이력과 현재 작업지시를 반환한다. `GET /work-orders/{id}/reschedules`는 프로젝트 읽기 권한으로 최신순 이력을 조회한다.
- 화면 **Schedule**은 이력을 보여 준다. owner만 수정 폼이 있고, 실행 중 시작일은 잠긴다. 화면에서 건드리지 않은 날짜는 원본 API 문자열을 보내 소수 초가 잘리지 않는다. 응답 미확인 시 입력을 잠그고 같은 내용·키로 재시도한다. 충돌 뒤 목록을 재조회하고 **Reload current plan**으로 새 계획을 읽는다. 계획 기간 제안의 자동 적용은 기존처럼 초안만 한다.
- 검증: 격리 Postgres에 V1–V51 그대로 적용한 후 V52 후보를 `BEGIN/ROLLBACK`, `rollback_ok=true`로 확인한 뒤 파일 추가. 통합 23건(권한·상태·입력·동시성·재송신·트랜잭션 롤백), 날짜 모델 4건, 모의 API 브라우저 4건 통과. 전체 백엔드 1,064건 실패·오류·건너뜀 0, 라인 81.15%·분기 67.40%·커버리지 기준·빌드 통과. 프런트 전체 464건·타입 검사·기존 lint·빌드 통과. 기존 lint 구성은 JS/JSX만 검사하므로 TS/TSX 검사 통과로 주장하지 않는다.
- V52는 격리 테스트 DB에만 적용했고 개발/세션 DB에는 적용하지 않았다. 실제 DB 전환은 별도 적용 작업이다. 화면 날짜 입력은 아직 브라우저 현지 시간 기준이며 프로젝트 timeZone 후속 구현은 별도다.
