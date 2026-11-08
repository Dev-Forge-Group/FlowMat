# Third-party notices

FlowMat includes code adapted from the projects below. Each keeps its own license; the full license and notice files are
in [`licenses/`](licenses).

## Archify

- Source: [github.com/tt-a1i/archify](https://github.com/tt-a1i/archify), revision
  `a4065941f12be7967436261d9934784e381d423c` (2026-10-31)
- License: Apache License 2.0 — [`licenses/archify/LICENSE`](licenses/archify/LICENSE)
- Notice: [`licenses/archify/NOTICE`](licenses/archify/NOTICE), reproduced below as Apache-2.0 section 4(d) asks. The
  historical MIT notice it refers to is in [`licenses/archify/LICENSE-MIT`](licenses/archify/LICENSE-MIT).

What was adapted: the reading tools of Archify's diagram viewer (`viewer/viewer.css`, `template.source.html` and the viewer
scripts), rewritten as React components and CSS for FlowMat's workflow canvas:

- Semantic Passport, Route Probe, Semantic Lens, Semantic Radar, the node finder, the diagram guide, the diagram view
  controls with the motion control and the presentation stage, and the reading receipts in
  `flowmat_frontend/src/pages/workspace/ui/` (`SemanticPassport.tsx`, `RouteProbe.tsx`, `SemanticLens.tsx`,
  `SemanticRadar.tsx`, `ProcessFinder.tsx`, `DiagramGuide.tsx`, `DiagramNav.tsx`, `ReadingReceipts.tsx`,
  `ProcessConnections.tsx`)
- Their styles and design tokens in `flowmat_frontend/src/pages/workspace/ui/archifyReading.css`
- The reading models they use in `flowmat_frontend/src/pages/workspace/model/` (canvas reading, relations, run overlay,
  revision changes, layout checks, SVG export, motion)

Each adapted file says so in its header comment. FlowMat changed them to read FlowMat's processes, connections, runs and
revisions instead of Archify's diagram data. FlowMat does not include Archify's fonts or brand icons; the CSS only names
the JetBrains Mono font family, which falls back to the system's monospace font.

### Archify NOTICE

```text
Archify
Copyright (c) 2026 tt-a1i (Archify)
Copyright (c) 2025 Cocoon AI

Archify is distributed under the Apache License, Version 2.0 (LICENSE),
except where a file, package manifest, or third-party notice specifies otherwise.
The separately versioned DeepSeek Harness adapter remains MIT; its bundled
Skill retains the license of the immutable source selected in release.json.

Code and contributions received under the MIT License through pre-transition
commit 25cf37080fc8b1dbe758a4efc3935b55f3327083 remain available under their
original terms. Previously published releases keep their existing licenses. The complete historical MIT
copyright and permission notice is preserved in LICENSE-MIT. This transition
does not revoke existing MIT grants or assert a new patent grant on behalf
of historical contributors.

Archify originated from Cocoon-AI/architecture-diagram-generator:
https://github.com/Cocoon-AI/architecture-diagram-generator

Third-party components retain their own licenses; see THIRD_PARTY_NOTICES.md
and the license files beside those components. Names and logos do not imply
official endorsement, and the software license grants no general trademark
permission.

These software licenses cover Archify's embedded runtime and templates, not
ownership of a user's authored content, business data, or source repository.
```
