# Handover — P4a: `acquire` emissione + round-trip

> ## STATO 2026-07-24: **P4a-1 COSTRUITO + verificato live, NON committato**
>
> Fatto in questa sessione (branch `edit-acquire-registration-stability`, working tree):
> - **`acquire` direttiva** (`edit_acquire.cljs`): `(acquire "dir" {:proxy :pose
>   :shapes :marks})` monta il proxy in posa come scaffold (famiglia
>   image-board/mesh-board, mai CSG/export) e RITORNA `{:proxy :pose :shapes :marks
>   :dir}` — destrutturabile per nome. Helper: `apply-pose` (group-transform rigido
>   creation-pose→:pose, up ortogonalizzato), `resolve-proxy`, `default-proxy`,
>   `record-scaffolds!`.
> - **`edit-acquire` marcatore**: nuova macro in `macros.cljs`, dispatch su
>   `(string? (first args))` → dir-string apre `edit-acquire-request!` (marcatore,
>   dal pannello definizioni); prima-arg mesh → `edit-acquire-open!` (= vecchia
>   `enter!` REPL, tenuta in parallelo). `enter!` rifattorizzata: il corpo async è
>   ora `open-session!` `[proxy dir from-marker?]`, condiviso da entrambe le vie.
> - **Write-back**: `confirm!` (bottone "Conferma (OK)" + niente Invio) →
>   `emit-acquire-code` → `replace-source!` col `(acquire "dir" {:proxy (box W H D)
>   :pose {…} :shapes {} :marks {}})` (dims da `bridge/dims-from-mesh`, posa da
>   `:creation-pose`), poi `close!`+`run-definitions!`. `cancel!`/`discard!`
>   (Chiudi/Esc) → strip-head `(edit-acquire …)`→`(acquire …)`. `:from-marker?` nel
>   session sceglie cancel! (marcatore) vs close! (REPL legacy).
> - **Bindings** (`bindings.cljs`): `'acquire`, `'edit-acquire-request!`,
>   `'edit-acquire-open!`. Rimosso `'edit-acquire → enter!` (la macro possiede il
>   nome ora).
> - **BUG preso**: `prims/box-mesh` dà vertici in frame LOCALE grezzo; la `(box …)`
>   emessa = macro `box` = `pure-box` = `transform-mesh-to-turtle` (x locale→destra,
>   y→su, z→heading). `box-basis`/`dims-from-mesh` leggono QUESTA convenzione, quindi
>   `default-proxy` deve fare `apply-transform` alla posa default o le dims tornano
>   PERMUTATE. Un `(box …)` fornito dall'utente passa già dalla macro, ok.
> - **Verificato live** (Chrome via nREPL CLJS, senza geo-server): `(box 60 20 40)` +
>   posa ruotata → `acquire` → dims `[60 20 40]` conservate, posa esatta, 1 scaffold,
>   attraverso `repl/evaluate-definitions` VERO. `emit-acquire-code` pulito. La macro
>   instrada `(edit-acquire "d")`→request! (guardia stampata). `find-form-bounds` +
>   `strip-head` corretti sulla mappa-opts annidata. Le 3 bindings sono `fn?`.
> - **NON verificato = IL GATE UMANO** (serve Rust geo-server + foto vere + gizmo):
>   apertura sessione → allineamento → Conferma scrive il buffer → riapertura stessa
>   posa. `open-session!` è la vecchia `enter!` collaudata rifattorizzata, invariata.
> - Gotcha vissuto: il runtime browser via nREPL era STALE (eseguiva eval ma non
>   riceveva gli hot-swap del watcher) → le nuove fn risultavano undefined. Fix:
>   `(.reload (.-location js/window))` via eval CLJS, aspetta ~7s, re-`(shadow/repl
>   :app)`. **Ricarica la pagina prima di ogni test live.**
>
> **P4a-1 gate umano PASSATO + committato `ca0605f`. P4a-3 (shapes+marks) gate
> PASSATO 2026-07-24** — mark nominati (modalità `k`, sul piano dichiarato) →
> `:marks {:id {:position :heading :up}}`, consumati con `(turtle (:id (:marks A))
> …)`; ricalco → `:shapes {:ricalco-1 (poly …)}`. Klein 180° sui mark si raddrizza
> con `m`. Scala = mm (fissata dal box calibro); posizione ~520 mm dall'origine =
> offset di gauge (re-centraggio opzionale, da decidere con Vincenzo). Dettagli
> pieni in `memory/project_edit_acquire_p4a.md`. Poi **P4b**.
>
> ## STATO 2026-07-24: **P4a-2 (osservazioni) COMMITTATO `b55d9b3` + gate PASSATO** (Vincenzo: pick PnP restano al rientro)
>
> Sorpresa: gran parte di P4a-2 l'aveva già fatta la stabilità-registrazione —
> pose camere, badge, marcature blindate ('m' → `:marker-picks`), piani
> (`:retrace`) erano già persistiti. I buchi veri, ora chiusi in
> `edit_acquire.cljs` (save/load):
> - **Pick PnP**: a differenza dello snap 's' (che ri-deriva gli spigoli dalla
>   posa persistita), una registrazione PnP *è* i suoi click — la posa
>   round-trippava ma i pick no, quindi rientrando in 'p' i pallini erano spariti.
>   Nuova sezione `:pnp` in `acquire-state.json` = `{"idx" {:picks {ci {:px
>   :screen}} :residuals {ci r} :outliers [ci…]}}` (solo foto con pick).
> - **Focale**: nuova sezione `:focal {:mm :source}`, ripristinata DOPO il read
>   EXIF così una taratura manuale vince.
> - `apply-loaded-state!` ripristina entrambe: helper `int-keys` per le chiavi
>   intere annidate (foto + spigolo, che JSON stringa), outliers vettore→set,
>   focale source stringa→keyword.
> - Verificato: simmetria di serializzazione (int-keys annidate + set + keyword) e
>   le funzioni REALI `apply-loaded-state!`/`save-acquire-state!` (shape JSON
>   corretta, nessun throw). `start-pnp!` conserva e ridisegna i pick → al rientro
>   si rivedono. **Gate umano**: registra una foto con 'p', OK, riapri, ripremi 'p'
>   → pallini colorati + spigoli piazzati ancora lì.
>
> **Prossimo: P4a-3** — il gesto del mark NOMINATO con id (clicca un punto, dagli
> un nome, triangola su più foto → `:marks {:nome {:position :direction}}`) + i
> ricalchi retrace → `:shapes {:nome (poly …)}`, così `:shapes`/`:marks` nella
> form si popolano e si destrutturano per nome. È ciò che Vincenzo ha chiesto
> ("non dovrei potergli dare un id?"). Il tasto 'm' resta il *marcatore blindato*
> (Klein branch-lock), NON questo — sono due cose diverse.

Aperto 2026-07-24 per **continuare in una chat nuova**. Task: implementare
**P4a-1** (round-trip del proxy) di `edit-acquire`, dentro il perimetro di design
GIÀ CHIUSO. Contesto: `dev-docs/brief-param-acq-v1.md` → P4 → sezione
"**Design del palcoscenico**" (autorevole) e `dev-docs/P4a-acquire-round-trip-
draft.md` (bozza d'implementazione, già rivista con Vincenzo — leggere QUESTA per
il piano concreto).

## Stato a monte (fatto in questa sessione)

- **fix-2 (trasporto rigido) + fix-1 "blindato" (Klein branch-lock) + `f` predict-
  only**: FATTI, **gate umano PASSATO** (Vincenzo: pallino rosso sulla freccia in
  tutte le viste), **committati** sul branch `edit-acquire-registration-stability`
  (2 commit: `32e409a` primitivi P3, `96baff0` registration stability). NON
  mergiati su main (Vincenzo mergia quando vuole). Vedi
  `dev-docs/HANDOVER-edit-acquire-registration-stability.md` +
  `memory/project_edit_acquire_registration_stability.md`.
- **P3 ricalco**: **gate PASSATO** (Vincenzo ha collaudato: funziona).
- **P4a design**: perimetro CHIUSO nel brief. **P4a-1 mappato e de-rischiato** (sotto).
- **Docs P4a NON committati** (working tree del branch): `P4a-acquire-round-trip-
  draft.md`, questo handover. (Committarli o no lo decide Vincenzo.)

## Perimetro P4a (dal brief, NON riaprire)

- Forma emessa **autocontenuta**, una sola form:
  `(acquire "dir" {:proxy … :pose … :shapes {:bezel (poly …)} :marks {:vite-1 {…}}})`.
  Niente `(poly …)` sciolti. `acquire` **ritorna il valore** → destrutturazione per
  nome (`(:bezel (:shapes A))`, pattern split-tree).
- **Demarcazione**: nella form (oggetto) = proxy+pose, `:shapes`, `:marks`; nel file
  di sessione (fotografia) = pose camere, intrinseche, **osservazioni-di-mark**
  (raggi per-foto: PnP/consistenza/marcature), residui, piani.
- **Impalcatura → "caduta per inline deliberato"**: la geometria cade sostituendo
  di proposito l'accesso nominato col letterale, NON cancellando una riga.
- **Mark = primitivo unificante dei punti** (posizione+direzione+id): PnP e
  consistenza erano mark. P4a-2 salva le osservazioni-di-mark. (Esiste già `mark`
  come àncora nominata nel sistema path/shape.)
- Frustum/in-posa/pellicola = **P4b** (frustum "da collaudare"). Fuori P4a.

## IL TASK: P4a-1 (round-trip del proxy)

Crea `acquire` (minimale) + converte `edit-acquire` da funzione REPL a **marcatore**
nel buffer, con write-back del proxy. Gate: apri `(edit-acquire "dir")` dal pannello
→ allinea il proxy sulla foto 0 → OK → nel buffer compare `(acquire "dir" {:proxy …
:pose … :shapes {} :marks {}})` → riapri → stessa posa. In P4a-1 `:shapes`/`:marks`
sono vuoti (i ricalchi/mark sono P4a-2/3).

## MECCANISMO edit-X → X (già studiato — NON ri-derivare)

Modello: `image-board`/`mesh-board`. Fatti con riferimenti:

- **Macro** in `macros.cljs`: `(edit-image-board [& args] → (edit-image-board-request!
  ~@args))` (`macros.cljs:2183`). Con-arg-quotati: vedi `edit-attach`/`edit-mesh-split`
  (`macros.cljs:2092,2112`). → scrivere `(defmacro edit-acquire [dir & more]
  (edit-acquire-request! ~dir ~@more))`.
- **Bindings** in `bindings.cljs`: `'image-board shape/image-board` (`:163`),
  `'edit-image-board-request! edit-image-board/request!` (`:641`), `'mesh-board
  mesh-board/mesh-board` (`:151`). Attuale: `'edit-acquire edit-acquire/enter!`
  (`:645`, funzione REPL — TENERE in parallelo). → aggiungere `'acquire
  edit-acquire/acquire` e `'edit-acquire-request! edit-acquire/request!`.
- **request!** (`edit_image_board.cljs:468`): costruisce la forma `X` e la RITORNA;
  se `consume-skip!` → ritorna e basta; se eval-source ≠ `:definitions` → stampa
  "apri dal pannello", ritorna; altrimenti `clear-orphan!`, controlla `find-marker`,
  `modal/claim!`, `reset! session {… :entered? false}`, ritorna la forma.
- **requested?** (`edit_image_board.cljs:506`): `(and (some? @session) (not
  (:entered? @session)))`. Il runner delle definizioni la raccoglie e chiama enter.
- **find-marker** (`edit_image_board.cljs:159`): `(modal/find-form-bounds
  (cm/get-value) marker-prefix)`; `marker-prefix "(edit-image-board"` (`:28`).
- **confirm!** (`edit_image_board.cljs:424`): `(modal/replace-source! from to code)`
  poi cleanup + `modal/run-definitions!`. **cancel!** (`:446`): `(modal/replace-
  source! from to (modal/strip-head … marker-prefix "(image-board"))`.
- **replace-source!** (`modal_evaluator.cljs:264`): rimpiazza [from to) col code.
- **reference-citizen (scaffold)**: `mesh-board/record-scaffolds!` (`mesh_board.cljs:42`)
  spinge le mesh in `state/scene-accumulator :scaffolds` (viste, mai export/CSG);
  `show!` ritorna il valore (`:91`). `acquire` riusa questo per mostrare il proxy.

## Piano di scrittura P4a-1 (dalla bozza)

1. **`acquire`** (nuova fn in `edit_acquire.cljs`, `^:export`):
   ```clojure
   (defn acquire [dir {:keys [proxy pose shapes marks]}]
     (let [posed (apply-pose proxy pose)]   ; rigido default→:pose (attachment/group-transform)
       (record-scaffolds! [posed])          ; come mesh-board (reference-citizen)
       {:proxy posed :pose pose :shapes (or shapes {}) :marks (or marks {})}))
   ```
   Nota: `record-scaffolds!` è privata in mesh-board; o estrarla in un ns condiviso,
   o replicare `(swap! state/scene-accumulator update :scaffolds into meshes)`.
2. **Macro `edit-acquire`** in `macros.cljs`; bindings `'acquire`, `'edit-acquire-
   request!`. TENERE `'edit-acquire → enter!` in parallelo (uso REPL con mesh
   esplicita, `(edit-acquire (box …) "dir")`).
3. **`request!` / `requested?`** in `edit_acquire.cljs` (modello image-board):
   costruisce il valore `acquire`, apre il modale in `:definitions`. `enter!`
   esistente diventa il "mount" chiamato su `requested?` (o adattarlo).
4. **`emit-acquire`** + `confirm!`/`cancel!`: OK → `replace-source!` con `(acquire
   "dir" {:proxy (box w h d) :pose {…} :shapes {} :marks {}})`; Annulla → `strip-head`.
   Il proxy: emettere `(box w h d)` (dims da `bridge/dims-from-mesh`) + `:pose`
   dalla `:creation-pose` corrente.
5. **Re-entry**: riaprire carica `acquire-state.json` (già lo fa `load-acquire-state!`)
   + legge proxy/pose dalla form. `proxy-pose` esce dal file di sessione → va nella
   form (per ora può restare in entrambi in transizione; la demarcazione pulita è
   P4a-2).

## DETTAGLI ancora aperti (impl, non design — vedi bozza §Dettagli)

- Serializzazione osservazioni-di-mark (P4a-2), naming auto di shapes/marks.
- `acquire` in P4a valuta a `{:proxy :pose :shapes :marks}` + proxy scaffold (P4b
  aggiunge pellicola+camere).
- Come il runner-definizioni raccoglie `requested?` (vedi come lo fanno gli altri
  edit-X: cercare chi chiama `requested?`/il mount dopo l'eval).

## GOTCHA (dalla sessione — a caro prezzo)

- **REPL/build**: `edit-acquire` gira in `edit_acquire.cljs` (deps browser: viewport/
  gizmo/backdrop) → NON node-testabile; si verifica **live in Chrome** su
  `localhost:9000/index.html` (la webview Tauri ha cache ostinata). `bridge.cljs`
  invece è node-testabile. Ricompila via nREPL: `clj-nrepl-eval -p 7888 "(require
  '[shadow.cljs.devtools.api :as api]) (api/watch-compile! :app)"` → `:ok`. MAI
  `shadow-cljs compile` a mano accanto al watcher.
- **Test build lento**: `api/compile :test` è in coda dietro il watcher `:app` (contesa)
  → minuti. `pgrep -f "api/compile :test"` **auto-matcha** lo shell che lo esegue →
  loop infinito; usa un sentinel-file ("ALL DONE") non pgrep.
- **Playwright MCP** funziona: `browser_navigate localhost:9000/index.html` → runtime
  JS; var CLJS su `window.ridley.<ns_con_>.<fn_con_/BANG_>`, `window.cljs.core` (cc),
  atomo sessione `window.ridley.editor.edit_acquire.session` (deref con
  `cc.deref(ea.session)`, NON `.state`). Costruire dati CLJS: `cc.js__GT_clj(obj,
  cc.keyword("keywordize-keys"), true)`; chiavi intere via `cc.hash_map(0, …, 1, …)`.
- **Marcatore vs REPL**: gli edit-X si aprono dal **pannello definizioni (Cmd+Enter)**,
  non dal REPL (request! stampa un avviso se eval-source ≠ :definitions). Il gate
  P4a-1 va fatto scrivendo `(edit-acquire "dir")` nel buffer definizioni e premendo
  Cmd+Enter — diverso da come si testava finora (REPL con la mesh esplicita).
- **Vincenzo NON è sviluppatore quotidiano**: istruzioni operative in sezione
  **"Prossimi passi per te"** (italiano, passi numerati, nomi esatti di bottoni/
  comandi — vedi `CLAUDE.md`).

## PRIMA MOSSA nella chat nuova

1. Leggi `P4a-acquire-round-trip-draft.md` (piano) + brief §"Design del palcoscenico".
2. Trova chi raccoglie `requested?` dopo l'eval definizioni (il mount degli edit-X) —
   è l'unico pezzo del meccanismo non ancora tracciato riga-per-riga qui.
3. Scrivi `acquire` (fn+bind) come primo pezzo testabile; poi la macro + request!;
   poi emissione; gate live via Cmd+Enter dal pannello.
