# Cos'è Ridley

Ridley è un programma di CAD (Computer Aided Design). Potremmo tradurre CAD con "Progettazione assistita da un computer". In soldoni serve a disegnare oggetti tridimensionali, destinati prevalentemente alla stampa 3D.

Di programmi che fanno questo ne esistono già decine, perché crearne uno nuovo? Ridley ha una serie di aspetti che lo differenziano da altri approcci.

## Basato sul testo

La maggior parte dei programmi di questo tipo prevede un'interazione che permette all'utente di lavorare direttamente sull'oggetto 3D in fase di disegno. Spostarlo, modificarne le dimensioni o altri aspetti è un'operazione che si fa cliccando sull'oggetto stesso e muovendo il mouse, o selezionando voci da menu contestuali che appaiono una volta selezionato l'oggetto o una sua parte (facce, spigoli, vertici). La creazione stessa dell'oggetto è basata sul mouse: trascinamento di forme elementari da una palette, per esempio.

Tutto questo non succede in Ridley: l'utente vede l'oggetto ma non l'ha creato, né lo può modificare, con il mouse. L'immagine 3D che l'utente vede è il risultato dell'esecuzione di un programma, che l'utente scrive e modifica nel pannello di sinistra dell'interfaccia (editor). Questo livello di mediazione tra te e il modello può essere percepito come un costo, soprattutto da chi viene da programmi mouse-oriented, ma si rivela uno dei suoi maggiori punti di forza in un uso assiduo.

Il testo che compone il programma si gestisce come qualunque altro testo: si apre in qualunque editor, si versiona, si cerca al suo interno, si confronta tra versioni differenti per capire cosa è cambiato, si manda per email, conserva la storia del progetto. Tutte cose difficili o impossibili con altri approcci.

Ridley non è l'unico CAD che adotta questa scelta. Il più famoso in questa categoria è OpenSCAD: chi viene da lì troverà un cugino, ma un cugino che sa fare qualcosa in più.

## Basato su Clojure

Clojure è un dialetto moderno di Lisp, una famiglia di linguaggi di programmazione tanto vecchia quanto rara da incontrare nei CAD. Per Ridley la scelta è meditata e ha una conseguenza precisa: le funzioni che l'utente scrive sono parte del linguaggio allo stesso titolo dei comandi predefiniti. Non c'è una libreria standard privilegiata da una parte e codice utente dall'altra. Tutto vive sullo stesso piano.

Concretamente: i verbi che compongono Ridley non si distinguono come forma dai verbi base del linguaggio, e l'utente può crearne di suoi, fino a costruire un linguaggio personalizzato specifico per le sue esigenze. Si parte scrivendo `(box 20)` e si finisce, senza che ci sia un confine percepibile, a scrivere `(la-mia-cornice 80 60 12)`, che si comporta esattamente come una primitiva.

## Basato sulla tartaruga

La "Geometria della Tartaruga" è un paradigma proposto da un linguaggio di programmazione nato per insegnare ai bambini a programmare, si chiamava Logo. Il concetto di fondo è di immaginare di dare comandi di movimento a una tartaruga (stilizzata sullo schermo). Comandi come "vai avanti di 5 unità", "gira a destra di 90 gradi". A fronte di questi comandi la tartaruga del Logo si muoveva sullo schermo lasciando traccia del suo percorso (un comando "penna su" o "penna giù" permetteva di muoversi lasciando la traccia o semplicemente di riposizionarsi).

Questo approccio permette di realizzare disegni geometrici senza fare ricorso a sistemi di coordinate esterni: la tartaruga ha sempre il suo sistema di riferimento con sé, ha un suo davanti e dietro, un suo destra e sinistra. Il programma "ripeti 4 volte: avanti di 10, gira a sinistra di 90" produce un quadrato dovunque la tartaruga si trovi.

Ridley riprende questo concetto e lo espande al mondo tridimensionale: la tartaruga può andare avanti e indietro, spostarsi di lato o in alto e basso, può ruotare orizzontalmente, verticalmente o intorno al proprio asse (roll). È diventata un piccolo aereo che si muove nello spazio, o una tartaruga marina (il nome Ridley viene da una specie di queste creature) che nuota nel mare. In Ridley il concetto di tartaruga è usato in molti ambiti: la tartaruga può tracciare percorsi che possono venire memorizzati e usati per ricordare punti importanti, possono essere usati per creare forme bidimensionali da cui derivare forme solide, possono stabilire come una forma bidimensionale debba essere trascinata per produrre un solido. Comandi tartaruga servono a spostare oggetti, a modificarne le facce, e molto altro.

Molta della programmazione in Ridley si risolve nel sedersi alla cloche di una tartaruga e pilotarla in giro per il modello che sta nascendo.
