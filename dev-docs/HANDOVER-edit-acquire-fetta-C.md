# HANDOVER — fetta C: detector automatico della corona (registrazione zero-click)

**Branch**: `edit-acquire-registration-stability` (committato + pushato su origin)
**Data**: 2026-07-29
**Task della prossima chat**: la **fetta C** del brief A/B/C — il software trova
da solo i dischetti della corona nella foto, li identifica (con lo zero-indice) e
registra **senza nessun click**. Il `p` (fetta A/B) resta come correzione manuale.

> **Inquadramento onesto**: fette A (4 click → proposte), B (4 dischetti qualsiasi
> → identità automatica) e l'**anello** (`f`: registra alcune, proponi le altre)
> sono FATTE, collaudate dal vivo e committate. Il canale piatto **funziona già
> bene**. La fetta C toglie gli ultimi click: è una comodità, non un blocco. Se il
> detector su foto reali si rivela fragile (riflessi, il pezzo scuro, i numeri
> stampati sulla variante carta), il fallback è tutto ciò che c'è già.

---

## Cosa c'è di pronto da RIUSARE (tutto committato + node-testato)

La fetta C è per il ~70% **assemblaggio** di mattoni esistenti. Il pezzo davvero
nuovo è UNO: il detector di blob su tutto il fotogramma.

- **`blob/snap-to-blob [lum-at center radius opts]`** → `{:center [u v] …}` o nil.
  Centroide sub-px di un dischetto scuro in una finestrella locale (mean-shift).
- **`blob/disc-at? [lum-at [u v] r opts]`** → bool. Giudice economico "c'è un
  disco scuro qui?" (centro scuro + anello più chiaro). ~9 campioni. **Validato
  sui pixel reali: 12/12, 0 falsi positivi** (`scratchpad/disc_check.js`).
- **`match-plate/assign-marks [clicks marks zero-obj intr disc-at? {:disc-r :face-normal}]`**
  → `{:assignment {click-idx→mark-idx} :pose :crown-hits :zero-hit? :score}`.
  Dà l'IDENTITÀ a un insieme di dischetti-corona: ricerca esaustiva ordine-ciclico
  + rompi-simmetria con lo **zero-indice** + filtro **front-facing** (scarta lo
  specchio) + scorer a due passi (coarse → LM refine → tight). **Questo è il cuore
  dell'identificazione: la fetta C gli dà blob rilevati invece di click.**
- **`match-plate/find-ring-pose [ref-pose marks zero-obj intr disc-at? {…}]`** →
  ricerca a 1 DOF (usata dall'anello): se una foto è già registrata, ne predice
  un'altra dell'anello. Utile come SEED cross-foto anche per la fetta C.
- **`bridge/plate-detect [proxy-mesh]`** → `{:zero-obj :disc-r :face-normal}` (nel
  frame oggetto). **`bridge/pnp-target-points [proxy-mesh cam]`** → i 12 mark
  `{:id :obj :world :visible?}` (esclude `:zero`).
- **`pnp/solve-pnp [corr intr {:method :planar}]`** → posa finale (omografia planare
  + LM + scarto outlier).
- **`registration-plate [& {:keys [d marks disc h]}]`** — il proxy nativo (cilindro
  + corona + zero-indice + `:mark-disc-r`), frame identità già tarato per il solver.
- **`acquire-backdrop/load-luminance-sampler [file]`** → Promise `{:lum-at :size}`:
  pixel di UNA foto OFF-SCREEN (per il batch, senza toccare la vista). Foto corrente:
  **`acquire-backdrop/luminance-at [x y]`**.
- In `edit_acquire.cljs`: `session-intrinsics [iw ih]`, `pnp-targets`,
  `solve-and-apply!`, `propose-and-snap!`, `on-fit-ring!` (modello di flusso batch),
  `plate-proxy?`.

## Cosa serve COSTRUIRE

### 1. Detector di blob su tutto il fotogramma (IL pezzo nuovo)
Trova i centroidi (sub-px) dei dischetti scuri **senza** una posa/predizione.
`snap-to-blob` oggi lavora in una finestrella data una predizione; qui serve la
scansione GLOBALE. Nucleo PURO in un ns dedicato (es. `ridley.photogrammetry.blob-detect`),
che prende `lum-at` come gli altri → node-testabile su scena sintetica + su foto
reali (sharp). Approcci possibili (da valutare sui dati veri):
- soglia adattiva (il piatto è chiaro, i dischi scuri) → componenti connesse →
  filtro forma/dimensione (tondi, raggio in un range) → centroide + `snap-to-blob`
  per il sub-px;
- oppure scala-spazio / scansione a griglia grossa + `disc-at?` per confermare +
  `snap-to-blob` per rifinire.
Output: lista di `{:center [u v] :radius-px …}` candidati (corona + rumore + il
pezzo + i numeri stampati + lo zero-indice: il detector NON deve distinguerli, ci
pensa il passo 2).
**Nota scala**: senza posa non conosci il raggio-disco in px → gestisci un RANGE
di dimensioni; il fit-corona (passo 2) sceglie il sottoinsieme a dimensione
coerente. Prospettiva: la corona è un CERCHIO nel piano oggetto → un'ELLISSE
nell'immagine; non fittare un cerchio in 2D, usa l'omografia (passo 2).

### 2. Fit-corona: SELEZIONA la corona dai blob rilevati (RANSAC) → identità
`assign-marks` oggi assume che i `clicks` SIANO i mark-corona (≤ 12). Il detector
dà un SOVRAinsieme con outlier. Serve un wrapper che scelga il sottoinsieme-corona:
- **RANSAC**: campiona 4 blob rilevati, trattali come 4 mark-corona (ignoti quali),
  risolvi l'omografia (come dentro `assign-marks`), riproietta i 12 mark + lo
  zero-indice, conta quanti blob RILEVATI cadono vicino alle riproiezioni
  (consenso). Il modello con più consenso + `zero-hit?` vince.
- In pratica: probabilmente puoi RIUSARE `assign-marks` quasi intatto passandogli
  i 4 blob campionati come `clicks` (già fa la ricerca ordine-ciclico + zero-indice
  + front-facing); il wrapper RANSAC aggiunge solo (a) il campionamento dei 4 tra
  gli N rilevati e (b) il conteggio di consenso sui blob rilevati (non su disc-at?,
  che rileggerebbe i pixel). Valuta se estendere `assign-marks` con "scegli k-di-N
  click con outlier" invece di un RANSAC esterno.
- Lo **zero-indice** è tra i blob rilevati ma NON è un mark-corona (è a raggio
  INDEX-R, interno): `assign-marks` lo gestisce già via `zero-obj` riproiettato →
  non serve pre-classificarlo.

### 3. Solve + registra (riuso puro)
Dai mark-corona identificati → `blob`-snap fine → correspondenze → `pnp/solve-pnp`
→ posa → `bridge/solver-pose->camera` → salva `:camera-poses`/`:acquire-results`
(vedi `solve-and-apply!`/`on-fit-ring!` come modello). Se il consenso è basso o
l'rms alto → NON registrare, lascia la foto al `p` manuale (fail sicuro).

### 4. UI
Un tasto/bottone (es. "Auto" o `a`) in `edit_acquire`: registra automaticamente la
foto corrente (o tutte, batch async come `on-fit-ring!` con `load-luminance-sampler`).
`p`/`b`/`f` restano. Dispatcha su `plate-proxy?`.

## Come VALIDARE (come tutto il resto del canale)

1. **Detector puro node-testato**: scena sintetica (dischi generati) + **foto REALI**
   (decodifica con `sharp`, pattern in `scratchpad/disc_check.js`). I pixel-disco veri
   sono noti: `test-assets/param-plate-{one,paper}/acquire-state.json` ha i click reali
   (`pnp.<idx>.picks.<mark>.px`) e le pose registrate (`photos.<idx>.camera-pose`).
   Confronta i blob rilevati coi pixel veri (recall/precision).
2. **Pipeline intera** su foto reali: detector → fit-corona → solve → confronta la
   posa con quella registrata a mano (dovrebbe combaciare; rms basso).
3. **Gate live**: Vincenzo apre `(edit-acquire "dir" {:proxy (registration-plate)})`,
   preme "Auto", verifica che le foto si registrino (fil di ferro combacia). Poi commit.

## File / entry point

- NUOVO: `src/ridley/photogrammetry/blob_detect.cljs` (detector globale, puro) +
  `test/ridley/photogrammetry/blob_detect_test.cljs`.
- `src/ridley/photogrammetry/match_plate.cljs` — aggiungi il wrapper fit-corona
  (RANSAC/selezione) accanto a `assign-marks`/`find-ring-pose`; estendi
  `match_plate_test.cljs`.
- `src/ridley/editor/edit_acquire.cljs` — tasto "Auto" + flusso (modello:
  `on-fit-ring!`, `register-one-ring-photo!`).
- `src/ridley/photogrammetry/plate.cljs` — il proxy per i test.

## Note REPL / build (IMPORTANTE — ci ho perso tempo)

- **Tab browser STALE**: cambi a `pnp`/`bridge`/`edit_acquire`/`blob`/`match-plate`/
  `plate` NON si hot-reloadano nella tab aperta → verifica via **suite node** (compila
  fresco).
- **Compila via nREPL in modo CLJ, NON CLJS**: se prima hai fatto `(shadow/repl :app)`
  per evals nel browser, DEVI `:cljs/quit` prima, altrimenti
  `(shadow-api/compile …)` gira in CLJS e **è un no-op silenzioso** (ci ho sbattuto).
  `clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as shadow-api])
  (shadow-api/compile :test)"` → poi `node out/test.js`. ~7 min.
- **Il nREPL CLJS finisce su una tab a caso** (spesso vuota/vecchia) → inutile per
  ispezionare la sessione live o testare codice nuovo. Fidati della suite node.
- **`sharp`** è disponibile (decodifica JPEG in node); esegui gli script dalla
  **root del progetto** (risolve `sharp` dai node_modules locali).
- Il proxy per i test: `(registration-plate)` (nativo) o il replay `:obj`
  `[58cos(30i) 58sin(30i) 1.5]`, intrinsics `k*` = 48mm-eq su 4032×3024.

## Memoria

`project_param_acq_gate_v1_plate_v2.md` (aggiornata: fette A/B, anello,
registration-plate — tutto committato+pushato; fetta C = prossimo). Handover
precedenti: `HANDOVER-edit-acquire-fetta-B.md` (design B),
`HANDOVER-edit-acquire-fetta-B-live-gate.md`.
