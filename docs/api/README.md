# 공개 API 계약

> **현행 문서** · 확인 2026-11-22(`main` V140, API 1.0.0). 판을 올리거나 규칙을 바꾸면 같은 변경에서 고친다.

[로드맵](../status/ROADMAP-2026-11-23.md) §12 P2 2번이다. 기능을 FlowMat 밖의 서비스로 떼어 따로 배포하려면, 그 서비스와 FlowMat은 판이 붙은 공개 계약으로만 이어져야 한다. 첫 서비스를 떼기 전에 그 계약을 먼저 고정해 둔다.

- `openapi.json`은 서버가 내는 OpenAPI 문서다(`/api/v3/api-docs`). 비교가 흔들리지 않게 다음 셋을 손봤다.
  - 서버 주소를 뺐다.
  - `operationId`를 뺐다. springdoc은 이름이 겹치는 메서드에 찾은 순서대로 번호를 붙이므로, 컨트롤러 하나가 늘면 다른 이름이 바뀐다.
  - 키와 태그를 정렬했다.
- 판은 `OpenApiConfig.API_VERSION`이다. 지금은 1.0.0이다.

## 검사

`OpenApiContractTest`(백엔드 테스트)가 서버의 문서와 `openapi.json`을 비교한다.

- **같지 않으면 실패한다.** HTTP API의 변화가 모두 리뷰에서 diff로 보인다. 일부러 바꿨다면 아래 명령으로 파일을 다시 쓰고, 같은 PR에 넣는다.
- **깨는 변경이면 큰 판을 올려야 한다.** 게시된 작업(메서드와 경로), 스키마, 스키마의 필드가 사라지는 것이 깨는 변경이다. 이때 `API_VERSION`의 첫 숫자가 `openapi.json`의 것보다 커야 한다.
  - 더하는 것(새 경로, 새 필드)은 판을 올리지 않아도 된다.
  - 필드를 필수로 바꾸거나 타입을 바꾸는 것은 이 검사가 잡지 못한다. 리뷰에서 diff로 본다.
- PR의 백엔드 테스트는 이미 통과한 클래스를 건너뛰지만([CI가 다시 돌리지 않는 것](../operations/ci-selection.md) §3), 이 테스트는 늘 돈다. springdoc은 컨트롤러를 실행하지 않고 읽기만 하므로, 바뀐 컨트롤러가 이 테스트를 다시 돌게 하지 못하기 때문이다. `backend.yml`은 이 파일만 바뀌어도 돈다.

```bash
cd flowmat_backend && ./gradlew test --tests org.myweb.flowmat.OpenApiContractTest -Dopenapi.update=true
```

## 이 계약을 쓰는 곳

- 지금은 FlowMat의 화면뿐이다. 화면은 같은 저장소에서 같이 바뀐다.
- 따로 배포하는 첫 단위는 flowmat-ai의 순서 최적화 서비스로 잡혀 있다(로드맵 §12 P2 3번). 그 서비스는 이 파일의 판을 보고 맞춘다.
