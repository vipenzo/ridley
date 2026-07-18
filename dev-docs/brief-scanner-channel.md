# Brief: canale scanner/fotogrammetria

> **Revisione 2026-07-16 (sera)**: aggiunta la **Fase 4** (ricostruzione locale
> via Object Capture di macOS; COLMAP come fallback cross-platform e per la
> guida di copertura) e la visione companion app iOS. Fasi 0-3 invariate.
>
> **Riprioritizzazione 2026-07-16 (tarda sera)**: Vincenzo dà priorità
> all'**acquisizione parametrica interattiva**
> (`dev-docs/acquisizione-parametrica-design.md`) rispetto alle Fasi 3-4 di
> questo brief. La Fase 1 (import OBJ) resta in coda a costo basso: serve al
> canale denso già praticabile via KIRI/PhotoCatch. Remesh e ricostruzione
> locale slittano dietro il nuovo design.

## Contesto

Decisione di Vincenzo (2026-07-16): aprire il canale scanner previsto da
`mesh-board-design.md` ("Canali d'ingresso e profili di qualità"), partendo
dalla **fotogrammetria da telefono** (nessuno scanner dedicato in programma).
Strumento scelto per la sperimentazione: KIRI Engine, piano gratuito (export
STL/OBJ illimitato; Photo Scan, non LiDAR — per pezzi piccoli la risoluzione
la fanno le foto).

Il confine architetturale resta quello del design: **Ridley inizia alla mesh**.
Niente nuvole di punti: il marching cubes consuma un campo, non punti, e
costruire il campo dalla nuvola È il problema della ricostruzione (segno da
normali orientate, densità non uniforme) — lavoro che le app di fotogrammetria
fanno già internamente, meglio di quanto lo rifaremmo noi. La ricostruzione è
mestiere loro; il nostro inizia dal PLY/OBJ/STL che consegnano.

Profilo di qualità del canale (dal design, invariato): sopravvivono al rumore
gli strumenti volumetrici (profilo A(t), simmetria, `decompose`, `mesh-split`,
confronto `mesh-board`); muoiono i face-based (spigoli riflessi, gradini da
facce complanari — su una scansione niente è complanare). Il flusso del
capitolo 18 delle guide regge intatto; il confronto è il caso d'uso nativo
dello scan (fedeltà 97% = successo, la deviazione è il rumore che si butta).

## Fase 0 — Accertamento con scansione reale (Vincenzo, in corso)

Prima di scrivere codice: una scansione KIRI di un pezzo meccanico vero,
export STL, dentro l'attuale `import-stl`. Da osservare e annotare:

- dimensioni: conteggio triangoli, peso del file (le scansioni sono dense:
  attese centinaia di migliaia di tri — regge il viewport? l'editor?);
- `mesh-diagnose`: quanti open-edge? è watertight per miracolo o bucata come
  atteso?
- `mesh-split` e `mesh-board` sulla scansione: funzionano? dove si rompono?
- errore di scala rispetto al calibro (per dimensionare la Fase 2).

Gli esiti calibrano le priorità delle fasi 1-3 (in particolare: quanto è
urgente il remesh).

### Esiti parziali (primo scan, 2026-07-16)

Oggetto: piccolo lettore di SD card appoggiato sullo schienale di una poltrona.

- **KIRI free esporta OBJ + MTL + JPG, NON STL** (smentite le comparative):
  la Fase 1 diventa prerequisito di tutto — **OBJ per primo**, PLY declassato
  a "quando capita". MTL/JPG (materiali e texture) si ignorano, come previsto.
- Consegna via mail con link di download (procedura laboriosa ma funziona);
  anteprima macOS dell'OBJ: dettaglio giudicato sufficiente per ricavarne il
  pezzo.
- La scansione prende tutta la scena (poltrona inclusa): il **ritaglio** del
  pezzo dalla scena è il primo passo del flusso reale — è un lavoro da
  `mesh-split`/`edit-mesh-split`, ma sulla mesh non riparata (vedi Fase 3:
  Manifold rifiuta input non-manifold — da verificare se il ritaglio grezzo
  richiede già il remesh, il che lo farebbe salire di priorità).
- Ancora da misurare (quando l'OBJ entra in Ridley): conteggio triangoli,
  mesh-diagnose, comportamento di mesh-split/mesh-board, errore di scala.

## Fase 1 — Loader OBJ (prima) e PLY (poi)

> **FATTO per l'OBJ, 2026-07-18** (PLY resta "quando capita"). Implementato:
> `ridley.library.obj/parse-obj` (parser puro) e `ridley.library.mesh-import`
> con `import-obj` + `import-mesh` (dispatch sull'estensione), bindings SCI,
> reference cards + Spec.md + guida 18 aggiornate, 9 test in
> `test/ridley/library/obj_test.cljs`. Verificato end-to-end nel browser
> (parser, import da path, `:recenter`, creation-pose, e la forma
> `(import-mesh …)` attraverso SCI) su un OBJ con quad, indici `v/vt/vn`,
> gruppi multipli e un `mtllib` **inesistente** — che infatti non è un errore.
>
> Non fatto, e deliberatamente: **la procedura di import della libreria**
> (base64 inlining) non è stata estesa all'OBJ, perché è esattamente il punto
> che il brief lascia aperto qui sotto — una scansione inlined produce
> sorgenti da decine di MB. Serve la decisione sulla soglia prima di
> cablarla. Oggi l'OBJ entra solo per path, che per le scansioni è comunque
> la via giusta.

**Riprioritizzata dalla Fase 0: l'OBJ è il formato che KIRI free consegna
davvero — è il prerequisito di tutto il canale.**

- `import-obj` accanto a `import-stl` (o un `import-mesh` unico che smista
  sull'estensione — preferibile: un solo nome da ricordare, stessa opzione
  `:recenter`). I loader three.js sono già in bundle (design, Q8): cablarli,
  scartare ciò che non serve (MTL, texture, gruppi di materiali), tenere la
  geometria. Un OBJ senza il suo MTL a fianco non deve produrre errori.
- PLY quando capita: attenzione alle proprietà per-vertice (colore, normali,
  confidence) — per ora si scartano, ma il parser non deve strozzarsi.
- Procedura di import della libreria: per le scansioni l'inlining base64 nel
  sorgente (la via di `generate-library-source` per gli STL) può produrre
  sorgenti da decine di MB. Decisione da prendere: soglia oltre la quale la
  entry di libreria riferisce il file per path (stile `import-stl`) invece di
  incorporare. Da discutere con Vincenzo al primo caso concreto.

## Fase 2 — Calibrazione di scala (piccola)

La fotogrammetria pura non conosce la scala: forma giusta, dimensioni
arbitrarie. L'erede del righello 2D (design, Q9):

- L'utente misura sul pezzo fisico una distanza nota (calibro); indica la
  stessa distanza sulla mesh (la misura shift+click del viewport esiste già e
  funziona sugli scaffold e sulle mesh); il fattore è noto/misurato.
- DSL minima: `(calibrate mesh known measured)` → mesh scalata (o solo il
  fattore, da passare a `mesh-scale` — decidere: la forma esplicita a due
  passi è più onesta, la funzione unica più comoda).
- Il valore emesso nel sorgente deve essere il fattore numerico, non un
  riferimento alla misura interattiva: round-trip come sempre.

## Fase 3 — Remesh implicito come repair (il boccone grosso)

Il gate del canale sono i buchi (design, Q7): le scansioni non sono watertight
e Manifold rifiuta l'input non-manifold — senza repair, `mesh-split` sulla
scansione vera muore. Invece di un riempi-buchi chirurgico, la via proposta è
il **remesh implicito** (voxel remesh alla Blender):

1. campo di distanza della mesh importata, campionato su griglia (BVH sui
   triangoli per la distanza; **generalized winding number** per il segno —
   robusto anche vicino ai buchi, dove una normale locale mente);
2. marching cubes sull'isosuperficie a risoluzione scelta dall'utente
   (`:voxel-size`, default legato al bbox);
3. output watertight **per costruzione**: i buchi si chiudono da soli, il
   rumore sotto la risoluzione si spiana (per la falsariga è una feature).

Collocazione: backend Rust/libfive (il marching cubes c'è già; il campo
mesh-distance + winding number è il lavoro nuovo). Trade-off da documentare:
il remesh arrotonda gli spigoli alla scala del voxel e sposta le superfici
fino a mezzo voxel — per la falsariga va benissimo, per un pezzo finale no.
DSL: `(remesh mesh :voxel-size 0.2)`, pura, riproducibile, emessa nel sorgente.

La Fase 3 parte solo dopo la Fase 0: se le scansioni KIRI arrivassero
sistematicamente watertight (improbabile ma possibile: alcune app chiudono via
Poisson), il remesh scala di priorità.

## Fase 4 — Ricostruzione locale (esplorativa, gated su Fase 0-3)

Obiettivo: eliminare la dipendenza dall'app/server di terzi — foto in una
cartella, mesh fuori, tutto locale. Direzione scelta (Vincenzo, 2026-07-16).

### 4.1 — Motore: Object Capture di macOS

Su Mac la via nativa batte la pipeline open source: l'API `PhotogrammetrySession`
(RealityKit) usa GPU e Neural Engine di Apple Silicon, produce mesh di qualità
commerciale in locale, esporta USDZ/OBJ, zero licenze (è il sistema operativo).

- Integrazione: piccolo helper Swift a riga di comando (Apple pubblica
  l'esempio `HelloPhotogrammetry`), invocato dal backend Tauri come sidecar —
  stesso pattern del geo_server. Ridley orchestra: cartella foto → helper →
  OBJ → import (Fase 1) → calibrazione (Fase 2).
- Limite: macOS-only. Fallback cross-platform, se mai servirà: COLMAP
  (BSD) + OpenMVS (AGPL, invocazione a processo separato) su CPU — lenta ma
  funzionante. Non si costruisce finché non c'è domanda.
- **Accertamento preliminare (Vincenzo, costo ~zero)**: stesse foto del
  lettore SD dentro un'app Mac basata su Object Capture (o l'esempio Apple
  compilato) → confronto qualità con la mesh KIRI. Se il nativo vince, la
  fase si finanzia da sola.

### 4.2 — Guida di copertura nel viewport

Il valore aggiunto che nessuna app gratuita dà: dopo una pass di sparse
reconstruction (veloce anche su CPU — COLMAP/glomap, o le pose intermedie di
PhotogrammetrySession), Ridley mostra nel viewport nuvola sparsa + pose delle
camere e colora le zone a bassa copertura: "servono foto qui, da questa
angolazione". Loop: scatti col telefono → AirDrop nella cartella → ri-pass →
mappa aggiornata. Il viewport è l'organo giusto ed esiste già; il lavoro è la
metrica di copertura e il rendering delle pose camera.

### 4.3 — Visione: companion app iOS (fuori scope, nel radar)

`ObjectCaptureSession` su iPhone fa la cattura guidata con overlay AR
(copertura in tempo reale, on-device) e consegnerebbe foto o mesh direttamente
a Ridley. A Vincenzo l'idea piace molto. È un altro ordine di ambizione
(app iOS, distribuzione, pairing col desktop): NON parte di questo brief, ma
le scelte delle fasi 1-4 non devono precluderla — in particolare l'import
(Fase 1) deve poter ricevere ciò che quella sessione produce (USDZ→OBJ, PLY).

## Fuori scope (esplicito)

- Nuvole di punti (vedi Contesto).
- Texture e colore per-vertice: il canale porta geometria; il colore delle
  scansioni è un tema separato (eventuale futuro brief, aggancio al sistema
  materiali del cap. 14).
- Registrazione multi-scansione, repair chirurgico dei buchi.
- LiDAR come sensore diretto (resta dentro solo dove lo usa Apple, sotto
  Object Capture / ObjectCaptureSession).
- La companion app iOS (visione 4.3: nel radar, non in questo brief).

## Documentazione da aggiornare

- `docs/manual/guides/it|en/18-acquisire-e-sostituire.md` § 18.2 — quando le
  fasi 1-2 atterrano, la riga onesta sullo scanner si sostituisce con il
  flusso reale (import-mesh, calibrate); § nuovo o esteso per il remesh alla
  Fase 3.
- Reference: `import-ply`/`import-obj` (o `import-mesh`), `calibrate`,
  `remesh` + `reference_index.cljs`.
- `docs/Spec.md` — sezione import.

## Verifica

- Fase 1: la scansione KIRI di Vincenzo (PLY e OBJ) importa e appare nel
  viewport; STL della stessa scansione → stessa geometria.
- Fase 2: pezzo con quota nota misurata al calibro → dopo calibrate, la misura
  shift+click sulla mesh restituisce la quota entro l'errore di scansione;
  il fattore emesso nel sorgente rieval identico.
- Fase 3: scansione bucata (accertamento Fase 0) → dopo remesh `manifold?` è
  true, `mesh-split` funziona, e il confronto `mesh-board` contro il pezzo
  nativo dà fedeltà sensata; `:voxel-size` più fine = fedeltà più alta, costo
  più alto (misurare e annotare la curva su un caso reale).
