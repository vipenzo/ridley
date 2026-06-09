;; Cinque modi per disegnare lo STESSO spigolo arrotondato
;; ========================================================
;; Un profilo a "L" con angolo arrotondato: la curva va da (0,0) a (a,a),
;; tangente orizzontale alla partenza e verticale all'arrivo, e bomba verso
;; la diagonale a 45° (la misura D la controlla). Lo stesso problema, risolto
;; in più modi. I metodi 1, 2 e 4 producono la curva IDENTICA (cubica tarata su
;; D); il 3 è il modo interattivo per ottenerla; il 5 è una variante a
;; control-polygon (parabola, stesse tangenti ma bow diverso).

(def a 45)   ; lato: la curva va da (0,0) a (a,a)
(def D 51)   ; misura sulla diagonale a 45°

;; Scaffold condiviso: la "L" NON arrotondata. I due mark danno gli estremi
;; e — dai loro heading — le direzioni tangenti: orizzontale in :start
;; (heading +x), verticale in :end (dopo th 90, heading +y).
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))

;; Da una curva-centro al muretto estruso, per confrontarle a parità di tutto.
(defn wall [curve] (extrude (stroke-shape curve 3) (f 10)))

;; --- 1. bezier-to: maniglie calcolate risolvendo la cubica -----------------
;; La via "a mano": si risolve l'equazione della cubica per trovare la
;; lunghezza p delle maniglie che fa passare la curva a distanza D sulla
;; diagonale. Funziona, ma richiede la matematica.
(def p (* (/ 4 3) (- (* (sqrt 2) D) a)))            ; ≈ 36.17
(def curve-1 (path (bezier-to [a a 0] [p 0 0] [a (- a p) 0])))

;; --- 2. bezier-to-anchor: una tension al posto delle maniglie --------------
;; Gli estremi e le tangenti vengono dai mark di ps; resta un solo numero, la
;; tension. Qui la ricaviamo da p, ma è l'unico parametro da toccare.
;; La forma path-first (ps :at :end) risolve i mark al volo: niente with-path.
(def t (/ p (* a (sqrt 2))))                         ; ≈ 0.568
(def curve-2 (path (bezier-to-anchor ps :at :end :tension t)))

;; --- 4. metà + mirror: simmetrica per costruzione --------------------------
;; Si disegna solo la metà O→M (M è il punto di mezzo). mirror-path la riflette
;; sul piano di simmetria e reverse-path la rigira per concatenarla con follow-path.
;; Non serve dire qual è l'asse: il path finisce rivolto verso la tangente vera
;; (come a livello turtle), e mirror-path usa quell'heading come normale del piano
;; — cioè il piano right/up della tartaruga lì. La curva esce simmetrica da sola.
;; (`half` è esattamente la prima metà della curva sopra — ciò che edit-bezier
;;  produrrebbe disegnando solo la metà; vedi il metodo 3.)
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4 (path (follow-path half)
                   (follow-path (reverse-path (mirror-path half)))))

;; --- 5. control polygon: bezier-as :control --------------------------------
;; bezier-as di solito interpola i vertici (curva PER i vertici). Con :control
;; li tratta come punti di CONTROLLO: la curva passa per i punti medi dei segmenti,
;; tangente lì, e arrotonda il poligono (duale dell'interpolazione).
;; Qui il control-polygon è simmetrico con UN solo parametro libero: x = lunghezza
;; delle due gambe (accoppiate dallo stesso simbolo), mentre il bevel a 45° y è
;; DERIVATO perché la curva chiuda esattamente su (a,a). Cambiando x cambi il bow,
;; mantenendo simmetria ed estremi — tweakalo a slider, oppure misura col righello
;; e itera:  (ruler [0 a] (mid poly 1))  ; distanza dal punto medio del seg. a 45°.
;; È una quadratica a tratti: stesse tangenti delle altre, bow un filo diverso.
(def curve-5
  (let [x 24                       ; unico parametro libero (le gambe, accoppiate)
        y (* (- a x) (sqrt 2))     ; bevel a 45° derivato: chiude su (a,a)
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))

;; Affianca i muretti (80mm l'uno dall'altro): 1, 2, 4 coincidono; 5 bomba meno.
(register confronto
          (mesh-union
           [(attach (wall curve-1) (rt 0))
            (attach (wall curve-2) (rt 80))
            (attach (wall curve-4) (rt 160))
            (attach (wall curve-5) (rt 240))]))

;; --- 3. edit-bezier: nessun calcolo, a occhio ------------------------------
;; Decommenta e lancia dal pannello definizioni (Cmd+Enter). Apre l'editor:
;; ↑↓ regolano la tension finché la curva tocca la diagonale, Invio conferma.
;; Alla conferma la chiamata si riscrive proprio nella forma del metodo 2,
;; (bezier-to-anchor ps :at :end :tension …) — quindi edit-bezier È il modo
;; interattivo di ottenere il metodo 2 senza calcoli. Con :symmetric c'è una
;; sola tension; senza, due manici indipendenti (Tab li alterna).
;;
;; (register supporto (wall (path (edit-bezier ps :at :end :symmetric))))
