# ADR-001: Organization / Project 경계

작성일: 2026-10-02

## 상태

**Accepted (2026-10-05).** 사용자가 수용 기준 네 가지와 목록·탈퇴 정책을 확정했다. [최종 결정](../../status/DECISIONS-2026-10-05.md) §7. 조직 구현·마이그레이션 적용은 아직 별개다.

상태 이력:
- 2026-10-05 사용자 최종 결정: Accepted. 프로젝트 override 없음, 관리 metadata만 예외, 탈퇴 시 ProjectMember 비활성화.
- 2026-10-02 작성: Accepted로 적었다.
- 같은 날 의사결정자 검토: Proposed로 조정했다. 이유는 조직 역할 → 프로젝트 권한 상속, cross-project access, 조직 OWNER·ADMIN 권한이 아직 결정되지 않았기 때문이다.

근거: 사용자가 최신 결정 문서로 지정한 [원문](../../reference/architecture/FlowMat_Architecture_refactoring_Handoff.md) §8–17, 사용자가 직접 고친 경계 문장(결정 1), 2026-10-02 의사결정자 검토.

## 수용 기준 (2026-10-05 사용자 확정)

- [x] Organization membership ≠ Project membership 확정
- [x] Cross-project access deny-by-default 확정
- [x] 조직 OWNER·ADMIN의 프로젝트 override 여부 확정
- [x] 마이그레이션 전후 프로젝트 접근 호환성 불변식 확정

## 배경

- 지금 Project 하나가 협업 공간, 권한 경계, Tenant 비슷한 역할, 업무 데이터 소속을 모두 맡는다.
- 업무 데이터는 거의 모두 `project_id`로 격리된다(재고, 품목, BOM, 워크플로, 실행).
- 접근은 [`ProjectAccessService`](../../../flowmat_backend/modules/project/src/main/java/org/myweb/flowmat/domain/project/application/ProjectAccessService.java)가 판정한다.
  - `project.owner_id`가 사용자와 같으면 owner다.
  - 아니면 활성 `project_member`의 `project_role`(viewer·editor·owner)을 쓴다.
  - 쓰기는 editor 이상, 소유자 작업은 owner만 할 수 있다.
  - 예외는 시스템 권한 `PROJECT_VIEW_ALL`뿐이며, 모든 프로젝트 목록을 볼 수 있다.
- 회사·사업장·거래처 모델이 없다. ERP·CRM 통합에는 프로젝트보다 위의 소유 단위와 거래처가 필요하다.
- FlowMat은 제조 전용이 아니다. 따라서 Company → Factory → Project 같은 고정 계층은 맞지 않는다. 게임 개발사의 CI 파이프라인이나 연구실의 분석 흐름에는 Factory가 없다.

## 결정

1. **Organization은 상위 소유 및 계정 그룹 경계이고, Project는 현재의 데이터 격리 및 접근권한 집행 경계다. Organization membership은 별도 정책이 도입되기 전까지 Project 접근권한을 암묵적으로 부여하지 않는다.** (사용자 확정 문장)
2. Project는 없애지 않는다. 기존 `project_id`는 `organization_id`로 바꾸지 않으며, 데이터가 속한 조직은 `데이터 → Project → Organization`으로 찾는다.
3. **권한 확정안**(2026-10-05 사용자 승인). 조직 멤버십과 프로젝트 멤버십을 분리한다.

   ```text
   OrganizationMember ──X──> Project 접근 자동 부여 없음
   ProjectMember      ─────> Project 접근 권한의 현재 SSOT
   cross-project access = DENY BY DEFAULT
   ```

   | 질문 | 확정 정책 |
   |---|---|
   | 조직 OWNER·ADMIN의 프로젝트 데이터 override | 없음. ProjectMember가 아니면 업무 데이터 접근 불가 |
   | 조직 OWNER·ADMIN의 프로젝트 목록 | 이름·ID·상태·owner 관리 metadata만 가능 |
   | 조직 MEMBER의 조직 전체 프로젝트 목록 | 불가 |
   | 조직 탈퇴/제거 | 해당 조직의 ProjectMember 비활성화 |
   | 탈퇴자가 Project owner | 소유권 이전 전까지 탈퇴/제거 거절 |
   | 프로젝트 owner와 조직 owner가 다른 경우 | 허용 |

   조직 도입/backfill 전후 기존 프로젝트 내용 접근 판정은 동일해야 한다. 관리 metadata 목록은 업무 데이터 조회와 분리한다.

4. Organization은 법인일 필요가 없다. 개인 workspace, 회사, 연구실 모두 Organization이 될 수 있다.
5. Site(공장·창고·사무실·연구실 같은 물리적 장소)와 BusinessPartner(고객·공급사·물류·외주 거래처)는 Organization 아래의 별도 모델이다. Project와는 선택적으로만 연결한다(`project_site`, `project_partner`). Project 아래에 강제로 두지 않는다.
6. 도입 순서(Accepted 뒤)
   - **Phase 1**: `organization`·`organization_member` 테이블을 추가하고 `project.organization_id`를 NULL 허용으로 추가한다.
   - **Phase 2**: 기존 사용자마다 개인 조직을 만들고, 기존 프로젝트를 소유자(`project.owner_id`)의 개인 조직에 연결한다.
   - **Phase 3**: backfill 결과를 확인한 뒤 `project.organization_id` NOT NULL 전환을 검토한다.
   - **Phase 4**: Site·BusinessPartner와 연결 테이블은 실제 요구가 생길 때 만든다.
7. 마이그레이션은 작업 시점의 빈 번호를 쓴다(V42는 포트 `itemId` 선택화에 먼저 쓸 예정). 적용된 마이그레이션은 주석·공백까지 수정하지 않는다.

## 결정하지 않은 것

아래는 별도 ADR이나 사용자 결정 없이 정하지 않는다.

- 조직 역할의 프로젝트 접근 상속을 나중에 도입할지와 그 방식
- Tenant 격리 정책: 조직 간 격리 수준, 조직 단위 조회·보고, 시스템 관리자의 범위
- `project.organization_id` NOT NULL 전환 시점
- Site·BusinessPartner의 도입 시점과 필드
  - 첫 후보는 출고 거래처다. 출고 거래에 거래처가 없어 LOT 리콜이 출고처를 보여 주지 못한다.
- 프로젝트의 조직 간 이동, 조직 삭제, 조직 소유권 이전
- Phase 1 이후 새 프로젝트를 어느 조직에 만들지(기본값). Phase 1 구현 때 제안하고 사용자 확인을 받는다. 어떤 기본값이든 결정 3을 바꾸지 않는다.
- 세분화 권한(APPROVER·QUALITY_MANAGER 등)의 도입. 초기 viewer/editor/owner 역할은 [2026-10-05 결정](../../status/DECISIONS-2026-10-05.md) §7로 확정.

## 결과

- 좋은 점
  - ERP·CRM 데이터가 속할 상위 소유 단위가 생긴다.
  - 기존 데이터, API, 권한 판정은 바뀌지 않는다.
- 비용
  - 경계가 둘이 되어, 조직 기준 조회는 Project를 거쳐 조인해야 한다.
  - 상속이 없으므로 조직 관리자도 프로젝트마다 멤버로 추가해야 한다. 상속 정책을 정할 때까지 의도한 불편이다.

## 검증 (Phase 1~3 구현 때)

수용 기준 4(호환성 불변식)를 테스트로 고정한다.

- 권한 회귀 테스트: 조직 도입·backfill 전후로 모든 사용자의 `listAccessibleProjects` 결과와 read·write·owner 판정이 같아야 한다.
- 조직 OWNER·ADMIN이지만 프로젝트 소유자도 멤버도 아닌 사용자가 그 프로젝트를 읽으면 403이어야 한다.
- 한 프로젝트의 멤버가 같은 조직의 다른 프로젝트를 읽으면 403이어야 한다(cross-project deny).
- 기존 `project_id` 기준 조회와 API는 그대로 동작해야 한다.

## 재검토 조건

여러 회사·조직·사업장에서 쓰기로 확정되면 Tenant 정책, 권한 상속, Site, Partner를 구현 수준으로 확정한다(원문 §51).

## 참고

- 원문 §8–17, §50 Invariant 1
- [결정 인계](../decision-handoff.md) A1–A3
