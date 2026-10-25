# 부적합과 시정 조치 (NCR / CAPA)

상태: **구현(2026-09-27).** 출처: [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-MFG-004의 `nonconformity`/`corrective_action`, [품질 검사](quality-inspection.md) "이후"의 시정 조치. 코드는 가져오지 않았습니다. **새 마이그레이션 V30** (`nonconformity`, `nonconformity_defect`, `corrective_action`). 2026-10-03에 **V48**(조치 효과 확인, N12), 2026-10-11에 **V70**(효과 확인 예정일, N13, 개발 DB 미적용)이 더해졌습니다.

## 왜 필요한가

불량 기록(`defect_log`)은 "무엇이 얼마나 잘못됐는가"와 그 건의 조치(종결·폐기)만 담습니다. 같은 원인의 불량이 여러 번 나오면:
- 원인을 어디에도 적지 않았고
- 제품을 어떻게 처리할지(그대로 사용·재작업·폐기·반품) 결정이 남지 않았고
- 재발 방지 조치를 누가 언제까지 하는지 추적할 수 없었습니다.

부적합 보고서(NCR)는 관련 불량을 모아 원인과 처리 결정을 기록하고, 조치가 끝나야 닫힙니다. **기록만 합니다**: 재고 폐기는 지금처럼 불량 종결 때 합니다.

## 모델

| 표 | 핵심 열 |
|---|---|
| `nonconformity` | `ncr_no`(프로젝트별 `NCR-0001`부터, 다시 쓰지 않음), `title`(200), `description`, `severity`(minor·major·critical), `status`(open·closed·cancelled), 대상 `item_id`·`lot_id`·`production_run_id`, `root_cause`, `disposition`(pending·use_as_is·rework·scrap·return_to_supplier), 발행·종결 사람과 시각, `closure_note`, 효과 확인(V48) `verification_result`(effective·not_effective)·`verification_note`(1000)·`verified_by`·`verified_at`. CHECK: 넷 다 비었거나, 결과가 두 값 중 하나이고 확인한 사람·시각이 있으며 status = closed. 효과 확인 예정일(V70) `verification_due_date`: 닫힌 NCR만 가짐(CHECK) |
| `nonconformity_defect` | NCR과 불량의 연결. **불량 하나는 NCR 하나에만**(유일 제약) |
| `corrective_action` | `action_no`(NCR 안에서 1부터), `action_type`(correction 이번 건 수정, corrective 원인 제거, preventive 다른 곳 재발 방지), `description`, `owner_id`, `due_date`, `status`(open·done·cancelled), `result_note`, 완료·취소 사람과 시각 |

DB 제약: 상태·종류·처리 값 CHECK, `(status = 'open') = (closed_at IS NULL)`, 조치도 같은 방식, `(project_id, ncr_no)`·`(nonconformity_id, action_no)` 유일.

## 규칙

| # | 규칙 | 위반 응답 |
|---|---|---|
| N1 | 발행 때 불량을 모을 수 있음. 불량은 같은 프로젝트여야 하고, 이미 다른 NCR에 있으면 거절 | 400 / 409 `The … defect is already on NCR-0003.` |
| N2 | 대상(품목·LOT·실행)을 주지 않으면 첫 불량에서 가져옴. LOT와 품목을 같이 주면 LOT가 그 품목의 것이어야 함 | 400 |
| N3 | 심각도를 주지 않으면 모은 불량 중 가장 높은 것(불량이 없으면 minor) | |
| N4 | 번호는 프로젝트별 advisory lock 아래에서 매김(동시에 두 건 발행해도 번호가 겹치지 않음) | |
| N5 | 열린 NCR만 수정·불량 추가·조치 추가·조치 완료/취소 가능 | 409 `NCR-0001 is closed.` |
| N6 | 조치 담당자는 프로젝트 소유자나 활성 구성원 | 400 `… is not a member of this project.` |
| N7 | 조치 완료에는 결과, 취소에는 이유가 필요. 이미 끝난 조치는 다시 끝낼 수 없음 | 400 / 409 |
| N8 | **닫기 조건:** 원인 기록, 처리 결정(pending 아님), 열린 조치 없음, 완료된 조치 1개 이상. 빠진 것을 한 번에 모두 알려 줌 | 409 `Before closing NCR-0001, record the root cause, decide the disposition, complete at least one action.` |
| N9 | 취소에는 이유가 필요. 열린 조치는 함께 취소되고 **모은 불량의 연결이 풀려** 다른 NCR에 다시 쓸 수 있음 | 400 |
| N10 | 조치 `overdue`: 열려 있고 기한이 서버 날짜보다 이전 | |
| N11 | 불량이 어느 NCR에 있는지는 열린·닫힌 NCR만 알려 줌(취소된 NCR은 N9로 연결이 풀림). 실행·LOT 불량 목록은 이것으로 번호를 보여 주고, 아직 어느 NCR에도 없는 미종결 불량만 새 NCR에 모을 수 있게 함(2026-10-03) | |
| N12 | **효과 확인**(2026-10-03, V48): 닫힌 NCR에 조치가 효과가 있었는지 한 번 기록(`effective`·`not_effective`, 대소문자 무시). 효과가 없으면 무엇이 아직 잘못되는지 메모가 필요. 결과와 상관없이 NCR은 닫힌 채로 남고, 효과가 없으면 화면이 같은 대상·심각도의 후속 NCR 발행을 채워 줌(자동으로 만들지 않음). 닫기 조건(N8)은 그대로이고 담당자 알림은 없음 | 409 `NCR-0001 is open; only a closed nonconformity's actions can be checked.` / 409 `… was already checked by kim.` / 400 `result must be effective or not_effective.` / 400 `Say what still goes wrong when the actions did not work.` |
| N13 | **효과 확인 예정일**(2026-10-11, V70): 닫을 때 `verificationDueDate`(선택)로 조치 효과를 언제까지 확인할지 정함. 프로젝트 오늘(업무 시간대)보다 이전이면 거절. 닫힌 뒤 확인을 기록하기 전까지 `PUT …/verification-due`로 옮기거나 지움(null). `verificationOverdue` = 닫힘·확인 전·예정일이 프로젝트 오늘보다 이전(N10과 같은 기준). 확인을 기록해도 예정일은 남고 overdue만 끝남. 알림은 없음 | 400 `The check date 2000-01-01 is before today (2026-10-11).` / 409 `NCR-0001 is open; only a closed nonconformity's actions can be checked.` / 409 `… was already checked by kim.` / 400 날짜·JSON 형식 `The request body is not valid JSON, or a date is not YYYY-MM-DD.` |

담당자 확인은 저장소(구성원·소유자)로 직접 합니다. 트랜잭션 프록시를 지난 예외를 잡으면 트랜잭션이 rollback-only로 표시되기 때문입니다(2026-09-27 CI 실패 원인과 같은 패턴).

## API

| 요청 | 권한 | 설명 |
|---|---|---|
| `GET /nonconformities?projectId=&status=` | 읽기 | 최신순. 불량·조치 포함, `openActions`, `overdueActions` |
| `GET /nonconformities/{id}` | 읽기 | |
| `POST /nonconformities` | 쓰기 | `{projectId, title, description?, severity?, itemId?, lotId?, productionRunId?, defectLogIds?}` |
| `PUT /nonconformities/{id}` | 쓰기 | `title`, `description`, `severity`, `rootCause`, `disposition` 중 준 것만. 빈 설명·원인은 지움 |
| `POST /nonconformities/{id}/defects` | 쓰기 | `{defectLogIds}` 추가로 모음 |
| `POST /nonconformities/{id}/actions` | 쓰기 | `{actionType, description, ownerId?, dueDate?}` |
| `POST /nonconformities/{id}/actions/{actionId}/complete` · `/cancel` | 쓰기 | `{note}` |
| `POST /nonconformities/{id}/close` · `/cancel` | 쓰기 | `{note}`(닫기는 선택, 취소는 필수). 닫기는 `verificationDueDate`(선택, N13)도 받음 |
| `POST /nonconformities/{id}/verify` | 쓰기 | `{result, note?}` 효과 확인(N12). 응답 NCR에 `verificationResult`·`verificationNote`·`verifiedBy`·`verifiedAt` |
| `PUT /nonconformities/{id}/verification-due` | 쓰기 | `{verificationDueDate}` 효과 확인 예정일 옮기기·지우기(null), 닫혔고 확인 전만(N13). 응답 NCR에 `verificationDueDate`·`verificationOverdue` |
| `GET /nonconformities/defect-links?projectId=` | 읽기 | 위 N11. `[{defectLogId, nonconformityId, ncrNo, status}]`(불량 ID순). 계산은 quality 저장소만 쓰는 `NonconformityDefectLinkService`(다른 도메인 저장소를 쓰는 기존 서비스는 건드리지 않음, ADR-002 Stage B) |

모든 변경은 NCR 전체를 돌려줍니다.

## 화면

재고 → **Quality** 탭 → **Nonconformities**
- 상태 거르기(Open 기본, All, Closed, Cancelled), **Raise nonconformity**
- 발행 폼: 제목, 심각도(비우면 불량에서), 설명, 미종결 불량 체크 목록
- 목록 표: 번호, 제목, 심각도, 대상(`품목 · LOT · run`), 조치 진행(`1 of 2 done`, 기한 지남은 빨강), 상태, Open/Hide
- 상세: 모은 불량, 원인·처리 편집과 저장, 조치 표(종류, 내용·결과, 담당, 기한, 상태, Done/Cancel → 결과·이유 입력), 조치 추가 폼, **닫기 전 남은 일** 목록(서버와 같은 순서, `nonconformityModel.closeBlockers`), 메모와 Close/Cancel 버튼. 남은 일이 있으면 Close 버튼은 비활성
- 효과 확인(2026-10-03): 목록 상태 칸에 닫힌 NCR이 아직 확인 전이면 `closed · not checked`, 효과가 없었으면 빨간 `· not effective`. 닫힌 NCR 상세에 **Check the actions** 폼(`Did the actions work?` Yes/No, **What was checked**, **Record check**). 기록 뒤에는 `The actions worked`(초록) 또는 `The actions did not work`(빨강)와 확인한 사람·시각·메모, 효과가 없으면 **Raise a follow-up** → 발행 폼을 `Follow-up to NCR-0001: 제목`, `The actions of NCR-0001 did not work. 메모`, 같은 심각도, 같은 품목·LOT·실행으로 채우고 폼 위에 `Follow-up to NCR-0001, about 품목 · LOT …`
- 효과 확인 예정일(2026-10-11, N13): 닫기 메모 아래 **Check the actions by**(화면 제안값: 프로젝트 오늘 + 30일 `CHECK_AFTER_DAYS`, 바꾸거나 비울 수 있음. 비우면 예정일 없음, 오늘 이전은 보내기 전에 `The check date is before today.`). 목록 상태 칸: 확인 전이고 예정일이 있으면 `closed · check by 2026-11-10`, 지났으면 빨간 `closed · check overdue (due 2026-11-10)`, 예정일이 없으면 그대로 `closed · not checked`. 확인 전 닫힌 NCR 상세의 **Check date** 폼: `Check by …` / 빨간 `The check was due …` / `No check date`, **New check date**와 **Save check date**(비우면 **Clear check date**)

실행 상세 → **Quality**, 재고 → LOTs → LOT → **Quality**의 **Defects** 목록(2026-10-03)
- NCR에 모인 불량은 번호(`NCR-0003`, 닫혔으면 `NCR-0003 · closed`)를 보여 줌
- 미종결이고 NCR에 없는 불량에는 **For a new NCR** 체크. 하나 이상 고르면 **Raise NCR from defects** 폼: 제목(기본: 목록 순서로 첫 불량 종류, `and N more`, `on 품목코드`. 목록은 최신순), **Raise NCR from N defects**, **Clear**. 심각도는 불량 중 가장 높은 것(N3), 대상도 첫 불량에서(N2)
- 발행하면 `Raised NCR-0005 from 2 defects.`, 두 불량에 번호가 붙고 체크가 사라짐. 원인·처리·조치는 Quality 탭에서
- 폼의 **Add to**(체크한 뒤에만 열린 NCR 목록을 불러옴): `New NCR`(기본) 또는 열린 NCR(`NCR-0003 · 제목`, 60자 넘으면 자름). 열린 NCR을 고르면 제목 칸이 사라지고 **Add N defects to NCR-0003** → `POST /nonconformities/{id}/defects`(N1·N5) → `Added 1 defect to NCR-0003.` 고른 NCR이 그사이 닫히면 새 NCR로 돌아감

## 검증

- `NonconformityIntegrationTest` 2건
  - 흐름: 불량 2건(minor·major)으로 발행 → `NCR-####`, 심각도 major, 품목은 불량에서, pending. 같은 불량으로 다시 발행 409. 바로 닫기 409(원인·처리·완료 조치). 외부인 담당자 400, 없는 종류 400. 기한 2000-01-01 조치 → `overdue`. 결과 없이 완료 400, 완료. 끝난 조치 취소 409. 예방 조치 추가, 원인·처리 저장, 잘못된 처리 400, 닫기 409(열린 조치 1), 예방 조치 취소, 닫기 성공, 닫힌 뒤 수정·조치 추가 409, closed 거르기
  - 취소: critical 불량으로 발행(심각도 critical), 이유 없이 취소 400, 취소 → 조치 취소·불량 연결 해제 → 같은 불량으로 새 NCR 가능, 외부인 목록·상세 403
- 전체 백엔드 434건 중 새 실패 없음(실패 1건은 다른 작업자의 진행 중인 포트 테스트)
- `nonconformityModel.test.ts` 4건(닫기 조건, 진행 표시, 대상 표시, 요청 본문)
- 불량 목록에서 발행(2026-10-03): `NonconformityDefectLinkIntegrationTest` — 불량 3건 중 2건으로 발행 → 두 건에 번호·`open`·NCR ID, 남은 한 건은 없음 → NCR 취소 → 두 건 모두 빠짐. 외부인 403. `ncrFromDefectsModel.test.ts` 4건(번호 표시, 모을 수 있는 불량, 열린 NCR 선택지 이름, 기본 제목·200자). 실 화면 `e2e/ncr-from-defects.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): API로 LOT 관리 품목·LOT·불량 2건 → LOTs 탭 LOT 선택 → Quality의 두 불량 체크 → 제목 `Dent … and 1 more on 품목` → 발행 → `Raised NCR-#### from 2 defects.`, 두 불량에 같은 번호, 체크 0개 → API로 셋째 불량 → 다시 열어 체크, **Add to**에서 그 NCR → 제목 칸 없음 → `Added 1 defect to NCR-####.`, 셋째에도 같은 번호. 끝나면 NCR 취소
- 실 화면 `e2e/nonconformity.spec.ts`(REAL_API_E2E, CI browser-e2e에 추가): API로 품목과 major 불량 → Quality 탭에서 발행(불량 체크) → 상세에 major·불량·"Record the root cause." 표시, Close 비활성 → 조치 추가 → Done에 결과 입력 → done → 원인·처리(Rework) 저장 → 남은 일 사라짐 → 닫기 → Open 목록에서 빠지고 Closed 거르기에 `closed · not checked` → (2026-10-03) No만 고르고 Record check → `Say what still goes wrong.` → 메모 입력 후 기록 → `The actions did not work`·메모, 목록에 `not effective` → **Raise a follow-up** → 제목 `Follow-up to NCR-####: 제목`, `about 품목코드` → Cancel(만들지 않음)
- 효과 확인(2026-10-03, V48): `NonconformityIntegrationTest` 첫째 끝에 닫은 뒤 결과 없음 → `maybe` 400 → 메모 없는 not_effective 400 → `Not_Effective`·메모 200(status closed, 결과·메모·확인한 사람·시각) → 다시 409. 둘째 끝에 취소된 NCR 확인 409, 외부인 403. V48은 세션 DB에서 트랜잭션 안에 먼저 돌려 닫힌 NCR에 기록 가능, 반쪽 값·모르는 결과·열린 NCR에 결과는 CHECK로 거절됨을 보고 롤백한 뒤 적용. `nonconformityModel.test.ts` `verificationPayload`·`followUpForm`(제목 200자 자르기, 대상 포함한 발행 본문). 같은 변경에서 `NonconformityService`의 다른 도메인 저장소 4줄을 공개 API로 옮김(ADR-002 Stage B, 동결 95 → 91)

- 효과 확인 예정일(2026-10-11, V70): `NonconformityIntegrationTest` 셋째 — 열린 NCR의 예정일 변경 409, 날짜 형식 400, 과거 날짜로 닫기 400(열린 채 남음), 2999-12-31로 닫기(overdue false), 과거로 옮기기 400, null로 지우기, 외부인 403, 저장소로 2000-01-01을 넣으면 목록 `verificationOverdue` true, 확인 기록 뒤 날짜는 남고 overdue false, 확인 뒤 옮기기 409. NCR 두 클래스 5건·아키텍처·마이그레이션 검사 함께 11건 실패 0. `nonconformityModel.test.ts` 3건(날짜 더하기의 달·해·윤일 경계, 날짜 검사, 목록 문구). 모의 E2E `e2e/ncr-check-date.spec.ts`(제안값, 과거 날짜는 보내지 않음, 닫기 본문, 목록 `check by`·`check overdue`, 지난 예정일 옮기기 본문). 실 화면 `e2e/nonconformity.spec.ts`의 닫은 뒤 기대를 `closed · check by YYYY-MM-DD`로 바꿈(V70 적용 DB 필요, 로컬 미실행). V70은 개발 DB에 적용하지 않음

## 이후

- ~~불량 목록(실행·LOT 화면)에서 바로 NCR 발행, NCR 번호 표시~~ → 위 N11과 화면(2026-10-03)
- ~~불량 목록에서 이미 열린 NCR에 불량 더하기~~ → 위 화면의 **Add to**(2026-10-03)
- 작업 지침 값이 한계 밖이면 실행 상세에서 그 실행의 NCR 발행을 제안(2026-10-03, [작업 지침](work-instruction.md) R8, 불량 없이 실행·제품 대상)
- ~~조치 효과 확인(verification) 단계~~ → N12(2026-10-03, V48). ~~확인 예정일(닫은 뒤 N일)~~ → N13(2026-10-11, V70). ~~담당자 알림~~ → [알림](notification.md) NT2(2026-10-11, V74. 직접 맡긴 일이라 구독 설정 없이)
- 공급자 반품(`return_to_supplier`)을 구매·입고와 연결: 2026-10-24 처리에서 **Return to supplier**를 고르면 그 LOT를 들여온 발주의 공급처를 보여 준다([구매 발주](purchasing.md) PO13, 2fq). ~~공급처로 돌려보내는 재고 이동과 발주의 반품 기록은 아직~~ → 발주서의 **Return to supplier**([구매 발주](purchasing.md) PO26–PO28, 2fz, V111, 2026-10-24). ~~NCR 처리에서 바로 반품을 만들기~~ → 처리 화면의 `Send it back from PO-0001` 링크가 그 발주를 연다([구매 발주](purchasing.md) PO37, 2gf). ~~반품 기록에 NCR을 잇기는 남음~~ → 반품이 그 NCR을 적고, NCR 상세가 그 반품을 **Sent back**으로 보여 준다([구매 발주](purchasing.md) PO42–PO43, 2gv, V118, 2026-10-25)
