(ns ridley.editor.sci-harness
  "Minimal SCI evaluation harness for testing DSL macro behavior.
   Replicates the essential bindings from repl.cljs without browser dependencies
   (no viewport, registry, manifold, panel, text, stl).

   Usage in tests:
     (let [{:keys [turtle result]} (eval-dsl \"(extrude (circle 5) (f 20))\")]
       (is (= 1 (count (:meshes turtle)))))"
  (:require [sci.core :as sci]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]
            [ridley.math :as math]))

;; ── Turtle atom (per-evaluation) ────────────────────────────

(defonce ^:private turtle-atom (atom nil))

(defn- reset-turtle! []
  (reset! turtle-atom (turtle/make-turtle)))

(defn get-turtle []
  @turtle-atom)

;; ── Implicit commands (mutate turtle-atom) ──────────────────

(defn- implicit-f [dist]
  (swap! turtle-atom turtle/f dist))

(defn- implicit-th [angle]
  (swap! turtle-atom turtle/th angle))

(defn- implicit-tv [angle]
  (swap! turtle-atom turtle/tv angle))

(defn- implicit-tr [angle]
  (swap! turtle-atom turtle/tr angle))

(defn- implicit-pen [mode]
  (swap! turtle-atom turtle/pen mode))

(defn- implicit-reset
  ([] (reset-turtle!))
  ([pos] (reset! turtle-atom (turtle/reset-pose (turtle/make-turtle) pos))))

(defn- implicit-resolution [mode value]
  (swap! turtle-atom turtle/resolution mode value))

(defn- implicit-joint-mode [mode]
  (swap! turtle-atom turtle/joint-mode mode))

(defn- implicit-arc-h [radius angle & {:keys [steps]}]
  (swap! turtle-atom turtle/arc-h radius angle :steps steps))

(defn- implicit-arc-v [radius angle & {:keys [steps]}]
  (swap! turtle-atom turtle/arc-v radius angle :steps steps))

(defn- implicit-bezier-to [target & args]
  (swap! turtle-atom #(apply turtle/bezier-to % target args)))

(defn- implicit-bezier-as [p & args]
  (swap! turtle-atom #(apply turtle/bezier-as % p args)))

(defn- implicit-push-state []
  (swap! turtle-atom turtle/push-state))

(defn- implicit-pop-state []
  (swap! turtle-atom turtle/pop-state))

(defn- implicit-goto [name]
  (swap! turtle-atom turtle/goto name))

(defn- implicit-look-at [name]
  (swap! turtle-atom turtle/look-at name))

(defn- implicit-run-path [p]
  (swap! turtle-atom turtle/run-path p))

;; ── Pure functions for extrude/loft/revolve ─────────────────
;; These replicate the pure-* functions from repl.cljs but read from our local turtle-atom.

(defn- combine-meshes
  [meshes]
  (when (seq meshes)
    (if (= 1 (count meshes))
      (first meshes)
      (loop [remaining (rest meshes)
             combined-verts (vec (:vertices (first meshes)))
             combined-faces (vec (:faces (first meshes)))]
        (if (empty? remaining)
          (cond-> {:type :mesh
                   :primitive :combined
                   :vertices combined-verts
                   :faces combined-faces
                   :creation-pose (:creation-pose (first meshes))}
            (:material (first meshes)) (assoc :material (:material (first meshes))))
          (let [m (first remaining)
                offset (count combined-verts)
                new-verts (:vertices m)
                new-faces (mapv (fn [face] (mapv #(+ % offset) face)) (:faces m))]
            (recur (rest remaining)
                   (into combined-verts new-verts)
                   (into combined-faces new-faces))))))))

(defn- pure-extrude-path [shape-or-shapes path]
  (let [shapes (if (vector? shape-or-shapes) shape-or-shapes [shape-or-shapes])
        current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
                        (turtle/make-turtle))
        results (reduce
                 (fn [acc shape]
                   (let [state (turtle/extrude-from-path initial-state shape path)
                         mesh (last (:meshes state))]
                     (if mesh (conj acc mesh) acc)))
                 [] shapes)]
    (if (= 1 (count results)) (first results) results)))

(defn- pure-loft-path
  ([shape transform-fn path] (pure-loft-path shape transform-fn path 16))
  ([shape transform-fn path steps]
   (let [current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :joint-mode (:joint-mode current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         result-state (turtle/loft-from-path initial-state shape transform-fn path steps)
         meshes (:meshes result-state)]
     (combine-meshes meshes))))

(defn- pure-loft-two-shapes
  ([shape1 shape2 path] (pure-loft-two-shapes shape1 shape2 path 16))
  ([shape1 shape2 path steps]
   (let [n1 (count (:points shape1))
         n2 (count (:points shape2))
         [s1 s2] (if (= n1 n2)
                   [shape1 shape2]
                   (let [target-n (max n1 n2)]
                     [(if (< n1 target-n) (xform/resample shape1 target-n) shape1)
                      (if (< n2 target-n) (xform/resample shape2 target-n) shape2)]))
         transform-fn (fn [_shape t] (xform/morph s1 s2 t))]
     (pure-loft-path s1 transform-fn path steps))))

(defn- pure-revolve
  ([shape] (pure-revolve shape 360))
  ([shape angle]
   (let [current-turtle @turtle-atom
         initial-state (if current-turtle
                         (-> (turtle/make-turtle)
                             (assoc :position (:position current-turtle))
                             (assoc :heading (:heading current-turtle))
                             (assoc :up (:up current-turtle))
                             (assoc :resolution (:resolution current-turtle))
                             (assoc :material (:material current-turtle)))
                         (turtle/make-turtle))
         result-state (turtle/revolve-shape initial-state shape angle)
         mesh (last (:meshes result-state))]
     mesh)))

(defn- implicit-extrude-closed-path [shape path]
  (let [current-turtle @turtle-atom
        initial-state (if current-turtle
                        (-> (turtle/make-turtle)
                            (assoc :position (:position current-turtle))
                            (assoc :heading (:heading current-turtle))
                            (assoc :up (:up current-turtle))
                            (assoc :joint-mode (:joint-mode current-turtle))
                            (assoc :resolution (:resolution current-turtle))
                            (assoc :material (:material current-turtle)))
                        (turtle/make-turtle))
        result-state (turtle/extrude-closed-from-path initial-state shape path)
        mesh (last (:meshes result-state))]
    mesh))

(defn- circle-with-resolution [radius & [segments]]
  (let [res (or segments
                (let [{:keys [mode value]} (or (:resolution @turtle-atom) {:mode :n :value 16})]
                  (case mode :n value :a (max 8 (int (Math/ceil (/ 360 value)))) value)))]
    (shape/circle-shape radius res)))

;; ── Bindings map ────────────────────────────────────────────

(def ^:private base-bindings
  {;; Movement
   'f            implicit-f
   'th           implicit-th
   'tv           implicit-tv
   'tr           implicit-tr
   'pen-impl     implicit-pen
   'reset        implicit-reset
   'joint-mode   implicit-joint-mode
   'resolution   implicit-resolution
   ;; Arcs
   'arc-h        implicit-arc-h
   'arc-v        implicit-arc-v
   ;; Bezier
   'bezier-to    implicit-bezier-to
   'bezier-as    implicit-bezier-as
   ;; State stack
   'push-state   implicit-push-state
   'pop-state    implicit-pop-state
   ;; Navigation
   'goto         implicit-goto
   'look-at      implicit-look-at
   'follow-path  implicit-run-path
   ;; Turtle state access
   'turtle       turtle/make-turtle
   'get-turtle   get-turtle
   'get-turtle-resolution (fn [] (or (:resolution @turtle-atom) {:mode :n :value 16}))
   'get-turtle-joint-mode (fn [] (or (:joint-mode @turtle-atom) :flat))
   'turtle-position (fn [] (:position @turtle-atom))
   'turtle-heading  (fn [] (:heading @turtle-atom))
   'turtle-up       (fn [] (:up @turtle-atom))
   ;; Pure turtle functions
   'turtle-f     turtle/f
   'turtle-th    turtle/th
   'turtle-tv    turtle/tv
   'turtle-tr    turtle/tr
   ;; Shapes (resolution-aware)
   'circle       circle-with-resolution
   'rect         shape/rect-shape
   'polygon      shape/ngon-shape
   'star         shape/star-shape
   'make-shape   shape/make-shape
   'shape?       shape/shape?
   ;; Shape transforms
   'scale-shape     shape/scale-shape
   'scale           shape/scale-shape
   'rotate-shape    xform/rotate
   'translate       xform/translate
   'morph           xform/morph
   'resample        xform/resample
   'path-to-shape   shape/path-to-shape
   ;; Path recording functions (used by macro-defs)
   'make-recorder       turtle/make-recorder
   'rec-f               turtle/rec-f
   'rec-th              turtle/rec-th
   'rec-tv              turtle/rec-tv
   'rec-tr              turtle/rec-tr
   'rec-set-heading     turtle/rec-set-heading
   'path-from-recorder  turtle/path-from-recorder
   ;; Shape recording functions (used by shape macro)
   'shape-rec-f         shape/rec-f
   'shape-rec-th        shape/rec-th
   'shape-from-recording shape/shape-from-recording
   'recording-turtle    shape/recording-turtle
   ;; Path utilities
   'run-path-impl       turtle/run-path
   'path?               turtle/path?
   'quick-path          turtle/quick-path
   'path-segments-impl  turtle/path-segments
   'subdivide-segment-impl turtle/subdivide-segment
   'compute-bezier-walk-impl turtle/compute-bezier-walk
   ;; Extrude/loft/revolve (pure functions)
   'pure-extrude-path        pure-extrude-path
   'extrude-closed-path-impl implicit-extrude-closed-path
   'pure-loft-path           pure-loft-path
   'pure-loft-two-shapes     pure-loft-two-shapes
   'pure-revolve             pure-revolve
   ;; Math (used by arc/bezier recording in macro-defs)
   'PI     js/Math.PI
   'abs    js/Math.abs
   'sin    js/Math.sin
   'cos    js/Math.cos
   'sqrt   js/Math.sqrt
   'ceil   js/Math.ceil
   'floor  js/Math.floor
   'round  js/Math.round
   'pow    js/Math.pow
   'atan2  js/Math.atan2
   'acos   js/Math.acos
   ;; Print (captured as no-ops in test)
   'println println
   'print   print
   ;; Anchor support (mark only meaningful inside path; no-op outside)
   'mark   (fn [_name] nil)
   ;; Needed for macro-defs follow-path reference
   'run-path (fn [p] (doseq [{:keys [cmd args]} (:commands p)]
                        (case cmd
                          :f  (implicit-f (first args))
                          :th (implicit-th (first args))
                          :tv (implicit-tv (first args))
                          :tr (implicit-tr (first args))
                          nil)))})

;; ── Macro-defs subset for test harness ──────────────────────
;; Extracted from repl.cljs macro-defs. Only includes path, shape, extrude,
;; loft, extrude-closed, revolve - no attach, register, show/hide, viewport.

(def ^:private test-macro-defs
  ";; Atom to hold recorder during path recording
   (def ^:private path-recorder (atom nil))

   ;; Recording versions that work with the path-recorder atom
   (defn- rec-f* [dist]
     (swap! path-recorder rec-f dist))
   (defn- rec-th* [angle]
     (swap! path-recorder rec-th angle))
   (defn- rec-tv* [angle]
     (swap! path-recorder rec-tv angle))
   (defn- rec-tr* [angle]
     (swap! path-recorder rec-tr angle))
   (defn- rec-set-heading* [heading up]
     (swap! path-recorder rec-set-heading heading up))

   ;; Recording version of mark
   (defn- rec-mark* [name]
     (swap! path-recorder update :recording conj {:cmd :mark :args [name]}))

   ;; Recording version of follow
   (defn- rec-follow* [path]
     (when (and (map? path) (= :path (:type path)))
       (doseq [cmd (:commands path)]
         (swap! path-recorder update :recording conj cmd))))

   ;; Recording version of resolution
   (defn- rec-resolution* [mode value]
     (swap! path-recorder assoc :resolution {:mode mode :value value}))

   ;; Recording version of arc-h
   (defn- rec-arc-h* [radius angle & {:keys [steps]}]
     (when-not (or (zero? radius) (zero? angle))
       (let [angle-rad (* (abs angle) (/ PI 180))
             arc-length (* radius angle-rad)
             res-mode (get-in @path-recorder [:resolution :mode] :n)
             res-value (get-in @path-recorder [:resolution :value] 16)
             actual-steps (or steps
                              (case res-mode
                                :n res-value
                                :a (max 1 (int (ceil (/ (abs angle) res-value))))
                                :s (max 1 (int (ceil (/ arc-length res-value))))))
             step-angle-deg (/ angle actual-steps)
             step-angle-rad (/ angle-rad actual-steps)
             step-dist (* 2 radius (sin (/ step-angle-rad 2)))
             half-angle (/ step-angle-deg 2)]
         (rec-th* half-angle)
         (rec-f* step-dist)
         (dotimes [_ (dec actual-steps)]
           (rec-th* step-angle-deg)
           (rec-f* step-dist))
         (rec-th* half-angle))))

   ;; Recording version of arc-v
   (defn- rec-arc-v* [radius angle & {:keys [steps]}]
     (when-not (or (zero? radius) (zero? angle))
       (let [angle-rad (* (abs angle) (/ PI 180))
             arc-length (* radius angle-rad)
             res-mode (get-in @path-recorder [:resolution :mode] :n)
             res-value (get-in @path-recorder [:resolution :value] 16)
             actual-steps (or steps
                              (case res-mode
                                :n res-value
                                :a (max 1 (int (ceil (/ (abs angle) res-value))))
                                :s (max 1 (int (ceil (/ arc-length res-value))))))
             step-angle-deg (/ angle actual-steps)
             step-angle-rad (/ angle-rad actual-steps)
             step-dist (* 2 radius (sin (/ step-angle-rad 2)))
             half-angle (/ step-angle-deg 2)]
         (rec-tv* half-angle)
         (rec-f* step-dist)
         (dotimes [_ (dec actual-steps)]
           (rec-tv* step-angle-deg)
           (rec-f* step-dist))
         (rec-tv* half-angle))))

   ;; Helper: normalize a 3D vector
   (defn- rec-normalize [v]
     (let [len (sqrt (+ (* (nth v 0) (nth v 0))
                        (* (nth v 1) (nth v 1))
                        (* (nth v 2) (nth v 2))))]
       (if (> len 0.0001)
         [(/ (nth v 0) len) (/ (nth v 1) len) (/ (nth v 2) len)]
         [1 0 0])))

   ;; Helper: dot product
   (defn- rec-dot [a b]
     (+ (* (nth a 0) (nth b 0))
        (* (nth a 1) (nth b 1))
        (* (nth a 2) (nth b 2))))

   ;; Helper: cross product
   (defn- rec-cross [a b]
     [(- (* (nth a 1) (nth b 2)) (* (nth a 2) (nth b 1)))
      (- (* (nth a 2) (nth b 0)) (* (nth a 0) (nth b 2)))
      (- (* (nth a 0) (nth b 1)) (* (nth a 1) (nth b 0)))])

   ;; Helper: compute th and tv angles to rotate from one heading to another
   (defn- rec-compute-rotation-angles [from-heading from-up to-direction]
     (let [up-comp (rec-dot to-direction from-up)
           horiz-dir [(- (nth to-direction 0) (* up-comp (nth from-up 0)))
                      (- (nth to-direction 1) (* up-comp (nth from-up 1)))
                      (- (nth to-direction 2) (* up-comp (nth from-up 2)))]
           horiz-len (sqrt (rec-dot horiz-dir horiz-dir))
           tv-rad (atan2 up-comp horiz-len)
           tv-deg (* tv-rad (/ 180 PI))
           [th-deg] (if (> horiz-len 0.001)
                      (let [horiz-norm (rec-normalize horiz-dir)
                            fwd-comp (rec-dot horiz-norm from-heading)
                            right (rec-cross from-heading from-up)
                            right-comp (rec-dot horiz-norm right)
                            th-rad (atan2 right-comp fwd-comp)]
                        [(* (- th-rad) (/ 180 PI))])
                      [0])]
       [th-deg tv-deg]))

   ;; Recording version of bezier-as — uses relative rotations (th/tv) instead
   ;; of absolute set-heading to produce orientation-independent paths.
   (defn- rec-bezier-as* [p & {:keys [tension steps max-segment-length cubic]}]
     (let [path-segs (path-segments-impl p)
           path-segs (if max-segment-length
                       (vec (mapcat #(subdivide-segment-impl % max-segment-length) path-segs))
                       path-segs)]
       (when (seq path-segs)
         (let [init-state @path-recorder
               init-pose (select-keys init-state [:position :heading :up])
               res-mode (get-in init-state [:resolution :mode] :n)
               res-value (get-in init-state [:resolution :value] 16)
               scaled-res (max 3 (round (/ res-value 3)))
               calc-steps-fn (fn [seg-length]
                               (or steps
                                   (case res-mode
                                     :n scaled-res
                                     :a scaled-res
                                     :s (max 1 (int (ceil (/ seg-length res-value)))))))
               walk-data (compute-bezier-walk-impl
                           path-segs init-pose
                           {:tension (or tension 0.33)
                            :cubic cubic
                            :calc-steps-fn calc-steps-fn})]
           (doseq [segment-data walk-data]
             (when-not (:degenerate segment-data)
               (doseq [step (:walk-steps segment-data)]
                 (let [{:keys [dist chord-heading final-heading final-up]} step
                       ;; Convert absolute chord-heading to relative th/tv rotations
                       current-heading (:heading @path-recorder)
                       current-up (:up @path-recorder)
                       [th-angle tv-angle] (rec-compute-rotation-angles current-heading current-up chord-heading)]
                   ;; Apply rotations then move forward
                   (when (> (abs th-angle) 0.001) (rec-th* th-angle))
                   (when (> (abs tv-angle) 0.001) (rec-tv* tv-angle))
                   (rec-f* dist)
                   ;; Now set heading to final-heading (tangent continuity)
                   (let [current-heading2 (:heading @path-recorder)
                         current-up2 (:up @path-recorder)
                         [th2 tv2] (rec-compute-rotation-angles current-heading2 current-up2 final-heading)]
                     (when (> (abs th2) 0.001) (rec-th* th2))
                     (when (> (abs tv2) 0.001) (rec-tv* tv2)))))))))))

   ;; Recording version of bezier-to
   (defn- rec-bezier-to* [target & args]
     (let [grouped (group-by vector? args)
           control-points (get grouped true)
           options (get grouped false)
           steps (get (apply hash-map (flatten options)) :steps)
           state @path-recorder
           p0 (:position state)
           start-heading (:heading state)
           p3 (vec target)
           dx0 (- (nth p3 0) (nth p0 0))
           dy0 (- (nth p3 1) (nth p0 1))
           dz0 (- (nth p3 2) (nth p0 2))
           approx-length (sqrt (+ (* dx0 dx0) (* dy0 dy0) (* dz0 dz0)))]
       (when (> approx-length 0.001)
         (let [res-mode (get-in state [:resolution :mode] :n)
               res-value (get-in state [:resolution :value] 16)
               actual-steps (or steps
                                (case res-mode
                                  :n res-value
                                  :a res-value
                                  :s (max 1 (int (ceil (/ approx-length res-value))))))
               n-controls (count control-points)
               [c1 c2] (cond
                         (= n-controls 2) [(vec (first control-points))
                                           (vec (second control-points))]
                         (= n-controls 1) (let [cp (vec (first control-points))]
                                            [cp cp])
                         :else
                         (let [heading start-heading]
                           [(mapv + p0 (mapv #(* % (* approx-length 0.33)) heading))
                            (let [to-start (rec-normalize [(- (nth p0 0) (nth p3 0))
                                                           (- (nth p0 1) (nth p3 1))
                                                           (- (nth p0 2) (nth p3 2))])]
                              (mapv + p3 (mapv #(* % (* approx-length 0.33)) to-start)))]))
               cubic-point (fn [t]
                             (let [t2 (- 1 t)
                                   a (* t2 t2 t2) b (* 3 t2 t2 t) c (* 3 t2 t t) d (* t t t)]
                               [(+ (* a (nth p0 0)) (* b (nth c1 0)) (* c (nth c2 0)) (* d (nth p3 0)))
                                (+ (* a (nth p0 1)) (* b (nth c1 1)) (* c (nth c2 1)) (* d (nth p3 1)))
                                (+ (* a (nth p0 2)) (* b (nth c1 2)) (* c (nth c2 2)) (* d (nth p3 2)))]))
               points (mapv #(cubic-point (/ % actual-steps)) (range (inc actual-steps)))
               segments (vec (for [i (range actual-steps)]
                               (let [curr-pos (nth points i)
                                     next-pos (nth points (inc i))
                                     dx (- (nth next-pos 0) (nth curr-pos 0))
                                     dy (- (nth next-pos 1) (nth curr-pos 1))
                                     dz (- (nth next-pos 2) (nth curr-pos 2))
                                     dist (sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
                                 {:dir (if (> dist 0.001) (rec-normalize [dx dy dz]) nil)
                                  :dist dist})))]
           (loop [remaining-segments segments
                  current-up (:up state)]
             (when (seq remaining-segments)
               (let [{:keys [dir dist]} (first remaining-segments)]
                 (if (and dir (> dist 0.001))
                   (let [dot-product (rec-dot current-up dir)
                         projected [(- (nth current-up 0) (* dot-product (nth dir 0)))
                                    (- (nth current-up 1) (* dot-product (nth dir 1)))
                                    (- (nth current-up 2) (* dot-product (nth dir 2)))]
                         proj-len (sqrt (rec-dot projected projected))
                         new-up (if (> proj-len 0.001)
                                  (rec-normalize projected)
                                  (let [right (rec-cross dir current-up)
                                        right-len (sqrt (rec-dot right right))]
                                    (if (> right-len 0.001)
                                      (rec-normalize (rec-cross right dir))
                                      current-up)))]
                     ;; Rotate to segment direction using relative th/tv
                     (let [cur-heading (:heading @path-recorder)
                           cur-up (:up @path-recorder)
                           [th-angle tv-angle] (rec-compute-rotation-angles cur-heading cur-up dir)]
                       (when (> (abs th-angle) 0.001) (rec-th* th-angle))
                       (when (> (abs tv-angle) 0.001) (rec-tv* tv-angle)))
                     (rec-f* dist)
                     (recur (rest remaining-segments) new-up))
                   (recur (rest remaining-segments) current-up)))))))))

   ;; path macro
   (defmacro path [& body]
     `(let [saved# @path-recorder]
        (reset! path-recorder (make-recorder))
        (swap! path-recorder assoc
               :resolution (get-turtle-resolution)
               :joint-mode (get-turtle-joint-mode))
        (let [~'f rec-f*
              ~'th rec-th*
              ~'tv rec-tv*
              ~'tr rec-tr*
              ~'arc-h rec-arc-h*
              ~'arc-v rec-arc-v*
              ~'bezier-to rec-bezier-to*
              ~'bezier-as rec-bezier-as*
              ~'resolution rec-resolution*
              ~'mark rec-mark*
              ~'follow rec-follow*]
          ~@body)
        (let [result# (path-from-recorder @path-recorder)]
          (reset! path-recorder saved#)
          result#)))

   ;; shape macro
   (defmacro shape [& body]
     `(let [state# (atom (recording-turtle))
            ~'f (fn [d#] (swap! state# shape-rec-f d#))
            ~'th (fn [a#] (swap! state# shape-rec-th a#))
            ~'tv (fn [& _#] (throw (js/Error. \"tv not allowed in shape - 2D only\")))
            ~'tr (fn [& _#] (throw (js/Error. \"tr not allowed in shape - 2D only\")))]
        ~@body
        (shape-from-recording @state#)))

   ;; pen macro
   (defmacro pen [mode]
     `(pen-impl ~mode))

   ;; run-path
   (defn run-path [p]
     (doseq [{:keys [cmd args]} (:commands p)]
       (case cmd
         :f  (f (first args))
         :th (th (first args))
         :tv (tv (first args))
         :tr (tr (first args))
         nil)))

   ;; extrude macro
   (defmacro extrude [shape & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           (symbol? arg)
           `(let [arg# ~arg]
              (if (path? arg#)
                (pure-extrude-path ~shape arg#)
                (pure-extrude-path ~shape (path ~arg))))

           (and (list? arg) (contains? #{'path 'path-to} (first arg)))
           `(pure-extrude-path ~shape ~arg)

           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-as} (first arg)))
           `(pure-extrude-path ~shape (path ~arg))

           :else
           `(let [result# ~arg]
              (if (path? result#)
                (pure-extrude-path ~shape result#)
                (pure-extrude-path ~shape (path ~arg))))))
       `(pure-extrude-path ~shape (path ~@movements))))

   ;; extrude-closed macro
   (defmacro extrude-closed [shape & movements]
     (if (= 1 (count movements))
       (let [path-expr (first movements)]
         (cond
           (symbol? path-expr)
           `(extrude-closed-path-impl ~shape ~path-expr)

           (and (list? path-expr) (= 'path (first path-expr)))
           `(extrude-closed-path-impl ~shape ~path-expr)

           (and (list? path-expr) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-as} (first path-expr)))
           `(extrude-closed-path-impl ~shape (path ~path-expr))

           :else
           `(let [result# ~path-expr]
              (if (path? result#)
                (extrude-closed-path-impl ~shape result#)
                (extrude-closed-path-impl ~shape (path ~path-expr))))))
       `(extrude-closed-path-impl ~shape (path ~@movements))))

   ;; loft macro
   (defmacro loft [shape transform-fn-or-shape & movements]
     (if (= 1 (count movements))
       (let [arg (first movements)]
         (cond
           (symbol? arg)
           `(let [arg# ~arg
                  tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (if (path? arg#)
                  (pure-loft-two-shapes ~shape tfn# arg#)
                  (pure-loft-two-shapes ~shape tfn# (path ~arg)))
                (if (path? arg#)
                  (pure-loft-path ~shape tfn# arg#)
                  (pure-loft-path ~shape tfn# (path ~arg)))))

           (and (list? arg) (= 'path (first arg)))
           `(let [tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (pure-loft-two-shapes ~shape tfn# ~arg)
                (pure-loft-path ~shape tfn# ~arg)))

           (and (list? arg) (contains? #{'f 'th 'tv 'tr 'arc-h 'arc-v 'bezier-to 'bezier-as} (first arg)))
           `(let [tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (pure-loft-two-shapes ~shape tfn# (path ~arg))
                (pure-loft-path ~shape tfn# (path ~arg))))

           :else
           `(let [result# ~arg
                  tfn# ~transform-fn-or-shape]
              (if (shape? tfn#)
                (if (path? result#)
                  (pure-loft-two-shapes ~shape tfn# result#)
                  (pure-loft-two-shapes ~shape tfn# (path ~arg)))
                (if (path? result#)
                  (pure-loft-path ~shape tfn# result#)
                  (pure-loft-path ~shape tfn# (path ~arg)))))))
       `(let [tfn# ~transform-fn-or-shape]
          (if (shape? tfn#)
            (pure-loft-two-shapes ~shape tfn# (path ~@movements))
            (pure-loft-path ~shape tfn# (path ~@movements))))))

   ;; revolve macro
   (defmacro revolve
     ([shape]
      `(pure-revolve ~shape 360))
     ([shape angle]
      `(pure-revolve ~shape ~angle)))
  ")

;; ── Public API ──────────────────────────────────────────────

(defn eval-dsl
  "Evaluate DSL code string through SCI. Returns:
   {:turtle <turtle-state-after>
    :result <SCI-eval-result>
    :error  <error-message-or-nil>}"
  [code]
  (reset-turtle!)
  (try
    (let [ctx (sci/init {:bindings base-bindings})]
      ;; Load macros first
      (sci/eval-string test-macro-defs ctx)
      (let [result (sci/eval-string code ctx)]
        {:turtle @turtle-atom
         :result result
         :error nil}))
    (catch :default e
      {:turtle @turtle-atom
       :result nil
       :error (.-message e)})))

(defn eval-dsl-seq
  "Evaluate multiple DSL strings in sequence, preserving state.
   Returns final {:turtle :result :error}."
  [& codes]
  (reset-turtle!)
  (try
    (let [ctx (sci/init {:bindings base-bindings})]
      (sci/eval-string test-macro-defs ctx)
      (let [result (last (mapv #(sci/eval-string % ctx) codes))]
        {:turtle @turtle-atom
         :result result
         :error nil}))
    (catch :default e
      {:turtle @turtle-atom
       :result nil
       :error (.-message e)})))
