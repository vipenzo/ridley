# Changelog 1.10.0 → 3.0.0

**Intervallo:** `v1.10.0` (2026-03-25) → `HEAD` / 3.0.0 (2026-06-10)
**Tag intermedie attraversate:** v1.11.0, v2.0.0, v2.0.1
**Linguaggio:** cosa può fare ora l'utente che prima non poteva (non commit-per-commit).
⭐ = candidate di punta per l'annuncio.

---

## Piattaforma

- ⭐ **App desktop nativa** — Ridley gira come applicazione macOS standalone (Tauri v2), non più solo nel browser. Build DMG via CI e installazione via Homebrew cask.
- **CSG nativo Manifold** sul desktop (poi consolidato in un'architettura **SCI-only**: tutta la valutazione del DSL avviene in-app, browser o desktop, senza processi esterni).
- ⭐ **Export 3MF** oltre allo STL, con **3MF multimateriale** (emette `model_settings.config` per Bambu/Orca Studio). Dialog di salvataggio nativo con scelta formato.
- **anim-export-gif** (solo desktop) — esporta una GIF dalle animazioni procedurali (`anim-proc!`), con tutte le anim guidate in lockstep.
- La finestra desktop ricorda posizione e dimensione tra un avvio e l'altro.
- Export STL/3MF più robusto: solidify del mesh prima dell'esportazione, dedup dei vertici, niente bordi non-manifold.

## Workspace e organizzazione

- ⭐ **Sistema Workspace** — documenti come workspace di prima classe, con Save/Open workspace-aware e dialog di file nativi (niente più clobber dell'editor).
- **Sistema librerie** — pannello librerie con install/delete; persistenza su filesystem (`~/.ridley/libraries/`) sul desktop e su localStorage sul web.
- **Librerie builtin auto-installate**: `puppet`, `gears`, `weave` e la libreria **Multiboard** (tile parametriche complete).

## Modellazione (DSL)

- ⭐ **embroid** — perfora una parete sottile estrusa con pattern honeycomb / voronoi / custom.
- ⭐ **SDF (modellazione implicita) completo da SCI**, senza backend esterno: `sdf-formula`, superfici **TPMS**, `sdf-revolve`, `sdf-rounded-box`, `sdf-slats` / `sdf-bars` / `sdf-grid`, `sdf-bar-cage`, `sdf-half-space`, `sdf-clip`, `sdf-cone`, `+torus` / `+blend-difference`, `project-mesh`. SDF e mesh sono interscambiabili nelle booleane e in `attach`.
- ⭐ **Pipeline `transform->`** — `extrude+` / `revolve+` restituiscono `{:mesh :end-face}` per concatenare estrusioni e rivoluzioni in catena.
- ⭐ **Profile marks → anchors** — i `mark` posati su un path usato come profilo (path-to-shape / stroke-shape / embroid) diventano automaticamente `:anchors` del mesh via extrude/loft/revolve.
- **mark / mark-anchors / mark-pos** — segnaposto mesh-local che fanno da ancore per l'assemblaggio; `mark-pos` restituisce la posizione 3D.
- **Le operazioni booleane preservano gli anchors** attraverso union/difference/intersection.
- **on-anchors** — istanzia geometria su ogni anchor di un mesh.
- **lay-flat** — appoggia una faccia scelta sul piano di stampa; accetta anche un path.
- **slice-mesh `:on`** e composizione `:on` morph-aware.
- **Face selection a query** — `find-faces`, `face-at`, `face-nearest`, `face-shape` (estrae il bordo di una faccia come shape 2D e posiziona la turtle).
- **revolve** più capace — `:pivot`, `:end-face`, auto-clip per shape che attraversano l'asse, auto-clear dell'asse per shape-fn, angolo clampato a ±360.
- **Testo** — `text-shape` con `:center`, `extrude-text` / `text-on-path` che restituiscono un mesh combinato, `text-heightmap` (rilievo del testo con anti-alias), glyph compositi, modulo `fonts`.
- **Curve avanzate** — `reflect` / `reverse` / `mirror-path`, `bezier-as :control`, tensioni sulle bezier, `bezier-to :local`, `mid` / `seg-mid`.
- **shell** — stili voronoi e lattice con `:softness` (aperture smussate via isocontour), `:invert?`, `:margin`, `hole-segments`.
- **Trasformazioni polimorfiche** — `translate` / `scale` / `rotate` operano su mesh, SDF e shape 2D (assi mondo, pivot sulla creation-pose).
- **shape booleane variadic** + forma vettoriale, allineate alle booleane sui mesh.
- **Navigazione turtle** — `side-trip`, `move-to :align`, `anchors-from-path`, accesso ergonomico agli anchor dei path.
- **2D** — `bounds` accetta shape e path (con `:size` / `:center`), misurazione 2D.
- **Mesh utils** — voronoi, `mesh-smooth` / `mesh-simplify` / `mesh-laplacian`, `merge-vertices`.
- **material** per-mesh con kwargs (`(material :name :opacity 0.5)`).

## Interattività

- ⭐ **edit-bezier** — editing interattivo delle curve con slider di tensione e anchor mode.
- ⭐ **pilot mode** — posizionamento interattivo di mesh e SDF da tastiera.
- **tweak mode** — slider interattivi sui parametri numerici dello script, con zoom di precisione e OK/Cancel.
- **Modal evaluator layer** — infrastruttura comune di re-eval live per edit-bezier / tweak / pilot.
- **Righelli e marks interattivi** — `add-mark`, `ruler :at`, anchor rulers, righelli 2D.
- **Viewport** — `TrackballControls`, angolo di reset-view configurabile, headlight della camera + ring lights configurabili, colori `:bg` / `:fg` da pannello, dropdown sorgente turtle.
- **Settings UI** — knob di default unificati attorno a `(resolution …)`.

## Manuale e documentazione

- ⭐ **Manuale in-app bilingue (IT/EN)** — renderer markdown con pulsanti **Run** sugli esempi, fallback bilingue, conferma di Edit.
- **Reference browser in-app** — tooltip sui simboli, auto-link dei simboli del Reference nella prosa, cross-link tra guide e schede.
- **Struttura del manuale** come unica sorgente di verità (`structure.cljs`): albero di navigazione, livelli, how-to-read.

## Fix rilevanti (percepibili dall'utente)

- Cap winding corretto per estrusioni con fori (risolve mesh non-manifold), in avanti e all'indietro.
- revolve: pole vertices e bordi non-manifold in export STL risolti.
- loft: seam-weld dei sub-mesh così i path con angoli vengono manifold.
- `extrude-from-path`: regressione sull'inversione a U corretta.
- WASM Manifold: niente più errore "deleted object" nelle catene CSG.
- STL import ricentrato sull'origine + warning per import con dimensioni molto piccole.
- `arc-h` / `arc-v` e `capped`: errori guida su raggio negativo invece di geometria silenziosamente sbagliata.

---

## ⚠️ Breaking change (da dichiarare nell'annuncio)

1. **`cone` / `sdf-cone`** — l'ordine degli argomenti del raggio è stato invertito per seguire il reading order di `loft` (commit marcato BREAKING). Gli script esistenti che usano `cone` con due raggi vanno aggiornati.
2. **Trasformazioni 2D rinominate** — i transform specifici delle shape sono ora `*-shape` (`translate-shape`, `scale-shape`, `rotate-shape`), mentre `translate` / `scale` / `rotate` sono diventati polimorfici (mesh/SDF/shape) e operano su assi mondo con pivot sulla creation-pose. Codice 1.10 che assumeva la vecchia semantica di `translate`/`scale`/`rotate` sulle shape va rivisto.
3. **Semantica `cp-*` invertita** — nel creation-pose-shifting l'anchor ora resta fisso e la geometria scorre sotto (prima era il contrario).
4. **`revolve`** — l'angolo è ora clampato a ±360°; sweep oltre un giro non si auto-sovrappongono più.

> Nota: l'introduzione e poi rimozione del sidecar JVM e del backend Manifold nativo via HTTP è avvenuta interamente dentro questo intervallo, quindi è trasparente per chi veniva dalla 1.10.0 (nessuna feature pubblica persa). L'effetto netto è l'architettura **SCI-only**.

---

## Dato grezzo

- **284 commit** da `v1.10.0` a `HEAD`.
- **Intervallo date:** 2026-03-26 → 2026-06-10 (~2,5 mesi).
- Salto di due major: 1.10 → 1.11 (12 mag) → 2.0 (3 giu, manuale v1 + reference) → 3.0 (10 giu, curve authoring + workspace + tooling modale).
