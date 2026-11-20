---
name: flowmat-module-boundary
description: FlowMat 백엔드의 모듈 경계, 공개 계약(publicapi), 모듈 카탈로그(modules.yml), 분리 후보를 바꿀 때 지켜야 할 것과 돌릴 검사.
---

# FlowMat 모듈 경계

1. 지금의 경계는 [domain-map](../../../docs/architecture/domain-map.md) §1·§4, [ADR-035](../../../docs/architecture/adr/ADR-035-gradle-modules.md)(Gradle 모듈), [ADR-036](../../../docs/architecture/adr/ADR-036-cross-context-contract-types.md)(공개 계약 타입), [분리 후보](../../../docs/architecture/extraction-candidates.md)다.
2. 규칙:
   - 다른 context는 `application.publicapi`로만 쓴다. repository·entity·`api` 타입을 건너 쓰지 않는다.
   - 의존은 한 방향이다. 왼쪽 context가 오른쪽 기능이 필요하면 왼쪽이 인터페이스를 정의하고 오른쪽이 구현한다.
   - 새 모듈은 `flowmat_backend/settings.gradle`, 모듈의 `build.gradle`, `modules.yml`(contract, foreign_keys_to, depends_on)에 함께 넣는다. 테이블의 소유 모듈은 그 엔티티가 있는 모듈이다.
   - 저장소나 서비스로 떼는 것은 별도 결정이다. 외래 키와 재고 원장 쓰기를 두 곳에 두지 않는다.
3. 검사:

```bash
cd flowmat_backend && ./gradlew test --tests '*ModuleBoundaryTest' --tests '*ModuleCatalogTest' --tests '*ModuleCatalogDataTest'
```

4. 프런트의 테스트도 `modules.yml`의 폴더 경로를 본다(`frontend.yml`). 같은 변경에서 domain-map의 표와 의존 순서를 고친다.
