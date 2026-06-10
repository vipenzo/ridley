;; Cinque modi per disegnare lo STESSO spigolo arrotondato
;; ========================================================
;; Un profilo a "L" con angolo arrotondato: la curva va da (0,0) a (a,a),
;; tangente orizzontale alla partenza e verticale all'arrivo, e bomba verso
;; la diagonale a 45° (la misura D la controlla). Lo stesso problema, risolto
;; in più modi. I metodi 1, 2 e 4 producono la curva IDENTICA (cubica tarata su
;; D); il 3 è il modo interattivo per ottenerla; il 5 è una variante a
;; control-polygon (parabola, stesse tangenti ma bow diverso).
;; Ogni muretto porta un righello :center→:apex che misura il bombaggio REALE
;; sulla diagonale: 1/2/4 leggono D=51, il 5 un po' meno (~48.8).

(def a 45)   ; lato: la curva va da (0,0) a (a,a)
(def D 51)   ; misura sulla diagonale a 45°

;; Scaffold condiviso: la "L" NON arrotondata. I due mark danno gli estremi
;; e — dai loro heading — le direzioni tangenti: orizzontale in :start
;; (heading +x), verticale in :end (dopo th 90, heading +y).
(def ps (path (mark :start) (f a) (th 90) (f a) (mark :end)))

;; Scaffold della diagonale: :center sull'angolo (0,a) e :D, il bersaglio a
;; distanza D sulla diagonale a 45°. È un side-trip — non aggiunge geometria,
;; lascia solo i mark. Il `th 90` dopo aver raggiunto :D gli dà l'heading +45°
;; (la tangente della curva nell'apice): così :D non è solo una posizione ma un
;; anchor con direzione, a cui mirare con bezier-to-anchor / edit-bezier (4.bis).
(def diag (path (mark :start)
                (side-trip (th 90) (f a) (th -135) (mark :center) (f D) (th 90) (mark :D))))

;; Da una curva-centro al muretto estruso e MISURATO, per confrontarle a parità
;; di tutto. wall fa tre cose, identiche per ogni metodo:
;;  - antepone `diag` (lo scaffold con :center e :D), via follow-path: il side-trip
;;    viaggia con la curva senza aggiungere geometria;
;;  - (add-mark … :apex 0.5) marca il punto di mezzo REALE della curva — l'apice;
;;  - estrude, posa a `dx` e tira il righello :center → :apex (il bombaggio vero,
;;    che cala se la curva non arriva a D). dx è opzionale (default 0).
;; I mark del profilo diventano anchor della mesh; il righello li rilegge sulla
;; mesh piazzata, così resta agganciato anche dopo attach/mesh-union.
(defn wall
  ([curve] (wall curve 0))
  ([curve dx]
   (let [profile (add-mark (path (follow-path diag)
                                 (follow-path curve))
                           :apex 0.5)
         w (attach (extrude (stroke-shape profile 3) (f 10)) (rt dx))]
     (ruler w :at :center w :at :apex)
     w)))

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
;;  produrrebbe disegnando solo la metà; vedi il 4.bis.)
(def half (path (bezier-to [36.06 8.94 0] [18.09 0 0] [29.34 2.21 0])))
(def curve-4 (path (follow-path half)
                   (follow-path (reverse-path (mirror-path half)))))

;; --- 4.bis. edit-bezier per la metà ----------------------------------------
;; La metà O→:D si può autorare a occhio come nel metodo 3, ma mirando a :D
;; (l'apice) invece che a :end. È qui che serve un :D RAGGIUNGIBILE: `diag` lo
;; espone già con l'heading +45° (la tangente nell'apice), così edit-bezier ha
;; estremi e tangenti fissi e resta un solo numero, la tension. Mentre regoli, il
;; preview ricompila tutto: la curva intera (half + mirror) e il righello
;; :center→:apex salgono verso D=51. Invio riscrive in
;; (bezier-to-anchor diag :at :D :tension …). Decommenta il blocco:
;;
;; (def half (path (edit-bezier diag :at :D :symmetric)))
;; (def curve-4 (path (follow-path half)
;;                    (follow-path (reverse-path (mirror-path half)))))
;; (register corner (wall curve-4))

;; --- 5. control polygon: bezier-as :control --------------------------------
;; bezier-as di solito interpola i vertici (curva PER i vertici). Con :control
;; li tratta come punti di CONTROLLO: la curva passa per i punti medi dei segmenti,
;; tangente lì, e arrotonda il poligono (duale dell'interpolazione).
;; Qui il control-polygon è simmetrico con UN solo parametro libero: x = lunghezza
;; delle due gambe (accoppiate dallo stesso simbolo), mentre il bevel a 45° y è
;; DERIVATO perché la curva chiuda esattamente su (a,a). Cambiando x cambi il bow,
;; mantenendo simmetria ed estremi — tweakalo a slider e guarda il righello
;; :center→:apex (montato da wall) scendere sotto D quando bombi di meno.
;; È una quadratica a tratti: stesse tangenti delle altre, bow un filo diverso.
(def curve-5
  (let [x 24                       ; unico parametro libero (le gambe, accoppiate)
        y (* (- a x) (sqrt 2))     ; bevel a 45° derivato: chiude su (a,a)
        poly (path (f x) (th 45) (f y) (th 45) (f x))]
    (path (bezier-as poly :control true))))

;; Affianca i muretti (80mm l'uno dall'altro) e misura ognuno: 1, 2, 4 toccano la
;; diagonale a D=51; il 5 bomba meno (il righello lo legge ~48.8). wall posa a dx,
;; tira il righello e torna la mesh per l'union.
(register confronto
          (mesh-union
           [(wall curve-1 0)
            (wall curve-2 80)
            (wall curve-4 160)
            (wall curve-5 240)]))

;; --- 3. edit-bezier: nessun calcolo, a occhio ------------------------------
;; Decommenta e lancia dal pannello definizioni (Cmd+Enter). Apre l'editor:
;; ↑↓ regolano la tension: il righello :center→:apex (montato da wall) sale verso
;; D=51 man mano che la curva tocca la diagonale; Invio conferma.
;; Alla conferma la chiamata si riscrive proprio nella forma del metodo 2,
;; (bezier-to-anchor ps :at :end :tension …) — quindi edit-bezier È il modo
;; interattivo di ottenere il metodo 2 senza calcoli. Con :symmetric c'è una
;; sola tension; senza, due manici indipendenti (Tab li alterna).
;;
;; (register supporto (wall (path (edit-bezier ps :at :end :symmetric))))
