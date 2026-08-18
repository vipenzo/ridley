# Brief: l'anello di registrazione — l'ultimo esperimento prima dell'archivio

Aperto 2026-08-17 (Vincenzo + Claude-docs). Stato: PROPOSTA con gate
spietato. Nasce dal primo caso reale fallito. RILANCIATO stesso giorno: si
parte direttamente dalla GABBIA A TRE ANELLI (vedi sezione "Rilancio").

## Il verdetto del caso reale (2026-08-17, da rispettare)

Pezzo di elettrodomestico rotto: Vincenzo ha RINUNCIATO all'acquire e l'ha
rifatto con `edit-image-board`, senza problemi. Diagnosi sua, esatta:
"le tante foto obbediscono a criteri boilerplate: servono a far star su il
meccanismo, non a disegnare l'oggetto". Molto lavoro, poco rendimento.
Inclinazione dichiarata: tenere l'acquire come esperimento semiriuscito.

## La verità di prodotto emersa

Il concorrente dell'acquire non è "niente": è **image-board + calibro**. Per
pezzi piatti o con piano dominante, una foto nelle condizioni migliori vince,
meritatamente. L'acquire paga solo quando la PROFONDITÀ è il problema. Il
protocollo piatto+giradischi ottimizza la registrazione e penalizza proprio
le foto che servono al disegno (frontali, vicine, mirate).

## L'idea base (Vincenzo): il riferimento viaggia con l'oggetto

Corona di mark ancorata rigidamente al pezzo, invece del pezzo appoggiato al
riferimento. Conseguenze in cascata: niente giradischi/θ/NOTE.md; niente
sessioni multiple né fusione (il gruppo di rigidità è oggetto+riferimento,
comunque lo si giri); ogni foto è BEN MIRATA per definizione e si
auto-registra; i mark stanno alla quota dell'oggetto (niente braccio di leva
dell'errore di posa nella zona di ricalco). Il boilerplate non è ridotto: è
eliminato, perché serviva a un vincolo (rigidità oggetto-piatto via
giradischi) che l'ancoraggio soddisfa fisicamente.

## Rilancio (Vincenzo, 2026-08-17): la GABBIA a tre anelli ortogonali

Un anello singolo planare resta degenere visto di taglio (viste utili in un
cono). La cura: TRE anelli ortogonali, pezzo al centro.

**Geometria (il perché funziona)**: per qualunque direzione di vista v,
max|v·asse_i| ≥ 1/√3 → almeno un anello è visto a ≥~35° dal suo piano, mai
degenere (35° è un'obliquità che il fit ellisse già digerisce). E da molte
angolazioni si vedono DUE anelli → dischetti NON complanari → **torna in
servizio il DLT non complanare** (costruito per il box, demansionato col
piatto): posa condizionata su tutti i 6 DOF, senza il braccio di leva del
piano singolo. La gabbia è un vincolo di posa MIGLIORE di quanto il piatto
sia mai stato.

**Il secondo regalo**: la gabbia viaggia col pezzo TUTTA INTERA → la giri,
la capovolgi, ogni foto resta nello stesso frame. Sfera completa delle viste
in UNA sessione, senza ri-ancoraggi né fusione. Unica zona cieca: il punto
di ancoraggio del pezzo.

**Identità degli anelli**: proposta di Vincenzo, mark di FORMA diversa per
anello (cerchi / quadrati / triangoli). Riserva da verificare: a 15-40px un
quadrato sfocato somiglia a un cerchio → valutare in alternativa/aggiunta la
codifica per COLORE (si stampa già in due colori; tre è un passo breve, e il
colore regge meglio la distanza). Ogni anello vuole comunque il suo
zero-indice (o disambiguazione incrociata quando si vedono due anelli — da
accertare quale basta).

**Costi onesti**:

1. **Occlusione**: fino a tre bande attraversano la vista. Mitigazioni: la
   mira è libera (si scatta in modo che le bande evitino il dettaglio);
   anelli sottili e ⌀ largo rispetto al pezzo; il palcoscenico CONOSCE la
   geometria della gabbia → può disegnare in anteprima dove cadranno le
   bande.
2. **Assemblaggio noto per costruzione**: tre anelli a incastro con giunti
   di registrazione, rigidi. Ma è un ATTREZZO RIUSABILE: capitale una
   tantum, non preparazione per-pezzo — differenza sostanziale col
   boilerplate precedente. Ancoraggio del pezzo: piedistallo/morsetto al
   centro (da disegnare; occlude solo la zona di contatto).
3. **Detector (l'unico costo software vero)**: archi di PIÙ anelli nello
   stesso fotogramma → multi-ellisse (estensione del RANSAC conico
   esistente) + classificazione forma/colore per attribuire ogni blob al suo
   anello + tolleranza agli archi parziali (min-crown per anello da
   abbassare con guardie sul condizionamento).

**Perché partire direttamente dalla gabbia** (risposta alla domanda "non
vale la pena?"): il fallimento previsto dell'anello singolo è esattamente la
degenerazione delle viste — un esperimento di cui si conosce già il modo di
fallire è cattiva spesa. L'anello singolo resta dentro la gabbia come caso
particolare gratuito. Il file `param-acq-plate.clj` resta l'unica fonte:
`registration-cage` genera i tre anelli E la mappa :anchors 3D (i mark hanno
già pose piene: nulla di nuovo nella rappresentazione).

## Il gate (spietato, decide l'archivio — aggiornato per la gabbia)

Stesso pezzo di elettrodomestico del fallimento, ancorato nella gabbia.
CINQUE foto mirate come per l'image-board, PIÙ UNA dal retro (la promessa
nuova). Confronto diretto col risultato image-board già fatto:

1. preparazione (ancoraggio incluso) sotto i 10 minuti?
2. le foto si registrano da sole (Auto), compresa quella dal retro?
3. il ricalco batte l'image-board in fedeltà, o la eguaglia con la
   profondità in più?
4. le bande di occlusione hanno impedito qualche vista che serviva?

Se NO alle prime tre → l'acquire si archivia con la coscienza pulita (gli
asset — solutore, bordi dichiarati, gesti dal sorgente — restano al
progetto). Se SÌ → la forma giusta del canale è trovata: **image-board
registrate** — poche foto buone che sanno dove stanno l'una rispetto
all'altra — e il cap. 19 si scrive attorno a QUESTO flusso.

## COSTRUITO (2026-08-17): fetta 0 — la gabbia esiste, il gate no

Deciso con Vincenzo, e in un ordine diverso da quello che il brief dava per
scontato: **il detector multi-ellisse NON si costruisce prima del gate.** Delle
quattro domande del gate, la 1 (preparazione), la 3 (fedeltà) e la 4
(occlusione) si rispondono cliccando i mark a mano, e la 2 va letta come due
domande — «si registra?» (dirimente, manuale) e «si registra *da sola*?»
(ergonomia, rinviabile). Se la gabbia cliccata a mano non batte l'image-board si
archivia senza aver scritto una riga di multi-ellisse.

**Il disegno è cambiato due volte, e la seconda per un errore mio.** Gli
incastri sono caduti subito: una fessura che attraversa un *anulus* in larghezza
lo taglia in due, e la corona si interrompe dove serve continua. La via d'uscita
— tre anelli di diametro DIVERSO, che come *cerchi* non si toccano mai — era
però sbagliata come l'avevo enunciata: vale per le curve, non per gli anuli, che
hanno larghezza. Il vincolo vero è che le **fasce radiali** siano disgiunte. Da
qui i numeri finali, più grandi di quelli promessi a voce:

- **⌀112 / ⌀144 / ⌀176**, fascia 12mm, gioco 4mm, spessore 3mm, apertura ⌀88;
- corona a 2.5mm dal bordo esterno, zero-indice 6mm più dentro, dischetti ⌀2.5,
  12 mark per faccia — tutto in FRAZIONI di `:d`, come il piatto, così una
  gabbia di qualunque taglia inquadrata a pieno campo presenta al detector la
  stessa geometria in pixel. Non scala solo lo spessore;
- **sei linguette incollate**, non incastrate: la linguetta appartiene
  all'anello più piccolo della coppia, sta nel suo piano (che è generato
  dall'asse condiviso e dalla normale del partner — per questo resta piatta e
  stampabile senza supporti), scavalca lateralmente i 3mm del partner e si ferma
  **a filo col suo bordo esterno**. Quel «a filo» è tutta la dima: si vede a
  occhio, non c'è niente da misurare. Quattro sull'anello piccolo, due sul
  medio, zero sul grande.

**Mark su ENTRAMBE le facce, e non è una rifinitura.** Con tre anelli marcati
solo su +X/+Y/+Z una camera nell'ottante (−,−,−) non vede *un solo mark*: la
zona cieca che la gabbia doveva abolire torna intera. Costa due cambi colore.

**L'identità degli anelli è gratis.** Il rapporto fra due ellissi riprese
insieme è invariante alla distanza, quindi gli anelli si distinguono per
DIMENSIONE: niente colori diversi, niente forme diverse, un solo tipo di
dischetto. La proposta di codificarli per forma/colore decade.

### La famiglia degenere, trovata dai test e non prevista dal brief

Sparando **esattamente lungo uno dei tre assi**, i due anelli che contengono
quell'asse sono di taglio e i loro mark stanno oltre 3mm di plastica: si vede un
anello solo, cioè un piatto, gemello specchiato incluso. Dieci gradi fuori asse
e gli altri tornano (misurato: 12 mark su 1 anello sull'asse, 36 su 3 anelli a
10°). Non è un difetto da correggere, è una nota d'uso — ma va **guardata**,
perché è l'unico modo in cui la gabbia può dare una posa sbagliata senza
sembrarlo.

`edit-acquire` ora la guarda: `bridge/camera-sees-marks?` generalizza da un
piatto (una faccia, una normale) a un proxy con sei facce, e una soluzione che
mette la camera DIETRO un dischetto che l'utente dice di aver cliccato viene
prima ritentata dall'allineamento a schermo e poi rifiutata per nome. Il ramo
del piatto non è stato toccato.

### Cosa c'è, e dove

- `photogrammetry/cage.cljs` → `registration-cage` (binding SCI), 78 anchor,
  `:tabs`, `:aperture`, `:anchor-culling?`;
- `bridge`: `index-anchor?` (gli zero-indici non sono bersagli cliccabili),
  `:normal` sui target, culling per-mark dichiarato dal proxy, `camera-sees-marks?`;
- `public/builtin-libraries/acquire-cage.clj` → `save-3mf`, `save-cradle`,
  `measured` (una lettura di calibro sola, non due: tre anelli dalla stessa
  macchina hanno un fattore solo, e se non l'avessero uscirebbero ovali);
- manuale: `docs/manual/reference/en/registration-cage.md`;
- test: `cage_test.cljs` — fasce disgiunte, sei corone, linguette (raggiungono
  il partner, non lo compenetrano, mancano il terzo anello, non collidono fra
  loro), culling, e la posa recuperata a <0.5mm da cinque direzioni fra cui una
  da sotto.

Verificato dal vivo in Chrome con Manifold vero: i sei pezzi da stampare escono
in ~950ms con gli ingombri giusti. **Non** verificato visivamente: il render
headless non parte nemmeno per un `(box)`, ed è il banco di prova.

### Cosa manca

Il gate. Cioè: stampare, montare la testina inkjet (3×5×6 cm), scattare, e
cliccare. Poi, e solo se passa, il detector automatico.

## Nota per il manuale (vale in entrambi gli esiti)

La guida deve guidare con onestà: per pezzi piatti la strada maestra è
`edit-image-board` (+ calibro); l'acquire è lo strumento per quando la
profondità è il problema. Qualunque sia l'esito del gate, il cap. 19 va
impostato su questa gerarchia, non sull'acquire come default.
