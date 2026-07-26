# Handover — P4b: toolbar del palcoscenico + riproiezione live (nuova sessione)

Aperto 2026-07-25 su richiesta di Vincenzo, per **ripartire in chat nuova**. Il
palcoscenico eval-driven (P4b) è **in piedi e collaudato**; questa è l'ultima
fetta ricca: la **UX toolbar + navigazione foto mentre si traccia** (= controllo
di riproiezione live). Branch: `edit-acquire-registration-stability`.

---

## ✅ STATO 2026-07-25 (fine sessione) — COSTRUITO + verificato Playwright, NON committato, in attesa del GATE UMANO

Tutti i pezzi A/B/C fatti e verificati live su `localhost:9000` coi dati veri
`test-assets/param-acq-box-tape/`. **Manca solo il collaudo ergonomico di
Vincenzo** (nella SUA app). Non committato.

- **(C) Toolbar** in `#viewport-toolbar`, montata solo con una `(acquire …)` in
  scena CON camere registrate; tolta quando l'acquire esce. Tre bottoni
  raggruppati `‹ · Foto · ›` (wrapper `#eaq-stage-tools`): **Prev**, **toggle
  Photo-lock**, **Next**. Toggle: selezionato (giallo) in posa, etichetta
  **"foto i/N · θ°"** (i = rango in ordine θ); premuto in posa → orbita libera
  (solo se nessun ricalco è aperto); premuto da libero → blocca la corrente (o
  la prima in θ se nessuna). Aggiornata a ogni cambio posa (anche via clic
  frustum, non solo via toolbar).
- **(B) Prev/Next + `[`/`]`** navigano in **ordine di θ** (non di indice) con
  **volo camera ~200 ms** (LERP pos + SLERP orient, smoothstep) — nuovo helper
  `viewport/fly-camera-to-pose!` (frame-callback `:camera-flight`; il render-loop
  è continuo, niente rAF separato). Verificato progressivo (mid ≈ metà strada).
- **(A) IL CUORE — navigazione MENTRE edit-path-2d è aperto = riproiezione live.**
  `go-in-pose!` NON fa più `clear-preview!` se `modal/active?` → l'overlay del
  ricalco (stesso layer preview condiviso) è **preservato** e si riproietta
  gratis dalla nuova camera. **Provato**: gli UUID degli oggetti preview restano
  IDENTICI prima/dopo il cambio foto, la camera si muove, il triangolo di
  edit-path resta sulla faccia top e si vede dal nuovo angolo (screenshot).
  `[`/`]` **promossi sopra il modale** (tolto il guard `modal/active?`, tenuto il
  guard `editable?` così i campi di testo e il sorgente ricevono le parentesi).
  Esc resta all'editor quando un modale è aperto; lo stage prende Esc solo senza
  modale. `go-in-pose!` in flight nasconde la vecchia foto e mostra la nuova
  all'arrivo (overlay sopra sfondo neutro durante il volo — **da giudicare**).
- **Toggle → globale col modale APERTO ora FUNZIONA** (feedback Vincenzo
  2026-07-25, corretto stessa sessione): premere "Foto" mentre edit-path-2d è
  aperto libera l'orbita (puoi orbitare e ispezionare il tratto da ogni angolo),
  il ricalco resta visibile e si riproietta. Il lock per-frame serviva SOLO in
  posa (lì impedisce che il grab-nodo orbiti via dalla foto); in orbita libera il
  comportamento normale di edit-path (disabilita i controlli solo durante il drag)
  è quello giusto → nessuna deriva. `leave-pose!` non è più guardato su
  `modal/active?`; salta solo `show-frustums!` col modale aperto (i frustum
  passerebbero da `show-preview!` che ripulisce il layer condiviso e cancellerebbe
  l'overlay). **Conseguenza**: mentre un ricalco è aperto i **frustum sono
  nascosti**; si naviga con toolbar/`[`/`]`; riappaiono chiudendo il ricalco.
- **Race dell'eval-fresco RISOLTA** (scoperta stessa sessione): `after-eval!`
  lancia `load!` async e il suo `.then` mostrava i frustum DOPO che edit-path
  aveva disegnato l'overlay → lo cancellava (eval fresco della form con
  `edit-path-2d` dentro → 12 frustum, tratto sparito). Fix: `show-frustums!` nei
  due rami di `after-eval!` è guardato su `(not (modal/active?))` — **regola
  coerente: con un modale aperto lo stage non mette mai i frustum sul layer
  preview condiviso; il modale lo possiede.** Verificato: eval fresco → overlay
  presente (2 oggetti), non 12 frustum.
- **Decisioni residue**: **(D) multi-acquire** ancora una sola acquire (l'ultima)
  — da concordare con Vincenzo se serve.

### Agg. 2026-07-26 — GATE PASSATO + due follow-up COMMITTATI

Vincenzo ha collaudato ("Tutto ok") toolbar + navigazione + toggle→global. Committati
`da7f00b` (volo camera) e `dd3743b` (toolbar+nav). Poi due segnalazioni sue, entrambe
**costruite + verificate Playwright + committate** (non ancora ri-collaudate live):

- **`cf2dfc0` — nomi delle facce unificati.** `(:top (:faces A))` e
  `(flash-face (:proxy A) :top)` cadevano su facce diverse (convenzione propria di
  acquire vs `:face-groups` del box). Ora `face-poses` chiava sui `:face-groups`
  reali della mesh → un nome = la stessa faccia. Scelta di Vincenzo: tenere la
  convenzione di flash-face/box ("acquire deve seguire le regole preesistenti").
  NB cambia quale faccia è `:top` (ora la +Y del box, laterale — voluto).
- **`959f1ff` — frustum su livello dedicato.** Prima sparivano aprendo un ricalco
  (layer preview condiviso). Ora `viewport/frustum-objects` +
  `show-frustum-layer!`/`clear-frustum-layer!`/`raycast-frustum-pick`, indipendente
  dal preview → coesistono con l'overlay del ricalco. Visibili in orbita libera
  (con o senza modale), cliccabili; in posa azzerati (camera zoomata, l'anello di
  camere ~250mm cade fuori campo → si naviga con Prev/Next/[/]/toggle).
- **File**: `viewport/core.cljs` (+`fly-camera-to-pose!`), `acquire_stage.cljs`
  (toolbar, `nav-order`/`nav-photo!` in θ, `go-in-pose!` animato+modal-aware,
  `leave-pose!` modal-guard, on-keydown promosso, lifecycle setup/teardown
  toolbar), `public/css/style.css` (`.eaq-stage-tools`).
- **Avvertenza collaudo (Review 4)**: da certe angolazioni il piano di schizzo va
  quasi di taglio → click mal condizionati; se i click "impazziscono" è quello.

---

## Stato a monte — cosa FUNZIONA già (tutto committato)

Il flusso completo è collaudato da Vincenzo ("va bene"): valuti nel sorgente
normale
```
(let [A (acquire "/percorso/ASSOLUTO/scans/…/" {:proxy (box W H D) :pose {…}})]
  (turtle (:top (:faces A)) (edit-path-2d)))
```
→ zoom out → clic su un **frustum** → **posa** con la **foto** sotto e il proxy
allineato → apri il **ricalco** edit-path-2d su una **faccia** (`:faces`) → disegni
sopra la foto. In posa: **zoom** (rotellina, attorno al cursore) e **pan** (tasto
destro); gli handle dei nodi restano di dimensione fissa.

Commit della sessione (in ordine), tutti su `edit-acquire-registration-stability`:
`12bf8a5` palcoscenico fuori dalla sessione (`(acquire …)`→frustum→click→posa) ·
`4d6b632` `:faces` · `804903d` scaffold nell'anteprima modale · `308591f`+`fd164c9`
sfondo · `37d82ca` frustum visibili (bug NaN) · `bf33c85` hard-lock camera in posa ·
`c4a0897` zoom+pan · `00faea3` handle a dimensione fissa · `09cdd31` (edit-path)
solo-sinistro-aggiunge-nodi + overlay-all'apertura.

**Architettura corretta (Vincenzo, non negoziabile)**: edit-acquire = SOLO
registrazione, poi si chiude. `(acquire …)` valutata = il palcoscenico come
**STATO DEL VIEWPORT** (non sessione modale). Il ricalco è `edit-path-2d` NORMALE
nel sorgente utente, piano dalla **turtle** (posata via `(:faccia (:faces A))` o un
mark), sfondo+riproiezione forniti dal palcoscenico. Vedi
`memory/project_edit_acquire_p4b.md` per il dettaglio completo di ogni pezzo/baco.

## IL TASK — UX toolbar del palcoscenico + riproiezione live (spec di Vincenzo)

Toolbar (in `#viewport-toolbar`, vedi sotto) che compare **solo se c'è una
`(acquire …)` in scena**; con più acquire, agisce sull'**ultima cliccata**. Senza
acquire → bottoni assenti.

1. **Toggle "Photo lock"**:
   - Clic su un **frustum** → **lock** (va in posa su quella foto) + il toggle si
     mostra **selezionato**, con etichetta **"foto i/N · θ"** (indice/totale + angolo
     giradischi di quella foto).
   - Toggle **premuto** (mentre è locked) → **navigazione globale** (esce dal lock =
     orbita libera, frustum mostrati).
   - **Ripremuto** → **lock sulla corrente** (rientra in posa sull'ultima foto).
   - In sostanza il toggle è lo stato in-posa ↔ libero (già `go-in-pose!` /
     `leave-pose!` nello stage), più l'etichetta i/N·θ.

2. **Prev / Next photo**:
   - Navigano i frustum **in ordine di θ** (NON di indice — le foto hanno `:theta`).
   - **Volo breve (~200 ms)** tra una posa e l'altra (animazione camera, non salto).
   - **REQUISITO (non nice-to-have)**: funzionano **anche con una sessione
     edit-path-2d aperta** — cambiare vista mentre si traccia È il controllo di
     riproiezione live (il tratto è geometria 3D, si ri-proietta gratis dalla nuova
     camera).

3. **Tastiera**:
   - `[` / `]` **promossi dal modale**: navigano le foto (prev/next in θ) ANCHE
     mentre edit-path-2d è aperto.
   - `Esc` **esce dal lock** (torna globale). (Occhio: edit-path usa Esc per
     annullare; vedi coesistenza sotto.)

## I punti DIFFICILI e come affrontarli

### (A) Navigare le foto MENTRE edit-path-2d è aperto — il cuore
Oggi lo stato è: lo stage `on-keydown` è **inerte quando `modal/active?`** (l'ho
messo io per evitare conflitti, `acquire_stage.cljs`). Va **ribaltato per `[`/`]`**:
devono agire anche col modale aperto. edit-path **non usa** i tasti `[`/`]` (grep
fatto: nessun uso), quindi non c'è conflitto di funzione — serve solo che lo
stage li intercetti (handler in **capture phase** su `document`, che sono già così).
Togli il guard `modal/active?` per `[`/`]` (tienilo semmai per altro).

⚠ **`go-in-pose!` CANCELLA il preview** (`(viewport/clear-preview!)` in coda):
navigando con edit-path aperto **spazzeresti via l'overlay del ricalco**. Il
ricalco DEVE restare visibile (e ri-proiettarsi) cambiando foto. Quindi: quando
un editor modale è aperto, `go-in-pose!` **non deve** fare `clear-preview!` (né
`show-frustums!`); deve solo spostare la camera + cambiare la foto di sfondo, e
lasciare l'overlay di edit-path dov'è (è world-space, si ri-proietta da solo).
Probabile che tu debba anche **rifar disegnare** l'overlay di edit-path dopo il
cambio foto se qualcosa lo tocca — ma se non lo tocchi, la ri-proiezione è gratis
(la camera si muove, il tratto no). Verifica con Playwright (vedi GOTCHA).

Nota coesistenza già risolta e da PRESERVARE:
- **hard-lock camera in posa** (`bf33c85`): frame-callback `:acquire-stage` che
  forza i controlli disabilitati ogni frame (edit-path li riabilita a ogni grab
  nodo → altrimenti deriva). Registrato in `go-in-pose!`, tolto in `leave-pose!`.
  Naviga-in-posa deve **mantenerlo** attivo tra una foto e l'altra.
- **zoom/pan** (`:view`) va **azzerato a ogni cambio foto** (già così in
  `go-in-pose!`/`leave-pose!` via `reset-view!`).

### (B) Volo breve ~200 ms
`viewport/set-camera-pose!` è **istantaneo** (setta posizione+lookAt, disabilita i
controlli). NON c'è un helper di animazione camera pronto in `viewport/core`.
C'è un ns animazioni (`ridley.anim`, usato in core come `anim/clear-all!`) — **da
verificare** se sa interpolare una camera; altrimenti fai un piccolo tween
(rAF/`setTimeout` che LERP-a posizione + slerp dell'orientamento per ~200 ms), poi
`set-camera-pose!` sul target finale. ⚠ Durante il volo la camera si muove: se
edit-path è aperto e i controlli sono hard-locked, ok; assicurati che l'animazione
non venga sovrascritta dal frame-callback (il callback disabilita i controlli, non
muove la camera, quindi va bene — ma il render-loop `(.update controls)` è saltato,
quindi anima tu la camera direttamente).

### (C) La toolbar (DOM)
Esiste `#viewport-toolbar` (id) dove `core.cljs` monta bottoni (mic, VR/AR — vedi
`core.cljs:2549` e `:3021`). Monta lì i bottoni dello stage (Photo lock toggle,
Prev, Next) **quando lo stage è attivo**, rimuovili in `deactivate!`. Aggiorna
l'etichetta "foto i/N · θ" a ogni cambio foto. Stile: riusa `.toolbar-button`.
Alternativa: un piccolo pannello flottante sul viewport — ma la toolbar esistente
è la strada di minor attrito.

### (D) Più acquire in scena → ultima cliccata
Oggi lo stage tiene UNA sola `(acquire …)` (l'ultima valutata; `note-eval!`
sovrascrive `:pending`, `after-eval!` la consuma). La spec dice: con più acquire,
la toolbar agisce sull'**ultima cliccata** (frustum). Questo implica che lo stage
possa conoscere **più** set di camere (uno per acquire) e che cliccare un frustum
di un certo acquire lo renda "attivo". È un'estensione: valuta se serve subito o se
la prima versione gestisce una sola acquire (caso comune) e multi-acquire è un
follow-up. Concordalo con Vincenzo se emerge.

## File e funzioni chiave (dove mettere le mani)

- **`src/ridley/editor/acquire_stage.cljs`** — TUTTO lo stage: `stage` atom
  (`:camera-poses` {idx pose}, `:photos` [{:file :theta}], `:current-idx`,
  `:in-pose?`, `:focal-mm`, `:view`, `:dims`, `:emit-pose`, `:dir`), `go-in-pose!`
  / `leave-pose!` (in-posa ↔ libero), `on-keydown` (`[`/`]`/Esc — **da promuovere**),
  `frustum-preview-items`/`show-frustums!`, zoom/pan (`apply-view!`, `on-wheel`,
  `on-pan-*`), `after-eval!` (attivazione post-Run), `deactivate!`. `:photos` ha già
  i `:theta` per l'ordinamento.
- **`src/ridley/core.cljs`** — hook post-eval: `(acquire-stage/after-eval!)` dopo
  `refresh-viewport!` in `evaluate-definitions-sci` (~riga 246). Toolbar buttons: vedi
  `#viewport-toolbar` (`core.cljs:2549`, `:3021`).
- **`src/ridley/editor/edit_path.cljs`** — l'editor modale del ricalco. `enter!`
  (ora fa `render!`), `on-pointer-down` (solo sinistro), `on-keydown` (i suoi tasti;
  `[`/`]` NON usati). `session` atom espone `:nodes`, `:pose`.
- **`src/ridley/editor/acquire_backdrop.cljs`** — piano-sfondo (foto): `create!`,
  `set-photo!`, `set-visible!`, `image-size` (ritorna nil senza foto), `ready?`.
- **`src/ridley/viewport/core.cljs`** — `set-camera-pose!` (istantaneo),
  `free-camera-at-pivot!` (sblocca senza salto), `register/unregister-frame-callback!`,
  `show-preview!`/`clear-preview!`, `world->screen`, `raycast-plane-point`,
  `scale-screen-dots!` (handle a dimensione fissa), `set-camera-fov!`.

## GOTCHA (a caro prezzo, da tenere)

- **Verifica live con Playwright su `localhost:9000`** — questa sessione ha usato
  Playwright a fondo e funziona benissimo: il build `:app` espone `window.ridley.*`
  (ogni ns) e `window.cljs.core`. Ricetta: `browser_navigate` a localhost:9000 →
  `browser_evaluate` che chiama `window.ridley.editor.repl.evaluate_definitions(src)`
  + `window.ridley.editor.acquire_stage.after_eval_BANG_()` (il percorso Run vero è
  `window.ridley.core.evaluate_definitions_sci(false)` dopo aver settato l'editor con
  `window.ridley.editor.codemirror.set_value(view, src)` dove `view =
  cljs.core.deref(window.ridley.core.editor_view)`), poi `go_in_pose_BANG_(idx)`, poi
  `browser_take_screenshot`. Munging: `-`→`_`, `!`→`_BANG_`, `?`→`_QMARK_`,
  `->`→`__GT_`. Ispeziona lo stato: `cljs.core.deref(window.ridley.editor.acquire_stage.stage)`,
  la camera `window.ridley.viewport.core.get_camera()`, `cam.view` (view offset),
  i preview `cljs.core.deref(window.ridley.viewport.core.preview_objects)`.
  **LEZIONE PAGATA**: verifica l'esito RENDERIZZATO (screenshot / `cam.view` /
  `mesh.visible` + `.material.map`), non solo i flag — un bug (foto assente, frustum
  NaN) è sfuggito perché controllavo solo `:in-pose?` e "12 oggetti esistono".
- **Ricompila via nREPL** (mai `shadow-cljs compile` a mano):
  `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as api]) (api/watch-compile! :app)"`
  → `:ok`. Il tab nREPL va in CLJS mode con `(shadow/repl :app)`; per la CLJ
  `api/watch-compile!` fai prima `:cljs/quit`. Dopo un compile il runtime browser è
  STALE: **ricarica** (`(.reload (.-location js/window))` via eval, ~9s) oppure usa
  Playwright che naviga fresco. Il tab appena ricaricato a volte non è pronto:
  ri-lancia l'attivazione dopo qualche secondo.
- **Multi-tab**: se ci sono più client connessi le eval possono atterrare su tab
  diversi → batcha le letture dipendenti in UNA eval atomica.
- **Geo-server con path ASSOLUTI**: `desktop-read-file` passa il path in un header
  `X-File-Path` a `geo-server-url/read-file`; con `cargo tauri dev` la CWD del
  geo-server non è la radice del progetto → i path relativi non si trovano. Usa il
  path pieno `/Users/vipenzo/Progetti/Ridley/test-assets/param-acq-box-tape/`. (In
  uso reale la cartella arriva già assoluta dal selettore file.)
- **Sessione di prova**: `test-assets/param-acq-box-tape/` — 6 foto registrate
  (anello pulito, focale 48, dims caliper 60.2/20.2/40.1 → box emesso 20.2 40.1 60.2
  a posa `{:position [0 0 0] :heading [0 1 0] :up [0 0 1]}`, che coincide con la
  `proxy-pose` in `acquire-state.json` → riconciliazione identità → proxy allineato).
- **Camera figlia di un Group a identità**: `set-camera-pose!` setta posizione LOCALE
  ma è ok (Group a [0,0,0]); la camera finisce esattamente sulla posa registrata
  (verificato delta [0,0,0]).
- **Vincenzo NON è sviluppatore quotidiano**: chiudi ogni risposta operativa con
  **"Prossimi passi per te"** in italiano (passi numerati, nomi esatti di
  bottoni/comandi, niente nomi interni) — vedi `CLAUDE.md`.

## PRIMA MOSSA nella chat nuova

1. Leggi `memory/project_edit_acquire_p4b.md` (stato completo + tutti i bachi/fix) e
   questo handover.
2. Apri la sessione di prova in Chrome/Playwright, vai in posa, apri un edit-path-2d,
   e **prova a chiamare `go_in_pose_BANG_(altro-idx)` mentre edit-path è aperto**:
   osserva se l'overlay del ricalco sopravvive e si ri-proietta, o viene cancellato
   (`clear-preview!`). Questo dimensiona il pezzo (A).
3. Parti dal pezzo (A) — navigazione-mentre-modale con preview preservato + `[`/`]`
   promossi — è il requisito duro e il cuore della riproiezione live. Poi (B) volo
   ~200ms, poi (C) toolbar UI, poi valuta (D) multi-acquire con Vincenzo.
4. Verifica ogni passo con Playwright (screenshot + stato), committa a fette,
   collaudo umano di Vincenzo per l'ergonomia.

## Review (Claude, pre-lancio) — quattro aggiunte

1. **Esc: il conflitto va risolto così** — con edit-path-2d aperto, Esc
   appartiene all'EDITOR (annulla), lo stage non lo tocca: il guard
   `modal/active?` resta per Esc e si toglie SOLO per `[`/`]`. Esc esce dal
   lock solo quando nessun modale è aperto.
2. **`[`/`]` in capture su `document` rubano i tasti ai campi di testo**:
   guard su `event.target` input/textarea (gemello del bug digit-buffer di
   edit-mesh-split).
3. **Toggle premuto senza una foto corrente** (palcoscenico mai lockato):
   default = prima foto in ordine di θ (o toggle disabilitato finché non
   esiste una corrente). Da definire, non lasciare al caso.
4. **Avvertenza da collaudo, non da codice**: navigando durante il ricalco
   si può arrivare a viste col piano di schizzo quasi di taglio → click sul
   piano mal condizionati (piccoli errori di mira = grandi salti). Se in
   collaudo i click "impazziscono" da certe angolazioni, è questo.

## Nota

`dev-docs/brief-param-acq-v1.md` ha una modifica NON committata di Vincenzo (note
di design del palcoscenico) — è sua, lasciata a lui.
