# Interactive Parallel Agent Scheduler

This static browser demo shows how the Bob-built Kotlin scheduler proposes compatible batches. It includes the real Markdown backlog from `examples/name-validation`, plus three focused interactive scenarios. It does not execute tasks or connect to an agent service.

## Run locally

Requires Node.js 22 or newer. From `demo/`:

```bash
npm ci
npm run dev
```

Open the URL printed by Vite. Run `npm test`, `npm run test:e2e`, and `npm run build` to verify the browser code. The browser tests use Chromium (`/snap/bin/chromium` locally); set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` to another Chromium path if needed. `npx playwright install chromium` can provision a browser where none is installed.

To refresh the checked-in real-backlog snapshot after editing Markdown issues, run `npm run import:backlog`. This validates schema v2 frontmatter and regenerates `src/scenarios/backlog.generated.ts`. The generated snapshot is committed so a static hosting build does not need the Kotlin toolchain or access to paths outside `demo/`.

To compare the TypeScript file-based selector with the Kotlin CLI, first build the CLI from the repository root with `./gradlew :planner-app:installDist`, then run `npm run test:parity` from `demo/`. The comparison includes real issues at multiple capacities, state transitions, affinity and an invalid cycle.

## Deploy on Vercel

Import `Pilleo/bob-hackathon-tasker` into Vercel with these project settings:

Use the `interactive-demo` branch until these demo sources are merged into `main`.

| Setting | Value |
| --- | --- |
| Root Directory | `demo` |
| Framework Preset | Vite |
| Install Command | `npm ci` |
| Build Command | `npm run build` |
| Output Directory | `dist` |

No API keys or server functions are required. After publishing, visit the generated URL in a private browser window to check the interactive scenarios, cover and PDF link. Record that public URL in the hackathon submission.

The same static build can be hosted on GitHub Pages. Build it from `demo/` with `DEPLOY_BASE=/bob-hackathon-tasker/ npm run build`, then publish the contents of `dist/` at the root of a `gh-pages` branch and enable branch-based Pages publishing. The project URL is `https://pilleo.github.io/bob-hackathon-tasker/`.

## Accuracy and boundaries

- Scheduler decisions are calculated in the browser using the file-based inputs shown on screen; they are not prewritten responses.
- The `test:parity` command guards the TypeScript port against the Kotlin scheduler implementation.
- Cache affinity is a preference for related work on the last available slot; neither the website nor this project measures cost reductions.
- The existing planner can use a configured ACP agent for elaboration and review. The website explains that workflow but does not pretend to perform live planning or launch coding agents.
