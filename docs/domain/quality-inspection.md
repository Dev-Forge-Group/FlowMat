# 품질 검사와 불량 기록

상태: **구현(2026-09-24).** 출처는 [벤치마크](../reference/benchmarks/FlowMat_GitHub_Benchmark_2026-09-24.md) FM-MFG-004(Quality/Issue)다. 코드는 가져오지 않았다. V1에 미리 만들어 둔 `quality_inspection`·`defect_log` 테이블을 그대로 쓴다. **새 마이그레이션 없음.**

## 목적

- 실행에서 쓰거나 만든 LOT, 또는 LOT 없는 품목을 **검사한 결과를 남긴다.**
- 불합격이면 **그 LOT를 같은 요청 안에서 격리**할 수 있다.
- 발견한 **불량을 기록하고, 조치 내용과 함께 한 번 종결**한다.

두 기록 모두 재고를 직접 바꾸지 않는다. 재고가 움직이는 경우는 불합격 검사가 LOT를 격리할 때 하나뿐이다. 이때도 일반 격리와 같은 재고 명령 경로(`InventoryCommandService`)를 탄다.

## API

| 요청 | 권한 | 내용 |
|---|---|---|
| `GET /quality-inspections?projectId=&productionRunId=&lotId=&itemId=` | 읽기 | 최신순. 거르기는 모두 선택이고 함께 쓸 수 있음 |
| `POST /quality-inspections` | 쓰기(editor 이상) | 검사 기록. 선택적으로 LOT 격리 |
| `GET /defects?projectId=&productionRunId=&lotId=&itemId=&openOnly=` | 읽기 | 최신순, `openOnly=true`면 미종결만 |
| `POST /defects` | 쓰기 | 불량 기록 |
| `POST /defects/{id}/resolve` | 쓰기 | `actionTaken` 필수, 한 번만 |
| `GET /quality/summary?projectId=&from=&to=` | 읽기 | 요약(아래). `from`·`to`는 ISO 일시, 둘 다 선택 |

검사와 불량에는 **수정·삭제 API가 없다.** 잘못 기록한 검사는 새 검사로 이어 적는다. 목록에는 둘 다 남고, 최신 것이 위에 나온다.

## 결정

| # | 결정 | 이유 |
|---|---|---|
| Q1 | 대상은 LOT, 실행, 품목 중 하나 이상. LOT를 주면 품목은 LOT의 품목으로 정해짐 | LOT 관리 품목과 아닌 품목을 모두 다룸 |
| Q2 | 실행과 LOT를 같이 주면, 그 LOT가 **이 실행에서 쓰였거나 만들어진 것**이어야 함(취소된 기록은 제외). 실행과 품목만 주면, 품목이 실행 대상 품목이거나 기록된 품목이어야 함 | 엉뚱한 실행에 결과가 붙는 것을 막음 |
| Q3 | 측정값과 한계(하한·상한 중 하나 이상)가 있으면 **서버가 합격/불합격을 계산**(경계값 포함 합격). 결과를 같이 보내면 계산과 같아야 함. 측정이 없으면 결과(`pass`/`fail`) 필수 | 판정과 수치가 어긋나는 기록을 막음 |
| Q4 | `quarantineLot=true`는 **불합격이고 LOT가 있을 때만** 허용. LOT 전체를 격리(계약서 §6)하고, 재고 이력의 참조는 `quality_inspection` / 검사 ID | 격리 이유를 재고 이력에서 추적 |
| Q5 | 이미 격리된 LOT는 그대로 둠(오류 아님). 종료된 LOT, 재고 행이 없는 LOT는 409 | 요청한 격리를 할 수 없으면 검사 기록도 남기지 않음(한 트랜잭션) |
| Q6 | 합격 검사가 격리를 **자동 해제하지 않음.** 해제는 Stock 화면의 격리 해제(`unquarantine`) | 해제는 따로 판단할 일 |
| Q7 | 불량 수량은 품목 단위, 0보다 커야 함. 심각도는 `minor`(기본)·`major`·`critical` | |
| Q8 | 검사에서 불량을 기록하면 실행·LOT·품목을 검사에서 물려받음. 다른 값을 보내면 400 | |
| Q9 | 불량 **기록**은 재고와 실행 수량(`good_qty`, `defect_qty`)을 바꾸지 않음. 끝난 실행의 수량 변경은 보정 전표로 함. 폐기는 아래 "폐기 처리"처럼 **종결할 때 선택**해서 함께 함 | 재고 변경 경로를 하나로 유지 |
| Q10 | 종결은 행 잠금(`PESSIMISTIC_WRITE`) 후 확인. 동시에 두 번 종결하면 하나만 성공, 나머지는 409 | |
| Q11 | 공정(`process_id`)은 비워 둠 | 공정 단계 실행(`flow_run_step`)이 들어오면 연결 |

## 폐기 처리 (불량 종결 때)

`POST /defects/{id}/resolve`에 `scrapInventoryId`, `scrapQuantity`를 **함께** 주면, 종결과 같은 트랜잭션에서 그 재고를 폐기한다.
- 폐기는 **감소 조정(`adjustment`)** 거래로 남는다. 참조는 `defect_log` / 불량 ID이고, 메모는 `Scrapped: 불량 유형`이다.
  - 출고(`issue`)가 아니라 조정인 이유: 불량품은 보통 격리돼 있고, 격리 재고는 출고가 막혀 있다. 조정은 격리와 상관없이 된다.
- 재고 행은 불량의 품목이어야 하고, 불량에 LOT가 있으면 그 LOT여야 한다. 아니면 400이다. 둘 중 하나만 주어도 400이다.
- 보유량이나 예약을 넘으면 기존 재고 규칙대로 409이고, 종결도 되지 않는다.
- 조치 내용 끝에 `(scrapped 3 from WH-A)`가 붙는다.
- 화면: 불량의 **Resolve**를 누르면 조치 입력란이 열린다.
  - 그 품목·LOT의 재고 행을 고르면 "Scrap from stock"으로 수량과 함께 폐기한다.
  - 버튼은 **Resolve and scrap**으로 바뀐다.

## 요약 (Quality 탭)

`GET /quality/summary`는 기간 안의 검사와 불량을 센다. 읽기 전용이고 새 테이블이나 마이그레이션은 없다.
- **기간:** 검사는 검사 시각, 불량은 기록 시각으로 넣는다. `from`은 포함하고 `to`는 뺀다.
- **숫자:** 검사 수, 합격, 불합격, 합격률(합격 ÷ 검사, 소수 4자리, 검사가 없으면 `null`), 미종결 불량, 종결된 불량.
- **불량 유형별(`defectsByType`):** 기록 수와 미종결 수, 많은 순. 수량은 더하지 않는다. 같은 유형이라도 품목마다 단위(kg, ea …)가 달라 합에 뜻이 없다.
- **항목별 불합격(`failuresByCheck`):** 한 번 이상 불합격한 검사 항목만, 불합격 많은 순. 그 항목의 전체 검사 수도 같이 준다.
- **품목별(`byItem`, 2026-09-26):** 기간 안에 불합격 검사나 불량이 하나라도 있는 품목만 나온다.
  - 검사 수·불합격 수·불량 수·미종결 불량 수와 **불량 수량 합(`defectQuantity`)**. 불량 수량은 품목 단위로 기록되므로 한 품목 안에서는 더할 수 있다.
  - 불량 많은 순, 같으면 불합격 많은 순. 품목 없이 기록된 검사·불량은 뺀다.
- 유형과 항목 이름은 **대소문자와 앞뒤 공백을 무시**하고 묶는다. 표시 이름은 가장 최근 기록의 표기다.
- 프로젝트의 기록을 불러와 메모리에서 센다. 기록이 많아지면 DB 집계(`GROUP BY`)와 `(project_id, inspected_at)`·`(project_id, logged_at)` 인덱스로 바꾼다. 인덱스는 새 마이그레이션이 필요하다.

화면: 재고 → **Quality** 탭
- 기간: 최근 7일, 30일(기본), 90일, 전체. 오늘을 포함해 로컬 자정부터 센다.
- 숫자 다섯 개(Inspections, Pass rate, Failed, Defects logged, Still open)와 두 표(Failures by check, Defects by type).
- 그 아래 **By item** 표: 품목, `불합격 of 검사`, 불량, 미종결(주황), 불량 수량과 단위. 문제가 있는 품목이 없으면 숨긴다.
- 아래 **Open defects**는 기간과 관계없이 미종결 불량 전체다. 여기서 바로 종결(폐기 포함)할 수 있고, 종결하면 요약도 다시 불러온다.
- **Record inspection / Log defect**는 실행과 관계없는 기록이다(입고 검사 등).
  - 대상은 종료되지 않은 LOT 전체, 그다음 LOT 관리를 하지 않는 품목이다. LOT 관리 품목은 LOT로 검사한다.
  - 실행의 검사·불량은 여전히 실행 상세에서 기록한다.
  - 저장하면 요약과 목록이 바로 새로 고쳐진다. `qualityOverviewModel.test.ts`의 `qualityTargets`.
  - 실 화면: LOT 관리 안 하는 품목에 측정 9·상한 8 → 서버 판정 불합격, 요약 검사 6 → 7.
- **Inspections CSV / Defects CSV:** 고른 기간의 검사·불량을 받는다. 누를 때만 전체 목록을 불러온다. 기간 비교는 시각을 실제 순간으로 바꿔서 한다. 서버 시각의 오프셋이 달라도 맞는다.
  - 검사 열: `inspected_at,inspection_type,result,measured,min,max,unit,item_code,lot,run,inspected_by,note`
  - 불량 열: `logged_at,defect_type,severity,quantity,unit,item_code,lot,run,status,action_taken,logged_by,resolved_by,resolved_at`(status는 open·resolved)
  - 파일 이름은 `inspections-<기간 시작>-to-<오늘>.csv`다. 따옴표 규칙과 BOM은 다른 내보내기와 같다. `qualityExportModel.test.ts` 3건. 실 화면에서 요약(검사 6, 불량 3)과 같은 줄 수를 확인했다.

## DB

V1의 두 테이블에는 **FK와 CHECK가 없다.** 프로젝트·실행·LOT·품목의 존재와 소속, 결과값과 심각도 값은 서비스에서 검사한다.

제약을 DB에 넣으려면 새 마이그레이션이 필요해서 이번에는 넣지 않았다. 다른 세션의 번호 사용(V23 이후)이 정리되면 다음 후보로 올린다.
- FK: `project_id`, `production_run_id`, `lot_id`, `item_id`, `defect_log.inspection_id`
- CHECK: `result_status IN ('pending','pass','fail')`, `severity`, `resolved_yn`, `quantity > 0`
- 인덱스: `defect_log(production_run_id)`, `defect_log(lot_id)`

`result_status`의 기본값 `'pending'`은 나중에 "계획된 검사"용으로 남겨 두었다. 지금은 쓰지 않는다.

## 화면

실행 상세 → 오른쪽 **Quality** 영역. 실행 상태와 관계없이 보인다.

- **검사 목록:** 결과(✓ Pass / ✕ Fail), 항목, 측정값과 한계, 대상, 기록자·시각. LOT가 지금 격리 중이면 "LOT quarantined"가 붙는다.
- **Record inspection:** 대상은 이 실행의 LOT·품목(산출 먼저)에서 고른다.
  - 측정값과 한계를 넣으면 결과를 미리 보여 주고, 결과 선택칸은 숨긴다.
  - 격리 체크는 불합격일 때만 켤 수 있다.
- **불합격 검사의 Log defect:** 그 검사에서 대상을 물려받은 불량 기록 양식을 연다.
- **Defects 목록:** 항목마다 Resolve 버튼이 있고, 누르면 조치 내용을 묻는다. NCR에 모인 불량은 번호가 보인다. 아직 NCR이 없는 미종결 불량은 골라서 새 NCR을 바로 발행할 수 있다([부적합](nonconformity.md) N11, 2026-10-03).

재고 → LOTs 탭 → LOT 선택 → **Quality**
- 그 LOT의 검사·불량 전체 이력이다. 어느 실행에서 기록했는지는 `run …`으로 보인다.
- 여기서 기록하는 검사와 불량은 실행 없이 LOT만 대상으로 한다(입고 검사 등).

- **저장이 느릴 때(2026-11-14):** 검사를 저장하는 동안 양식을 고치면, 저장이 끝나도 양식이 닫히지 않는다. 고친 값이 다음 검사로 남고 `Saved. What you typed while it saved is kept here as the next inspection.`이라고 알린다. 보낸 그대로였으면 전처럼 닫힌다(`InspectionForm`의 `onDone`).

목록과 양식은 `entities/quality/ui`(`QualitySection`, `QualityRecords`)에 있어 두 화면이 같이 쓴다. 판정과 요청 조립 규칙은 `entities/quality/model/qualityModel.ts`에 있다.

## 검증

- `QualityIntegrationTest` 6건(실제 Postgres, `ddl-auto=validate`). 결과·대상 테스트에서 `itemId` 거르기(그 품목 검사 1건, 불량 0건)도 확인
- 품목 상세의 Quality 줄(Items 탭 Details): 그 품목의 검사 수·불합격 수·최근 검사, 불량 수·미종결 수
  - 요약: 기간 안 검사 4(대소문자만 다른 항목 2건은 한 항목), 합격률 0.5, 불량 유형 묶기와 미종결 수, 품목별 한 줄(검사 4·불합격 2·불량 3·미종결 2·수량 2+1+3=6), 빈 기간은 합격률 `null`·품목별 없음, 외부인 403
  - 실 화면(2026-09-26): Quality 탭 30일에 By item 표 15줄(예 `QO-… · overview bread | 1 of 2 | 1 | 0 | 3 ea`), 콘솔 오류 0
  - 폐기 종결: 격리된 LOT 재고 4에서 3 폐기 → 1, 격리 유지, 이력 `adjustment −3`/`defect_log`. 다른 품목 행 400, 한쪽만 400, 초과 409.
  - 불합격 측정 → LOT 격리 → 재고 이력에 검사 참조 → 다른 실행에 투입 409 → 합격 재검사를 기록해도 격리 유지
  - 결과·대상 규칙: 결과 없음, 측정과 다른 결과, 하한 > 상한, 잘못된 결과값, 합격인데 격리, LOT 없이 격리, 다른 품목의 LOT, 실행과 무관한 LOT, 대상 없음 → 400. 상한만으로 판정, LOT 없는 품목, 실행의 투입 LOT
  - 불량: 검사에서 물려받기, 다른 LOT 지정 400, 심각도·수량·품목 누락 400, 미종결 목록, 빈 조치 400, 종결, 두 번째 종결 409, 재고 불변
  - 권한: 외부인 403, viewer 읽기만, editor 기록·종결
- e2e `inventory-reports.spec.ts`(REAL_API_E2E): API로 자료를 만든 뒤 Quality 탭(불합격 항목, 불량 종결), Analysis 탭(30 days), Movements → Stock on a date(15 kg), Stock 탭 재발주(30 days, 40 kg)를 실제 화면으로 확인
- `qualityModel.test.ts` 5건, `qualityOverviewModel.test.ts` 3건(기간 시작, 합격률 표시)
- e2e `production-forms-pending.spec.ts`(모의 API): 첫 검사가 저장되는 동안 고친 검사(`Weight`, 불합격)가 남고, 두 번째로 저장하면 양식이 닫힌다
- 실 화면(2026-09-24): 수분 14.2%(10–12) 불합격 + 격리 → 해당 검사에서 불량 2 kg 기록 → 종결. 산출 LOT 재고 4, 격리 상태 유지, 콘솔 오류 0. 이어서 LOTs 탭에서 같은 LOT의 이력(실행 번호 포함)을 확인하고, 실행 없는 외관 검사를 추가
- 실 화면(2026-09-25, Quality 탭): 무게 검사 불합격 1·합격 1, 불량 1 → 숫자 `2 / 50.0% / 1 / 1 / 1`, 두 표에 한 줄씩. Open defects에서 종결하자 목록에서 빠지고 유형 표의 Open이 0이 됨. 콘솔 오류 0

## 이후

- ~~검사 기준(품목별 항목과 한계) 마스터~~ → [검사 기준](inspection-standard.md)으로 구현(2026-09-27, V29). 공정별 기준은 남음
- ~~시정 조치(CAPA)~~ → [부적합과 시정 조치](nonconformity.md)로 구현(2026-09-27, V30). 재작업의 재고 처리(폐기는 종결 때 선택으로 구현됨)는 남음
