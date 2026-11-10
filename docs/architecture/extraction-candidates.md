# FlowMat 분리 후보와 초기화 전 프로젝트

기준일: 2026-11-10 KST. 현재 개발 우선순위는 FlowMat이다. 이 목록은 Git 저장소가 있는 폴더뿐 아니라 실제 소스가 있는 하위 프로젝트와 아직 추출하지 않은 후보도 추적한다. 후보 이름은 현재 원격 저장소의 존재나 구현 완료를 뜻하지 않는다.

현재 정본은 `flowmat_frontend`와 `flowmat_backend`다. 백엔드의 15개 Gradle 모듈은 이미 같은 제품 안에서 빌드되며, 각 폴더에 `.git`이 없는 것이 초기화 누락은 아니다. 루트의 과거 Gradle skeleton과 `legacy`도 새 활성 프로젝트로 취급하지 않는다. 경계는 [도메인 지도](domain-map.md)의 공개 API·의존 순서와 기존 경계 테스트를 따른다.

| 후보 | 현재 기능·소스 소유 경로 | 다음 작업과 별도 초기화 조건 |
| --- | --- | --- |
| ERP | backend `catalog`, `bom`, `inventory`, `purchasing` 모듈과 대응 frontend entities/pages | **우선:** 승인→부분 입고→putaway→LOT·원장·반품 대사를 기존 API로 연결하고 실패 복구를 검증한다. 별도 저장소는 독립 변경·릴리스 수요와 소비 계약을 확인한 뒤 판단한다. |
| MES | backend `production`, `quality`, `workforce`, `workflow`, `flowrun` 및 기존 재고 공개 계약 | 구매·창고 여정 다음에 생산·품질·LOT까지 한 여정을 확장한다. ERP/MES 양쪽에 품목·재고·BOM 쓰기 책임을 복사하지 않는다. |
| flowmat-editor | `flowmat_frontend/src/lib/flowmat-editor`의 core와 React 연결부, 제품의 저장·권한 adapter | 공개 export·host 계약, 실제 독립 consumer, pack/consumer fixture, 직렬화 호환·단일 React·되돌리기를 확인한다. 다른 작업 공간에서 이미 추출한 정본이 있으면 먼저 비교한다. |
| flowmat-compute | 기존 production·flowrun·workflow·rule 계산과 실행 계약 | 대표 입력의 병목을 측정하고 Java/TypeScript 개선과 비교한다. 개정·단위·정밀도·권한·취소·비물리 실행 계약과 단일 원장 writer를 고정한 뒤 별도 언어/실행 단위를 선택한다. |
| flowmat-ai | 이 checkout에서 독립 FlowMat AI 프로젝트는 확인되지 않음 | 실제 의사결정 한 가지와 평가 사례·데이터 audience·비용·fallback·운영 owner를 먼저 정한다. UniPlace AI 사본을 FlowMat 정본으로 복제하지 않는다. |
| flowmat-crm | 독립 CRM 프로젝트는 확인되지 않음. 공급처·구매는 현 purchasing 책임 | 고객·영업·견적·상담의 실제 요구와 기존 기능의 부족을 확인한다. 기존 공급처 기능을 이름만 바꿔 별도 제품으로 만들지 않는다. |
| devforge-deploy | 현 `.github/workflows`, backend `compose.yaml`과 제품별 배포 정의 | 기존 릴리스의 source·artifact·DB·설정 호환과 격리 환경 적용·복구를 먼저 연결한다. 여러 저장소 산출물을 조합해야 할 때 활성 원본과 불변 버전 관리 책임을 확정한다. |

새 폴더나 다른 작업 공간이 추가되면 `.git`뿐 아니라 manifest·실제 소스·생성물·submodule·외부 template 여부를 함께 확인한다. 기존 원격/다른 대화의 정본, local-only 변경, 현재 소비자와 소유 경로를 대조한 뒤 초기화·이동한다. Git 초기화, npm 패키지 추출, 독립 프로세스, DB 분리, 배포 조합은 각각 별도로 결정한다. 설정 YAML은 구현된 선택지를 연결하며 새 업무 로직·권한·트랜잭션을 대신하지 않는다.

현재 클라우드에서 Windows의 `E:\projects\git\apgs`와 `benchmark/optimized_setup` 소스에는 접근하지 못했다. 이를 현재 구현·벤치마크·이식 완료로 기록하지 않는다. 다른 작업 공간의 `DevForge-migration` 결과도 이 checkout에 반영됐다고 가정하지 않으며, 필요할 때 정확한 정본과 계약을 비교한다.

우선 기존 구매·창고 업무 하나를 완주하고, 후속 생산·품질 및 복원 검증으로 이어간다. 여러 후보의 저장소·언어·DB를 동시에 새로 만드는 일정으로 해석하지 않는다.
