# Handover — acquisizione parametrica (chat enorme → nuova chat)

Aggiornato 2026-07-20. Questo documento basta a riprendere il lavoro senza
rileggere la chat precedente. Leggi anche, nell'ordine:
`dev-docs/acquisizione-parametrica-design.md` (il design + tutti gli
accertamenti in coda) e i `NOTE.md` delle sessioni foto.

---

## In una riga

Il canale "foto → geometria parametrica" ha un prototipo funzionante: dai click
sugli spigoli di un box, con le quote note dal calibro, si ricostruiscono le
pose delle camere e si misura il pezzo. **Il test del nastro è ESEGUITO
(2026-07-20): verdetto MATERIALE** — vedi sotto. La stima intrinseche (Coda §1)
è quindi esclusa; la prossima leva di accuratezza è il **fillet-blend (Coda §2)**.

## ESEGUITO 2026-07-20 — verdetto del test del nastro: MATERIALE

Nastrato (6 foto, `test-assets/param-acq-box-tape/`, calibro col nastro
60.2/20.2/40.1): firma bias **0.171 / 0.168 mm/lato** (Δ −0.342 / −0.337 mm),
per-lato costante. Nudo (14 foto): **0.51 / 0.49 mm/lato**. Il bias per-lato è
**crollato ~3×** solo coprendo di nastro carta opaco → la causa dominante era il
**PLA traslucido** (subsurface scattering). Intrinseche **escluse**: sarebbero
rimaste ~0.5 e proporzionali (% costante); qui la firma è restata
assoluta-costante mentre le % divergono (−0.6% / −1.7%). GATE 2: hold-out 4.34
px = **1.2×** (meglio del nudo 1.9×). Consistenza assoluta ancora aperta (1 solo
punto in ≥2 viste, RMS 380 px — servono click dello stesso spigolo del piano).

Conseguenze: Coda §1 (principal point + k1) NON serve. Il residuo 0.17 mm/lato è
firma-silhouette = arrotondamento reale degli spigoli → è **Coda §2
(fillet-blend)**, la prossima leva. Vincolo da documentare: **superfici opache**
(nastrare/opacizzare i print traslucidi). Nota: GATE 1 in assoluto è ancora
sopra la soglia 0.2 mm (Δ 0.34), ma il *discriminante* del test è chiuso.

## Come è stato eseguito il test del nastro (per replica)

Le foto sono pronte in `test-assets/param-acq-box-tape/` (blocco coperto di
nastro carta opaco, 6 foto, calibro col nastro 60.2 / 20.2 / 40.1 mm). Il
`NOTE.md` lì dentro spiega il perché.

```bash
npx shadow-cljs compile paq
node out/paq.js --init-session test-assets/param-acq-box-tape   # legge il NOTE
python3 -m http.server 8099
#   → http://localhost:8099/scripts/param-acq-tool.html?s=param-acq-box-tape
# clicca gli spigoli di 4 foto (3 gruppi: verticali/alto/basso), snap con 's'
# esporta EDN → test-assets/param-acq-box-tape/picks.edn
node out/paq.js --check test-assets/param-acq-box-tape/picks.edn   # conteggi ok?
node out/paq.js --match test-assets/param-acq-box-tape/picks.edn
```

**Come leggere l'esito** — guarda la "firma del bias" nel GATE 1, e confrontala
con quella del blocco NUDO (sessione `param-acq-box`, registrata sotto):

| esito sul nastrato | verdetto |
|--------------------|----------|
| bias per-lato **crolla** (→ ~0 o leggermente positivo) | **materiale** (subsurface scattering del PLA traslucido). Il metodo è certificato sul pezzo teso; si documenta il vincolo "superfici opache". |
| bias per-lato **resta ~0.5 mm/lato** | **intrinseche**. Parte il lavoro in coda: stima di principal point + k1 (vedi "Coda"). |

Riferimento dal blocco NUDO (14 foto, 2026-07-20):
`X 58.98 (Δ−1.02), Y 19.02 (Δ−0.98)`; firma **0.51 / 0.49 mm/lato** (costante),
**−1.7% / −4.9%** (varia col lato). Costante-in-assoluto = firma-silhouette;
proporzionale sarebbe firma-intrinseche. Vincenzo propende per il materiale.

---

## Lo stato dei due gate (blocco nudo, 14 foto)

- **GATE 1 (quote)**: ~1 mm in difetto per quota (bias sopra). NON sotto la
  soglia 0.2 mm. È il numero che il test del nastro deve spiegare.
- **GATE 2 (registrazione)**: hold-out 6.77 px vs baseline 3.65 px = **1.9×** →
  "la registrazione generalizza". **Primo pass del "registratore di camere"**,
  l'obiettivo del design doc (§ "Il prodotto visto dall'utente": clicco una
  foto, il viewport ci va). Marginale (soglia 2×) ma reale, ottenuto con la
  copertura a 360° delle 14 foto. Il giradischi ricostruito è coerente a 1.05°
  su tutto il giro — verifica indipendente che le camere sono davvero
  registrate l'una all'altra.

Manca ancora la **metrica di consistenza assoluta**: clic dello *stesso* punto
fisico (angolo del piano superiore) in ≥2 foto che si risolvono. Vincenzo non
li ha ancora messi. Il codice triangola e riporta l'RMS quando ci sono.

---

## Mappa del codice (tutto puro, cljs, `src/ridley/photogrammetry/`)

| file | cosa | note |
|------|------|------|
| `linalg.cljs` | solve gaussiano, Jacobi simmetrico | |
| `camera.cljs` | pinhole, posa Rodrigues, distorsione k1/k2, look-at | |
| `lm.cljs` | Levenberg-Marquardt, jacobiano numerico | |
| `box_fit.cljs` | fit del box a pose libere; `edge-silhouette-points` (fillet); `scale-constraint` accetta 1 o N vincoli | il fillet è **solo silhouette**, non crease — vedi "Coda" |
| `turntable_fit.cljs` | fit vincolato al giradischi (asse + angoli); `pose-at-angle` | fillet per-asse opzionale |
| `synth.cljs` | scene sintetiche, RNG seminato, `observations-rounded` | |
| `bootstrap.cljs` | **vecchio** flusso a ordine-di-click (superato); ma contiene ancora `pixel-ray`/`triangulate` usati dalla metrica di consistenza, e `hypotheses`/`group-of` usati da `--check` | non buttare: dipendenze vive |
| `match.cljs` | **il flusso attuale**: correspondence per-foto dalle quote note, ordine dei click irrilevante; `solve-photo`, `solve-session`, `fit-turntable`, `reproject-turntable`, `refine-from-pose`, `nearest-edge-residuals`, `twin-seed-pose` | |
| `cli.cljs` | l'eseguibile `node out/paq.js` (build `:paq` in shadow-cljs.edn) | tutti i comandi sotto |

Test: `test/ridley/photogrammetry/` — `box_fit_test`, `accuracy_study_test`
(EXP 1-6), `turntable_study_test` (7-8), `fillet_fit_test` (11-14),
`bootstrap_test` (15-17), `match_test` (18-19). **710 test verdi.**
Compila `:test` via il nREPL CLJ (`shadow.cljs.devtools.api/compile :test`),
MAI CLI in parallelo al watcher (corrompe `:app`). Esegui `node out/test.js`.

## Lo strumento e i comandi CLI

- **Tool**: `scripts/param-acq-tool.html`. Servi il repo con
  `python3 -m http.server 8099`, apri `?s=<nome-cartella-sessione>`. Legge
  `<sessione>/session.json`. Click a gruppi (verticali/alto/basso), snap ai
  gradienti (`s`), "Accetta proposte → click" per confermare le predictions,
  punti di registrazione (clic singolo), export EDN.
- `--init-session <dir>` — legge `<dir>/NOTE.md` (tabella foto/θ + calibro)
  come **unica fonte di verità**, scrive `session.json`.
- `--check <picks.edn>` — conteggi per gruppo; segnala sovra-click (max
  geometrico: vertical 3, top 4, bottom 4) e gruppi mancanti.
- `--match <picks.edn>` — il fit completo: per-foto → giradischi indipendente
  → GATE 1 (quote + firma bias) → GATE 2 (hold-out + consistenza) →
  predictions su tutte le foto della sessione.
- `--diagnose <picks.edn> <IMG>` — separa "click cattivi" da "ricerca fallita"
  su una foto: semina la posa dalla gemella Klein (θ±180), stampa residuo
  per-linea. Usato per risolvere il caso 8907.

## Decisioni già prese (non ri-litigare)

1. **Correspondence dai click NON dall'ordine.** L'ordine ha fallito due volte;
   ora `match.cljs` risolve ogni foto dalla geometria (quote note). L'ordine
   dei click è irrilevante (test: click invertiti → stesso risultato).
2. **Simmetria di Klein.** Un box è invariante sotto 4 rotazioni (identità +
   180° per asse). Le etichette valgono a meno di quelle: le foto possono
   risolversi in frame simmetrici diversi, ed è innocuo (stesse quote). I test
   usano `symmetry-equivalent?`.
3. **Il calibro viene dal NOTE della sessione**, non è cablato. Ogni sessione
   si misura contro il proprio pezzo (`adopt-session!`).
4. **La firma del bias** (mm/lato costante vs % costante) è il discriminante
   materiale-vs-intrinseche. Stampata a ogni run.
5. **Coverage seeding** (fix 2026-07-20): `solve-photo` semina best-per-costo
   *e* best-per-settore-di-azimut, così la posa vera non viene mai scartata.
   Prima 8907 (az~45°) finiva a 152 px. Regressione: test EXP 19.
6. **fit-turntable** solo dalle foto risolte pulite; degeneri (<5 spigoli
   coerenti) scartate.
7. **GATE 1 reproiez.** = media per-foto a quote di calibro (onesta). NON la
   reproiez. del fit congiunto con X,Y liberi (è ~0, le quote assorbono).

## Coda (lavoro futuro, in ordine)

1. **Principal point + k1** — ~~SOLO se il test del nastro dice "intrinseche"~~.
   **ESCLUSO 2026-07-20**: il test del nastro ha detto MATERIALE, non
   intrinseche. Non serve. (Restano nominali: cx=2016, cy=1512, k1=0, focale
   5376 px = 48mm-eq su 4032.) Il residuo 0.17 mm/lato è fillet, non intrinseche.
2. **Fillet-blend liscio** — il modello fillet attuale è solo-silhouette e
   ramifica in modo discontinuo su crease (edge con due facce visibili). Serve
   un blend liscio crest↔tangent pesato dall'angolo di grazing. Riporta il
   lettore SD (raccordi 1.7 mm) sotto soglia. Vedi il § fillet nel design doc.
3. **rounded-prism** — primitiva con raggio come parametro, per pezzi non a
   spigoli vivi (il caso normale). Il forward model esiste
   (`box-fit/edge-silhouette-points`); manca la citizenship come primitiva.
4. **Consistenza assoluta** — quando Vincenzo mette i punti di registrazione.
5. **UX/viewport** (il vero lavoro grosso del v1, accertamento 6 nel design
   doc): "camera contro sfondo fisso" richiede FOV parametrica + secondo
   schema di controllo camera + foto incollata allo schermo. TrackballControls
   attuale ruota solo attorno al target. Non ancora iniziato.

## Stato git — TUTTO NON COMMITTATO

Working tree con molto lavoro non committato (7+ file nuovi, diversi
modificati). Whenever Vincenzo vuole, si può raggruppare in commit costruibili
per-feature (import-obj è già committato in `62c3691`; il resto no). NON è
stato committato di proposito. `CLAUDE.md` risulta modificato ma NON da questo
lavoro.

Import OBJ (canale scanner Fase 1) è già a posto e committato — separato da
questo arco.
