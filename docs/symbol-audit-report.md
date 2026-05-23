# Symbol Audit Report — Spec↔codice

**Data:** 2026-05-16
**Brief:** `dev-docs/brief-audit-spec.md` (Task 1)
**Piano di riferimento:** `docs/manual-redesign-plan.md` §6.7 (criterio editoriale), §6.8 (sotto-categorie Internals)
**Input:** `/tmp/orphans.txt` (204 simboli bound in SCI ma non citati in `docs/Spec.md`)

## Intro

Questo report classifica i simboli sospetti (cioè quelli che superano il pre-filtro di plumbing evidente) in una delle tre destinazioni del piano:

- **A** — Spec + Reference standard (Functions): materia ordinaria di scrittura Ridley.
- **B** — Spec + Reference Internals: API per chi *estende* Ridley.
- **C** — Allowlist: plumbing esposto a SCI per ragioni tecniche ma non parte del linguaggio.

I simboli ambigui sono marcati **da chiarire** con una domanda specifica per Vincenzo in coda al report.

**Pre-filtro applicato.** Sui 204 orfani sono stati esclusi (come plumbing evidente, prima di classificare):

- 39 simboli con suffisso `-impl` (implementazioni di macro)
- 11 simboli con prefisso `pure-` (shadow funzioni pure)
- 17 simboli con prefisso `rec-` (recording helpers)
- 24 simboli con prefisso `turtle-` (namespace turtle low-level, già esposti come `f`/`th`/`tv`/...)
- 3 dynamic vars `*…*` (`*eval-source*`, `*eval-text*`, `*turtle-state*`)
- 6 costruttori-helper evidenti (`make-recorder`, `make-shape`, `make-turtle`, `recording-turtle`, `shape-from-recording`, `replay-path-to-recording`, `shape-rec-f`, `shape-rec-th`)

Rimangono **107 simboli sospetti** (un po' più dei ~70 stimati dal brief: la differenza viene dal fatto che il pre-filtro è strettamente sintattico, mentre la stima nel piano era già parzialmente classificata).

## Tabella di classificazione

| Simbolo | Destinazione | Sotto-categoria (se B) | Note |
|---|---|---|---|
| `add-mesh!` | B | Registry pattern | `scene/registry.cljs:99`. Aggiunge mesh anonima alla scena. Diverso da `register-mesh!` (named). User-facing per chi mostra mesh senza nome. |
| `add-source` | B | Source tracking & metaprogramming | `editor/bindings.cljs:49`. Append a `:source-history` di una mesh. Hook per metaprogrammazione. |
| `all-meshes-info` | B | Registry pattern (introspection) | `scene/registry.cljs:338`. Introspezione dello stato della scena. |
| `anim-clear-all!` | B | Animation API | `anim/core.cljs`. Reset animazioni. Esposto a SCI come internals dei macro `anim!`/`span`. |
| `anim-make-cmd` | B | Animation API | Helper di costruzione comando animazione. Pubblicato per macro consumer. |
| `anim-make-span` | B | Animation API | Helper di costruzione span. Pubblicato per macro consumer. |
| `anim-preprocess` | B | Animation API | `anim/preprocess.cljs`. Step di preprocessing animazione. |
| `anim-proc-register!` | B | Animation API | Registra animazione procedurale (callback-based). |
| `anim-register!` | B | Animation API | Registra animazione (entry point del macro `anim!`). |
| `anonymous-count` | C | — | Diagnostica interna: numero di mesh anonime. Plumbing. |
| `anonymous-meshes` | C | — | Diagnostica interna: dump delle mesh anonime. Plumbing. |
| `audio-feedback?` | B | Runtime settings | `settings.cljs:321`. Getter accessibility. |
| `chamfer-edges` | A | — | `editor/impl.cljs` (chamfer-edges-impl) — operatore mesh user-facing. Già nel piano A. |
| `chamfer-prisms` | A | — | `geometry/faces.cljs`. Operatore mesh user-facing. Già nel piano A. |
| `claim-mesh!` | **da chiarire** | — | `scene/registry.cljs:114`. Marca una mesh come "già registrata" perché `set-definition-meshes!` non duplichi. È interno al meccanismo di `register`. Domanda: utile dall'utente o solo dal macro `register`? Sospetto C. |
| `clear-collisions` | B | Collisions & pilot | `anim/core.cljs`. Pulisce tutti i collision handler registrati. |
| `decode-mesh` | A | — | `library/stl.cljs`. Decode base64 → mesh, user-facing per import. Già nel piano A. |
| `desktop?` | B | Runtime settings | `env.cljs`. Predicato per ramificare codice desktop vs webapp. |
| `down` | A | — | `editor/implicit.cljs`. Alias di `d` (turtle down). Già nel piano A. |
| `env` | B | Runtime settings | `env.cljs`. Restituisce `:desktop` o `:webapp`. |
| `extrude-text` | A | — | `editor/text_ops.cljs`. Operatore user-facing testo→mesh. Già nel piano A. |
| `extrude-y` | A | — | `geometry/operations.cljs:89`. Convenience extrude lungo Y. User-facing. |
| `extrude-z` | A | — | `geometry/operations.cljs:84`. Convenience extrude lungo Z. User-facing. |
| `find-sharp-edges` | A | — | `geometry/faces.cljs`. Operatore diagnostica mesh. Già nel piano A. |
| `get-camera-pose` | B | Animation API | `viewport/core.cljs:1068`. Snapshot stato camera. Usato in animazioni / scripting. |
| `get-mesh` | B | Registry pattern (introspection) | `scene/registry.cljs:307`. Lookup mesh per nome. Già nel piano B. |
| `get-orbit-target` | B | Animation API | `viewport/core.cljs:1082`. Snapshot target orbita. Usato in animazioni. |
| `get-panel` | B | Registry pattern (introspection) | `scene/registry.cljs:381`. Lookup pannello per nome. |
| `get-path` | B | Registry pattern (introspection) | `scene/registry.cljs`. Lookup path per nome. Già nel piano B. |
| `get-shape` | B | Registry pattern (introspection) | `scene/registry.cljs`. Lookup shape per nome. Già nel piano B. |
| `get-source-form` | B | Source tracking & metaprogramming | `scene/registry.cljs:318`. Recupera la quoted form sorgente di una mesh registrata. |
| `get-turtle-joint-mode` | B | Selection & visibility | `editor/state.cljs`. Introspezione turtle (joint mode corrente). Già nel piano B. |
| `get-turtle-resolution` | B | Selection & visibility | `editor/state.cljs`. Introspezione turtle (resolution corrente). Già nel piano B. NB: la sotto-categoria "Selection & visibility" è una forzatura — vedi pattern in coda. |
| `hide-all!` | B | Selection & visibility | `scene/registry.cljs:272`. Visibilità globale. |
| `hide-mesh!` | B | Selection & visibility | `scene/registry.cljs:242`. Visibilità per nome. |
| `hide-mesh-ref!` | B | Selection & visibility | `scene/registry.cljs:258`. Visibilità per referenza. |
| `hide-panel!` | B | Selection & visibility | `scene/registry.cljs:401`. Visibilità pannello. |
| `hide-turtle` | B | Selection & visibility | `editor/bindings.cljs:415`. Nasconde indicator turtle nel viewport. Già nel piano B. |
| `init-turtle` | C | — | `editor/state.cljs:64`. Init del turtle var (boilerplate del macro `turtle`). Plumbing. Già nel piano C. |
| `last-op` | B | Selection & visibility | `viewport/core.cljs`. Ultimo operatore selezionato via picking. Già nel piano B. |
| `lay-flat` | A | — | `editor/impl.cljs` (lay-flat-impl). Operatore mesh user-facing. Già nel piano A. |
| `list-collisions` | B | Collisions & pilot | `anim/core.cljs`. Lista collision handler attivi. |
| `off-collide` | B | Collisions & pilot | `anim/core.cljs` (unregister-collision!). Disconnette collision. |
| `on-collide` | B | Collisions & pilot | `anim/core.cljs` (register-collision!). Registra collision callback. |
| `ops-extrude` | C | — | Alias legacy di `extrude` da `ops` namespace. Esposto per migrazione vecchio codice. Plumbing. Già nel piano C. |
| `ops-loft` | C | — | Alias legacy di `loft` da `ops` namespace. Plumbing. Già nel piano C. |
| `origin-of` | B | Selection & visibility | `viewport/core.cljs` (picking). Recupera origine della selezione corrente. Già nel piano B. |
| `panel?` | A | — | `scene/panel.cljs:50`. Predicato user-facing per pannelli (consistente con `shape?`/`path?`/`manifold?`). |
| `path-` | C | — | Costruttore interno path (impl detail di `quick-path`). Plumbing. Già nel piano C. |
| `path-from-recorder` | C | — | `turtle/core.cljs:1304`. Helper che estrae path da recorder. Plumbing del path-macro. |
| `path-names` | B | Registry pattern (introspection) | `scene/registry.cljs`. Lista nomi path registrati. Già nel piano B. |
| `path?` | A | — | `turtle/core.cljs`. Predicato user-facing. Già nel piano A. |
| `pen-down` | A | — | `editor/implicit.cljs:133`. Comando turtle user-facing (estrusione path). |
| `pen-up` | A | — | `editor/implicit.cljs:130`. Comando turtle user-facing. |
| `perf-now` | C | — | `editor/bindings.cljs:361`. `js/performance.now()` per macro `bench`. Plumbing. Già nel piano C. |
| `pilot-request!` | B | Collisions & pilot | `editor/pilot_mode.cljs:525`. Entry point pilot mode (positioning interattivo). Già nel piano B. |
| `pr` | C | — | `editor/bindings.cljs:499`. Clojure-style print captured. Esposto per parità con Clojure, ma `println`/`print` sono in Spec; `pr` resta plumbing. Già nel piano C come "Debug interno". |
| `print-bench` | C | — | `editor/bindings.cljs:362`. Helper del macro `bench`. Plumbing. Già nel piano C. |
| `refresh-viewport!` | B | Selection & visibility | `scene/registry.cljs:491`. Forza refresh viewport. Esposto per script che modificano la scena fuori dal flusso normale. |
| `register-mesh!` | B | Registry pattern | `scene/registry.cljs:136`. Already in piano B. |
| `register-panel!` | B | Registry pattern | `scene/registry.cljs:367`. Already in piano B. |
| `register-path!` | B | Registry pattern | `scene/registry.cljs:84`. Already in piano B. |
| `register-shape!` | B | Registry pattern | `scene/registry.cljs:432`. Already in piano B. |
| `register-value!` | B | Registry pattern | `scene/registry.cljs:455`. Already in piano B. |
| `registered-names` | B | Registry pattern (introspection) | `scene/registry.cljs:302`. Lista nomi mesh registrate. Already in piano B. |
| `reset-collide` | B | Collisions & pilot | `anim/core.cljs`. Reset stato di un collision handler. |
| `resolve-and-merge-marks*` | C | — | `editor/implicit.cljs`. Helper interno del macro `with-path`. Suffisso `*` indica anchor-support. Plumbing. Già nel piano C. |
| `restore-anchors*` | C | — | `editor/implicit.cljs`. Helper interno macro `with-path`/`anchor`. Plumbing. Già nel piano C. |
| `run-definitions!` | B | Runtime settings | `editor/bindings.cljs:635`. Trigger del "Run definitions" pulsante via REPL (accessibility). Già nel piano B. |
| `save-3mf` | A | — | `export/stl.cljs`. Export user-facing. Già nel piano A. |
| `save-anchors*` | C | — | `editor/implicit.cljs`. Helper interno macro anchor. Plumbing. Già nel piano C. |
| `save-mesh` | A | — | `export/stl.cljs`. Export user-facing. Già nel piano A. |
| `save-stl` | A | — | `export/stl.cljs`. Export user-facing. Già nel piano A. |
| `sdf-ensure-mesh` | A | — | `manifold/core.cljs`. Operatore SDF user-facing (override risoluzione/bounds). Già nel piano A. |
| `sdf-node?` | A | — | `sdf/core.cljs`. Predicato user-facing. Già nel piano A. |
| `selected` | B | Selection & visibility | `viewport/core.cljs`. Selezione corrente (picking). Già nel piano B. |
| `selected-face` | B | Selection & visibility | `viewport/core.cljs`. Faccia selezionata. Già nel piano B. |
| `selected-mesh` | B | Selection & visibility | `viewport/core.cljs`. Mesh selezionata. Già nel piano B. |
| `selected-name` | B | Selection & visibility | `viewport/core.cljs`. Nome registrato della selezione. Già nel piano B. |
| `set-audio-feedback!` | B | Runtime settings | `settings.cljs:326`. Setter accessibility. |
| `set-creation-pose` | C | — | `editor/bindings.cljs:89` (form a 1 o 2 args). User-facing nei macro `attach!`/`set-creation-pose!`. **NB:** il piano già lo classifica C (§6.7), ma il nome non porta suffisso `-impl` ed è chiamabile direttamente. Mantengo C secondo il piano: la versione user-facing è il macro `set-creation-pose!` (con `!`), non questa funzione plain. |
| `set-source-form!` | B | Source tracking & metaprogramming | `scene/registry.cljs:312`. Setter del source-form (usato dal macro `register`). |
| `shape-bridge` | A | — | `clipper/core.cljs:335`. Operatore shape 2D user-facing. Già nel piano A. |
| `shape-names` | B | Registry pattern (introspection) | `scene/registry.cljs`. Lista nomi shape registrate. Già nel piano B. |
| `shape?` | A | — | `turtle/shape.cljs`. Predicato user-facing. Già nel piano A. |
| `show-all!` | B | Selection & visibility | `scene/registry.cljs:265`. |
| `show-mesh!` | B | Selection & visibility | `scene/registry.cljs:233`. |
| `show-mesh-ref!` | B | Selection & visibility | `scene/registry.cljs:251`. |
| `show-only-registered!` | B | Selection & visibility | `scene/registry.cljs:279`. |
| `show-panel!` | B | Selection & visibility | `scene/registry.cljs:394`. |
| `show-turtle` | B | Selection & visibility | `editor/bindings.cljs:397`. Mostra indicator turtle. Già nel piano B. |
| `slice-at-plane` | A | — | `manifold/core.cljs:752`. Operatore mesh user-facing (taglio piano). Già nel piano A. |
| `source-of` | B | Source tracking & metaprogramming | `viewport/core.cljs` (picking). Source-history della selezione. Già nel piano B. |
| `source-ref` | B | Source tracking & metaprogramming | `editor/bindings.cljs:56`. Estrae ref compatta dal source-history. Già nel piano B. |
| `svg-shapes` | A | — | `library/svg.cljs`. Import SVG user-facing. Già nel piano A. |
| `text-width` | A | — | `turtle/text.cljs`. Misura testo, user-facing. Già nel piano A. |
| `transform` | A | — | `editor/bindings.cljs:363` (= `turtle/transform-mesh`). Già nel piano A come alias utile. |
| `tweak-start!` | B | Selection & visibility | `editor/test_mode.cljs`. Avvia modalità tweak interattivo. Già nel piano B. |
| `tweak-start-registered!` | B | Selection & visibility | `editor/test_mode.cljs`. Variante per mesh registrate. Già nel piano B. |
| `vec3*` | A | — | `turtle/core.cljs` (v*). Math util user-facing per coord 3D. Pattern coerente con `vec3+`/`vec3-`/`vec3-dot`/... Vedi nota pattern in coda. |
| `vec3+` | A | — | `turtle/core.cljs` (v+). Math util. |
| `vec3-` | A | — | `turtle/core.cljs` (v-). Math util. |
| `vec3-cross` | A | — | `turtle/core.cljs` (cross). Math util. |
| `vec3-dot` | A | — | `turtle/core.cljs` (dot). Math util. |
| `vec3-normalize` | A | — | `turtle/core.cljs` (normalize). Math util. |
| `visible-meshes` | B | Registry pattern (introspection) | `scene/registry.cljs:291`. Già nel piano B. |
| `visible-names` | B | Registry pattern (introspection) | `scene/registry.cljs:286`. Già nel piano B. |

## Riepilogo numerico

| Destinazione | Conteggio |
|---|---:|
| **A** — Reference standard | 30 |
| **B** — Reference Internals | 62 |
| **C** — Allowlist | 14 |
| **Da chiarire** | 1 |
| **Totale** | 107 |

Distribuzione delle 62 B per sotto-categoria di §6.8:

| Sotto-categoria | Conteggio | Esempi |
|---|---:|---|
| Selection & visibility | 22 | `selected`, `show-mesh!`, `hide-all!`, `show-turtle`, `tweak-start!`, ... |
| Registry pattern (introspection) | 10 | `get-mesh`, `get-path`, `get-shape`, `registered-names`, `all-meshes-info`, ... |
| Animation API | 8 | `anim-register!`, `anim-make-cmd`, `get-camera-pose`, `get-orbit-target`, ... |
| Collisions & pilot | 6 | `on-collide`, `off-collide`, `reset-collide`, `pilot-request!`, ... |
| Registry pattern | 6 | `register-mesh!`, `register-path!`, `register-shape!`, `register-value!`, `register-panel!`, `add-mesh!` |
| Source tracking & metaprogramming | 5 | `add-source`, `source-of`, `source-ref`, `get-source-form`, `set-source-form!` |
| Runtime settings | 5 | `desktop?`, `env`, `audio-feedback?`, `set-audio-feedback!`, `run-definitions!` |
| Contracts | 0 | (le shape-fn/thickness-fn non emergono dall'audit perché sono macro-driven) |

Nota: in tabella la sotto-categoria "Registry pattern" e "Registry pattern (introspection)" sono qui distinte per chiarezza (i `register-X!` mutano lo stato, i `get-X`/`*-names` lo leggono); nel piano §6.8 è una sola sotto-categoria che le include entrambe come *register* + *introspection* — totale 16.

Nota: la sotto-categoria **Selection & visibility** ha assorbito anche `get-turtle-resolution`, `get-turtle-joint-mode`, `refresh-viewport!`, `show-turtle`/`hide-turtle` perché tutte e tre toccano lo stato della scena/turtle visibile. Una sotto-categoria "Turtle introspection" più stretta potrebbe staccare gli ultimi due simboli. Non l'ho proposta perché solo 2 simboli — vedi "Pattern emersi".

## Da chiarire

- `claim-mesh!`: l'utente normale lo chiama mai? Il codice (`scene/registry.cljs:114-117`) lo descrive come "marca una mesh come claimed-by-register, così `set-definition-meshes!` non aggiunge un duplicato". È evidentemente un'API interna del meccanismo di `register`, ma è bound in SCI senza essere prefissato. Sospetto C (Allowlist), ma chiedo conferma: è mai documentabile come pattern per chi scrive un macro register-like? Se sì → B/Registry. Se no → C.

## Pattern emersi

1. **Tutti i `register-X!` sono B/Registry pattern.** Cinque simboli (`register-mesh!`, `register-path!`, `register-shape!`, `register-value!`, `register-panel!`), una sotto-categoria pulita.

2. **Tutti i `get-X` di lookup nel registry sono B/Registry pattern (introspection).** `get-mesh`, `get-path`, `get-shape`, `get-panel`, più gli aggregati `registered-names`, `path-names`, `shape-names`, `visible-names`, `visible-meshes`, `all-meshes-info`.

3. **Tutti i `show-X!`/`hide-X!` (mesh, panel, turtle, all) sono B/Selection & visibility.** Otto simboli, simmetria perfetta. Stessa sotto-categoria assorbe anche le selection-by-picking (`selected*`, `origin-of`, `last-op`) — semanticamente diversi ma operativamente legati alla domanda "su quale mesh sto lavorando".

4. **Tutti gli `anim-*` (5 simboli con prefisso) + `get-camera-pose`/`get-orbit-target` sono B/Animation API.** Hooks per macro animation. Il prefisso `anim-` è già auto-classificante.

5. **I 6 `vec3-*` sono A/utility math.** Funzioni vettoriali pubblicate per uso utente — gemelle dei `Math/*` (sin/cos/...) già esposti. Senza dubbio A.

6. **I tre `*-anchors*` con suffisso star sono C/plumbing macro.** Helper del macro `with-path`/`anchor`. Il suffisso `*` segnala "support function".

7. **`pen-up`/`pen-down`/`down` sono A/turtle, ma erano stati persi nell'audit precedente.** Sono semplici comandi turtle user-facing su cui Spec.md ha probabilmente già `d`, `pen`, ma non gli alias.

8. **`extrude-y` / `extrude-z` sono A ma sono convenience trascurabili.** Forse Spec.md le menziona già sotto `extrude`; verificare al prossimo task (aggiornamento Spec.md).

9. **Asimmetria con `panel?`.** Predicati simili (`shape?`, `path?`, `manifold?`, `sdf-node?`) sono già in Spec o classificati A. `panel?` resta orfano: stessa famiglia, stessa destinazione → A.

10. **Decisione importante presa autonomamente.** Ho marcato `set-creation-pose` (la forma plain, non `set-creation-pose!`) come C: nel piano §6.7 era già negli "Esempi C — Init: `init-turtle`, `set-creation-pose`". Mantengo. Razionale: la versione documentabile è il macro con `!` (assegnazione di pose a mesh registrata), non questa funzione bound che è invocata dal macro stesso.

11. **`refresh-viewport!` è B ma è un'isola.** Non rientra cleanly in nessuna sotto-categoria di §6.8 — l'ho messa in "Selection & visibility" come compromesso, ma è più "scene management". Se emergono altri 2-3 simboli simili (es. da future iterazioni), potrebbe nascere una sotto-categoria "Scene management".

12. **Source tracking & metaprogramming è una sotto-categoria piccola ma coerente (5 simboli):** tutti girano attorno alla coppia `source-history` (per-mesh log di operazioni) + `source-form` (quoted form della definizione). Compongono un'unica feature.
