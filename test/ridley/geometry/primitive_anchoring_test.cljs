(ns ridley.geometry.primitive-anchoring-test
  "WHERE a primitive sits relative to the turtle pose that made it, on each of
   the pose's three axes.

   The question came from a real confusion (Vincenzo 2026-08-01): geometry
   placed at a plane mark looked pushed into the object 'by some rule I don't
   know'. The brief (brief-plane-marks.md, Seguito 3) proposed closing the fork
   — anchoring convention vs systematic offset — with two cylinders of different
   HEIGHTS at the same mark: same correction ⇒ systematic offset.

   Measuring it shows that discriminator is confounded, so these tests pin the
   actual convention instead. What matters for a plane mark is the extent along
   its HEADING, because that is the surface normal: whatever sticks out behind
   the pose along heading is what ends up buried in the object."
  (:require [cljs.test :refer [deftest is testing]]
            [ridley.geometry.primitives :as prims]
            [ridley.turtle.core :as turtle]))

(defn- at-pose [position heading up]
  (assoc (turtle/make-turtle) :position position :heading heading :up up))

(defn- extent
  "[min max] of a mesh's vertices along `dir`, measured from `origin`."
  [mesh origin dir]
  (let [ds (map (fn [v] (reduce + (map * (map - v origin) dir))) (:vertices mesh))]
    [(apply min ds) (apply max ds)]))

(defn- last-mesh [state] (last (:meshes state)))

(def ^:private o [0.0 0.0 0.0])
(def ^:private h [0.0 0.0 1.0])   ; heading — at a plane mark this IS the surface normal
(def ^:private u [0.0 1.0 0.0])   ; up
(def ^:private r [1.0 0.0 0.0])   ; right = up × heading

(defn- fmt [x] (.toFixed (js/Number. x) 2))

(defn- report [label mesh]
  (let [[rl rh] (extent mesh o r) [ul uh] (extent mesh o u) [hl hh] (extent mesh o h)]
    (println (str "  " label
                  "  right " (fmt rl) "…" (fmt rh)
                  " | up " (fmt ul) "…" (fmt uh)
                  " | HEADING " (fmt hl) "…" (fmt hh)
                  "  → sepolto " (fmt (- hl)) "mm"))
    {:right [rl rh] :up [ul uh] :heading [hl hh]}))

(deftest a-primitive-straddles-the-pose-on-every-axis
  (println "\n=== Dove nasce un primitivo rispetto alla posa ===")
  (let [cyl6 (report "cyl r2 h6 " (last-mesh (prims/cyl (at-pose o h u) 2.0 6.0)))
        cyl20 (report "cyl r2 h20" (last-mesh (prims/cyl (at-pose o h u) 2.0 20.0)))
        sph (report "sphere r1.5" (last-mesh (prims/sphere (at-pose o h u) 1.5)))
        box (report "box 4 6 10 " (last-mesh (prims/box (at-pose o h u) 4.0 6.0 10.0)))]
    (testing "a cylinder's AXIS runs along up, not along heading"
      (is (= [-3.0 3.0] (:up cyl6)) "half the height each way along up")
      (is (= [-2.0 2.0] (:heading cyl6)) "and only the RADIUS along heading"))
    (testing "so its buried depth does not depend on its height at all"
      (is (= (:heading cyl6) (:heading cyl20))
          "the brief's discriminator (two heights) is CONFOUNDED: the height
           runs across the normal, so both sink by the same 2mm — which would
           read as a systematic offset while being nothing of the sort"))
    (testing "every primitive is centred on the pose"
      (is (= [-1.5 1.5] (:heading sph)))
      (is (= [-5.0 5.0] (:heading box)) "box is (right, up, heading) = (4, 6, 10)"))))

(deftest the-correction-is-half-the-size-ALONG-THE-NORMAL
  (println "\n=== Il discriminante corretto: variare la misura lungo la normale ===")
  (let [sink (fn [mesh] (- (first (extent mesh o h))))
        ;; for a cylinder the extent along heading is the RADIUS…
        c-r2 (sink (last-mesh (prims/cyl (at-pose o h u) 2.0 6.0)))
        c-r5 (sink (last-mesh (prims/cyl (at-pose o h u) 5.0 6.0)))
        ;; …and for a box it is half its third dimension
        b-d10 (sink (last-mesh (prims/box (at-pose o h u) 4.0 6.0 10.0)))
        b-d3 (sink (last-mesh (prims/box (at-pose o h u) 4.0 6.0 3.0)))]
    (println (str "  cilindro r=2 → sepolto " (fmt c-r2) " | r=5 → " (fmt c-r5)))
    (println (str "  box d=10 → sepolto " (fmt b-d10) " | d=3 → " (fmt b-d3)))
    (println "  → la correzione cambia con la geometria: convenzione di ancoraggio, non offset di sistema")
    (is (not= c-r2 c-r5) "vary the radius and the sink changes")
    (is (not= b-d10 b-d3) "vary the depth and the sink changes")
    (is (= [2.0 5.0 5.0 1.5] [c-r2 c-r5 b-d10 b-d3])
        "always exactly half the primitive's own extent along the normal")))

(deftest a-cylinder-at-a-plane-mark-lies-DOWN-on-it
  (println "\n=== Conseguenza meno ovvia ===")
  (let [m (last-mesh (prims/cyl (at-pose o h u) 2.0 20.0))
        {:keys [up heading]} (report "cyl r2 h20 " m)]
    (is (= [-10.0 10.0] up))
    (is (= [-2.0 2.0] heading))
    (println (str "  un cilindro piazzato su un mark-piano giace SULLA superficie "
                  "(asse nel piano), non ci sta in piedi sopra"))
    (is (> (- (second up) (first up)) (- (second heading) (first heading)))
        "its long axis lies IN the plane — to stand it up on the surface the
         turtle must be turned, or the solid extruded along heading instead")))
