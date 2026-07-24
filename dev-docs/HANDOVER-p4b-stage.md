# Handover — P4b: il palcoscenico non-modale (frustum / foto in posa / pellicola)

> **STATO 2026-07-24 — FETTA 1 FATTA + GATE PASSATO (branch, da committare/committata).**
> La fetta a basso rischio è viva e collaudata da Vincenzo: **camera libera + vai
> in posa** (bottone "Palcoscenico" → orbita l'oggetto; clic miniatura → la camera
> vola nella posa, sfondo full-res, geometria sopra; Esc/orbita → libero). Chiavi:
> `enter-stage!`/`leave-stage!`/`go-in-pose!`/`leave-pose!`/`toggle-stage!` in
> `edit_acquire.cljs`; helper `viewport/free-camera-at-pivot!` (no-salto) e
> `backdrop/set-visible!`. Rifiniture dai gate live: **oggetto ancorato alla turtle**
> (`reanchor-to-build-pose!`, WYSIWYG), **pallini ripuliti**, **back-face culling** dei
> ricalchi (attiva-in-editing sempre visibile), **guardia `plausible-hit?`** sui click
> lontani. Dettaglio riga-per-riga nel brief §P4b-FETTA-1 e in `memory/project_edit_acquire_p4b.md`.
> **RESTA di P4b**: (2) **frustum-nel-mondo** — lo strato la cui ergonomia è
> dichiaratamente DA COLLAUDARE (fallback: solo pellicola + toggle); (3) la
> **ricchezza di edit-path-2d in posa** (bezier/archi, non solo la polilinea).
> Il resto di questo handover (design, entry points, gotcha) resta valido.
>
> **AGGIORNAMENTO stessa data — FRUSTUM + RADDRIZZAMENTO + Esc FATTI + GATE PASSATO:**
> **(1) Frustum-nel-mondo** — camere registrate come piramidi-ghost (corrente ciano),
> sotto toggle `:show-frustums?`; all'ingresso la camera arretra per inquadrare
> l'anello (`viewport/frame-camera!`). Niente miniatura/click ancora (fase B se
> convince — Vincenzo: "può andare"). **(2) Raddrizzamento standard**
> (`canonicalize-orientation!`, all'apertura E in `confirm!`): se c'è un anello di
> camere pulito, ri-descrive rigidamente tutto → asse giradischi verticale = +Z,
> oggetto assi-allineato, `up = dimensione verticale`, emette `(box 20.2 40.1 60.2)`;
> permutazione ciclica (nessuno specchio), shapes/mark/piani re-espressi, idempotente,
> guardia (≥4 camere + anello dominante) con fallback traslazione. **(3) Esc sicuro**:
> al livello base non chiude più (uscita = bottone "Chiudi"). Dettaglio nel brief e in
> `memory/project_edit_acquire_p4b.md`. **RESTA**: ergonomia frustum (miniatura/click
> o solo-pellicola) + edit-path-2d in posa.

Aperto 2026-07-24 per **continuare in una chat nuova**. Prerequisito **P4a
COMPLETO e committato** (sotto). P4b è l'ultimo blocco del design di
`dev-docs/brief-param-acq-v1.md` → P4 → "**Design del palcoscenico**"
(autorevole; NON riaprire il perimetro). Attenzione: **una parte di P4b è
dichiaratamente DA COLLAUDARE, non decisa** (l'ergonomia dei frustum) — vedi
§Design aperto.

## Stato a monte — P4a FATTO + committato

Branch `edit-acquire-registration-stability` (non mergiato su main; Vincenzo
mergia quando vuole). Working tree pulito. Il round-trip `acquire` è **completo,
collaudato da Vincenzo su dati reali, e leggibile**. Commit P4a di questa
sessione, in ordine:

- `ca0605f` P4a-1 — `acquire` direttiva + `edit-acquire` marcatore + write-back del proxy (gate PASSATO).
- `b55d9b3` P4a-2 — persistenza osservazioni (pick PnP + focale) in `acquire-state.json` (gate PASSATO).
- `984bcf0` docs — gate umani P4a-1/P4a-2 passati.
- `01bf8c0` P4a-3 — `:shapes` (ricalchi) + `:marks` (punti nominati) nella form (gate PASSATO).
- `afe5ba5` — l'oggetto emesso si ancora alla **posizione della turtle** a `edit-acquire` (non all'origine arbitraria del frame d'acquisizione).
- `d7e31ef` — **ricalchi multipli nominati** (`:ricalchi`+`:ricalco-idx`) + il gizmo si nasconde col proxy (`v`).
- `d4215b7` — la form emessa è **pretty-printed** (multi-linea, indentata alla colonna del marcatore).
- `665551d` — ogni ricalco esce come `{:shape (poly …) :mark {…}}`: la shape porta la sua **posa di faccia**, così `(turtle (:mark q) (extrude (:shape q) (f d)))` la estrude SULLA faccia, perpendicolare.

La forma emessa finale (esempio):
```clojure
(def A
  (acquire "scans/lettore/"
           {:proxy (box 60.2 20.2 40.1)
            :pose {:position [...] :heading [...] :up [...]}
            :shapes {:bordo {:shape (poly …) :mark {:position … :heading … :up …}}}
            :marks {:vite-1 {:position … :heading … :up …}}}))
```
`acquire` RITORNA questa struttura (destrutturabile per nome). La **fotografia**
(pose camere, osservazioni, piani, focale) sta nel file `acquire-state.json`
dentro la cartella; l'**oggetto** (proxy+posa, shapes, marks) sta nella form.

Memoria: `memory/project_edit_acquire_p4a.md` (dettagli riga-per-riga di tutte le fette).

## Cos'è P4b (dal brief, §"Le foto, in tre stati")

Oggi `edit-acquire` è un **MODALE a camera bloccata**: mutex condiviso
(`modal/claim!`), la camera è forzata ferma ogni frame, un pannello DOM sotto il
REPL, e si registra foto per foto guardando UNA foto alla volta come backdrop.
P4b **apre il palcoscenico**: la fase 2 (ricalco/mark) esce dal modale e diventa
**editor normale**. Le foto vivono in tre stati:

1. **Frustum nel mondo** (camera libera): le camere registrate disegnate come
   **piramidi ghost con miniatura**, alla loro posa vera nel mondo. Si legge a
   colpo d'occhio la copertura del giro. Cittadinanza da riferimento: mai
   export/pick/CSG (come `image-board`/`mesh-board`).
2. **In posa**: click su un frustum/miniatura → la camera **vola dentro** quel
   frustum, la foto va a pieno schermo (backdrop full-res), la geometria
   (proxy/ricalchi/marks) sopra. Esc / orbita → di nuovo camera libera. Qui,
   in posa, torna la **ricchezza di edit-path-2d** per ricalcare (bezier/archi,
   non solo la polilinea minimale di oggi).
3. **Pellicola come pannello**: la striscia di miniature — riusa la *process
   view* di `mesh-board` (`ridley.viewport.inset`), nata per vivere anche
   FUORI dalla sessione.

**Invariante di frame** (per il futuro live): il frame del palcoscenico è
l'**OGGETTO** — la geometria non si muove mai. Live = il piatto che gira = la
camera che orbita: la camera live è un **frustum in moto** attorno al mondo
fermo; "scatta" = congela il frustum live in una foto registrata. (Il live è
oltre P4b — è in coda — ma il palcoscenico va disegnato con questo invariante in
mente.)

## ⚠️ Design APERTO (decide Vincenzo, dopo prova)

L'**ergonomia dei frustum** (ingombro visivo nel viewport, come si naviga tra
loro) è **da collaudare, non decisa**. Non implementare "alla cieca" la piramide
+ miniatura come se fosse deciso: proporre, mostrare, far provare. **Fallback
esplicito nel brief**: se i frustum ingombrano/confondono → **solo la pellicola
(pannello) + un toggle per mostrare/nascondere i frustum**. Prima mossa
consigliata: la pellicola-pannello (bassa incertezza) e il "vai in posa", poi i
frustum come layer opzionale da collaudare.

## Vincoli / meccanismi tecnici (entry points)

- **`src/ridley/editor/edit_acquire.cljs`** — la sessione. Oggi:
  - `modal/claim! :edit-acquire` (mutex) + `register-kind!` in fondo
    (`:requested? (constantly false)` → modale sincrono, come `tweak`).
  - **Camera bloccata**: `viewport/register-frame-callback! :edit-acquire` forza
    `set-controls-enabled! false` ogni frame (perché il gizmo la riabilita a fine
    drag). **Per P4b la camera va LIBERATA** in stato "libero" — questo callback è
    il punto da cambiare.
  - `session` atom: `:camera-poses {idx pose}` (world, le pose registrate → da qui
    si disegnano i frustum), `:photos [{:file :theta}]`, `:current-idx`,
    `:proxy-mesh`, `:ricalchi`, `:marks`, `:build-pose`, `:hide-proxy?`.
  - Modi: `:gizmo` / `:pnp` (`p`) / `:retrace` (`d`) / `:mark` (`k`) / `:marker`
    (`m`, blindato). Tasti `[`/`]` navigano la pellicola. `enter-photo!` cambia foto
    (setta camera-pose + backdrop).
- **`src/ridley/editor/acquire_backdrop.cljs`** — il backdrop foto: un *plane*
  figlio della camera a `default-depth` (250mm), foto full-res via
  `stl/desktop-read-file-blob`. Per la "foto in posa" si riusa così com'è; per le
  **miniature** (frustum + pellicola) serve caricare thumbnail (NON full-res —
  14×24MP non stanno in RAM insieme, vedi brief §Memoria). `pixel-under-pointer`
  e `image-size` sono qui.
- **`ridley.viewport.inset`** — mini-viewport picture-in-picture keyed
  (mount!/unmount!/set-content!), già usato da `mesh-board` per le viste di
  confronto e da `edit-mesh-split`. Candidato per la **pellicola-pannello**.
- **`ridley.viewport.core`** — camera, controlli orbita
  (`enable-orbit-controls!`/`set-controls-enabled!`), `set-camera-pose!`,
  `register-frame-callback!`, `show-preview!`, `set-labels!`, frustum? (da
  costruire: disegnare piramidi ghost — vedi come `mesh-board` spinge mesh-scaffold
  in `state/scene-accumulator :scaffolds`).
- **`src/ridley/photogrammetry/camera.cljs`** (`pcamera`) — intrinseche, FOV
  (`equiv-focal->hfov-deg`), `project`/`pixel-ray`: servono per disegnare il
  frustum alla FOV giusta e per la "foto in posa".
- **`ridley.photogrammetry.bridge`** — `box-basis`/`local->world` (frame oggetto),
  `editor->solver-pose`/`solver-pose->camera`. `edit_acquire.cljs` è browser-only
  (deps viewport/gizmo/backdrop) → **NON node-testabile**; `bridge.cljs` sì.

## GOTCHA (a caro prezzo, da questa sessione)

- **REPL/build**: `edit_acquire` gira solo in browser → verifica **live in Chrome
  su `localhost:9000`** (la webview Tauri ha cache ostinata). Ricompila via nREPL:
  `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as api]) (api/watch-compile! :app)"` → `:ok`. MAI `shadow-cljs compile` a mano accanto al watcher.
- **Runtime browser STALE**: dopo un ricompile il runtime connesso via nREPL
  **esegue le eval ma non riceve gli hot-swap del watcher** → le fn nuove risultano
  `undefined`. Fix: `(.reload (.-location js/window))` via eval CLJS, **aspetta ~7s**,
  re-`(shadow/repl :app)`. **Ricarica la pagina prima di OGNI test live.** (Costato
  molte false diagnosi di "codice non caricato".)
- **CLJS REPL**: `(shadow/repl :app)` porta in CLJS mode; `:cljs/quit` torna a CLJ
  (serve per `api/watch-compile!`). `(def A (acquire …))` in `evaluate-definitions`
  ritorna la VAR (`#'A`), non il valore — per il valore eval `"(acquire …)"` da solo
  o `"(def A …)\nA"`.
- **Playwright MCP**: il profilo Chrome può risultare *locked* ("Browser is already
  in use") → in quel caso pilota tutto via nREPL CLJS, non Playwright.
- **`(box …)` grezzo vs macro**: `prims/box-mesh` dà vertici in frame LOCALE; la
  `(box …)` macro (`pure-box` → `transform-mesh-to-turtle`) applica la convenzione
  (x→right, y→up, z→heading) che `box-basis`/`dims-from-mesh` leggono. Se costruisci
  un box "a mano" per test, ricordalo o le dims tornano permutate.
- **Vincenzo NON è sviluppatore quotidiano**: chiudi ogni risposta operativa con
  **"Prossimi passi per te"** (italiano, passi numerati, nomi esatti di
  bottoni/comandi, niente nomi interni) — vedi `CLAUDE.md`. L'ha collaudato lui ogni
  fetta; il palcoscenico va fatto provare, non dato per deciso (specie i frustum).

## PRIMA MOSSA nella chat nuova

1. Leggi il brief §"Design del palcoscenico" (autorevole) + `memory/project_edit_acquire_p4a.md`.
2. Apri una sessione live per capire lo stato attuale: nel pannello definizioni
   `(def A (edit-acquire "test-assets/param-acq-box-tape/" {:proxy (box 60.2 20.2 40.1)}))`,
   Cmd+Enter, e guarda com'è ORA (modale, camera bloccata, una foto per volta).
3. Decidi con Vincenzo l'ordine: proposta = **pellicola-pannello + "vai in posa"
   prima** (basso rischio), **frustum-nel-mondo come layer da collaudare dopo**
   (col fallback toggle già in tasca).
4. Il perno tecnico: liberare la camera (togliere/cambiare il frame-callback
   `:edit-acquire` che la blocca) e disegnare le camere registrate (`:camera-poses`)
   come frustum ghost — misurando l'ingombro col verdetto di Vincenzo.

## Coda (dopo P4b, dal brief)

Corona di marker sul piatto (bersaglio PnP universale, θ dalla corona); rounded-
prism + fillet-blend (leva d'accuratezza); sorgenti live (webcam/companion ARKit)
sopra l'interfaccia in-posa. P5 = protocollo di scatto + documentazione.
