# Handover — P4b frustum, FASE B (miniatura sul frustum + click-per-andare-in-posa)

## STATO 2026-07-25 — Pezzo 1 (CLICK) FATTO + gate PASSATO + COMMITTATO `c5c810e`

**Pezzo 1 (click su frustum → go-in-pose) FATTO, gate passato, committato** (branch
`edit-acquire-registration-stability`, `c5c810e`). Vincenzo: "si individua facilmente
il frustum e cliccando ci va". Meccanismo verificato end-to-end in Chrome via nREPL
prima del gate live:
- `viewport/build-preview-object` ora onora `:pick-only` → mette `visible=false` ma
  l'oggetto resta **raycast-hittable** (verificato in three.js r160: `intersectObject`
  su una mesh `visible=false` → 2 hit).
- `frustum-pick-mesh` (edit_acquire :210): gemella **solida invisibile** della
  piramide (apice + 4 triangoli laterali + 2 base, double-sided), `:pick-id`=idx-foto.
  `frustum-corners` (:186) condivisa con `frustum-edges`. `frustum-items` ora emette
  DUE item per camera: `:lines` (visibile) + pick-mesh (verificato: 4 item per 2
  camere, tipi `[:lines :mesh :lines :mesh]`, pick-id `[nil 0 nil 1]`).
- Test reale del pick: mesh `:pick-only` davanti alla camera vera → proiettata sullo
  schermo → `raycast-preview-pick` restituisce il pick-id giusto (anche se invisibile).
- Handler `frustum-on-pointerdown`/`-pointerup` (:1025) installati in `enter-stage!`,
  tolti in `leave-stage!`/`close!`. Solo in orbita libera del palcoscenico
  (`stage-free-orbit?`). Click "pulito" (spostamento < 6px tra down e up) su un
  frustum → `go-in-pose!`. **NON** fa preventDefault/stopPropagation → OrbitControls
  vede sempre gli eventi (un click pulito non ruota) e `go-in-pose!` blocca la camera
  da solo; così il click non ruba mai il drag dell'orbita.
- Messaggio del palcoscenico aggiornato: "Clicca una foto o il suo frustum…" quando
  i frustum sono mostrati.

**PROSSIMO**: (a) Vincenzo collauda il click dal vivo → se convince, si decide se
serve il **Pezzo 2** (miniatura) o basta la pellicola (fallback). (b) Se OK, committare.

---

Aperto 2026-07-25 per **continuare in una chat nuova**. Prerequisito: P4b fetta 1
+ frustum FASE A + raddrizzamento, tutti **fatti, collaudati e committati** (sotto).
Il perimetro autorevole resta `dev-docs/brief-param-acq-v1.md` → P4 → "Design del
palcoscenico" → §"Le foto, in tre stati" (stato 1 = frustum-nel-mondo). NON
riaprirlo. Handover generale del palcoscenico: `dev-docs/HANDOVER-p4b-stage.md`.

## Stato a monte — cosa c'è già (branch `edit-acquire-registration-stability`)

Due commit di P4b sopra P4a:
- `63ef164` — fetta 1 (camera libera + vai-in-posa) + rifiniture.
- `f338953` — **frustum FASE A** + raddrizzamento standard (`canonicalize-orientation!`)
  + Esc-safe.

**Frustum FASE A, già vivo** (Vincenzo: "può andare"): le camere registrate come
**piramidi-ghost** (`:lines`) alla loro posa vera nel mondo, la corrente in **ciano**
(`frustum-current-color`), le altre grigio-azzurro (`frustum-ghost-color`), sotto
**interruttore** `:show-frustums?` (bottone nel pannello, solo nel palcoscenico;
fallback previsto dal brief = sola pellicola). All'ingresso nel palcoscenico la
camera **arretra** (tenendo la direzione) per inquadrare l'anello — le camere sono
~250mm fuori da un oggetto ~60mm, se no i frustum finiscono fuori schermo.

Chiavi (in `src/ridley/editor/edit_acquire.cljs`):
- `frustum-edges` (:186) — 8 spigoli di una piramide (apice=posizione camera, base
  a `depth` davanti; `depth ≈ 1.2 × raggio-box`; FOV da focale+aspect via
  `pcamera/equiv-focal->hfov-deg`).
- `frustum-items` (:202) — una `{:type :lines …}` per ogni entry di `:camera-poses`;
  vuoto se `:show-frustums?` off; la corrente in ciano.
- `stage-free-preview-items` (:223) — `(into (proxy-preview-items) (frustum-items))`;
  usata SOLO in orbita libera (`enter-stage!` :892, `leave-pose!` :949), non in posa.
- `toggle-frustums!` (:968), stato `:show-frustums?` (init a `true`, :2984), bottone
  `:frustum-btn-el` in `build-panel!`/`update-panel!` (:2344).
- `viewport/frame-camera!` (`src/ridley/viewport/core.cljs` :1345) — arretra la camera
  lungo la direzione di vista per inquadrare una sfera (centro+raggio), pivot orbita
  al centro; usata in `enter-stage!`.

## Cosa manca — FASE B (questo handover)

Dal brief, stato 1 = "piramidi ghost **con miniatura**", e cliccando un frustum
"la camera **vola dentro**". Due pezzi, indipendenti:

### Pezzo 1 — CLICK sul frustum → vai in posa (CONSIGLIATO PER PRIMO)

È il gesto-chiave del palcoscenico spaziale (navigare cliccando la piramide invece
del numero nella pellicola). **Contenuto**, riusa l'infrastruttura di pick esistente:

- Le `:lines` NON sono raycast-hittable (nota storica mesh-board: solo `THREE.Mesh`
  lo è). Quindi aggiungi, per ogni frustum, una **mesh-piramide invisibile
  gemella** (`{:type :mesh :data <piramide-solida> :pick-id <idx-foto>}`) —
  `build-preview-object` (viewport/core :2529) tagga `userData.pickId`; opacity 0 /
  non serve che si veda. La piramide solida = apice + 4 spigoli-base come 4 triangoli
  (o un cono grezzo); riusa i vertici di `frustum-edges`.
- Aggiungi un **pointerdown sul canvas** attivo SOLO nell'orbita libera del
  palcoscenico (`:stage? && !:in-pose?`), sul modello di `retrace-on-pointerdown`
  (:1558) / `marker-on-pointerdown` (:1827): installalo in `enter-stage!`, rimuovilo
  in `leave-stage!`/`go-in-pose!`/`close!`. Nel handler: `(viewport/raycast-preview-pick e)`
  (viewport/core :2808 — ritorna il `:pick-id` sotto il puntatore) → se è un idx-foto,
  `(go-in-pose! idx)`.
- ⚠ Non deve rubare il drag dell'orbita: raycasta solo su un click "pulito"
  (pochissimo movimento tra pointerdown e pointerup), altrimenti lascia orbitare.
  Guarda come pnp/retrace gestiscono `stopPropagation`/`preventDefault`.
- Evidenzia il frustum sotto il puntatore (hover) se aiuta — opzionale.

### Pezzo 2 — MINIATURA della foto sul frustum (più pesante)

- **Vincolo di memoria (brief §Memoria)**: 14 × 24MP NON stanno in RAM full-res
  insieme. `acquire_backdrop` carica UNA foto full-res per volta (`set-photo!` :114,
  `desktop-read-file-blob`). Per le miniature serve un **percorso ridimensionato
  nuovo**: carica ogni foto come blob → `createImageBitmap(blob, {resizeWidth: …})`
  (o disegna su un canvas piccolo) → texture piccola. Cache-a le N miniature (piccole).
- **Rendering**: `show-preview!` NON fa piani texturati (`:mesh` non ha texture,
  `:lines`/`:dots`/`:stamp` nemmeno). Due strade: (a) estendere `build-preview-object`
  con un tipo `:textured-plane` (data = mesh-plane + texture) — la via pulita e
  riusabile; oppure (b) disegnare le miniature come oggetti THREE separati (come
  `acquire_backdrop` fa col plane full-res, ma in posa-mondo sulla base della
  piramide, non figli della camera). (a) è preferibile se il tipo serve altrove.
- Posa: la miniatura è un quad sulla **base** della piramide (i 4 corner di
  `frustum-edges`), orientata verso l'apice; texture = la foto di quell'idx.
- Il valore ergonomico è meno certo del click: la piramide + ciano-corrente già dà
  la direzione. **Fai il Pezzo 1 prima, poi valuta se la miniatura serve** (potrebbe
  bastare mostrare la miniatura SOLO al hover, per identificare la foto).

## Il gate ergonomico è ancora aperto

Il brief dichiara l'ergonomia dei frustum **da collaudare**. Fase A ha passato un
"può andare". Fase B è per capire se, con click (+ eventuale miniatura), i frustum
**diventano il modo naturale** di navigare le viste, oppure se restano un di-più e
la navigazione vera resta la pellicola (fallback: sola pellicola + toggle frustum,
già in tasca). Porta il Pezzo 1 a Vincenzo e fatti dire se clicca i frustum o la
pellicola.

## GOTCHA (a caro prezzo, da tenere)

- **Verifica live in Chrome su `localhost:9000`** (la webview Tauri ha cache
  ostinata). Ricompila via nREPL: `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as api]) (api/watch-compile! :app)"` → `:ok`. MAI `shadow-cljs compile` a mano.
- **Runtime browser STALE dopo un ricompile**: esegue le eval ma non riceve gli
  hot-swap → le fn nuove risultano `undefined`. Fix: `(.reload (.-location js/window))`
  via eval CLJS, aspetta ~7s, re-`(shadow/repl :app)`. **Ricarica prima di OGNI test.**
- **Geo-server con path ASSOLUTI**: legge i file dal disco risolvendo dalla sua CWD;
  un path relativo (`test-assets/…`) dà "No such file". Da nREPL usa il path pieno
  `/Users/vipenzo/Progetti/Ridley/test-assets/param-acq-box-tape/…`. (Il geo-server
  è attivo se Vincenzo sta girando `cargo tauri dev`.)
- **Riproduzione headless del flusso reale** (utile): costruisci un box mesh con
  `prims/box-mesh` + `prims/apply-transform`, `(reset! session {…:base-dir <assoluto> …})`,
  `(load-acquire-state!)` (file veri via geo-server) → `.then` → verifica. `(in-ns
  'ridley.editor.edit-acquire)` rende chiamabili le fn private.
- **Sessione di prova**: `(edit-acquire "test-assets/param-acq-box-tape/" {:proxy (box
  60.2 20.2 40.1)})` dal pannello definizioni (Cmd+Invio). Ha 6 foto registrate
  (anello pulito) → i frustum compaiono subito. Nota: dopo il raddrizzamento
  l'oggetto è dritto e il box emesso è `(box 20.2 40.1 60.2)`.
- **Vincenzo NON è sviluppatore quotidiano**: chiudi ogni risposta operativa con
  **"Prossimi passi per te"** in italiano (passi numerati, nomi esatti di
  bottoni/comandi, niente nomi interni) — vedi `CLAUDE.md`.

## Memoria

`memory/project_edit_acquire_p4b.md` (dettaglio di fetta-1 + frustum-A + raddrizzamento
+ Esc). Contesto risalente: `[[project_edit_acquire_p4a]]`.

## PRIMA MOSSA nella chat nuova

1. Leggi il brief §"Le foto, in tre stati" + `memory/project_edit_acquire_p4b.md`.
2. Apri la sessione di prova (sopra), entra nel palcoscenico, guarda i frustum vivi.
3. Fai il **Pezzo 1** (click → go-in-pose): mesh-piramide invisibile con `:pick-id`
   in `frustum-items` (accanto alle `:lines`) + pointerdown-in-orbita-libera che
   raycasta `raycast-preview-pick`. Falla provare a Vincenzo.
4. Solo dopo, decidi con lui se serve il **Pezzo 2** (miniatura) o se la pellicola
   basta (fallback).
