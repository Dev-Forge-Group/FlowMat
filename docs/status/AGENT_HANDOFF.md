# Agent 인계 (세션 1 구역)

> **2026-10-09 최신 상태:** 사용자 `수정 허가`로 project 시간대/settings/공개 Query, production 마감/workflow 공개 Query 및 첨부 storage/config/build.gradle 범위를 진행했다. 2bx–2bz 완료. 최종 격리 백엔드 **1,193 실패·오류·건너뜀 0**, 라인 **82.13%**·분기 **68.81%**·커버리지 기준/bootJar. 프런트 **590**·타입·lint·빌드, 전체 기본 모의 E2E **116 통과/16 의도적 제외**. 첨부 transport 라인 100%·분기 90%, 서버 첨부 서비스 라인 98.25%, 내용 검증 96%, S3 구현/설정 100%. Stage B 동결 80→54(새 위반 0), 원본/격리 src 불일치 0. 최초 전체 1,184 중 7 실패는 fixture 1·새 의존성 mock 6으로 수정했고, 후속 25 중 2 실패는 누락 업로드 500·시간대 동시 덮어쓰기의 실제 버그로 수정했다. V1–V58 수정/repair 없음, 새 V59는 전용 SQL rollback·Testcontainers에만 적용. 이 세션은 개발 DB의 BOM·기존 서버·브랜치/커밋/푸시를 건드리지 않았다. 새 번호 V60(직전 최고 재확인). D2 품목 삭제 한 건은 미응답으로 유지한다.

## 2026-10-09 인계

- 산출물: `C:/Users/Public/Documents/ESTsoft/CreatorTemp/flowmat-cost-20261005/final-calendar-attachment-validation-20261009.json`, `final-calendar-attachment-20261009.log`, `frontend-all-20261009.log`, `frontend-lint-20261009.log`, `frontend-build-20261009.log`, `attachment-frontend-coverage-20261009.log`, `all-mock-calendar-attachment-20261009.log`. 브라우저 결과는 `all-mock-calendar-attachment-results/`.
- 새 V59 SQL은 최소 parent table을 둔 포트 없는 전용 PostgreSQL에서 BEGIN/ROLLBACK(생성 1→롤백 0)으로 확인했고 전체 실제 스키마는 Testcontainers로 검증했다. V58/V59를 기존 개발 DB에 적용하지 않았다.
- 검증용 4189 서버는 PID·시작 시각·스크립트와 확인한 자식 4개만 종료했다. 포트 없는 flowmat-attachment-rehearsal-20261009도 ID·작업 label·빈 host binding 대조 후 제거했다. 사용자 서버/컨테이너는 건드리지 않았다.
- 소스는 unstaged. 수정한 계약·상태 문서만 staging. UTF-8 BOM 0, diff 공백 오류 0. 사용자 HEAD 9162c6d는 바꾸지 않았다.
- 다음 승인 구현: 유효일 BOM은 `BomRevisionQuery.findEffective(projectId,itemId,on)` 기반은 있으나 아직 WorkOrderService에서 plannedStartAt의 프로젝트 날짜로 선택하지 않는다. 다중 approved·기간 overlap 거절·자동 retire 제거 및 phantom line 전개가 남아 있다. 이 부분은 지금 완료로 표시하지 않는다. 수정 전 status·최대 migration 번호 확인을 계속 지킨다.
- 지침 첨부는 서버 확인을 잃은 경우 동일 File/UUID로 재시도한다. 새로고침은 File 객체를 저장하지 않으므로 파일 목록으로 확인한다. 외부 버킷 검증/운영 파일 이관·보존기간·미참조 회수·바이러스 검사·실사용/부하 검증은 별도다. 기본 모의 browser 16개 제외는 실 API/수동 조건 스펙이며, 실제 API BOM 생성 스펙은 실행하지 않는다.

> **2026-10-08 최신 상태:** 사용자 HEAD 0a38981 원격 backend/frontend/browser/security CI 성공. 2bq/2br 인계 보완·2bs 설비 원가/추정·2bt 다차원 setup 속성·2bu 계획 갱신·2bv 저장 규칙 미리보기·2bw revision 새로고침 복구 완료. 최종 전체 격리 백엔드 1,165 실패·오류·건너뜀 0·라인 81.89%·분기 68.70%·기준/빌드, 프런트 572·타입·기존 JS/JSX lint·빌드. 전체 기본 모의 E2E 110 통과/16 의도적 제외. V56/V57은 SQL BEGIN/ROLLBACK·Testcontainers에만 적용. 개발 DB BOM·기존 V1–V55·기존 서버·브랜치/커밋/푸시 변경 없음. 다음 V58(직전 최고 재확인). 재개 시 존재한 EquipmentSequence·테스트·equipment-changeover 문서·package-lock 변경 보존. project 시간대/workflow actualEndAt/D2 및 첨부 공용 storage/config/build.gradle 범위 회신 대기. 검증용 4189 프런트와 포트 없는 flowmat-setup-rehearsal-20261008 컨테이너는 소유 확인 후 종료·제거했다. 사용자 서버는 건드리지 않았다.

## 2026-10-08 검증 산출물과 다음 범위

- 산출물: `C:/Users/Public/Documents/ESTsoft/CreatorTemp/flowmat-cost-20261005/`의 `with-preview-validation-20261008.json`, `with-preview-jacoco-20261008.xml`, `full-with-preview-validation-20261008.log`, `frontend-revision-reload-tests-20261008.log`, `frontend-revision-reload-build-20261008.log`, `all-mock-revision-reload-20261008.log`. 브라우저 trace·스크린샷은 `all-mock-revision-reload-20261008/`.
- 원격 CI 성공은 기존 HEAD 0a38981에 대한 결과다. 이번 커밋 전 구현은 로컬 격리 전체 검증을 통과했으며 원격에는 보내지 않았다.
- 다음 구현: 프로젝트 시간대 설정/공개 Query, workflow 실제 마감 시각, 지침 첨부 Local-S3. 정책과 공유 영역 수정은 사용자 허가 후 2bx–2bz에서 구현했다. D2 품목 삭제 집계는 확인 전 15/15로 표시하지 않는다. 새 일을 임의로 추가하거나 미응답을 승인으로 간주하지 않는다.
- 코드는 스테이징하지 않는다. 이 작업의 도메인 계약·상태 문서만 스테이징한다. 재개 시 존재한 `docs/domain/equipment-changeover.md`는 다른 작업자의 변경이므로 제외한다.

> **2026-10-06 부산물 가치 후속:** 2bp 구현·검증 완료. 전체 격리 백엔드 1,128 실패·오류·건너뜀 0·라인 81.60%·분기 68.09%·기준/빌드, 프런트 476·타입·기존 lint·빌드, 전체 기본 모의 브라우저 99 통과/16 제외 및 조회 복구 추가 뒤 관련 5 통과. 재료비 차감 없이 고정 BOM 부산물 실제량 × D+ 가격을 별도 표시, 폐기 비용/배출 모델은 미구현. 다음은 2bq 전환 규칙 삭제·수정 동시성 재현. BOM revision 생성 requestId는 아직 없으며 응답 유실 방어 후속 필요. 프로젝트 시간대/마감 공개 API 허가 답변 대기 유지. 개발 DB·기존 서버·V1–V55 변경 없음.

> **2026-10-06 13:52 최신 상태:** 사용자 커밋 d22d37f의 backend/frontend/security CI 성공, browser #44는 BOM period Revision locator 7건만 실패(90 통과·16 제외). combobox 이름으로 수정, 기간/미리보기·여러 draft 모의 브라우저 9건 통과. 2bn 기간 명령·명시 날짜 Query 기반과 2bo 여러 draft/단일 pending·승인 그래프/CSV 동시성 방어 구현. 전체 격리 백엔드 1,119건 실패·오류·건너뜀 0·라인 81.53%·분기 67.92%·커버리지 기준/빌드 통과. 프런트 476건·타입·기존 lint·빌드, 전체 기본 모의 브라우저 98 통과/16 의도적 제외; 미리보기 재조회 보완 후 관련 9 재통과. Stage B 자동 동결 82→80. V55까지 기존 파일(수정 금지), 다음 V56 직전 재확인. V52–V55 개발 DB 미적용. 기간별 다중 승인·계획일 revision 선택·팬텀은 아직 미완료. 프로젝트 시간대 수정 범위와 workflow actualEndAt 공개 API 허가 답변 대기. 이어서 승인된 별도 부산물 가치 표시를 구현 중. 커밋·푸시·개발 DB BOM 삽입 없음.

> **2026-10-05 23:42 최신 상태:** 2bk 서버 실사 계획·2bl 예약 유지 이동·2bm 자동 예약 우선 피킹 완료. 전체 백엔드 1,099건 실패/오류/건너뜀 0·라인 81.39%·분기 67.74%·기준/빌드, 프런트 473·타입·기존 lint·빌드, 관련 모의 API 브라우저 18 통과. V52–V54는 격리 테스트에만 적용, 개발 DB 미적용. 다음 새 번호 V55(직전 재확인). Stage B 82. 원격 main 769c5c4의 최근 backend/browser/frontend/security CI 네 개는 읽기 전용 확인에서 모두 성공. 기존 aa5c294 Codecov TLS 업로드 실패에 대한 로컬 수정은 미커밋. 다음 유효일 BOM 구현. 프로젝트 시간대 담당 범위 및 actualEndAt workflow 공개 API 질문은 사용자 답변 대기.

> **2026-10-05 CI·D+ 1단계:** Codecov TLS만 원격 CI 실패, 필수 테스트/커버리지/빌드는 성공. backend workflow에 v5·업로드만 실패 허용·보고서 artifact·경고 추가. 과거 단가 공개 Query 구현, 단위 15·격리 통합 3 포함 전체 백엔드 1,041건/실패·오류·건너뜀 0·라인 81.07%·분기 67.31%·기준 통과. 새 Query 라인 100%/분기 95%. 이어진 2bi는 승인 일정 변경으로 완료. actualEndAt·실행 원가 연결의 workflow 공개 API 수정 범위는 확인 중. 기존 서버·DB·BOM 변경 없음.

> **2026-10-05 최종 사용자 결정 수령:** [결정 기록](DECISIONS-2026-10-05.md)이 아래 예전 결정 대기·상태 고정 지침보다 우선한다. 승인 범위는 구현 가능하며 순서는 D+ 원가 → 프로젝트 시간대 → 승인 일정 → 예약 피킹 → 실사 계획 → 유효일/팬텀 BOM → setup → 첨부 → Data Flow E2E. 소유 구역·DB·서버·커밋 금지는 유지한다. 품목 삭제 방어 집계 한 건은 별도 사용자 확인 중이다.

> **현행 문서** · 작성 2026-10-03 · 세션 1 Agent가 쉬는 동안(약 5일) 이 구역을 이어받는 Agent가 처음 읽는 문서. 무엇을 하고 무엇을 하지 않는지, 어떻게 검증하고 언제 멈추는지를 적는다. 일의 목록은 [WORKBOARD](WORKBOARD.md), 기능 상태는 [CURRENT_CAPABILITIES](CURRENT_CAPABILITIES.md), 아키텍처 결정은 [결정 인계](../architecture/decision-handoff.md)가 기준이다.

> **2026-10-04 상태:** 인계 Agent가 2am–2ao를 끝낸 뒤 사용자가 멈추고, 세션 1이 다시 이어받았다(같은 구역에서 두 Agent가 동시에 고치지 않기 위해). 다시 인계할 때는 이 문서를 그대로 쓰되 WORKBOARD §1의 마지막 기록과 §2의 마지막 번호부터 확인한다.

> **2026-10-05 추가 검증:** 2aq(열린 피킹의 실제 준비 수량)을 구현했습니다. 전체 백엔드 1,016건·커버리지 기준, 프런트 417건·타입·린트·빌드, 브라우저 34건(BOM 생성 스펙 제외) 통과. 기존 서버를 재시작하지 않고 C: 격리 복사본과 별도 포트·임시 DB를 사용했으며, 검증용 서버·컨테이너는 종료했습니다. 재개 시 WORKBOARD §1의 최신 환경·결과와 §3·§4 결정 대기를 확인합니다.

> **2026-10-05 스캐너 추가 작업:** 2ar–2as 완료. 백엔드 전체 1,023건·커버리지 81.02%, 프런트 421건·타입·린트·빌드, 관련 가짜 API 브라우저 7건 통과. 최신 스캐너 작업 표시, 키보드 포커스·입력 잠금, 실제 완료 응답 안내, 선택 필드 `expectedToLocation`의 서버 잠금 안 대조와 거절 뒤 복구를 구현했습니다. 기존 완료 요청은 호환됩니다. 다음 번호는 2at입니다. 별도 검증 서버는 종료했습니다.

> **2026-10-05 실사 추가 작업:** 2at 완료. 첫 입력·CSV 불러오기 장부 수량을 재조회 뒤에도 유지하고 사라진 입력 행은 전체 요청 거절, Clear counts 후 재실사 가능. 프런트 전체 427건·타입·린트·빌드, 관련 가짜 API 브라우저 3건과 모델 커버리지 라인 100%·분기 97.77% 통과. 서버·마이그레이션 변경 없음. 다음 번호는 2au입니다.

> **2026-10-05 재고 재시도 추가 작업:** 2au 완료. 입고·이동 등 History 명령의 응답 유실 재시도 키 유지와 저장 중 입력 잠금. 프런트 전체 433건·타입·린트·빌드, 가짜 API E2E 2건, 공통 명령 6건·커버리지 100% 통과. 서버·DB·마이그레이션 변경 없음. 다음 번호는 2av이며 FEFO 출고·예약에서 같은 격차를 확인 중입니다.

> **2026-10-05 FEFO 재시도 추가 작업:** 2av 완료. 출고·예약의 응답 유실 재시도와 입력 잠금, 프로젝트별 키 분리. 프런트 전체 437건·타입·린트·빌드, 재고·FEFO 가짜 API E2E 4건, 공통 명령·FEFO mutation 10건·대상 커버리지 100% 통과. 자동 승인 검토 사용량 한도로 보류된 브라우저는 제한 시간 후 승인 경로로 재실행했습니다. 백엔드는 변경·재실행하지 않았으며 마지막 전체 결과는 2as의 1,023건·81.02%입니다. 다음 번호는 2aw입니다. PID·시작 시각·스크립트로 확인한 4186 검증 서버와 자기 esbuild 자식만 종료했습니다.

> **2026-10-05 실사 재시도 추가 작업:** 2aw 완료. 실사 응답 유실 복구, 행 순서 변경 대응, 중첩 수량 구분. 프런트 443건·타입·린트·빌드, 실사 브라우저 5건, 명령·FEFO·실사 단위 16건·커버리지 기준 통과. 서버의 거래 없는 실사 재생 한계는 stock-count C13에 기록. 다음 번호 2ax, 만료 폐기 화면 결과 유지·복구를 확인 중입니다. 4187 검증 프런트만 실행 중입니다.

> **2026-10-05 폐기·게이트웨이 추가 작업:** 2ax–2ay 완료. 폐기 결과 유지와 원래 LOT 집합 복구, HTTP 500 이상 재고·FEFO·실사 미확인 안내. 프런트 전체 446건·타입·린트·빌드, 가짜 API 19건, 명령/mutation 19건·80% 커버리지 기준 통과(라인 94.73%, 분기 84.61%). 서버 변경 없음. 다음 번호 2az, 실사 중 행 삭제 뒤 재시도 확인 중. 4187은 이 Agent 전용 가짜 API 검증 프런트입니다.

> **2026-10-05 실사·포장 입력 추가 작업:** 2az–2bb 완료. 실사 삭제 행 원래 요청 복구, 시트 중복·읽기 취소·오류 안내, 포장 입력 비우기의 옛 환산량 제거. 전체 프런트 449건·타입·린트·빌드, 실사 가짜 API 12건과 포장·재시도 7건 통과. 서버 변경 없음. 다음 번호 2bc, 일괄 입고 파일 선택·검사 응답 순서 점검. 4187 검증 프런트는 이 Agent 소유로 유지 중입니다.

> **2026-10-05 CSV 입고 추가 작업:** 2bc–2bd 완료. 파일/검사 경합·취소·파일 오류 복구, 실제 입고 중 입력 잠금과 저장 미확인 재제출 차단. 프런트 전체 449건·타입·린트·빌드, 가짜 API 8건 통과. 서버 CSV 입고 requestId 재생은 없으며 S10에 한계를 기록. 다음 번호 2be(중복 CSV 열 점검). 4187은 이 Agent 전용 검증 프런트입니다.

> **2026-10-05 중복 CSV 열 추가 작업:** 2be 완료. 실사·품목·입고에서 같은 필드 열 중복·별칭 충돌 거절. 프런트 전체 457건·타입·린트·빌드, 관련 가짜 API 10건·모델 21건·라인 100%·분기 80.80%·기준 통과. 서버 변경 없음. 다음 번호 2bf, 품목 CSV 파일/검사 경합 점검. 4187 유지 중.

> **2026-10-05 품목 CSV 추가 작업:** 2bf 완료. 파일/검사 경합·취소·읽기 실패·저장 중 잠금 보완. 프런트 전체 457건·타입·린트·빌드, 가짜 API 6건 통과. 다음 번호 2bg, BOM 자재 CSV의 같은 경합과 replace 옵션 점검(모든 API 모킹, 실제 BOM 삽입 없음). 서버 변경 없음. 4187 유지 중.

> **2026-10-05 BOM 자재 CSV 추가 작업:** 2bg 완료. 파일/검사/교체 옵션 경합, 파일 읽기 중 현재 옵션 반영, 검사 취소·실제 저장 입력 잠금·중복 열 거절. 전체 프런트 460건·타입·린트·빌드, 가짜 API 8건·모델 14건·라인 100%·분기 93.47%·기준 통과. 실제 BOM 생성·DB 요청 없음. 다음 번호 2bh. 이번에 확인한 CSV 격차는 구현했고 §3·§4 결정 대기는 유지합니다. 4187 전용 검증 프런트는 저장된 PID 26208·UTC 시작 시각·recovery-frontend.mjs 실행 명령을 모두 대조한 뒤 자기 esbuild 자식과 함께 종료했습니다. 기존 8080·5173·컨테이너는 건드리지 않았습니다. 수정한 코드·테스트·문서 40개 파일의 UTF-8 BOM 0, 문서 9개의 깨진 상대 링크 0, diff 공백 오류 0. 코드·문서는 이 Agent가 새로 스테이징하지 않았습니다.

> **2026-10-05 승인 일정 변경:** 2bi 구현. V52는 격리 psql BEGIN/ROLLBACK 뒤 추가했으며 개발 DB에는 미적용. owner 명령·이력·상태/시작일/기존 날짜 검증·멱등 재송신과 Schedule 화면, 통합 23·브라우저 4 통과. 전체 백엔드 1,064건 실패 0·라인 81.15%·분기 67.40%·커버리지 기준·빌드, 프런트 464건·타입·기존 lint·빌드 통과. 2bj 원가 조회·표시는 아래와 같이 완료했으며 마감 시각 기록의 workflow 공개 API 범위는 사용자 답변 대기. 다음 새 마이그레이션 번호는 V53(직전 재확인). 기존 서버·DB·BOM은 변경하지 않음.

> **2026-10-05 원가 조회·표시:** 2bj 구현. V1 actual_end_at mapping·과거 단가 Query 연결·추정 API/화면. 마감 시각 저장은 아직 미완료(사용자에게 workflow 공개 API 범위 질문 유지). 전체 백엔드 1,070건 실패 0·라인 81.18%·분기 67.52%·기준·빌드, 프런트 469·타입·기존 JS/JSX lint·빌드, 원가 모의 API 3 통과. Stage B 동결은 자동 84→82. 다음 2bk 실사 계획 구현 중이며 후보 V53 SQL의 격리 BEGIN/ROLLBACK 검증은 통과. V53 파일 추가 완료, 개발 DB에는 미적용. 다음 번호는 V54(추가 직전 재확인).

## 1. 맡는 구역

- 백엔드 `domain/inventory`, `bom`, `catalog`, `production`, `quality`와 그 테스트
- 프런트 `pages/inventory`, `pages/runs`의 작업지시·실행 상세·부하표 쪽, `entities/inventory`·`catalog`·`production`·`quality`
- 문서 `docs/domain`(공정 포트·Flow Run 문서 제외), `docs/status`

다른 구역(Flow Run `domain/flowrun`·`RunsRoute`·`FlowRunsPanel`, 인증 `entities/auth`, 공정 포트·연결 `domain/workflow`·`pages/workspace`)의 파일은 고치지 않는다. 공용 파일(`shared/types/api.ts`, `CURRENT_CAPABILITIES.md`)은 덧붙이기만 한다.

## 2. 지금 상태 (2026-10-03 밤)

- 커밋 전 변경: [WORKBOARD](WORKBOARD.md) §1. 마이그레이션 V42–V51과 §2의 2a–2al(설비 상태 이력, 부족 예상, 지침 한계 밖 NCR 제안, 끝난 실행 부산물 차이, 위치 간 이동 분석, 모든 단계 반제품 초안, 스캐너 화면, 위치 바코드 라벨, 전환 순서 제안, 좁은 화면 넘침, 재고 있는 위치의 코드 변경 등). 코드는 스테이징하지 않았고 docs는 스테이징돼 있다.
- 마지막 전체 검증: 백엔드 994건과 커버리지 기준(2aj까지), 프런트 409건·린트·타입 검사·빌드와 브라우저 E2E 34건(2ag까지). 2ah 이후 변경(2al의 백엔드 포함)은 관련 단위·통합 테스트와 E2E만 다시 돌렸다. 다음 전체 회귀에서 함께 확인한다.
- (2026-10-18 정정) 세션 DB(`flowmat-session1-db`, 5434)에는 V1–V87이 적용돼 있다(2026-10-17). V88–V94는 사용자가 커밋했고(2026-10-18 `9500678`) 복제본 리허설을 통과했지만 5434에는 아직 올리지 않았다(사용자 결정 대기). V95–V101은 커밋 전이다. **적용된 마이그레이션은 주석·공백까지 고치지 않는다.** 다음 번호는 V102.
- (2026-10-18 정정) ADR-002 동결 목록은 0줄이 되어 지웠다([ADR-012](../architecture/adr/ADR-012-workflow-access-boundary.md)). 이제 `ModuleBoundaryTest`는 동결 없이 모든 cross-domain repository 사용에서 실패한다.
- **알려진 문제:** 실 API 스펙 `e2e/bom-lot-flow.spec.ts`는 화면으로 BOM을 만들어 승인한다. 그래서 전체 E2E를 돌릴 때마다 세션 DB에 BOM이 쌓였다("BOM 삽입 금지"에 어긋남, 2026-10-03 기준 BOM 41개 중 최근 하루 약 10개). 실행·작업지시가 참조해 지우지 않았다. 정리 여부는 사용자가 정한다. 로컬에서는 이 스펙을 빼고 돌린다(§5).

## 3. 할 일 (순서대로)

1. **CI 확인.** 사용자가 커밋·푸시했다면 GitHub Actions(백엔드, 프런트, 브라우저 E2E, 보안 검사) 결과를 보고 실패를 고친다. 로그는 GitHub 인증이 필요할 수 있다. 공개 API로는 job 단계·annotation까지만 보인다.
2. **하루 한 번 전체 회귀**(§5). 깨지면 원인을 고치고, 고친 내용을 그 기능의 도메인 문서와 WORKBOARD에 남긴다.
3. **결정 메모.** 2026-10-03–04에 WORKBOARD §3의 두 도메인 결정과 §4의 막힌 후보 대부분(할당 재고 피킹, 블라인드 실사, 첨부, 시간대, setup 묶음·부산물 가치·유효일 BOM)에 Proposed 메모를 각 도메인 문서에 써 두었다(WORKBOARD 근거 칸에서 링크). 남은 것은 공정별 지침·검사 기준(flow-run 구역과 함께)과 D2 대기 항목이다. 사용자가 결정하면 그 메모를 근거로 구현 계획을 세운다. 메모의 상태를 올리거나 정리하려고 승인으로 바꾸지 않는다.
4. **결정이 필요 없는 작은 개선만** 구현한다. 예: 재고 화면의 좁은 화면 점검, 접근성, 콘솔 오류, 문서와 코드가 어긋난 곳. 기능마다 단위 테스트, 관련 E2E, 도메인 문서·CURRENT_CAPABILITIES·WORKBOARD 갱신까지 한 묶음.

## 4. 지킬 것

- 브랜치 생성·전환, 커밋, 푸시를 하지 않는다. 변경은 작업 트리에만 남긴다.
- 적용된 Flyway 마이그레이션을 고치지 않는다. 새 마이그레이션은 V58부터(추가 직전 최고 버전 재확인), psql `BEGIN; … ROLLBACK;`으로 먼저 돌려 본 뒤 만든다. devtools가 컴파일 때 새 마이그레이션을 바로 적용하므로 완성 전 파일을 두지 않는다.
- dev DB(세션 DB 포함)에 BOM을 넣지 않는다. BOM 화면은 가짜 API 스펙(`e2e/support/mockApi.ts`)으로 확인한다.
- 다른 세션의 dev 서버·컨테이너를 끄거나 재설정하지 않는다. 끌 때는 포트와 시작 시각으로 자기 프로세스인지 확인한다.
- 토큰·쿠키 값을 문서나 보고에 쓰지 않는다. `.gitignore`는 바꾸지 않는다.
- ADR-002 Stage B(다른 context 저장소를 공개 API로)는 **고치는 파일에서만** 한다. 기존 참조를 한꺼번에 고치지 않는다. 공개 API의 `Map` 응답은 빈 입력에 `Collections.emptyMap()`(호출자가 null id를 찾으므로 `Map.of()` 금지).
- 일부러 잡아 둔 뼈대(`global/storage`, 빈 스텁, 쓰지 않는 루트)는 지우거나 지우자고 하지 않는다.
- 사용자 승인 없이 결정 상태를 올리지 않는다. 2026-10-05 최종 결정에 따라 ADR-001/004 Accepted, ADR-002/003 기존 원칙 유지·registry Experimental, ADR-005 Accepted. D2는 품목 삭제 한 건 확인 전 14/15로 유지한다. 구현 완료로 표기하지 않는다.

## 5. 검증 방법

- **서버:** 백엔드는 `flowmat_backend`에서 `bootRun --args='--spring.profiles.active=dev'`. 환경 변수는 `DB_URL=jdbc:postgresql://localhost:5434/flowmat`, `DB_USERNAME=flowmat`, `DB_PASSWORD`(`docker inspect flowmat-session1-db`의 `POSTGRES_PASSWORD`), 임의의 `JWT_SECRET`, `AUTH_LOGIN_ACCOUNT_LIMIT=100`, `AUTH_LOGIN_IP_LIMIT=200`. 프런트는 `flowmat_frontend`에서 `npm run dev`(5173, `/api`를 8080으로 넘김). 백그라운드 작업은 시간 제한(최대 2시간)이 지나면 꺼지고 백엔드 java 프로세스만 8080에 남는다. 그 PID의 시작 시각을 확인해 끈 뒤 다시 띄운다.
- **드라이브 루트 접근(2026-10-04 재부팅 뒤):** 이 계정이 E 드라이브 루트(`E:/`)를 읽지 못한다(`E:/projects` 아래는 괜찮음). E: 경로에서 띄우면 Vite가 `EPERM lstat` 오류로 멈추고 gradlew `compileJava`가 "cannot find symbol" 100건으로 실패한다. 권한은 사용자가 고칠 일이고, 그 전에는 `subst X: E:/projects/git`(PowerShell에서는 백슬래시 경로)로 잡은 X:에서 `X:/FlowMat/flowmat_backend`·`flowmat_frontend`를 띄운다(Bash 경로 `/x/FlowMat`, 해제는 `subst X: /d`). 재부팅 뒤에는 `docker start flowmat-session1-db flowmat-uicheck-redis`도 필요하다.
- **준비 확인:** 로그인으로 확인하지 않는다(계정당 10분 8회 제한). `curl http://localhost:8080/api/projects`가 401이면 백엔드, `curl http://localhost:5173/`가 200이면 프런트가 떠 있다.
- **백엔드 변경 뒤:** `gradlew test`가 클래스를 지웠다 다시 만들어 devtools 재시작이 반쯤 될 수 있다. `touch build/classes/java/main/org/myweb/flowmat/Application.class` 후 로그에 새 `Started Application`이 찍히면 브라우저 확인을 한다.
- **전체 회귀:**
  - 백엔드 `./gradlew test jacocoTestCoverageVerification`
  - 프런트 `npx vitest run`, `npm run lint`, `npm run typecheck`, `npm run build`
  - E2E `REAL_API_E2E=1 BASE_URL=http://localhost:5173 npx playwright test --grep-invert "BOM approval"`(33건, `bom-lot-flow` 제외)
- **링크:** 문서를 고친 뒤 상대 링크가 깨지지 않았는지 확인하고 `git add -u docs`(새 md는 `git add -f`).

## 6. 기록과 멈춤

- 하루를 끝낼 때 [WORKBOARD](WORKBOARD.md) §1에 한 줄: 한 일, 검증 결과(건수), 남은 문제.
- 기능을 끝내면 WORKBOARD §2에 다음 번호(2bh부터)로 행을 더하고, CURRENT_CAPABILITIES와 도메인 문서를 같은 변경에서 고친다.
- **멈추는 조건:** 결정 없이 할 일이 남지 않으면 새 일을 만들어 내지 않는다. WORKBOARD §1에 "결정 대기" 목록(무엇을, 왜, 근거 문서)을 정리하고 멈춘다. 결정이 필요한 일을 추측으로 구현하지 않는다.

## 7. 사용자가 하는 일

- 커밋·푸시와 CI 확인(Agent는 하지 않는다). 쉬기 전에 지금 트리를 커밋해 두면 커밋 전 변경이 더 쌓이지 않는다.
- WORKBOARD §3의 결정, 세션 DB에 쌓인 BOM의 정리 여부.
