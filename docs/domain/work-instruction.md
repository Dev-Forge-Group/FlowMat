# 작업 지침과 실행 체크리스트 (Work instruction)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-MFG-003 Work Instruction(instruction revision, 본문, 파일 링크, checklist, operator confirmation). **새 마이그레이션 V35** (`work_instruction`, `work_instruction_step`, `run_instruction_check`). 작업지시에 붙이는 지침 링크(`work_order.pdf_url`)는 그대로입니다. 이후 **V37**(마감 막기 `work_instruction.blocks_finish_yn`)과 **V38**(실행이 따르는 revision 고정 `production_run.work_instruction_id`)이 추가됐습니다(2026-10-02 문서 반영). 2026-10-03에 **V46**(확인 취소 기록, R6)과 **V47**(단계 값 한계, W6·R7)이, 2026-10-11에 **V71**(검사 기준을 따르는 단계, W13–W15)과 **V72**(확인이 남긴 검사, R9)가 더해졌습니다(둘 다 개발 DB 미적용).

## 왜 필요한가

작업지시에는 지침 링크 하나만 있었습니다. 제품마다 반복되는 표준 작업 절차(예열 온도 확인, 성형, 청소)를 단계로 적고, 실행 때 작업자가 한 단계씩 확인하고 값(온도 등)을 남겨야 "누가 언제 무엇을 확인했는지"가 남습니다.

## 모델

- `work_instruction`: 제품(`item_id`)별 revision. `status` draft → released → retired, 제목(200자), 본문(20000자), 문서 링크(http/https, 500자), 마감 막기 여부(`blocks_finish_yn`, 기본 N, V37), 배포한 사람·시각. 제품마다 **배포본 하나, 초안 하나**까지(부분 유일 색인). revision 번호는 삭제된 초안 번호도 다시 쓰지 않음
- `work_instruction_step`: 번호, 내용(500자), 필수 여부, 값 기록 여부와 값 이름(예: `Oven °C`), 값 한계(`value_min`·`value_max`, numeric(18,4), V47). 한계는 값 기록 단계에만 두고 아래 ≤ 위(둘 다 CHECK 제약). 따르는 [검사 기준](inspection-standard.md) `standard_id`(V71, 값 기록 단계에만 CHECK, 외래 키 없음): 한계는 단계에 복사본으로 남아 배포본이 바뀌지 않음
- `run_instruction_check`: 실행 × 단계 한 번의 확인. 값(200자), 메모(500자), 확인한 사람·시각. 취소하면 지우지 않고 취소한 사람·시각(`undone_by`·`undone_at`, V46)을 남김. (실행, 단계)마다 **취소되지 않은** 확인은 하나(부분 유일 인덱스), 취소된 기록은 여럿 쌓일 수 있음. 확인이 함께 남긴 품질 검사 `inspection_id`·`inspection_result`(V72, R9, 둘 다 있거나 둘 다 없음, 결과 pass·fail CHECK). 검사는 바뀌지 않으므로 결과를 옆에 둔다
- `production_run.work_instruction_id`(V38): 실행이 따르는 revision. 첫 확인 때 정해지고, 확인을 모두 취소해도 남는다. 기존 실행은 남아 있던 가장 이른 확인의 revision으로 채웠다

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| W1 | 초안만 고침(제목·본문·링크·단계 추가·삭제). 단계를 지우면 나머지 번호를 1부터 다시 매김 | 409 `… make a new revision to change it.` |
| W2 | 제품에 초안이 있으면 새 초안(생성·새 revision) 불가 | 409 `Revision N is still a draft; edit it or delete it first.` |
| W3 | 배포는 프로젝트 소유자만, 단계가 하나 이상. 이전 배포본은 retired | 403 / 400 `Add at least one step before releasing.` |
| W4 | 새 revision은 배포본·폐기본을 복사한 초안 | |
| W5 | 문서 링크는 절대 http/https 주소만 | 400 |
| W6 | 값 기록 단계에는 한계(`valueMin`·`valueMax`, 하나만도 됨)를 둘 수 있다(2026-10-03, V47). 값을 기록하지 않는 단계에는 둘 수 없고, 아래 한계가 위 한계보다 클 수 없다. 새 revision에 복사된다 | 400 `Limits need a step that records a value.` / 400 `valueMin must not be above valueMax.` |
| W13 | **검사 기준을 따르는 단계**(2026-10-11, V71): 값 기록 단계는 제품의 활성 `production`·`any` 검사 기준(`standardId`)을 따를 수 있다. 한계는 기준의 것을 복사하고, 함께 보낸 한계는 비었거나 같아야 한다(S6처럼 조용히 덮지 않음). 값 이름을 비우면 `검사 이름 단위`(예: `Moisture %`). 입고 기준은 입고 LOT용이라 거절 | 400 `A standard needs a step that records a value.` / 400 `A receipt standard is for incoming LOTs; follow a production one.` / 400 `The standard is not an active inspection standard of this product.` / 400 `The standard sets the limits; leave them empty or send the same.` |
| W14 | 배포할 때 기준을 따르는 단계는 기준의 **지금** 한계를 다시 가져온다. 배포본은 그 뒤 기준이 바뀌어도 그대로(R1·R7은 단계의 복사본 기준). 새 revision(W4)은 연결을 복사하고 기준의 지금 한계를 가져온다. 기준이 비활성·삭제·입고 단계로 바뀌었으면 배포를 막는다(새 revision은 옛 한계를 둔 채 연결만 복사) | 409 `Step 1 follows the Moisture standard, which is no longer an active production standard; remove the step before releasing.` |
| W15 | 응답 단계의 `standardStatus`: `current`(단계 한계 = 기준 한계), `changed`(기준 한계가 바뀜: 초안은 배포 때 가져오고, 배포본은 새 revision이 필요), `inactive`(더는 따를 수 없음). 기준이 없으면 null. `standardName`은 기준의 검사 이름 | |
| W16 | 단계는 그것이 속한 공정(`processId`)을 가질 수 있다(2026-10-17, V91, [ADR-011](../architecture/adr/ADR-011-production-steps.md)). 같은 프로젝트의 지워지지 않은 공정만 된다. 비우면 실행 전체의 단계다. 응답에 `processId`와 `processName`이 있고, 새 revision(W4)으로 복사된다 | 400 `processId is not a process of this project.` |
| R1 | 실행은 **첫 확인 때의 revision**을 따름. 확인 전에는 제품의 현재 배포본을 보여 줌. 실행 중에 새 revision이 배포돼도 진행 중인 체크리스트는 바뀌지 않고, 새 실행은 새 revision을 씀 | |
| R2 | 열린 실행(pending·running)만 확인·취소 | 409 `Run R-… is finished; its checklist can no longer change.` |
| R3 | 값을 기록하는 단계는 값이 있어야 확인 | 400 `Step 1 records Oven °C; enter it.` |
| R4 | 이미 확인한 단계를 다시 확인하려면 먼저 취소 | 409 |
| R5 | 제품에 배포본이 없으면 체크리스트 없음(`instruction: null`, 완료로 봄) | |
| R6 | 확인 취소는 기록을 남김(2026-10-03, V46). 취소된 확인은 진행률·마감 막기 계산에서 빠지고, 응답 `undone`에 취소 이른 순으로 나옴. 같은 단계를 다시 확인할 수 있고, 취소된 확인이 없는 단계를 취소하면 404 | 404 |
| R7 | 한계가 있는 단계의 값은 숫자여야 한다. 한계 밖의 값도 **기록되고** 응답 `checks[].outOfLimits: true`로 표시된다(2026-10-03, V47). 확인을 막지 않고, 완료·마감 막기(F1) 계산에도 영향이 없다 | 400 `Step 1 records Oven °C as a number, such as 200.` |
| R8 | 한계 밖 값은 실행 상세에서 [부적합(NCR)](nonconformity.md) 발행을 **제안**한다(2026-10-03, 프런트만). 2026-10-11부터 확인이 남긴 검사가 불합격이면(R9–R10, 한계를 넘지 않았어도) 같은 방식으로 제안하고 제목은 `Step 2 Visual failed: cracked`(한계 밖이면 기존 제목이 우선). 누르면 대상은 그 실행과 제품, 심각도는 기본(불량 없음 → minor), 제목 `Step 1 Oven °C 250 outside 200–230`, 본문은 지침 제목·revision·단계·기록한 사람·시각. 그 실행에 같은 제목의 열린·닫힌 NCR이 있으면 버튼 대신 그 번호를 보여 준다(취소된 NCR은 세지 않음). 자동으로 만들지 않는다 | 발행 규칙은 NCR의 N1–N4 |
| R9 | **기준을 따르는 단계의 확인은 그 실행의 검사도 남긴다**(2026-10-11, V72). 단계가 따르는 기준(W13)이 활성·생산용이고 한계가 하나 이상이며 실행 제품의 기준이고 값이 숫자면, 확인과 같은 트랜잭션에서 품질 검사(`standardId`, 실행, 제품, 측정값, 메모 `Work instruction step 1: 내용`)를 기록한다. 검사 이름·한계·단위·판정은 기준의 **지금** 값(S6·S7), 단계의 한계 밖 표시(R7)는 단계의 복사본. 한계 없는 기준은 R10, 더는 따를 수 없는 기준·숫자가 아닌 값은 확인만. 확인 취소(R6)는 검사를 지우지 않고(검사는 기록), 다시 확인하면 새 검사가 남아 실행 체크리스트는 최신 검사를 따른다. 응답 `checks[].inspectionId`·`inspectionResult` | |
| R10 | **한계 없는 기준은 확인하는 사람이 판정**(2026-10-11, 마이그레이션 없음): 따르는 기준에 한계가 없으면 확인 요청에 `result`(pass·fail, 대소문자 무시)가 필요하고, 그 결과로 검사를 남긴다(측정값 없음, 메모 `Work instruction step 2: 내용 (기록한 값)`). 다른 단계에 `result`를 보내면 조용히 버리지 않고 거절 | 400 `Step 2 follows the Visual check; say whether it passed (pass or fail).` / 400 `Step 1 takes no result; its value decides the Moisture result.` / 400 `Step 3 takes no result; it does not follow a pass/fail standard.` |
| R11 | 생산 실행의 공정 단계(ADR-011)를 완료할 때, 지침이 마감을 막게 되어 있으면(F1) 그 공정의 필수 단계를 모두 확인해야 한다. 막지 않게 되어 있으면 완료는 되고, 공정 단계 목록에 필요한 수(`instructionRequired`)와 확인한 수(`instructionDone`)가 나온다 | 409 `Confirm the required work instruction step 1 for Bake first.` |

실행 마감(Finish)은 기본적으로 막지 않습니다. 화면이 확인되지 않은 필수 단계 수를 마감 버튼 옆에 알립니다(`2 required instruction steps not confirmed yet.`).

| # | 규칙 | 위반 응답 |
|---|---|---|
| F1 | revision에 **마감 막기**(`blocksFinish`, V37)를 켜면, 그 revision을 따르는 실행은 필수 단계를 모두 확인해야 마감할 수 있다. 초안에서만 바꾸며 새 revision에 복사된다 | 409 `Confirm the required work instruction steps first (1 of 2 done).` 화면 문구 `Confirm 2 required instruction steps before finishing.` |

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /work-instructions?projectId=&itemId=` | 읽기 | 제품별, 최신 revision 먼저. 각 revision에 단계 |
| `POST /work-instructions` | 쓰기 | `{projectId, itemId, title, body?, documentUrl?}` → 초안 |
| `PUT /work-instructions/{id}` | 쓰기 | `{title, body, documentUrl, blocksFinish?}` 초안만 |
| `DELETE /work-instructions/{id}` | 쓰기 | 초안 소프트 삭제 |
| `POST /work-instructions/{id}/steps` | 쓰기 | `{text, required?(true), recordsValue?(false), valueLabel?, valueMin?, valueMax?, standardId?}`(W6, W13). 응답 단계에 `valueMin`·`valueMax`(없으면 null), `standardId`·`standardName`·`standardStatus`(W15). 기준 읽기는 quality 공개 API `InspectionStandardQuery`(ADR-002, 저장소 직접 사용 없음) |
| `DELETE /work-instructions/{id}/steps/{stepId}` | 쓰기 | 초안의 단계 삭제·번호 다시 매김 |
| `POST /work-instructions/{id}/release` | 소유자 | W3 |
| `POST /work-instructions/{id}/revise` | 쓰기 | W4 |
| `GET /production-runs/{runId}/instruction` | 읽기 | `{open, instruction, checks[{stepId, value, note, checkedBy, checkedAt, outOfLimits, inspectionId, inspectionResult}], requiredSteps, requiredDone, complete}`(`outOfLimits`: R7, `inspection…`: R9) |
| `POST /production-runs/{runId}/instruction/steps/{stepId}/check` | 쓰기 | `{value?, note?, result?}`. 기준을 따르는 단계면 검사도 기록(R9, quality 공개 API `RunInspectionCommand`), `result`는 한계 없는 기준 단계만(R10) |
| `DELETE /production-runs/{runId}/instruction/steps/{stepId}/check` | 쓰기 | 확인 취소(R6, 기록은 남음). 응답 `undone: [{stepId, stepNo, value, note, checkedBy, checkedAt, undoneBy, undoneAt}]` |

## 화면

- 재고 → **Instructions** 탭: 제품 선택(옵션에 `released v2 · draft v3` 같은 상태), 지침이 있는 제품 표. 오른쪽에 보여 줄 revision(초안 > 배포본 > 최신): 초안이면 제목·지침 본문·문서 링크 폼(**Save text**), 단계 목록(**Remove**), **Add step**(필수, 값 기록과 값 이름, 값 기록이면 **Min**·**Max**; 단계 목록에 `records Oven °C (200–230)`), **Release**(단계가 있어야), **Delete draft**. 배포본·폐기본이면 읽기 전용과 **New revision**. 단계 추가 중에 다음 단계를 입력해도 그 글이 지워지지 않음(보낸 뒤 바뀐 폼은 비우지 않음)
- 생산 실행 상세: 기록 표 아래 **Work instruction · 제목 v1**: `1 of 2 required steps done`(모두 끝나면 초록 `All 2 required steps done`), 문서 링크, 본문, 단계마다 값 입력(값 기록 단계)과 **Done**, 한계가 있으면 값 입력 옆에 `200–230`(하나만이면 `≥ 200`·`≤ 230`). 확인된 단계는 줄 긋고 `✓ Oven °C 220 · demo-owner · 시각` **Undo**, 한계 밖이면 값 뒤에 빨간 `(outside 200–230)`과 **Raise NCR**(R8, 발행 뒤에는 `NCR-0017`). 마감 폼에 `2 required instruction steps not confirmed yet.`

Instructions 탭의 기준 따르기(2026-10-11, W13–W15): 초안의 **Add step**에서 값 기록을 켜고 제품에 따를 수 있는 기준이 있으면 **Limits from**(`(typed here)` 또는 `Moisture · 10–13 %`, 한계 없는 기준은 `Visual · pass/fail`). 기준을 고르면 Min·Max 칸이 사라지고 값 이름 예시가 `Moisture %`. 단계 목록에 `records Moisture % (10–12) · follows Moisture`, 기준이 바뀌었으면 주황 `The limits of the Moisture standard changed; releasing takes them.`(배포본은 `…; make a new revision to follow them.`), 더는 따를 수 없으면 `The Moisture standard is no longer active; remove this step to release.` 기준 목록은 초안을 볼 때만 불러온다

실행 상세의 기준을 따르는 단계(2026-10-11, R9): 확인 줄에 `✓ Moisture % 13 (outside 10–12) inspection fail · 확인한 사람 · 시각`(불합격은 빨강). 검사를 남긴 확인 뒤에는 실행의 Quality 체크리스트·검사 목록·품질 요약을 다시 불러온다. 한계 없는 기준 단계(R10)는 값 입력 옆에 `Visual…`/**Pass**/**Fail** 선택이 있고, 고르기 전에는 **Done**이 비활성(`personDecides`)

실행 상세 체크리스트 아래 **Undone confirmations (N)**(접힘, 2026-10-03): `Step 2 (220) · confirmed by kim 2026-10-03 07:05 · undone by lee 2026-10-03 07:09`(현지 시각).

## 검증

- 불합격 검사의 NCR 제안(2026-10-11, R8 확장, 프런트만): `workInstructionModel.test.ts` `failedCheckNcrTitle`·`suggestedNcrTitle`(한계 밖 제목 우선, 합격은 없음). 모의 E2E `run-instruction-inspection.spec.ts` 끝: Visual Fail 뒤 **Raise NCR** → 발행 본문 제목 `Step 2 Visual failed: cracked` → 줄에 `NCR-0001`, 버튼 사라짐
- 사람이 판정하는 기준 단계(2026-10-11, R10): `WorkInstructionStandardIntegrationTest` 둘째에서 Visual 단계를 결과 없이 확인 400, 한계 있는 Moisture 단계에 결과 400, `Pass`로 확인 → `inspectionResult` pass, 실행 품질 체크리스트 Visual pass, 검사 메모 `Work instruction step 2: Check the dough (smooth)`, 검사 3건. 관련 47건(순서 고친 뒤 해당 클래스 재실행 통과), 프런트 624건, 모의 E2E 전체 134통과/19제외. `workInstructionModel.test.ts` `personDecides`. 모의 E2E `run-instruction-inspection.spec.ts` 확장(결과 고르기 전 Done 비활성, 본문 `result: 'fail'`, `inspection fail`)
- 확인이 남긴 검사(2026-10-11, V72): `WorkInstructionStandardIntegrationTest` 둘째 — Moisture 10–12 % 기준을 따르는 단계와 한계 없는 Visual 기준 단계를 배포 → 실행에서 11 확인 → `inspectionResult` pass, 실행 품질 체크리스트 Moisture pass·같은 `inspectionId`, 검사 목록에 기준·측정 11·메모 `Work instruction step 1: Check the dough` → Visual `smooth`는 검사 없음 → 취소 후 13 → fail, 체크리스트 fail·`failed` 1, 검사 2건. 지침·실행 지침·검사 기준·품질·아키텍처·마이그레이션 55건 실패 0, 전체 백엔드 1,269건 실패 0(2cz–2db 포함). `workInstructionModel.test.ts` `inspectionText`. 모의 E2E `e2e/run-instruction-inspection.spec.ts`(확인 본문, `inspection fail` 표시, 품질 체크리스트 다시 읽기). V72는 개발 DB에 적용하지 않음
- 검사 기준을 따르는 단계(2026-10-11, V71): `WorkInstructionStandardIntegrationTest` — 값 기록 안 하는 단계 400, 입고 기준 400, 다른 제품의 기준 400, 다른 한계 400, 같은 아래 한계만 보내면 기준 10–12·`Moisture %`·`current` → 기준을 10–13으로 바꾸면 초안은 12 유지·`changed` → 배포 때 13·`current` → 기준 9–13이면 배포본 10 유지·`changed`, 실행에서 9.5는 한계 밖 → 새 revision은 9·연결 유지·`current` → 기준 비활성화 → `inactive`, 배포 409. 지침·실행 지침·검사 기준·첨부·아키텍처·마이그레이션 함께 62건 실패 0. `workInstructionModel.test.ts` 3건(따를 수 있는 기준과 선택지 문구, 한계 대신 기준을 보내는 요청, 바뀐·비활성 안내). 모의 E2E `e2e/instruction-standard.spec.ts`(바뀐 기준 안내, 입고 기준 제외한 선택지, Min 칸 사라짐, 요청 본문, 새 단계 표시). V71은 개발 DB에 적용하지 않음
- 확인 취소 기록(2026-10-03, V46): `WorkInstructionIntegrationTest` — 둘째 단계 취소 → `undone` 1건(단계 번호 2, 확인·취소한 사람) → 다시 확인(완료, `undone` 1) → 다시 취소(`undone` 2, 남은 확인 1) → 또 취소 404. `RunInstructionRevisionIntegrationTest`(첫 확인의 revision 고정) 그대로 통과. V46은 세션 DB에서 트랜잭션 안에 먼저 돌려 기존 확인 14건이 모두 살아 있고, 취소 뒤 다시 확인이 되며, 반쪽 취소 값은 CHECK로 거절되는 것을 보고 롤백한 뒤 적용했다. `workInstructionModel.test.ts` `undoneLine`. 실 화면 `e2e/work-instructions.spec.ts` 끝: Shape **Undo** → `1 of 2` → **Done** → `All 2` → Undone confirmations (1) `Step 2 · confirmed by demo-owner … · undone by demo-owner`
- 한계 밖 값의 NCR 제안(R8, 2026-10-03, 프런트만): `workInstructionModel.test.ts`의 `outOfLimitsNcrTitle`(값 이름 없음·아래 한계 없음, 200자 자름)·`outOfLimitsNcrDescription`·`ncrForValue`(같은 실행·제목, 취소된 것과 다른 실행·다른 값 제외). 실 API E2E `e2e/work-instructions.spec.ts`: 250 Done 뒤 **Raise NCR** → `(outside 200–230) NCR-…`, 버튼 사라짐. 세션 DB의 그 NCR은 minor·품목·실행이 있고 본문이 `Work instruction "Baking rolls" v1, step 1: Preheat the oven. Recorded …`. 테스트 끝에 그 실행의 열린 NCR을 취소
- 단계 값 한계(2026-10-03, V47): `WorkInstructionIntegrationTest.aValueOutsideItsStepsLimitsIsRecordedAndMarked` — 값 기록 안 하는 단계에 한계 400, 아래 > 위 400, 200–230 단계 추가(응답 한계) → 배포 → 새 revision이 한계 복사 → 실행에서 `hot` 400(`Step 1 records Oven °C as a number, such as 200.`) → 250 확인은 기록되고 `outOfLimits: true`·완료 → 취소 후 215는 `outOfLimits: false` → 마감 200. 같은 변경에서 `WorkInstructionService`가 `ItemRepository` 대신 `CatalogQuery`를 써서 ADR-002 동결 위반이 96 → 95건. V47은 세션 DB에서 트랜잭션 안에 먼저 돌려 본 뒤 적용. `workInstructionModel.test.ts`(`stepPayload` 한계, `limitText`). 실 화면 `e2e/work-instructions.spec.ts`: 단계 추가에 Min 200·Max 230 → 목록 `records Oven °C (200–230)` → 실행에서 250 Done → `Oven °C 250 (outside 200–230) · demo-owner`

- `WorkInstructionIntegrationTest` 2건(실제 Postgres)
  - revision: 초안 → 두 번째 초안 409, ftp 링크 400, 제목·본문·https 링크 수정, 단계 없이 배포 400, 단계 3개(값 기록·선택 단계), 빈 단계 400, 둘째 단계 삭제 후 번호 1·2, 외부인 배포 403, 배포 → `releasedBy`, 배포본 수정 409, 새 revision v2(단계 3개 복사), 초안 있는데 또 revise 409, v2 배포 → 목록 v2 released·v1 retired, 외부인 목록 403, v3 초안 삭제 후 새 초안은 v4
  - 실행: 배포 후 실행 → v1, 필수 2·완료 0, 값 없이 확인 400(`Oven °C`), 220으로 확인(확인자), 재확인 409, 없는 단계 404, 둘째 확인 → 완료, 취소 → 미완료, 외부인 403. v2 배포 → 진행 중 실행은 v1 유지, 새 실행은 v2. 마감 → 닫힘·확인 409. 지침 없는 제품의 실행 → `instruction` 없음·완료
- 전체 백엔드 449건 통과
- `workInstructionModel.test.ts` 3건(revision 묶기·보여 줄 revision·상태 문구, 단계 요청·링크 검사, 진행 문구)
- 실 화면 `e2e/work-instructions.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가, BOM 없음): 새 제품 → Instructions 탭에서 지침 시작 → 값 기록 단계(`Oven °C`)·필수 단계·선택 단계 추가 → Release → API로 실행 시작 → 실행 상세 `0 of 2 required steps done`과 마감 옆 알림 → 250 입력 후 Done(한계 200–230 밖이라 `(outside 200–230)` 표시, 2026-10-03), 둘째 Done → `All 2 required steps done`, 알림 사라짐. 끝나면 실행 마감. 이 테스트가 단계 입력 중 폼이 지워지는 경합을 찾아냄(위 화면 설명대로 고침)

## 2026-10-09 구현: 파일 첨부 (2bz)

**Accepted 정책 구현.** [최종 결정](../status/DECISIONS-2026-10-05.md)의 DB 메타데이터 + 설정에 따른 Local/S3-compatible를 구현했다. **V59**는 기존 V1–V58을 변경하지 않고 추가했으며 전용 SQL BEGIN/ROLLBACK과 Testcontainers에서 검증한다. 개발 DB에는 적용하지 않는다.

| 규칙 | 동작·이유 | 위반 응답 |
|---|---|---|
| W7 | 읽기/다운로드는 Project read, 추가/삭제는 Project write. viewer는 다운로드만 | 403 |
| W8 | 새 파일·삭제는 draft에서만. released/retired는 불변. revision 복사는 새 attachmentId와 같은 저장 키를 참조 | 409 `… make a new revision.` |
| W9 | `PUT /work-instructions/{instructionId}/attachments/{attachmentId}` multipart `file`, 클라이언트 UUID 고정. 같은 작성자·파일명·MIME·크기·SHA-256의 재전송은 기존 결과를 돌려준다. 배포 뒤에도 이미 성공한 업로드는 복구 가능 | 다른 내용/삭제된 ID는 409, 잘못된 UUID·파일은 400 |
| W10 | 기본 10 MiB, 설정 가능. png/jpg/jpeg/gif/webp/pdf/txt/csv 확장자·MIME 대조, 이미지/PDF signature, 텍스트 UTF-8·제어 문자 검사. 원래 경로는 제거, 서버 임의 파일명 사용 | 400, 서블릿 한도 초과 413 |
| W11 | 다운로드는 인증 API에서 bytes로 반환, `attachment`, `nosniff`, `private, no-store`. DB 크기·해시와 실제 파일이 맞아야 한다. 저장 키/버킷/인증값은 응답에 넣지 않는다 | 없는 참조 404, 저장소 불가·손상 500 |
| W12 | draft에서 파일을 제거하면 참조만 soft delete. released/retired 및 복사본의 파일은 보존. DB 롤백은 그 업로드가 만든 새 객체만 정리 | 재삭제는 200; 새 객체 정리 실패는 서버 경고 |

- 조회: `GET /work-instructions/{instructionId}/attachments`; 다운로드: `GET …/{attachmentId}/download`; 삭제: `DELETE …/{attachmentId}`.
- 화면: Inventory → Instructions, 실행 상세의 고정 지침에도 읽기 전용 첨부 목록. 응답 유실/5xx는 같은 File·UUID로 수동 재시도, 변경 입력 잠금. 자동 업로드 재시도 없음. 새로고침 시 File 객체는 보존하지 않으므로 목록을 확인한다.
- Local 경로는 설정 root 안으로 제한하고 내부 symbolic link·외부 real path를 거절한다. 저장 root는 운영자만 쓰는 전용 디렉터리여야 한다. S3는 공식 AWS SDK 2.55.13의 실제 put/get/delete를 사용하며 프라이빗 버킷을 전제로 한다. 원격 버킷에는 검증 중 업로드하지 않았다.
- 환경: `STORAGE_TYPE=local|s3`, `STORAGE_UPLOAD_DIR`; S3는 `STORAGE_S3_BUCKET`, `STORAGE_S3_REGION`, 선택 `STORAGE_S3_ENDPOINT`, MinIO 등에는 `STORAGE_S3_PATH_STYLE=true`. 인증은 AWS SDK 기본 자격 증명 체인(환경변수/인스턴스 역할)을 쓴다. 저장소 전환은 파일 복사를 자동으로 하지 않으므로 기존 파일을 옮기고 metadata를 검증하는 운영 절차가 필요하다.
- `STORAGE_MAX_FILE_SIZE_BYTES` 기본 10485760, multipart 요청 전체 한도 `STORAGE_MAX_REQUEST_SIZE_BYTES` 기본 11534336. 파일 한도를 키우면 요청 한도도 충분히 키운다.
- 범위: 바이러스 검사·내용 전체의 파일 형식 유효성 검사는 구현하지 않았다. browser inline preview 없이 다운로드만 제공한다. 삭제된 draft의 미참조 blob 회수는 보존기간 결정 후 별도 관리 작업이며, 자동 삭제하지 않는다.

## 결정 메모: 이미지·파일 첨부 (Proposed, 2026-10-03)

> **대체됨(2026-10-10 표시):** 이 메모의 선택지는 [DECISIONS-2026-10-05](../status/DECISIONS-2026-10-05.md) §2 "첨부"로 확정됐고 위 2bz로 구현됐다. 아래는 검토 이력으로만 남긴다. 업로드 한도·형식 오류의 전역 처리기는 `global/exception/MultipartExceptionHandler`(2026-10-10 이동, 동작 같음)다.

> **결정이 아니다.** [WORKBOARD](../status/WORKBOARD.md) §4 "작업 지침 이미지·파일 첨부"를 고르기 위한 자료다. 고르기 전에는 구현하지 않는다.

**지금 코드 (2026-10-03 확인)**
- 지침은 링크(`document_url`, V35, http·https만, W5)만 가진다.
- `global/storage`는 일부러 잡아 둔 뼈대이고 아직 아무도 쓰지 않는다. 손대지 않고 그대로 둔다. 들어 있는 것:
  - `StorageService.store(file, directory)` 인터페이스
  - `LocalStorageService`: `app.storage` 업로드 폴더(기본 `./uploads`)에 저장
  - `S3StorageService`: 저장 키만 만듦
  - `StorageFilenamePolicy`: 경로 탈출·파일 이름 검사
  - `StorageProperties`: 기본 10 MB, 확장자 png·jpg·jpeg·gif·webp·pdf·txt·csv
- 내려받기·지우기 연산과 파일 메타데이터 표가 없다. 두 구현이 모두 `@Service`라 처음 쓸 때 어느 쪽을 쓸지 고르는 설정이 필요하다.

| 안 | 저장 위치 | 필요한 일 | 약점 |
|---|---|---|---|
| A | 서버 디스크(뼈대의 로컬 저장) + DB 메타 표(파일 키·원래 이름·크기·종류·올린 사람·시각·지침 revision) | 메타 표 마이그레이션, 올리기·내려받기 API(프로젝트 읽기 권한 검사 후 내려줌), 지침 화면 | 서버가 여럿이면 공유 디스크가 필요. 백업 대상이 늘어남 |
| B | S3 호환 객체 저장소 + 같은 메타 표 | A + 실제 업로드·서명 URL 내려받기, 운영 계정·버킷 | 운영 인프라 결정이 먼저 |
| C | DB에 바이트로(`bytea`) | 메타 표에 내용 열 | DB 크기·백업 시간이 파일과 함께 늘어남 |
| D | 지금처럼 링크만(사내 문서 서버·드라이브 링크) | 없음 | 권한·보존은 바깥 시스템에 맡김 |

**권장안: A로 시작하고 저장소는 설정으로 고른다.** 운영 배포 방식이 정해지면 같은 메타 표 위에서 B로 옮긴다.
- 지침 revision마다 첨부를 두고, 새 revision은 파일을 복사하지 않고 같은 키를 참조한다(배포본은 고칠 수 없으므로).
- 폐기된 revision의 파일은 남긴다. 실행이 그 revision을 따르기 때문이다(R1).
- 사용자가 정할 것: 저장 위치(개발은 A), 크기·형식 제한(뼈대 기본값을 쓸지), 내려받기 권한(프로젝트 읽기 권한이면 되는지), 보존 기간.
- 뼈대 파일은 정해진 뒤 설정 선택만 더하는 정도로 쓴다(지우거나 다시 만들지 않는다).

## 이후

- ~~워크플로 공정(노드)별 지침과 실행 단계 연결~~ → W16, R11(2026-10-17, ADR-011). 검사 기준은 아직 지침 단계를 거쳐서만 공정과 이어진다
- ~~필수 단계 미확인 시 마감 막기(설정으로)~~ → F1로 구현(V37). 검증: `WorkInstructionIntegrationTest`, `RunInstructionRevisionIntegrationTest`
- ~~값 범위(한계)~~ → W6·R7(2026-10-03, V47). ~~한계 밖 값에서 부적합 제안~~ → R8(2026-10-03). ~~[검사 기준](inspection-standard.md)과의 연결(같은 한계를 두 곳에 적지 않기)~~ → W13–W15(2026-10-11, V71). ~~기준을 따르는 단계의 값 기록을 그 실행의 검사 기록으로도 남기기~~ → R9(2026-10-11, V72). ~~한계 없는(사람이 판정하는) 기준 단계에서 합격·불합격을 함께 고르기~~ → R10(2026-10-11). ~~확인이 남긴 불합격 검사에서 NCR로 바로 이어지기~~ → R8 확장(2026-10-11). ~~불합격 검사에서 불량(`defect_log`) 기록으로 바로 이어지기~~ → 확인이 남긴 검사도 실행 상세 Quality의 검사 목록에 나오고, 거기의 기존 **Log defect**가 그 검사를 불량의 근거로 채운다(2026-10-11 확인, 새 코드 없음)
- 공정별 지침 첨부, 저장소 이관·보존기간에 따른 미참조 파일 회수
- ~~확인 취소 이력 남기기(지금은 확인 행을 지움)~~ → R6(2026-10-03, V46)
