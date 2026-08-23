# HANDOVER — la gabbia di registrazione

Aperto 2026-08-18. **Fetta 0 costruita e committata; il gate umano NON è stato
fatto.** Documento di governo: `dev-docs/brief-registration-ring.md`.

## In una riga

Il riferimento della fotogrammetria smette di stare SOTTO l'oggetto (il piatto)
e gli va INTORNO: tre anelli piatti ortogonali, il pezzo ancorato al centro,
ogni foto ben mirata e auto-registrata. È **l'ultimo esperimento prima
dell'archivio** dell'acquire, e il gate è spietato per scelta.

## Perché esiste

Il 2026-08-17 il primo caso reale è fallito. Vincenzo ha rinunciato all'acquire
su un pezzo di elettrodomestico rotto e l'ha rifatto con `edit-image-board`
senza problemi. La sua diagnosi, che è quella giusta: *«le tante foto
obbediscono a criteri boilerplate: servono a far star su il meccanismo, non a
disegnare l'oggetto»*.

Il concorrente dell'acquire non è "niente": è **image-board + calibro**. Per
pezzi piatti o con un piano dominante, una foto nelle condizioni migliori vince
meritatamente. L'acquire paga solo quando la PROFONDITÀ è il problema — e il
protocollo piatto+giradischi penalizzava proprio le foto che servono al disegno.

## Stato: cosa c'è

Sette commit sul branch `grab-and-register`:

- `053fd22` **fix(export)** — indipendente dalla gabbia. `(export :Grande :3mf)`
  restituiva `nil` senza fare niente; ora `export` rimette insieme le mesh
  registrate come vettore (`register` archivia `Nome/0`, `Nome/1`), il picker
  che rifiuta ripiega sullo scaricamento, e nessun ramo torna più nil in
  silenzio. Più `save-3mf-set-at` (N file, UNA domanda sulla cartella).
- `0779e69` **feat(registration-cage)** — il proxy, le guardie nel bridge e in
  edit-acquire, i test.
- `3db88fd` **feat(acquire-cage)** — la libreria di fabbricazione, il manuale,
  Roadmap e brief.
- `5d05f97` **docs** — questo handover.
- `63112e4` **feat(acquire-cage)** — i file di stampa arrivano già distesi
  (`cage/printable-ring`): due anelli su tre stanno di taglio nelle coordinate
  della gabbia, e ruotarli nello slicer significa poter lasciare indietro i
  dischetti, che sono un altro oggetto.
- `0b4297a` **fix(acquire-cage)** — le quattro battute erano **staccate**
  dall'anello di 0.3mm: sono uscite dalla stampante come pezzi sciolti e sono
  saltate via togliendo l'anello dal piatto. Il gioco serve fra la battuta e
  l'anello da fermare, non fra la battuta e la linguetta che la porta.
- `023d775` **fix(3mf)** — un pezzo a due colori è **un oggetto con due parti**,
  non due oggetti. Da separati, lo slicer metteva i supporti sotto i dischetti
  della faccia superiore (che nel proprio oggetto galleggiano a 2.4mm) e
  segnalava un conflitto di gcode sulle pareti coincidenti delle tasche. Ora le
  mesh che condividono `:export-group` escono come `<components>` + `<part>`,
  la forma che Bambu e Orca si aspettano.

Suite: **949 test, 0 fallimenti**, 123 warning (invariati).

Tre difetti su tre sono stati trovati dal PEZZO STAMPATO, non dal modello, ed è
la lezione operativa di questa fetta: per la fabbricazione il collaudo che conta
è `mesh-components` sul pezzo (deve dare **1 solido**) più l'ingombro misurato,
non la rilettura del codice. Il test geometrico che c'era verificava che le
linguette non SBATTESSERO contro niente — cosa diversa dall'essere attaccate.

### La geometria, e perché è quella

- **⌀112 / ⌀144 / ⌀176**, fascia 12mm, gioco 4mm, spessore 3mm, apertura libera
  ⌀88. Tutto in FRAZIONI di `:d` come il piatto (così una gabbia di qualunque
  taglia, inquadrata a pieno campo, presenta al detector la stessa geometria in
  pixel); non scala solo lo spessore.
- **Tre anelli** perché per ogni direzione `max|v·asse| ≥ 1/√3`: almeno uno è
  visto a ≥35° dal suo piano, e da una direzione generica si vedono tutti e
  tre → mark NON complanari → torna in servizio il DLT, e il gemello specchiato
  dell'omografia planare non si presenta.
- **Diametri diversi** perché gli *anuli* hanno larghezza: due cerchi ortogonali
  di raggio diverso non si toccano mai, ma due anuli collidono se le fasce
  radiali si sovrappongono. Fasce disgiunte → nessun incastro, nessun intaglio,
  niente da forzare.
- **Identità degli anelli gratis dalla dimensione**: il rapporto fra due ellissi
  riprese insieme è invariante alla distanza. Niente colori né forme diverse (la
  proposta iniziale del brief decade).
- **Mark su ENTRAMBE le facce**, obbligatorio: marcandone una sola, una camera
  nell'ottante opposto non vede *un solo mark*.
- **Corone ruotate di mezzo passo** (`cage/crown-phase`): le sei linguette
  corrono lungo gli assi condivisi, e senza quella rotazione sette mark — uno
  dei quali uno zero-indice — cadevano esattamente sotto una linguetta.

### L'unica famiglia degenere, ed è nota

Sparando **esattamente lungo uno dei tre assi**, i due anelli che lo contengono
sono di taglio e i loro mark stanno oltre 3mm di plastica: si vede un anello
solo, cioè un piatto, gemello specchiato incluso. Dieci gradi fuori asse e
tornano tutti (misurato: 12 mark su 1 anello sull'asse, 36 su 3 anelli a 10°).

`bridge/camera-sees-marks?` la sorveglia — generalizza il vincolo fisico «quel
dischetto l'hai fotografato, quindi la camera stava davanti» da una faccia a
sei. In `edit-acquire` una soluzione che mette la camera dietro un dischetto
cliccato viene prima ritentata dall'allineamento a schermo, poi rifiutata per
nome. **Il ramo del piatto non è stato toccato.**

## Come si usa

```clojure
;; guardarla / esportarla
(register Grande (acquire-cage/make-cage-ring 176 :big))   ; :big :medium :small
(register Gabbia (acquire-cage/make-cage-ring 176))        ; :all, il default
(export :Grande :3mf)

;; oppure i tre file in un colpo (una sola domanda sulla cartella)
(acquire-cage/save-3mf 176 "~/Downloads")
(acquire-cage/save-cradle 176 "~/Downloads")

;; la sessione
(edit-acquire "/…/testina" {:proxy (registration-cage :d 176)})
;; dopo il calibro:
(edit-acquire "/…/testina" {:proxy (acquire-cage/measured 176 175.4)})
```

Un anello per file, e dentro **un oggetto con due parti**: da oggetti separati
lo slicer li appoggia sul piatto per conto proprio, mette i supporti sotto i
dischetti della faccia superiore e segnala conflitti sulle pareti coincidenti.
I file escono già distesi in posa di stampa: non c'è niente da ruotare.

## Banco preparato per il gate (2026-08-19)

La gabbia è stampata e **incollata**. Prima di mandare Vincenzo a scattare, tre
inciampi tolti dalla strada — due erano trappole vere, il terzo è la risposta al
punto aperto qui sotto.

**1. Una cartella di foto senza NOTE.md apriva una sessione VUOTA, e ne
persisteva il vuoto.** `ensure-session-json!` ripiegava sulla sessione vuota (che
è giusta per una cartella vuota, il caso della presa dal vivo) e la SCRIVEVA su
disco: alla seconda apertura il codice che avrebbe potuto rimediare non veniva
nemmeno più raggiunto. Ma la gabbia non ha giradischi, quindi non ha angoli,
quindi **non ha NOTE**: adesso una cartella con immagini e senza NOTE dà una
sessione con quelle foto, tutte fuori-anello (θ nil), che è il protocollo della
gabbia e non una tolleranza (`session-json-from-folder`).

**2. Le vie automatiche consigliavano un piatto a chi ha in mano una gabbia.**
Auto (`a`), l'assegnazione batch e Grab (`g`) sono costruite sull'UNICA corona
del piatto più il suo zero-indice; una gabbia ha sei corone e nessun `:zero`, e
tutte e tre rifiutavano — correttamente — con «questo piatto non espone lo
zero-indice: usa `registration-plate`». Ora dicono la verità (`cage-proxy?` +
`no-auto-on-cage-msg`): il rilevamento automatico della gabbia non è ancora
costruito, si registra a mano con `p`. Nessun comportamento cambiato, solo il
messaggio — ma era un messaggio che mandava a rifare il lavoro nel modo
sbagliato.

**3. `:phases` — la rotazione degli anelli, da imporre a MISURARE.** Vedi la
sezione dedicata più sotto, che questa chiude.

- `(registration-cage :d 176 :phases {:x 2.5})` dichiara di quanto ogni anello è
  girato attorno al proprio asse sulla gabbia che hai davvero incollato (`:x` il
  grande, `:z` il piccolo), in gradi, regola della mano destra. La stampa non è
  toccata: `acquire-cage` stampa sempre la gabbia nominale, `:phases` descrive
  quella costruita.
- E soprattutto la MISURA. Dopo ogni solve su gabbia, `cage-phase-report!`
  stampa nella REPL la fase di ciascun anello, **ciascuno misurato contro una
  posa risolta SENZA di lui** (leave-one-ring-out), più un giro di raffinamento.
  Le due cose non sono zelo: misurato contro il fit che lo ha usato, un anello
  girato di 3.0° legge **1.0°** — il fit ruota tutta la gabbia per spartire la
  differenza — e trascina gli altri due a −1.2° ciascuno. Tenuto fuori e
  raffinato: colpevole 3.00°, innocenti 0.00°.
- **La prova è il ±, non la grandezza.** Un anello cliccato male dà stime che
  si sparpagliano quanto la loro media; un anello incollato girato dà lo stesso
  scarto da OGNI suo mark. L'accusa richiede `|deg| > 1.5 × spread`.
- Quanto pesa: 4° non dichiarati = **22px di rms e 15mm di camera**; 1.8° con
  rumore di click realistico (±0.7px) = 9.7px di rms, e la stima legge
  1.80° ±0.04 da soli 5 mark per anello.

Verificato dal vivo nel bundle del browser (non solo "compila"): il percorso
esatto di `on-solve-pnp!` chiamato con osservazioni sintetiche stampa le sette
righe giuste, diametro compreso, senza sollevare eccezioni. Suite **952 test, 0
fallimenti**, 123 warning (invariati).

Nell'ordine di montaggio la pagina del manuale diceva ancora «chiudi col
piccolo per ultimo»: corretta in «piccolo e medio prima, il GRANDE per ultimo»,
che è quella che lascia una sola incognita invece di tre.

## La trappola delle facce, trovata al primo scatto vero (2026-08-19)

Vincenzo ha registrato la prima foto e ha ottenuto **1007px**. I dodici click
erano tutti centrati su dischetti veri — verificato ritagliando la foto attorno a
ciascuno. Erano i NOMI a essere sbagliati, e il colpevole è il programma:

**i pallini che l'editor offre li sceglie dalla posa del PROXY, non dalla foto.**
Col proxy fuori posa offre i mark della faccia sbagliata; e siccome le due facce
di un anello portano gli stessi dischetti agli stessi angoli, non c'è niente sullo
schermo che lo riveli. L'utente clicca i dischetti che vede, con le etichette che
gli vengono date, e non può accorgersi di nulla.

Il punto che rende la cosa insidiosa: **gli anelli sbagliano in modi DIVERSI nella
stessa foto.** Lì l'anello grande era numerato correttamente e il medio era
specchiato (`ym01→yp10`, `ym03→yp08`, `ym10→yp01`, cioè `i → 11−i` più uno
scarto), perché la camera stava da parti opposte dei due — che per una gabbia è la
norma, non sfortuna. Quindi **ribaltare tutte le etichette insieme non ripara
niente**, ed è la ragione per cui il soccorso lavora anello per anello.

`cage-relabel-rescue` (in `edit_acquire.cljs`): quando il solve mette la camera
dietro un dischetto cliccato e nemmeno la ripresa dall'allineamento a schermo
salva, si fida dell'anello con più punti, risolve da quello solo, poi lascia che
ogni anello scelga la propria rilettura fra le 4n di `cage/crown-misreadings` (due
facce × due versi × n scarti), e rifà il fit. Adotta solo se la guardia fisica
passa **sulle etichette nuove** e l'rms scende sotto la metà. I click non si
toccano mai: si rinominano, via `relabel-picks!`.

Misurato sui dati veri di quella foto: **712px → 23px**, dodici dischetti
rinominati, e le correzioni trovate dal solutore coincidono con quelle ricavate a
mano dalla riproiezione.

Il messaggio di rifiuto è stato corretto: diceva «vuol dire che i punti stanno
tutti su UN anello», che in quel caso era **falso** (i punti stavano su tre) e
mandava a rifare una foto che andava benissimo.

Collaudo: le primitive (`mark-parts`, `crown-misreadings`, `relabel`) hanno test
in `cage_test.cljs`, incluso il caso vero `ym01→yp10`; l'orchestrazione è
verificata **dal vivo sui dati della sessione reale**, non da un test unitario —
`edit_acquire` non è caricabile sotto Node.

### Nota di metodo, che è costata un giro

La prima diagnosi di questa sessione era sbagliata: avevo calcolato le intrinseche
trattando la foto come orizzontale (8064×6048) mentre l'EXIF dice **Orientation
6**, cioè verticale (6048×8064). Con l'ottica girata di 90° avevo "escluso" il
verso di rotazione — che era invece uno dei due colpevoli. Il segnale c'era ed era
leggibile: un pick a y=6118 in un'immagine alta 6048. **Prima di escludere una
causa, controllare che l'immagine sia quella che si crede.**

## La faccia si sceglie con la GUARDIA, non col residuo (2026-08-23)

Vincenzo mette i punti dell'anello X sulla prima foto della gabbia nuova, chiede
il fit, e vuole aggiustare gli altri anelli — ma le facce non corrispondono:
«quello che vedo io del ring Y è la faccia p (lo dico perché vedo i mark andare
CCW), mentre lui mi presenta ym0, ym1…, non ho modo di mettere i punti yp0,
yp1». La sua domanda, che è quella giusta: **non nasconde un errore geometrico?
L'algoritmo dovrebbe avere tutti gli elementi per capire che facce sono quelle
rivolte verso di me.**

Ha ragione a metà, ed è la metà che conta. Misurato su dati sintetici, sei
dischetti di una corona fotografati da davanti:

| etichette | rms | guardia fisica |
|---|---|---|
| faccia giusta (`xp`) | 0.00px | passa |
| faccia sbagliata (`xm`) | 0.00px | **fallisce** |

**Il residuo non ha quegli elementi**: i 3mm di plastica fra le due facce se li
mangia la camera spostandosi di 3mm, e i pixel tornano identici. Non è un
difetto del solutore, è una degenerazione vera — nessun raffinamento la toglie.

**La guardia fisica ce li ha**: quei dischetti erano *fotografati*, quindi la
camera stava davanti a ciascuno. Una posa che la mette dietro non è improbabile,
è impossibile. `bridge/camera-sees-marks?` lo sa da sempre.

Quello che mancava è che quando la guardia sparava, il codice **non provava il
ribaltamento di faccia**: `cage-relabel-rescue` esce subito con meno di due
anelli, perché per fidarsi di un anello ne vuole un altro con cui confrontarlo —
e una corona sola è il modo NORMALE di cominciare una foto. Risultato: la
sessione rifiutava invece di provare l'unica cosa che era sbagliata.

Ora il ribaltamento di faccia è la **prima** ipotesi del ramo gabbia di
`solve-and-apply!`, prima della ripresa dal seed e prima del soccorso caro: gli
stessi click, i nomi ribaltati (`mark-parts` → `mark-id` col segno opposto), un
solve, e si adotta solo se la guardia passa. Costa un solve; il soccorso per-anello
ne costa 4n. Verificato dal vivo sulla copia della sessione: sei dischetti `xp`
registrati come `xm` si rinominano da soli, rms 9.91px, e l'aggancio automatico
attacca altri quattro mark. Test: `a-crown-read-from-the-wrong-face-is-caught-by-the-camera-not-the-residual`.

Resta vero, e va detto all'utente: **la faccia non si legge nella foto.** Il
messaggio spiega che i due nomi sono lo stesso dischetto attraverso la plastica.

## Il caso "una corona intera più due punti" (2026-08-20)

Le prime quattro foto della sessione a 48mm sono andate a 11.8 / 11.7 / 11.9 /
10.7px, con **quattro click a mano** ciascuna e il resto trovato dagli agganci
automatici. La quinta ha dato **9736px**, ed è un guasto che merita di stare
scritto perché non assomiglia alla sua causa.

I punti erano 12 sulla corona −X e 2 sull'anello Y. Non è un insieme complanare —
quei due stanno decine di mm fuori dal piano, e `coplanar?` giustamente dice no —
ma **«non complanare» non vuol dire «ha profondità»**: dodici punti su quattordici
non ne portano nessuna, quindi la portano i due, e con essa tutto il loro errore
(erano agganci automatici finiti 24 e 40px fuori posto, con il nome però GIUSTO).

E il colpo di grazia è l'eliminazione degli scarti, che è cieca a ciò che un
punto CONTRIBUISCE: ha buttato il residuo più alto, cioè `ym01` — uno dei due
soli punti fuori piano. Misurato: DLT su tutti e 14 = **12.5px**; sui 12 rimasti
= **7700px**.

`solve-once` ora, sotto `:auto` e solo quando il primo passaggio esce brutto,
riparte dal piano che i più condividono (`dominant-plane-subset` +
`estimate-homography`) e raffina su TUTTI i punti: i punti fuori piano fanno
allora ciò per cui sono buoni — rompere l'ambiguità speculare dell'omografia e
fissare la profondità — invece di doverla condizionare da soli. Tiene la
soluzione migliore delle due, quindi non può peggiorare. Sui dati veri: **8px**,
metodo `:planar-seeded`.

Corretto anche un difetto introdotto scrivendolo: il soccorso scavalcava un
`:method` forzato dal chiamante, e un forzamento che non forza rende impossibile
misurare ciò che si dice di misurare.

### Il test è su dati VERI, e non per pigrizia

La versione sintetica di questa configurazione NON riproduce il guasto: con pixel
puliti il DLT malcondizionato torna 0.00px, e anche mettendo ±30px sui due punti
fuori piano si ferma a 9px. Un test così sarebbe passato anche senza la
correzione. Il fixture è quindi le quattordici coppie reali della foto 5 —
e come **vettore ordinato**, non mappa: con 14 chiavi Clojure passa a hash-map,
l'ordine di `keys` non è quello di scrittura, e su un sistema così malcondizionato
**l'ordine delle righe cambia la soluzione** (7700px in un ordine, 8px in un
altro). Un fixture che non fissa l'ordine non riproduce niente.

## I nomi dei mark sulla foto ('n')

Il gesto che toglie di mezzo tutta la difficoltà del contare: scrive il nome di
ogni mark visibile sulla foto, dove il modello dice che si trova. Legge quel che
legge il solutore, quindi è onesto quando sbaglia — nomi lontani dai dischetti
vogliono dire proxy fuori posa.

Da cui la ricetta breve, che è quella che ha fatto funzionare la sessione:
**quattro click su UN anello solo** (quattro mark complanari determinano
esattamente una posa planare) → `r` → da lì i nomi cadono sui dischetti giusti di
ogni anello e gli agganci automatici fanno il resto. Misurato: 4 click a mano →
25 mark trovati → 11.8px.

## IL GATE — è questo che manca

Al 2026-08-19 i tre anelli sono STAMPATI e Vincenzo li sta incollando. Poi si
ancora la testina e si scatta. Il confronto è col risultato image-board **già
fatto** sullo stesso pezzo di elettrodomestico. Cinque foto mirate + una da
dietro (la promessa nuova).

1. preparazione, ancoraggio incluso, sotto i 10 minuti?
2. **si registra** ogni foto, compresa quella dal retro? (a mano col tasto `p`:
   il detector automatico è DELIBERATAMENTE rinviato, vedi sotto)
3. il ricalco batte l'image-board in fedeltà, o la eguaglia con la profondità in
   più?
4. le bande di occlusione hanno impedito qualche vista che serviva?

**Se NO alle prime tre → l'acquire si archivia**, con gli asset che restano al
progetto. Se SÌ → la forma giusta del canale è *image-board registrate*, e il
cap. 19 del manuale si scrive attorno a quel flusso.

### LA ROTAZIONE DEGLI ANELLI — il punto aperto più importante

Domanda di Vincenzo, 2026-08-18, ed è quella giusta: *«i dischi possono avere
una posizione qualunque uno rispetto agli altri?»*. **No.** Il modello dichiara
dove sta ogni dischetto, e a raggio 85mm un solo grado vale 1.5mm.

Cosa è vincolato e cosa no:

- concentricità e ortogonalità: le impongono linguette e battute;
- la rotazione di anello PICCOLO e MEDIO attorno al proprio asse: la fissano le
  loro stesse linguette, che arrivano sugli altri anelli solo se l'anello è
  girato bene;
- la rotazione dell'anello GRANDE: **libera.** Non ha linguette proprie, è
  tenuto da quelle degli altri due che gli premono contro la faccia, e può
  girare restando appoggiato.

Il controllo visivo esatto: le corone sono ruotate di mezzo passo
(`cage/crown-phase`), quindi **ogni punto di contatto cade esattamente a metà
fra due pallini**, 15° da ciascuno. Un contatto sopra un pallino è un anello
storto.

Resta un'ambiguità di 90°, ed è benigna: con dodici mark ogni 30° una rotazione
di 90° porta i dischetti dove il modello ne aspetta altri, quindi cambia solo
QUALE mark è il numero zero. Lo dice lo zero-indice, e si sistema nel modello.

**Il difetto di progetto, dichiarato**: trovare a occhio il punto a metà fra due
pallini mentre si incolla è difficile — Vincenzo lo ha detto provandoci
(2026-08-19), e ha ragione. Due strade, in ordine di costo:

1. **per il gate**: incollare come viene, puntando al vuoto, e trattare la fase
   di ciascun anello come un numero da MISURARE invece che da imporre. È un solo
   scalare per anello, ed è la filosofia del canale (cfr. `plate-calib`: la
   difficoltà si sposta dal costruire al misurare). **COSTRUITO il 2026-08-19**:
   `:phases` su `registration-cage` per dichiararla, e `cage-phase-report!` che
   la misura da una foto registrata (leave-one-ring-out + raffinamento). Vedi
   §"Banco preparato per il gate". Se al gate i residui di un anello sono
   sistematicamente peggiori degli altri, adesso la REPL lo dice per nome e con
   il numero;
2. **per una v2 stampata**: chiavettare il giunto, che è la proposta di Vincenzo
   («fossette e rilievi»). Il posto giusto NON è la faccia marcata ma il BORDO:
   la battuta già abbraccia il bordo del partner, quindi le si dà un dentino
   verso l'interno e al bordo si fa una tacca corrispondente. Le quattro battute
   esistenti chiavetterebbero l'anello grande (due) e il medio (due), cioè
   esattamente quelli che servono. Da verificare che la tacca non arrivi ai
   dischetti: la corona sta 2.5mm dentro il bordo e il dischetto ha raggio 1.25,
   quindi il margine è ~1.25mm.

### Il montaggio (da fare prima del gate)

Sei giunti incollati con **epossidica**. I quattro dell'anello piccolo hanno la
battuta: si spinge finché non si ferma. I due fra medio e grande no, e non è una
dimenticanza — una battuta ferma qualcosa solo se attraversa il piano
dell'anello, ed è esattamente la direzione da cui l'anello entra: lì avrebbe
bloccato il montaggio invece dell'anello. Quei due si allineano a occhio, punta
della linguetta a filo del bordo esterno.

Ordine consigliato, che riduce il problema della rotazione a una variabile
sola: **prima piccolo + medio**, che hanno la fase già imposta dalle proprie
linguette; poi il pezzo al centro; poi l'anello grande per ultimo, che chiude
la cerniera e a cui resta solo la propria rotazione da centrare sui quattro
contatti.

**Ancoraggio del pezzo**: il vincolo è SOLO la rigidità durante la sessione — la
posizione nella gabbia non viene mai assunta, solo usata come frame. Quindi il
supporto può essere arbitrario, ma non cedevole: elastici no (immagazzinano
energia e spostano il pezzo appena la gravità gira), fili di nylon no (tirano
soltanto, e metà si allentano a ogni capovolgimento). Aste sì — spiedini di
bambù che convergono, o un montante rigido, con pongo adesivo sulla punta. Se
il pezzo si muove **non è silenzioso**: i mark continuano a concordare fra le
viste, i ricalchi no.

## Cosa NON è stato fatto, e perché

- **Il detector automatico (multi-ellisse) è rinviato di proposito.** Tre delle
  quattro domande del gate si rispondono cliccando i mark a mano; se la gabbia
  non batte l'image-board si archivia senza aver scritto una riga di quel
  codice. Quando servirà: `match-plate/crown-ring-hypotheses` restituisce già le
  top-5 ellissi in classifica — quel che va tolto è l'assunto `ring-slack`
  («la corona è l'anello più grosso, il resto è spazzatura»), che con tre anelli
  è falso. L'identità dell'anello si risolve per coerenza incrociata
  (riproiettare gli altri anelli e vedere quale ipotesi li fa cadere su blob
  rilevati) oltre che per dimensione.
- **Calibrazione della gabbia**: `plate-calib` (tasto `C`) è ancora
  piatto-specifico. Sulla gabbia la triangolazione dei mark sarebbe MEGLIO
  condizionata (punti non complanari), e il vincolo di gauge è più semplice —
  un solo fattore di scala, non due. Da generalizzare se il gate passa.
- **Nessuna verifica visiva automatica**: le mesh in Ridley si vedono solo se
  REGISTRATE (`extract-render-data` non mostra le mesh di extrude/loft da sole).
- ~~**La fase per-anello non è né dichiarabile né misurabile**~~ — FATTO il
  2026-08-19, vedi §"Banco preparato per il gate". Resta fuori: la fase si
  misura da UNA foto alla volta e non viene fusa fra le foto, né scritta da
  sola nel sorgente; è l'utente che la dichiara. Con più foto registrate le
  stime vanno confrontate a mano — se concordano, è la gabbia; se no, è una
  foto.
- **La faccia superiore** degli anelli ha i bordi meno netti di quella contro il
  piano di stampa; è inevitabile in una stampata sola. L'upgrade, se servisse:
  ogni anello in due metà da 1.5mm, ciascuna coi mark verso il piano, incollate
  schiena contro schiena.

## Il gesto manuale non chiude, e il perché è misurato (2026-08-23)

Giornata di collaudo sui dati veri di Vincenzo (`~/Pictures/RidleyScan/Presa`,
`IMG_9014`, gabbia nuova ⌀176). Tutto quanto segue è misurato, non dedotto.

**I suoi click sono impeccabili.** Dodici su dodici centrati sui dischetti,
ritrovati dal rilevatore entro 0.7–1.9px. I gruppi (grande=X, medio=Y,
piccolo=Z) sono giusti. Il problema non è mai stato la mano.

**Una corona da sola non può identificarsi.** Dodici mark equidistanti sono
invarianti per rotazione e dall'altra faccia si leggono specchiati: **tutte e
48 le riletture della corona X danno lo stesso rms, 32.5px**. I numeri offerti
sono congetture ricavate da dove sta il proxy.

**Lo zero-indice rompe la simmetria, ed è stato reso cliccabile** (`234fe89`).
Senza: 48 letture su 48 restano entro il doppio della migliore, e la migliore è
sbagliata. Con un click sull'indice: **2**, che differiscono solo per i 3mm fra
le facce — cioè la degenerazione che la guardia fisica scioglie. Sui dati veri
il click su `⊙xm` riproietta a **3px**.

**Due difetti nella ricerca per anello, corretti** (`ea42a63`):
`best-misreading` ordinava le riletture per solo errore di riproiezione, che è
cieco al cambio di faccia (3mm ≈ 19px, sempre in salita) — quindi restituiva
*sistematicamente* la faccia rivolta dall'altra parte. E l'adozione pretendeva
che l'rms si dimezzasse, cosa impossibile quando i click sono giusti e sbagliano
solo i nomi (misurato: soglia richiesta «< 0.000px»). Ora la guardia sceglie
l'insieme dei candidati e l'errore sceglie dentro; l'rms fa solo da
non-regressione.

**E nonostante tutto questo il gesto manuale NON chiude.** Il numero che lo
dice: la posa dai soli punti di un anello (nove punti, rms 5.15px) colloca i
mark degli **altri due anelli a 130–260px** dai dischetti veri — una corona è
complanare, la posa è precisa nel suo piano e vaga fuori. Peggio: aggiungere
UN punto di un secondo anello, anche corretto, porta il fit da 5.15px a
**1008px** (dodici coplanari più uno: il singolo punto fuori piano porta tutta
la profondità e tutto il proprio errore — è la famiglia del guasto già
documentata sopra). Nel frattempo l'utente deve appaiare i nomi ai dischetti a
occhio sopra un disegno fuori di 150px, e tre volte su sei prende un dischetto
dell'anello piccolo credendolo del medio, cosa che vicino agli incroci **non
si può** distinguere. Il circolo non si apre dall'interno.

Da qui il via libera al **riconoscimento automatico**, che era «deliberatamente
rinviato a dopo il gate»: il gate ha risposto.

### Il rilevatore, primo mattone (`c14733a`)

Sulla foto vera: trova TUTTI i dischetti dell'anello grande, NESSUNO dei due
interni. Causa: la finestra della media locale è scelta «≫ un dischetto, ≪ la
distanza fra due», che su un piatto è campo bianco largo; sugli anelli interni
è una banda sottile su fondo scuro, la finestra si riempie di fondo, la media
va a nero. Stringerla li ritrova con 214 candidati per 10 buoni; `bright-around`
(un dischetto è scuro ED è circondato di bianco) taglia a 89 perdendone uno.
`cage-opts` raccoglie le tarature.

Su una foto su **carta bianca** il guasto della media locale sparisce e resta
scoperto quello sotto: a risoluzione ridotta il **bordo** della plastica
somiglia a un dischetto quanto un dischetto (modo piatto: 26 rilevazioni quasi
tutte vere ma poche; modo gabbia: 104 con una fila di falsi sul bordo).

**Prossimo passo, e adesso è preciso**: cercare i dischetti a risoluzione piena
con un criterio di FORMA — correlazione con un modello di disco. Un bordo non
correla con un disco; un dischetto sì, anche schiacciato in ellisse. Poi
l'abbinamento posa+identità sopra i candidati.

## Trappole del banco di prova (costate mezza giornata)

- **Playwright MCP e il browser dell'utente possono essere LO STESSO**, e
  Playwright dirotta gli scaricamenti in `<repo>/.playwright-mcp/`, non in
  `~/Downloads`. Prima di indagare qualunque percorso di salvataggio, guardare
  lì. Il segno rivelatore: i `console.log` dell'agente compaiono nella console
  dell'utente.
- **Non fingere il meccanismo sospetto.** Ho sostituito `showSaveFilePicker` con
  un finto che riusciva sempre, e per tre round ho dichiarato "verificato dal
  vivo" mentre all'utente non arrivava niente. Un mock serve a esercitare il
  RAMO DI FALLIMENTO, non a dichiarare che il percorso funziona.
- `showSaveFilePicker` rifiuta **sempre** se chiamato dalla console devtools: non
  c'è nessun gesto utente dietro. Non è un difetto.
- **Il ricaricamento a caldo salta**, e una build rotta lascia servire il bundle
  vecchio: leggere il log di `npm run dev`, e verificare `fn.toString()` prima
  di credere a un collaudo.
- `box` prende **(destra, su, avanti)**, non (x, y, z): le linguette uscivano
  ruotate di 90°, e a dirlo è stato misurare l'ingombro del pezzo prodotto, non
  rileggere il codice.
- **Due finestre Ridley = niente file, in silenzio (2026-08-23).** Il servizio
  che legge le cartelle è il geo-server Rust dentro il processo Tauri, uno solo,
  su `127.0.0.1:12321` — e serve ANCHE la scheda del browser: `localhost:9000`
  funziona soltanto perché da qualche parte c'è un Ridley desktop aperto. Se ne
  apri un secondo mentre il primo tiene la porta, il `bind` falliva su un thread
  staccato con `.expect(...)`: il thread moriva da solo, la finestra si apriva
  normale, e per tutto il resto della sessione ogni operazione sui file tornava
  vuota. `edit-acquire` annunciava «Empty session: open the camera and grab a
  frame» su una cartella piena di foto, identico a «l'app ha perso le mie foto».
  Diagnosi in un comando: `lsof -nP -iTCP:12321 -sTCP:LISTEN` — niente in ascolto
  con un `ridley-desktop` vivo è esattamente questo. Ora lo dicono entrambe le
  metà: il Rust stampa invece di morire, e `stl/service-unreachable` marca il
  guasto di TRASPORTO (nessuna risposta) distinguendolo da un errore HTTP (una
  richiesta sbagliata), così la sessione lo scrive nel pannello REPL invece di
  inventarsi una cartella vuota. La causa CLJS era un `(.catch (fn [_] #js []))`
  sulla lettura della cartella: silenzio trasformato in «vuota».
- **`shadow-cljs release app` calpesta il watcher**: scrive lo stesso
  `public/js/main.js` che serve `localhost:9000`. Dopo aver costruito il DMG,
  rimettere il bundle di sviluppo con `(shadow.cljs.devtools.api/compile :app)`
  passando dall'nREPL già connesso, e controllare la dimensione del file.
- **Le mesh ruotano attorno al proprio centroide**: ruotare l'anello e i suoi
  dischetti separatamente li disallinea. Per questo `printable-ring` cambia
  frame con `unplace` invece di ruotare le mesh.

## File

- `src/ridley/photogrammetry/cage.cljs` — il proxy: `registration-cage`
  (`:phases`), `ring-radii`, `joint-tabs`, `crown-phase`, `aperture`,
  `turn-about-axis`, `phase-from-residuals` (leave-one-ring-out + raffinamento)
- `src/ridley/photogrammetry/bridge.cljs` — `index-anchor?`,
  `camera-sees-marks?`, culling per-mark dichiarato
- `src/ridley/editor/edit_acquire.cljs` — la guardia nel ramo `p` (cerca
  `per-mark-faces?`), `cage-phase-report!` (il referto della fase dopo il
  solve), `cage-proxy?`/`no-auto-on-cage-msg` (le vie automatiche parlano di
  gabbia), `session-json-from-folder` (foto senza NOTE.md)
- `public/builtin-libraries/acquire-cage.clj` — `make-cage-ring` (posa della
  gabbia), `make-print-ring` (posa di stampa), `files`, `save-3mf`, `cradle`,
  `measured`
- `src/ridley/export/threemf.cljs` — `group-plan`: mesh con lo stesso
  `:export-group` diventano un oggetto multi-parte
- `test/ridley/photogrammetry/cage_test.cljs` — fasce disgiunte, sei corone,
  linguette e battute, nessun mark sotto una linguetta, posa recuperata da
  cinque direzioni, degenerazione sull'asse, la fase (un passo esatto rinomina i
  mark e muove entrambe le facce; un anello storto di 4° e la fase dichiarata
  che lo rimette a posto; il referto che dice QUALE anello e di quanto)
- `docs/manual/reference/en/registration-cage.md`

## Nota per il manuale, valida in entrambi gli esiti del gate

La guida deve guidare con onestà: per pezzi piatti la strada maestra è
`edit-image-board` + calibro; l'acquire è lo strumento per quando la profondità
è il problema. Il cap. 19 va impostato su questa gerarchia, non sull'acquire
come default.
