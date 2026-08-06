# Brief: acquire guidata dalle osservazioni (visione v2 del canale)

Aperto 2026-08-02 (Vincenzo + Claude-docs). Stato: VISIONE/PROPOSTA — non un
task. Fissa la direzione in cui i lavori già decisi (focale condivisa +
raffinamento congiunto, assegnabile subito) si incastrano dichiaratamente.
Contesto a monte: gate della fusione PASSATO (anello A→B→C chiude a 0.13 mm,
vedi brief-session-fusion.md), ma "l'esperienza è faticosa … non credo che
riuscirei a indurre qualcun altro a ripeterla" (Vincenzo). Questo brief è la
risposta strutturale a quella fatica.

## L'idea (Vincenzo, 2026-08-02)

Oggi il canale ha quattro macchine separate (PnP per foto, anello, mark-piano,
acquire-union), ognuna col suo gesto e il suo momento. La proposta: UNA
macchina. L'utente mette le foto nelle directory (tutte le pose dell'oggetto),
scrive UNA `edit-acquire`, e poi DICHIARA OSSERVAZIONI cliccando:

- "questo è il dischetto 3 del piatto" — cliccato su più foto;
- "questo è lo stesso edge fisico" (dritto o curvo) — cliccato su più foto;
- il programma SEGNALA quali foto hanno bisogno di dati in più.

Un unico solutore stima tutto insieme: pose di tutte le camere, focale per
sessione, punti, edge, trasformazioni tra gruppi di rigidità. Ogni click
entra in un pool globale di osservazioni invece che nel silo della sua foto.

## Perché attacca la fatica (i tre punti forti)

1. **Gli edge non richiedono identità di punto.** Per dichiarare "stesso
   spigolo" NON serve ritrovare lo stesso punto fisico da un'altra
   angolazione: un punto QUALSIASI lungo l'edge in ciascuna foto è
   un'osservazione valida (una retta 3D ha 4 DOF; ogni proiezione osservata
   dà 2 vincoli). Sparisce il gesto più fallibile del flusso attuale — è lì
   che nascevano gli "specchiati" del gate di fusione.
2. **Sulla plastica nera lucida gli edge sono dove c'è ancora segnale.**
   L'obiezione al feature matching (texture) resta valida; ma un edge è
   CONTRASTO, non texture. Snap-all'edge locale attorno al click (gemello del
   blob-snap, sul gradiente) → raffinamento sub-pixel proprio dove il
   matching fotometrico è cieco. La filosofia non cambia: l'identità la
   dichiara l'utente, la precisione la raffina il programma.
3. **I click diventano prodotto.** Un edge dichiarato e triangolato È un
   segmento/arco 3D dell'oggetto: è già ricalco. Registrazione e modellazione
   smettono di essere fasi separate — ogni click vincola le camere E
   costruisce geometria emettibile (path/curve Ridley).

## Sottigliezze di design (da non perdere)

- **I gruppi di rigidità sostituiscono le sessioni.** Se tra gruppi di foto
  l'oggetto è stato girato sul piatto, il solutore deve sapere quali foto
  condividono la relazione oggetto↔piatto: le DIRECTORY lo dichiarano già
  naturalmente. La trasformazione di fusione diventa un'incognita stimata
  INSIEME al resto: `acquire-union` come caso particolare del solutore, non
  più passo a parte.
- **Edge curvi: partire dai parametrici.** Una curva generica ha infiniti
  DOF; gli edge dei pezzi reali sono quasi sempre rette, cerchi, archi →
  si parte da lì. La curva libera è fuori perimetro (semmai spline con pochi
  controlli, molto dopo).
- **La corona resta l'àncora assoluta** (scala mm + frame): i dischetti
  dichiarati (o auto-rilevati: la fetta C diventa un RIEMPITORE automatico di
  osservazioni, non una macchina a parte) ancorano il tutto; gli edge
  vincolano l'oggetto e le camere tra loro.
- **"Quali foto hanno bisogno di dati" è quasi gratis**: il solutore
  congiunto conosce il condizionamento delle proprie equazioni per camera →
  può dire PER NOME quali foto sono sotto-vincolate, invece di lasciarlo
  scoprire come geometria storta tre passi dopo.

## La scala (ordine dei lavori)

1. **ORA (assegnabile, già proposto da Code)**: focale UNICA per sessione
   fittata dai dati + raffinamento congiunto di tutte le pose contro tutte le
   osservazioni dei dischetti (LM esistente; 120 misure per 31 incognite
   invece di 24 per 6). È la PIETRA DI FONDAZIONE: il solutore che oggi
   mangia solo osservazioni-dischetto è lo stesso che domani mangia punti ed
   edge. Attacca subito i 2-3 mm di posa per foto misurati al gate.
2. Punti dichiarati nel pool congiunto (i mark attuali diventano osservazioni
   del solutore globale invece che triangolazioni locali).
3. Edge dritti dichiarati (+ snap al gradiente) → vincoli camera + segmenti
   3D emettibili.
4. Archi/cerchi. Poi la segnalazione di copertura per foto.
5. Gruppi di rigidità = fusione dentro il solutore (acquire-union assorbita).

## Fuori perimetro (dichiarato)

- Feature matching automatico (resta la riserva texture; gli edge dichiarati
  lo aggirano invece di richiederlo).
- Curve libere non parametriche.
- Cambiare i gesti già collaudati finché il solutore congiunto non è a
  regime: mark-piano e acquire-union restano il flusso di produzione.

## Nota di metodo

Il capitolo 19 della guida va riscritto DOPO questa esperienza e attorno agli
errori tipici (quale mark si sbaglia, cosa vuol dire "specchiato", perché
serve un dettaglio asimmetrico), non come sequenza di gesti — concordato con
Code. Se la visione v2 procede, la procedura da documentare cambia di nuovo:
non investire nella riscrittura fine della sezione registrazione finché il
punto 1 della scala non è a regime.
