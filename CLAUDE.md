# Ridley Project Instructions

## Clojure REPL Evaluation

Use `/Users/vipenzo/.local/bin/clj-nrepl-eval` for evaluating ClojureScript code via nREPL.

**Discover nREPL servers:**
```bash
/Users/vipenzo/.local/bin/clj-nrepl-eval --discover-ports
```

**For shadow-cljs projects, first switch to CLJS mode:**
```bash
/Users/vipenzo/.local/bin/clj-nrepl-eval -p <port> "(shadow/repl :app)"
```

**Then evaluate CLJS code:**
```bash
/Users/vipenzo/.local/bin/clj-nrepl-eval -p <port> "<cljs-code>"
```

The REPL session persists between evaluations. Always use `:reload` or `:reload-all` when requiring namespaces to pick up changes.

## Documentation Maintenance

Keep the following documents up to date:
- **`docs/Architecture.md`** - System architecture and design decisions
- **`docs/Roadmap.md`** - Project roadmap and sprint planning
- **`docs/Spec.md`** - Language specification and reference
- **`dev-docs/Examples.md`** - Usage examples and code samples

### Before Every Commit
Review and update the relevant documentation files if there have been changes that impact them.

### Before Every New Iteration
1. Check the **Current Sprint** section in `Roadmap.md`
2. Review completed tasks and pending items

### After Every Iteration
Update the **Current Sprint** section in `Roadmap.md` with progress and any new items.

## Release Process

When creating a new release:
1. Create the GitHub release with `gh release create`
2. GitHub Actions builds and deploys automatically on release publish (Pages + the macOS DMG)
3. **Wait for "Desktop Build (macOS)" to finish, then run `scripts/bump-cask.sh` — it is NOT automatic.**

Step 3 is the one that gets forgotten. Nothing tells Homebrew a new version exists:
`brew upgrade ridley` only reads `version` in `Casks/ridley.rb` of the **separate**
`vipenzo/homebrew-ridley` tap, and until that file is committed, users are told they
already have the latest. It was skipped for v3.2.0, v3.3.0 and v3.4.0.

```bash
scripts/bump-cask.sh            # latest release tag
scripts/bump-cask.sh v3.4.0     # a specific tag
```

It downloads the release's DMG, computes its sha256, and commits both to the tap — so it
must run AFTER the Desktop Build workflow has attached the DMG, or it downloads the wrong
file (or none). It runs on your local `gh` auth; wiring it into Actions would need a PAT
with write access to the tap, since `GITHUB_TOKEN` cannot push cross-repo.

The deploy workflow (`.github/workflows/deploy.yml`) handles:
- Installing dependencies
- Building production JS with shadow-cljs
- Deploying `public/` to GitHub Pages

The desktop workflow (`.github/workflows/desktop-build.yml`) stamps the bundle version from
the release tag into `tauri.conf.json` before building, so `About Ridley` matches the tag.
The versions checked into `tauri.conf.json` / `Cargo.toml` / `package.json` are only the
baseline a local dev build reports — they do not need bumping per release.

## ClojureScript Gotchas

### Dead Code Elimination (DCE)

Functions used in SCI bindings (the `implicit-*` functions in `repl.cljs`) must use `^:export` metadata to prevent Google Closure Compiler from eliminating them in production builds.

**Wrong:**
```clojure
(defn- implicit-foo [x] ...)  ; Will be eliminated!
```

**Correct:**
```clojure
(defn ^:export implicit-foo [x] ...)  ; Preserved in production
```

This is because the reference through the SCI bindings map is not visible to the Closure Compiler's static analysis.

