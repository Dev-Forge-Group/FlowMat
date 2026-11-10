# 아키텍처

> **2026-10-05 사용자 최종 결정:** [결정 기록](../status/DECISIONS-2026-10-05.md)이 아래 예전 Proposed·결정 대기 표시보다 우선한다. ADR-001/004 Accepted, ADR-002/003 원칙 유지, ADR-005 후속 계측·검증 결정. 정책 승인과 현재 코드 구현을 구분한다.

> **현행 문서** · 최종 확인 2026-10-02 · 이 폴더의 문서는 모두 현행이다. 결정이 바뀌면 같은 변경에서 고친다.

FlowMat은 업종이 아니라 흐름을 모델링하고 실행한다. 업종 개념은 Core가 아니라 도메인 확장으로 둔다.

## 문서

| 문서 | 내용 | 언제 읽나 |
|---|---|---|
| [decision-handoff.md](decision-handoff.md) | 확정된 결정, 아직 정하면 안 되는 것, 다음 작업 순서와 진행 상황 | 아키텍처에 닿는 작업을 시작하기 전 |
| [adr/](adr/README.md) | ADR-001 Organization/Project 경계, ADR-002 모듈 의존 규칙, ADR-003 Resource·Port 계약과 실행 Core, ADR-004 Flow Run 실행 정책(Proposed) | 해당 결정의 근거·보류 항목·재검토 조건이 필요할 때 |
| [domain-map.md](domain-map.md) | bounded context별 책임·엔티티·공개 API, 기업 시스템 개념 대응, 의존 규칙 | 새 기능을 어디에 둘지 정할 때 |
| [extraction-candidates.md](extraction-candidates.md) | 초기화 전 프로젝트와 editor·ERP·MES·compute·AI·CRM·배포 분리 후보의 현재 소유 경로·착수 조건 | 신규 저장소·추출 작업을 시작하기 전 |
| [execution-model.md](execution-model.md) | 정의 → 발행 revision → 실행, 상태, Flow Run, 시뮬레이션, 재고 부수효과 | 실행·계획 기능을 만들거나 바꿀 때 |

## 우선순위

충돌하면 다음 순서를 따른다.

1. 실제 DB migration과 코드
2. `docs/domain/`의 계약·설계 문서
3. `docs/status/CURRENT_CAPABILITIES.md`
4. 이 폴더(그 안에서는 ADR과 `decision-handoff.md`가 우선)
5. `docs/reference/`(동결된 근거·조사)
6. `docs/archive/`(레거시)

## 근거와 이전 판

- 결정의 논의 원문(2026-10, 동결): [reference/architecture/](../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md)
- 외부 시스템 참고 목록: [reference/benchmarks/enterprise-system-references.md](../reference/benchmarks/enterprise-system-references.md)
- 2026-09 판 도메인 지도·실행 모델·로드맵(레거시): [archive/2026-09-architecture/](../archive/2026-09-architecture/domain-roadmap.md)
