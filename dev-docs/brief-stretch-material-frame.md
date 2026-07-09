# Brief: stretch nel frame materiale della mesh

Data: 2026-07-08. Stato: da implementare. Cambia la semantica delle direzioni di stretch dentro attach: da assi della pose corrente ad assi materiali della mesh. Breaking change deliberato e a superficie minima (vedi Contesto).

## Contesto

Oggi il replay degli stretch (editor/impl.cljs, ramo mesh circa righe 1082-1102, ramo SDF circa righe 1381-1391) passa a `stretch-mesh-along-axis` / `sdf-stretch-along-axis` gli assi correnti della pose: `(:heading s)`, `(cross heading up)`, `(:up s)`, con pivot `(:position s)`. Le due funzioni di stretch sono frame-agnostiche (prendono asse e pivot world-space qualsiasi): il comportamento è interamente deciso da quali assi il replay passa.

Dentro attach le operazioni rigide (f, th, ...) muovono pose e mesh insieme come corpo rigido, quindi i due frame restano incollati e stretch-f allunga sempre lo stesso asse dell'oggetto. Le rotazioni cp (cp-th/cp-tv/cp-tr) invece ruotano la geometria relativamente alla pose: dopo una cp rotazionale gli assi della pose non sono più gli assi materiali della mesh, e stretch-f finisce per modificare più di una dimensione dell'oggetto. Il difetto era presente da sempre ma le cp rotazionali erano irraggiungibili dal keyboard pilot; le ha rese accessibili il gizmo (brief-edit-attach-handles.md), quindi la superficie di modelli esistenti che dipendono dal comportamento attuale è minima ed è questo il momento di cambiare.

Il principio che giustifica il cambio, da riportare a suo tempo nel manuale: traslazioni e rotazioni sono operazioni di piazzamento e vivono nel frame dell'ancora; lo stretch è una deformazione e appartiene al corpo. Quindi le direzioni di stretch sono sempre gli assi materiali della mesh, mentre il pivot resta la posizione corrente della pose (deliberato: "metto l'ancora su quel vertice e stretcho attorno all'ancora" è il caso d'uso, e le cp traslazionali devono continuare a spostare il pivot).

Invariante che definisce il comportamento nuovo: `(stretch-f k)` prima e dopo una `(cp-th a)` produce la stessa mesh, per ogni combinazione asse di stretch / asse di rotazione cp. Corollario: nel frame materiale gli stretch commutano con le cp rotazionali, il che tiene aperta la porta a un eventuale "simplify" futuro.

Definizione operativa del frame materiale: frame della pose composto con una rotazione di offset espressa nel frame locale della pose. L'offset parte da identità all'ingresso in attach, è invariante sotto le operazioni rigide (che ruotano pose e geometria insieme), e viene aggiornato solo dalle cp rotazionali. Espresso nel frame locale, l'offset non richiede coniugazioni quando operazioni rigide successive ruotano la pose.

## Accertamenti preliminari

A1. Verso dell'aggiornamento dell'offset: la semantica documentata delle cp è "la pose si muove relativamente alla geometria" (verificata per le traslazioni), ma il segno esatto della rotazione applicata ai vertici da `rotate-vertices-keeping-creation-pose` va letto e il verso dell'aggiornamento dell'offset va fissato empiricamente contro l'invariante di commutazione, non per deduzione. Il test dell'invariante viene scritto prima del codice ed è il giudice.

A2. Stato del replay: verificare se i rami mesh e SDF condividono la mappa di stato del replay o se l'offset va tracciato in entrambi separatamente; l'invariante vale identico per i due backend.

## Lavoro richiesto

### 1. Tracking dell'offset nel replay

Nello stato del replay di attach, un campo per la rotazione di offset geometria-vs-pose espressa nel frame locale della pose (quaternione o matrice, a discrezione). Identità all'ingresso; aggiornato da cp-th/cp-tv/cp-tr (verso da A1); non toccato da nessun'altra operazione. Nei due rami secondo l'esito di A2.

### 2. Assi materiali negli stretch

I tre rami `:stretch-*` (mesh e SDF) calcolano l'asse come asse della pose composto con l'offset, invece dell'asse della pose nudo. Pivot invariato: `(:position s)`. `stretch-mesh-along-axis` e `sdf-stretch-along-axis` non cambiano.

### 3. Esposizione del frame materiale alla sessione

edit-attach deve poter leggere il frame materiale dopo ogni re-eval (dallo stato del replay), perché il gizmo deve disegnare le maniglie di stretch sugli assi materiali, mentre frecce e anelli restano sugli assi della pose. Le due terne divergono visibilmente solo quando la storia contiene cp rotazionali, che è l'onestà visiva voluta: l'utente vede che l'ancora è girata rispetto al corpo.

### 4. Test prima del codice

- Invariante di commutazione: per ognuna delle 9 coppie (stretch-f/rt/u × cp-th/tv/tr), il path con lo stretch prima della cp e quello con lo stretch dopo producono mesh identiche (a meno di epsilon numerico).
- Regressione: un path senza cp rotazionali (stretch dopo f e th semplici) produce una mesh identica a prima del cambio; questo è il caso di tutti i modelli esistenti scritti prima del gizmo.
- Fattore negativo (riflessione): il flip del winding continua a funzionare con gli assi materiali.

## Verifica

- I test del punto 4 verdi, su entrambi i backend (mesh in suite; SDF almeno via REPL sul desktop, riportando l'evidenza).
- Verifica visiva: un box con `(cp-th 45)` seguito da `(stretch-f 1.5)` si allunga lungo il proprio asse originale, non lungo la diagonale; le maniglie di stretch del gizmo appaiono ruotate di 45 gradi rispetto a frecce e anelli.
- Il pivot segue le cp traslazionali: `(cp-rt 10)` poi `(stretch-f 2)` stira attorno alla nuova posizione dell'ancora (comportamento invariato rispetto a oggi).
- Suite `npm test` verde.

## Fuori scope

- Aggiornamento del manuale (la pagina che documenta gli stretch) e di Architecture.md: nel passaggio documentale unico a valle dell'arco edit-attach.
- Il "simplify" della storia: la commutazione guadagnata è un abilitatore, non un impegno.
- Cambiare la semantica del pivot: resta la posizione corrente della pose, per scelta.

## Alternative rigettate

- Status quo (stretch sugli assi della pose): rigettata. Dopo una cp rotazionale stretch-f modifica più dimensioni dell'oggetto; non è raccontabile ("allunga lungo l'asse dell'ancora, che però potrebbe non essere un asse dell'oggetto") e nessun caso d'uso concreto lo richiede.
- Pivot materiale (centro del bounding box o origine di creazione) insieme alle direzioni materiali: rigettata. Il pivot sull'ancora è la feature (stretch attorno al punto scelto dall'utente via cp traslazionali o snap su vertice); direzione e pivot sono ortogonali e si cambia solo la direzione.
- Flag per comando (`:frame :pose` / `:frame :material`) per scegliere il comportamento: rigettata ora. Nessun caso concreto per lo stretch nel frame della pose; se ne emergerà uno, il flag si aggiunge senza rompere niente (nessuna astrazione prima dei casi).
- Offset tracciato in coordinate mondo: rigettata. Richiederebbe la coniugazione dell'offset a ogni rotazione rigida successiva; espresso nel frame locale della pose è invariante per costruzione.
