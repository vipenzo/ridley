# Archived examples and experiments

Historical artifacts kept for reference. Not part of the active documentation.

## multiboard-native.clj

Benchmark script that toggled between `native-*` (Rust HTTP geo-server) and `mesh-*` (WASM Manifold) backends. Native Manifold backend was removed on 2026-04-23 after the analysis in `dev-docs/manifold-backends-audit.md` and `dev-docs/transport-audit.md` showed WASM wins on all operations and sizes due to JSON+HTTP transport overhead, not Manifold engine performance. This script no longer runs on current Ridley (native-* bindings removed); kept for historical reference.
