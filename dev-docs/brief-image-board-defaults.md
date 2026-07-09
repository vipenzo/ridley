# Brief: default e validazione per image-board

Data: 2026-07-08. Stato: da implementare. Nasce dall'accertamento A3 di brief-edit-attach.md: `(image-board PATH)` col solo path non fallisce, produce una shape con coordinate nil che fluisce a valle in silenzio.

## Contesto

La primitiva `image-board` è in `src/ridley/turtle/shape.cljs` (circa riga 71), con una sola arità fissa: `[path scale-factor [imx imy] [orx ory] [w h]]`. Chiamata con meno argomenti non lancia: destruttura nil e produce coordinate nil/NaN, cioè una shape apparentemente valida ma rotta, il peggior modo di fallire perché il difetto emerge a valle, lontano dalla causa (stessa famiglia del bug di mesh-hull documentato a suo tempo).

I default ragionevoli esistono già ma vivono nel layer sbagliato: `request!` in `src/ridley/editor/edit_image_board.cljs` li conosce tutti (scale 100, imxy [0 0], wh [scale scale], orxy centrato sulla turtle `[(- (/ w 2)) (- (/ h 2))]`) e li applica prima di chiamare `shape/image-board`. L'editor sa quali sono i valori sensati, la primitiva no. È un'inversione: il posto canonico dei default è la primitiva, e l'editor deve ereditarli, non duplicarli.

Conseguenza collaterale già pagata: il cancel di edit-image-board non può fare l'head-strip letterale come il resto della famiglia (esito A3) e ricostruisce la form full-args da uno snapshot preso all'apertura della sessione. Quel caso speciale esiste solo perché la primitiva non ha default; con i default si smonta.

Vincolo da preservare: la sessione dell'editor tiene un flag `:auto-rect?` legato a `(nil? wh)`, cioè a "l'utente ha omesso wh". Questa distinzione tra omesso ed esplicito è logica dell'editor e deve sopravvivere alla delega dei default.

## Lavoro richiesto

### 1. Risoluzione dei default in un punto solo

In shape.cljs, una funzione di risoluzione parametri (nome indicativo `image-board-params`) che dato path e gli argomenti opzionali restituisce la mappa completa dei valori risolti, con la catena di dipendenze attuale di request!: scale default 100; imxy default [0 0]; wh default [scale scale]; orxy default `[(- (/ w 2)) (- (/ h 2))]` calcolato sul wh risolto. La funzione è l'unica fonte dei default: la usa image-board per le arità ridotte e la usa request! per popolare la sessione.

### 2. Multi-arità su image-board

Arità da `[path]` a `[path scale imxy orxy wh]`, ognuna delegante alla risoluzione del punto 1 e poi al corpo attuale. L'ordine posizionale resta quello di oggi (path, scale, imxy, orxy, wh): passare wh senza orxy non è possibile, com'è già oggi per il contratto posizionale di edit-image-board. Il comportamento della piena arità con argomenti espliciti deve restare identico byte per byte a prima.

### 3. Validazione con throw sulla piena arità risolta

Dentro la funzione di risoluzione, dopo l'applicazione dei default: path stringa non vuota; scale numero finito positivo; imxy e orxy vettori di due numeri finiti; wh vettore di due numeri finiti positivi. In caso di violazione, `js/Error` con un messaggio che nomina l'argomento offensivo e il valore ricevuto. Regola: da image-board non deve poter uscire una shape con coordinate non numeriche, per nessuna combinazione di input. Il throw copre tutti gli entry point perché la validazione vive nella risoluzione condivisa.

### 4. request! delega

request! smette di applicare default per conto suo: determina `:auto-rect?` sui suoi argomenti grezzi (wh omesso o nil), poi ottiene i valori risolti dalla funzione del punto 1 e popola la sessione con quelli. I numeri in sessione devono risultare identici a oggi per ogni combinazione di argomenti (è una pura ricollocazione della logica, non un cambio di valori).

### 5. Cancel di edit-image-board torna head-strip

Rimuovere il ripiego full-args introdotto per A3: il cancel riscrive il solo simbolo di testa (edit-image-board → image-board) lasciando il body verbatim, come il resto della famiglia. Per il caso fresco `(edit-image-board PATH)` le due strade producono lo stesso risultato per costruzione (l'head-strip dà `(image-board PATH)`, che ora risolve negli stessi default dello snapshot), quindi la semplificazione non cambia semantica.

## Verifica

- `(image-board "p.png")` valuta e produce una shape identica a `(image-board "p.png" 100 [0 0] [-50 -50] [100 100])`.
- Catena dei default: `(image-board "p.png" 80)` produce wh [80 80] e orxy [-40 -40].
- Regressione sulla piena arità: una chiamata a cinque argomenti espliciti produce una shape identica a prima del cambio.
- Validazione: `(image-board "p.png" "cento")`, `(image-board "p.png" -5)`, `(image-board "p.png" 100 [0])`, `(image-board "p.png" 100 [0 0] [0 0] [100])` lanciano tutte con messaggio che nomina l'argomento; nessuna combinazione di input produce coordinate nil o NaN.
- Sessione: per ogni combinazione di argomenti di `(edit-image-board ...)`, i valori in sessione (scale, imx, imy, orx, ory, w, h) sono identici a quelli di oggi; `:auto-rect?` è true con wh omesso e false con wh esplicito.
- Cancel fresco: `(edit-image-board "p.png")`, Esc → il sorgente contiene `(image-board "p.png")`, il run successivo valuta senza errori e la scena coincide con quella pre-sessione.
- Cancel con argomenti: `(edit-image-board "p.png" 80 [5 5] [-40 -40] [80 60])`, Esc → head-strip col body carattere per carattere intatto.
- Suite `npm test` verde.

## Fuori scope

- La documentazione delle nuove arità nel manuale (reference di image-board): il cambio è additivo, le chiamate documentate restano valide; l'aggiornamento della reference segue il flusso consueto del manuale, non questo brief.
- Argomenti keyword (`:scale`, `:at`, ...) al posto del contratto posizionale: altro progetto, vedi alternative rigettate.
- Un passaggio di validazione analogo sulle altre primitive shape a arità fissa (per esempio set-image, che ha la stessa esposizione al silent-nil): candidato da loggare in dev-docs/code-issues.md durante l'implementazione, non da fare qui.

## Alternative rigettate

- Lasciare i default in request! e aggiungerne una copia in image-board: rigettata, due fonti di verità che derivano; la risoluzione condivisa è il punto del brief.
- Passare a keyword args per gli opzionali: rigettata qui. Il contratto posizionale è rilasciato, la riscrittura di edit-image-board lo emette alla conferma, e il manuale lo documenta; cambiarlo è un progetto a sé con la sua migrazione.
- Validare senza lanciare (warning e default di ripiego): rigettata. Il silent-nil è il difetto che questo brief cura; degradare a warning ne ripristinerebbe una variante più gentile ma con la stessa firma (difetto che emerge a valle).
- Tenere il ripiego full-args nel cancel di edit-image-board "perché ormai c'è": rigettata. Esiste solo per l'assenza dei default; con i default l'head-strip è equivalente sul caso fresco e rende la famiglia uniforme, e il codice in meno è manutenzione in meno.
