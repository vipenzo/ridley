# Brief: auto-bounds del ramo rotate, dalla sfera all'AABB degli angoli ruotati

Data: 2026-07-09. Stato: da implementare. Nasce dal root-causing del mismatch 9-vs-533 vertici (addendum-brief-stretch-material-frame.md, esito loggato in dev-docs/code-issues.md). Fix piccolo e delimitato: un ramo di una funzione, più test.

## Contesto

`auto-bounds` (src/ridley/sdf/core.cljs, circa riga 671) stima i bounds di meshing da un albero SDF. Il ramo `rotate` (circa riga 706) è l'unico lossy in modo composto: prende i bounds del figlio, calcola r come distanza dall'origine dell'angolo più lontano (radice della somma dei quadrati dei max assoluti per asse) e restituisce il cubo [±r]³. Ogni nodo rotate gonfia quindi fino a un fattore √3, e i rotate si compongono: n nodi in catena danno fino a (√3)ⁿ.

I nodi rotate proliferano più in fretta di quanto sembri dal sorgente utente: `sdf-rotate-axis` (circa riga 438) decompone ogni rotazione ad asse arbitrario in tre nodi rotate cardinali (Tait-Bryan ZYX estrinseca), quindi una singola cp rotazionale dal gizmo produce 3 nodi, e il sandwich di `sdf-stretch-along-axis` (rotazione, scala, rotazione inversa) altri 6. Il caso misurato nel root-causing: due ordinamenti della stessa coppia stretch+cp-th, uno con 3 rotate e uno con 9, bounds ±11.8 contro ±322.7, rapporto 27.3 contro il (√3)⁶ ≈ 27 predetto. La risoluzione automatica (`resolution-for-bounds`) distribuisce la griglia sulla regione stimata: su una regione 27 volte troppo larga il mesher affama e produce una mesh degenere a 9 vertici, senza nessun errore (famiglia "apparentemente valida ma priva del contributo").

Fino a ieri le catene stretch+cp-rotazioni su SDF erano un caso esotico; il gizmo le ha rese un drag e il frame materiale le ha rese sensate da usare. La strada maestra ora porta dritta nel difetto, da cui l'urgenza.

Un secondo difetto dello stesso ramo, che il fix cura gratis: il cubo [±r]³ è centrato sull'origine per costruzione (usa i max assoluti), quindi per figli decentrati i bounds perdono anche la posizione, non solo la taglia.

## Il fix

Il nodo rotate conosce la propria rotazione esatta (`:axis` cardinale e `:angle`). Invece della sfera: prendere gli 8 angoli dei bounds del figlio, ruotarli con la rotazione effettiva del nodo, e restituire min/max per asse (AABB degli angoli ruotati).

Perché è strettamente migliore, mai peggiore: ogni angolo del box del figlio dista dall'origine al più r (r è costruito dai max assoluti per asse, quindi maggiora la norma di ogni angolo); la rotazione preserva le norme; quindi ogni angolo ruotato giace dentro il cubo [±r]³ attuale, e il loro AABB è contenuto nel cubo attuale. Per un singolo rotate su un box esatto è addirittura esatto (il box è l'inviluppo convesso dei suoi angoli e la rotazione è lineare, quindi l'AABB del box ruotato è l'AABB degli angoli ruotati). In catena resta una perdita da ri-boxing (AABB di AABB), ma limitata e senza il fattore √3 incondizionato per nodo; le rotazioni che riallineano (i sandwich rotazione-inversa dello stretch) tornano quasi ai bounds di partenza invece di raddoppiare l'inflazione.

Conservatività: l'AABB contiene esattamente i bounds ruotati del figlio, e i bounds del figlio portano già i margini di sicurezza dei rami primitivi (1.2 su sphere e cyl, 0.6 per semi-lato su box); nessun padding aggiuntivo necessario nel ramo rotate.

## Trappola di segno (attenzione, errore già commesso due volte nel progetto)

L'angolo memorizzato nel nodo è in convenzione libfive: alla costruzione l'angolo viene negato per l'asse y (circa riga 387-388, commento nel sorgente). La rotazione applicata agli angoli in auto-bounds deve usare la stessa convenzione che il backend applica al campo, altrimenti l'AABB è quello della rotazione speculare. Per contenuti quasi simmetrici l'errore non si vede; il test con figlio decentrato e rotazione su y (sotto) esiste apposta per catturarlo. Una quantità misurata nella convenzione sbagliata dà risultati sbagliati ma plausibili: fissare la convenzione con quel test, non per deduzione dal sorgente.

## Lavoro richiesto

1. Riscrivere il ramo `(= op "rotate")` di auto-bounds: bounds del figlio, 8 angoli, rotazione cardinale del nodo (axis + angle, convenzione verificata col test), min/max per asse.
2. Test unitari, scritti prima del cambio:
   - Rotazione di 90 gradi su asse cardinale di un box axis-aligned: bounds identici al box (esattezza sul caso banale).
   - Rotazione di 45 gradi attorno a z di un box [±a]³: estensioni x e y pari ad a√2, z invariata (contro il cubo ±a√3 attuale).
   - Figlio decentrato: box traslato di +10 su x, ruotato di 90 gradi attorno a z, i bounds atterrano attorno a ±10 su y con il segno giusto (cura del centraggio); variante con rotazione attorno a y per la trappola di convenzione.
   - Contenimento: per un campione di rotazioni e box, l'AABB nuovo è sempre contenuto nel cubo che il ramo vecchio avrebbe prodotto (la proprietà "mai peggiore" resa test).
3. Test di accettazione end-to-end, dalla riproduzione REPL del root-causing: i due ordinamenti stretch+cp-th (3 rotate contro 9) devono produrre bounds comparabili tra loro e mesh a conteggio vertici comparabile a risoluzione automatica (ordine ~5900 entrambi, non 9 contro 533). Su desktop con geo-server, evidenza nel report.
4. Aggiornare la entry di code-issues.md (`auto-bounds` sovra-inflaziona): da difetto aperto a risolto, lasciando la nota sul collasso delle catene affini come evoluzione futura.

## Verifica

- I test del punto 2 e 3 verdi; suite `npm test` verde.
- Controllo visivo su desktop: il caso B del root-causing (cp-th poi stretch) mesha a risoluzione automatica con densità normale.
- Nessuna regressione sui rami non toccati di auto-bounds: gli altri casi della suite SDF esistente restano verdi.

## Fuori scope

- Il collasso delle catene affini prima del meshing (fondere move/rotate/scale consecutivi in un'unica trasformazione): è la cura profonda ma è un progetto, e con l'AABB in mezzo perde urgenza. Resta annotata come evoluzione nella entry di code-issues.md.
- Ritoccare le euristiche degli altri rami (padding di blend e offset, gestione di scale): funzionano e non compongono in modo patologico.
- La logica di risoluzione automatica (`resolution-for-bounds`): il difetto è nei bounds, non nella griglia.
- Bounds orientati (OBB) lungo l'albero: più precisi in catena ma cambiano la rappresentazione dei bounds ovunque; sproporzionato rispetto al problema.

## Alternative rigettate

- Status quo con padding ritoccato: rigettata, tratta il sintomo; la composizione (√3)ⁿ resta illimitata e riappare due rotate più in là.
- Collasso delle catene affini come fix immediato: rigettata come primo passo, è un refactor del pipeline di meshing con superficie ampia; l'AABB è locale a un ramo, strettamente migliore, e compra il tempo per farlo bene se e quando servirà.
- OBB propagati lungo l'albero: rigettata, vedi fuori scope; la precisione extra in catena non giustifica il cambio di rappresentazione.
- Bounds espliciti richiesti all'utente nei casi degeneri: rigettata, il difetto è silenzioso proprio perché l'utente non sa di doverli fornire; la stima deve essere sana di suo.
