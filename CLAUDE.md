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
1. Build the production version for GitHub Pages: `npx shadow-cljs release app`
2. Commit the built files in `public/` if changed
3. Create the GitHub release with `gh release create`

