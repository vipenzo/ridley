# Brief per Code: schede Reference — Batch 6 (tutto il resto)

## Obiettivo

Chiudere la Reference Functions con un unico batch finale. Coverage di:
- §2/§9 residui (anchors navigation, attach-path, link!, play-path, stretch-*)
- §7 residui (mesh utility non coperte nel batch 4)
- §13 Scene residui (hierarchical assemblies, show-turtle/hide-turtle)
- §14 Live & Interactive (intero)
- §15 AI Describe (intero)
- §16 Export (intero)
- §17 Variables, Functions, Math (consolidato)

Sesto e ultimo batch per le Functions standard. Le Internals (§18) restano fuori scope (cartella separata `internals/`).

## Riferimenti

- **Formato**: `dev-docs/reference-manual/loft.md` (scheda modello).
- **Convenzioni cartella**: `dev-docs/reference-manual/Readme.md`.
- **Source-of-truth**: `docs/Spec.md`.
- **Lingua**: inglese.
- **Destinazione**: `dev-docs/reference-manual/`.

## Convenzioni

Stesse dei batch precedenti. In più:

- **Schede consolidate per math e vec3**: una sola scheda per ciascuna famiglia, con tabella signature → descrizione. Evita ~20 mini-schede ripetitive.
- **Categorie del frontmatter**: riusare gli slug dei batch 1–5 dove pertinente. Nuovi slug solo per categorie non ancora introdotte (vedi "Categorie" sotto).
- **`parallel`** è una forma riconosciuta solo dentro `span`: documentata in `span.md`, non scheda separata.
- **Macros**: `tweak`, `anim!`, `anim-proc!`, `span` sono macro (in `macros.cljs`), non funzioni — non cambia il formato della scheda ma è bene saperlo.

## Categorie frontmatter

Riusare gli slug esistenti dove pertinente:

| Slug | Origine | Uso in batch 6 |
|------|---------|----------------|
| `turtle-movement` | batch 3 | goto, look-at, get-anchor |
| `registration-visibility` | batch 3 | show-turtle, hide-turtle |
| `positioning-assembly` | batch 4 | attach-path, link!, unlink!, play-path, stretch-* |
| `mesh-operations` | batch 4 | mesh-simplify, mesh-laplacian, merge-vertices, manifold? |

Nuovi slug:

| Slug | Spec | Schede |
|------|------|--------|
| `live-interactive` | §14 | tweak, anim!, anim-proc!, span, play!, pause!, stop!, stop-all!, seek!, anim-list, ease, pilot-request!, picking |
| `ai-describe` | §15 | describe, ai-ask, end-describe, cancel-ai, ai-status |
| `export` | §16 | export, save-mesh, save-stl, save-3mf, render-view, render-all-views, render-slice, save-views, save-image, anim-export-gif, export-manual |
| `math` | §17 | math (consolidata), vec3 (consolidata), turtle-state, console-print |

## Lista schede da scrivere

### §2/§9 Turtle & Assembly residui (8 schede)

`turtle-movement`:
1. `goto` — naviga turtle a un anchor (posizione + heading + up)
2. `look-at` — orienta turtle verso un target
3. `get-anchor` — dati di un anchor per nome

`positioning-assembly`:
4. `attach-path` — associa i mark di un path come anchor di una mesh registrata
5. `play-path` — replay path dentro `attach`/`attach!` (risolve binding capture issue)
6. `stretch` (scheda unica `stretch.md`) — `stretch-f` / `stretch-rt` / `stretch-u` body-only attach (semantica simmetrica, tabella delle tre varianti)
7. `link!` — collega figlio a genitore per animation playback (varianti: `:at`, `:from`, `:inherit-rotation`)
8. `unlink!` — rimuove link

### §7 Mesh utility residue (4 schede)

`mesh-operations`:
9. `mesh-simplify` — semplificazione mesh (quadric edge collapse)
10. `mesh-laplacian` — smoothing laplaciano
11. `merge-vertices` — merge di vertici coincidenti
12. `manifold?` — predicato manifold (a metà tra diagnostico e validazione)

### §13 Scene residui (3 schede + 1 sezione)

`registration-visibility`:
13. `show-turtle` / `hide-turtle` — turtle indicator visibility (scheda unica `turtle-visibility.md`)

`positioning-assembly` (o `registration-visibility`, scegliere coerentemente):
14. `register` (map literal) → **sezione "Hierarchical assemblies" dentro `register.md` esistente**, non scheda autonoma. La forma map dentro `with-path` è una variante d'uso di register: documentarla come sezione Variations con esempi (puppet/arm), spiegare il name-prefixing automatico, il link inference, e il prefix matching di show/hide.

### §14 Live & Interactive (13 schede)

`live-interactive`:
15. `tweak` — scheda ricca: tutte le varianti (index, keyword, `:all`, combinazioni). Una scheda, signature multipla, esempio per ciascun pattern.
16. `anim!` — animation timeline-based (spans, easing, loop modes, camera target)
17. `anim-proc!` — animation procedurale (gen-fn per frame, loop modes)
18. `span` — segmento timeline. **Includere `parallel` come sub-form** nella sezione Variations: weight, easing, `:ang-velocity`, `:on-enter`/`:on-exit`, comandi turtle ammessi, `parallel` per esecuzione simultanea.
19. `play!` — avvia (specifico o tutte)
20. `pause!` — pausa
21. `stop!` — ferma e resetta
22. `stop-all!` — ferma tutte
23. `seek!` — salta a posizione fraction
24. `anim-list` — lista animazioni con stato/tipo
25. `ease` — funzione di easing standalone (utile per espressioni custom)
26. `pilot-request!` — entry-point pilot mode (vedi `project_pilot_mode` per stato implementazione)
27. `picking` (scheda unica `picking.md`) — `selected`, `selected-mesh`, `selected-face`, `selected-name`, `source-of`, `origin-of`, `last-op`. Spec li mette in §18 Internals ma sono pubblici e citati da AI describe. Scheda unica con tabella signature → descrizione.

### §15 AI Describe (5 schede)

`ai-describe`:
28. `describe` — descrizione AI della geometria (`(describe)` o `(describe :name)`)
29. `ai-ask` — domanda follow-up nella sessione attiva
30. `end-describe` — chiudi sessione
31. `cancel-ai` — cancella chiamata AI in corso (sessione resta attiva)
32. `ai-status` — stato configurazione AI provider

### §16 Export (10 schede)

`export`:
33. `export` — STL/3MF per nome registrato. Documentare: registry vs ref, multi-target, `:3mf` trailing arg, multi-material 3MF con `color` per mesh
34. `save-mesh` — download via native picker, da mesh value (scheda madre)
35. `save-stl` — wrapper STL (stub → `save-mesh`)
36. `save-3mf` — wrapper 3MF (stub → `save-mesh`)
37. `render-view` — view ortografica/prospettica, ritorna data URL
38. `render-all-views` — 6 ortho + 1 prospettica, ritorna mappa
39. `render-slice` — sezione 2D a piano axis-aligned, ritorna data URL PNG
40. `save-views` — download ZIP di tutte le viste
41. `save-image` — download data URL come PNG
42. `anim-export-gif` — export animazione procedurale come GIF (desktop only). Documentare le opzioni `:fps`, `:duration`, `:width`, `:anim`, `:overwrite`
43. `export-manual` (scheda unica `export-manual.md`) — `export-manual`, `export-manual-en`, `export-manual-it`

### §17 Math & Utilities (4 schede consolidate)

`math`:
44. `math` (scheda unica `math.md`) — tabella unica per **tutte** le funzioni matematiche. Sezioni: Trigonometric (`sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2`), Exponentials (`exp`, `math-log`, `pow`, `sqrt`), Rounding (`floor`, `ceil`, `round`, `abs`), Comparison (`min`, `max`), Angle conversion (`to-radians`, `to-degrees`), Constants (`PI`). **Gotcha**: `math-log` è il logaritmo, `log` è il debug printer per la console browser (binding distinto, vedi `console-print.md`).
45. `vec3` (scheda unica `vec3.md`) — tabella unica per tutti i 3D vector helpers Ridley: `vec3+`, `vec3-`, `vec3*`, `vec3-dot`, `vec3-cross`, `vec3-normalize`. Tipo di ritorno (scalar per dot, vector per gli altri) in tabella.
46. `turtle-state` (scheda unica `turtle-state.md`) — introspection: `get-turtle`, `turtle-position`, `turtle-heading`, `turtle-up`, `attached?`, `last-mesh`. Tabella. Esempio: misurare distanza tra due posizioni turtle dentro un path.
47. `console-print` (scheda unica `console-print.md`) — `println`, `print`, `prn`, `log` (debug), `T` (tap label+value, restituisce valore). Una scheda. **Gotcha**: distinguere `log` (console.log) da `math-log` (Math.log).

## Schede già esistenti — verificare/auditare se serve

Non sono nuove schede ma vanno **verificate** per non lasciare buchi a fine batch. Audit veloce: signature contro Spec, esempi che funzionano, link non rotti.

Già coperte nei batch 3-4 (NON riscrivere):
- Turtle movement/rotation/arcs/bezier/pen/reset/resolution/scope: f, th, tv, tr, u, d, down, rt, lt, arc-h, arc-v, bezier-to, bezier-to-anchor, bezier-as, pen, reset, resolution, turtle, path-to, with-path
- Scene Registry/Viewport: register, show, hide, show-all, hide-all, show-only-objects, objects/registered/scene, info, bounds, dimensions, mesh, fit-camera, show-lines/hide-lines/lines-visible?, show-stamps/hide-stamps/stamps-visible?
- Panels: panel, out/append/clear, panel?
- Color & material: color, material, reset-material
- Anchors: anchors

**Eccezione**: `register.md` va **esteso** con la sezione "Hierarchical assemblies" (vedi item 14 sopra).

## Decisioni per questo batch

### Hierarchical assemblies → variations dentro register.md

Non scheda autonoma: è una forma d'uso di `register` (map literal dentro `with-path`). Aggiungerla come sezione "Variations" o "Hierarchical assemblies" in `register.md` esistente, con esempi (puppet/arm), spiegazione di:
- name-prefixing automatico (`:puppet/r-arm/upper`)
- link inference (parent attach point dedotto da `goto` precedente)
- prefix matching di `show`/`hide` (`(hide :puppet/r-arm)` nasconde tutto il sotto-albero)

### Math: una sola scheda con tabella

Per evitare 20 mini-schede da 8 righe. Tutte le funzioni matematiche in `math.md` con sezioni per famiglia (trig, exp, rounding, ecc.). Una entry in Signature, descrizione condivisa.

### Vec3: una sola scheda con tabella

Stesso pattern di `math.md`. Tutti i 6 helper in `vec3.md`. Distinguere scalar return (`vec3-dot`) da vector return (gli altri) in tabella.

### `parallel`: sub-form di `span`

Non binding autonomo, riconosciuto solo nel body di `span`. Documentato nella sezione Variations di `span.md`, non scheda separata. Esempio: confronto sequential vs parallel per `(rt 360) (u 90)` vs `(parallel (rt 360) (u 90))`.

### Picking: scheda unica raggruppata

Le 7 funzioni `selected*` / `source-of` / `origin-of` / `last-op` sono utility di debug interattivo. Esposte pubblicamente, citate da AI describe pipeline e dalla UI di picking. Una scheda `picking.md` con tabella signature → descrizione + esempio di sessione di debug.

### Stretch: una scheda per le tre varianti

`stretch-f`, `stretch-rt`, `stretch-u` hanno semantica simmetrica (asse diverso, stesso pivot, stessa regola di sign). Una scheda `stretch.md` con tabella e nota che sono **body-only** in `attach`/`attach!` (errore istruttivo al top-level che punta verso `scale`).

### Utility minori — skip

Skip dal reference manual (uso interno o troppo specifico):
- `audio-feedback?`, `set-audio-feedback!` — accessibility internals
- `desktop?`, `env` — runtime checks
- `get-camera-pose`, `get-orbit-target` — camera state (poco usabili da REPL utente)
- `mesh-status` — uso diagnostico interno

Se servono in futuro, andranno nella cartella `internals/`.

### PROGRESS.md da aggiornare

`PROGRESS.md` non riflette il batch 5 (faces/SDF/text/warp). Le schede esistono ma non sono tracciate. **Aggiungere sezione "Batch 5"** prima di iniziare batch 6, e aggiungere "Batch 6" man mano che le schede vengono scritte. Questo permette di verificare lo stato in modo univoco.

## Conteggio

**Schede nuove da scrivere: ~37** (le schede consolidate `math`/`vec3`/`turtle-state`/`console-print`/`picking`/`stretch`/`export-manual`/`turtle-visibility` valgono 1 ciascuna).

Più 1 sezione da aggiungere a `register.md` (Hierarchical assemblies).

Più audit veloce delle ~70 schede esistenti per Turtle/Scene.

## Ordine di lavoro suggerito

1. **PROGRESS.md update** — chiudere batch 5 nel tracker
2. **§7 Mesh utility residue** (4) — riscaldamento, schede simili al batch 4
3. **§2/§9 Turtle/Assembly residui** (8) — goto, look-at, ecc.
4. **§13 register.md extension** + show-turtle/hide-turtle
5. **§17 Math & Utilities** (4 schede consolidate) — math, vec3, turtle-state, console-print
6. **§16 Export** (10)
7. **§15 AI Describe** (5)
8. **§14 Live & Interactive** (13) — la più corposa, tweak/anim/anim-proc ricche

## Test di verifica

Come nei batch precedenti:
- `name` del frontmatter = simbolo Ridley
- `category` coerente (vedi tabella Categorie sopra)
- Signature verificata contro Spec.md
- Esempio autocontenuto
- Cross-reference corretti
- PROGRESS.md aggiornato con la nuova scheda
