# Handover: spigoli dichiarati (gradino 3, fetta 1)

Aperto 2026-08-06, cresciuto per sei giri d'uso vero fino al 2026-08-07. Stato:
**COSTRUITO e COMMITTATO** sul branch `acquire-edges-declared`; suite verde
(893 test / 3515 asserzioni); verificato dal vivo sulla sessione vera.

Il gate umano è in corso e sta guidando il lavoro: Vincenzo ha già creato un
piano dai tratti dritti. Ogni sezione qui sotto nasce da una sua osservazione
all'uso, ed è ordinata dalla più recente alla più vecchia — chi arriva adesso
legga le prime tre, che sono la forma che il gesto ha oggi.

Brief di riferimento: `dev-docs/brief-observation-driven-acquire.md`.

## Perché esiste

Il gesto "Piano" chiede la cosa più difficile che ci sia: ritrovare LO STESSO
PUNTO FISICO da un'altra angolazione. Sulla plastica nera lucida quel punto
spesso non c'è, ed è da lì che venivano gli "specchiati" del gate di fusione.

Uno spigolo non lo chiede. Su ogni foto si dichiara **la riga nell'immagine** —
due click dove capita lungo lo spigolo — e due punti qualsiasi di una retta
valgono quanto altri due. L'identità della FEATURE resta dichiarata dall'utente
("questo è lo stesso spigolo"); l'identità del PUNTO non serve mai.

E il click diventa prodotto: ne esce un segmento 3D misurato dell'oggetto,
scritto nel sorgente come una posa che percorre lo spigolo.

## Dove sta il codice

| pezzo | file |
|---|---|
| solutore puro | `src/ridley/photogrammetry/edge.cljs` |
| test sintetici | `test/ridley/photogrammetry/edge_test.cljs` |
| rilevamento da UN click | `src/ridley/photogrammetry/edge_snap.cljs` (`edge-at-point`) |
| suoi test su foto finte | `test/ridley/photogrammetry/edge_snap_test.cljs` |
| cerchi e archi | `src/ridley/photogrammetry/circle.cljs` |
| loro test | `test/ridley/photogrammetry/circle_test.cljs` |
| gesto + HUD + write-back | `src/ridley/editor/acquire_stage.cljs`, sezione "DECLARED EDGES" |
| disegno dal sorgente | `acquire_stage.cljs`, `source-edge-items` |
| forma di riposo `(edge-mark …)` | `src/ridley/editor/edit_acquire.cljs` |
| `:edges` nel valore + fusione | `edit_acquire.cljs`: `acquire`, `transform-edge`, `fuse-sessions` |
| ancoraggi turtle | `src/ridley/turtle/core.cljs`, `named-poses` |
| binding SCI | `src/ridley/editor/bindings.cljs` |

## La geometria in tre righe

Una riga d'immagine + il centro ottico spannano un **piano** nel mondo, e lo
spigolo ci sta dentro. Due piani non paralleli si incontrano in una retta sola
(forma chiusa); le altre foto la sovradeterminano, e l'LM minimizza la distanza
in PIXEL dei capi dichiarati dalla retta riproiettata — mai da un punto, che è
il motivo per cui non serve corrispondenza.

Conteggio dei gradi di libertà, da cui discendono tutte le guardie: una retta 3D
ne ha 4, ogni foto ne vincola 2. Quindi **due foto sono esatte** (residuo zero
per costruzione: `:exact?` viaggia col risultato), tre rendono il residuo una
misura, e **quattro** sono il primo numero a cui il leave-one-out può nominare un
colpevole (togliendone una da tre resta un sistema esatto e chiunque sembrerebbe
la causa).

## Un click, non due (2026-08-06, chiesto da Vincenzo)

Domanda: «si riuscirebbe a identificare l'edge con un singolo click?». Sì, ma da
un posto solo dei due possibili:

- **dalla geometria già nota**: NO. Dichiarato lo spigolo sulla prima foto, un
  click sulla seconda vincola la retta 3D di UNO dei suoi quattro gradi di
  libertà — servirebbe comunque il secondo click lì. Zero guadagno.
- **dall'immagine**: SÌ. Attorno al click la direzione del bordo è già scritta
  nei gradienti, e il **tensore di struttura** la legge — insieme a quanto quel
  punto è un bordo dritto invece che un angolo (`coherence`, da 0 a 1). Avuta la
  direzione, il programma CAMMINA lungo il bordo agganciandosi al contrasto
  finché il contrasto c'è.

Quindi un click non sostituisce solo l'altro click: **trova anche l'estensione**,
e cammina molto più lungo di quanto uno tracci a mano — il che migliora il
condizionamento invece di barattarlo con la comodità (972 px contro 120 px di un
tratto generoso, nel test).

**Il pezzo che ha fatto la differenza, e va capito prima di toccare le soglie**:
i bordi veri FINISCONO IN CURVE (un raccordo, uno spigolo arrotondato). Il
cammino segue il contrasto e quindi esce dal tratto dritto ed entra nella
piega — e adattare una retta a tutto quello che ha trovato buttava via anche la
parte buona. Ora si tiene il **tratto dritto attorno al click**
(`straight-run-around`, che cresce a somme correnti, O(1) per punto). Misurato
sul collare nero di Vincenzo, sondando alla cieca 1442 punti sulla sua area:
**1 successo prima, 33 dopo** — e il migliore è un bordo dritto di 290 px che è
lo spigolo verticale sinistro del pezzo.

Le quattro maniere in cui RIFIUTA, ognuna con la sua frase in italiano:
`:flat` (niente contrasto), `:ambiguous` (più di una direzione: angolo, incrocio,
texture — il tensore ne farebbe una media che non appartiene a nessuna delle
due), `:short` (il bordo dura meno di 20 px), `:curved` (il tratto dritto è
meno di 60 px o meno del 35% di quanto camminato: è un arco). In tutti e quattro
i casi il gesto **chiede i due click**, che restano lì come via di scampo — e un
secondo click su una foto che ha già il suo tratto significa «questo lo disegno
io», che è anche come si riprende in mano l'estensione scelta dal programma.

Perché 60 px: con ~0.5 px di rumore di aggancio su ~30 stazioni, 60 px di base
fissano la direzione a circa un terzo di grado, e sotto la mano fa altrettanto.
La sagitta lo dice dall'altro lato — un cerchio resta dentro 1.2 px per
`sqrt(8·R·tol)` px, cioè 34 px su raggio 120, quindi un arco non può arrivarci e
continua a essere rifiutato come arco (verificato: il test del disco tiene 58 px
e viene respinto).

## SCRIVEVA NELLA FORMA SPENTA (2026-08-07, due giri)

«Avevo commentato il `(def A …` di prima per ripartire da zero, e con `n` scrive
a riga 24 anziché nel blocco non commentato (quello a riga 27).»

Difetto vero, e appena il banco ha cominciato a vivere nel sorgente diventava
inevitabile: il write-back cercava la sua forma con una ricerca di testo — la
PRIMA occorrenza di `(acquire "dir"` nel buffer — e una forma spenta è pur
sempre un'occorrenza. Anzi è quella che vince **sempre**, perché il modo di
ripartire da zero è disattivare la vecchia e scrivere la nuova SOTTO.

Il primo rimedio ha saltato solo i commenti di riga, e lui ha risposto «No,
scrive ancora in quello commentato». Aveva ragione, e la lezione è che la
domanda era troppo piccola: la sua vecchia forma non era dietro `;;`, era dentro
`(comment def A (acquire …`, che è testo vivo per qualunque misura lessicale.

Domanda giusta: **questo punto fa parte del programma?** `source-edit/dead-code?`
cammina le parentesi dall'inizio del buffer (rispettando stringhe e commenti di
riga) e guarda se una qualunque forma ancora APERTA è di uno dei tre tipi che
significano "non è programma": commento di riga, `(comment …)`, `#_`. Testo
sbilanciato davanti può solo farle rispondere "non morto", cioè il
comportamento di prima. La usa `find-form-bounds` (`modal_evaluator.cljs`), che
è il localizzatore di TUTTI i modali: ne guadagnano gratis edit-path,
edit-bezier, edit-attach e edit-image-board.

Il lato LETTURA non aveva il problema e non è stato toccato: banco e piani si
leggono da `:pending`, che nasce dalla VALUTAZIONE dell'acquire — una forma
spenta non si valuta, quindi non è mai esistita per il banco.

### Lo slot che non c'è

Guardando il suo buffer vero è saltato fuori il muro successivo, a due secondi
di distanza: la forma viva era stata sfoltita a mano a `:proxy` e `:pose`, e
`ensure-edges-slot!` sapeva creare `:edges` solo appendendolo a `:marks`. Senza
`:marks`, la risposta a un bordo appena misurato sarebbe stata «vai a scrivere
`:edges {}` dentro quella mappa e ridisegnalo».

Ora `ensure-slot!` (uno solo, per bordi E piani) prova prima ad affiancare la
chiave sorella — che tiene l'ordine delle chiavi emesse — e altrimenti scrive
in fondo alla mappa delle opzioni, trovata con `src/first-map-bounds` (salta le
stringhe: le graffe dentro `"dir{x"` non contano) e indentata con
`src/entry-column`.

Verificato dal vivo (Playwright, sul build in esecuzione), su una replica fedele
del suo buffer — `(comment def A (acquire "…param-plate-paper"` in cima, il
`(def A …` vero sotto, un altro `(comment` in fondo:

- `find-form-bounds` torna la forma viva sia con la dir sia col ripiego;
- `commit-edge!` scrive `spigolo-1` **nella forma viva**, creando `:edges` dal
  nulla, e la forma spenta resta byte per byte identica;
- `commit-plane-mark!` fa lo stesso con `piano-1` e `:marks`.

## IL BANCO VIVE NEL SORGENTE (2026-08-07)

«Sembra non ci sia modo, se sbagli, di annullare un piano e rifarlo. Non sarebbe
meglio accumulare le cose (piani, segmenti) nel sorgente, così li posso cancellare
come testo invece che nella UI?»

Sì, ed era il resto del canale a essere coerente e questa gestura no. I piani già
finivano nel sorgente; erano i BORDI a restare in un magazzino invisibile, che
per giunta spariva chiudendo il gesto.

Ora `n` non mette il bordo su un banco in memoria: lo **scrive nel sorgente**,
nel blocco `:edges` dell'acquire, e il banco è la LETTURA di quel blocco. I piani
si leggono da `:marks` come sempre. Nessuna copia in memoria di nessuno dei due —
una copia sarebbe proprio ciò che una riga cancellata non riesce a raggiungere.

Il guadagno non è solo l'annullamento. Cancellare, rinominare, riordinare,
tenere fra una sessione e l'altra, diffare: il testo fa già tutto questo, e ogni
verbo che avrei dovuto costruire nella UI è un verbo che non esiste. Il codice
che ne è uscito è meno di quello che c'era.

- una curva ha una forma di riposo sua, `(curve-mark {:points […]})`: nessuna
  posa, perché una curva non ce l'ha — è EVIDENZA per un piano, non un ancoraggio,
  e `named-poses` la salta apposta;
- `acquire-union` la trasporta (i punti si muovono come punti);
- Backspace, che prima toglieva l'ultimo dal banco, ora dice dove si cancella.

Verificato dal vivo: due bordi e un piano nel sorgente → si cancella `:curva-1`
come testo e il banco passa da 2 a 1 → si cancella `:piano-1` e il piano sparisce
da elenco, disegno ed etichette.

## LA PENNELLATA, TERZO GIRO: TRE QUANTITA', NON UNA (2026-08-07)

«Ancora non si riesce. Qui siamo un pelo zoomati. Senza zoom viene verde, ma
credo prenda altri bordi che a quel punto vengono inclusi.»

Le due frasi sono la stessa causa vista da due lati, e la misura le unisce: il
tubo laterale era ancora misurato dalla pennellata DIPINTA, quindi doveva
assorbire l'errore della mano; e siccome la punta e' in pixel-SCHERMO, zoomando
diventa stretta in pixel-foto. Zoomato: tubo da 7 px, il cammino esce dopo 40 px
su 564 dipinti, e sotto la soglia esce «non c'è contrasto» — il messaggio dello
screenshot. Senza zoom: tubo da ~58 px, abbastanza largo da lasciare il cammino
saltare su un bordo parallelo vicino, che e' il «prende altri bordi».

Dalla stessa gestura escono TRE quantita', e adesso fanno tre mestieri distinti:

| quantita' | che cos'e' | dove agisce |
|---|---|---|
| **larghezza** (punta, px-schermo × scala) | quanto la mano puo' sbagliare | aggancia di lato i punti dipinti sul contrasto |
| **lunghezza** | dove il bordo deve finire | proiezione fra i capi (+12 px) |
| **tubo** (14 px, assoluto) | quanto il cammino puo' scostarsi dal BORDO | attorno ai punti AGGANCIATI |

La chiave e' l'ultima riga: una volta agganciati di lato i punti dipinti, il tubo
si misura dal bordo VERO invece che dalla pennellata, quindi non deve piu'
assorbire l'errore della mano — e puo' essere stretto e indipendente dallo zoom,
che e' quello che serve per non saltare sul bordo accanto.

Misurato, cinque regimi: zoomato forte con mano peggiore → ✓ 108 px, scarto 1.19;
senza zoom con punta grossa (raggio 156 px!) e mano larga → ✓ 66 px, scarto 0.88.
Prima il primo dava «niente contrasto» e il secondo scappava.

## LA PENNELLATA: LARGHEZZA E LUNGHEZZA SONO DUE COSE (2026-08-07)

«Non ce la fa ancora con questo (sempre foto 1), ho provato sia con size grandi
che piccoli.» Il bordo era la silhouette superiore del collare, e il censimento
su di essa lo spiega: dopo l'allargamento della banda i `:flat` erano spariti
(46 punti: 10 ok, 36 `:curved`), ma i `:curved` camminavano **700-970 px** attorno
al profilo del pezzo tenendone dritti un centinaio. Il cammino scavalcava, e la
pennellata avrebbe dovuto fermarlo.

Non lo fermava, e la misura dice esattamente da che larghezza in poi:

| raggio della fascia | esito |
|---|---|
| 10-30 px | **DRITTO**, rms 1.19 |
| **60 px** | `:curved` — scappa di nuovo |

E la punta di default, allo zoom normale (≈4 px-foto per px-schermo), produceva
già una fascia da ~56 px. Cioè: la pennellata funzionava solo se stretta, e
stretta di default non era.

**Il difetto era di disegno**: usavo la LARGHEZZA per due lavori in conflitto —
tollerare l'imprecisione della mano (vuole essere generosa) e fermare il cammino
(vuole essere stretta). Ma quello che si dice dipingendo è «FIN QUI», cioè una
LUNGHEZZA. Ora sono due condizioni separate:

- **lungo** il tratto: fra i suoi due capi, proiettando sulla direzione
  principale della pennellata (più 12 px di margine, perché la mano si ferma dove
  intende ma non al pixel). È l'istruzione, e vuole essere esatta.
- **di lato**: entro il raggio della fascia. È la tolleranza, e può essere larga
  quanto serve. Serve anche a un secondo scopo, che prima non poteva avere: ogni
  seme viene AGGANCIATO DI LATO al contrasto più forte dentro la fascia prima di
  provarci, perché una pennellata tirata venti pixel fuori dal bordo offriva solo
  semi venti pixel fuori dal bordo.

Con le due cose separate, ogni raggio da 10 a 120 px dà lo stesso risultato
(DRITTO, rms 1.16), e pilotando il gesto vero: mano a ±4 px → dritto 130 px;
±12 px → dritto 128 px; ±22 px → trovato come curva. Una fascia larga adesso
aiuta invece di far danno.

## IL BORDO SFOCATO (2026-08-07) — era la BANDA, non il contrasto

Vincenzo, con una foto: un bordo in silhouette del collare, ovvio all'occhio, che
il cammino non prendeva. «C'e' un mix di sfocatura e rotondita' dello spigolo che
concorrono credo, ma forse si puo' abbassare qualche soglia?»

Non era una soglia di contrasto. Censimento su quella foto, 186 punti che portano
un salto di luminosita' VERO (>60 livelli su 24 px): quelli rifiutati come
`:flat` avevano **coerenza 0.84** — il tensore vedeva benissimo la direzione — e
gradiente locale 6.6, cioe' proprio sul filo del vecchio pavimento assoluto di
`cross-peak`.

**Era la BANDA di ricerca.** Un bordo sfocato spalma la transizione su dieci o
quindici pixel, quindi una scansione da ±10 ci sta tutta dentro e il massimo del
gradiente cade all'ESTREMO della banda — dove `cross-peak` giustamente lo
rifiuta, perche' un picco al bordo vuol dire che il bordo vero e' fuori e la sua
posizione non e' dicibile.

| banda H | ok | `:flat` | falsi su 454 zone piatte |
|---|---|---|---|
| ±10 (prima) | 41 | **17** | 1 |
| **±16 (ora)** | 39 | **0** | 2 |
| ±22 | 37 | 5 | 2 |
| ±30 | 45 | 3 | 2 |

**Un'ipotesi provata e SCARTATA, che vale la pena non ri-provare**: rendere il
test del picco RELATIVO (prominenza sopra il fondo della scansione) invece che
assoluto. Sembra la cosa giusta — una soglia assoluta penalizza per costruzione
i bordi sfocati — e peggiora a ogni variante (17 → 21 col fondo preso sulla
mediana di tutta la banda, → 26 prendendolo agli estremi). Il motivo e' lo
stesso della diagnosi vera: un bordo sfocato e' una gobba LARGA, e in una banda
che la gobba riempie non resta fondo da cui distinguersi. La prominenza misurava
la gobba contro se stessa.

## VEDERE IL BANCO (2026-08-07)

«Comincia a essere usabile. La difficolta' piu' grande che trovo ora e' interagire
col banco, i segmenti listati li' come faccio a vedere dove sono nelle foto?
Dopo aver creato un piano dovrei farli sparire? In teoria serve una lista dei
piani gia' definiti con una lista dei segmenti/punti che appartengono a loro»
(Vincenzo, 2026-08-07).

Tre domande, un problema solo: il banco era un ELENCO SENZA CORRISPONDENZA. Una
lista di cose numerate i cui numeri non compaiono da nessuna parte sulla cosa non
e' una lista, e' un indovinello.

- **Il numero e' scritto nella foto**, accanto al bordo che nomina
  (`viewport/set-labels!`, le stesse etichette billboard che edit-path usa per i
  suoi mark). Selezionato, cambia colore insieme al bordo.
- **Un bordo speso in un piano non sparisce: si smorza e dice a chi
  appartiene** — nell'elenco («→ piano-1») e nella foto (l'etichetta diventa
  «1 → piano-1»). Non sparisce perche' uno spigolo puo' servire a DUE facce, e
  perche' resta comunque l'evidenza di cio' che e' stato deciso.
- **I piani fatti sono un elenco a se'**, ognuno coi numeri dei bordi di cui e'
  fatto, e il loro nome e' scritto nel mondo sull'origine del piano.

I numeri si spengono col tasto `l` (bottone «Numeri: sì/no»), e serve: sono la
risposta a una domanda che ci si fa TRA una pennellata e l'altra («quale di
questi e' il numero 2?»), mentre DURANTE una stanno in mezzo — sono disegnati
sopra tutto, quindi coprono la foto proprio dove si sta cercando di dipingere
(Vincenzo, 2026-08-07: «con le labels cosi' in evidenza non si riesce a
selezionare ulteriori tratti»). Due modi d'uso, un interruttore.

Attenzione per chi tocca questo: `set-labels!` e' GLOBALE e sostituisce tutto.
Va chiamata solo mentre il gesto e' attivo, o cancella le etichette di un
edit-path-2d aperto — e il palcoscenico ridisegna a ogni cambio di foto, cioe'
esattamente quando un ricalco si sta navigando.

**Direzione dichiarata, non costruita** (Vincenzo, stesso messaggio): «in futuro
si potrebbe pensare di raccogliere questi segmenti in un path-2d editabile (sono
semilavorati di qualcosa che sicuramente servira')». E' la strada giusta e il
brief la prevede gia' in spirito — «i click diventano prodotto». I bordi misurati
su una faccia, proiettati sul piano che quella faccia definisce, SONO un contorno
2D; darlo a `edit-path-2d` chiude il giro fra misurare e modellare. Il pezzo che
manca non e' la geometria (il piano e i bordi ci sono gia') ma l'ordinamento: un
contorno vuole i suoi tratti in sequenza e cuciti agli angoli.

## ACCONTENTARSI DEL PEZZO IN COMUNE (2026-08-07)

Vincenzo, dopo aver creato un piano riuscendoci solo con i tratti dritti: «con le
curve sembra avere piu' difficolta'. In particolare e' difficile ripetere lo
stesso segmento di curva in foto diverse, la parte comune sara' sicuramente solo
un pezzo, dovrebbe accontentarsi».

Aveva ragione, e la misura ha detto anche PERCHE' la vecchia soglia era sbagliata.
Censimento sui suoi dati, due tratti del bordo del piatto dichiarati in posti
diversi sulle due foto:

- un accoppiamento VERO passa a **0.23 mm**, uno falso a **32 mm**. Un fattore
  sessanta. La distanza e' gia' un giudice quasi perfetto;
- degli accoppiamenti tenuti sotto 0.5 mm, praticamente tutti sono veri;
- ma sono POCHI: 3 su 40 raggi. La parte in comune era il 7%.

Verificata e scartata l'ipotesi che fosse granularita' di campionamento
(infittire la seconda curva da 40 a 567 punti non cambia nulla: sempre 3). I due
tratti sono davvero quasi disgiunti.

Quindi il pericolo non e' la contaminazione ma la SCARSITA', e la soglia sulla
frazione (75%) stava buttando via roba buona per un pericolo che non c'e'. Due
cambiamenti:

1. la frazione scende a 0.6 — sopra il 51% che il caso produrrebbe, sotto quel
   che una corrispondenza vera raggiunge;
2. e soprattutto: **una curva sul banco non deve pinzare un piano da sola**. E'
   evidenza. Bastano 3 punti in comune per tenerla; se ce ne sia abbastanza lo
   decide dopo `min-width-mm` sui punti di TUTTI i bordi messi insieme. E' per
   questo che il banco esiste.

Misurato con una mano volutamente imprecisa (dichiarazioni a posti diversi sulle
due foto): 3 punti in comune / ordine 100% → tenuta; 18 punti / 64% → tenuta;
13 punti / 33% → RIFIUTATA, e giustamente. Piano dai due pezzi: **1.1° dalla
verticale, quota 0.87 mm**.

## IL PENNARELLO (2026-08-07) — «cerca la linea in questa zona»

Vincenzo, con una foto a riprova: «la cattura della linea ha preso troppo:
insegue tratti non complanari. Non è che può essere utile una sorta di pennarello
a punta spessa, con cui l'utente dice: la linea cercala in questa zona?»

Sì, e coglie il difetto alla radice. Il cammino si ferma quando **muore il
contrasto** — ma un bordo vero non muore a un angolo, si trasforma in un altro
bordo, e il cammino lo segue girando su una faccia che sta su un altro piano.
QUALE bordo si intende è conoscenza che il programma non ha e l'utente sì. Quindi
l'utente dipinge una fascia e il cammino ci resta dentro.

- **Trascinare** in posa dipinge la zona (il tasto sinistro era libero: la
  panoramica è sul destro). La pennellata resta disegnata, così anche quando il
  rilevatore non trova niente si vede che il gesto è stato sentito.
- La punta è **spessa in pixel-SCHERMO** e convertita in pixel-foto dalla scala
  che il tratto stesso misura — quindi ha lo stesso spessore a ogni zoom, e
  zoomare è il modo di averla più fine.
- Il seme si cerca **lungo** la pennellata, dal centro in fuori: la fascia è la
  dichiarazione, dove esattamente attaccarsi è affare del programma, e una
  pennellata a mano ha il diritto di essere approssimativa.
- Con una zona la soglia di lunghezza si abbassa (da 60 a 25 px): dipingere corto
  è un atto deliberato — è proprio il modo di tagliare il bordo prima che
  diventi un altro — non un fallimento nel trovare di più.

**Misurato sui dati veri** (collare, foto 0): stesso bordo, click libero →
cammina 692 px, ne tiene 163 dritti, **rifiutato come curvo**; con la pennellata
sul tratto dritto → cammino confinato a 254 px, **accettato**, scarto 1.19 px.
Sul solo collare i casi di cammino che scavalca sono **48**.

Nota sul test sintetico: un angolo NETTO ferma il cammino da solo (il picco
perpendicolare salta e sparisce), quindi lo sconfinamento non si riproduce lì —
si riproduce sulle foto vere, dove gli angoli sono raccordati. Il test sintetico
prova il meccanismo (la fascia limita il cammino a esattamente ciò che è stato
dipinto); l'evidenza dello sconfinamento sono i numeri qui sopra.

## IL BANCO (2026-08-07) — la forma che il gesto ha adesso

Il primo giro della curva→piano è stato **bocciato all'uso**: «Troppo complicato.
Prima di aver cliccato tre volte su una curva non dà segni di vita, al primo
click dice che la curva c'è, ma non si vede nessuna linea, e alla fine non sono
mai riuscito ad andare oltre». Due difetti distinti, e vale la pena separarli:

1. **Un difetto vero**: la dichiarazione non veniva disegnata. Il gesto diceva
   «bordo CURVO trovato» e non mostrava niente, perché i punti comparivano solo
   dopo che la SECONDA foto rendeva possibile il fit. Chi clicca non aveva modo
   di distinguere un click buono da uno cattivo per due foto intere. Ora quello
   che si dichiara si vede subito, alla profondità dell'oggetto sul proprio
   raggio — quindi sulla foto da cui viene sta esattamente sopra i pixel che
   l'hanno prodotto.
2. **Un difetto di disegno**, e la proposta di Vincenzo è migliore: «se
   accumulassimo semplicemente segmenti che restano visualizzati e l'utente può
   selezionare per dire *questi stanno sullo stesso piano*?»

Il gesto è ora un **banco**. Ogni bordo misurato — dritto o curvo — resta lì,
disegnato nel mondo e numerato; `n` lo tiene, il suo numero lo seleziona, `p`
scrive il mark-piano dai selezionati. Invio scrive un bordo dritto come spigolo,
`c` un cerchio.

**Perché è meglio, e non solo più comodo.** Una RETTA si misura intersecando i
piani che le sue righe-immagine spannano: nessun punto viene mai accoppiato con
un altro, quindi non ci sono fantasmi da sopravvivere, nessun ordine da
rispettare, nessun accordo al 62%. È l'evidenza più solida che ci sia qui — ed è
proprio quella che il rilevatore trova meglio (una volta su due al primo click).
**Due rette non parallele su una faccia ne fissano il piano esattamente.** La
curva smette di essere l'unica strada al piano e diventa una delle cose che si
possono mettere sul banco.

E ogni bordo è indipendente: uno che non riesce non porta con sé gli altri, e i
bordi possono venire da COPPIE DI FOTO DIVERSE — il banco non se ne cura.

### Misurato dal vivo (piatto vero, la verità nota migliore)

Il bordo del piatto è UN cerchio, quindi due suoi archi qualsiasi sono
provatamente complanari. Due archi (a 210° e 345°, misurati dalle foto 0 e 7,
accordo 100% e 96%), tenuti sul banco, selezionati, `p`:

**piano a 0.64° dalla verticale e 0.47 mm di quota** — contro i 6.9° della
singola curva, che veniva giustamente rifiutata. Scritto nel sorgente come
`(plane-mark …)` normale, con i suoi punti in `:from`.

## Da una curva, il PIANO (2026-08-06, la correzione di rotta di Vincenzo)

Dopo aver provato i cerchi sui suoi pezzi: «la curva da identificare non è mai un
cerchio, al massimo un segmento. Credo che potremmo usare le linee curve, non
tanto per identificare cerchi, quanto per identificare **piani**. Quelle su cui
sto cliccando sono tutte curve adagiate su un piano.»

Ha ragione su ogni conto, e la geometria è d'accordo:

- un piano ha **3** gradi di libertà dove un cerchio ne ha 6, quindi gli stessi
  punti recuperati lo determinano molto meglio;
- un piano non chiede alla curva di essere niente in particolare. Un cerchio si
  adatta solo a una curva che È un cerchio, e solo se se ne vede abbastanza;
  qualunque curva piana — un arco, il contorno di una modanatura, uno spigolo
  arrotondato — dà un piano;
- e il piano è ciò che il resto di Ridley già sa usare. Esce come un **normale
  mark-piano**, scritto in `:marks` dallo STESSO write-back del gesto a tre
  punti, quindi `(turtle A :at :zona …)`, `edit-path-2d` sulla zona e gli
  agganci di `acquire-union` funzionano intatti.

Quello che sostituisce è il gesto che costa di più: il mark-piano a tre punti,
ciascuno cliccato su due foto, **ciascuno chiedendo di ritrovare lo stesso punto
fisico**. Sei click fallibili, ed è il posto da cui venivano gli "specchiati" del
gate di fusione. Una curva sono due click e non chiede nessuna corrispondenza.

`n` dichiara una SECONDA curva sulla stessa faccia — ed è l'unica cura per il
modo in cui questo fallisce, che non è ovvio guardando la foto: una curva poco
pronunciata dà punti quasi in fila, e una fila di punti sta su INFINITI piani.
Nessun click più preciso rimedia; una seconda curva sì (misurato: larghezza da
3.05 a 56.5 mm, con la normale che resta esatta ma smette di essere fortunata).

### Il fantasma, e i due test sbagliati prima di quello giusto

Un raggio della foto A buca il cono di B due volte, quindi metà degli incroci
sono fantasmi. Con i cerchi bastava il RANSAC; col piano no, e il caso peggiore
è **un piano ordinato, pulito e sbagliato di 88°**. Due test sono stati provati e
tutti e due l'hanno lasciato passare — sono documentati nel codice perché il
prossimo non li ri-provi:

1. **l'elevazione delle camere sopra il piano trovato.** Vera sui dati del piatto
   (là i fantasmi si accumulavano proprio sul piano che contiene i due centri
   ottici, e quel piano si smentisce da solo: visto di taglio, una curva si
   vedrebbe dritta). Inutile in generale: nel caso sintetico il piano fantasma
   stava 47° sotto le camere e passava indisturbato.
2. **se le due foto ricostruiscono la stessa curva su quel piano.** Sembra il
   test definitivo ed è **circolare**: i fantasmi SONO gli incroci dei raggi,
   quindi qualunque piano ci passi fa passare di lì entrambe le ricostruzioni.
   Misurato: 93% di accordo per un piano sbagliato di 88°.

Quello che funziona è l'**ORDINE**. Il cammino restituisce i punti ordinati lungo
la curva, e una corrispondenza vera conserva l'ordine: percorri la curva in un
verso su A e la percorri in un verso su B. Gli incroci casuali no. Non è
circolare, perché è una proprietà dell'ACCOPPIAMENTO e non del piano che ci si
adatta sopra. Misurato: **100% sul caso vero, 51% sul fantasma** — e il 51% non è
casuale, è la lunghezza attesa della sottosequenza monotona più lunga di una
permutazione a caso (~2√n). Soglia al 75%, in mezzo, con margine da entrambe le
parti.

Resta la guardia più vecchia e più affidabile di tutte: **il dischetto disegnato
nel mondo**. Cambiare foto e vedere se resta incollato alla superficie è il test
che l'aritmetica non sa fare.

### Misurato dal vivo (piatto vero, 2 foto)

Dichiarando lo STESSO tratto del bordo in entrambe: accordo **100%**, il piano
cade a **0.59 mm** dal piano vero del piatto — e viene **rifiutato lo stesso**,
perché i punti sono larghi 2 mm contro i 4 richiesti, e infatti la normale è
6.9° fuori. Il gesto lo dice e chiede la seconda curva.

Nota utile per il collaudo, e conferma quantitativa della difficoltà segnalata da
Vincenzo: fra due foto di quella sessione, sondando il bordo del piatto ogni 15°,
c'era **un solo punto in comune** dove entrambe lo vedono come curva.

## Cerchi e archi (2026-08-06, la fetta scelta dopo il gate)

Un bordo curvo non è più un rifiuto: il cammino l'ha GIÀ seguito, e quei punti
sono esattamente quello che il fit del cerchio mangia. Il rifiuto `:curved`
adesso porta con sé i punti, e il gesto passa da solo in modalità CERCHIO — è la
prima dichiarazione a decidere cosa si sta misurando, e dopo le due devono
andare d'accordo, perché uno spigolo dritto e un cerchio sono misure di cose
diverse e una mescolanza è un errore da nominare, non da mediare.

**Perché la matematica è un'altra.** Una riga nell'immagine spanna un PIANO, e
due piani si incontrano in una retta: forma chiusa. Una curva spanna un CONO, e
due coni si incontrano in una quartica — quella scorciatoia non c'è. Al suo posto:
i raggi delle due foto che si sfiorano segnano un punto della curva (le pose sono
già note, quindi la geometria fa da sola l'accoppiamento che nessuno dichiara),
poi piano, poi cerchio nel piano (Kasa, un solo sistema lineare), poi LM sui 6
gradi di libertà contro la riproiezione in PIXEL.

Emesso come `(circle-mark {…})` nello stesso blocco `:edges`: posa col centro e
l'asse più `:radius`, quindi `(turtle A :at :cerchio-1 (extrude (circle r) (f d)))`
alesa o rialza esattamente dove le foto hanno trovato un cerchio.

**Due difetti veri trovati sui dati veri, che i sintetici non avevano visto:**

1. **La soglia di accoppiamento era il triplo di quanto serve.** Misurato sul
   piatto: un accoppiamento GIUSTO passa a 0.15 mm, uno sbagliato a 3.3 mm — un
   fattore venti. Con la soglia a 1.5 mm un pugno di raggi quasi complanari (la
   stessa cattiva parallasse che rende cieco il mezzo giro per una retta)
   contribuiva decine di incroci a testa: nuvola di 329 punti di cui 28 veri, e
   il RANSAC preferiva un cerchio di **raggio 1838 mm** a un bordo da 65. Ora
   0.5 mm e UN solo incrocio per raggio (che è il numero fisicamente giusto:
   ogni punto dichiarato è l'immagine di un punto solo). Nei sintetici la nuvola
   è passata da 41% a 100% pulita, con la stessa precisione.
2. **Un modello illimitato si adatta a qualunque cosa**: un cerchio enorme è
   localmente quasi una retta, quindi infila più inlier di quello vero. Ora un
   candidato non può superare 3× l'estensione della nuvola — una scala letta dai
   dati, senza chiederla a nessuno.
3. **Mancava la guardia sul residuo.** Un cerchio con centro vicino all'oggetto e
   arco ben coperto passava ogni test pur avendo **157 px** di riproiezione,
   cioè pur non spiegando affatto le foto. Ora nessuna figura si scrive sopra gli
   8 px (`max-write-rms-px`, la stessa riga che il palcoscenico già traccia per
   una foto mal registrata), e vale anche per le rette.

**Misurato dal vivo sul piatto vero** (⌀130 nominale, 3 foto, click sul bordo):
**⌀128.4**, asse a **2.4°** dalla verticale, centro a 2 mm dall'asse del
giradischi, riproiezione 5.2 px — e il gesto lo rifiuta lo stesso, perché se ne
era dichiarato solo 73° di giro contro i 120 richiesti. Sotto un terzo di giro un
cerchio piccolo vicino e uno grande lontano spiegano gli stessi pixel.

**Avvertenza per chi collauda**: il piatto ha cerchi CONCENTRICI (bordo della
carta, bordo sopra e sotto del piatto), e seguirne uno diverso su foto diverse dà
subito residui enormi — misurato, 85 px. È il caso in cui l'anello arancione
disegnato nel mondo serve davvero: cambia foto e guarda se cade sullo stesso
bordo.

## La cosa non ovvia, da non ri-scoprire

`:angle-deg` è il giro che le due camere fanno **INTORNO** allo spigolo,
ripiegato in [0°, 90°]. Conseguenze:

- spostarsi **lungo** lo spigolo non aggiunge niente, per quanto ci si allontani;
- **mezzo giro è cieco quanto stare fermi**: a 180° le due camere e lo spigolo
  tornano complanari, i due piani coincidono, e la retta può scivolare dentro
  quel piano continuando a proiettarsi esattamente sulle due righe disegnate.

Asserito in `edge_test/the-angle-is-the-turn-around-the-edge`, non solo scritto
in un docstring — la prima stesura del docstring diceva l'opposto ed è stato il
test a smentirla.

## Cosa è stato verificato, e come

**Sintetico** (`edge_test`, 10 test): tratti DIVERSI su foto diverse danno la
stessa retta (la proprietà su cui poggia tutto il progetto); 2px di rumore di
mano → 0.23 mm medi; la coppia degenere riporta la propria parallasse invece di
rispondere con sicurezza; due click sovrapposti sono rifiutati; con 5 foto quella
ruotata di 6° viene nominata; il verso è quello del primo tratto disegnato.

**Dal vivo** (browser + sessione vera `param-plate-paper`, 10 foto, camere
registrate, focale rifinita 50.07 mm):

- da 3 pose VERE, una retta nota torna esatta a **1e-14 mm** (rms 2.6e-13 px);
- i capi cadono sull'unione dei tratti dichiarati a **1e-14 mm** (attenzione: le
  frazioni lungo l'IMMAGINE non sono le frazioni lungo il 3D — la prospettiva le
  separa di ~1.2 mm su 100, ed è corretto così);
- `accept-edge!` scrive nel sorgente, il ri-eval restituisce `:edges`, il
  palcoscenico li ridisegna dal sorgente;
- lo snap aggancia il contrasto vero (28 stazioni su 40 su un bordo reale) e
  rifiuta un tratto che non sta su nessun bordo, dicendolo.

## Gate umano — PASSATO sul click singolo (Vincenzo, 2026-08-06)

> «Direi una volta su due devo dare due click, va abbastanza bene.»

Cioè: su un oggetto vero, il click singolo risponde circa metà delle volte, e il
tasso è giudicato accettabile. Vale la pena dire perché quel 50% costa meno di
quanto sembri: **un rifiuto costa UN click in più, non due**, perché il click
che ha fallito resta e diventa il primo capo del tratto a mano. Il costo medio è
quindi ~1.5 click per foto, contro i 2 fissi della prima fetta e contro il gesto
del mark-piano, dove il costo vero non erano i click ma il dover RITROVARE lo
stesso punto fisico.

NON ancora detto dal gate, e da chiedere alla prossima occasione: **quale**
rifiuto domina. Se è `:curved`, la fetta degli archi non è un'aggiunta ma la
metà mancante di questo gesto (e l'oggetto di prova — un collare — è fatto
soprattutto di archi). Se fosse `:ambiguous`, la cura sarebbe diversa e a portata
di mano: ritentare con una finestra del tensore più piccola prima di arrendersi,
visto che un angolo confonde una finestra da 25 px e spesso non una da 11.

Restano da confermare dal vivo (non ancora riferiti):

1. Che il segmento arancione resta incollato allo spigolo cambiando foto.
2. Che `(turtle A :at :spigolo-1 …)` estrude dove serve.

Nota per chi collauda: la scansione alla cieca NON è il gate. Un utente clicca
SU un bordo; la scansione campiona a caso e quindi misura soprattutto quanto il
rilevatore si trattiene dall'inventare bordi dove non ce ne sono (sulla foto
intera: 346 `:flat` su 450 campioni, che è la risposta giusta per carta e
tappetino sgombri).

## Limiti dichiarati (fetta 1)

- **I tratti non sopravvivono alla chiusura.** Vivono in `(:edge @stage)`; nel
  sorgente finisce solo il risultato. Aggiungere una foto a uno spigolo già
  accettato oggi significa rifarlo. È la **fetta 2**: il magazzino delle
  osservazioni, indicizzato per `(sessione, foto)` — che è anche ciò che serve
  al solutore congiunto del gradino 2.
- **Le pose sono congelate al momento del click** (stessa cosa che fa il gesto
  Piano): dopo una rifinitura `R` uno spigolo misurato prima resta indietro di
  quanto si sono mosse le camere. Rifinire PRIMA di misurare.
- **Rette e cerchi**, non archi generici: un arco parziale si misura (il fit non
  chiede il giro completo), ma sotto 120° il raggio non è una misura e viene
  detto. La curva libera resta fuori perimetro, come da brief.
- **Il cerchio non ha via di scampo a mano**: per una retta due click bastano
  sempre, per un cerchio no (un segmento non dice niente di un arco), quindi se
  il cammino non riesce a seguire il bordo curvo lì non si misura.
- **Nessun editor** `edit-edge-mark` ancora: la coppia `edit-X ⇄ X` esiste solo
  per i mark-piano.
- **Capitolo 19 del manuale intonso**, come da nota di metodo del brief: la
  procedura va riscritta quando il canale si è assestato, non a ogni fetta.
