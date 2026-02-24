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

B - Voronoi procedurale come shape-fn
  L'idea sarebbe:
  Generare un pattern Voronoi 2D sulla shape del profilo
  Usare le celle Voronoi per creare una shape "traforata" (bordi delle celle = materiale, interni = vuoto)
  Estrudere con loft e una shape-fn che ruota/varia il pattern lungo il percorso

  clojure;; Concept (nuova API)
  (register voronoi-tube
    (loft (voronoi-shell (circle 20) :cells 40 :wall 1.5)
      (f 100)))
  Questo richiederebbe di implementare Voronoi 2D (tramite Delaunay → duale) e poi shape-offset per creare i "muri" tra le celle. Con Clipper2 che hai già, la parte di offset e boolean 2D è coperta.
  Implementare Voronoi 2D — Delaunay triangulation → dual graph. Ci sono librerie JS come d3-delaunay che puoi importare
  voronoi-shape — nuova primitiva che genera una shape traforata da un set di punti seed dentro un contorno
  Come shape-fn — i seed si spostano leggermente lungo t, dando quella variazione organica che si vede nella stampa
  bloft per path complesse — visto che il profilo cambia ad ogni step


D - Import STL

E - Litofanie

F - Libreria Viti/Threads

