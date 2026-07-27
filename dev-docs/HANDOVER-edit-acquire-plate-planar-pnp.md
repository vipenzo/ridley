# HANDOVER — piatto di registrazione + PnP planare

**Branch**: `edit-acquire-registration-stability`
**Data**: 2026-07-27
**Task della prossima chat**: aggiungere il **PnP planare** al solver, con un
**flag** per tenere vivo anche il PnP non-complanare (box). Poi rifare il gate
live sul piatto.

---

## TL;DR

Il piatto di registrazione (fetta A) e la registrazione `p` sui suoi mark
(fetta B) sono **fatti, verificati, committati**. Il **gate live** sul piatto
stampato è **fallito per un motivo strutturale**: i mark del piatto sono tutti
**complanari** (tutti sulla faccia superiore) e il solver PnP attuale è un
**DLT generale** che per costruzione richiede punti **non** complanari →
"nessuna soluzione" oppure RMS spazzatura (>126 px). Non è un problema di
precisione dei click.

La cura è il **PnP planare** (omografia → posa-seme → l'LM già esistente): è
esattamente come registrano i bersagli piatti standard (ArUco/ChArUco/AprilTag).
Il piatto piatto è il design giusto; manca il pezzo di solver.

Vincenzo vuole **entrambi** i motori vivi (il `p` sui vertici del box resta
utile) con un **flag per scegliere**.

---

## Cosa gira oggi (commit, sul branch)

- `259e4f9` — auto-init `session.json` in-app da NOTE.md + parser NOTE condiviso
  (`ridley.photogrammetry.note`) + foto θ=`libera` (fuori-anello, solo PnP).
- `0c9f60b` — verbale gate v1 (funzionale ✅ / precisione ✗) + design piatto v2.
- `9d6a03a` — **fetta A**: `examples/param-acq-plate.clj` genera il piatto ⌀130
  + corona di 12 dischetti a due colori (incassati a filo) + zero-indice.
  UNICA FONTE: `mark-centers` genera geometria E mappa mark; i mark viaggiano
  sulla mesh sotto `:anchors` `{:m00 {:position :heading :up}…}`. Export 2-colori
  `(save-3mf (into [piatto] discs) …)`.
- `e7d9190` — **fetta B fondamenta**: `attachment/transform-mesh-rigid` (dietro
  `group-transform`) ora porta anche `:anchors` (prima li lasciava indietro →
  i mark si sganciavano quando la sessione ri-posava il proxy).
- `adf7dec` — **fetta B**: `p` registra sui mark del proxy, non solo spigoli box.
  Core puro in `bridge` (`world->local`, `pnp-target-points` — node-testato,
  4 test); rewire `edit_acquire` (~10 punti, chiavi-pick restano indici);
  `canonicalize-orientation!` ora remappa anche `:anchors`. Suite 751/0-fail.

Test-asset (untracked): `test-assets/param-plate-one/` — 11 foto reali del
lettore SD sul piatto + `NOTE.md` (tutte θ=libera) + `session.json` +
`acquire-state.json` (i click che Vincenzo ha già piazzato).

---

## Il gate live che ha innescato l'handover

Vincenzo, piatto stampato, oggetto = lettore SD (lo stesso di
`param-acq-reader`) al centro del piatto. Aperto con
`(edit-acquire "test-assets/param-plate-one/" {:proxy piatto})`, `p` su 3 foto:

- **10/12 mark visibili** su ogni foto → il disegno dei pallini e il click
  **funzionano**.
- Il **solve** dà: RMS da **>126 px** a **"PnP: nessuna soluzione — spigoli su
  più facce e almeno 6?"**.

### Causa (certa)

I mark del piatto sono tutti a `z=1.5` nel frame oggetto → **complanari**.
`pnp/estimate-dlt` (DLT lineare in coord. calibrate) è singolare su punti
complanari e ritorna `nil` — documentato nella sua stessa docstring
(`pnp.cljs:20-22` e `:92-94`): *"Requires ≥6 correspondences that are NOT
coplanar … a coplanar or under-determined set makes the normal equations
singular and returns nil."*
- "nessuna soluzione" = complanare esatto → singolare → nil.
- RMS >126 = rumore click rompe di poco la complanarità → DLT sputa posa
  spazzatura → LM converge a un minimo pessimo.

Col box funzionava perché i suoi 8 spigoli stanno su ≥2 facce (3D).

---

## Il fix da implementare: PnP planare

### Struttura del solver oggi (`src/ridley/photogrammetry/pnp.cljs`)

- `estimate-dlt [corr intr]` → posa lineare seedless, **nil se complanare/pochi
  punti**.
- `refine [corr intr seed opts]` → LM sulla riproiezione da un seme. **Gestisce
  benissimo i punti complanari** (è solo il DLT lineare a fallire). Ritorna
  `{:pose :rms-px :n :per-point}`.
- `solve-once [corr intr sigma seed]` → `start (or dlt seed)` → refine; tagga
  `:method :dlt`/`:seed`.  ← **punto d'innesto naturale**.
- `solve-pnp [corr intr opts]` → robusto: solve-once + reiezione greedy degli
  outlier. Accetta già `:seed`.

### Piano (contenuto — riusa refine + outlier)

1. **`estimate-homography [corr intr]` → posa** (nuova, in pnp.cljs):
   - Fitta il piano ai `:world` (tutti su un piano nel frame oggetto). Costruisci
     una base 2D `(u,v)` + origine sul piano; esprimi ogni `:world` come `(a,b)`.
   - Pixel → coord normalizzate (`normalized-point`, già c'è).
   - Stima l'omografia `H` (3×3, 8 DOF, ≥4 punti, DLT lineare piano-2D ↔
     immagine-normalizzata).
   - Decomponi con `K=I`: colonne `H=[h1 h2 h3]`, `λ=1/‖h1‖` (o media
     ‖h1‖,‖h2‖); `r1=λh1, r2=λh2, r3=r1×r2, t=λh3`; ortonormalizza `[r1 r2 r3]`
     (riusa `gram-schmidt-rotation` o SVD). Questa è la posa **piano→camera**;
     **componi** con la rigida **oggetto→piano** (origine + base u,v,normale) per
     ottenere **oggetto→camera** (`{:rvec :t}`, ciò che refine si aspetta).
   - **Ambiguità 2-fold** del planare: genera i due candidati (segno della
     normale), scegli quello coi punti **davanti** alla camera (profondità > 0)
     e/o RMS minore **dopo** refine.

2. **Innesto in `solve-once`**: `start (or dlt (estimate-homography corr intr)
   seed)`; `:method` = `:dlt` | `:planar` | `:seed`.

3. **Flag** (richiesta di Vincenzo — tieni vivo anche il box/DLT): opzione
   `:method` su `solve-pnp`: `:auto` (default) | `:dlt` | `:planar`.
   - `:auto` = prova DLT, se nil (o punti complanari) usa planar. Il box dà punti
     3D → DLT; il piatto dà complanari → planar; **automatico**, ma il flag
     permette di forzare.
   - `edit_acquire.cljs` `on-solve-pnp!` (~riga 1361) chiama `pnp/solve-pnp`:
     passa `:method` (o lascia `:auto`). Volendo, un test di complanarità
     esplicito (distanza max dal piano fittato < soglia, es. 1e-3·estensione) per
     decidere il ramo prima ancora di provare il DLT.

### Verifica

- **Sintetico** (aggiungi a `test/ridley/photogrammetry/pnp_test.cljs`): genera
  correspondences **complanari** da una posa camera nota (proietta punti su un
  piano); `estimate-homography` + `refine` devono recuperare la posa a <1 px.
  Copri anche la scelta del ramo dell'ambiguità (posa dietro rifiutata).
- **Regressione box**: i test PnP esistenti (non-complanari) devono restare
  verdi (il DLT resta il ramo di default per loro).
- **Gate live**: Vincenzo rifà `p` sul piatto (`test-assets/param-plate-one/`,
  `acquire-state.json` ha già i suoi click) — l'RMS deve **crollare sotto i
  14 px** del lettore.

---

## File chiave / entry point

- `src/ridley/photogrammetry/pnp.cljs` — **il grosso**: `estimate-homography` +
  innesto in `solve-once` + flag `:method` in `solve-pnp`.
- `src/ridley/photogrammetry/bridge.cljs` — `pnp-target-points` è la sorgente
  delle correspondences: i mark del piatto danno `:obj` complanari (frame
  oggetto = `box-basis(proxy-pose)`), il box dà spigoli 3D. Non serve toccarlo.
- `src/ridley/editor/edit_acquire.cljs` — `on-solve-pnp!` (~1361) chiama
  `pnp/solve-pnp`; qui si passa `:method`.
- `test/ridley/photogrammetry/pnp_test.cljs` — test planare (+ bridge_test ha
  già i 4 test di `pnp-target-points`).
- Piatto: `examples/param-acq-plate.clj` (def `piatto`, mark su `:anchors`).
- Sessione reale: `test-assets/param-plate-one/`.

---

## Come aprire la sessione col piatto (gotcha già scoperti)

- **`reset-ctx!` azzera i `def` tra valutazioni**: valuta l'esempio del piatto
  **e** `(edit-acquire "dir" {:proxy piatto})` nella **stessa** run.
- **Togli/commenta** in fondo all'esempio `(register piatto piatto)` e il
  `doseq … register-mesh!`: se no la geometria registrata si disegna sopra le
  foto (viene renderizzata dopo l'`hide-user-geometry!` del modale). Tieni i
  `def`.
- **Miglioria offerta ma NON fatta**: far accettare a `:proxy` il **nome
  registrato** (`{:proxy :piatto}`), così i mesh registrati (che sopravvivono a
  `reset-ctx!`, a differenza dei `def`) si riusano tra valutazioni separate.
  Entry: `resolve-proxy` (edit_acquire.cljs:2834) — accettare una keyword →
  lookup nel registry (`scene/registry`), preservando `:anchors`.

## Note REPL/build (IMPORTANTE — verifica del codice host)

- **La scheda browser hot-reload è STALE per il codice host**: i cambi a
  `pnp.cljs`/`bridge.cljs`/`edit_acquire.cljs` NON si verificano dal vivo nel
  REPL browser (l'hot-reload salta la tab). **Verifica via SUITE node** (compila
  fresco): `pnp` e `bridge` sono ns puri → node-testabili.
- Compila via nREPL shadow-api, **mai** `npx` in parallelo al watcher:
  `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as shadow-api])
  (shadow-api/compile :test)"` — **autoruns** la suite (~7 min). 751 test, 0 fail.
- `(shadow/repl :app)` per CLJS; `:cljs/quit` per tornare CLJ e compilare.

## Aperti / futuro (non nel task)

- **Occlusione dei mark dall'oggetto**: oggi la visibilità è solo front-face
  (`bridge/mark-front-facing?`); l'oggetto al centro copre alcuni mark → Vincenzo
  ne vede 10/12. Post-v1: test di occlusione vero.
- **Diametro del piatto parametrico** (Vincenzo, futuro): oggi `def PLATE-D`;
  renderlo argomento/fn.
- **Corona codificata per auto-detect** (dopo): stessa architettura, cambia il
  detector (centroide del blob) non il flusso.
