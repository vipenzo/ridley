;; Ridley Library: joints

;; =========================
;; Joints: giunzioni stampabili per pezzi tagliati con mesh-split
;; =========================
;;
;; Convenzione comune a tutti gli helper:
;; - la mesh viene tagliata alla posa corrente della tartaruga
;;   (piano = posizione, normale = heading), come mesh-split;
;; - l'asse della giunzione e' l'heading, il centro e' la posizione;
;; - il risultato e' {:a mesh :b mesh :extras [mesh ...]}
;;   :a = lato :ahead (porta la parte maschio), :b = lato :behind
;;   (porta la parte femmina), :extras = pezzi da stampare a parte;
;; - t e' il gioco (clearance) per lato, in mm: 0.2 e' un buon default
;;   per PLA; il gioco totale sul diametro e' 2t.
;;
;; Tutte le geometrie di giunzione sono costruite a cavallo del piano
;; di taglio, simmetriche rispetto ad esso: la meta' che cade dentro il
;; materiale di un pezzo e' un no-op per l'unione, e la differenza agisce
;; solo dall'altro lato. Cosi' nessun helper deve ragionare sul verso
;; dell'heading, e le stesse primitive servono per :a e per :b.
;;
;; Stato (2026-09-27): tenon, dowel, bayonet e thread verificati allo
;; schermo da Vincenzo con le sezioni di mesh-board (thread: sense 1 e'
;; destrorso). Nessuno e' ancora stato STAMPATO: giochi e tenuta della
;; baionetta si giudicano solo sul pezzo.

(defn deg->rad [d] (* d (/ PI 180.0)))

;; ---------- tenone ----------

(defn tenon
  "Tenone integrale: perno cilindrico su :a, foro cieco su :b.
   r raggio nominale, l lunghezza totale del cilindro (meta' sporge dal
   piano di taglio), t gioco per lato."
  [m r l t]
  (let [{a :ahead b :behind} (mesh-split m)
        peg  (cyl (- r t) (- l t t))
        hole (cyl (+ r t) (+ l t t))]
    {:a (mesh-union a peg)
     :b (mesh-difference b hole)
     :extras []}))

;; ---------- spina (dowel) ----------

(defn dowel
  "Spina cilindrica stampata a parte: fori identici su :a e :b, la spina
   in :extras. Stessi parametri di tenon. I due pezzi restano senza
   sporgenze, comodi da stampare con la faccia di taglio sul piatto."
  [m r l t]
  (let [{a :ahead b :behind} (mesh-split m)
        peg  (cyl (- r t) (- l t t))
        hole (cyl (+ r t) (+ l t t))]
    {:a (mesh-difference a hole)
     :b (mesh-difference b hole)
     :extras [peg]}))

;; ---------- baionetta ----------

(defn bayonet
  "Innesto a baionetta. Maschio su :a: spinotto di raggio r che sporge di
   depth, con n nottolini radiali vicino alla punta. Femmina su :b: foro
   con n scanalature a L (tratto assiale dal piano fino al nottolino, poi
   tratto circonferenziale di angle gradi). Si infila con i pezzi ruotati
   di angle e si gira per agganciare: in chiusura i due pezzi tornano
   ALLINEATI (il canale assiale sta angle dopo il nottolino, l'arco
   torna indietro e finisce sotto il nottolino).
   opts: :n 2 nottolini, :lug 2 sporgenza radiale del nottolino,
   :lr 1.5 raggio del nottolino, :angle 45 gradi di rotazione,
   :sense 1 verso del tratto circonferenziale (verificato allo schermo
   2026-09-27: canale a +angle dal nottolino, arco che torna sotto il
   nottolino); -1 per l'altro verso."
  [m r depth t & {:keys [n lug lr angle sense]
                  :or {n 2 lug 2 lr 1.5 angle 45 sense 1}}]
  (let [{a :ahead b :behind} (mesh-split m)
        d      (- depth lr 1)                 ; nottolino a 1 mm dalla punta
        plug   (cyl r (* 2 depth))
        bore   (cyl (+ r t) (* 2 (+ depth t)))
        ;; tratto assiale: dal piano (con 1 mm di margine) al nottolino
        len    (+ d lr t 1)
        ;; tratto circonferenziale al raggio r: angle gradi piu' il margine
        ;; perche' il NOTTOLINO INTERO entri in sede (l'arco fermo sul centro
        ;; del nottolino ne lasciava meta' fuori — Vincenzo 2026-09-27)
        margin (* (/ 180 PI) (/ (+ lr t) r))
        sweep  (+ angle margin)
        segs   (max 8 (int (/ sweep 5)))
        step   (/ sweep segs)
        chord  (* 2 r (sin (/ (deg->rad step) 2)))
        phi    (fn [i] (* i (/ 360 n)))
        ;; rotazione di chiusura: l'arco, dalla posa (tv 90), avanza nel verso
        ;; in cui phi DIMINUISCE (misurato 2026-09-27: partendo da phi-45 la
        ;; sede finiva a phi-90), quindi canale e inizio arco stanno a phi+lock
        ;; e l'arco torna a phi e lo supera di margin, cosi' il nottolino
        ;; riposa tutto dentro.
        lock   (* sense angle)
        lugs   (vec (for [i (range n)]
                      (turtle (tr (phi i)) (f (- d)) (rt r) (th 90)
                              (cyl lr (* 2 lug)))))
        chans  (vec (for [i (range n)]
                      (turtle (tr (+ (phi i) lock)) (f (- (- (/ len 2) 1))) (rt r) (th 90)
                              ;; dopo th 90: forward = radiale, right = asse, up = up
                              (box len (* 2 (+ lr t)) (* 2 (+ lug t))))))
        arcs   (vec (for [i (range n)]
                      (turtle (tr (+ (phi i) lock)) (f (- d)) (rt r) (tv 90)
                              ;; dopo tv 90: heading tangenziale, up = asse,
                              ;; right = radiale
                              (extrude (rect (* 2 (+ lug t)) (* 2 (+ lr t)))
                                    (path (dotimes [_ segs]
                                            (f chord) (th (* sense step))))))))]
    {:a (mesh-union (into [a plug] lugs))
      :b (mesh-difference (into [b bore] (concat chans arcs)))
      :extras []}))

;; ---------- filetto ----------

(defn thread
  "Filetto a sezione quadra. Maschio su :a: nucleo di raggio r con dente
   di altezza h; femmina su :b: foro filettato. l = lunghezza totale del
   maschio (meta' sporge), t gioco per lato.
   opts: :pitch 2 passo, :h 1 altezza del dente, :sense 1 destrorso
   (verificato allo schermo 2026-09-27), -1 sinistrorso.
   L'elica e' una curva a curvatura e torsione costanti, quindi si scrive
   con i soli comandi che extrude segue: (f ds) (th k*ds) (tr tau*ds) —
   un (u dz) per passo NON avanza (2026-09-27: extrude costruisce il
   binario dai soli tratti f e il dente usciva un anello piatto).
   Costruita a cavallo del piano come tutto il resto: i giri che cadono
   dentro :a sono un no-op per l'unione."
  [m r l t & {:keys [pitch h sense] :or {pitch 2 h 1 sense 1}}]
  (let [{a :ahead b :behind} (mesh-split m)
        n      32                              ; passi per giro
        w      (* 0.5 pitch)                   ; larghezza assiale del dente
        steps  (int (* n (/ l pitch)))
        ;; elica di raggio rr e passo pitch: c = pitch/2pi,
        ;; curvatura k = rr/(rr^2+c^2), torsione tau = c/(rr^2+c^2),
        ;; angolo d'elica alpha = atan(c/rr); un passo ds = 2pi*sqrt(rr^2+c^2)/n
        helix  (fn [rr]
                 (let [c   (/ pitch (* 2 PI))
                       q   (+ (* rr rr) (* c c))
                       ds  (/ (* 2 PI (sqrt q)) n)
                       dth (* (/ 180 PI) (/ rr q) ds)
                       dtr (* sense (/ 180 PI) (/ c q) ds)]
                   (path (dotimes [_ steps]
                           (f ds) (th dth) (tr dtr)))))
        alpha  (* sense (/ 180 PI) (atan2 (/ pitch (* 2 PI)) r))
        ;; frame: dopo (tv 90) up = -asse (l'heading iniziale era l'asse),
        ;; right = radiale; (u (* sense (- l/2))) porta all'estremita' da cui
        ;; l'elica avanza (misurato: con sense 1 avanza verso -asse);
        ;; (tv alpha) inclina l'heading dell'angolo d'elica.
        ridge  (turtle (tv 90) (u (* sense (- (/ l 2)))) (rt r) (tv alpha)
                       (extrude (rect (* 2 h) w) (helix r)))
        groove (turtle (tv 90) (u (* sense (- (/ l 2)))) (rt (+ r t)) (tv alpha)
                       (extrude (rect (* 2 h) (+ w t t)) (helix (+ r t))))
        core   (cyl r l)
        bore   (cyl (+ r t) (+ l t t))]
    {:a (mesh-union a core ridge)
     :b (mesh-difference b bore groove)
     :extras []}))

;; ---------- esempi d'uso (da incollare nell'editor, non qui) ----------
(comment
  (def halves (mesh-split (box 40 60 80)))
  (def L (layout-anchors (:behind halves) :cut :inset 5 :spacing 10))


  ; pins
  (register pins (on-anchors L "pin" :align (cyl 3 10)))
  (register B (mesh-difference (:behind halves) pins))
  (register A (mesh-difference (:ahead halves) pins))
  (mesh-board {:A A :B B :pins pins} {:solid true :views [[:section :cut :offset 3]]})



  ; tenon
  (def thing (box 40 40 40))
  (def ab (tenon thing 5 20 0.2))
  (register A (attach (:a ab)))
  (register B (attach (:b ab)))
  (mesh-board {:A A,:B B} {:solid true,:views [[:section :cut :offset 3]]})


  ; dowel
  (def ab (dowel (box 40 40 40) 5 20 0.2))
  (register A (attach (:a ab)))
  (register B (attach (:b ab)))
  (register C (first (:extras ab)))
  (mesh-board {:A A,:B B :pin C} {:solid true,:views [[:section :cut :offset 3]]})

  ;bayonet
  (def ab (bayonet (box 40 40 40) 8 12 0.3))
  (register A (attach (:a ab)))
  (register B (attach (:b ab)))
  (mesh-board {:A A,:B B} {:solid true,:views [[:section :cut :offset 3]]})

  (u 100)
  ;thread
  (def ab (thread (box 40 40 40) 8 24 0.25 :pitch 2.5))
  (register A4 (attach (:a ab) (rt -40)))
  (register B4 (attach (:b ab) (rt 40)))


  (tweak (mesh-board {:A A :B B :pins pins} {:solid true :views [[:section :cut :offset 3]]}))

  )