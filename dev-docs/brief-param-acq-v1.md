# Brief: acquisizione parametrica — v1 (sessione edit-acquire)

## Contesto

Gli accertamenti di `acquisizione-parametrica-design.md` sono chiusi, tutti
con verdetto:

- **Matematica certificata**: fit sintetico a 0.003 mm su pezzo
  modello-conforme; corrispondenza per-permutazioni (mai per ordine di
  click); seed coverage per settore di azimut (il punto cieco a 45° è il
  test di regressione EXP 19).
- **Registratore di camere certificato**: Gate 2 passato (hold-out 1.2× sul
  blocco nastrato), giradischi coerente al grado sul giro completo.
- **Vincolo materiale**: il PLA traslucido sposta la silhouette di
  ~0.5 mm/lato (subsurface scattering, test del nastro 2026-07-20).
  Le intrinseche EXIF bastano; principal point + k1 NON servono (cancellato).
- **Residuo 0.17 mm/lato = raccordi veri** → coda rounded-prism/fillet-blend,
  agganciata al ritorno del lettore SD.

Il tool HTML (`scripts/param-acq-tool.html`) e la CLI `paq` erano impalcatura
d'accertamento: il v1 porta il flusso DENTRO Ridley, riusando la matematica
(`ridley.photogrammetry.*`) così com'è. UX concordata con Vincenzo
(2026-07-21) — questa è la fonte di verità sul perimetro.

## Il prodotto (riassunto della sessione tipo)

`(edit-acquire "scans/lettore-sd/")` apre una sessione modale sul viewport
(famiglia modal-evaluator, come edit-mesh-split):

1. **Pellicola di miniature** su un lato: le foto della sessione, con badge
   di stato (grigia = non registrata; verde + residuo px = registrata;
   bordata = corrente).
2. **Fase 1 — registrazione**: foto corrente come sfondo (camera bloccata,
   FOV da EXIF). L'utente allinea il PROXY (box/primitiva) col gizmo di
   edit-attach; `s` = edge-snap sub-pixel; badge aggiornato. Dalla seconda
   foto la manipolazione è INVERTITA (il proxy canonico non si muove: il
   gesto registra la camera — narrazione "ruota il pezzo com'era in quella
   foto"). Con angoli-giradischi in sessione, le foto 2..N arrivano
   pre-posate: confermare, non rifare. Niente più click a gruppi: il click
   di singoli spigoli resta come ritocco dove lo snap non aggancia.
3. **Fase 2 — lavorare sulle viste registrate** (il valore del prodotto):
   click su una miniatura → camera in quella posa, foto sotto, geometria
   Ridley sopra in proiezione corretta. Strumenti normali di Ridley sopra la
   foto: edit-path-2d su un piano dichiarato (es. faccia del proxy), il
   ricalco si **riproietta live nelle altre viste** (cambio foto → stesso
   tratto visto dall'altra angolazione → correzione della profondità);
   shift+click misura; **righelli di calibro** = vincoli (due facce
   evidenziate mentre si digita il valore; il fit si aggiorna).
4. **Uscita**: il commit emette **sorgente Ridley normale** (primitiva coi
   numeri fittati, ricalchi come shape/path); pose camere e picks vivono in
   un file di sessione accanto alle foto (impalcatura, non programma).
   Round-trip: `edit-` davanti riapre la sessione dov'era.

## Due astrazioni obbligatorie (Vincenzo, 2026-07-21 — non negoziabili)

Il v1 va scritto contro due interfacce, non contro "cartella di JPEG":

1. **Sorgente di frame**: oggi cartella + EXIF + tabella θ; domani webcam
   USB, cellulare collegato, companion app ARKit (frame + posa allegata).
   L'evoluzione target: "inquadro il piatto riconoscibile e il viewport si
   orienta da solo" — stream al posto della pellicola.
2. **Ancora di registrazione**: oggi il proxy allineato a mano; domani la
   **corona di marker sul piatto** (registra qualunque oggetto, senza
   proxy) e le pose ARKit. Il proxy ha due ruoli distinti — ancora di
   registrazione (non richiede somiglianza: un box che ingabbia l'oggetto
   registra quasi tutto) e ipotesi di misura (richiede somiglianza: coda
   rounded-prism) — e l'architettura non deve fonderli.

## Gate ingegneristico (prima delle parti)

**Prototipo d'interazione della manipolazione invertita** (già assegnato):
foto fissa come sfondo + proxy sul gizmo esistente + FOV parametrica +
camera lock (`set-camera-pose!` + `set-controls-enabled!`, precedente:
sistema di animazione). Giudice: Vincenzo, criterio ergonomico. Se
l'inversione non regge alla prova, si torna al design doc (controllo camera
free-look, il costo grosso dell'accertamento 6) prima di costruire il resto.

> **ESITO (2026-07-22): SUPERATO** — verdetto di Vincenzo dopo 6 giri di
> bug-fixing dal vivo (`HANDOVER-edit-acquire-gate.md`): "quasi sufficienza",
> il meccanismo regge; la fatica residua è attribuita alla **simmetria del
> proxy** (Klein), non all'inversione. Conseguenze recepite nelle Parti:
>
> - **P2 si arricchisce delle mitigazioni di simmetria** (vedi P2).
> - Il collaudo di P1/P2 si fa con un **oggetto asimmetrico**: il target con
>   tacche (`examples/param-acq-target.clj`, da stampare) o il lettore SD
>   nastrato — mai più il blocco liscio, che è l'oggetto più ambiguo
>   possibile e confonde il giudizio ergonomico.
> - Bug collaterale scoperto (fuori scope, tracciato in
>   `dev-docs/code-issues.md`): `mesh-union` giustappone invece di fondere
>   in certi contesti — sospetto fallback muto senza WASM.
> - **P0 (commit) è ancora aperto e ora URGENTE**: gate + prototipo + fix
>   vivono non committati sopra la settimana di accertamenti già non
>   committata.

## Parti

### P0 — Consolidamento
Commit di tutto il lavoro accertamenti (solver, matcher, tool, test,
verdetti nei doc). Prerequisito di ogni cosa.

### P1 — Viewport
FOV parametrica per-sessione (da EXIF); camera lock/unlock pulito; foto come
sfondo in posa (rect world-space frontale alla camera bloccata, riuso del
path stamp/image-board); pellicola di miniature con badge.

### P2 — Sessione e registrazione
`edit-acquire` (marker nel buffer, modal-evaluator); caricamento sorgente
frame (cartella; interfaccia per le sorgenti future); proxy + gizmo +
manipolazione invertita; edge-snap (`s`); priori θ; badge/residui per foto;
file di sessione (pose, picks, vincoli) accanto alle foto; le tre
singletonicità di image-board da rompere sono mappate nell'accertamento 1.

**Mitigazioni di simmetria (dal gate, 2026-07-22; diagnosi affinata da
Vincenzo 2026-07-23)** — l'ambiguità sta nel PROXY, non nella scena: il
pezzo fisico porta spesso indizi (disegni sul nastro, dettagli), le foto
sono asimmetriche — ma il wireframe del proxy è identico da tutti i lati,
quindi l'utente non sa quale faccia virtuale corrisponde a quale faccia
fisica. Le cure, in ordine di priorità:

- **l'ipotesi non balla mai**: scelto un ramo di simmetria sulla foto 0, le
  proposte per le foto successive restano su quel ramo — mai rí-scegliere
  il gemello di Klein per foto;
- **il proxy dichiara il suo orientamento**: una faccia colorata (o un
  vertice marcato) nel rendering del wireframe, per confrontare a colpo
  d'occhio col pezzo fisico (che porta il puntino di pennarello);
- il puntino centrale sempre-visibile del prototipo è il precedente:
  promuoverlo a indizio di orientamento, non solo di posizione.

### P3 — Viste registrate
Ricalco su piano dichiarato con riproiezione live sulle altre viste;
righelli di calibro come vincoli del fit (con evidenziazione delle entità
agganciate); misure; punti di consistenza integrati nel flusso (il numero
del registratore visibile nel pannello, flip-aware).

### P4 — Emissione e round-trip
Sorgente canonico (primitiva + ricalchi + vincoli); file di sessione;
re-entry. Il contratto è quello di ogni editor Ridley.

**Design dell'emissione (Vincenzo, 2026-07-23 — la convenzione dei nomi come
contratto)**: `edit-acquire` deve emettere una forma **`acquire`**, come ogni
`edit-X` emette la sua `X`. La `acquire` è una **direttiva-palcoscenico**
(famiglia image-board/mesh-board, cittadinanza da riferimento: mai
nell'export, mai nella CSG): valutata, monta la pellicola di foto e le
camere registrate — "clicco la miniatura, il viewport va in posa". I
parametri fini (pose, residui, scala) vivono nel file di sessione; il
sorgente porta la forma e il riferimento.

Conseguenza architetturale: la **registrazione** (fase 1) è la sessione
modale `edit-acquire`; il **ricalco** (fase 2) è vita normale dell'editor
col palcoscenico attivo — edit-path-2d, misure, righelli, mesh-board sopra
le viste in posa, senza sessione dedicata. Stesso argomento con cui
mesh-board-design.md rifiutò il workplace modale: lo stato è il sorgente,
la sessione serve solo dove serve il gesto interattivo. (Ricongiunge anche
la decisione dell'handover "funzione ora, wrapper macro quando arriva
l'emissione": il wrapper è la `acquire`.)

### P5 — Protocollo e documentazione
Vincolo "superfici opache" (nastrare/opacizzare i traslucidi) nel manuale;
protocollo di scatto (focale fissa, AE/AF lock, ~250 mm, non riempire il
frame, giro con θ annotati); guida (nuovo cap. o estensione del 18).

## Coda (dopo il v1, ordine indicativo)

1. **Corona di marker sul piatto** — Ridley genera il piatto marcato
   (dogfooding), rilevamento automatico → registrazione senza proxy per
   oggetti qualunque; abilita anche il live.
2. **Rounded-prism + fillet-blend** — la leva di accuratezza, collaudata sul
   ritorno del lettore SD (nastrato!).
3. **Sorgenti live** (webcam/companion ARKit) — sopra l'interfaccia di P2.

## Verifica

- Gate ingegneristico: sessione di prova con le foto del blocco nastrato —
  Vincenzo registra le 6 foto in meno di X minuti senza istruzioni scritte
  (X da fissare col prototipo; il criterio è "non serve il manuale").
- P2: stessi numeri della CLI sugli stessi dati (blocco nastrato: quote e
  hold-out riprodotti dentro Ridley).
- P3: un ricalco tracciato su due viste triangola con consistenza ≤ qualche
  px; un righello di calibro cambia la quota fittata e il residuo scende.
- P4: round-trip — commit, riapertura, stessa sessione; il sorgente emesso
  rieval identico.
- End-to-end: **il lettore SD, nastrato**, acquisito da capo dentro il v1 —
  proxy box per registrare, ricalchi per bezel/slot, calibro sulle quote
  critiche → primo pezzo vero prodotto dal flusso completo, confrontato con
  mesh-board contro la sua scansione KIRI (il cerchio col capitolo 18 si
  chiude).
