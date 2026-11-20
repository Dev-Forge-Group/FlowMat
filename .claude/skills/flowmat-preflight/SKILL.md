---
name: flowmat-preflight
description: FlowMat에서 큰 작업을 시작하기 전에 고칠 정본, 영향받는 계약·DB·CI, 위험 등급, 검사 계획을 읽기만으로 정한다. 여러 모듈에 걸치거나 마이그레이션·권한·재고를 건드리는 작업 전에 쓴다.
---

# FlowMat 사전 점검

아무것도 고치지 않고 아래를 확인한 뒤 짧은 계획을 낸다.

1. `git status`, 지금 브랜치, `origin/main`과의 차이를 본다. 다른 사람이나 다른 에이전트가 고치던 파일이 있으면 건드리지 않는다.
2. [AGENTS.md](../../../AGENTS.md)와 작업에 관련된 문서만 읽는다: [domain-map](../../../docs/architecture/domain-map.md), 해당 ADR, `docs/domain/`의 계약, [CURRENT_CAPABILITIES](../../../docs/status/CURRENT_CAPABILITIES.md). 보관 문서(`docs/archive/`)의 할 일은 현행 문서로 다시 확인한다.
3. 고칠 파일이 정본인지 본다. 편집기 사본(`flowmat_frontend/src/lib/flowmat-editor`), 커밋된 WebAssembly(`flowmat_frontend/src/shared/wasm`), 고정 입력(`compute/condition-expression/fixtures`)은 만든 곳에서 다시 만든다.
4. 영향을 적는다.
   - API: 컨트롤러 경로, 요청·응답 모양, 프런트의 사용처(`flowmat_frontend/src/entities/*/api`).
   - DB: 새 마이그레이션 번호(`flowmat_backend/src/main/resources/db/migration`의 마지막 번호 + 1), 테이블의 소유 모듈(`ModuleCatalogDataTest`).
   - 모듈: 다른 모듈은 `application.publicapi`로만 쓴다. `modules.yml`의 `depends_on`과 의존 순서.
   - CI: 바뀌는 경로가 어느 워크플로의 `paths`에 걸리는지.
5. 위험 등급을 고른다(AGENTS.md의 표). 재고·원장, 인증·권한, 결제, 마이그레이션은 높음이다.
6. 계획에 적는다: 바꿀 파일, 새 테스트와 돌릴 기존 테스트, 되돌리는 법, 사람의 승인이 필요한 곳.
