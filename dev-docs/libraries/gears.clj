;; =========================
;; Utilities
;; =========================

(defn deg->rad [d] (* d (/ PI 180.0)))

(defn rotate-pt [[x y] a]
  (let [ca (cos a) sa (sin a)]
    [(- (* x ca) (* y sa))
     (+ (* x sa) (* y ca))]))

(defn mirror-x [[x y]] [x (- y)])

(defn polar [r ang] [(* r (cos ang)) (* r (sin ang))])

(defn rad2 [[x y]] (+ (* x x) (* y y)))
(defn radius [[x y]] (sqrt (rad2 [x y])))

(defn signed-area
  "Signed area of polygon. Positive => CCW winding."
  [pts]
  (let [n (count pts)]
    (/ (reduce
         (fn [acc i]
           (let [[x1 y1] (nth pts i)
                 [x2 y2] (nth pts (mod (inc i) n))]
             (+ acc (- (* x1 y2) (* y1 x2)))))
         0.0
         (range n))
       2.0)))

(defn ensure-ccw [pts]
  (if (neg? (signed-area pts))
    (vec (reverse pts))
    pts))

(defn dedupe-consecutive
  "Remove consecutive duplicate points (within epsilon)."
  [pts]
  (let [eps 1e-6
        eq? (fn [[x1 y1] [x2 y2]]
              (and (< (abs (- x1 x2)) eps)
                   (< (abs (- y1 y2)) eps)))]
    (reduce (fn [acc p]
              (if (and (seq acc) (eq? (peek acc) p))
                acc
                (conj acc p)))
            []
            pts)))

(defn wrap-angle
  "Wrap angle to (-PI, PI]."
  [a]
  (let [two (* 2.0 PI)
        x (mod (+ a PI) two)]
    (- x PI)))

(defn arc-short
  "Arc on radius r from a0 to a1 following the *shortest* angular delta."
  [r a0 a1 n]
  (let [da (wrap-angle (- a1 a0))
        a1' (+ a0 da)]
    (mapv (fn [i]
            (let [t (/ i (double (dec n)))
                  a (+ a0 (* t (- a1' a0)))]
              (polar r a)))
          (range n))))

;; =========================
;; Involute
;; =========================

(defn involute-pt
  "Involute of base circle radius rb, parameter t."
  [rb t]
  (let [ct (cos t) st (sin t)]
    [(* rb (+ ct (* t st)))
     (* rb (- st (* t ct)))]))

(defn involute-curve
  "Return n points of involute from base circle rb to radius rTarget (>= rb)."
  [rb rTarget n]
  (let [tmax (sqrt (- (pow (/ rTarget rb) 2) 1.0))]
    (mapv (fn [i]
            (let [t (* tmax (/ i (double (dec n))))]
              (involute-pt rb t)))
          (range n))))

(defn involute-rot
  "Compute rotation for the CW involute so that at pitch radius r
   the flank is at angular position 'half-ang'."
  [rb r half-ang]
  (let [t_pitch (sqrt (- (pow (/ r rb) 2) 1.0))
        [px py] (involute-pt rb t_pitch)
        phi (atan2 py px)]
    (+ half-ang phi)))

;; =========================
;; Tooth shapes
;; =========================

(defn tooth-sector-shape
  "Full tooth sector: from root radius to outer radius.
   Uses CW involute so the tooth correctly narrows at the tip."
  [& {:as opts}]
  (let [z (:teeth opts)
        m (:module opts)
        pressure-angle (double (or (:pressure-angle opts) 20.0))
        backlash (double (or (:backlash opts) 0.0))
        nfn (int (or (:fn opts) 10))
        arcfn (int (or (:arcfn opts) 8))

        alpha (deg->rad pressure-angle)
        r  (/ (* m z) 2.0)          ; pitch radius
        rb (* r (cos alpha))        ; base radius
        ra (+ r m)                  ; outer radius
        rf (- r (* 1.25 m))         ; root radius

        tau (/ (* 2.0 PI) z)
        backlash-angle (if (pos? r) (/ backlash r) 0.0)
        half-ang (- (/ tau 4.0) (/ backlash-angle 2.0))

        rot (involute-rot rb r half-ang)

        ;; CW involute (mirror before rotate) gives correct narrowing
        upper (mapv #(rotate-pt % rot) (mapv mirror-x (involute-curve rb ra (max 6 nfn))))
        lower (mapv mirror-x upper)

        base-u (first upper)
        base-l (first lower)
        outer-u (last upper)
        outer-l (last lower)

        ang-b (atan2 (second base-u) (first base-u))
        root-u (polar rf ang-b)
        root-l (polar rf (- ang-b))

        ang-ou (atan2 (second outer-u) (first outer-u))
        ang-ol (atan2 (second outer-l) (first outer-l))

        tip (arc-short ra ang-ol ang-ou (max 3 arcfn))

        pts (-> (vec (concat
                       [root-l base-l]
                       (rest lower)
                       (rest tip)
                       (rest (reverse upper))
                       [base-u root-u]))
                dedupe-consecutive
                ensure-ccw)]
    (make-shape pts)))

(defn tooth-cap-shape
  "Tooth cap only: from pitch circle to outer radius.
   Uses CW involute so the tooth correctly narrows at the tip."
  [& {:as opts}]
  (let [z (:teeth opts)
        m (:module opts)
        pressure-angle (double (or (:pressure-angle opts) 20.0))
        backlash (double (or (:backlash opts) 0.0))
        nfn (int (or (:fn opts) 12))
        arcfn (int (or (:arcfn opts) 10))

        alpha (deg->rad pressure-angle)
        r  (/ (* m z) 2.0)          ; pitch radius
        rb (* r (cos alpha))        ; base radius
        ra (+ r m)                  ; outer radius

        tau (/ (* 2.0 PI) z)
        backlash-angle (if (pos? r) (/ backlash r) 0.0)
        half-ang (- (/ tau 4.0) (/ backlash-angle 2.0))

        rot (involute-rot rb r half-ang)

        ;; CW involute (mirror before rotate) gives correct narrowing
        full (mapv #(rotate-pt % rot) (mapv mirror-x (involute-curve rb ra (max 8 nfn))))
        idx (or (first (keep-indexed (fn [i p] (when (>= (radius p) r) i)) full)) 0)
        upper (subvec full idx)
        lower (mapv mirror-x upper)

        pitch-u (first upper)
        pitch-l (first lower)

        outer-u (last upper)
        outer-l (last lower)

        ang-ou (atan2 (second outer-u) (first outer-u))
        ang-ol (atan2 (second outer-l) (first outer-l))

        tip (arc-short ra ang-ol ang-ou (max 3 arcfn))

        pts (-> (vec (concat
                       [pitch-l]
                       (rest lower)
                       (rest tip)
                       (rest (reverse upper))
                       [pitch-u]))
                dedupe-consecutive
                ensure-ccw)]
    (make-shape pts)))

;; =========================
;; Tooth meshes (extruded)
;; =========================

(defn tooth-sector
  "Extruded full tooth sector (root to outer)."
  [& {:as opts}]
  (let [thk (double (or (:thickness opts) 8.0))]
    (extrude (apply tooth-sector-shape (mapcat identity opts)) (f thk))))

(defn tooth-cap
  "Extruded tooth cap (pitch to outer)."
  [& {:as opts}]
  (let [thk (double (or (:thickness opts) 8.0))]
    (extrude (apply tooth-cap-shape (mapcat identity opts)) (f thk))))

;; =========================
;; Full gear
;; =========================

(defn- center-thickness-x
  "Shift mesh by -thk/2 along heading to center on the YZ plane."
  [mesh thk]
  (attach mesh (f (- (/ thk 2.0)))))

(defn gear-profile
  "Full gear cross-section as a single 2D polygon.
   Returns a vector of [x y] points (CCW winding) with all involute
   teeth and root circle arcs. No boolean operations needed."
  [& {:as opts}]
  (let [z (:teeth opts)
        m (:module opts)
        pressure-angle (double (or (:pressure-angle opts) 20.0))
        backlash (double (or (:backlash opts) 0.0))
        nfn (int (or (:fn opts) 10))
        arcfn (int (or (:arcfn opts) 8))

        alpha (deg->rad pressure-angle)
        r  (/ (* m z) 2.0)
        rb (* r (cos alpha))
        ra (+ r m)
        rf (- r (* 1.25 m))

        tau (/ (* 2.0 PI) z)
        backlash-angle (if (pos? r) (/ backlash r) 0.0)
        half-ang (- (/ tau 4.0) (/ backlash-angle 2.0))

        rot (involute-rot rb r half-ang)

        ;; One tooth's involute curves at angle 0
        upper (mapv #(rotate-pt % rot) (mapv mirror-x (involute-curve rb ra (max 6 nfn))))
        lower (mapv mirror-x upper)

        ang-b  (atan2 (second (first upper)) (first (first upper)))
        ang-ou (atan2 (second (last upper)) (first (last upper)))
        ang-ol (atan2 (second (last lower)) (first (last lower)))

        ;; One tooth (CCW): root_l → lower flank → tip arc → upper flank → root_u
        tooth-0 (vec (concat
                       [(polar rf (- ang-b))
                        (first lower)]
                       (rest lower)
                       (rest (arc-short ra ang-ol ang-ou (max 3 arcfn)))
                       (rest (reverse upper))
                       [(first upper)
                        (polar rf ang-b)]))

        ;; Build full profile: all teeth + root arcs between them
        pts (loop [i 0 acc []]
              (if (>= i z)
                acc
                (let [angle (* i tau)
                      rotated (mapv #(rotate-pt % angle) tooth-0)
                      ;; Root arc from this tooth's root_u to next tooth's root_l
                      root-u-ang (+ angle ang-b)
                      next-root-l-ang (+ angle tau (- ang-b))
                      arc-da (- next-root-l-ang root-u-ang)
                      arc-pts (mapv (fn [j]
                                      (let [t (/ (double j) 3.0)
                                            a (+ root-u-ang (* t arc-da))]
                                        (polar rf a)))
                                    [1 2])]
                  (recur (inc i) (into (into acc rotated) arc-pts)))))]
    (-> pts dedupe-consecutive ensure-ccw)))

(defn gear
  "Spur gear with involute teeth.
   Generates the full gear profile as a single 2D polygon and extrudes once.
   Bore is extruded as a hole — no boolean operations needed."
  [& {:as opts}]
  (let [thk  (double (or (:thickness opts) 5.0))
        bore (double (or (:bore opts) 0.0))

        ;; Full gear outline as a single polygon
        profile-pts (apply gear-profile (mapcat identity opts))

        ;; Bore hole (CW winding for inner contour)
        bore-hole (when (pos? bore)
                    (let [br (/ bore 2.0)
                          n 32]
                      (vec (for [i (range n)]
                             (let [a (- (* i (/ (* 2.0 PI) n)))]
                               [(* br (cos a))
                                (* br (sin a))])))))

        ;; Create shape with optional bore hole
        shape (if bore-hole
                (make-shape profile-pts {:centered? true :holes [bore-hole]})
                (make-shape profile-pts {:centered? true}))]
    (center-thickness-x (extrude shape (f thk)) thk)))

;; =========================
;; Hobbyist helper
;; =========================

(def ^:private standard-modules [4.0 3.0 2.5 2.0 1.5 1.25 1.0 0.8 0.5])
(def ^:private min-teeth-20deg 14)

(defn- resolve-backlash [tol]
  (cond
    (number? tol) tol
    (= tol :fdm) 0.20
    (= tol :sla) 0.10
    (= tol :fine) 0.05
    :else 0.15))

(defn- find-params
  "Find best module and tooth counts for given diameter and ratio.
   Prefers larger modules (bigger teeth, easier to 3D print).
   Skips modules where tooth count falls below minimum or ratio drifts too far."
  [diameter ratio]
  (let [ratio (max 1.0 (double ratio))]
    (loop [mods standard-modules]
      (if (empty? mods)
        ;; Fallback: smallest module, no constraints
        (let [m 0.5
              z1 (int (round (/ diameter m)))
              z2 (int (round (/ z1 ratio)))]
          {:module m :z1 z1 :z2 z2})
        (let [m (first mods)
              z1 (int (round (/ diameter m)))
              z2 (int (round (/ z1 ratio)))
              actual-ratio (if (pos? z2) (/ (double z1) (double z2)) 0.0)]
          (if (and (>= z1 min-teeth-20deg)
                   (>= z2 min-teeth-20deg)
                   (< (abs (- actual-ratio ratio)) (* ratio 0.15)))
            {:module m :z1 z1 :z2 z2}
            (recur (rest mods))))))))

(defn gear-pair
  "Create a pair of meshing gears from simple parameters.

   :ratio     - speed ratio (>= 1). The small gear turns this many times
                per revolution of the large one. Default 2.
   :size      - approximate diameter of the larger gear (mm). Default 40.
   :thickness - gear thickness (mm). Default 8.
   :tolerance - printing tolerance. One of:
                  :fdm  (0.20mm backlash, default)
                  :sla  (0.10mm)
                  :fine (0.05mm)
                  or a number in mm for custom backlash.
   :bore      - shaft hole diameter for both gears (mm). Default 0.
   :bore1     - shaft hole for large gear (overrides :bore).
   :bore2     - shaft hole for small gear (overrides :bore).
   :module    - override automatic module selection.

   Returns a map:
     :gear1    - large gear mesh
     :gear2    - small gear mesh
     :distance - center-to-center distance (mm)
     :phase    - rotation offset for gear2 (degrees)
     :z1       - teeth on large gear
     :z2       - teeth on small gear
     :module   - selected module
     :ratio    - actual ratio (may differ slightly from requested)

   Usage:
     (def p (gears/gear-pair :ratio 2 :size 40 :bore 5))
     (register g1 (:gear1 p))
     (register g2
       (attach (:gear2 p)
         (tv 90) (f (:distance p)) (tv -90) (tr (:phase p))))"
  [& {:as opts}]
  (let [ratio     (max 1.0 (double (or (:ratio opts) 2.0)))
        diameter  (double (or (:size opts) 40.0))
        thk       (double (or (:thickness opts) 8.0))
        tol       (or (:tolerance opts) :fdm)
        bore-def  (double (or (:bore opts) 0.0))
        bore1     (double (or (:bore1 opts) bore-def))
        bore2     (double (or (:bore2 opts) bore-def))

        backlash  (resolve-backlash tol)

        ;; Find module and tooth counts
        params (if (:module opts)
                 (let [m  (double (:module opts))
                       z1 (int (round (/ diameter m)))
                       z2 (int (round (/ z1 ratio)))]
                   {:module m :z1 z1 :z2 z2})
                 (find-params diameter ratio))

        m  (:module params)
        z1 (:z1 params)
        z2 (:z2 params)
        actual-ratio (/ (double z1) (double z2))

        ;; Geometry
        r1   (/ (* m z1) 2.0)
        r2   (/ (* m z2) 2.0)
        dist (+ r1 r2)

        ;; Phase offset: align gear2's gap with gear1's tooth at contact
        phase (let [pitch2 (/ 360.0 z2)
                    f1 (mod (/ (double z1) 4.0) 1.0)]
                (mod (+ 90.0 (* (- 0.5 f1) pitch2)) pitch2))

        ;; Print summary
        _ (println (str "Module: " m "mm  |  Backlash: " backlash "mm"))
        _ (println (str "Large: " z1 "T, dia " (* z1 m) "mm"))
        _ (println (str "Small: " z2 "T, dia " (* z2 m) "mm"))
        _ (println (str "Ratio: " actual-ratio ":1"
                        (if (> (abs (- actual-ratio ratio)) 0.01)
                          (str "  (requested " ratio ":1)")
                          "")))
        _ (println (str "Center distance: " dist "mm"))
        _ (when (< z2 min-teeth-20deg)
            (println (str "WARNING: " z2 " teeth — risk of undercutting")))

        ;; Build both gears
        common {:pressure-angle 20.0
                :thickness      thk
                :backlash       backlash
                :fn 12  :arcfn 10}

        g1 (apply gear (mapcat identity (assoc common :teeth z1 :module m :bore bore1)))
        g2 (apply gear (mapcat identity (assoc common :teeth z2 :module m :bore bore2)))]

    {:gear1 g1  :gear2 g2
     :distance dist  :phase phase
     :z1 z1  :z2 z2  :module m  :ratio actual-ratio}))

;; =========================
;; Quick examples
;; =========================

(comment
  ;; Single tooth
  (register tc
    (tooth-cap :teeth 20 :module 2 :pressure-angle 20
               :thickness 8 :backlash 0.05 :fn 12 :arcfn 10))

  ;; Full gear
  (register g
    (gear :teeth 20 :module 2 :pressure-angle 20
          :thickness 8 :bore 6 :backlash 0.2 :fn 12 :arcfn 10
          :rot :tr))

  ;; Easy gear pair (hobbyist API)
  (def p (gear-pair :ratio 2 :size 40 :thickness 8
                    :tolerance :fdm :bore 5))
  (register g1 (:gear1 p))
  (register g2
    (attach (:gear2 p)
      (tv 90) (f (:distance p)) (tv -90) (tr (:phase p)))))
