# 에이전트 지침

Claude Code, Codex 등 AI 코딩 도구가 이 저장소에서 작업할 때 따른다.

## 커밋·PR 작성자 표기

GitHub contributors에 AI 도구 계정이 올라가지 않도록 커밋 메타데이터에 AI 도구를 남기지 않는다.

- 커밋 메시지와 PR 본문에 AI 도구를 공동 작성자로 넣지 않는다. `Co-authored-by: Claude …`, `Co-authored-by: Codex …`처럼 `noreply@anthropic.com`·`openai.com` 주소를 쓰는 트레일러가 대상이다.
- AI 도구의 세션 링크도 넣지 않는다. `Claude-Session:` 트레일러와 PR 본문의 claude.ai 세션 URL, "Generated with …" 문구가 대상이다.
- 커밋의 author와 committer는 작업을 맡긴 사람의 Git 계정으로 한다. 실행 환경의 Git 사용자가 AI 도구 계정(예: `Claude <noreply@anthropic.com>`)이면 커밋 전에 저장소 설정(`git config user.name`, `git config user.email`)으로 바꾼다.
- Claude Code는 `.claude/settings.json`의 `attribution` 설정으로 공동 작성자 표기와 세션 링크를 끈다.
- `.github/workflows/commit-attribution.yml`이 PR과 기본 브랜치 push에서 이를 검사한다. 걸리면 해당 커밋의 트레일러나 작성자를 고친 뒤 다시 push한다.
