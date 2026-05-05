# Roadmap

## Cos'è questo documento

Questo documento descrive le direzioni di lavoro previste per Ridley nei prossimi mesi e anni. Non è una lista di task con scadenze, e non è una promessa di funzionalità: è una mappa.

La mappa è organizzata per orizzonti, dal più vicino al più lontano. La Parte I raccoglie il lavoro di breve termine, la Parte II quello di medio termine, la Parte III le visioni di lungo periodo. La Parte IV vive su un asse separato: raccoglie sperimentazioni tecniche di natura aperta, dove non c'è ancora consenso su se valga la pena pagare il costo.

Dentro ciascun orizzonte, le voci sono raggruppate per natura: manutenzione, evoluzione del DSL, evoluzione dell'AI, librerie a corredo, e così via. La distinzione fra le nature è importante: alcune voci sono pagamento di debito già preso (manutenzione), altre sono direzioni di esplorazione il cui esito non è scontato.

Il riferimento di base per il debito tecnico già diagnosticato è il capitolo 15 di `Architecture.md`. Molte voci della Parte I rimandano puntualmente a sezioni di quel capitolo, e questo documento non le riprende per intero ma le inquadra nell'orizzonte.

---

## Parte I — Breve termine

Le voci di questa parte sono lavoro a settimane o mesi, con dipendenze risolte e design già preso. Sono pagamenti di debito conosciuto e completamenti puntuali.

### 1.1 Bonifica degli esempi

Priorità prima di tutto. La cartella `examples/` contiene oggi diversi script che non funzionano: alcuni riferiscono primitive SDF la cui API è cambiata, altri sono in stati intermedi di port. Lo stato di fatto è sgradevole nel modo più diretto: gli esempi sono spesso il primo contatto di un visitatore col progetto, e un esempio rotto è una pessima introduzione.

Il lavoro è di tre tipi: aggiornare gli esempi esistenti all'API corrente, decidere quali esempi non hanno più senso e cancellarli, scriverne di nuovi dove la copertura è scarsa (in particolare per le SDF, dove l'esemplificazione è oggi sotto-rappresentata rispetto alla maturità del sottosistema). La rimozione del codice morto è preferibile alla sua manutenzione: meno esempi, ma tutti funzionanti e ben scelti.

Una conseguenza laterale di questo lavoro è il completamento dell'abbozzo Gears (1.5), che diventa il primo elemento della libreria parts a corredo.

### 1.2 Revisione della manualistica

La documentazione di Ridley vive su tre livelli, e tutti e tre richiedono attenzione.

**`Spec.md`** è il riferimento DSL completo, in inglese, organizzato per categoria di operazione. Pubblico: chi vuole sapere esattamente cosa fa una funzione e quali parametri accetta. Stato attuale: disallineato dall'implementazione corrente. Mancano funzioni introdotte negli ultimi mesi (scope, scena, animazioni procedurali, anim-proc!, parts del nuovo sistema di registry, shape-fn aggiunte come `shell` e voronoi-shell). Alcune voci sono obsolete. La struttura di alto livello è cresciuta organicamente e ha bisogno di un riordino editoriale prima del riallineamento puntuale del contenuto.

Il piano è in due passaggi: prima la riorganizzazione manuale dello scheletro (decisione editoriale di come raggruppare le voci), poi il riempimento del contenuto delegabile a Code, file per file, con un brief che fornisca lo scheletro e chieda di riempirlo guardando il sorgente. Il `File Structure` finale del documento, oggi sbagliato, va riscritto da zero.

**Manuale online** (`src/ridley/manual/content.cljs`, bilingue EN/IT) è già implementato e integrato nell'app, con esempi auto-eseguibili nel viewport. Il pubblico è un utente che impara facendo. Lo stato attuale ha due problemi: la struttura, cresciuta organicamente seguendo l'evoluzione delle feature, non riflette la struttura concettuale del sistema (debito già diagnosticato in 15.4.4 di `Architecture.md`); e manca una funzione di search, che con l'attuale volume di documentazione è un buco evidente (debito 15.4.3).

Una volta che la search del manuale esiste, è naturale estenderla per presentare anche i risultati provenienti da `Spec.md`: il documento resta separato, ma le sue pagine diventano visualizzabili online come approfondimento. Il manuale e la spec rimangono distinti per pubblico e funzione, ma comunicano nel punto in cui l'utente cerca informazione.

**User guide e supporti narrativi** sono il terzo livello e oggi non esistono. Ne parla la Parte II, sezione 2.5: è un progetto editoriale che richiede pianificazione, non manutenzione, e quindi vive in un orizzonte diverso.

### 1.3 Pulizia ed estrazione del codice

Le voci di consolidamento del cap. 15.2 di `Architecture.md`, ordinate per costo crescente.

Costo basso: rimozione di `api.cljs` e del build target `:core` ormai inerte (15.5.1, meno di un'ora). Rinomina di `test_mode` → `tweak_mode` per allinearsi al nome utente del modal evaluator (parte di 15.2.1).

Costo medio: estrazione del pattern modal evaluator dalle implementazioni concrete di `tweak_mode` e `pilot_mode` in un protocollo astratto (15.2.1, due giorni). Multimethod per il dispatch dei provider AI al posto del `case` corrente, con i provider come tabella di contenuti registrati invece che casi cablati (15.2.2, una giornata). Unificazione del flusso storia AI fra le due superfici palette e voce, oggi in due percorsi separati (15.2.3, due-tre giorni). Decisione di principio sull'uso del macro prompt di sistema AI (15.2.4): se mantenerlo, riprogettarlo, o rimuoverlo.

L'effetto cumulativo di questa pulizia è una superficie di codice più piccola e più leggibile, e la rimozione di trappole conosciute. Non aggiunge feature, ma rende il sistema più adatto a riceverne di nuove.

### 1.4 Robustezza puntuale

Le voci del cap. 15.3 di `Architecture.md` che sono fattibili a costo limitato.

Tweak offset stale e graceful degradation del mutex interattivo (15.3.1, una giornata): oggi un'edit accidentale durante una sessione tweak può lasciare lo stato in posizione incoerente; la fix richiede tracciare l'offset rispetto al sorgente e restare graceful sulla collisione di mode. Storage librerie con segnalazione errori esplicita all'utente (15.3.2, mezza giornata): oggi un fallimento di scrittura è silente. Protocollo backend storage librerie (15.3.3, una giornata): unificare i due backend (filesystem desktop, localStorage web) dietro un protocollo comune. Edit mode pannello librerie (15.3.4): rinominazione e cancellazione delle librerie utente sono oggi possibili solo da console; serve una UI minima.

Spy/recorder per gli stub muti del sci-harness (15.3.5, alcune ore): il test harness oggi traccia silenziosamente le call a stub di Three.js, di registry, eccetera; un piccolo recorder che li espone migliorerebbe la diagnosticabilità dei test che falliscono per side-effect non previsti.

### 1.5 Completamenti DSL minori

Lavori di pochi giorni ciascuno, ben circoscritti.

Non-uniform scale con vector: oggi la firma è `(scale fx fy fz)` con tre numeri; estendere il caso `(vector? ...)` in `unified-scale` per accettare `(scale [sx sy sz])` (mezza giornata).

Pretty-print dei risultati REPL: oggi la output usa `pr-str` puro; passare a `cljs.pprint/pprint` con troncamento per output grandi rende il REPL leggibile (mezza giornata).

Auto-complete custom dei binding DSL: l'editor ha autocomplete generico CodeMirror sui token del buffer corrente, ma non un completion source che pesca dai circa 250 binding SCI di Ridley. La fonte naturale è `bindings.cljs`, arricchita con docstring derivate dal manuale online (uno-due giorni).

Inline documentation hover: tooltip su nome di funzione che mostra docstring derivata da `manual/content.cljs`. Estensione `hoverTooltip` di CodeMirror (due-tre giorni).

Error highlighting persistente: oggi gli errori SCI producono un flash arancio sulla riga; l'integrazione con `@codemirror/lint` darebbe marker persistenti su gutter e squiggle sotto il token problematico (una giornata).

Save/open file dialog simmetrici: il save dialog nativo è già implementato lato Tauri, manca l'open speculare. Poche ore di lavoro.

Recent files: lista dei file aperti di recente con persistenza e voce di menu (una giornata).

`path-to-shape` come vera proiezione XY: oggi `path-to-shape` ([shape.cljs:359](../src/ridley/turtle/shape.cljs#L359)) ritraccia il path considerando solo `:f`, `:th` e `:set-heading` (con Z scartata), ignorando silenziosamente `:b`, `:tv` e `:tr`. La docstring e Spec.md la descrivono come "XY projection", ma una proiezione reale eseguirebbe il path completo in 3D e poi scarterebbe la Z dei waypoint. Da fare: eseguire il trace con turtle 3D e proiettare a posteriori (mezza giornata).

### 1.6 Recupero della copertura test

Il cap. 15.2.7 di `Architecture.md` elenca le aree del sistema con copertura test scoperta. Sono aree distribuibili nel tempo e su sessioni separate, perché ognuna è autocontenuta: voce, AI, animazione, viewport, librerie, modi interattivi. Il lavoro è di scrivere fixture e harness specifici per ogni area, non di portare il numero di test in alto in modo grossolano.

Il recupero degli E2E del sidecar (15.2.6) è una voce a sé. Quando il sidecar JVM è stato cancellato il 23 aprile 2026, una serie di test E2E che giravano su quello hanno perso il loro target. Il loro recupero richiede port a SCI+CLJS e estensione del test harness per coprire le superfici E2E rilevanti. È una settimana o più di lavoro, e va pianificato come progetto a sé.

---

## Parte II — Medio termine

Le voci di questa parte sono linee di lavoro più sostanziose, dove il design è chiaro nelle linee generali ma richiede ancora scelte. Sono direzioni, non task.

### 2.1 Evoluzione del rapporto con l'AI

L'integrazione AI è funzionale ma il guadagno effettivo dell'utente è modesto rispetto all'investimento. La diagnosi e le vie di pagamento sono tracciate in 15.4.5 di `Architecture.md`. Qui interessa l'orientamento di medio termine.

La via di sperimentazione naturale è il **RAG su `examples/`** invece che (o in aggiunta a) `Spec.md`. Il RAG keyword-based attuale recupera chunk pertinenti ma non basta a colmare il gap del modello che non ha visto Ridley nel training set. Imparare per esempi piuttosto che per descrizione è una via che non è stata ancora tentata, e la cartella `examples/` — una volta bonificata (1.1) — è il corpus naturale.

La via realistica nel medio termine è la **ridefinizione del ruolo dell'AI** nel progetto. Non assistente alla generazione di codice complesso, dove la qualità è insufficiente, ma scaffolder per costrutti semplici, traduttore di intenti high-level in skeleton di partenza che l'utente raffina, oppure interprete di richieste nel mode `:ai` della voce dove la qualità accettabile è più bassa perché il contesto è esplorativo. Questa ridefinizione è in parte una scelta editoriale (cosa promettiamo all'utente) e in parte di prodotto (come strutturiamo le superfici AI per riflettere i loro punti di forza reali).

La via massima — fine-tuning di un modello small su corpus crescente di codice Ridley — vive in Parte III come visione lunga.

### 2.2 Allargamento delle superfici di edit non testuale

Il paradigma testuale di Ridley fonde due affermazioni: il codice come fonte di verità del modello, e scrivere codice come esperienza utente. La prima è invariante. La seconda è già parzialmente smontata da `tweak`, `pilot`, viewport picking, AI palette: nuove superfici di edit non testuale, accessibili a utenti meno esperti di programmazione, sono un obiettivo dichiarato del progetto a patto che producano codice come output.

Il cap. 15.4.6 di `Architecture.md` raccoglie cinque direzioni concrete in ordine di maturazione crescente. Le prime quattro vivono nel medio termine.

**Estensione del pattern modal evaluator** (la prima e più immediata): oggi `tweak` e `pilot` sono i due esemplari, ma il pattern è generalizzabile. Sessioni di painting, dove l'utente "dipinge" su una superficie e Ridley produce le primitive corrispondenti. Sessioni di tracing, dove l'utente disegna un path nel viewport e Ridley produce il `path` o `bezier-as` corrispondente. Altre superfici interattive specifiche per dominio. Il prerequisito è l'estrazione del pattern modal evaluator (1.3, voce 15.2.1).

**Palette di blocchi pre-confezionati**: galleria di blocchi geometrici inseribili nel sorgente con drag-and-drop o picking dal menu, ognuno coi parametri esposti come UI ma persistiti come codice. La forma esatta è da decidere — palette flottante, sidebar, picker contestuale — ma il principio è chiaro.

**Wizard parametrici**: per oggetti comuni (cerniere, viti, ingranaggi, contenitori parametrici), una UI di wizard che chiede all'utente i parametri rilevanti e produce codice. È più editoriale che tecnica: scegliere quali oggetti meritano un wizard, scrivere le librerie corrispondenti, costruire la UI di interrogazione.

**Estensione del sistema di assemblaggio**: oggi i `mark` permettono di nominare punti significativi su una mesh per riferirli da un'altra. La sua estensione naturale è l'**assemblaggio per facce combacianti**: l'utente seleziona dal viewport due facce di mesh diverse, Ridley produce il codice di trasformazione che le fa combaciare. È il superamento del "calcola le coordinate giuste a mano" verso il "dichiara la relazione, lascia che Ridley calcoli le coordinate". Richiede un piccolo lavoro di design DSL per la sintassi della relazione e l'integrazione col viewport picking esistente.

### 2.3 Animation export

L'export GIF è la prima voce in casa, e ha portato con sé l'infrastruttura su cui si appoggiano le altre. Il loop di cattura off-realtime sta in `ridley.export.animation/capture-frames!`: sospende il render loop di Three.js, itera `t ∈ [0,1]` chiamando `seek-and-apply!`, forza un render sincrono, e passa il canvas a un `on-frame` callback. È encoder-agnostico per costruzione. L'encoder GIF (gif.js, lazy-loaded da `public/vendor/`) consuma quel callback e scrive il file via geo_server in `~/Documents/Ridley/exports/`. Solo desktop, gating su `env/desktop?`, bundle web invariato.

Le voci ancora aperte poggiano tutte su `capture-frames!`.

**PNG frame-by-frame export**: con il loop pronto è un giorno di lavoro. On-frame raccoglie `canvas.toBlob('image/png')`, JSZip (già nel bundle) impacchetta, write-file scarica.

**MP4 export via ffmpeg**: l'output PNG sequence passa per ffmpeg per produrre un video. La scelta di design è ffmpeg system-installed contro ffmpeg sidecar bundled nel binario Tauri. Bundled è più affidabile per l'utente finale ma aumenta la dimensione del bundle; system-installed è più leggero ma richiede installazione utente. Tre-cinque giorni più la decisione.

**Encoder GIF Rust nativo (gifski)**: oggi gif.js (NeuQuant in JS, output 1-3 MB sui pilot) è sufficiente per la documentazione tecnica. Migrazione a `gifski` via crate Rust darebbe palette percettiva (output 30-50% più piccolo a parità di qualità) ed encoding parallelo veloce, al costo di ~5-15 MB sul binario Tauri. Da rivalutare quando avremo abbastanza GIF nella documentazione per giudicare se la qualità di gif.js basta.

La generalizzazione di `capture-frames!` da uso visivo a uso analitico vive in §2.7.

### 2.4 Geometria avanzata

Tre completamenti sostanziosi del DSL geometrico, indipendenti fra loro.

**Adaptive loft step density per shape-fn transitions**: il `bloft` ha già adaptive sampling sulla curvatura del path; manca l'analogo sul *rate of change della shape-fn*. Il `capped` shape-fn produce oggi faceting grezzo perché i loft step sono uniformi nonostante il profilo cambi rapidamente in alcune zone. Il blocco di design è la metrica: definire un "cambio shape" robusto per shape-fn arbitrarie — una shape-fn ritorna una shape, e misurare la distanza fra due shape è in generale più sottile che misurare la distanza fra due punti di un path. Tre-cinque giorni di lavoro più l'esplorazione della metrica.

**Face cutting**: disegnare una shape 2D su una faccia esistente di una mesh e usarla come operazione di taglio (passante o cieco). Oggi `attach-face` permette estrusione e inset di una faccia, ma non "qui voglio scavare un buco con questo profilo". Una settimana, con tre blocchi di design: orientamento UV della shape sulla faccia, gestione della profondità (passante/cieco), boolean Manifold finale.

**Attach structure-preserving**: oggi `attach` accetta vettori di mesh ma li flatten ricorsivamente. Una variante structure-preserving permetterebbe di trattare gruppi nidificati come corpi rigidi, con la stessa nesting structure in input e output. Uno-due giorni di lavoro.

### 2.5 Apertura del progetto verso l'esterno

Il livello narrativo della documentazione, oggi assente. Chi arriva su `vipenzo.github.io/ridley` o sul subreddit non trova un percorso "guarda cosa puoi fare in 5 minuti", non trova un'introduzione al perché Ridley esiste, non trova esempi commentati di cosa il sistema sa fare bene.

Il lavoro è editoriale, non tecnico. Le forme possibili sono diverse e non mutuamente esclusive:

**User guide testuale breve**: una pagina o un documento che porta un nuovo arrivato dal "che cos'è questa cosa" al primo modello stampabile in 5-10 minuti. È blog post lungo, non manuale. Tono narrativo, screenshot, codice commentato passo passo. Il pubblico è chi è curioso ma non ancora investito.

**Serie YouTube tematica**: video brevi (5-10 minuti) ognuno su un aspetto del sistema. Il primo, "What is Ridley?", è un investimento ad alta leva: pochi giorni di registrazione e montaggio per qualcosa che vive su YouTube e si lega facilmente nelle altre vetrine del progetto. Video successivi: "Hello turtle", "Modeling a bracket in 5 minutes", "From sketch to STL", e via dicendo.

**Articoli di approfondimento**: una serie di blog post tecnici sull'architettura, sulle decisioni di design, sull'esperienza dello sviluppo AI-assistito. Sono già parzialmente esistenti come materiale, vanno strutturati come serie. Il subreddit r/RidleyCAD, oggi usato quasi solo per gli announce di versione, è il posto naturale per ospitarli e dare al subreddit stesso una funzione che vada oltre il changelog. Pubblicare regolarmente lì un articolo a settimana o ogni due settimane attiverebbe il subreddit come spazio di lettura, non solo di notifica.

La scelta della forma o della combinazione di forme è essa stessa parte del lavoro. Una preferenza iniziale: cominciare dal primo video YouTube come pezzo singolo ad alto rendimento, dalla user guide testuale come ancoraggio del sito GitHub Pages, e dall'attivazione editoriale di r/RidleyCAD come canale continuativo.

### 2.6 Librerie a corredo

Standard parts come libreria parts vere e proprie, non come funzionalità del core. Sono progetti di contenuto: si scrivono come `.clj` idiomatici, si pubblicano come parte degli esempi o di una collezione `library/parts/` separata.

**Gears**: completamento di quanto abbozzato nei primi esempi con ingranaggi. Coppie di ingranaggi spur con parametri standard (modulo, denti, larghezza), eventuali estensioni a ingranaggi elicoidali e conici come incremento.

**Threads**: viti, dadi, accoppiamenti filettati. Helix sweep su profilo trapezoidale per filetti realistici. Set base secondo standard ISO (M3, M4, M5, M6, M8, M10) più dadi e rondelle corrispondenti. È lavoro di una-due settimane per il set base; il blocco non banale è il filetto realistico, dove la mesh prodotta deve essere stampabile e stabile in boolean.

Ulteriori librerie a corredo (cuscinetti, profilati, raccordi) possono seguire come incrementi quando emergono casi d'uso.

### 2.7 Animazioni come strumento di analisi

`capture-frames!` (§2.3) è oggi il loop di cattura visiva: per ogni frame, render del canvas e cattura. La sua generalizzazione naturale è sostituire "cattura il canvas" con "esegui una funzione utente arbitraria" — misura geometrica, predicato, calcolo. L'output non è più un GIF: è una traccia temporale di una proprietà del modello.

Per la cerniera di `01-hinge-c-profile`, a ogni `t` calcoli `(mesh-volume (mesh-intersection inner-hinge outer-hinge))`: se è zero per ogni `t` la cerniera è meccanicamente valida, se è non-zero per qualche `t` hai un'interferenza e sai *quando* (per quale angolo) e *quanto* (volume). È un salto di categoria: oggi le animazioni di Ridley servono a *vedere* il movimento, con questa estensione servirebbero a *verificarlo* — sostituendo molte iterazioni di "stampo, monto, riprogetto" con due righe di check nel sorgente. Diventa pertinente appena scriveremo esempi meccanici più complessi (manovellismi, giunti cardanici, meccanismi a quattro barre).

L'API è stratificata, una sola implementazione. La primitiva **`anim-fold`** ha semantica reduce con supporto a `reduced` per stop precoce: accumula su tutti i frame, ferma appena l'utente decide. Sopra di essa, due helper. **`anim-sample`** è `anim-fold` con `:init [] :step conj`: ritorna un vettore di N misure, adatto al caso comune di produrre un trace per plotting o ispezione. **`anim-check`** è `anim-fold` con un predicato ed early-stop alla prima falsità, adatto alle verifiche pre-confezionate.

Lo spec va scritto prima di implementare. Aperti: la firma esatta degli helper; una libreria di check pre-confezionati (`point-in-mesh` leggero contro `mesh-intersection` costoso, perché 90 booleane Manifold per check non si possono permettere sempre); eventuale visualizzazione del trace sovrapposta all'animazione, voce secondaria.

---

## Parte III — Lungo termine

Le voci di questa parte sono visioni del progetto, non piani. Hanno orizzonte di anni o di "quando sarà il momento", e il loro design è ancora in larga parte aperto.

### 3.1 WebXR di seconda generazione

Il sottosistema WebXR (sezione 11.8 di `Architecture.md`) è oggi una superficie di rendering: la scena è visualizzata in immersione, ma manca la simmetria del lato editing. Il sottosistema voce, pensato come canale di edit alternativo alla tastiera fisica, è in pausa di sviluppo (15.4.1) in attesa di sblocco da parte della 15.4.2: la visualizzazione del sorgente in XR.

Il problema di 15.4.2 è di design, non di implementazione. Una semplice trasposizione di CodeMirror in 3D non è una soluzione: un editor di testo galleggiante in uno spazio virtuale non è ergonomico né per leggere né per editare. La domanda è cosa significhi "vedere il codice" in un contesto immersivo, dove le metafore del piano 2D non valgono e quelle dello spazio reale non sono ancora codificate da convenzioni stabili.

Quando questa domanda trova risposta, il bundle si sblocca e diventa fattibile in tempi misurabili: rendering della rappresentazione scelta, riapertura della voce con aggiornamento dei matching i18n e dell'help db al DSL corrente, integrazione fra voce ed edit del codice nel visore.

La quinta direzione di 15.4.6 — composizione visuale 3D del codice come blocchetti funzionali assemblabili in immersione — è una variante diversa dello stesso problema, presa dall'angolo "come faccio a comporre il codice in 3D" invece di "come faccio a vedere il codice in 3D". È visione lunga, con elemento esogeno (richiede progressi di paradigma in interaction design XR che oggi non esistono come stato dell'arte).

### 3.2 Versioning e storia del modello via git

Il modello in Ridley è codice. Il codice si versiona con git. La conseguenza naturale è che il "salvataggio del progetto" e la "storia del modello" coincidono con commit e log di un repository git, e l'undo "vero" è un checkout su un commit precedente.

L'integrazione git nativa nella versione desktop sfrutterebbe questa coincidenza. Una vista temporale dei commit del file corrente, ognuno cliccabile per visualizzare lo stato del modello a quel punto. Commit automatici a ogni eval di successo, o manuali con messaggio. Branch per esplorare varianti del modello senza perdere la linea principale.

Questa direzione sostituisce e supera l'idea di un undo/redo globale del modello: invece di un meccanismo dedicato di history a runtime, si appoggia su un sistema (git) che già conosce versioning, branch, merge, diff. La WebView ha accesso al filesystem desktop tramite il sidecar Rust, quindi le primitive git sono disponibili lato server con un'API HTTP nuova.

Il design da prendere include: granularità del commit (per eval, per save, manuale), interfaccia utente (pannello laterale, vista timeline, integrazione nell'editor), comportamento sui branch e sui conflitti. È visione di lungo termine, non perché il lavoro tecnico sia enorme, ma perché richiede una visione editoriale matura del rapporto fra utente e storia del proprio modello.

### 3.3 Fine-tuning di un modello small su corpus Ridley

La via massima del cap. 15.4.5: fine-tuning di un modello small (dimensioni nell'ordine dei 7-13B parametri) su un corpus crescente di codice Ridley scritto a mano. È il pagamento più sostanzioso della diagnosi sull'AI: invece di compensare la sotto-rappresentazione del corpus nel training set dei modelli generici, addestrare un modello che ha visto codice Ridley.

I prerequisiti sono tre. Un volume di codice esemplare significativamente più ampio di quello attuale (la cartella `examples/` ha 23 file `.clj` oggi; per fine-tuning utile servono ordini di grandezza in più). Infrastruttura di addestramento che il progetto non ha. Una visione del prodotto — il modello fine-tuned diventa un asset distribuibile, e questo cambia il modello di distribuzione di Ridley.

È visione di lungo periodo, e ha un elemento esogeno: dipende dall'evoluzione dei modelli small open weight, dal costo dell'addestramento, e dal volume di codice Ridley pubblico nel tempo. Resta in roadmap come direzione consapevolmente nominata, non come piano operativo.

---

## Parte IV — Sperimentazioni tecniche

Le voci di questa parte sono linee aperte sul fronte performance e architettura, dove non c'è ancora consenso su se valga la pena pagare il costo. Vivono su un asse separato dalle Parti I-III: non hanno orizzonte temporale stabilito, e la decisione di intraprenderle dipende da rivalutazioni quando i vincoli cambiano.

### 4.1 Trasporto binario mesh Rust↔SCI

Il trasporto attuale fra geo-server Rust e CLJS passa per JSON sincrono via XHR. Il cap. 9.3 di `Architecture.md` documenta il razionale: SCI è sincrono, le mesh devono ritornare prima che `register` continui, e fra le opzioni sincrone JSON è risultato più veloce delle alternative binarie a causa dello scan UTF-8 sui body `application/octet-stream` in WKWebView e Chrome.

La rivalutazione del trasporto torna in considerazione se uno dei vincoli cambia. Il candidato principale è la sostituzione delle dipendenze CDN con bundle locale, che farebbe cadere il problema COEP e renderebbe percorribile la via SharedArrayBuffer + Web Worker per pseudo-sync su un canale async. Quel cambio richiede 3-5 giorni di refactor con regressioni rischiose, e il `transport-audit.md` raccoglie il quadro completo delle alternative valutate.

Per ora il rapporto fra costo e beneficio non si paga e l'attuale trasporto regge. La voce resta in pista come esperimento da fare se le condizioni cambiano.

### 4.2 Web Worker per SCI evaluation

Spostare la valutazione SCI fuori dal main thread. Lavoro nell'ordine di una-due settimane. Tre blocchi pesanti: gli atom condivisi (turtle, registry, stato persistente del runtime DSL) richiedono un message passing strutturato; Three.js è main-thread-only, quindi le mesh prodotte da SCI andrebbero serializzate worker→main; Manifold (via geo-server HTTP) e SDF sono già off-thread, riducendo il guadagno netto della parallelizzazione.

Il valore della voce è discutibile: dato che le operazioni geometriche pesanti sono già off-thread, lo SCI sul main thread blocca solo per il costo della sua valutazione pura, che per script tipici è nell'ordine di decine di millisecondi. Lo sblocco interessante non è la performance ma la responsività della UI durante eval lunghe. Va tenuta in pista come esperimento da fare in coppia con 4.1, perché entrambe puntano allo stesso obiettivo per vie diverse.

### 4.3 Incremental geometry updates

Oggi una modifica al sorgente innesca un full clear della scena Three.js seguito da un create-three-mesh per ogni mesh registrata. Per scene piccole il costo è invisibile; per scene grandi (decine di mesh, qualcuna con vertex count alto) il delay diventa percepibile.

Il piano di lavoro è un diff per identity/hash mesh fra rebuild successivi: solo le mesh effettivamente cambiate vengono ricreate, le altre restano in scena. L'animation system fa già qualcosa di simile in `apply-mesh-pose!`, ma per rigid transforms via `position`/`quaternion` su Object3D senza ricostruire geometry. Per il caso editor il diff è più sottile perché le mesh possono cambiare in topologia, non solo in posa. Una settimana di lavoro per un primo cut.

### 4.4 Level-of-detail per viewport

Riduzione del dettaglio delle mesh distanti dalla camera. Il modulo `mesh-simplify` esiste, ma non è chiamato dal viewport, e Three.js LOD non è usato. Il blocco è la cache: la decimazione real-time è troppo lenta, quindi serve una cache di mesh decimati pre-calcolati a soglie multiple. Tre-cinque giorni di lavoro più il design della cache.

Come 4.3, è una voce di performance la cui priorità dipende dalla dimensione tipica delle scene utente. Oggi non è urgente. Lo diventerà se il progetto si sposterà verso scene grandi (assemblaggi complessi, moduli architettonici parametrici, decorazioni a lattice generative).

---

## Come si paga la roadmap

Le voci della Parte I sono pagamenti di debito già preso e completamenti di lavori in corso: vanno fatte, e il quando dipende solo dalla cadenza di lavoro. La bonifica degli esempi e la revisione della manualistica sono priorità prima di tutto perché toccano la prima superficie di contatto col progetto, e uno stato di fatto disallineato qui costa più degli altri tipi di disallineamento.

Le voci della Parte II sono direzioni di esplorazione, non promesse. La scelta di quale perseguire e in che ordine dipende da segnali — feedback degli utenti, nuove dipendenze esterne mature, momenti di interesse pubblico per certe aree del progetto. La sequenza fra 2.1 (AI), 2.2 (superfici di edit), 2.3 (animation export), 2.4 (geometria), 2.5 (apertura esterna), 2.6 (librerie) non è prescrittiva.

Le voci della Parte III sono visioni: vivono come direzioni nominate, e prendono concretezza solo quando una decisione editoriale e una serie di prerequisiti convergono. La 3.1 dipende da una svolta di design XR. La 3.2 dipende da una visione editoriale matura. La 3.3 dipende da maturazione di tecnologia esterna e crescita del corpus pubblico Ridley.

Le voci della Parte IV vivono come opzioni: si attivano se rivalutazione dei vincoli cambia il rapporto costo/beneficio, e fino a quel momento restano nominate.

La roadmap è uno strumento di consapevolezza, non di pianificazione vincolante. Il principio guida è quello del cap. 16 di `Architecture.md`: turtle-centricity come bussola, codice come fonte di verità, astrazioni a soglia. Ogni voce di questo documento, quando arriva il momento di essere pagata, si misura contro questo principio.