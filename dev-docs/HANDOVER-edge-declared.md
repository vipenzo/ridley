# Handover: spigoli dichiarati (gradino 3, fetta 1)

Aperto 2026-08-06. Stato: **COSTRUITO, suite verde (869 test / 3412 asserzioni),
verificato dal vivo sulla sessione vera. GATE UMANO DA FARE** (Vincenzo deve
disegnare uno spigolo VERO di un oggetto VERO). Non committato.

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
- **Solo rette.** Archi e cerchi sono il passo dopo (il brief dice: partire dai
  parametrici, la curva libera è fuori perimetro).
- **Nessun editor** `edit-edge-mark` ancora: la coppia `edit-X ⇄ X` esiste solo
  per i mark-piano.
- **Capitolo 19 del manuale intonso**, come da nota di metodo del brief: la
  procedura va riscritta quando il canale si è assestato, non a ogni fetta.
