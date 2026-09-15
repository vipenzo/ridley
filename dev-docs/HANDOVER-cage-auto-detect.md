# HANDOVER — riconoscimento automatico della gabbia

> **Entry point attuale: `dev-docs/HANDOVER-cage-zero-click.md`** (stato dei
> lavori e frontiera). Questo file resta la STORIA completa del fronte — perché
> ogni scelta è com'è — e vale la pena leggerlo quando una decisione qui sotto
> sembra strana.

## In una riga

Dare alla GABBIA quello che il piatto ha già: premi un tasto, il programma trova
i dischetti nella foto, capisce quale è quale, e registra. Vincenzo ha dato il
via il 2026-08-23, dopo che il gesto manuale si è dimostrato chiuso.

## Perché, e non è un lusso — è misurato

Tutto quanto segue viene dai dati veri di Vincenzo, non da ragionamenti. Foto
`~/Pictures/RidleyScan/Presa/IMG_9014.jpeg` (gabbia ⌀176, 4032×3024 raster con
EXIF Orientation 6, quindi **3024×4032 in coordinate di visualizzazione**, che
è il sistema in cui stanno i pick).

1. **I click dell'utente sono impeccabili.** Dodici su dodici centrati sui
   dischetti, ritrovati dal rilevatore entro 0.7–1.9px. Il problema non è mai
   stata la mano.
2. **Una corona da sola non può identificarsi.** Dodici mark equidistanti sono
   invarianti per rotazione e dall'altra faccia si leggono specchiati: **tutte
   e 48 le riletture della corona X danno lo stesso rms, 32.5px**.
3. **La posa da un anello solo non colloca gli altri.** Nove punti di una
   corona (indice compreso) chiudono a 5.15px e mettono i mark degli altri due
   anelli a **130–260px** dai dischetti veri: una corona è complanare, la posa
   è precisa nel suo piano e vaga fuori. *(Il 5.15px è da rifare: uno di quei
   nove punti si è poi rivelato fuori di 143px — vedi fetta 1. La conclusione —
   una corona sola non colloca le altre — non dipende da quel numero.)*
4. **Un solo punto di un secondo anello fa esplodere il fit**: 5.15px →
   **1008px**. Dodici coplanari più uno: il punto fuori piano porta tutta la
   profondità e tutto il proprio errore. È la famiglia del guasto già
   documentata in `HANDOVER-registration-cage.md` (foto 5, 9736px).
5. **L'utente non può rompere il circolo a occhio.** Deve appaiare i nomi ai
   dischetti sopra un disegno fuori di 150px, e **tre click su sei** finiscono
   su un dischetto dell'anello piccolo creduto del medio — vicino agli incroci
   fra anelli non c'è modo di distinguerli.

Quindi: l'identificazione va tolta dalle mani dell'utente. Non è una comodità,
è l'unica strada.

## Cosa c'è già, e cosa fa

### Il rilevatore — `src/ridley/photogrammetry/blob_detect.cljs`

> Questa sezione descrive lo stato **prima** della fetta 1. `bright-around` e le
> vecchie `cage-opts` non esistono più: `enclosed-frac` fa lo stesso lavoro sui
> pixel veri e meglio. Resta qui perché spiega da dove viene il problema.

`detect-blobs` cerca componenti scure sotto la media locale, su immagine
ridotta. Nato per il PIATTO e tarato per quello.

Aggiunto il 2026-08-23 (`c14733a`), e sono i due mattoni da cui ripartire:

- **`cage-opts`** — tarature per la gabbia: finestra stretta (`:win 6`),
  `:max-aspect 6.0` (i mark si vedono a ogni obliquità e si schiacciano in
  ellissi lunghe), `:surround-bright 0.5`.
- **`bright-around`** — un dischetto è scuro ED è circondato di bianco; una
  macchia del fondo no. Campiona un anello a 1.8/2.4/3.0 raggi, 32 direzioni.

Misurato su `IMG_9014` (fondo NERO): il modo piatto trova **tutti** i dischetti
dell'anello grande e **nessuno** dei due interni — la finestra della media
locale è scelta «≫ un dischetto, ≪ la distanza fra due», che sugli anelli
interni (bande sottili su fondo scuro) si riempie di fondo e manda la media a
nero. Stringendola: 214 candidati per 10 buoni; con `bright-around`: 89
candidati, un solo dischetto vero perso.

Misurato su `IMG_9019` (fondo CHIARO, carta bianca): il guasto della media
locale sparisce, e **resta scoperto quello sotto** — a risoluzione ridotta il
**bordo** della plastica somiglia a un dischetto quanto un dischetto. Modo
piatto: 26 rilevazioni quasi tutte vere ma poche. Modo gabbia: 104, con una
fila di falsi lungo il bordo dell'anello grande e sull'ombra.

### Il percorso automatico del PIATTO, da imitare

`edit_acquire.cljs` tasto **`a`** → `on-auto-register!` (riga ~3256), che oggi
rifiuta esplicitamente la gabbia (`no-auto-on-cage-msg`). Sotto:
`blob-detect/detect-blobs` → `match-plate/fit-crown` (riga 463, RANSAC su
quartetti sparsi) → `match-plate/assign-marks` (riga 223, identità e indice
dello zero) → `pnp/solve-pnp` per foto, con fail-safe rms≤12 e corona≥8.

### Il resto della catena, già solido

- `cage/registration-cage`, `mark-id`/`mark-parts`/`index-id`/`index-parts`,
  `crown-misreadings`/`relabel` (l'indice cambia faccia ma non gira),
  `anchor-axis`, `:phases` per un anello incollato storto.
- `bridge/pnp-target-points` — **78** bersagli su una gabbia: 72 mark di corona
  più i sei zero-indici (`:index? true`), che dal 2026-08-23 sono cliccabili.
- `bridge/camera-sees-marks?` — la GUARDIA FISICA: quei dischetti erano
  fotografati, quindi la camera stava davanti a ciascuno. È l'unico arbitro
  della faccia, perché il residuo è cieco ai 3mm fra le due.
- `pnp/solve-pnp` con `:auto` → dlt / planar / planar-seeded.

## Il piano

### Fetta 1 — FATTA il 2026-08-24, e misurata

Bersaglio: battere 89 candidati per 10 dischetti veri, con recall su tutti e tre
gli anelli. **Risultato su `IMG_9014`: 32 candidati, di cui ~29 veri, e 13 mark
noti su 13 — corona 8/8, zero-indice 1/1 e i quattro degli anelli INTERNI, che
prima erano zero su quattro.** Sull'intera sessione più le gabbie vuote: 20-43
candidati per foto, ~0.9s a fotogramma.

Tre cambiamenti, e ciascuno ripara un guasto misurato, non immaginato:

1. **Il riferimento chiaro è il MASSIMO locale, non la media** (`:reference
   :max`, una cimasa morfologica). Una media presa su una finestra larga
   abbastanza da scavalcare un dischetto è fatta quasi tutta di FONDO, e
   scende sotto i mark degli anelli sottili: quelli smettono di essere «scuri»
   e spariscono. Il massimo non si lascia trascinare giù — una finestra centrata
   su un mark tocca comunque la banda su cui è stampato.

   Quello che il massimo lascia entrare in cambio è un NASTRO di fondo largo
   `:win` lungo ogni bordo di banda; ma quel nastro è *una sola* regione
   connessa enorme, e il filtro d'area lo butta via in un pezzo solo. È
   esattamente ciò che la media stretta non poteva fare: lei lo sbriciolava in
   centinaia di macchioline, una per una plausibili.

2. **Risoluzione piena, e non è una raffinatezza.** I mark degli anelli interni
   stanno a 4-6px dal bordo della loro banda. Un solo giro di media a blocchi
   chiude quel varco: i pixel del mark si saldano al nastro di fondo e vengono
   portati via dentro il componente gigante. Misurato: a metà risoluzione 2 dei
   4 mark interni noti spariscono, a risoluzione piena nessuno.

3. **Il criterio di forma è `enclosed-frac`**, sui pixel VERI: da ogni candidato
   partono 16 raggi e si chiede quanti incontrano un pixel abbastanza più chiaro
   prima che il buio ricominci. Raggi e non una corona a raggio fisso, perché un
   mark obliquo è un'ellisse tre volte più lunga che larga: una corona larga
   abbastanza da scavalcarne l'asse lungo esce dalla banda lungo quello corto.

   Attenzione a cosa fa davvero, perché è controintuitivo: a `0.35` **non**
   chiede chiaro tutt'intorno — un mark appoggiato al bordo della banda arriva
   al massimo a 0.5, e pretendere di più lo butta via. Chi si guadagna il pane è
   il CONTRASTO che i raggi devono trovare (55 livelli): la grana della pelle è
   piena di fossette circondate di chiaro, e su un fotogramma ben illuminato
   (`IMG_9015`) davano 50 candidati su 80, finché ai raggi non è stato imposto
   di trovare bianco vero. Con quello: 80 → 32.

Le tarature stanno in mezzo all'altopiano misurato, non sul ciglio: recall pieno
per `:margin` 55-60 (sotto, i mark si saldano al fondo; sopra, si assottigliano
sotto `:min-area`) e per `:enclose-frac` fino a 0.44, che è il punteggio del mark
più difficile del fotogramma.

**Il banco**: `npx shadow-cljs compile cage-study && node out/cage-study.js
~/Pictures/RidleyScan/Presa`. Stampa candidati e recall per anello e scrive i
candidati in JSON, da disegnare sulla foto. Il test `real-cage-photo-detect`
(protetto, salta senza le foto) fissa 13/13 e i tetti sui candidati.

#### Quattro punti della «verità» qui sopra erano sbagliati

Guardando le foto a 4× — la regola della sezione «Trappole», applicata prima di
ragionare — tre dei quattordici pick di `acquire-state.json` non sono dischetti:

- `28` (1390.0, 2523.2) e `30` (931.3, 2474.6) stanno **sul fondo nero**;
- `7` (1949.7, 2776.5), che questo stesso documento riporta fra i dischetti
  «verificati», sta su **banda bianca vuota**: il mark che nomina è a
  **(2071, 2853)**, 143px più in là. Il rilevatore lo trova a 0.5px.

E un quarto, trovato dal fit il 2026-08-24: `9` (304.6, 2235.1) **è** un
dischetto, ma non quello: il pixel cliccato sta a 21.6px da `xm10`, e chiamandolo
`xm09` il fit lo scarta a 270px.

I primi tre sono `proposed?`, cioè predizioni accettate, non click — la prima
constatazione del documento (i click sono impeccabili) resta in piedi quanto alla
MANO; il nome, no. Ma
**l'affermazione che con quei nove punti la posa chiude a 5.15px non può stare
in piedi con uno di essi fuori di 143px**: chi riprende quel numero lo rifaccia
prima di fidarsene. La verità ripulita è la fixture `cage-9014-truth` in
`test/ridley/photogrammetry/blob_detect_test.cljs`.

### Fetta 2 — abbinamento posa + identità

**Il meccanismo è stato trovato e verificato sui dati veri il 2026-08-24**, e non
è quello che il piano prevedeva. Banco: `node out/cage-fit.js` (build
`:cage-fit`, ns `ridley.photogrammetry.cage-fit-study`), che porta i numeri qui
sotto e li rifà in un secondo.

**Quello che il piano prevedeva non funziona.** «Con seme: si campiona un intorno
della posa e si tiene quella che spiega più candidati» presuppone che ci sia un
intorno. Non c'è. Partendo l'LM da 91 pose sparse su ±150° attorno a nove assi si
torna sempre allo **stesso unico minimo**; e scuotendo la posa attorno a quel
minimo — 900 pose per giro, sei giri, con l'rms dell'anello cliccato tenuto sotto
8px — i quattro mark interni noti passano da 92-157px a **97-162px**. Nessuna posa
che spieghi l'anello cliccato spiega gli altri due. Cercare nell'intorno non può
funzionare perché l'intorno non esiste.

**Quello che funziona lo ha reso possibile la fetta 1.** Le 48 riletture della
corona cliccata spiegano l'anello *in sé* in modo indistinguibile — 5.3-5.4px
tutte, che è esattamente la seconda constatazione di questo documento — ma
**pesate sui candidati degli ALTRI DUE anelli non sono affatto equivalenti**:

```
rot 3  mirror no  faccia-girata no  · rms 5.41px · ai 4 interni noti 23 17 17 17
rot 9  mirror no  faccia-girata no  · rms 5.41px · ai 4 interni noti 23 17 17 17
rot 2  mirror sì  faccia-girata sì  · rms 5.41px · ai 4 interni noti 23 17 17 17
rot 8  mirror sì  faccia-girata sì  · rms 5.41px · ai 4 interni noti 23 17 17 17
rot 0  (la lettura dell'utente)     · rms 5.32px · ai 4 interni noti 157 127 92 139
```

Le 48 collassano a 4, che sono **2 pose fisiche** (rot 3 e rot 9 differiscono di
180°; le altre due sono la stessa coppia letta dalla faccia opposta) — e scegliere
fra due facce è precisamente il mestiere di `bridge/camera-sees-marks?`.

Cioè: **la simmetria della corona non si rompe cliccando di più, si rompe col
RESTO della gabbia**. Ed è per questo che il rilevatore doveva venire prima.

Da cui la forma della fetta 2, **costruita il 2026-08-24** in
`src/ridley/photogrammetry/match_cage.cljs` (`read-crown`):

1. rileva i candidati (fatto);
2. l'utente clicca 4+ mark di UN anello più il suo zero-indice — cosa che sa fare
   bene, e che il documento ha già misurato chiudere a 5px;
3. si provano tutte e 48 le riletture, ciascuna punteggiata da **quanto della
   gabbia intera** la sua posa spiega contro i candidati;
4. restano due facce, e decide la guardia fisica, non il residuo;
5. poi `assign` appaia OGNI mark che la posa spiega col dischetto su cui cade
   (nearest-neighbour **mutuo**: un mark si prende un dischetto solo se quel
   dischetto a sua volta ha lui come più vicino), e si risolve di nuovo su tutte
   le corrispondenze — ed è lì che un fit planare a un anello diventa una posa
   condizionata sulla gabbia intera.

**Sui pixel veri di `IMG_9014`** (test protetto `real-cage-photo-read-crown`):
legge `rot 3`, spiega 19 mark, ne appaia 19, e la posa risolta su tutti chiude a
**9.13px**. I quattro mark interni noti passano da **157 127 92 139px** (lettura
dell'utente) a **21 8 4 4px**. Su gabbia sintetica: sfasamenti 0/3/5/9 tutti
recuperati esatti, 39 corrispondenze su 39 mark rivolti alla camera, rms 0.00px.

**La tolleranza `:tol-px` è il numero da capire**, e la fissa la GABBIA, non la
camera: una lettura si giudica su mark che l'anello cliccato non vincola, quindi
la predizione porta addosso tutto l'errore del modello — le fasi di incollaggio
(misurate: 1-2°) e l'anello che non è perfettamente piano (Vincenzo, guardando il
pezzo: «di poco» — a quell'inquadratura ~1.2mm, cioè una ventina di pixel). Più
stretta di così, la lettura GIUSTA prende zero e la ricerca non trova nulla.

**Il residuo, spiegato.** I 17-23px che restavano dopo la sola rilettura non sono
le `:phases`: misurate sulla lettura giusta valgono X −0.4°, Y +0.7…+2.4°,
Z −0.9…−0.3°, cioè un grado o due — un incollaggio fatto bene. È **la planarità**,
e nessuna fase la corregge, perché una fase è una rotazione rigida. Ed è anche
perché il quarto mark interno resta a 21px mentre gli altri tre stanno a 4-8.

Provata e scartata: `:index-phase 0`. Il docstring di `default-index-phase` dice
che una gabbia stampata PRIMA del 2026-08-22 ha lo zero-indice sull'asse del mark
0, e questa è stata incollata il 18-19 agosto — ma è sbagliata per questa gabbia:
con `:index-phase 0` il pallino cliccato finisce a 149px e il solve lo butta,
mentre col terzo di passo nominale sta a 4.1px. Ha l'indice chirale.

**Senza seme** (nessun click) resta la strada lunga: RANSAC sulle ellissi — i tre
anelli proiettano tre ellissi, cinque punti definiscono una conica, il raggio noto
dà la posa a meno di due soluzioni e la seconda ellisse disambigua. Da valutare
dopo, perché il passo 3 qui sopra potrebbe bastare a partire da un seme molto
piccolo.

### Il gate umano — FALLITO il 2026-08-24, e il fallimento era la scoperta

Vincenzo ha cliccato 4 mark ({0,3,6,9} — il sottoinsieme 4-simmetrico, il caso
peggiore) più lo zero-indice e premuto `a`. L'app ha detto «i nomi che avevi dato
erano giusti», ha registrato a 6.4px sull'anello X — e ha lasciato Y e Z
scambiati, con Y chiamata `m` quando lui, guardando il pezzo, la sapeva `p`.

**Tutti e due i verdetti erano giusti, ed è questo il punto.** I candidati
dicevano «gli altri anelli stanno 3 passi più in là» (il rot 3 di tutta la
sezione qui sopra); lo zero-indice cliccato diceva «i nomi sono giusti» (rot 0,
5.4px). La conciliazione è l'errore di montaggio che nessuno dei due da solo può
vedere: **l'anello grande è INCOLLATO girato di un quarto di giro** — la sua
rotazione è l'unica che nessun giunto impone, e a 90° le linguette ricadono fra i
mark, quindi a occhio è perfetto. Verificato sui suoi click con tre testimoni:

- `:phases {:x 90}` → zero a 7.9px ✓, interni a 5-24px ✓, facce **x=m y=p z=m** —
  esattamente come Vincenzo le aveva lette sul pezzo ✓;
- `{:x -90}` → interni ok ma facce y=m/z=p ✗ (il segno lo decidono le FACCE, non i
  candidati: ±90 mettono i piani degli anelli nello stesso posto).

**Quindi il «rot 3» delle sezioni sopra era la fase travestita da rinominazione**:
spiegava i candidati ma buttava via lo zero-indice come outlier senza dirlo — il
`solve-pnp` dentro la ricerca può scartare 2 punti, e scartava proprio il
testimone. I numeri (19 corr, 9.13px, interni 4-21) restano validi come geometria;
i NOMI di quella lettura erano sbagliati, e le facce con loro.

Da cui le due correzioni in `match-cage`, entrambe con test sintetico + reale:

1. **Lo zero-indice non si mette ai voti.** Se il vincitore ai punti ha scartato
   un indice cliccato e esiste una rilettura che lo tiene, vince quella, e la
   differenza di rot diventa la diagnosi (`:phase-suspect {:axis :steps :deg}`).
2. **`phase-probe`** per quando i click sono pochi e la rilettura concorrente non
   fa punti (il caso live: 4 click): sotto la posa vincente si provano gli altri
   anelli ruotati di −k passi attorno all'asse cliccato; se un k spiega ≥6 mark e
   ≥4 più del nominale, stessa diagnosi. `phase-from-residuals` NON può vederlo:
   misura la fase solo modulo un passo.

Il messaggio in `edit-acquire` nomina la causa e dà la forma da copiare:
`(registration-cage :d 176 :phases {:x 90})`, col caveat sul segno. k e 12−k sono
lo stesso quarto di giro visto dai due versi: si riporta il canonico e il segno lo
verificano le facce.

**La gabbia di riferimento VA MODELLATA così d'ora in poi**:
`(registration-cage :d 176 :phases {:x 90})`. Non è un difetto da rifare: è una
proprietà del pezzo, misurata, e dichiarata funziona al pari del nominale.

**Esito col quarto di giro dichiarato (2026-08-24, Vincenzo)**: gate PASSATO.
`r` da solo registra a 8.7px in **dlt** — non più planar: le corrispondenze
attraversano più anelli, cioè la posa è vincolata su tutti e sei i gradi di
libertà, che è il motivo per cui la gabbia esiste — e `a` conferma («i nomi che
avevi dato erano giusti»), piazza altri mark, 10.8px. Y e Z giusti, facce giuste.

**E per le gabbie future la fase non serve più: c'è la CHIAVE DI MONTAGGIO**
(proposta di Vincenzo, «una tacca e una spina», implementata lo stesso giorno).
`joint-tabs` ora emette `:key-pin` (spina su una linguetta dell'anello di mezzo,
al livello del piatto di stampa: zero sbalzi) e `:key-notch` (tacca passante nel
bordo dell'anello grande — l'UNICA scatola della famiglia che è un TAGLIO nel suo
owner, non materiale). Infilando l'anello grande, la spina incontra il bordo e
l'anello non si posa — a QUALSIASI rotazione sbagliata, e anche girato
faccia-per-faccia (la spina è asimmetrica apposta) — finché la tacca non la
riceve. La spina affonda 1mm nella linguetta (un contatto esatto = facce
complanari, la ricetta nota degli artefatti CSG). `printable-ring` porta il
`:kind`; `acquire-cage` unisce il materiale e sottrae il taglio. Verificato coi
test geometrici (margine tacca→mark 20.4mm) e DAL VIVO nel browser: le mesh
stampabili contengono spina e tacca alle coordinate calcolate.

### Fetta 3 — cablaggio: FATTA il 2026-08-24

Tasto **`a`** (e bottone «Auto — leggi la gabbia») su una gabbia →
`edit-acquire/cage-read-and-place!`. Clicchi 4 dischetti su UN anello, premi `a`,
e: rileva i candidati in tutta la foto, legge la corona con
`match-cage/read-crown`, **rinomina i tuoi pick senza toccarne i pixel**, piazza
come proposti tutti i mark che la lettura spiega, e chiama il solve normale.

La scelta che conta: quello che finisce in sessione è **ordinario**. I mark
piazzati sono `:proposed?` come quelli della fetta A del piatto, quindi il
pannello, i residui, i pallini rossi degli outlier e il report delle fasi
funzionano senza una riga di impianto nuovo, e una proposta che ha agganciato il
dischetto sbagliato si presenta come un outlier rosso da ricliccare, come
qualunque altro.

È **seeded**, non zero-click, e va detto invece che nascosto: il rilevatore trova
i dischetti, ma una corona di mark uguali non può dire quale sia il mark zero —
nessuna fotografia può — quindi i quattro click sono ciò su cui l'automatico sta
in piedi.

Cosa dice quando non ce la fa: se `read-crown` torna nil, nomina la causa
abituale (si vede UN anello solo — bastano pochi gradi fuori dall'asse); se torna
con `:ties` non vuoto, **avvisa** che più riletture spiegano la gabbia
altrettanto bene e che quella foto non basta a decidere.

Il messaggio `no-auto-on-cage-msg` — che le altre vie automatiche (batch `r`,
grab `g`, ancora costruite sulla corona unica del piatto) danno a una gabbia —
non dice più «la gabbia non ha il rilevamento automatico», che era diventato
falso: manda ad `a`.

**Verificato dal vivo, non solo compilato**: bundle del browser ricaricato,
`match-cage/read-crown` chiamato sui candidati veri di IMG_9014 dentro Chrome →
`rot=3 explained=19 corr=19 fullRms=9.24px`, gli stessi numeri di node. E la
chiamata a vuoto ha trovato un bug che né il compilatore né un test con sessione
già aperta potevano vedere: `(nth (:photos @session) idx nil)` con `idx` nil
lancia, perché in CLJS `nth` rifiuta un indice non numerico anche con default.
Ora è `get` su un vettore.

## Il banco di prova, e usalo

### Dati veri

- **`~/Pictures/RidleyScan/Presa`** — cinque foto della sessione, fondo NERO,
  più `acquire-state.json` con i pick veri (tre dei quali NON sono dischetti:
  vedi fetta 1). La foto 0 è `IMG_9014.jpeg`. Le copie di lavoro delle foto 0 e 1
  stanno in `test-assets/cage-presa/` — si prova su quelle, mai sulla sessione.
- **`~/Pictures/RidleyScan/GabbieVuote`** — gabbia sola: `IMG_9019` su carta
  bianca (la più pulita), `IMG_9021`/`IMG_9022` contro la scrivania (fondo
  disordinato, molti falsi), `IMG_9020` come 9021 ma con ritaglio digitale a
  121mm equivalenti.

### Verità note su `IMG_9014` (coordinate di visualizzazione 3024×4032)

Ricontrollata a 4× il 2026-08-24; la versione corretta vive come fixture
`cage-9014-truth` in `test/ridley/photogrammetry/blob_detect_test.cljs`.

```
corona  (494.0,1005.8) (992.3,578.5)  (1631.4,422.0) (2261.8,573.9)
        (2728.4,1022.3)(2648.0,2362.9)(2071.0,2853.0)(304.6,2235.1)
zero    (693.9,890.8)
interni (2274.1,2116.5)(1898.9,2047.2)(1021.4,1711.0)(661.1,1491.7)
```

Rispetto a com'era scritto qui prima: `xm07` era (1949.7,2776.5) — banda vuota,
il mark vero è 143px più in là — e fra gli «interni» comparivano (1390.0,2523.2)
e (931.3,2474.6), che stanno sul fondo nero, mentre mancava (2274.1,2116.5).
L'identità (quale mark è quale) resta NON stabilita per gli anelli interni: è il
lavoro della fetta 2.

Il **5.15px** citato più sotto è stato ottenuto includendo `xm07`: non
riutilizzarlo senza rifarlo.

### Le facce, dette da Vincenzo guardando il pezzo

- foto 1 (`IMG_9014`): **X e Z sono `m`, Y è `p`**
- foto 2 (`IMG_9015`): **X è `p`, Y e Z sono `m`**

Sono l'unico dato indipendente sulla faccia. Un risultato che le contraddice è
sbagliato, per quanto basso sia l'rms.

### Focale e intrinseche

EXIF dà 48mm equivalenti su tutte le foto della sessione. Le intrinseche si
costruiscono `(cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 48 (/ 3024 4032)) 3024 4032)`
— e **l'orientamento conta**: usare 4032×3024 al posto di 3024×4032 è costato
un giro il 19 agosto.

## Trappole, tutte pagate

- **Guarda la foto PRIMA di ragionare.** Il 23 agosto ho sbagliato tre diagnosi
  di fila (gemella planare, anello incollato girato, vicinanza affidabile dopo
  il primo fit) e ogni volta la correzione è arrivata dal disegnare i punti
  sulla foto o dal leggere i pick veri. `magick` c'è: disegnare i candidati
  sull'immagine e guardarli costa un minuto e vale più di un'ora di ipotesi.
- **Non generalizzare da due numeri fortunati.** Avevo dichiarato «dopo la
  registrazione dell'anello X la vicinanza è affidabile» avendo visto 27 e
  48px; sul set successivo erano 130–260px.
- **Il residuo non sa nulla della faccia.** Misurato: 0.00px con le etichette
  giuste e 0.00px con quelle della faccia opposta, perché i 3mm se li mangia la
  camera spostandosi di 3mm. Solo `camera-sees-marks?` decide.
- **La scala è inosservabile**: `:d` 176 o 230 danno lo stesso rms. Non
  diagnosticare mai con uno sweep del diametro.
- **Un solo Ridley alla volta.** Il geo-server sta dentro il processo Tauri e
  serve ANCHE la scheda del browser; se la porta 12321 è occupata il secondo
  non parte e ogni cartella legge vuota. `lsof -nP -iTCP:12321 -sTCP:LISTEN`.
- **Mai `shadow-cljs release app` col watcher attivo**: scrivono lo stesso
  `public/js/main.js`. Ricompilare passando dall'nREPL già connesso
  (`(shadow.cljs.devtools.api/compile :app)`), mai un secondo processo CLI.
- **Provare sulle COPIE**, non sulla sessione di Vincenzo: il 22 agosto ho
  distrutto quattro punti di ricalco suoi testando dal vivo.
- **`js->clj` con `:keywordize-keys` non keywordizza i vettori.** Un path
  costruito così scrive su una chiave-stringa e il codice legge la keyword: si
  perde mezz'ora a credere che uno `swap!` non abbia effetto.

## Cosa NON fare

- Non ritoccare le tarature del PIATTO: `default-opts` è tarato su quello e
  funziona (recall 12/12 sul gate di luglio). La gabbia ha il suo `cage-opts`.
- Non far scegliere all'utente la faccia come strada principale: l'aveva
  proposto lui, ma con l'automatico non serve più. Semmai come rete.
- Non promettere che il tele aiuti: l'EXIF dice che è un ritaglio digitale
  della stessa ottica, e allontanarsi appiattisce la prospettiva, che è proprio
  l'informazione di profondità che manca. Consiglio alle foto: **48mm nativo,
  vicino, gabbia inclinata, fondo liscio e chiaro, ombra staccata dal fondo.**

## File

- `src/ridley/photogrammetry/blob_detect.cljs` — rilevatore, `cage-opts`, `bright-around`
- `src/ridley/photogrammetry/match_plate.cljs` — `fit-crown`, `assign-marks` (il modello da imitare)
- `src/ridley/photogrammetry/cage.cljs` — geometria, id, riletture, `:phases`
- `src/ridley/photogrammetry/bridge.cljs` — `pnp-target-points` (78 bersagli), `camera-sees-marks?`
- `src/ridley/photogrammetry/pnp.cljs` — `solve-pnp`, `accept-rms-px`
- `src/ridley/editor/edit_acquire.cljs` — `on-auto-register!` (~3256), tasto `a` (~5549),
  `cage-relabel-rescue` / `best-misreading` (~1948)
- `test/ridley/photogrammetry/cage_test.cljs` — 959 test verdi, fixture su dati veri
- `dev-docs/HANDOVER-registration-cage.md` — la gabbia nel suo complesso

## Appendice — save-3mf: tre guasti in uno (2026-08-24)

Vincenzo: «la save-3mf sulla desktop app non produce nessun file in Downloads,
su Firefox produce solo gabbia-176-small.3mf». Tre difetti veri, verificati e
corretti:

1. **Firefox**: i tre download partivano da un `Promise.all` — tre `a.click()`
   nello stesso giro di eventi, e i click successivi soppiantano i precedenti
   prima che il browser li commetta. La PROVA era in Downloads: `small-2` e
   `small-3` delle 16:48, cioè due tentativi e da ciascuno solo l'ULTIMO file.
   Ora i download sono sequenziali a 600ms — misurato dal vivo via CDP:
   click a 946/1587/2229ms, tutti e tre.
2. **Desktop, la `~` letterale**: `stl/expand-home` risolve `~` con una XHR
   sincrona che nella WKWebView può fallire in silenzio; la `~` letterale arriva
   al geo-server, che diligentemente prova `CWD/~/…` — per un'app lanciata dal
   Finder `/~`, permesso negato. Ora `expand_tilde` sta NEL SERVER Rust
   (write/read/delete): il server conosce la propria $HOME, non c'è ragione di
   fidarsi che il client l'abbia saputa. (Richiede una build desktop nuova.)
3. **Il silenzio**: la promise del salvataggio viene deliberatamente scartata
   dal chiamante SCI (un println asincrono ricomparirebbe nell'evaluation
   successiva — problema noto), e con lei si scartava anche il RIGETTO. Ora
   `stl/set-async-notify!` registra il pannello errori di core all'avvio e ogni
   salvataggio si porta un `.catch` che ci scrive: un fallimento asincrono non
   è più muto.

**Pericolo residuo, da non dimenticare**: la libreria builtin sta sul
FILESYSTEM (`~/.ridley/libraries/`), condivisa fra TUTTE le istanze desktop —
un'app vecchia (l'installata è 3.5.1 del 2 agosto) può trovarsi a eseguire una
libreria nuova che chiama binding che il suo bundle non ha, e l'errore è
«Could not resolve symbol» a runtime. Il negozio di librerie non è versionato
contro l'app: prima o poi servirà un minimo di gating.

## Prossimo fronte proposto — il PORTAPEZZO a bastoncini (Vincenzo, 2026-08-24)

L'idea, con le sue parole: «dei fermi con blocco a camma (4 su ogni anello) che
permettano di bloccare bastoncini da spiedino o piccoli tubetti di fil di ferro
o anche stick stampati in 3D. Lo scopo è tenere l'oggetto da ricalcare al centro
della gabbia senza mollette o nastro adesivo: ingabbiato tra le punte dei
bastoncini che escono dai diversi anelli — con quattro o cinque bastoncini si
può bloccare qualsiasi oggetto.»

È la chiusura giusta del cerchio: le mollette e gli steli verdi della sessione
Presa sono ESATTAMENTE ciò che oggi copre dischetti e genera falsi candidati.

Vincoli da rispettare, tutti già pagati altrove:

- **La camma è la scelta giusta per una ragione precisa**: si autoadatta al
  diametro (spiedino ~3mm, fil di ferro 1.5-2, stick stampati qualunque). Un
  foro fisso o un collet no.
- **Forma stampabile senza supporti**: canale radiale a V APERTO IN ALTO
  (nessun foro chiuso) + leva eccentrica che preme il bastoncino nella V — lo
  schema del cam-cleat. Stampa piatta con l'anello, come le linguette.
- **Azimut liberi**: i mark stanno ai multipli DISPARI di 15° (15,45,75,…), le
  linguette a 0/90/180/270. Per 4 fermi per anello: 30/120/210/300 — a 15° dal
  mark più vicino e 30° dalle linguette. MAI coprire un dischetto.
- **I bastoncini nelle foto**: attraversano l'inquadratura come oggi gli steli
  — sceglierli CHIARI (il bambù va bene): il rilevatore cerca macchie scure, e
  un bastoncino scuro gliene regala.
- La generazione dei mesh senza export è verificata (make-cage-ring /
  make-print-ring, chiave inclusa): si può prototipare nell'editor.

**COSTRUITO il 2026-08-25, su design COLLAUDATO da Vincenzo** — che ha superato
la mia idea (cam-cleat con leva): lo stick stesso è la camma. Sezione ellittica
4.0×3.6, canale 4.4×4.0 con gioco 0.2 per lato; infili con le ellissi allineate,
un quarto di giro e l'asse maggiore dello stick morde l'asse minore del canale.
Zero pezzi mobili. Ha anche disegnato la `punta-tricuspide` (tre sfere schiacciate
in blend SDF), piedino opzionale che si monta sulla punta con lo stesso principio.

Implementazione: `cage/stick-slots` (pose: 2 per anello a 30°/210° locali, raggio
outer−7, heading verso il centro, up = asse dell'anello) esportate anche in
`printable-ring :slots`; la libreria le costruisce (`slot-pieces`) come blocchi
dal piatto di stampa a +6 sopra la faccia (ricetta delle linguette, zero sbalzi)
col canale ellittico passante — asse maggiore VERTICALE in stampa, così il
cedimento del ponte cade sui 0.4mm di gioco e non sullo zero del bloccaggio.
`acquire-cage/stick` e `acquire-cage/punta-tricuspide` (il suo design, verbatim).

Bug trovato dal vivo e da ricordare: `rotate` su mesh gira attorno alla
CREATION-POSE, che `mesh-translate` porta con sé — ruotare dopo la traslazione
fa girare il pezzo su se stesso invece che attorno all'origine. Orientare prima,
traslare dopo, e la posizione arriva dai dati di posa del proxy.

Resta il collaudo di stampa: chiave di montaggio + slot nella prossima gabbia.

## Appendice — la rifinitura congiunta non è più avvelenabile (2026-08-26)

Dalla sessione vera della gabbia nuova (quella con chiave e portapezzi): la foto
6, registrata a 138.8px dopo un groviglio di click doppi, è entrata nella
rifinitura congiunta e ha trascinato la focale condivisa da 48.6 a 60.7mm,
portando le foto pulite da 3-8px a 12-20px. Il refiner sapeva chi era il
colpevole — stampava «è questa che tira su la media» — e lo lasciava vincere.
E ha perfino ADOTTATO un passaggio peggiorativo: 48.9 → 90.3px, con la focale
ferma al limite del clamp. Con la focale avvelenata, la foto 8 non poteva che
fallire («ogni soluzione mette la camera dietro un dischetto cliccato»).

Due regole, ora nel codice (`on-refine-session!`):

1. **Una foto sopra la soglia (accept-rms-px, 12) non vota sulla lente.** Il
   fit congiunto è ai minimi quadrati: un voto avvelenato non si media, trascina.
   La foto resta nella sessione, il log dice che è esclusa e come sistemarla
   (Azzera, 4 click + 'a', poi R).
2. **Un risultato peggiore non si adotta.** Se l'rms totale dopo la rifinitura
   supera quello di prima, si tengono focale e pose correnti e si nomina la
   foto peggiore da guardare.

## Fetta 5 — ZERO CLICK (2026-08-27, prima luce)

Vincenzo, a valle della settimana di recupero: «se non riusciamo ad avere la
registrazione automatica delle foto sarà tutto inutile». Direttiva accolta:
`match-cage/auto-read` legge la gabbia SENZA click. Catena: candidati dal
rilevatore → ipotesi di anello (RANSAC ellissi, `ellipse/fit-inliers-ranked`,
piso 8 inlier per il muro di costo C(12,k)) → per ipotesi × faccia, identità
con `mp/assign-marks` (corona + ZERO-INDICE **verificati sui pixel**) → posa del
seme → raccolta su tutta la gabbia (`assign`) → solve pieno → guardie (rms ≤
soglia, camera-sees, phase-probe). Cablata in `a` a zero click; il seeded resta
il ripiego, e il messaggio lo dice.

Tre lezioni pagate sul banco (`node out/cage-auto.js`, sessione battiscopa: 8
foto registrate a mano = verità):

1. **Il seme della macchina NON passa dal voto a 48.** Il voto esiste per
   etichette non fidate (le mani); il seme automatico ha l'indice puntato e la
   faccia giudicata sui pixel. Il voto gli rompeva i nomi (rot 5 → 547mm) o lo
   uccideva coi pareggi del gemello attraverso-la-plastica. Senza voto, la
   stessa foto 8 — due giorni di battaglia a mano — si registra DA SOLA a 1.0mm
   dalla verità.
2. **Mai il primo che passa: il migliore.** Gli stessi 11 dischetti si
   identificano come anello X E come anello Y (stesso cerchio, raggio diverso:
   la posa assorbe la scala nella distanza), entrambi a rms pulito, entrambi
   oltre la guardia. Li separa solo quanto del RESTO della gabbia spiegano
   (13 vs 12, misurato): first-wins spediva il 12, camera a 697mm.
3. **Il budget di identità** (`:max-identify` 12): l'identificazione dei
   costellati-spazzatura era il costo intero (40–65s a rifiuto → 10–23).

Stato: 2/8 da sola, 0 falsi. Le sei rifiutate muoiono TUTTE allo stadio
ellissi: l'anello vero non emerge fra le ipotesi (junk conics vincono, o
raccoglie <8 inlier). Frontiera prossima, in ordine di leva attesa:
- **concentricità**: i tre anelli condividono il centro — un RANSAC che ipotizza
  la famiglia concentrica invece di coniche indipendenti;
- **identità condivisa fra le famiglie di anello**: X/Y/Z hanno la stessa
  geometria a meno di scala — oggi la stessa ricerca si paga 6 volte;
- **recall del rilevatore su queste foto**: 16–31 candidati contro i 19–30
  pick della mano (su foto 8: 16 vs 19 — la mano vedeva dischetti che il
  rilevatore perde).
