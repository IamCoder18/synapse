# Synapse Website + Agent Skill — Implementation Plan

## Goal
Deliver a deployable Astro + Fumadocs marketing/docs site for the Synapse library, plus an agent skill `synapse-pubsub-ftc`. Both must be AI-friendly.

## Resolved Decisions
- **Brand palette:** Teal-signal + lime accent on near-black. Primary `#0E7C7B`, accent `#B6FF3C`, surface `#0A0F14`, surface-elevated `#11181F`, text `#E6F1EE`, muted `#7A8A86`. Dark-mode default with a designed light mode (not auto-invert).
- **Site scope:** `/` (home + CTA), `/docs` (Fumadocs), `/install`, `/changelog`, `/community`. No blog.
- **Skill name:** `synapse-pubsub-ftc`.

## Content Honesty Rules (hard constraints)
- **No fabricated information.** Every claim on the site must be backed by either (a) source code in this repo with a `file_path:line_number` reference, or (b) the contents of `README.md` / `CHANGELOG.md`. No invented numbers, benchmarks, download counts, adoption stats, latency figures, or "X teams use this" claims.
- **No testimonials.** Do not invent quotes, attributions, or endorsements. If real quotes are ever added, they must come from a named person with their explicit written permission and a verifiable source (PR comment, email, etc.).
- **Known user (only one):** FTC Team 23684 — Tech Titans. They may be acknowledged on `/community` as the one confirmed team using Synapse. Do not extrapolate to "multiple teams", "FTC-wide adoption", or any other usage claim.
- **ComparisonTable is opinion, not benchmark.** Frame raw-FTC vs. Synapse rows as design choices (e.g., "Synapse provides a single hardware thread; raw FTC does not enforce one") — never as measured numbers like "X% fewer races" or "Y ms faster".
- **No "social proof" widgets** (download counters, fake testimonial carousels, GitHub star counters unless rendered live from the actual API at build time — and even then, only as optional decoration, not a claim).

## Layout (created inside `website/`)

```
website/
├── astro.config.mjs
├── package.json
├── tsconfig.json
├── tailwind.config.ts                  # only if needed; Fumadocs ships its own token system
├── Dockerfile
├── docker-compose.yaml
├── .dockerignore
├── README.md
├── public/
│   ├── favicon.svg
│   ├── og-default.png
│   └── robots.txt
└── src/
    ├── env.d.ts
    ├── styles/
    │   ├── global.css                   # imports tailwindcss + fumadocs-ui css variables override
    │   └── tokens.css                   # Synapse brand CSS custom properties
    ├── lib/
    │   ├── source.ts                    # Fumadocs loader (mirror of manual install)
    │   ├── search.ts                    # Orama index generator
    │   ├── ai.ts                        # helpers to generate /llms.txt and per-page .md
    │   └── changelog.ts                 # reads CHANGELOG.md from repo root at build time
    ├── components/
    │   ├── layout/
    │   │   ├── BaseLayout.astro         # <head>, JSON-LD, ViewTransitions, theme boot
    │   │   ├── Nav.astro                # logo + links + GitHub star CTA
    │   │   ├── Footer.astro
    │   │   └── ThemeToggle.astro
    │   ├── marketing/
    │   │   ├── Hero.astro               # neural-network animated SVG, install cmd, two CTAs
    │   │   ├── FeatureGrid.astro        # 6 feature cards
    │   │   ├── SafetyPillars.astro      # the 4 properties the README sells
    │   │   ├── LiveCodePreview.astro    # client:visible Shiki + typewriter
    │   │   ├── ArchitectureDiagram.astro
    │   │   ├── ComparisonTable.astro    # raw FTC vs. Synapse (design choices, no numbers)
    │   │   └── InstallSnippet.astro     # copy-to-clipboard Gradle block
    │   └── react/
    │       ├── Docs.tsx                 # wraps fumadocs-ui DocsLayout + DocsPage
    │       ├── Search.tsx               # orama static-client search dialog
    │       ├── HeroCanvas.tsx           # canvas pulse-line neural network
    │       └── CopyButton.tsx
    ├── content/
    │   └── docs/                        # MDX files for Fumadocs
    │       ├── index.mdx                # docs landing
    │       ├── meta.json
    │       ├── get-started/
    │       │   ├── index.mdx
    │       │   ├── install.mdx
    │       │   └── first-opmode.mdx
    │       ├── concepts/
    │       │   ├── index.mdx
    │       │   ├── orchestrator.mdx
    │       │   ├── topics.mdx
    │       │   ├── nodes.mdx
    │       │   └── subscriptions.mdx
    │       ├── annotations/
    │       │   ├── index.mdx
    │       │   ├── subscribed-to.mdx
    │       │   ├── run-periodically.mdx
    │       │   ├── runnable-action.mdx
    │       │   └── on-hardware-thread.mdx
    │       ├── ftc/
    │       │   ├── index.mdx
    │       │   ├── safe-opmode.mdx
    │       │   ├── hardware-actions.mdx
    │       │   ├── safe-device.mdx
    │       │   └── gamepad-adaptor.mdx
    │       ├── recipes/
    │       │   ├── index.mdx
    │       │   ├── mecanum-drive.mdx
    │       │   ├── bulk-read-sensors.mdx
    │       │   └── two-controller-teleop.mdx
    │       └── api/
    │           └── index.mdx            # hand-curated API surface for v1
    └── pages/
        ├── index.astro                  # marketing home
        ├── install.astro
        ├── changelog.astro              # renders src data via lib/changelog.ts
        ├── community.astro
        ├── docs/
        │   └── [...slug].astro          # catch-all routing into Fumadocs
        ├── api/
        │   └── search.ts                # fumadocs-core search endpoint
        ├── og/
        │   └── docs/[...slug]/image.webp.ts
        ├── llms.txt.ts                  # returns Markdown root llms.txt
        ├── llms-full.txt.ts             # concatenated markdown of every page
        └── docs.md.ts                   # returns /docs as concatenated markdown (describedby)
```

## Tech Stack
- Astro 5 (`output: 'static'`), `@astrojs/react`, `@astrojs/mdx`, `@astrojs/sitemap`.
- Tailwind CSS 4 via `@tailwindcss/vite` (Fumadocs requires it). Override the `fumadocs-ui/css/neutral.css` and `preset.css` variables with Synapse tokens in `global.css`.
- `fumadocs-core`, `fumadocs-ui` (DocsLayout + DocsPage inside a React island).
- `takumi-js` + `sharp` for OG images (per manual install).
- `shiki` for code highlighting (Fumadocs handles via `rehypeCode`).
- `@orama/orama` for the search index (fumadocs-core ships `staticClient`).
- `gray-matter` to parse `CHANGELOG.md` (Keep-a-Changelog format).
- No SSR adapter needed — site is fully static so the Docker image is nginx-based (smaller, faster).

## Design System
- **Type:** Display = `Space Grotesk` (700) for headings; Body = `Inter Variable` (400/500); Mono = `JetBrains Mono Variable` for code. Self-hosted via Fontsource-free CDN with `font-display: swap`.
- **Logo:** Custom SVG monogram — a stylized synapse: two nodes joined by an animated pulse line. Inline SVG in `Nav.astro`; no raster fallback.
- **Color tokens** (CSS custom properties in `tokens.css`):
  - `--signal`: `#0E7C7B`
  - `--signal-bright`: `#1FBFB6`
  - `--lime`: `#B6FF3C`
  - `--surface-0`: `#0A0F14`
  - `--surface-1`: `#11181F`
  - `--surface-2`: `#18222A`
  - `--text`: `#E6F1EE`
  - `--text-muted`: `#7A8A86`
  - `--border`: `#1E2A33`
  - Light mode is a separate token set (surface `#F7FAF9`, text `#0A1417`).
- **Theme toggle:** controlled by `data-theme` attribute, persisted in `localStorage`, no FOUC via inline boot script in `<head>`.
- **Motion:**
  - Hero: canvas-based pulse-line neural network (≈ 12 nodes, lines drawn with spring tension, pulses fire on edges). React island `client:visible`, respects `prefers-reduced-motion`.
  - Scroll: IntersectionObserver-driven reveal (CSS classes only, no JS lib).
  - Astro `<ClientRouter />` for SPA-style transitions between marketing pages.
  - All non-essential motion disabled when `prefers-reduced-motion: reduce`.
- **Iconography:** `lucide` via inline SVGs (no icon font).

## Page Content (high-level)

### `/` (home)
1. **Hero** — H1 "Robots that listen to each other." Subhead explaining Synapse in ≤ 25 words. Two CTAs: "Read the docs →" and "Install for FTC". Below: copy-paste Gradle snippet (`implementation 'com.aaravlabs:synapse:0.3.1'`). Live canvas behind text.
2. **FeatureGrid** — 6 cards: `@SubscribedTo` typed callbacks, `@RunPeriodically` fixed-rate loops, `@RunnableAction` named commands, `@OnHardwareThread` marker, `SafeOpMode` drop-in base, `GamepadAdaptor` zero-boilerplate controls.
3. **LiveCodePreview** — split panel: left is annotated `DriveNode.java`, right shows the same code with hover annotations explaining each line.
4. **SafetyPillars** — the four properties the tests prove: (a) single hardware thread, (b) two-pool isolation, (c) copy-on-write subscribe snapshots, (d) `assertNotHardwareThread` fail-fast.
5. **ComparisonTable** — raw FTC vs. Synapse, framed as design choices (no benchmark numbers): e.g., "Synapse provides a single hardware thread; raw FTC does not enforce one." Each row cites the source (`README.md`, `OrchestratorImpl.java:93`, etc.).
6. **Footer** — license, repo, docs, community, install.

### `/install`
- Three tabs: **Gradle (Groovy)**, **Gradle (Kotlin DSL)**, **Maven**.
- Step-by-step: 1) add dependency, 2) apply R8 keep rules (link to synapse.pro), 3) extend `SafeOpMode` (one-file copy-paste), 4) verify on `BIND` event, 5) optional: enable `GamepadAdaptor`.
- "Verify it works" block with a minimal `MyFirstOpMode` and expected logcat lines.

### `/changelog`
- At build time, `lib/changelog.ts` reads `CHANGELOG.md` copied into the builder from the repository-root build context (Keep-a-Changelog 1.1.0), parses it with `gray-matter` + a tiny regex pass for `## [x.y.z] - date` sections, returns structured data.
- Renders version cards (badge per SemVer), grouped by Added/Changed/Fixed/Removed. Highlights latest release.

### `/community`
- Three blocks:
  1. **Contribute code** — link to repo, list of "good first issues" fetched at build time via `gh api` CLI (cached as JSON during build). Fall back to static list if offline.
  2. **Share projects** — invite FTC teams using Synapse to open a PR adding their team to a showcase list. Include a single confirmed entry as the example: **FTC Team 23684 — Tech Titans** (with whatever public info the team has consented to share: name, number, optional link). No other teams are listed unless explicitly added later.
  3. **Improve docs** — link to the `content/docs/` source and the "Edit on GitHub" pattern.
- Contributor ladder (Inspired by some open-source projects): Contributor → Maintainer → Reviewer.
- **Explicit on-page disclaimer**: "Synapse is new. FTC Team 23684 — Tech Titans is the one team confirmed to be using it." (No invented "users", "downloads", "stars", or community size claims anywhere on this page.)

### `/docs` (Fumadocs)
- Sidebar tree: Get Started → Concepts → Annotations → FTC Integration → Recipes → API.
- Top bar search dialog (Orama) triggered with `⌘K`/`Ctrl K`.
- Each MDX page renders through `Docs.tsx` React island; TOC generated from headings; "Edit on GitHub" link.
- Home page of docs (`docs/index.mdx`) is a friendly gateway: 60-second tour, then direct links into sections.

## AI-Friendly Requirements
- **`/llms.txt`** — generated at build from `pages/llms.txt.ts`. Markdown following llmstxt.org v2 spec: H1, blockquote summary, sections per route, links to `.md` mirror. Updates whenever content collections change.
- **`/llms-full.txt`** — every page concatenated, plain markdown, ≤ 1 MB.
- **Per-page `.md` mirror** — pre-rendered at build time as a static `.md` file beside each docs page (MDX body stripped of components → plain markdown via `remark`). Nginx serves the pre-rendered `.md` when the request's `Accept` header prefers `text/markdown` (content negotiation lives in `nginx.conf`; Astro middleware does not run at request time with `output: 'static'`). The `Link: <url>; rel="alternate"; type="text/markdown"` header is emitted by Nginx `add_header` (see Docker Deployment).
- **`/docs.md`** — same as llms-full but scoped to `/docs/`.
- **JSON-LD** on every page (`SoftwareSourceCode` for repo, `TechArticle` for docs, `WebSite` with `SearchAction` for home, `Organization` on `/community`).
- **Semantic HTML**: single `<h1>` per page, proper landmark roles, `aria-label` on icon-only nav buttons.
- **`<meta>`** + OG tags generated per page via a shared helper.
- **Sitemap.xml** via `@astrojs/sitemap`.
- **`robots.txt`** allows all and points to `/llms.txt`.
- **Structured headings** so an LLM can chunk docs naturally.

## Docker Deployment
- **Multi-stage Dockerfile** (build context = repository root so `CHANGELOG.md` is inside it):
  - `node:lts-alpine` builder copies `website/` and `CHANGELOG.md`, then runs `npm ci && npm run build`.
  - `nginx:alpine` runtime copies `dist/` and a custom `nginx.conf` that:
    - serves on `0.0.0.0:8080`
    - sets `Cache-Control: public, max-age=31536000, immutable` for hashed assets
    - sets `Cache-Control: public, max-age=0, must-revalidate` for HTML
    - emits `Link: </llms.txt>; rel="describedby"` and per-page markdown alternate `Link` headers via `add_header` (`sub_filter` is only for rewriting response-body content, e.g. per-page markdown alternate links inside the HTML)
    - falls back to `/404.html` for missing routes
    - gzip + brotli for text
- **`docker-compose.yaml`** at `website/docker-compose.yaml` (context is the repository root so the builder can copy `CHANGELOG.md`):
  ```yaml
  services:
    web:
      build:
        context: ..
        dockerfile: website/Dockerfile
      image: synapse-website:local
      container_name: synapse-website
      restart: unless-stopped
      ports:
        - "8080:8080"
      healthcheck:
        test: ["CMD", "wget", "-qO-", "http://localhost:8080/healthz"]
        interval: 30s
        timeout: 3s
        retries: 3
  ```
- Adds a `/healthz` endpoint that returns 200 with text `ok`.

## Agent Skill — `synapse-pubsub-ftc`
Location: `/home/aarav/apps/ftcpubsub/.kilo/agent/synapse-pubsub-ftc/SKILL.md` (plus one `references/` subdir).

### `SKILL.md` outline (target ≈ 280 lines)
```
---
name: synapse-pubsub-ftc
description: Builds FIRST Tech Challenge (FTC) robot code on the Synapse annotation-driven pub/sub bus. Use when the user asks for FTC robot code, hardware-thread-safe teleop/autonomous logic, or to refactor raw FTC OpModes to Synapse, or mentions Synapse / synapse-pubsub / @SubscribedTo / @RunPeriodically / @OnHardwareThread / SafeOpMode / HardwareActions / SafeDevice / GamepadAdaptor / Synapse orchestrator.
---

# Synapse on FTC

## When to use
- Writing a NEW FTC OpMode that should use Synapse primitives.
- Migrating an existing OpMode off direct `DcMotorEx` calls onto the hardware thread.
- Debugging a race condition or repeated `RuntimeException` involving `DcMotor` / `Servo` / `IMU`.
- Wiring gamepads / sensors / actuators via topics.

## When NOT to use
- Pure FRC / non-FTC code. STOP and recommend the FTC analogue only.
- Tasks that don't involve hardware (vision pipelines, UI) — Synapse still works but is overkill.

## Mental model
- One `Orchestrator` per robot. It owns four executors (NOT in the API: scheduler, callback pool, action pool, **single** hardware thread).
- Annotations are the entry point; classes extend `Node` and methods are bound by `AnnotationBinder` at runtime.
- All hardware calls must cross the hardware thread. Off-thread access throws.
- Topics are typed (`Topic<Double>`, `Topic<Transform2d>`). Subscribers get type-checked callbacks.

## Core workflow (copy/paste this checklist)
```
- [ ] 1. Confirm user wants Synapse (not raw FTC) — see When to use above.
- [ ] 2. Identify OpMode lifecycle hooks needed (init / loop / stop) and whether teleop or auto.
- [ ] 3. Decide the Topics (name + Java type) BEFORE writing nodes.
- [ ] 4. Build Node classes (one concern each: drive, intake, shooter, etc.).
- [ ] 5. Wire hardware via `safeMap.device(...)` and `hardware.call(...)` — never raw.
- [ ] 6. Validate hardware-thread discipline with `hardware.assertNotHardwareThread()` in `loop()`.
- [ ] 7. Provide a copy-paste `MyFirstOpMode extends SafeOpMode` if user is new.
```

## Decision trees

### "Should this run on the hardware thread?"
- Touches any `DcMotor*`, `Servo*`, `IMU`, `ColorSensor`, `TouchSensor`, `DigitalChannel` → YES via `@OnHardwareThread` or `hardware.call(...)`.
- Reads gamepad input → NO (gamepad reads are off-thread safe and update via `GamepadAdaptor`).
- Pure math / state machine → NO.

### "Periodic vs action vs subscription?"
- Needs to fire every N ms regardless of input → `@RunPeriodically(hz=N)`.
- One-shot in response to a button → `@RunnableAction("name")` + `orch.runAction("name")`.
- Reactive to a state change → `@SubscribedTo(topic = "topic")`.

### "Topic type — primitive vs wrapper?"
- Either works; `double` and `Double` are interchangeable at the topic layer (`boxed(Class)` helper). Prefer primitives in annotations.

## Patterns
[Use this template when generating a new node]
```java
public class DriveNode extends Node {
    private final HardwareActions hardware;
    public DriveNode(Orchestrator orch, SafeHardwareMap map) {
        this.hardware = orch.hardware();
        orch.registerNode(this);
    }

    @SubscribedTo(topic = "drive/target")
    void onTarget(TargetPose t) {
        hardware.run(() -> /* motor writes */);
    }
}
```

## Common mistakes
1. Calling `motor.setPower(...)` from `loop()` → race. Use `hardware.run(...)` or `@OnHardwareThread`.
2. Forgetting `orch.registerNode(this)` → annotations never bind.
3. Using `int` topic for sensor that produces `double` → type mismatch at publish time.
4. Re-creating an `Orchestrator` per `loop()` → memory leak and thread churn.

## Reference files (load on demand)
- `references/annotations.md` — full attribute table for each annotation.
- `references/safety.md` — the four safety invariants and which tests prove each.
- `references/recipes.md` — copy-paste recipes: mecanum, bulk reads, two-controller teleop.
- `references/migration.md` — step-by-step raw-FTC → Synapse refactor.
```

### `references/annotations.md`
A small table per annotation: target, attributes, defaults, runtime contract, what happens if you violate it, one example.

### `references/safety.md`
The four invariants from `SafetyPillars.astro`, each cited with the test file that proves it (`HardwareThreadTest.java`, `ThreadingTest.java`, `SoakTest.java`, `SafeOpMode` `loop()`).

### `references/recipes.md`
Same content as `content/docs/recipes/*.mdx`, condensed to ~150 lines.

### `references/migration.md`
Step-by-step: identify hardware writes → wrap in `hardware.call` → extract topic types → create `Node` → bind annotations.

## Implementation Order

1. **Scaffold** — `cd /home/aarav/apps/ftcpubsub && npm create astro@latest website -- --template minimal --typescript strict --no-install --no-git --skip-houston`, then `cd website && npm i` the dependency list above. Verify `npm run dev` boots.
2. **Astro + Tailwind + React + MDX** — wire `astro.config.mjs` per Fumadocs manual install, add `tailwindcss()` Vite plugin, set `site: 'https://synapse.i-am-coder.dev'` (placeholder).
3. **Brand tokens + global CSS** — `tokens.css` then `global.css` with `@import 'tailwindcss'; @import 'fumadocs-ui/css/neutral.css'; @import 'fumadocs-ui/css/preset.css';` and override the CSS vars.
4. **Base layout + nav + footer + theme boot script.**
5. **Homepage** — Hero + canvas island + FeatureGrid + SafetyPillars + LiveCodePreview + ArchitectureDiagram + ComparisonTable + Footer.
6. **Install page.**
7. **Changelog parser + page** (read repo-root `CHANGELOG.md`).
8. **Community page.**
9. **Docs (Fumadocs)** — implement `lib/source.ts`, `Docs.tsx`, `Search.tsx`, `[...slug].astro`, `api/search.ts`, `og/docs/[...slug]/image.webp.ts`. Author all MDX files in `content/docs/`.
10. **AI-friendly plumbing** — pre-rendered per-page `.md` mirrors (served by Nginx on `Accept: text/markdown`), `llms.txt.ts`, `llms-full.txt.ts`, `docs.md.ts`, `Link` headers via Nginx `add_header`, JSON-LD helpers.
11. **SEO** — `@astrojs/sitemap`, `robots.txt`, OG defaults, canonical URLs.
12. **Docker** — multi-stage `Dockerfile`, `nginx.conf`, `docker-compose.yaml`, `.dockerignore`.
13. **Agent skill** — create `.kilo/agent/synapse-pubsub-ftc/SKILL.md` and `references/` files.
14. **README** in `website/` documenting `npm run dev`, `npm run build`, `docker compose up`.

## Validation
- `npm run build` produces `dist/` with no errors.
- `npm run dev` → click every marketing page → every docs link → search dialog returns results.
- `curl -L http://localhost:8080/llms.txt` returns a valid llms.txt v2 document.
- `curl -LH "Accept: text/markdown" http://localhost:8080/docs/get-started` returns markdown.
- `curl http://localhost:8080/sitemap.xml` lists every route.
- `docker compose up --build` serves the site on `:8080`; `curl :8080/healthz` returns 200.
- Lighthouse desktop ≥ 95 Performance, ≥ 95 Accessibility on `/` and a docs page.
- `axe-core` run on `/`, `/docs/get-started/install`, `/community` → zero violations.
- Skill metadata validates (name ≤ 64, lowercase-hyphen, description ≤ 1024, third person).
- Skill `SKILL.md` body ≤ 500 lines; references stay one level deep.
- **Content honesty audit:** grep `dist/` for `["0-9]+ teams?`, `downloads?`, `used by`, `powers`, `trusted by`, fake metrics. Only `FTC Team 23684` and `Tech Titans` may appear as a user name. Any other match is a bug — fix before shipping.

## Risks & Mitigations
- **Fumadocs Astro quirks** — `RootProvider` + `navigate` from `astro:transitions/client` must be the exact form from the manual-install doc or hydration breaks. Pin versions.
- **OG image generation** — `takumi-js` has no Linux ARM64 wheels yet; if build fails in CI, switch to a static SVG-derived PNG using `sharp`.
- **Changelog parser drift** — `gray-matter` handles the YAML frontmatter; the `## [x.y.z] - date` regex must tolerate `- TBD` and `- Unreleased`. Keep parser in one file with a `parse()` unit test invoked via `npm run test:parse`.
- **AI header/mirror delivery** — `Link` headers are emitted by Nginx `add_header` and `.md` mirrors are pre-rendered at build (static Astro output cannot negotiate per request); verify both in `nginx.conf` and in the built `dist/` tree.
- **No gradle wrapper in repo** — Docker image is nginx-based so no JDK needed; smaller, faster cold start.

## Explicit Out of Scope
- Blog (user replaced with `/community`).
- SSR adapter / server endpoints.
- Authentication, analytics, telemetry.
- Auto-generating API docs from Javadoc (hand-curated for v1).
- Translations / i18n.
- Real testimonials, social-proof widgets, or fabricated usage statistics.
- Any user list beyond FTC Team 23684 — Tech Titans, unless explicitly added later with consent.