# Ridley – Architecture Assessment (2026-02-06)

## Snapshot
- UI orchestration in `ridley.core` mescola DOM, REPL, registry, viewport, AI/voice/sync con molti `defonce` globali.
- Scene graph informale (`scene/registry`) con mesh/path/material non tipizzati e math duplicata in `turtle/*` e `geometry/*`.
- Rendering Three.js in `viewport/core` accoppiato a stato globale; nessuna API pura headless.
- REPL SCI esegue codice utente con namespace ampio, stato globale (`turtle-atom`) e gestione errori minima.
- Bundle unico `:app` (shadow-cljs) con feature opzionali sempre caricate; test limitati al solo `turtle/core_test.cljs`.
- Persistenza in localStorage senza versioning/migrazioni; storage AI/voice/sync non validato.

## Weak Points (priorità)
1) Stato globale condiviso (UI, viewport, registry, REPL, settings) → difficile testare, multi-session, time-travel/debug.
2) Protocollo dati mesh/path/material non normalizzato; assenza di spec/validazione → rischio incoerenze e bug sottili.
3) Duplicazione utilità math → fix da replicare, rischio divergenza.
4) Rendering non componibile: Three.js gestito via side effects; impossibile usare backend alternativi o test headless.
5) REPL/AI/voice con error handling debole e sandboxing minimo; superfice sicurezza ampia.
6) Build monolitico senza code splitting; cold start e peso bundle elevati.
7) Persistenza senza version bump/migrazioni → possibili rotture silenti.
8) Test coverage quasi nulla su geometria, registry, REPL, export.

## Roadmap proposta
**Fase 1 (rapida)**
- Introdurre `ridley/math.cljs` unica per vettori/matrix e rimuovere duplicazioni.
- Definire spec/malli per `Mesh`, `Path`, `Material`, `Pose` in `ridley/schema.cljs` con assert in dev.
- Aggiungere property test per `geometry/operations` (extrude, revolve, loft) e contract test registry (id/visibility/update).

### Fatto (2026-02-06)
- Creato `src/ridley/math.cljs` e agganciato `turtle/core.cljs` + `geometry/operations.cljs`.
- Creato `src/ridley/schema.cljs` con assert dev e collegato a registry e operazioni geometriche (extrude, revolve, loft).
- Aggiunti test regressione in `test/ridley/geometry/operations_test.cljs`; suite test Shadow verde.
- Puliti gli infer-warning in `src/ridley/manifold/core.cljs` con hint `^js`/call esplicite; test suite verde e senza warning.

**Fase 2**
- Creare `ridley/state.cljs` (event/state container o re-frame) e migrare gradualmente gli atom globali di UI/viewport/registry/REPL/settings.
- Estrarre “scene graph” puro (`scene/graph.cljs`) e adattatore Three.js che renderizza da snapshot/diff.
- Hardening REPL: whitelist namespace SCI, timeout eval, gestione promise rejection, modalità “safe”.
- Versionare storage (settings/save-to-storage) + migrazioni.

**Fase 3**
- Code splitting shadow-cljs: moduli `:main`, `:ai`, `:voice`, `:xr` con lazy load.
- Observability: logger centrale + counter metriche base; UI fallback chiari per errori AI/voice/sync.
- Security review per sync P2P (schema messaggi) e per REPL (bloccare fetch/DOM non autorizzati).

## Prossimi passi consigliati (2–3 ore)
1) Ripulire warning `manifold/core.cljs` (annotazioni `^js` o `goog.object/get`).
2) Estendere assert a esport / loft complessi (`sweep-two-shapes-with-holes`, pipeline Manifold) e collegarli al registry.
3) Aggiungere test property su loft/sweep (winding cap, orientamenti) e su `scene/registry` (id/visibility/update invarianti).
