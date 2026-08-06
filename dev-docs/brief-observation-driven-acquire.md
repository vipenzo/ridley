# Brief: acquire guidata dalle osservazioni (visione v2 del canale)

Aperto 2026-08-02 (Vincenzo + Claude-docs). Stato: VISIONE/PROPOSTA — non un
task. Fissa la direzione in cui i lavori già decisi (focale condivisa +
raffinamento congiunto, assegnabile subito) si incastrano dichiaratamente.
Contesto a monte: gate della fusione PASSATO (anello A→B→C chiude a 0.13 mm,
vedi brief-session-fusion.md), ma "l'esperienza è faticosa … non credo che
riuscirei a indurre qualcun altro a ripeterla" (Vincenzo). Questo brief è la
risposta strutturale a quella fatica.

> **GRADINO 1 GIÀ FATTO, e ha pagato** (rilasciato in v3.6.0): `photogrammetry/bundle`
> — una focale per sessione + tutte le pose insieme, tasto `R`. Ha trovato che la
> focale EXIF era sbagliata del 4.5%, che è la spiegazione dei 2-3 mm.
> Vedi il seguito in `brief-session-fusion.md`.
>
> **GRADINO 3 (edge dichiarati), FETTA 1 COSTRUITA 2026-08-06 — gate umano DA FARE.**
> Scelta di Vincenzo: partire dagli edge invece che dai punti nel pool, perché è
> lì che sta la fatica. Fatto:
>
> - **`photogrammetry/edge`** (puro, 10 test, 40 asserzioni): la retta 3D che
>   spiega lo stesso spigolo dichiarato su più foto. Ogni foto contribuisce il
>   PIANO che la sua riga d'immagine spanna col centro ottico; due piani non
>   paralleli si incontrano in una retta sola, in forma chiusa, e le altre foto
>   la sovradeterminano (seme chiuso + LM sui 4 DOF, stile di casa). Riporta
>   riproiezione in px, `:exact?` con due sole foto, e con ≥4 foto NOMINA quella
>   disegnata male (leave-one-out; con tre non si può — tolta una, quel che resta
>   è esatto e chiunque sembrerebbe colpevole).
> - **Gesto "Spigolo"** sul palcoscenico, gemello di "Piano": **UN click per
>   foto** (vedi sotto), o due se l'immagine non sa rispondere; mai gli stessi
>   punti delle altre foto. Segmento arancione disegnato NEL MONDO — quindi
>   `[`/`]` è la verifica, gratis.
> - **UN CLICK BASTA** (chiesto da Vincenzo subito dopo la prima fetta). La
>   direzione può venire da due posti e solo uno funziona: dalla geometria già
>   nota no (un click sulla seconda foto vincola la retta di 1 DOF su 4, il
>   secondo servirebbe comunque), **dall'immagine sì** — il tensore di struttura
>   attorno al click dà la direzione E quanto quel punto è un bordo dritto
>   invece che un angolo. Poi il programma CAMMINA lungo il bordo finché il
>   contrasto c'è, quindi trova anche l'estensione, e più lunga di quanta se ne
>   traccia a mano (972 px contro 120). Il pezzo che decide: i bordi veri
>   finiscono in curve, quindi si tiene il **tratto dritto attorno al click** —
>   senza, sul collare nero di Vincenzo un click alla cieca riusciva 1 volta su
>   1442; con, 33. Rifiuta in quattro modi nominati (`:flat`, `:ambiguous`,
>   `:short`, `:curved`) e in tutti chiede i due click, che restano.
> - **`:edges` nel valore di `acquire`**, scritto come `(edge-mark {…})`. È una
>   POSA che corre LUNGO lo spigolo, quindi `(turtle A :at :spigolo-1 (extrude
>   (circle 2) (f 79.7)))` posa un raccordo su tutta la sua lunghezza senza DSL
>   nuovo. Tenuto separato da `:marks` di proposito: l'heading di un mark è una
>   normale, quello di uno spigolo una direzione, e `acquire-union` leggerebbe
>   uno spigolo come un piano storto. `acquire-union` li trasporta.
>
> **Quello che il collaudo ha insegnato, e non era ovvio**: la parallasse di uno
> spigolo è il giro che le camere fanno INTORNO a lui, ripiegato in [0°,90°] —
> spostarsi LUNGO lo spigolo non serve a niente per quanto lontano si vada, e
> **mezzo giro è cieco quanto stare fermi** (a 180° le due camere e lo spigolo
> tornano complanari, i due piani coincidono e la retta è libera di scivolarci
> dentro). È scritto come test, non come opinione.
>
> Verificato dal vivo sulla sessione vera `param-plate-paper` (10 foto, camere
> registrate, focale rifinita 50.07): da 3 pose vere una retta nota torna esatta
> a 1e-14 mm, i capi cadono sull'unione dei tratti dichiarati a 1e-14 mm, la
> scrittura nel sorgente e il ri-eval chiudono il giro, e lo snap aggancia il
> contrasto vero (28 stazioni su 40 su un bordo reale).
>
> **CERCHI E ARCHI COSTRUITI (2026-08-06)**, fetta scelta dopo il gate del click
> singolo: un bordo curvo smette di essere un rifiuto e diventa una misura
> (`photogrammetry/circle`, 7 test). La matematica è un'altra — una curva spanna
> un CONO, non un piano — quindi si recuperano prima i punti 3D dai raggi che si
> sfiorano, poi piano + cerchio + LM in pixel. Sui dati veri sono usciti tre
> difetti che i sintetici non vedevano: soglia di accoppiamento tre volte troppo
> larga (giusto 0.15 mm contro sbagliato 3.3), un modello illimitato che si
> adatta a tutto (raggio 1838 mm preferito a 65), e la guardia mancante sul
> residuo (157 px passavano ogni altro test). Misurato sul piatto: ⌀128.4 su 130
> nominale, asse a 2.4°.
>
> **Resta**: il magazzino delle osservazioni (fetta 2 — oggi le dichiarazioni
> vivono nello stato del palcoscenico e non sopravvivono alla chiusura), i punti
> nel pool congiunto (gradino 2), la segnalazione di copertura.
> Entry point: `dev-docs/HANDOVER-edge-declared.md`.

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
