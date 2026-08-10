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
> **DA UNA CURVA, IL PIANO (2026-08-06)** — correzione di rotta di Vincenzo dopo
> aver provato i cerchi: «la curva da identificare non è mai un cerchio, al
> massimo un segmento … potremmo usare le linee curve per identificare PIANI».
> È il colpo che attacca la fatica alla radice, perché sostituisce il gesto più
> costoso del canale — il mark-piano a tre punti, sei click ognuno dei quali
> chiede di RITROVARE lo stesso punto fisico — con due click e nessuna
> corrispondenza. Esce come un normale `(plane-mark …)` dallo stesso write-back,
> quindi tutto il valle funziona intatto. `photogrammetry/curve`, 8 test.
> Il difetto insidioso: metà degli incroci sono fantasmi, e possono formare un
> piano ordinato e sbagliato di 88°. Due test l'hanno lasciato passare (uno
> inutile in generale, uno CIRCOLARE — i fantasmi sono gli incroci, quindi ogni
> piano che ci passa fa coincidere le ricostruzioni: 93% di accordo per un piano
> sbagliato di 88°). Funziona l'ORDINE del percorso: 100% contro 51%.
>
> **Resta**: il magazzino delle osservazioni (fetta 2 — oggi le dichiarazioni
> vivono nello stato del palcoscenico e non sopravvivono alla chiusura), i punti
> nel pool congiunto (gradino 2), la segnalazione di copertura. E un limite
> misurato: su queste foto il rilevatore trova i bordi CURVI solo a tratti (fra
> due foto, un solo punto in comune sul bordo del piatto sondando ogni 15°) —
> il che rende il gesto a due click meno spesso disponibile di quanto vorrebbe.
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

## Svolta: il gesto si guida dal CODICE (Vincenzo, 2026-08-09)

Dopo sei giri d'uso vero del gradino 3, il verdetto sull'interfaccia:

> «Trovo che il resto, a questo punto, sia piuttosto complesso e confuso.
> Proporrei di togliere stati alla UI e usare di più il codice. Potremmo
> togliere del tutto il banco: lo mettiamo direttamente nel codice, di fatto
> c'è già. Se una nuova linea la facessimo partire, anziché cliccando su
> "Spigolo", scrivendo nel codice `(edit-edge-mark)` o `(edit-curve-mark)`? A
> quel punto parte la registrazione del drag. Una volta conclusa viene generata
> la edge-mark o la curve-mark e l'utente può creare un nuovo piano scrivendo
> `(plane-from-edges :spigolo-1 :spigolo-2 :curva-1)`.»

Concordato. Il guadagno più profondo non è togliere bottoni: **il piano smette
di essere una copia e diventa una formula**. Oggi `plane-mark` conserva i numeri
calcolati una volta e le prove in `:from`; con `(plane-from-edges …)` il piano si
ricalcola a ogni Run dalle prove nominate — correggi uno spigolo e il piano lo
segue, ne cancelli uno e il piano cambia, e il caso «piano vecchio, prove nuove»
smette di esistere. È il principio che ha già pagato col banco nel sorgente,
portato fino in fondo. Muore con esso tutta la UI di selezione: i nomi NEL
CODICE sono la selezione, e sono durevoli, ripetibili e diffabili.

Decisioni prese insieme:

- il nome è **`plane-from-edges`**, non `plane-from-curves`: quest'ultimo esiste
  già dentro il codice e prende curve grezze, non nomi — due significati per un
  nome si pagano dopo;
- **riaprire uno spigolo rimisura da zero**. Le osservazioni sono righe di pixel
  per foto, e il sorgente deliberatamente non sa niente di foto e pixel; portarle
  nel sorgente sarebbe una decisione diversa, e per ora non si prende;
- **la visibilità si pilota dal mark** (sotto).

Due vincoli tecnici da non scoprire a metà strada:

1. `(plane-from-edges :a :b)` scritto dentro la mappa dell'acquire viene valutato
   PRIMA che l'acquire esista, quindi non può risolvere i nomi da sé. Va
   restituita una **specifica differita** che `acquire` risolve dopo aver
   costruito i suoi `:edges` — lo stesso trucco che `edit-plane-mark` usa già.
2. `(edit-edge-mark)` **non deve essere una sessione del modal-evaluator**: il
   palcoscenico disattiva i propri click quando un modale è aperto
   (`(not (modal/active?))`), quindi si spegnerebbe da solo. Va armato come
   `edit-plane-mark`, che è gestito dal palco.

Costo accettato: ogni bordo nuovo costa un giro dall'editor. È il ritmo di
`edit-path`; non va compensato con un "riarma da solo", che sarebbe lo stato in
memoria che rientra dalla finestra.

Fette concordate: **1)** `(edit-edge-mark)`/`(edit-curve-mark)` come innesco +
conferma che scrive il letterale al posto della forma; **2)** `plane-from-edges`
come specifica differita, coi rifiuti che diventano errori di valutazione con i
loro numeri; **3)** demolizione (banco, selezione, tasti numerici, bottone
Spigolo, etichette col nome invece del numero).

### Chiavi di visibilità (fatte, 2026-08-09)

> «Dovremmo anche poter pilotare come si vedono mark e edges nel viewport: oggi
> ci sono troppi puntini e lineette e fa confusione.»

Il controllo sta sul mark, non in un pannello di caselle: sopravvive alla
chiusura del gesto, si mette su uno senza toccare gli altri, ed è testo.

    :show   false     niente
            true/-    il segno (disco+origine per un piano, il segmento per uno
                      spigolo) — il default
            :prove    anche i punti da cui è stato ricavato
    :label  false     nessuna scritta
            "testo"   quella scritta
            true/-    il suo nome — il default

Il default NON disegna più i punti delle prove: tre o più pallini per piano
erano il grosso di ciò che rendeva illeggibile il viewport, e sono evidenza —
da chiedere, non da portarsi sempre dietro. Un mark nascosto non prende
etichetta (un nome che galleggia sul nulla è peggio di nessun nome), e l'elenco
nel pannello dice «nascosto», perché nascondere una cosa non deve somigliare a
perderla.

### Fetta 1 — l'innesco dal codice (fatta, 2026-08-09)

`(edit-edge-mark)` e `(edit-curve-mark)` armano il gesto. La forma si scrive
come VALORE dentro `:edges`, e la chiave davanti è il nome:

    :edges {:bordo-alto (edit-edge-mark)}

Run → il palcoscenico entra in posa e apre i bordi; si dipinge su due foto;
la conferma **sostituisce la forma** con `(edge-mark {…})` sotto quello stesso
nome. Esc annulla: la forma vuota se ne va con tutta la sua voce (`(edge-mark)`
sarebbe un errore di arità), una forma che ne avvolgeva una già misurata torna
`(edge-mark {…})` col corpo identico byte per byte.

Dettagli decisi qui e non altrove:

- **il nome è dell'utente.** `commit-edge!` in modalità bersaglio non genera
  `:spigolo-7`: legge la chiave davanti alla forma. È ciò che rende leggibile
  `(plane-from-edges :bordo-alto :bordo-basso)`;
- **la via a mano resta**, finché c'è il bottone Spigolo: senza bersaglio la
  voce si aggiunge al blocco con un nome generato, esattamente come prima;
- **non è un modale.** Il palco disattiva i propri click quando un modale è
  aperto, quindi si sarebbe spento da solo: si arma come `edit-plane-mark`, da
  una nota lasciata durante la valutazione e consumata dal post-eval.

Verificato dal vivo sul sorgente vero (headless): conferma su forma vuota →
`:bordo-alto (edge-mark {…})` al posto giusto e resto intatto; Esc su forma
vuota → la voce sparisce; Esc su forma avvolgente → testa rinominata, corpo
identico; curva → `:profilo (curve-mark {…})`; senza bersaglio → `:spigolo-1`
appeso in fondo. E la valutazione vera di `(edit-edge-mark)` /
`(edit-curve-mark …)` lascia la nota giusta (`:retta` / `:curva` col letterale).

NON ancora collaudato con le mani: dipingere e confermare dentro una sessione
vera con le foto. È il gate di Vincenzo.

### Fetta 2 — il piano è una formula (fatta, 2026-08-09)

`(plane-from-edges :bordo-alto :bordo-basso)` si scrive dove sta un mark:

    :marks {:coperchio (plane-from-edges :bordo-alto :bordo-basso)}

Vale ogni tipo di bordo misurato — spigolo dritto (campionato lungo sé stesso),
curva (i suoi punti), cerchio (il suo anello): `curve/edge-points` li porta tutti
a punti, e il piano è il fit robusto che già c'era. Un ultimo argomento mappa
passa dritto nel mark, quindi `(plane-from-edges :a :b {:show false})` fa il
piano e lo tiene fuori dal disegno.

Tre decisioni dentro:

- **il verso della normale non viene dalle camere.** Qui non ce ne sono, e
  soprattutto una FORMULA deve dare lo stesso risultato a ogni Run mentre le
  camere si spostano: la normale punta VIA DAL CENTRO dell'oggetto, che è la
  regola fisica della faccia uscente;
- **un rifiuto non crea il mark**, e porta i suoi numeri: «i bordi nominati
  stanno quasi in FILA (larghi 2 mm) … 1 mm d'errore inclinerebbe la normale di
  26.5651°. Il mark NON è stato creato.» Meglio un nome che manca di un piano
  sbagliato che nessuno ha modo di sospettare;
- **il pezzo geometrico è puro e testato**: `curve/edge-points` sta in
  photogrammetry, non nell'editor, e la suite node ne prova le tre specie più il
  fatto che *spostata la prova, il piano si sposta con lei*.

Verificato dal vivo valutando sorgenti veri: due bordi incrociati a z=10 → piano
a z=10 con normale +Z; gli stessi a z=25 → piano a z=25; due paralleli a 2 mm →
rifiuto coi numeri e nessun mark; un nome che non esiste → rifiuto che lo
nomina.

### Fetta 3 — la demolizione (fatta, 2026-08-10)

Via il banco e tutto ciò che serviva a tenerlo: la lista nel pannello, la
selezione, i tasti numerici, `p`, `n`, il bottone **Spigolo** e il suo
toggle, i colori del banco, il nome generato `:spigolo-N` e il ramo di
`commit-edge!` che appendeva in fondo al blocco. Circa 200 righe in meno.

Cosa prende il loro posto, e perché è meno e non solo diverso:

- **il gesto si arma scrivendo la sua forma**, quindi non c'è più un modo da
  accendere e spegnere;
- **un solo tasto scrive**: Invio, per tutti e due i tipi. Quale sia lo decide
  l'IMMAGINE — si può scrivere `(edit-edge-mark)` e trovarsi con una curva, e
  riscrivere la forma giusta è compito del programma. `'c'` resta perché è una
  DICHIARAZIONE in più («questa curva è un cerchio»), non una misura diversa;
- **le etichette portano il NOME**, non il numero. Il numero esisteva per essere
  digitato a un banco che non c'è più; quello che lega la foto al codice adesso
  è il nome, perché è ciò che si scrive in `(plane-from-edges :bordo-alto …)`;
- **l'elenco nel pannello resta, ma è un promemoria**, non una cosa da cui
  scegliere: dice quali nomi ci sono, di che specie sono e quali sono nascosti;
- **Chiudi passa da `stop-edge!`**: con una forma armata, rinunciare deve
  rimettere il sorgente com'era, e un bottone che si limitava a scordare lo
  stato lasciava `(edit-edge-mark)` orfana nel testo.

Il palcoscenico non disegna più né i bordi né i piani per conto suo: li disegna
`source-edge-items` / `source-mark-items` LEGGENDO IL SORGENTE. Una seconda
copia dentro il gesto sarebbe una copia che una riga cancellata non raggiunge —
ed è la ragione per cui il banco è finito nel sorgente in primo luogo.

Verificato dal vivo sullo stato vero del palco: etichette `bordo-alto`,
`profilo`, `coperchio` (e niente per quello con `:show false`); i passi del
pannello sono «Dipingi :bordo-alto su 2 foto → Invio lo scrive → il piano si
scrive (plane-from-edges …)»; i bottoni rimasti sono Scrivi, Nomi, punta,
Ricomincia, Annulla tratto, Chiudi.

### Quanto conta il terzo bordo (2026-08-10)

> «Se a plane-from-edges passo più di due segmenti, del terzo prende solo il
> centro, è giusto?»

Misurato, non ricordato. Non prende «solo il centro»: il fit è ROBUSTO, non
parziale. Un terzo bordo complanare conta per intero (60 punti su 60, zero
scartati); uno che sta oltre `plane-outlier-mm` (1.5 mm) dal piano su cui gli
altri sono d'accordo viene scartato INTERO (20 su 20) e il piano non si sposta
di un micron. In mezzo — un bordo obliquo che attraversa il piano — ne contano i
punti che ci stanno dentro (17 su 20 nella prova) e il piano si inclina un po'.

Ed è giusto così: se un bordo di un'altra faccia potesse piegare il piano *un
po'*, sarebbe il modo peggiore di sbagliare — un errore piccolo, plausibile e
invisibile. La soglia è la stessa che regge tutto il canale.

Quello che NON andava è che lo scarto era muto: si nomina un terzo bordo
credendo di rinforzare il piano, e invece non conta niente senza che nulla lo
dica. Ora ogni bordo nominato rende conto di sé:

    ;; plane-from-edges · … · :coperchio: :tre NON è servito a questo piano:
    ;; sta fino a 3 mm fuori dal piano su cui gli altri sono d'accordo (la
    ;; soglia è 1.5 mm). Un bordo di un'altra faccia non deve poter inclinare
    ;; questo piano, quindi il fit lo lascia fuori.

o, quando ne serve solo una parte, «di :tre sono serviti 17 punti su 20».
Silenzio = tutti i bordi nominati sono serviti per intero. La politica è fissata
da un test (`a-third-edge-counts-whole-or-not-at-all`).

### I dati veri di :tip-plane, e perché "sembra prendere solo il centro" (2026-08-10)

Vincenzo, su tre spigoli veri della stessa punta: «di :tip-alto sembra usare solo
il centro (se lo tolgo il piano si sposta, quindi lo prende in considerazione)».

Misurato sui suoi numeri: **tutti e 60 i punti sono usati, zero scartati**, e il
bordo più lontano sta a 0.69 mm dal piano. Quello che si vede ha una causa
diversa e più semplice: il fit ai minimi quadrati fa passare il piano IN MEZZO
alle prove, quindi **ogni bordo lo attraversa** — `:tip-alto` va da −0.42 mm a
+0.69 mm passando per lo zero verso il centro, e lo stesso fanno gli altri due.
Un segmento a cavallo del piano tocca il disco solo dove lo incrocia e se ne
stacca ai capi; su un bordo lungo 16 mm si nota, sui due verticali da 5 mm no.
Non è "ne usa un pezzo": è il piano che ci passa in mezzo, come deve.

Ma i numeri hanno detto anche altro, ed è la cosa che valeva la pena costruire:
**togliendo un bordo il piano ruota di 9°** (senza `:tip-sx` 9.32°, senza
`:tip-alto` 8.91°, senza `:tip-dx` 4.52°). Tre prove che si contraddicono di
nove gradi non descrivono la stessa faccia — e la planarità in MILLIMETRI non lo
diceva: 0.69 mm stava tranquillamente sotto la soglia d'allarme di 1 mm, perché
0.69 mm su una nuvola larga 8 mm *sono* 9°.

Da qui il **leave-one-out**, da tre bordi in su: per ciascuno, di quanto
ruoterebbe il piano se lo togliessi, riportato quando il massimo supera 2°. È la
stessa arma che il canale usa già per gli spigoli, e sostituisce una soglia
assoluta che non poteva funzionare — un millimetro vuol dire cose diverse su una
nuvola larga 8 mm e su una larga 80.
