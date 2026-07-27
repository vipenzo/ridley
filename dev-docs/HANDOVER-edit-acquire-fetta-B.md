# HANDOVER — fetta B (registrazione piatto SENZA identità)

**Branch**: `edit-acquire-registration-stability`
**Data**: 2026-07-27
**Task della prossima chat**: costruire la **fetta B** — l'utente clicca 4
dischetti *qualsiasi* (senza dichiarare quale mark è quale) e il software assegna
le identità da solo. Toglie l'unica fatica rimasta (contare i mark dallo
zero-indice) e funziona **sul piatto di carta che c'è già** (niente ristampa,
niente numeri da leggere).

---

## Dove siamo (tutto committato sul branch)

Catena PnP planare → fetta A → variante carta, tutta verde (suite **761/0**):

- `3f2b9cc` PnP planare (omografia per bersagli complanari) — il piatto ha i
  mark complanari, il DLT era singolare.
- `30cc793` UX registrazione piatto (viewport segue la posa, guard `s`/`f`).
- `b8aee7e` blob-snap (`ridley.photogrammetry.blob/snap-to-blob`) — centroide
  del dischetto scuro, mean-shift, puro/node-testato.
- `60cb7dd` **fetta A**: `p` sul piatto = 4 click → omografia seed → riproietta
  i mark restanti → blob-snap → 2° solve. `solve-and-apply!`, `min-pnp-picks`=4,
  avanzamento sparpagliato, tasto `o` (occlusi).
- `59f7ee0` generatore SVG del foglio (`ridley.export.plate-svg/marks->svg`:
  dischi mm esatti + 2 barre di scala + numeri 0-11 sbiaditi) + `save-svg`.
- `e7ecac1` **fix sessione-fresca** (LEGGI: cambia il comportamento dei mark):
  - `bridge/pnp-target-points` ora `:visible? true` per TUTTI i mark del piatto.
    Il front-facing per-mark era una nozione da BOX; la vista di default inquadra
    il *sotto* del piatto → nascondeva tutti i mark su una sessione fresca. Il PnP
    planare è seedless, non serve posa. Occlusione = tasto `o`. (`mark-front-facing?`
    rimosso; il box tiene `box-fit/visible-corners`.)
  - **seed-snap**: un click su un mark si aggancia al centroide (blob-snap) come
    le proposte → tutti i pick sono centroidi sub-px, l'utente clicca *grosso modo*.
- `9981c17` esempio: **variante carta**. `piatto-carta = (assoc piatto :anchors
  marks-carta)` — RIUSA la mesh di `piatto`; costruirlo da `plate-solid` grezzo dà
  un creation-pose ruotato (dal `rotate` del cilindro) che ribalta i mark (bug
  trovato live). Scala per-asse dalle barre (`MEASURED-X/Y`), foglio numerato.
- `e43d751` docs (brief variante carta + NOTE.md sessioni; foto/json fuori dal repo).

### Stato PRECISIONE — pavimento OTTICO, obiettivo raggiunto

Il seed-snap ha reso *tutti* i pick centroidi sub-px e l'RMS **non si è mosso**
(4.4 → 4.58px). Quindi il pavimento **non** sono i click: è **ottica**
(distorsione ai bordi del frame + defocus/DoF sui mark lontani + planarità della
carta). **4.5px è 3× meglio dei 14px del lettore → l'obiettivo del piatto è
raggiunto.** Scendere sotto i 2px è lavoro d'ottica (angolo/distanza/luce), lato
Vincenzo — NON software. La fetta B è **ergonomia**, non precisione.

Test-asset (untracked, solo NOTE.md committati): `param-plate-one/` (piatto 3D),
`param-plate-paper/` (piatto carta, 10 foto θ=libera). `acquire-state.json` di
ognuno ha i click reali (utile per replay).

---

## Fetta B — il design (interazione GIÀ scelta: opzione 1)

**Interazione (Vincenzo ha scelto la 1)**: in modalità `p`, un toggle (proposto
`b` = "batch") attiva "senza identità": l'utente clicca 4+ dischetti *qualsiasi*
(nessun marker armato), ognuno si aggancia al centroide (riusa il seed-snap). Un
trigger (es. `r`) lancia l'assegnazione + registra. Fuori dal toggle resta la
fetta A. **Tieni ENTRAMBE le strade** (la manuale-armata è la rete di sicurezza).

### L'algoritmo: ricerca completa, l'immagine come giudice

Niente stima d'angolo sull'anello (fragile in prospettiva). Ricerca esaustiva:

1. I 4 click (centroidi blob-snap) in **ordine ciclico** nell'immagine (angolo
   attorno al loro centroide).
2. Enumera le assegnazioni candidate **che preservano l'ordine ciclico** (una
   camera non-specchio preserva l'ordine): c0..c3 → 4 mark dei 12 in ordine
   ciclico. Sono `12 * C(11,3) = 1980` candidati (= 495 sottoinsiemi × 4
   rotazioni). Pochi ms.
3. Per ogni candidato: risolvi l'**omografia** dai 4 (`pnp/estimate-homography`),
   **riproietta tutti i 12 mark**, e conta quanti cadono su un dischetto scuro
   vero (giudice economico `disc-at?`, sotto).
4. **Rompi la simmetria a 12 con lo ZERO-INDICE** (punto SOTTILE, vedi sotto).

### ⚠ Punto sottile #1 — la corona è simmetrica a 12, serve lo zero-indice

I 12 mark sono a 30° l'uno dall'altro: un'assegnazione **ruotata di 30°**
(clicchi → mark {1,4,7,10} invece dei veri {0,3,6,9}) dà una posa ruotata di 30°
→ i 12 mark riproiettati cadono sulle discs ruotate di 30° → **anche loro su
discs vere** → punteggio 12/12 identico! Quindi il punteggio-corona da solo NON
rompe l'ambiguità rotazionale: sopravvivono ~12 rotazioni. Le distingue solo lo
**zero-indice** (il pallino asimmetrico interno): riproietta la sua posizione
attesa (radialmente DENTRO m00) e controlla `disc-at?` — solo l'orientamento
giusto ci trova il pallino. Metti lo zero-indice nel punteggio: `score =
(#mark-su-disc) + (zero-indice-su-disc ? bonus-grosso : 0)`.

### ⚠ Punto sottile #2 — lo zero-indice NON è in :anchors

`(:anchors piatto)` ha **12** mark (m00-m11). Lo zero-indice è un **13° disco**
(visivo, a raggio `INDEX-R`=52, radiale su m00) ma NON un anchor. La fetta B ha
bisogno della sua **posizione obj** per il tiebreak. Decisione da prendere:
esporlo sulla mesh (es. chiave `:zero-anchor`/`:asymmetry` o un anchor
distinto marcato), che sia il generatore del piatto a mettercelo. Nel frame
oggetto: `world->local` della sua posizione, stesso frame dei mark (coplanare).
Il generatore lo conosce già (`index-center` in `examples/param-acq-plate.clj`).

### Il giudice `disc-at?` (economico, NON blob-snap 1980×12 volte)

Dato un pixel previsto `p` e il raggio-disco atteso in px (dalla scala della
posa): dischetto presente se `luminance-at(p)` è SCURO **e** l'anello a ±r
(`p±[r,0]`, `p±[0,r]`) è per lo più CHIARO (≥3/4). Pochi campioni per candidato.
Soglia: adattiva locale meglio dell'assoluta (esposizione variabile). Il
raggio-disco in px lo stimi proiettando due punti a distanza nota (es. DISC-R)
sul piano con la posa candidata, o dalla spaziatura mark riproiettata.

### Nucleo puro prima (come il blob-snap)

Fai il **nucleo puro** in un ns testabile (`ridley.photogrammetry.match-plate`
o simile): `(assign-marks clicks mark-objs zero-obj intrinsics disc-at?)` →
`{:assignment {click-idx→mark-id} :pose :score}` o nil. Prende `disc-at?` come
funzione (come `snap-to-blob` prende `lum-at`) → node-testabile su una scena
sintetica: posa camera nota, proietta i 12 mark + zero-indice, `disc-at?` finto
che risponde vero sui pixel dei dischi; verifica che recuperi l'assegnazione
giusta (incluso l'orientamento via zero-indice) e rifiuti click ambigui/clusterati.
Poi aggancia all'UI (toggle `b`, click→centroide, trigger→assign→fetta A propose).

### Dettaglio da confermare con Vincenzo

Per un'omografia ben condizionata i 4 click vanno **un po' sparsi**. Proposto:
un avviso gentile se troppo ravvicinati ("clicca più sparsi") invece di lasciar
fallire il consenso in silenzio. Vincenzo non ha ancora confermato — chiediglielo.

---

## File / entry point

- `src/ridley/photogrammetry/pnp.cljs` — `estimate-homography` (posa dai 4),
  `solve-pnp` (finale). Riusa così com'è.
- `src/ridley/photogrammetry/blob.cljs` — `snap-to-blob` (centroide click) +
  il `disc-at?` economico può vivere qui o nel nuovo ns.
- `src/ridley/photogrammetry/bridge.cljs` — `pnp-target-points` dà i mark
  (`:obj` complanari, `:id`, `:world`). Lo zero-indice va aggiunto qui o esposto
  dalla mesh.
- `src/ridley/editor/edit_acquire.cljs` — `on-solve-pnp!` (~1470,
  `solve-and-apply!`/`propose-and-snap!`), `pnp-on-pointerdown` (il seed-snap +
  il toggle vanno qui), `start-pnp!`/il key handler (aggiungi `b`), il pannello.
- `src/ridley/editor/acquire_backdrop.cljs` — `luminance-at` (pixel foto per il
  giudice), `screen-of-pixel`.
- `examples/param-acq-plate.clj` — struttura mark (12 corona + zero-indice
  `index-center`), `piatto`/`piatto-carta`. Da qui esporre lo zero-indice.
- Sessione reale: `test-assets/param-plate-paper/` (piatto carta, 10 foto,
  `acquire-state.json` coi click).

## Note REPL / build (IMPORTANTE)

- **Codice host stale nella tab**: cambi a `pnp`/`bridge`/`edit_acquire`/
  `blob`/`plate-svg` NON si verificano live (hot-reload salta la tab).
  Verifica via **suite node** (compila fresco): `pnp`/`bridge`/`blob`/`match`/
  `plate-svg` sono ns puri → node-testabili.
- Compila via nREPL shadow-api (**mai** `npx` in parallelo al watcher):
  `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as shadow-api])
  (shadow-api/compile :test)"` — autoruns la suite (~7 min). Per l'app Vincenzo:
  `(shadow-api/compile :app)` poi lui fa **Cmd+Shift+R**.
- L'**esempio** `.clj` è SCI (non compilato in :app): basta ri-valutare
  `(load-file …)`, niente ricompila/reload. Le "unresolved symbol" del linter
  sui binding Ridley (`cyl`/`color`/`marks->svg`…) sono falsi positivi normali.
- Aprire la sessione piatto: `(load-file "examples/param-acq-plate.clj")` E
  `(edit-acquire "test-assets/param-plate-paper/" {:proxy piatto-carta})` nella
  **stessa** valutazione (reset-ctx! azzera i `def`).

## Memoria

`project_param_acq_gate_v1_plate_v2.md` (aggiornata a fine sessione — variante
carta committata, pavimento ottico, fetta B come prossimo).
