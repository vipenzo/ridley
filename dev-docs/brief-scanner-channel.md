# Brief: canale scanner/fotogrammetria

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

## Fuori scope (esplicito)

- Nuvole di punti (vedi Contesto).
- Texture e colore per-vertice: il canale porta geometria; il colore delle
  scansioni è un tema separato (eventuale futuro brief, aggancio al sistema
  materiali del cap. 14).
- Registrazione multi-scansione, LiDAR, repair chirurgico dei buchi.

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
