Macro:
A - Misurazioni precise
  1. Misure da REPL (estensione di quello che hai)
    Questo è il più naturale per la filosofia di Ridley — codice come interfaccia:
    clojure;; Distanza tra due punti/oggetti
    (distance :box1 :box2)              ; tra centroidi
    (distance :box1 :top :box2 :bottom) ; tra facce specifiche

    ;; Quota annotata nel viewport (linea + testo 3D)
    (measure :box1 :box2)               ; mostra ruler 3D tra i due oggetti
    (measure-face :box1 :top)           ; mostra dimensioni della faccia

    ;; Ruler libero da coordinate
    (ruler [0 0 0] [100 0 0])           ; linea quotata tra due punti
    (ruler :box1 :top [0 0 50])         ; da faccia a punto

    ;; Cancella
    (clear-measures)
    I ruler sarebbero oggetti Three.js overlay (come i tuoi highlight) — linee con frecce alle estremità e un pannello di testo 3D con la dimensione. Potresti renderizzarli su un layer separato che non viene esportato in STL.
  2. Measure interattivo via Alt+Click
    Hai già il picking con Alt+Click per il source tracing. Potresti estendere il pattern:

    Shift+Click su un punto → piazza un marker
    Shift+Click su un secondo punto → mostra ruler tra i due
    Shift+Click su una faccia → mostra area e dimensioni
    Esc → cancella misura temporanea

    L'implementazione userebbe il raycaster Three.js che presumo hai già per il picking. Il risultato potrebbe anche stampare la misura nel REPL, così hai il valore copiabile.
Dettagli implementativi
  Per il rendering dei ruler, penserei a:

    Linea con frecce SVG-style alle estremità (o piccole mesh cone)
    Testo via i tuoi panel 3D o sprite sempre rivolti alla camera (billboard)
    Layer separato — renderizzato dopo la scena, con depth test disattivato così è sempre visibile
    Unità configurabili: (units :mm) o (units :cm) — moltiplicatore + suffisso nel testo
    Snap opzionale: ai vertici, centri faccia, spigoli — usando il raycast esistente

    La cosa bella è che si integra naturalmente con tweak — potresti avere un ruler che si aggiorna live quando muovi uno slider.




E - Litofanie

F - Libreria Viti/Threads

G - Estensione libreria gears ai denti angolati e ai gear interni - trovare esempio.

