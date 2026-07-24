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

> **Stato 2026-07-25**: P0 ✅ · Gate ✅ (poi scavalcato dal PnP) · P1 ✅ ·
> **P2 ✅ nella sostanza** (registrazione per corrispondenze PnP collaudata
> da Vincenzo: "abbastanza usabile, proxy allineato facilmente alle sei
> foto"; le mitigazioni di simmetria sono assorbite dal PnP). Restano
> rifiniture P2 (priori θ nel fit congiunto). **Prossimo: P3**, come fetta
> verticale sottile: piano dichiarato su faccia del proxy (± offset) →
> ricalco con edit-path-2d sulla foto in posa (raggio∩piano) → riproiezione
> live nelle altre viste → persistenza in sessione → emissione minima come
> shape (P4 anticipato di un pezzo: senza, i ricalchi si perdono all'uscita).
> Punti liberi triangolati (stesso gesto PnP, su feature dell'oggetto) per
> ciò che non giace su piani. Collaudo del giro completo: il lettore SD
> nastrato (proxy box → piano top → ricalco del bezel).

> **Stato P3-slice 2026-07-23 (COSTRUITO, NON committato, in attesa del gate
> umano)**: la fetta è dentro `edit-acquire` come nuovo modo `:retrace` (tasto
> `d`), NON come edit-path-2d — quello è una sessione modale a sé e non può
> convivere nella sessione modale di edit-acquire senza anticipare tutta
> l'architettura non-modale del "palcoscenico" (P4). Scelta concordata con
> Vincenzo (2026-07-25): **polilinea minimale** che dimostra tutta la spina;
> la ricchezza di edit-path-2d (bezier/archi) torna in P4. Fatto: primitiva
> `camera/pixel-ray` + `math/ray-plane-point` (inverse esatte di `project`,
> test di regressione a 0 mm), piano dichiarato = faccia del box (axis/sign +
> offset) nel frame OGGETTO, click→raggio→∩piano→punto oggetto, polilinea come
> geometria 3D world (quindi la riproiezione nelle altre viste è gratis: `[`/`]`
> muovono la camera, la polilinea resta ferma nel mondo), persistenza in
> `acquire-state.json` (`:retrace`), emissione minima `(poly …)` alla chiusura.
> Verificato: math + glue di frame (editor→solver→oggetto) a ~1e-14 mm via
> REPL. Manca: il **gate umano** (Vincenzo traccia il bezel del lettore SD
> nastrato e giudica). Handover `dev-docs/HANDOVER-edit-acquire-p3.md`.

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

**Registrazione per corrispondenze (proposta Vincenzo, 2026-07-24 —
candidata a gesto primario)**: invece del trascinamento 6-DOF, l'utente
seleziona un'entità sul modello virtuale (vertice/spigolo), la clicca nelle
foto dove è visibile, e dichiara l'identità. Con quote note e 4-6
corrispondenze per foto, la posa camera si calcola in forma chiusa (PnP):
niente bacino di convergenza, niente inizializzazione, e il gemello di
Klein muore per costruzione (l'identità è dichiarata). Le corrispondenze
sono la stessa struttura dei punti di consistenza, promossa da metrica a
input: un solo meccanismo registra, misura e rompe la simmetria. Dopo 2
foto, riproiezione predittiva: le foto successive arrivano coi punti
proposti da confermare. Lo snap resta il rifinitore sub-pixel a valle
(il suo mestiere); il gizmo resta per il piazzamento grossolano e per
oggetti senza punti identificabili (cilindri, forme lisce) — due modalità
della stessa ancora. Flusso efficiente: entità-prima ("spigolo A → click
nelle 6 foto"), non foto-prima.

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

**Design del palcoscenico (Vincenzo/Claude, 2026-07-26 — perimetro per
P4a/P4b):**

- **Forma nel sorgente, minimale**: `(acquire "scans/lettore-sd/")`
  (+ `{:label …}` se ne coesistono più d'una). Regola di demarcazione:
  *nel sorgente ciò che descrive l'oggetto* (proxy con posa, ricalchi
  emessi), *nel file di sessione ciò che descrive com'è stato fotografato*
  (pose camere, intrinseche, picks, residui, piani dichiarati).
- **Forma emessa autocontenuta (rivisto 2026-07-27, discussione
  Vincenzo/Claude sulla bozza P4a)**: niente `(poly …)` sciolti dopo la
  acquire (accoppiamento invisibile, vincoli non scritti per l'utente) e
  niente ricalchi opachi nel solo file di sessione (violerebbe "lo stato è
  il sorgente"). La sintesi: **tutto dentro la forma, nominato** —
  `(acquire "dir" {:proxy … :pose … :shapes {:bezel (poly …)} :marks
  {:vite-1 {…}}})`. La acquire **ritorna il valore**: accesso per nome via
  destrutturazione (`(:bezel (:shapes A))` — pattern split-tree), nessuna
  API nuova. Round-trip su una sola form.
- **Principio dell'impalcatura, raffinato**: la caduta è un **gesto
  deliberato di inline** (sostituire `(:bezel (:shapes A))` col letterale,
  eventualmente con un aiuto dell'editor "estrai ricalco"), non una
  cancellazione che regge per costruzione — coerente con la sostituzione
  progressiva della guida 18. Resta il vincolo P4b: il palcoscenico non
  entra mai nella catena CSG/export.
- **I mark come primitivo dei punti acquisiti (Vincenzo, 2026-07-27)**:
  "punto nominato dell'oggetto osservato in più foto" = il `mark` di
  Ridley (posizione + direzione + id). Creato su una foto è un raggio
  (profondità ignota, mostrato come tale); la seconda foto triangola; le
  successive rifiniscono; la direzione dal piano dichiarato o da una
  coppia di punti. Unifica retroattivamente corrispondenze PnP e punti di
  consistenza (erano mark senza saperlo) e apre ai mark tutta la macchina
  esistente: attach, path per mark, misure — senza codice nuovo a valle.
- **Le foto, in tre stati**: (1) **frustum nel mondo** — camera libera:
  le camere registrate come piramidi ghost con miniatura, alla loro posa
  vera (si legge la copertura del giro; cittadinanza da riferimento, mai
  export/pick); (2) **in posa** — click sul frustum/miniatura → la camera
  vola dentro, foto a pieno schermo, geometria sopra; Esc/orbita → di
  nuovo libero; (3) **pellicola come pannello** (precedente: la vista
  processo di mesh-board, nata per vivere anche fuori dalla sessione).
  ⚠️ L'ergonomia dei frustum (ingombro visivo, navigazione) è **da
  collaudare, non decisa** — verdetto di Vincenzo dopo prova; fallback:
  solo pellicola + toggle frustum.
- **Memoria**: miniature per frustum e pellicola; full-res caricata solo
  per la foto in posa (14 × 24 MP non stanno in RAM insieme).
- **Invariante di frame (per il futuro live, 2026-07-26)**: il frame del
  palcoscenico è l'OGGETTO — la geometria (proxy, ricalchi) non si muove
  mai. Nel live, il piatto che gira = la camera che orbita: la camera live
  è un **frustum in moto** attorno al mondo fermo; le foto scattate sono
  frustum fermi (le fermate). Click su frustum fermo = vai a quella foto;
  click sul live = segui la diretta (viewport in posa continua — lì sullo
  schermo "il mondo gira", stessa scena dall'altra faccia della gauge).
  "Scatta" = congela il frustum live in una nuova foto registrata.
- **Spezzatura** (proposta Code, confermata): **P4a** round-trip
  (emissione `acquire` + write-back proxy + re-entry; gate: commit →
  riapri → stessa sessione con pose, marcature e ricalchi) → **P4b**
  palcoscenico non-modale (la fase 2 esce dal modale `:retrace` e diventa
  editor normale: lì torna la ricchezza di edit-path-2d).
  - **P4a-1 (round-trip del proxy) COSTRUITO + verificato live 2026-07-24, non
    committato** (branch `edit-acquire-registration-stability`). `acquire`
    direttiva reference-citizen (ritorna `{:proxy :pose :shapes :marks :dir}`,
    monta il proxy in posa) + `edit-acquire` marcatore (macro con dispatch
    dir-string→`request!` / mesh-form→`edit-acquire-open!` legacy) + write-back
    (`Conferma`→`(acquire "dir" {:proxy (box W H D) :pose {…} :shapes {} :marks
    {}})`, `Chiudi`/`Esc`→strip-head). dims+posa round-trippano attraverso il
    valutatore SCI reale (`repl/evaluate-definitions`). `:shapes`/`:marks` vuoti
    (→ P4a-3). **Manca il gate umano** (geo-server + foto vere: apri → allinea →
    Conferma → riapri stessa posa). Vedi `dev-docs/HANDOVER-p4a-acquire.md`.
  - **P4a-2 (osservazioni nel file di sessione) COMMITTATO `b55d9b3` + gate PASSATO 2026-07-24.**
    La stabilità-registrazione aveva già reso persistenti pose camere, badge,
    marcature blindate ('m' → `:marker-picks`) e piani (`:retrace`); mancavano
    **i pick PnP** (i click delle corrispondenze — a differenza dello snap 's',
    che li ri-deriva dalla posa, una registrazione PnP *è* i suoi click) e la
    **focale**. Aggiunti a `acquire-state.json`: sezione `:pnp` (per foto:
    `:picks {ci {:px :screen}}` + `:residuals` + `:outliers`) e `:focal
    {:mm :source}`; ripristinati in `apply-loaded-state!` (chiavi intere annidate,
    set da vettore, focale dopo il read EXIF così una taratura manuale vince).
    Round-trip save→JSON→load verificato attraverso le funzioni reali. Effetto
    visibile: riapri una foto registrata con 'p', ripremi 'p' → i pallini colorati
    e gli spigoli piazzati sono ancora lì (prima erano vuoti). **P4a-3** =
    shapes+marks dentro la form.

### P5 — Protocollo e documentazione
Vincolo "superfici opache" (nastrare/opacizzare i traslucidi) nel manuale;
protocollo di scatto (focale fissa, AE/AF lock, ~250 mm, non riempire il
frame, giro con θ annotati); guida (nuovo cap. o estensione del 18).

## Coda (dopo il v1, ordine indicativo)

1. **Corona di marker sul piatto** — ruolo chiarito dal PnP (2026-07-25):
   è il **bersaglio PnP universale**. Oggi le corrispondenze si cliccano
   sui vertici del proxy, che funziona solo se l'oggetto ha quei vertici
   fotografabili (il blocco sì; un pezzo organico ingabbiato in un
   proxy-box no: gli angoli sono a mezz'aria). I marker sono punti
   fotografabili con coordinate note per costruzione (Ridley genera il
   piatto): registrazione indipendente dalla forma del pezzo, rilevamento
   automatico (pattern codificato) → zero click in fase 1, θ letto dalla
   corona (tabella NOTE obsoleta), scala metrica di stampa. Vincolo di
   progetto: l'oggetto occlude il centro — corona ai bordi, con margine,
   visibile in parte da ogni angolo. Prerequisito del live (webcam/
   companion): "inquadro e il viewport si orienta" = auto-posa per frame
   via corona. Momento giusto: appena la fetta P3 chiude sul lettore.
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
