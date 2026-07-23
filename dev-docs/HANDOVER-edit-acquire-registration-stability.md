# Handover — edit-acquire: stabilità della registrazione

Aperto 2026-07-25 per **continuare in una chat nuova**. Task concordato con
Vincenzo: due interventi strutturali sulla registrazione multi-foto di
`edit-acquire`. Contesto a monte: `dev-docs/brief-param-acq-v1.md` (P2/P3),
`dev-docs/HANDOVER-edit-acquire-p3.md` (il ricalco P3), `dev-docs/HANDOVER-
edit-acquire-pnp.md` (il PnP).

## I due interventi da fare (l'oggetto di questa chat)

1. **Blocco del ramo (Klein)** — scelto il ramo di simmetria sulla foto 0, TUTTE
   le foto successive devono restare su quel ramo. È la cura del brief
   *"l'ipotesi non balla mai"*. Chiude alla radice l'ambiguità di Klein che oggi
   spacca la sessione in due giri opposti.
2. **Trasporto rigido invece di azzeramento** — quando si ritocca il proxy sulla
   foto 0, le camere registrate delle foto 1..N devono seguire **rigidamente** il
   proxy, non essere azzerate.

Vincenzo li ha scelti dopo una **diagnosi ferma** (vedi sotto). Ordine libero; il
(2) è più piccolo e ben delimitato, il (1) è di design.

## STATO 2026-07-23 (chat successiva) — leggere PRIMA

- **FIX (2) — FATTO e verificato.** `attachment/transform-pose-rigid` (nuovo,
  riusa le primitive rigide testate) + `transport-registered-cameras!` in
  `edit_acquire.cljs` sostituiscono l'azzeramento `select-keys [0]` in
  `on-photo0-commit!` E nel ramo PnP-foto-0 di `on-solve-pnp!`. Le camere
  **registrate** (`:rms-px` da snap/PnP, o `:manual?` da drag) seguono il proxy
  RIGIDAMENTE su translate E rotate; i semi/predizioni si scartano per
  ri-derivare. Persistito anche `:manual?` in `acquire-state.json` (una camera a
  mano resta "registrata" dopo riapertura). Test Node: 739 verdi (nuovo
  `test/ridley/turtle/transform_pose_rigid_test.cljs`). Verificato live in Chrome
  (funzione di produzione sull'atomo `session`): translate → camera registrata
  trasla del delta, orientamento invariato; rotate 90° → ruota rigidamente attorno
  al pivot; camera-0 intatta; predetta scartata; residuo preservato.

- **FIX (1) — evoluto nel "BLINDATO" (marker-click): COSTRUITO, verificato in
  codice, attende gate umano.** Il primo tentativo — `klein-branch-lock` con
  riferimento = seme (`seed-camera-pose`, orbita l'**up del proxy**) — è stato
  RIMOSSO perché la verifica sui dati reali ha rivelato che l'asse vero del
  giradischi è `[0.627,−0.647,−0.433]`, **90° dall'up del proxy** (scatola ripresa
  di fianco), e a **θ180** il seme sceglie il gemello sbagliato. Vincenzo ha poi
  chiarito che sul nastro c'è una **freccia a pennarello** su uno spigolo (i
  gemelli SONO distinguibili) e ha scelto il **blindato**: usare il segno fisico.
  - **Diagnosi ri-eseguita (dati attuali):** θ30…270 coerenti su un ramo, **foto 0
    (θ0)** sull'altro (0→30 a 149°). Confermato geometricamente che **un unico
    flip di 180° attorno all'asse HEADING** del box riporta θ30-270 sul ramo di
    foto 0 → tutte coerenti (0 anomalie). Combacia con l'osservazione di Vincenzo:
    pallino rosso dal lato della freccia solo su foto 0, opposto sulle altre.
  - **`bridge/branch-by-marker` + `bridge/klein-images`** (NUOVI, puri,
    node-testati — `test/ridley/photogrammetry/branch_by_marker_test.cljs`):
    dei 4 gemelli di Klein sceglie quello che riproietta lo spigolo marcato più
    vicino al **pixel cliccato** dall'utente. Deciso dall'osservazione, non dal
    seme → robusto a θ180 (separazione misurata tra le 4 riproiezioni ≥650px su
    frame 4032). Capstone su dati reali: click freccia a θ180 → la posa stored
    (ramo sbagliato) viene riportata al ramo corretto, esatto.
  - **UI in `edit_acquire.cljs`:** tasto `m`/pulsante "Marca il segno" → modalità
    `:marker` (one-shot: clic → blocca → torna al gizmo). `marker-lock-camera` è
    l'unico imbuto in cui passano snap/PnP/fit, così il segno cliccato UNA volta
    blocca il ramo per sempre su quella foto (sopravvive a ri-snap/fit).
    `:marker-picks {idx [px py]}` persistito in `acquire-state.json`. 741 test
    Node verdi; `:app` compila; fn esposte e verificate live.
  - **`f` reso PREDICT-ONLY (fix del gate 2026-07-23):** al primo test dal vivo il
    blindato funzionava foto-per-foto, ma premere `f` **distruggeva tutto** — il
    fit congiunto (`apply-turntable-fit!`) sovrascriveva OGNI posa con il modello
    globale di giradischi (una sola posa base+asse+verso, asse per il centro box),
    più grezzo di uno snap individuale e impreciso su questo giradischi storto; il
    marker-lock correggeva il ramo ma non la geometria → pallini sparsi. Ora
    `apply-turntable-fit!` **salta le foto già registrate** (`registered-result?`:
    snap `s` o marca `m`) e predice solo quelle senza registrazione. Verificato
    live: con tutte registrate, `f` è no-op. **Il blindato rende `f` inutile per
    la coerenza** (ogni foto è già corretta): non è più un passo del workflow.
  - **Workflow corretto:** foto 0 allinea proxy col pallino rosso sulla freccia;
    su ogni foto 1..N `m`+click sulla freccia, poi `s`; **NON premere `f`**;
    verificare che il pallino rosso cada sulla freccia in tutte le viste (`[`/`]`).
  - **Manca:** il **gate umano** completo (rifare con `m`+`s` su tutte, senza `f`).
    NB: `acquire-state.json` salva pose ma NON i pick di snap, quindi la sessione
    va comunque ri-agganciata dal vivo.

## LA DIAGNOSI (perché servono, certificata dalla geometria)

Sessione di prova: `test-assets/param-acq-box-tape/` (blocco nastrato, 6 foto a
θ = 0, 30, 60, 90, 180, 270). Misurata la rotazione relativa tra foto consecutive
(orientamento completo, non solo posizione camera) nel frame OGGETTO:

| transizione | Δθ | angolo | asse |
|---|---|---|---|
| θ0→θ30 | 30° | 30.2° | [0, −0.84, −0.54] |
| θ30→θ60 | 30° | 29.8° | [0, −0.84, −0.54] |
| θ60→θ90 | 30° | 30.8° | [0, −0.84, −0.54] |
| **θ90→θ180** | 90° | 88.9° | **[0, +0.81, +0.58] ← asse INVERTITO** |
| **θ180→θ270** | 90° | 89.4° | **[0, +0.82, +0.57] ← asse INVERTITO** |

Gli angoli combaciano con Δθ (è davvero un giradischi), ma **l'asse si rovescia di
segno tra θ90 e θ180**. Ruotare di +90° attorno all'asse negato = −90° attorno
all'asse vero: da θ180 il giro va all'indietro. Quindi **θ0,30,60,90 stanno su un
ramo; θ180,270 sul ramo opposto** (specchiato 180°). Lo spacco cade a θ180 —
dove il blocco simmetrico è quasi identico a θ0 e la registrazione è scattata sul
gemello di Klein; θ270 ha seguito.

Conseguenze osservate da Vincenzo, tutte spiegate da questo:
- **"'f' ribalta le facce / il pallino rosso passa dall'altra parte"**: il fit
  congiunto (`match/fit-turntable-seeded`) impone UN solo verso; con picks su due
  rami opposti è costretto a rovesciare il gruppo di minoranza → facce/pallino
  saltano di 180°. Non è un bug del fit: è input incoerente.
- **"Tocco la foto 1 e si disallineano tutte le altre"**: `on-photo0-commit!`,
  quando il pivot si sposta, fa `(swap! session update :camera-poses select-keys
  [0])` e lo stesso su `:acquire-results` — **azzera** le registrazioni di 1..N.
  È l'accoppiamento che il fix (2) sistema.

**Nota di onestà / punto aperto**: la geometria dice che le girate sono **θ180 e
θ270**; Vincenzo a occhio vedeva la **foto 1 (θ0)** come la discorde. Con un
blocco simmetrico "quale sia la strana" dipende da quale ramo consideri giusto —
sono due gruppi che litigano, non una foto sola. Prima di correggere conviene
**co-verificare a schermo** con Vincenzo il gruppo di ciascuna foto (i colori-
faccia lo rendono ora visibile). NON dare per scontato che θ180/270 siano "le
sbagliate" da raddrizzare: potrebbe essere il ramo di photo-0 quello da girare.

**Il difetto profondo**: niente blocca il ramo. Ogni foto risolve Klein per conto
suo. Il fix (1) è la cura strutturale.

## FIX (2) — trasporto rigido (piccolo, ben delimitato)

File: `src/ridley/editor/edit_acquire.cljs`, `on-photo0-commit!` (~riga 394).
Oggi:
```clojure
(when-not (= old-pivot (pivot))
  (swap! session update :camera-poses select-keys [0])
  (swap! session update :acquire-results select-keys [0]))
```
Serve: calcolare la trasformazione rigida `old-proxy-pose → new-proxy-pose`
(cattura `old-pose`/`new-pose` = `:creation-pose` prima/dopo lo spostamento) e
**applicarla a ogni camera registrata** (idx>0 che ha un `:acquire-results` con
`:rms-px` o `:manual?`), lasciando intatti i loro `:acquire-results`. I semi
puri (foto senza registrazione, o `:predicted?`) possono ancora essere
ricalcolati/azzerati.

Le camere sono `{:position :heading :up}` in mondo-editor; il proxy ha subito un
rigido. Applica lo STESSO rigido alle camere. Riusa il meccanismo di
`attachment/transform-mesh-rigid` / `group-transform` (in `turtle/attachment.cljs`)
ma su una posa invece che una mesh — o scrivi un `transform-pose-rigid`
{:position :heading :up} che trasla la posizione e ruota heading/up con la stessa
rotazione. `group-transform p0 h0 u0 p1 h1 u1` dà il rigido da (p0,h0,u0) a
(p1,h1,u1); usa la coppia old/new del proxy come sorgente/destinazione.

Attenzione: `on-photo0-commit!` scatta il ramo `when-not (= old-pivot (pivot))`
solo sui **translate** (le rotazioni non muovono il pivot). Valuta se il
trasporto rigido vada fatto anche sulle **rotazioni** del proxy (una rotazione
del proxy cambia l'orientamento → le camere relative dovrebbero ruotare): oggi
le rotazioni NON toccano le camere (commento storico: "il seme orbita attorno al
pivot fermo, resta valido"), ma quel ragionamento vale per i SEMI, non per le
pose registrate. Probabilmente il trasporto rigido va applicato a ogni commit
(translate E rotate) sulle camere registrate.

Test: registra 2..6, ritocca il proxy sulla foto 0, verifica che 2..6 restino
allineate (prima si azzeravano). Ricetta di ispezione sotto.

## FIX (1) — blocco del ramo (design)

Obiettivo: dopo che la foto 0 fissa l'orientamento del proxy, ogni registrazione
successiva è **vincolata al ramo turntable-coerente**. Dove Klein si risolve oggi,
per foto:

- **PnP** (`on-solve-pnp!` → `photogrammetry/pnp.cljs`): forma chiusa da
  corrispondenze DICHIARATE. In teoria l'identità toglie l'ambiguità, MA
  l'etichettatura degli spigoli può essere flippata dall'utente su un box
  simmetrico → il PnP finisce sul ramo sbagliato "legalmente".
- **Snap** (`on-snap!` → `photogrammetry/match.cljs`): raffina da un SEME
  (`seed-camera-pose`, orbita photo-0 attorno all'up del proxy di −θ). Se il seme
  è sul ramo giusto e lo snap non salta 180°, il ramo si conserva.
- **Fit** (`on-fit-turntable!` → `match/fit-turntable-seeded`): sceglie UN
  `:sense`+`:yaw` globali. È QUI che il ramo si fissa per l'intero set — e dove
  può ribaltare (vedi 'f').

Meccanismo proposto (da progettare nella nuova chat, con il brief P2
"Mitigazioni di simmetria" e "Registrazione per corrispondenze"):
1. Stabilire il **ramo di riferimento** dalla foto 0 (l'orientamento del proxy) +
   il modello turntable (asse+verso) appena c'è abbastanza per fissarlo.
2. Ad ogni registrazione di una foto i>0, calcolare la **posa predetta dal
   giradischi** (`seed-camera-pose`/`match/reproject-turntable`) e confrontare la
   soluzione trovata con lei e col suo **gemello di Klein** (posa +180° attorno
   all'asse del giradischi); **scegliere quella nell'emisfero del ramo di
   riferimento** (o rigettare/riflettere la twin). In pratica: la registrazione
   non può cadere sul ramo opposto per costruzione.
3. Per `fit-turntable-seeded`: **vincolare il `:sense`** a quello del ramo
   stabilito, invece di riscannerlo (oggi fa uno sweep). Vedi la sua docstring
   in `edit_acquire.cljs` ~riga 285 e il suo codice in `match.cljs`.
4. Indizio d'orientamento già presente e da sfruttare: `corner-marker-pos` (lo
   spigolo rosso marcato) e il puntino centrale — il brief li vuole come àncora
   di ramo, non solo di posizione.

Caveat importante: la sessione ESISTENTE di Vincenzo È già spaccata (θ180,270 sul
ramo opposto). Il blocco del ramo previene spacchi FUTURI ma non raddrizza da
solo i dati vecchi — dopo il fix va **ri-registrata** la coppia flippata (o si
aggiunge un comando "porta tutte sul ramo di foto 0"). Deciderlo con Vincenzo.

## FILE E MECCANISMI CHIAVE

- `src/ridley/editor/edit_acquire.cljs` — sessione modale. Poses: proxy in
  `:proxy-mesh :creation-pose` (unica fonte), camere in `:camera-poses {idx pose}`.
  Foto 0 = allineamento PROXY (gizmo lo muove). Foto 1..N = camera (gizmo
  invertito). `seed-camera-pose` (orbita photo-0), `camera-pose-for` (memoizza),
  `on-photo0-commit!`/`on-inv-commit!` (commit gizmo), `on-snap!`/`on-solve-pnp!`/
  `on-fit-turntable!` (registrazioni). `default-vantage-pose` (vantaggio fisso di
  foto 0, guarda lungo [1 −1 1], up = Z-mondo — POSSIBILE fonte del "roll" di
  foto 0 rispetto al giradischi: da tenere presente nel fix 1).
- `src/ridley/photogrammetry/bridge.cljs` — `editor->solver-pose`,
  `solver-pose->camera`, `solver-pose->proxy`, `box-basis` (convenzione box:
  ex=up×heading, ey=up, ez=heading), `local->world` (nuovo, pubblico).
- `src/ridley/photogrammetry/camera.cljs` — modello pinhole. `rodrigues`,
  `camera-center`, `project`, `pixel-ray` (nuovo, backprojection), `look-at-pose`.
- `src/ridley/photogrammetry/match.cljs` — solver (snap/fit turntable). Qui
  `fit-turntable-seeded` con `:sense`/`:yaw`/`:axis-params`.
- `src/ridley/photogrammetry/pnp.cljs` — PnP da corrispondenze dichiarate.
- `src/ridley/turtle/attachment.cljs` — `group-transform`, `transform-mesh-rigid`,
  `translate-mesh`, `rotate-mesh` (per il fix 2).

## RICETTA DI VERIFICA (Playwright MCP, read-only — è così che ho diagnosticato)

Ambiente: `cargo tauri dev` acceso (dà il geo_server per le foto). Testa in
**Chrome/Playwright su `http://localhost:9000`** (la webview di Tauri ha cache
ostinata, evitala). Apri la sessione col REPL in basso a sinistra:
```
(edit-acquire (box 60.2 20.2 40.1) "/Users/vipenzo/Progetti/Ridley/test-assets/param-acq-box-tape")
```
Accesso ai var CLJS da JS: `window.ridley.<ns con _>.<fn con _/BANG_>`,
`window.cljs.core` (cc), atomo di sessione `window.ridley.editor.edit_acquire.session.state`
(deref). Chiavi via `cc.get(map, cc.keyword("..."))`, vettori via `cc.nth`.

La misura che ha inchiodato la diagnosi = rotazione relativa tra foto consecutive
(θ-ordinate): per ogni foto `R = cam.rodrigues(editor->solver-pose(cameraPose,
proxyPose).rvec)`; rotazione relativa `R_j · R_iᵀ`; angolo da `acos((tr−1)/2)`,
asse dalla parte antisimmetrica. Angolo≈Δθ e **asse costante** = ramo coerente;
asse che si NEGA = ramo opposto. (Lo script completo è nella cronologia della
chat precedente; ricostruibile in ~30 righe.)

Preview objects in scena: `window.ridley.viewport.core.preview_objects.state`
(vettore cljs). Il colore-faccia attivo è un `:mesh` quad translucido.

## STATO NON COMMITTATO (tutto il lavoro di questa sessione)

`git status` (nessun commit fatto — la registrazione blocca il gate finale):
```
 M src/ridley/editor/edit_acquire.cljs   (+499/−42: modo :retrace, colore-faccia,
                                          toggle proxy in gizmo, trace persistente,
                                          persistenza gizmo-commit + camera-pose-0,
                                          hook ^:dev/after-load)
 M src/ridley/math.cljs                  (ray-plane-point)
 M src/ridley/photogrammetry/camera.cljs (pixel-ray)
 M src/ridley/photogrammetry/bridge.cljs (local->world pubblico)
 M public/css/style.css                  (.eaq-retrace-box)
 M dev-docs/brief-param-acq-v1.md        (stato P3)
 M CLAUDE.md                             (preesistente, non da questa sessione)
?? test/ridley/photogrammetry/backproject_test.cljs  (round-trip 0mm, verde)
?? dev-docs/HANDOVER-edit-acquire-p3.md
```
Cosa è VERIFICATO e funziona (da NON rompere coi due fix):
- **P3 ricalco su piano dichiarato** (`d`): click→pixel→`pixel-ray`→∩piano→punto
  frame-oggetto; polilinea world; riproiezione live cambiando foto; persistenza
  `:retrace`; emissione `(poly …)`. Math a 0mm (test). Ma il ricalco vero è
  bloccato dai problemi di registrazione (piano giusto = faccia giusta, e le
  facce sono incoerenti tra rami).
- **Colore-faccia attivo** (`retrace-face-colors` + `active-face-quad` + bottoni
  colorati) — è LO strumento che ha reso visibile lo spacco di Klein.
- **Toggle proxy** in modalità gizmo (`v` / "Nascondi proxy"), proxy MAI in
  `:retrace`, stato persistente.
- **Persistenza**: salva su commit gizmo (`on-photo0-commit!`/`on-inv-commit!`),
  `camera-pose-0` salvata+riletta (round-trip foto 0 = 0mm verificato).
- **`^:dev/after-load reinstall-after-hot-reload!`** — riarma tastiera+pannello+
  preview su hot-reload con sessione aperta (altrimenti l'hot-reload NON aggiorna
  tastiera/pannello: gotcha ripetuto in questa sessione).

Nessun test rotto (Node suite verde; `backproject_test` nuovo).

## WORKFLOW / GOTCHA (imparati a caro prezzo in questa sessione)

- **Hot-reload ≠ ricarico**: un hot-swap rifà i disegni ma NON riarma il keydown
  installato né ricostruisce il DOM del pannello, a meno che l'hook after-load
  (ora c'è) non giri. Per certezza: **ricarico completo pagina (Chrome ⌘⇧R) +
  ri-esegui `edit-acquire`**. La webview di Tauri spesso NON fa un vero reload
  (cache WKWebView) — per iterare usa Chrome su localhost:9000.
- **Compilare**: NON lanciare `shadow-cljs compile` a mano accanto al watcher.
  Ricompila via nREPL CLJ: `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.
  devtools.api :as api]) (api/watch-compile! :app)"` → `:ok`.
- `acquire-state.json` è un sidecar **gitignored** (giusto). Le mie ispezioni
  Playwright l'hanno toccato (salvato camera-pose-0, azzerato il ricalco vecchio,
  piano su "Sopra"): stato attuale = ricalco vuoto, plane axis 1. La sessione ha
  ancora lo **spacco di ramo** (θ180,270 flippate).
- Vincenzo NON è uno sviluppatore quotidiano: nelle sue istruzioni operative usa
  la sezione **"Prossimi passi per te"** in italiano, passi numerati, un'azione
  ciascuno, nomi esatti di bottoni/comandi (vedi `CLAUDE.md`).

## PRIMA MOSSA CONSIGLIATA nella chat nuova

1. Ricarica pulito + apri la sessione di prova; **co-verifica a schermo con
   Vincenzo** quali foto stanno su quale ramo (mostragli i colori-faccia) — così
   il "θ0 vs θ180" si chiude prima di scrivere codice.
2. Fai prima il **fix (2)** (piccolo, sblocca subito il ritocco della foto 0),
   poi progetta il **fix (1)** col brief P2 sotto mano.
