# 검사 기준 (Inspection standard)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-MFG-004의 `validation_plan`/`validation_check`, [도메인 로드맵](../archive/2026-09-architecture/domain-roadmap.md) P5 Quality "specification / criterion", [품질 검사](quality-inspection.md) "이후"의 첫 항목. 코드는 가져오지 않았습니다. **새 마이그레이션 V29** (`inspection_standard`, `quality_inspection.standard_id`).

## 왜 필요한가

지금까지 검사는 매번 항목 이름과 한계(하한·상한)를 손으로 입력했습니다. 그래서:
- 같은 품목의 같은 검사라도 기록마다 한계가 달라질 수 있었음
- "이 제품은 어떤 검사를 꼭 해야 하는가"를 알 수 없었고, 실행이 필요한 검사를 빠뜨렸는지 보이지 않았음

## 모델

`inspection_standard` (품목별)

| 열 | 뜻 |
|---|---|
| `item_id` | 대상 품목(필수, 바뀌지 않음) |
| `inspection_type` | 검사 이름. 검사 기록의 `inspection_type`과 같은 뜻(대소문자 무시로 맞춤), 50자 |
| `stage` | `production`(생산 실행 산출물), `receipt`(입고), `any`(언제나) |
| `standard_min`, `standard_max` | 한계. 둘 다 비면 합격/불합격을 사람이 판정 |
| `unit` | 측정 단위(자유 입력, 20자) |
| `required_yn` | `Y`: 이 품목을 만드는 모든 실행이 기록해야 하는 검사 |
| `active_yn` | `N`이면 검사 양식과 체크리스트에서 빠짐 |

`quality_inspection.standard_id`: 검사가 따른 기준. 기준을 삭제해도 과거 검사의 참조는 남습니다(소프트 삭제).

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| S1 | 한 품목에서 같은 검사 이름(대소문자 무시)의 기준은 단계가 겹치지 않아야 함. `any`는 모든 단계와 겹침 | 409 `… already has a … standard for …` |
| S2 | 하한 ≤ 상한 | 400 `The lower limit is above the upper limit.` |
| S3 | 단계는 `receipt`·`production`·`any`만 | 400 |
| S4 | 기준은 품목을 바꿀 수 없음(새로 추가) | 400 |
| S5 | 검사가 `standardId`를 주면: 기준이 이 프로젝트의 활성 기준이어야 하고(비활성 409), 검사 대상 품목이 기준의 품목이어야 함(400 `This standard is for a different item.`). LOT도 품목도 주지 않으면 기준의 품목이 대상 | |
| S6 | 기준을 따르는 검사의 항목 이름·한계·단위는 **기준의 값**. 다른 항목 이름(대소문자 무시로 같으면 허용), 다른 한계, 다른 단위를 보내면 조용히 덮지 않고 거절 | 400 `The standard sets the limits; leave them empty or send the same.` 등 |
| S7 | 합격/불합격은 기존처럼 서버가 측정값과 (기준의) 한계로 계산 | [품질 검사](quality-inspection.md) Q3 |

## 실행 체크리스트

`GET /production-runs/{id}/quality-checklist` — 실행 읽기 권한
- 대상 품목: 실행의 목표 품목과, 취소되지 않은 **산출** 기록의 품목
- 기준: 그 품목들의 **활성**이고 단계가 `receipt`가 아닌 기준
- 각 기준의 상태는 이 실행의 **가장 최근 검사**로 정함: 그 기준을 따른 검사, 또는 기준 없이 같은 품목에 같은 이름(대소문자 무시)으로 기록한 검사. 없으면 `missing`, 있으면 그 결과(`pass`/`fail`)
- 합계: `required`, `requiredPassed`, `requiredMissing`, `failed`(필수 여부와 관계없이 최근 결과가 불합격인 수)
- 정렬: 필수 먼저, 그다음 품목 코드, 검사 이름

실행 종료를 막지는 않습니다(기존 생산 흐름과 e2e를 바꾸지 않음). 화면에서 경고 색으로 보입니다.

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /inspection-standards?projectId=&itemId=` | 읽기 | 품목 코드, 검사 이름, 단계 순 |
| `POST /inspection-standards` | 쓰기 | `{projectId, itemId, inspectionType, stage?, standardMin?, standardMax?, unit?, required?, note?}` |
| `PUT /inspection-standards/{id}` | 쓰기 | 검사 이름·단계·한계·단위·필수·메모를 **통째로 교체**(빈 한계는 지움). `active`는 줄 때만 바꿈 |
| `DELETE /inspection-standards/{id}` | 쓰기 | 소프트 삭제 |
| `GET /production-runs/{id}/quality-checklist` | 읽기 | 위 체크리스트 |
| `POST /quality-inspections` | 쓰기 | 기존 요청에 `standardId` 추가(S5, S6). 응답에도 `standardId` |

## 화면

- 재고 → **Quality** 탭 아래 **Inspection standards**: 표(품목, 검사, 단계, 한계 `10–12 %`·`≤ 12`·`pass/fail`, 필수, 상태, Edit/Deactivate/Delete), 검색, 오른쪽 추가·수정 폼(품목, 검사, 단계, 하한·상한·단위, 필수, 메모). 추가 후 품목과 단계는 남겨 같은 품목의 다음 검사를 빨리 넣게 함
- 검사 기록 양식(실행 상세, LOT 상세, Quality 탭 공통): 검사 대상 품목에 활성 기준이 있으면 **Standard** 선택칸이 나옴. 고르면 검사 이름·한계·단위가 채워지고 읽기 전용이 되며, 측정값만 넣으면 됨. "(none, type the check)"로 자유 검사도 그대로 가능
- 재고 → LOTs → LOT 선택 → Quality 맨 위 **Receipt checks**(2026-10-03): 그 LOT 품목의 활성 `receipt`·`any` 기준(생산 기준은 빠짐), 필수 먼저. 결과는 그 LOT 검사 중 기준을 따른 것, 기준 없이 같은 품목·같은 검사 이름(대소문자 무시)으로 기록한 것의 최신(실행 체크리스트와 같은 규칙). 요약은 실행 체크리스트와 같은 문장(`1 of 2 required checks passed · 1 missing`), 표(검사, 한계, 필수, 결과와 측정값). 품목에 입고 기준이 없으면 보이지 않음. 아래 검사·불량 목록과 같은 조회를 써서 요청이 늘지 않음(서버 계산 없음)
- 재고 → Stock → **Add Stock**으로 LOT 재고를 넣으면(2026-10-03) 폼 아래 **Received LOT L-…**: 그 LOT의 Receipt checks 표와 LOT 상세와 같은 검사·불량 기록(**Record inspection**에서 기준을 고르고 결과 저장). 품목에 입고·any 기준이 없으면 보이지 않고, **Done**으로 닫음. 서버는 바뀌지 않음(같은 조회·기록 API)
- 재고 → **Purchasing** → 발주서 입고 줄의 **Receipt checks**(2026-10-24, 2fo): 발주로 받은 LOT도 Add Stock 뒤와 같은 **Received LOT** 검사 표와 기록 폼을 연다. 품목에 입고·any 기준이 없으면 버튼이 없다([구매 발주](purchasing.md) PO11)
- 재고 → LOTs 목록 위 **Receipt checks** 필터(2026-10-03): `required receipt check missing`(입고·any 필수 기준 중 기록 없는 것이 있는 LOT) 또는 `receipt check failed`(최신 결과가 불합격인 기준이 있는 LOT). 각 LOT은 위 Receipt checks와 같은 규칙으로 계산하고, 걸린 LOT 번호 아래 `1 required check missing · 1 failed`를 표시. 프로젝트의 모든 검사를 읽으므로 필터를 고를 때만 기준·검사를 불러옴(기본 화면은 요청이 늘지 않음). CSV 내려받기는 걸러진 줄만
- 실행 상세 → **Finish Run**(2026-10-03): 필수 검사가 아직 없거나 불합격 검사가 있으면 Finish 버튼 위에 `Quality: 1 required check is not recorded (BREAD Visual); 1 check failed (BREAD Colour).`(주황, `role=note`)를 보이고, 종료 확인창에도 같은 문장을 덧붙임. 막지는 않음(마감 차단은 작업 지침의 F1만). 체크리스트와 같은 조회라 요청이 늘지 않음
- 실행 상세 → Quality 맨 위 **Checklist**: "1 of 2 required checks passed · 1 missing" 요약(빠짐·불합격이 있으면 주황), 표(품목, 검사, 한계, 필수, 결과와 측정값). 검사를 기록하면 바로 다시 불러옴. 대상 품목에 기준이 없으면 표시하지 않음

## 검증

- `InspectionStandardIntegrationTest` 2건
  - 기준: `any`와 겹침 409, 같은 단계 중복 409, 다른 단계(입고)는 허용, 하한 > 상한 400, 없는 단계 400, 품목별 목록 정렬, 수정(한계 변경·비활성화), 비활성 기준으로 검사 409, 삭제 후 목록에서 빠짐
  - 체크리스트: 투입 품목의 기준과 입고 기준은 빠지고 산출 품목의 필수 2·선택 1만, 기준과 다른 한계 400, 다른 검사 이름 400, 다른 품목 400, 기준을 따른 측정 12.5 → 서버 판정 불합격·한계·단위·`standardId`·품목(기준에서) 채움, 기준 없이 이름만 같은 `VISUAL` 합격도 반영, 다시 측정 11 → 최근 결과로 합격, 외부인 403
- 전체 백엔드 432건 중 새 실패 없음(실패 1건은 다른 작업자의 진행 중인 포트 테스트), 기존 `QualityIntegrationTest` 통과
- `standardModel.test.ts` 5건(한계 표시, 쓸 수 있는 기준, 폼 검사, 체크리스트 요약, 종료 경고 `finishQualityWarning`: 선택 검사 빠짐·통과만이면 없음, 필수 빠짐 1·2건, 불합격 이름), 기존 `qualityModel.test.ts` 통과(요청 본문은 기준을 골랐을 때만 `standardId`를 넣어 기존 기대값 유지)
- 입고 체크리스트(2026-10-03): `standardModel.test.ts` `receiptChecklist`(입고·any만, 비활성·다른 품목·생산 기준 제외, 기준 없는 같은 이름 검사의 최신, 다른 기준을 따른 같은 이름 검사는 제외, 기준을 따른 검사는 이름이 달라도 인정, 필수 먼저, 요약). 실 화면 `e2e/receipt-checklist.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): API로 LOT 관리 품목·LOT·입고 기준 2개(Seal, Temperature 0–5 °C)·생산 기준 1개 → LOT 선택 → `0 of 2 required checks passed · 2 missing`, Moisture 줄 없음 → API로 Temperature 기준을 따른 3 °C 검사 → `1 of 2 … · 1 missing`, Temperature `✓ Pass (3 °C)`, Seal `Not checked`. 이어서 LOT 목록(2026-10-03): 검색에 LOT 번호 → **Receipt checks** `required receipt check missing`이면 그 줄에 `1 required check missing`, `receipt check failed`면 줄 없음 → API로 Seal 불합격 → 새로 고침 후 `receipt check failed`면 `1 failed`, `missing`이면 줄 없음. `standardModel.test.ts` `receiptCheckCounts`(LOT별 빠진 필수·불합격 수, LOT 없는 검사 제외)·`receiptCheckText`. 이어서 입고 화면(2026-10-03): Stock 탭에서 그 품목·LOT 5개 **Add** → **Received LOT** `1 of 2 required checks passed · 1 failed` → **Record inspection**에서 `Seal · pass/fail (receipt)`·Pass 저장 → `2 of 2 required checks passed` → **Done**으로 닫힘. 끝나면 기준 삭제
- 실 화면 `e2e/inspection-standards.spec.ts` 끝에 종료 경고(2026-10-03): Moisture만 기록한 뒤 Finish 위 `Quality: 1 required check is not recorded (… Visual).`, Finish를 누르면 확인창에 같은 문장 → 취소
- 실 화면 `e2e/inspection-standards.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): API로 품목·재고·기준 2개·실행을 만든 뒤 Quality 탭 표에 `10–12 %`·`pass/fail` → 실행 상세 체크리스트 "0 of 2 … · 2 missing" → Record inspection에서 Standard로 Moisture 선택 시 검사 이름·상한 채워짐 → 측정 11 저장 → "1 of 2 … · 1 missing", Moisture 줄 `✓ Pass (11 %)`. 끝나면 기준 삭제

## 이후

- ~~입고 검사 체크리스트(LOT 상세에 `receipt` 기준 표시)~~ → 위 "화면"의 Receipt checks(2026-10-03)
- ~~입고 기준 필수 검사가 빠진 LOT을 목록에서 거르기~~ → 위 "화면"의 LOT 목록 **Receipt checks** 필터(2026-10-03)
- ~~입고(재고 추가) 화면에서 바로 검사 기록~~ → 위 "화면"의 Stock 탭 **Received LOT**(2026-10-03)
- ~~실행 종료 시 필수 검사 미완료 경고를 종료 확인창에 표시(지금은 체크리스트 색만)~~ → 위 "화면"의 Finish Run(2026-10-03)
- 공정별 기준(`process_id`). 전제였던 공정 단계 실행(`flow_run_step`, V26)은 생겼지만, 검사·기준과는 아직 연결하지 않았습니다
- 작업 지침 단계가 기준의 한계를 따름(2026-10-11, [작업 지침](work-instruction.md) W13–W15, V71). 다른 컨텍스트는 `InspectionStandardQuery`(삭제된 기준도 `active=false`로)로 읽음. 기준을 고쳐도 배포된 지침은 바뀌지 않고 `changed`로 보임
- 그 단계의 실행 확인이 검사를 함께 남김(2026-10-11, [작업 지침](work-instruction.md) R9, V72). 다른 컨텍스트는 `RunInspectionCommand`로 검사를 기록(S5–S7 그대로, 품질 화면과 같은 경로)
- ~~부적합(NCR)·시정 조치(CAPA)~~ → [부적합과 시정 조치](nonconformity.md)로 구현(2026-09-27)
