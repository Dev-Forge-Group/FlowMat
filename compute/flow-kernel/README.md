# flowmat-flow-kernel

FlowMat 흐름의 의미를 한 곳에 모을 Rust 크레이트다([ADR-040](../../docs/architecture/adr/ADR-040-flow-kernel.md)). 포트 호환, 조건식, 검증 31규칙, 경로 계산을 맡는다. 편집기는 WebAssembly로 그리는 즉시 검증하고 돌려 본다. 서버가 쓸지는 4주차에 잰다(결정 6). 그때까지 Java가 정본이다.

지금(1주차)은 Java가 답을 적은 고정 입력만 있다. Rust 코드는 2주차에 들어온다([월간 계획](../../docs/status/MONTH-PLAN-2026-11-23.md)).

## 고정 입력

| 파일 | 내용 | 누가 쓰나 |
|---|---|---|
| `fixtures/cases.json` | 손으로 고른 그래프 8개: 문제없는 줄·게이트웨이·분할과 합류, 기본 경로 둘, 분할 몫 초과, 스키마 불일치, 순환, 빈 흐름 | 사람 |
| `fixtures/validation.json` | 위의 그래프와 고정 시드(`20261123`)로 만든 그래프 160개, 각각에 `WorkflowValidationService`가 낸 답(`errors`, `warnings`, `issues`의 순서·근거·고치는 법·숨기는 경고), 알려진 단위(`context.knownUnits`) | `FlowKernelFixtureTest` |

- 그래프 하나는 `{processes, ports, connections}`이고, 필드 이름은 서버의 공정·포트·연결과 같다. 알려진 단위는 파일의 `context.knownUnits` 하나다. 표준 자원 종류는 커널에 고정한다(`ResourceTypes`와 같은 목록).
- 백엔드 테스트 `FlowKernelFixtureTest`가 Java의 지금 답이 파일과 같은지, 31규칙이 각각 한 번 이상 걸리는지 본다. 백엔드 CI는 이 폴더가 바뀌어도 돈다.
- 경로 계산(`FlowRunGraph`)의 고정 입력은 다음에 더한다.

검사를 일부러 바꿨으면 Java의 답을 다시 쓰고, 바뀐 줄을 읽은 뒤 커널을 맞춘다.

```bash
cd flowmat_backend
```

```bash
./gradlew test --tests '*FlowKernelFixtureTest' -Dflow.fixtures.update=true
```
