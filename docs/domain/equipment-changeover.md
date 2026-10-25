# 설비 전환 시간(Setup / Changeover)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-PLAN-004 Setup / Changeover Matrix(라인 세척, 도료 색 전환, 금형 교체). [설비 달력·정지 시간](equipment-schedule.md) 위에 올립니다. 코드는 가져오지 않았습니다. **새 마이그레이션 V33** (`equipment_changeover`).

## 왜 필요한가

같은 설비에서 다른 품목으로 넘어갈 때는 세척·금형 교체 같은 준비 시간이 듭니다. 이 시간은 생산량과 상관없이 전환할 때마다 한 번 들고, 앞에 무엇을 만들었는지에 따라 다릅니다(빨간 도료 → 흰 도료는 길고, 흰 → 빨강은 짧음). 지금까지 준비 점검은 생산 시간만 보았기 때문에, 전환이 끼면 계획 기간이 모자라도 "충분함"으로 보였습니다.

## 모델

`equipment_changeover` (설비별)

| 열 | 뜻 |
|---|---|
| `from_item_id` | 앞에 만든 품목. 비우면 **아무 품목** |
| `to_item_id` | 다음에 만들 품목. 비우면 **아무 품목** |
| `changeover_minutes` | 전환 시간(분). 1~10080(일주일) |
| `note` | 설명(500자) |

DB 제약: 분 범위 CHECK, 삭제되지 않은 규칙은 설비·앞 품목·다음 품목 조합마다 하나(빈 칸끼리도 같은 것으로 봄, `coalesce` 유일 색인).

## 규칙 고르기

앞 품목 A에서 다음 품목 B로 넘어갈 때:
1. **A → B** 규칙
2. 없으면 **A → 아무 품목**
3. 없으면 **아무 품목 → B**
4. 없으면 **아무 품목 → 아무 품목**(기본 전환 시간)
5. 없으면 전환 시간 없음

**같은 품목을 다시 만들면(A → A) 전환이 없습니다.** A → A 규칙을 따로 두었을 때만 그 시간을 씁니다(같은 품목도 배치마다 세척하는 설비).

## 준비 점검에서

설비가 붙은 작업지시의 [준비 점검](work-order-readiness.md)에서:
- **앞 작업지시** = 같은 설비에 붙은 다른 작업지시 중 **승인·진행 중·완료**이고, 대상 품목과 계획 시작이 있으며, 계획 종료가 시작 뒤인 것 가운데 계획 시작이 이 작업지시의 계획 시작보다 앞선 것 중 가장 늦게 시작하는 것. 종료가 없는 진행 중 작업지시는 유효한 앞 작업으로 봅니다. 시작 시각이 같으면 작업지시 ID가 사전순으로 가장 큰 것을 선택합니다. 초안·취소와 끝이 시작보다 이른 잘못된 계획 기간은 세지 않습니다.
- 앞 작업지시와 이 작업지시의 대상 품목으로 규칙을 고릅니다. 규칙이 있으면:
  - `changeover` 항목(ok): `Follows WO-… on Equipment OVEN-1: 120 min changeover from RYE to WHEAT.`
  - `equipment` 항목의 필요 시간에 전환 시간을 더함(시간 단위, 소수 2자리 올림): `needs 12 h (with 2 h changeover) of the 16 h available in the planned window.` 필요 시간이 가용 시간보다 크면 경고(전과 같음)
- 규칙이 없거나, 앞 작업지시가 없거나, 품목이 같으면 전과 같습니다.

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /equipments/{id}/changeovers` | 읽기 | 규칙 목록. 정확한 쌍 → 한쪽이 빈 규칙 → 아무 품목끼리 순, 같은 무리 안에서는 품목 코드 순. 각 규칙에 품목 코드·이름 |
| `POST /equipments/{id}/changeovers` | 쓰기 | `{fromItemId?, toItemId?, minutes, note?}`. 이미 있는 쌍 409 `A changeover from X to Y is already set on this equipment; change its time instead.`, 분 범위 밖 400, 이 프로젝트 품목이 아니면 400 |
| `PUT /equipments/{id}/changeovers/{changeoverId}` | 쓰기 | `{minutes, note}`만 바꿈(품목을 바꾸려면 지우고 다시 추가). 빈 메모는 지움 |
| `DELETE /equipments/{id}/changeovers/{changeoverId}` | 쓰기 | 소프트 삭제. 다른 설비의 규칙 404 |

변경 응답은 모두 규칙 목록 전체입니다.

## 화면

재고 → Equipment → **Schedule** 안의 **Changeovers**
- 추가 폼: From·To(활성 품목, 비우면 `Any item`), Minutes, Note, **Add changeover**. 이미 있는 쌍은 보내기 전에 `This pair already has a changeover; change its time instead.`
- 표: From, To, Time(`1 h 30 min`), Note, **Change time**(그 줄에서 분을 고쳐 Save) / **Remove**
- 바꾸면 열린 준비 점검을 다시 불러옵니다.

## 검증

- `EquipmentChangeoverIntegrationTest` 2건(실제 Postgres)
  - 규칙: 아무→아무 30분, RED→WHITE 90분 → 목록 순서(정확한 쌍 먼저). 같은 쌍 45분 409, 아무→아무 다시 409, 0분·10081분 400, 없는 품목 400(`fromItemId`), 120분으로 수정·공백 메모 지움, 음수 400, 삭제 후 다시 삭제 404, 다시 추가 200, 외부인 읽기·추가 403
  - 준비 점검: 09–17시 월–금, RYE→WHEAT 120분·아무→아무 30분. 월요일 RYE 작업지시가 초안이면 화–수 WHEAT에 `changeover` 없음 → 승인하면 그 번호와 `120 min changeover from RYE to WHEAT`, `needs 12 h (with 2 h changeover) of the 16 h`. 목–금 SPELT는 규칙이 없어 기본 30분(`needs 10.5 h (with 0.5 h changeover)`). RYE 다음 RYE는 전환 없음. 규칙을 모두 지우면 `needs 10 h of the 16 h`
- 기존 `EquipmentScheduleIntegrationTest`·`WorkOrderReadinessIntegrationTest` 포함 전체 백엔드 442건 통과
- `changeoverModel.test.ts` 2건: 분 표시(`45 min`, `2 h`, `1 h 30 min`), `Any item`, 분 읽기(1~10080 정수), 요청 본문과 이미 있는 쌍 거절
- 실 화면 `e2e/equipment-changeover.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): 품목 둘·설비(시간당 10, 09–17 월–금)·작업지시 둘(월요일 앞 품목 승인, 화–수 다음 품목)을 API로 만들고 → Schedule의 Changeovers에서 90분 추가 → `1 h 30 min` → 같은 쌍 다시 추가는 경고 → Change time으로 120 → `2 h` → 다음 작업지시 Readiness에 앞 작업지시 번호, `120 min changeover from A to B`, `needs 12 h (with 2 h changeover) of the 16 h`. 끝나면 작업지시 취소, 설비 삭제

## 2026-10-05 확정: Setup 속성과 비용

**Accepted (정책).** 다차원 setup attributes 승인, 단일 setup_group 문자열 미채택. 품목 쌍 > 속성 쌍 > default. setupCost = 시간 × 설비 시간당 원가, Material Cost와 분리. 계획 estimate·실제 snapshot 구분. 아래 메모는 검토 이력이다. 구현은 2026-10-08 2bt([setup 속성](setup-attributes.md), V57)·2bs(설비 시간당 원가, V56)와 2026-10-10 2cy(계획·실제 비교)로 끝났다(2026-10-24 정정). [최종 결정](../status/DECISIONS-2026-10-05.md)이 아래 예전 선택지보다 우선한다.

## 결정 메모: setup 묶음과 전환 비용 (Proposed, 2026-10-04)

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §4의 제품 규칙 묶음 중 전환 쪽을 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

- 지금 코드: `equipment_changeover`(V33)는 앞 품목·다음 품목(둘 다 비우면 아무 품목)과 `changeover_minutes`(1–10080)만 가진다. 품목이 많으면 쌍마다 규칙을 만들어야 한다. 비용 열은 없고, 설비에는 시간당 전력·물만 있고 인건비·시간당 원가는 없다.
- setup 묶음 선택지
  - A: 품목에 `setup_group` 글 열 하나(예 `red`, `mold-12`)를 두고, 규칙의 앞·다음에 품목 대신 묶음을 쓸 수 있게 함. 가장 구체적인 규칙 우선(품목 쌍 > 묶음 쌍 > 아무 품목)
  - B: 품목 속성(jsonb)의 키로 묶음. [품목 상세](item-details.md)의 jsonb 구조 결정이 먼저
  - C: 지금처럼 품목 쌍과 아무 품목 규칙만
- 전환 비용 선택지
  - A: 규칙에 금액 열을 둠
  - B: 분 × 설비 시간당 원가(새 설비 필드)로 계산. 실행 원가를 언제 정하는지([재료비](material-cost.md) 결정 메모)와 함께 정함
- **권장안: 묶음은 A, 비용은 B.** 묶음 하나로 규칙 수가 줄고 마이그레이션 하나(품목 열·규칙의 묶음 열 두 개)로 끝난다. 비용은 분을 이미 쓰고 있으니 시간당 원가 하나만 더하면 [전환 순서 제안](equipment-load.md)도 금액으로 바꿀 수 있다.

## 이후

- ~~품목 대신 **setup 묶음**(색·금형·재질)으로 규칙 두기~~ → 다차원 setup 속성(2bt, [setup 속성](setup-attributes.md), V57)(2026-10-24 정정)
- ~~전환 비용(`cost`)~~ → 시간 × 설비 시간당 원가(2bs, V56)(2026-10-24 정정). ~~전환 순서 최적화~~ → [설비 부하표](equipment-load.md) "전환 순서 제안"(2026-10-03, 분 기준 제안만)
- ~~실행 기록에 실제 전환 시간을 남겨 규칙과 비교~~ → 작업지시 setup 계획·실제 비교(2cy, V61 실행 setup 기록)(2026-10-24 정정)
