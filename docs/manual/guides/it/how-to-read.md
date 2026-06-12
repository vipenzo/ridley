<!--
=========================================================================
NOTE INTERNE PER L'AUTORE — NON DESTINATE AL LETTORE FINALE

Pagina di orientamento, fuori numerazione (come about-ridley).
Origine: manual-redesign-plan §3.0 (mappa per fasi di lavoro, mai
pubblicata prima) + decisione 2026-06-10 sui percorsi di lettura.

- La mappa riproduce §3.0 del piano, senza il cap. 18 (non esiste ancora).
- I cinque percorsi sono la risposta al problema "a chi è rivolto il
  manuale": invece di un lettore unico, cinque itinerari dichiarati.
  Il quinto (il primo nell'ordine della pagina) è il curioso che deve
  ancora decidere se Ridley gli interessa: il suo percorso non è fatto
  di capitoli ma di esempi eseguibili.
- Il tour del curioso punta la lampada traforata e il cesto intrecciato
  al cap. 6, dove shell e woven-shell ora vivono (spostate dal cap. 4 il
  2026-06-10, brief dev-docs/brief-shapefn-move.md).
- I riferimenti ai livelli (base/intermedio/avanzato) usano i marker
  `<!– level: … –>` nei capitoli; il rendering dei badge è implementato
  e verificato (Code, 2026-06-11, brief dev-docs/brief-level-badges.md).
=========================================================================
-->

# Come leggere questo manuale

Questo manuale ha due anime. Le **Guide** sono i capitoli numerati: prosa che si legge, con esempi eseguibili, pensata per imparare. La **Reference** è l'insieme delle schede per funzione: densa, fattuale, pensata per consultare. Le due si parlano: nella prosa delle guide i nomi delle funzioni sono link alle schede, e nell'editor stesso i simboli che digiti mostrano un suggerimento con la firma e un collegamento alla scheda completa. Quando ti serve sapere *come si usa* qualcosa, la scheda è più veloce della guida; quando ti serve capire *quando e perché*, è il contrario.

E poi c'è la cosa che rende questo manuale diverso da un libro: **quasi ogni esempio si esegue**. Il bottone accanto a un blocco di codice lo valuta sul posto, e il risultato compare nel viewport; il bottone "Edit" apre lo stesso esempio in un nuovo Workspace, dove puoi modificarlo, romperlo e ripartire senza toccare il tuo lavoro. Leggere e basta è il modo peggiore di usare queste pagine.

I capitoli sono numerati, ma la numerazione è un ordine didattico di massima, non un percorso obbligato. Nessuno ha bisogno di tutto il manuale per usare Ridley, e lettori diversi hanno bisogno di pezzi diversi in ordini diversi. Qui sotto trovi prima una mappa dei capitoli per fase di lavoro, poi cinque itinerari consigliati a seconda di da dove arrivi.

## La mappa

```
Imparare       1.  Per iniziare
               16. Clojure per Ridley

Materia prima  3.  Lavorare con le forme 2D
               5.  Path: registrare il movimento
               7.  Mesh

Costruire      2.  Modellare per primitive
               4.  Estrusione
               6.  Da funzioni matematiche a forme
               12. Lavorare con gli SDF

Comporre       8.  Assemblaggio
               11. Curve avanzate

Riusare        9.  Workspaces e Librerie

Capire         10. Analizzare e misurare
               15. Mettere a fuoco e risolvere i problemi

Curare         13. Testo
               14. Colore e materiali

Concludere     17. Esportare e stampare
```

La mappa si legge così: i capitoli "Materia prima" descrivono i dati con cui Ridley lavora (forme 2D, percorsi, mesh), quelli "Costruire" le tecniche che trasformano quei dati in solidi, e via via fino all'export. I numeri non seguono le fasi perché la sequenza didattica è un'altra cosa: per esempio il cap. 2 (primitive) viene prima del 3 (forme 2D) perché è più gratificante costruire subito qualcosa di solido, anche se concettualmente le forme 2D sono materia prima.

## Cinque lettori, cinque percorsi

### Vuoi capire se Ridley fa per te

Non hai ancora deciso se vale la pena impararlo: vuoi vedere cosa fa. Allora non leggere: **esegui**. Apri un capitolo qualsiasi, premi il bottone accanto a un esempio e guarda il viewport. Se preferisci un giro guidato, questi sei esempi raccontano bene di cosa è capace il sistema: la **barchetta a vela** del cap. 2 (poche primitive e quattro operazioni che diventano un oggetto riconoscibile), il **tavolino esagonale** del cap. 5 (un unico path che fa insieme da profilo per il piano *e* da scheletro per i piedi: un dato, due usi, nessuna possibilità che divergano), la **lampada traforata e il cesto intrecciato** del cap. 6 (superfici decorate descritte da funzioni), il **portachiavi a parete** del cap. 8 (un oggetto vero, parametrico, da stampare e usare), il **testo che sale in spirale** del cap. 13, e le **forme che si fondono** del cap. 12 (gli SDF e i loro raccordi morbidi). Su ognuno, "Edit" ti lascia cambiare un numero e rieseguire: è lì che capisci se questo modo di modellare ti somiglia. Se la risposta è sì, scegli qui sotto il percorso che corrisponde a da dove vieni.

### Non hai mai modellato in 3D

Magari non hai mai nemmeno programmato. Il tuo percorso è il più lineare: **1 → 2 → 3 → 4 → 17**. Il cap. 1 ti mette le mani sull'interfaccia e sulla tartaruga, il 2 ti fa costruire i primi oggetti veri, il 3 e il 4 aggiungono le forme libere e l'estrusione, il 17 ti porta alla stampa. Quando il codice inizia a starti stretto, il cap. **16** (Clojure) consolida le basi che il cap. 1 ti ha dato in pillole. Da lì, **5** (i percorsi) e **10** (misurare) sono i prossimi passi naturali. Lascia per un secondo giro i capitoli 6, 7, 11 e 12: nessuno di loro serve per i primi venti oggetti.

### Vieni da OpenSCAD o da un altro CAD a codice

Conosci già l'idea di modello-come-programma, ti serve la mappa delle differenze. Leggi `about-ridley`, poi del cap. **1** solo le sezioni su interfaccia e tartaruga (il paradigma tartaruga è la differenza grossa rispetto al sistema di coordinate globali di OpenSCAD). Nel cap. **2** vai dritto alla sezione finale "Per chi viene da un CAD tradizionale": `translate`, `rotate` e `scale` esistono e funzionano come ti aspetti, ma lì capisci quanto `attach` e i comandi locali sono più espressivi. Poi **3 → 4 → 5 → 8** in ordine: forme, estrusione, percorsi con i marcatori, assemblaggio. I marcatori e gli skeleton del cap. 8 sono la cosa che OpenSCAD non ha e che ripaga prima lo studio. Il 7 e il 10 li apri quando ti servono.

### Vieni da un CAD a sketch (Fusion, SolidWorks, FreeCAD)

Per te lo scoglio non è la sintassi, è un cambio di modello mentale: in Ridley non ci sono sketch ancorati a piani, non ci sono vincoli, non c'è la timeline. La porta d'ingresso giusta è la sezione **"Profili come valori"** del cap. 3, che affronta esattamente questa differenza. Da lì leggi il cap. **3** per intero, poi **4** (l'estrusione è il tuo terreno familiare), **5** (i `mark` nei path fanno il lavoro che nei CAD a sketch fanno i vincoli e i piani di riferimento) e **8** (gli skeleton sono l'equivalente degli assiemi). Il cap. **10** ti restituisce le quote e le misure a cui sei abituato.

### Vuoi stampare qualcosa stasera

Mezz'ora, un oggetto sul piatto. Leggi la prima sezione del cap. **1** (giusto per sapere dove si scrive e cosa premere), poi le prime tre sezioni del cap. **2**: primitive, composizione, e il portapenne, che è già un oggetto utile e stampabile. Salta al cap. **17**, sezione STL, ed esporta. Fine del percorso breve. Quando l'oggetto è sul piatto, torna indietro e riparti da uno degli altri itinerari.

## I livelli

Ogni capitolo dichiara in testa il proprio livello: **base**, **intermedio** o **avanzato**. Dove un capitolo mescola livelli diversi, lo dichiarano le singole sezioni: capita spesso che un capitolo base abbia una coda avanzata (l'ultima sezione del cap. 4, per esempio) o che un capitolo avanzato abbia un'apertura accessibile. Il livello non misura quanto è difficile la lettura, ma quanto puoi rimandarla: "avanzato" significa "puoi costruire molto senza questo", non "incomprensibile".

## Gli esempi

Quasi tutti i blocchi di codice nelle guide sono eseguibili, come detto sopra. Due avvertenze pratiche: alcuni esempi sono marcati come lenti (calcoli di diversi secondi, tipicamente gusci e intrecci ad alta risoluzione), e pochi non sono eseguibili direttamente perché aprono sessioni interattive (`tweak`, `pilot`, `edit-bezier`): in quei casi il testo lo dice. Per tutto il resto vale la regola di prima: se un esempio ti incuriosisce, premi Edit e mettici le mani.
