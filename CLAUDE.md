# Ridley Project Instructions

## Documentation Maintenance

Keep the following documents in `/dev-docs` up to date:
- **Architecture.md** - System architecture and design decisions
- **Roadmap.md** - Project roadmap and sprint planning
- **Examples.md** - Usage examples and code samples
- **Spec.md** - Language specification and reference

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
2. GitHub Actions will automatically build and deploy to GitHub Pages on release publish

The deploy workflow (`.github/workflows/deploy.yml`) handles:
- Installing dependencies
- Building production JS with shadow-cljs
- Deploying `public/` to GitHub Pages

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

