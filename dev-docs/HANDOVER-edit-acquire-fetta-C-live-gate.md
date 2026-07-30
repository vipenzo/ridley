# HANDOVER — fetta C (detector automatico della corona): COSTRUITA, node-testata, ATTENDE GATE LIVE

**Branch**: `edit-acquire-registration-stability`
**Data**: 2026-07-29
**Stato**: costruita e node-testata (sintetico + geometria reale + JPEG reale via
sharp). **NON committata**. Manca il **gate live** (Vincenzo preme "Auto" su una
sessione vera). Brief di partenza: `dev-docs/HANDOVER-edit-acquire-fetta-C.md`.

---

## Cosa fa (in una frase)

Con la registration-plate aperta, il tasto **`a`** (o il bottone **"Auto — rileva e
registra"**) registra **tutte** le foto **senza un click**: per ogni foto il
software trova da solo i dischetti scuri della corona su tutto il fotogramma, li
identifica (lo zero-indice rompe la simmetria a 12), e risolve la posa via PnP. Il
`p` (fetta A/B), la `b` (identità-free) e la `f` (anello) restano intatti come
correzione/fallback.

## Cosa è stato costruito (3 pezzi)

1. **`src/ridley/photogrammetry/blob_detect.cljs`** — `detect-blobs [lum-at [w h]
   opts]`, il detector GLOBALE (il pezzo davvero nuovo). Puro sopra `lum-at` come
   gli altri. Pipeline: downsample ~3× (block-average) → soglia adattiva locale
   (integral image, finestra ≫ disco) → componenti connesse (flood-fill 8-conn) →
   filtro forma (area/aspect/fill) → top-K per area·fill. Restituisce
   `[{:center [u v] :radius-px :area :fill} …]`, ≤ `:max-blobs` (40). I default
   sono tarati sul regime foto-telefono (~4000px, disco 15–40px di raggio): recall
   **9–12/12** della corona sulle foto ben inquadrate, ~15–40 candidati.
2. **`match-plate/fit-crown [blob-pts marks zero-obj intr disc-at? opts]`** — il
   wrapper che SELEZIONA la corona dal sovrainsieme del detector e la IDENTIFICA.
   RANSAC su quartetti "spread" (deterministico, `spread-samples`: semini
   dall'esterno + vicini a +90/180/270 → 4 mark di corona ben distribuiti) →
   ogni quartetto passa a `assign-marks` INTATTO (che fa già identità + zero-indice
   + rifiuto specchio + LM). Early-exit al primo fit forte. Ritorna
   `{:pose :crown-hits :zero-hit? :score :pixels {mark-idx [u v]}}` (come
   `find-ring-pose`) o **nil** (fail-safe) quando nessun quartetto supera la soglia.
3. **`edit_acquire.cljs`** — tasto **`a`** + bottone "Auto", dispatch su
   `plate-proxy?`. `on-auto-register!` registra ogni foto non ancora registrata
   SEQUENZIALMENTE (foto 0 prima — sposta il proxy come fa `p` lì; le altre come
   camera), OFF-SCREEN come l'anello. Per foto: `detect-blobs` → `fit-crown` →
   `blob/snap-to-blob` sui mark identificati → `pnp/solve-pnp` → **gate**
   (rms ≤ `accept-rms-px` = 12px e crown ≥ 8 + zero-hit) → applica, altrimenti
   **salta** (la foto resta per `f`/`p`). `apply-auto-solve!` replica esattamente i
   due rami di `solve-and-apply!`.

## Perché è fail-safe

Una foto difficile (scatto radente: i dischetti diventano ellissi sottili che il
filtro forma scarta; recall crolla) semplicemente **non** supera `fit-crown`
(niente zero-hit / crown < 8) → nil → la foto **resta** per l'anello `f` (da una
vicina registrata) o `p` a mano. Non registra MAI una posa sbagliata: zero-indice
+ soglia crown + barra rms sono tre guardie indipendenti.

## Validazione (node, deterministica)

- **`test/ridley/photogrammetry/blob_detect_test.cljs`** — scena sintetica (recupera
  i dischi, scarta parte/linee, centroidi sub-px), scena piatto proiettata
  (recall corona), e **test REALE guardato** (`real-photo-detect-and-fit`): decodifica
  `param-plate-paper/IMG_8938.jpeg` con `sharp`, gira il detector+fit-crown VERI in
  cljs, confronta col ground-truth (`acquire-state.json` picks). Salta pulito dove
  `sharp`/foto assenti (CI).
- **`test/ridley/photogrammetry/match_plate_test.cljs`** — `fit-crown` da un
  sovrainsieme sulla geometria REALE (`real-px-1`, param-plate-paper foto 1) +
  multi-posa sintetico + fail-safe senza corona.
- **`test/ridley/photogrammetry/plate_scene.cljs`** — renderer sintetico condiviso
  (proietta corona+zero, stampa dischi in un buffer luminanza).

Esplorazione dati reali (JS, `sharp`): recall del detector 9–12/12 sulle foto ben
inquadrate di ENTRAMBE le varianti (una/carta), fallisce solo i 2–3 scatti radenti
per sessione (attesi, coperti da `f`/`p`).

**Suite node: 778 test, 2950 assertion, 0 failures, 0 errors.** Recap fetta C:
detector scena piatto sintetica recall 11/12 e 12/12; **JPEG reale
(param-plate-paper foto 0): candidati 18, recall 12/12, fit-crown crown 11 zero
true**; fit-crown da sovrainsieme reale (16 blob) → crown 12 zero true; 3 pose
sintetiche → crown 12 zero true; fail-safe (nessuna corona) → nil.

## Gate live #1 (2026-07-29): FALLITO per performance → CORRETTO (non ri-gated)

Vincenzo ha premuto "Auto": "ci mette un sacco e il browser si sgancia" (in Chrome);
nell'app Tauri sopravvive ma lentissimo, **1/11 registrate**. Causa: il batch bloccava
il thread principale per decine di secondi → l'heartbeat del dev-server cadeva. Tre fix:

1. **Detector veloce (il collo di bottiglia)**: il downsample faceva ~**12M chiamate
   di CLOSURE `lum-at` per foto**. Ora `blob-detect/detect-blobs` accetta `:rgba` (l'array
   RGBA grezzo di `getImageData`) e usa `downsample-rgba` — UN loop stretto con letture
   inline, ~100ms invece di secondi. `load-luminance-sampler` ora ritorna anche `:data`.
2. **fit-crown limitato + ANELLO come 2° passo**: `:max-samples` 24→12 (una foto DIFFICILE
   non blocca più a lungo). E soprattutto: Auto ora fa **poche** foto col detector, poi
   passa le rimanenti all'**anello** (`register-one-ring-photo!`, ricerca 1-DOF, nessun
   detect per foto) — `on-auto-register!` → `ring-fill-then-finish!`. Molto meno lavoro.
3. **Yield + REPL stream**: `yield-frame` (setTimeout 0) tra una foto e l'altra → la pagina
   respira. E il progresso per-foto va nella **REPL** (`auto-log!` → pannello `repl-history`,
   PERSISTENTE) invece che nella status-line che sparisce in 4s (richiesta di Vincenzo).

**Da ri-gatare** con queste correzioni. Suite dopo i fix: **779 test, 0 fail** (incl.
`rgba-fast-path-matches` + real-photo test che ora usa il percorso `:rgba` di PRODUZIONE:
JPEG reale → candidati 18, recall 12/12, fit-crown crown 12 zero true). App build pulito.
Se il batch è ancora lento, il collo residuo è probabilmente `getImageData`
(~200-400ms/foto) o `fit-crown` sulle radenti — ridurre ulteriormente `:max-samples`,
o far registrare col detector solo 2-3 foto sparse e lasciare TUTTO il resto all'anello.

## Gate live #2 (2026-07-30): diagnosi in browser (Playwright) — DUE cause

Gate #2: **param-plate-paper 7/10** (funziona, focale 48 EXIF corretta), **param-plate-one
0/11 e lentissimo**. Diagnosi diretta nel browser (Playwright + `window.ridley.*`,
`:simple` espone tutto), non a indovinare:

- **DETECTOR e FIT-CROWN FUNZIONANO** su param-plate-one nel browser (foto 9: 20 blob,
  recall 12/12, fit-crown crown 12 zero true; pixel browser = pixel sharp, contrasto
  166-172). Il rgba fast-path è 3× più veloce del lum-at (92 vs 291ms). Quindi NON è né
  il detector né i pixel.
- **CAUSA 1 — FOCALE SBAGLIATA (il vero 0/11)**: `param-plate-one/acquire-state.json` ha
  `focal {mm 22, source manual}`; l'EXIF (FocalLengthIn35mmFilm, come param-plate-paper)
  dice **48**. `apply-loaded-state!` ripristina la focale salvata SOPRA l'EXIF (by design:
  la scelta manuale vince). A 22mm gli intrinsics sono sbagliati → fit-crown non aggancia
  (provato: foto0 a 22mm = NIL, a 48mm = crown 10) e macina (lento). **Vincenzo deve
  rimettere la focale a 48 (slider Focale) o resettarla**; nuovo hint nella REPL "⚠ 0
  registrate: controlla la FOCALE".
- **CAUSA 2 — FIT-CROWN LENTO su viste OBLIQUE**: ogni campione RANSAC = ~3960 omografie
  (`estimate-homography` 92µs) → ~0.4-1s/campione; ~metà delle foto oblique param-plate-one
  non trova un quartetto tutto-corona al primo campione → tanti campioni → lento.
  Tentata la restrizione ai sottoinsiemi a passo bilanciato (gap 2-4) MA le viste oblique
  mappano quartetti a 90°-immagine su mark a gap 1-5 → rompeva foto9/foto0 → REVERTITO.
  Il collo vero (3960 omografie/campione) resta: il fix pulito è un algoritmo diverso
  (fit ellisse per selezionare la corona → 1 assign-marks), NON fatto.

## Mitigazioni applicate (round 2)

- **Detector rgba fast-path** (già round 1) — 92ms/foto.
- **Judge GEOMETRICO** in fit-crown: "il disco è colpito" = un blob RILEVATO entro r
  (niente riletture pixel nei ~3960 candidati/campione). L'accuratezza finale resta dal
  blob-snap + PnP + gate rms reali. ~40% più veloce/campione.
- **top-16 blob** (auto-fit-blobs) + **seeding per punteggio** (spread-samples semina in
  ordine d'ingresso = score) + **partner ad alto punteggio** (nella finestra ±45° prende
  l'indice più basso = più corona) → primo campione tutto-corona più spesso.
- **max-samples 8** (bound): una foto difficile fallisce in ~3-4s (→ anello), non 10s.
- **Anello 2° passo** + **REPL log** + **yield** (round 1).
- **hint focale** quando 0 registrate.

**Stato onesto**: fetta C funziona su scatti ben inquadrati con focale GIUSTA (paper 7/10);
gli obliqui difficili cadono su anello/`p`. Il 0/11 di param-plate-one è la FOCALE 22mm.
Speed migliorata ma non istantanea. Ottimizzazione vera (fit-ellisse) = follow-up.

## Round 3 (2026-07-30): FIT-ELLISSE — riscrittura del cuore (Vincenzo ha scelto questa)

Il collo (3960 omografie/campione × tanti campioni) è ELIMINATO sostituendo la ricerca
a quartetti con una selezione GEOMETRICA della corona:

- **NUOVO `ridley.photogrammetry.ellipse/fit-inliers [pts opts]`**: i 12 mark della
  corona stanno su un CERCHIO → nell'immagine su un'ELLISSE; lo zero-indice (dentro) e
  il rumore (fuori) no. RANSAC conico (5 punti, `f=1` fissa la scala + `la/solve`, come
  h33 in `estimate-homography`; Hartley-normalizzato o il 5×5 è degenere; check ellisse
  `b²−4ac<0`; distanza di Sampson per gli inlier; PRNG LCG seminato → DETERMINISTICO)
  → gli inlier SONO la corona.
- **`match-plate/fit-crown` riscritta**: (1) `ellipse/fit-inliers` → ~12 mark puliti;
  (2) `assign-marks` su quei 12 → k=12 = UN sottoinsieme × 12 rot × 2 ≈ **24 candidati**
  (non 3960). UN fit ellisse + UN solve identità per foto, non una tempesta.
  `spread-samples`/gap-band RIMOSSI (morti).
- **RISULTATO (browser, Playwright, 9 foto reali one+paper, focale 48)**: **7/9 registrate**
  (crown 10-12, zero true), **totale 1.9s** (era 32s) = ~208ms/foto medio, ~**170× più
  veloce**. I fallimenti FALLISCONO VELOCI (~160-420ms, non 5s) → anello/`p`. Le 2 non
  registrate = scatti radenti estremi paper (40 blob, recall detector bassa già in
  origine).
- **Default**: `auto-fit-blobs` 24 (più blob = l'ellisse riprende le foto rumorose),
  `ellipse-thr` 0.04, `ellipse-iters` 250, `min-crown` 8.
- Test: `ellipse_test` (selezione inlier su ellisse+outlier) + i `fit-crown` esistenti
  invariati (interfaccia uguale).

**Ora l'unico limite è la FOCALE** (param-plate-one a 22mm) + gli scatti radenti estremi.
Da RI-gatare con focale corretta.

## Prossimo passo: GATE LIVE (Vincenzo)

Aprire in Chrome (localhost:9000), valutare
`(edit-acquire "test-assets/param-plate-one" {:proxy (registration-plate)})`,
premere **"Auto — rileva e registra"** (o `a`). Attese: la maggior parte delle foto
si registra da sola (il fil di ferro combacia); le radenti restano — premere `f`
(anello) per propagarle, o `p` a mano. Se il gate passa → **commit**.

## Note tecniche

- Params detector in `blob-detect/default-opts` (in px DOWNSAMPLED, regime ~4000px).
- `min-crown-assign` (8) è la soglia crown condivisa con la fetta B.
- Il detector tocca ~12M `lum-at`/foto (downsample): il batch è async con status;
  se dal vivo è lento, si può aggiungere un fast-path su array RGBA grezzo.
- **REPL**: cambi a `blob-detect`/`match-plate`/`edit-acquire` NON si hot-reloadano
  nella tab aperta → verifica via suite node. Compilazione `:test`: `npx shadow-cljs
  compile test` (client separato, evita lo stato-sessione dell'nREPL) → `node
  out/test.js`.
