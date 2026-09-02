(ns ridley.photogrammetry.bridge-test
  "Round-trip verification for ridley.photogrammetry.bridge — the one
   mathematically risky piece of edit-acquire's edge-snap slice (P2, brief-
   param-acq-v1.md), isolated here so it's checkable headlessly, without the
   flaky browser environment (dev-docs/HANDOVER-edit-acquire-gate.md's
   'Limite ambientale'): editor->solver-pose followed by either inverse must
   return the pose that was held fixed, exactly (pure algebra, no LM)."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.test-helpers :refer [vec-approx=]]
            [ridley.math :as m]
            [ridley.photogrammetry.box-fit :as bf]
            [ridley.photogrammetry.bridge :as bridge]))

(def simple-proxy-pose
  {:position [10.0 5.0 -3.0] :heading [0.0 1.0 0.0] :up [0.0 0.0 1.0]})

(def simple-camera-pose
  {:position [50.0 5.0 -3.0] :heading [-1.0 0.0 0.0] :up [0.0 0.0 1.0]})

(def tilted-proxy-pose
  {:position [-4.0 12.0 8.0] :heading [1.0 0.0 0.0] :up [0.0 0.70710678 0.70710678]})

(def tilted-camera-pose
  {:position [5.0 -20.0 15.0] :heading [0.0 1.0 0.0] :up [0.0 0.0 1.0]})

(deftest box-basis-matches-mesh-convention
  (testing "at a fresh box's default creation-pose (heading=[1 0 0] up=[0 0 1]),
            the box's own local +x (its w/sx argument) lands at world +Y —
            up×heading, not heading×up. Getting this sign wrong silently
            mirrors every pose the solver computes (see namespace docstring)."
    (let [basis (bridge/box-basis {:position [0 0 0] :heading [1 0 0] :up [0 0 1]})]
      (is (vec-approx= [0.0 1.0 0.0] (:ex basis)))
      (is (vec-approx= [0.0 0.0 1.0] (:ey basis)))
      (is (vec-approx= [1.0 0.0 0.0] (:ez basis))))))

(deftest round-trip-camera-simple
  (testing "editor->solver-pose then solver-pose->camera, proxy held fixed,
            recovers the original camera pose (photos 1..N-1's case)"
    (let [solver-pose (bridge/editor->solver-pose simple-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->camera solver-pose simple-proxy-pose)]
      (is (vec-approx= (:position simple-camera-pose) (:position recovered) 1e-6))
      (is (vec-approx= (:heading simple-camera-pose) (:heading recovered) 1e-6))
      (is (vec-approx= (:up simple-camera-pose) (:up recovered) 1e-6)))))

(deftest round-trip-proxy-simple
  (testing "editor->solver-pose then solver-pose->proxy, camera held fixed,
            recovers the original proxy pose (photo 0's case)"
    (let [solver-pose (bridge/editor->solver-pose simple-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose simple-camera-pose)]
      (is (vec-approx= (:position simple-proxy-pose) (:position recovered) 1e-6))
      (is (vec-approx= (:heading simple-proxy-pose) (:heading recovered) 1e-6))
      (is (vec-approx= (:up simple-proxy-pose) (:up recovered) 1e-6)))))

(deftest round-trip-camera-tilted
  (testing "same round trip with a tilted proxy up-vector, so the box-local
            frame isn't axis-aligned with the editor world"
    (let [solver-pose (bridge/editor->solver-pose tilted-camera-pose tilted-proxy-pose)
          recovered (bridge/solver-pose->camera solver-pose tilted-proxy-pose)]
      (is (vec-approx= (:position tilted-camera-pose) (:position recovered) 1e-5))
      (is (vec-approx= (:heading tilted-camera-pose) (:heading recovered) 1e-5))
      (is (vec-approx= (:up tilted-camera-pose) (:up recovered) 1e-5)))))

(deftest round-trip-proxy-tilted
  (testing "same round trip (proxy direction) with a tilted proxy up-vector"
    (let [solver-pose (bridge/editor->solver-pose tilted-camera-pose tilted-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose tilted-camera-pose)]
      (is (vec-approx= (:position tilted-proxy-pose) (:position recovered) 1e-5))
      (is (vec-approx= (:heading tilted-proxy-pose) (:heading recovered) 1e-5))
      (is (vec-approx= (:up tilted-proxy-pose) (:up recovered) 1e-5)))))

(def non-orthogonal-camera-pose
  "Reproduces the exact bug found live 2026-07-22: edit_acquire.cljs's
   default-vantage-pose used heading=(normalize [1 -1 1]) and up=[0 0 1]
   VERBATIM — dot ≈ 0.577, nowhere near perpendicular. Harmless for THREE.js's
   own camera.lookAt (which orthogonalizes internally) but this bridge used
   to trust heading⊥up exactly, silently turning solver-pose->proxy's rigid
   transform into a shear (the box coming out non-rectangular)."
  {:position [50.0 5.0 -3.0]
   :heading (m/normalize [1.0 -1.0 1.0])
   :up [0.0 0.0 1.0]})

(deftest box-basis-orthonormal-despite-non-orthogonal-input
  (testing "box-basis Gram-Schmidts up against heading (m/orthogonalize-up)
            rather than trusting the pose's own up field — {:ex :ey :ez} must
            come out mutually orthonormal even when given a pose whose raw
            heading/up aren't (fed simple-proxy-pose's position/heading with
            non-orthogonal-camera-pose's bad up, worst case)"
    (let [{:keys [ex ey ez]}
          (bridge/box-basis {:position [0 0 0] :heading (:heading non-orthogonal-camera-pose)
                             :up (:up non-orthogonal-camera-pose)})]
      (is (< (Math/abs (m/dot ex ey)) 1e-6))
      (is (< (Math/abs (m/dot ey ez)) 1e-6))
      (is (< (Math/abs (m/dot ex ez)) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ex))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ey))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude ez))) 1e-6)))))

(deftest solver-pose->proxy-orthonormal-despite-non-orthogonal-camera
  (testing "solver-pose->proxy's output heading/up stay exactly perpendicular
            even when the FIXED camera pose it's given (photo 0's case) has
            heading/up merely close to perpendicular, like the real
            default-vantage-pose bug — a non-orthogonal result here is what
            corrupted the proxy mesh into a shear live"
    (let [solver-pose (bridge/editor->solver-pose non-orthogonal-camera-pose simple-proxy-pose)
          recovered (bridge/solver-pose->proxy solver-pose non-orthogonal-camera-pose)]
      (is (< (Math/abs (m/dot (:heading recovered) (:up recovered))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude (:heading recovered)))) 1e-6))
      (is (< (Math/abs (- 1.0 (m/magnitude (:up recovered)))) 1e-6)))))

(deftest dims-from-mesh-recovers-extents
  (testing "dims-from-mesh reads the box's actual world-space corner vertices
            (built directly from box-basis, not via bridge internals) and
            recovers the [w h d] extents used to place them"
    (let [proxy-pose simple-proxy-pose
          {:keys [ex ey ez]} (bridge/box-basis proxy-pose)
          origin (:position proxy-pose)
          [w h d] [12.0 20.0 8.0]
          v+ (fn [[a b c] [d1 e f]] [(+ a d1) (+ b e) (+ c f)])
          v* (fn [[a b c] s] [(* a s) (* b s) (* c s)])
          corner (fn [sx sy sz]
                   (v+ origin (v+ (v* ex (* sx w 0.5))
                                  (v+ (v* ey (* sy h 0.5)) (v* ez (* sz d 0.5))))))
          verts (vec (for [sx [-1 1] sy [-1 1] sz [-1 1]] (corner sx sy sz)))
          mesh {:vertices verts}
          [w' h' d'] (bridge/dims-from-mesh mesh proxy-pose)]
      (is (< (Math/abs (- w w')) 1e-6))
      (is (< (Math/abs (- h h')) 1e-6))
      (is (< (Math/abs (- d d')) 1e-6)))))

(deftest world->local-is-exact-inverse-of-local->world
  (testing "world->local undoes local->world at a tilted proxy pose"
    (let [pt [7.0 -3.0 11.0]
          w (bridge/local->world tilted-proxy-pose pt)]
      (is (vec-approx= pt (bridge/world->local tilted-proxy-pose w) 1e-9)))))

;; --- pnp-target-points: source-agnostic PnP correspondence targets (fetta B) ---
;; The plate work leans on this: box corners and plate marks must present as the
;; SAME indexed shape, both with :obj in the object/solver frame, so the picking
;; gesture and the solver are identical whatever the proxy is.

(defn- box-mesh-at
  "A box mesh (no :anchors) with [w h d] extents at proxy-pose, built directly
   from box-basis — the same way the real box proxy's vertices are laid out."
  [proxy-pose [w h d]]
  (let [{:keys [ex ey ez]} (bridge/box-basis proxy-pose)
        origin (:position proxy-pose)
        v+ (fn [a b] (mapv + a b))
        v* (fn [a s] (mapv #(* % s) a))
        corner (fn [sx sy sz]
                 (v+ origin (v+ (v* ex (* sx w 0.5))
                                (v+ (v* ey (* sy h 0.5)) (v* ez (* sz d 0.5))))))]
    {:creation-pose proxy-pose
     :vertices (vec (for [sx [-1 1] sy [-1 1] sz [-1 1]] (corner sx sy sz)))}))

(deftest pnp-targets-box-mode-matches-box-corners
  (testing "a proxy with no :anchors yields the 8 box corners in the object
            frame, indexed 0-7 — identical to what box-fit/corners feeds the
            solver, and :world = local->world of :obj"
    (let [dims [12.0 20.0 8.0]
          mesh (box-mesh-at simple-proxy-pose dims)
          targets (bridge/pnp-target-points mesh simple-camera-pose)]
      (is (= 8 (count targets)))
      (is (= (range 8) (map :id targets)))
      (doseq [[t c] (map vector targets (bf/corners dims))]
        (is (vec-approx= c (:obj t) 1e-6))
        (is (vec-approx= (bridge/local->world simple-proxy-pose c) (:world t) 1e-6))))))

(defn- mark [world nrm] {:position world :heading nrm :up [1.0 0.0 0.0]})

(deftest pnp-targets-marks-mode-uses-anchors-object-frame
  (testing "a proxy carrying :anchors yields one target per mark (sorted by id),
            :obj recovering the mark's object-frame coords — and pose-invariant:
            the same coords come back at a different proxy pose (the property
            that keeps a re-posed plate clicking the right point)"
    (let [mesh {:creation-pose simple-proxy-pose
                :vertices [[0 0 0]]
                :anchors {:m1 (mark (bridge/local->world simple-proxy-pose [58.0 0.0 1.5]) [0 0 1])
                          :m0 (mark (bridge/local->world simple-proxy-pose [0.0 58.0 1.5]) [0 0 1])}}
          targets (bridge/pnp-target-points mesh simple-camera-pose)]
      (is (= [:m0 :m1] (map :id targets)) "sorted by mark id")
      (is (vec-approx= [0.0 58.0 1.5] (:obj (first targets)) 1e-6))
      (is (vec-approx= [58.0 0.0 1.5] (:obj (second targets)) 1e-6))
      (testing "same object coords recovered at a tilted proxy pose"
        (let [m2 {:creation-pose tilted-proxy-pose
                  :vertices [[0 0 0]]
                  :anchors {:m0 (mark (bridge/local->world tilted-proxy-pose [0.0 58.0 1.5]) [0 0 1])}}]
          (is (vec-approx= [0.0 58.0 1.5]
                           (:obj (first (bridge/pnp-target-points m2 tilted-camera-pose))) 1e-6)))))))

(deftest pnp-targets-plate-marks-are-always-offerable
  ;; A registration plate's marks are all coplanar on one face, which the user
  ;; always photographs — so every mark is offerable regardless of the normal's
  ;; sign under the CURRENT (pre-PnP, possibly wrong) pose. Per-mark front-facing
  ;; was a box notion that only mis-fired here: the fresh-session default vantage
  ;; frames the plate's blank underside, which would hide every mark and leave
  ;; nothing to click. Occlusion by the part is the user's 'o' key, not a normal
  ;; test.
  (testing "every plate mark is :visible? true whichever way its normal points"
    (let [cp {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}
          cam {:position [0.0 0.0 100.0] :heading [0 0 -1] :up [0 1 0]}
          mesh-with (fn [nrm] {:creation-pose cp :vertices [[0 0 0]]
                               :anchors {:m {:position [0.0 0.0 0.0] :heading nrm :up [1 0 0]}}})
          vis? (fn [nrm] (:visible? (first (bridge/pnp-target-points (mesh-with nrm) cam))))]
      (is (true? (vis? [0.0 0.0 1.0])) "normal toward the camera")
      (is (true? (vis? [0.0 0.0 -1.0])) "normal away — still offerable (user photographs the marked face)"))))

(deftest plate-detect-and-zero-exclusion
  ;; Fetta B plumbing: the zero-index rides in :anchors under :zero (so it
  ;; transports rigidly for free) but must be EXCLUDED from the pickable crown,
  ;; and its object-frame position + the marked-face normal must come back for
  ;; the identity-free match. Deterministic on a known creation-pose.
  (let [cp {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}   ; box-basis ex=[0 1 0] ey=[0 0 1] ez=[1 0 0]
        mesh {:creation-pose cp :vertices [[0 0 0]] :mark-disc-r 1.25
              :anchors {:m00 {:position [58.0 0.0 1.5] :heading [0 0 1] :up [1 0 0]}
                        :m01 {:position [0.0 58.0 1.5] :heading [0 0 1] :up [0 1 0]}
                        :zero {:position [52.0 0.0 1.5] :heading [0 0 1] :up [1 0 0]}}}]
    (testing "the :zero anchor is not among the pickable crown targets"
      (let [targets (bridge/pnp-target-points mesh cp)]
        (is (= 2 (count targets)) "only the two crown marks, never :zero")
        (is (= [:m00 :m01] (mapv :id targets)))))
    (testing "plate-detect returns the zero in the object frame, disc radius, and a unit face normal"
      (let [det (bridge/plate-detect mesh)]
        ;; world->local of [52 0 1.5] onto (ex=[0 1 0], ey=[0 0 1], ez=[1 0 0]) = [0 1.5 52]
        (is (vec-approx= [0.0 1.5 52.0] (:zero-obj det) 1e-6))
        (is (= 1.25 (:disc-r det)))
        ;; heading [0 0 1] → object frame [0 1 0] (dot with ex,ey,ez), unit
        (is (vec-approx= [0.0 1.0 0.0] (:face-normal det) 1e-6))
        (is (< (Math/abs (- 1.0 (m/magnitude (:face-normal det)))) 1e-9) "unit normal")))
    (testing "a mesh without a :zero anchor (a box, or a plate lacking one) → nil"
      (is (nil? (bridge/plate-detect (update mesh :anchors dissoc :zero)))))))

;; ── registration-verdict: the stage's ✓/⚠ flag, held to the target kind ──────

(deftest registration-verdict-judges-each-target-by-its-own-rules
  ;; The plate's rules applied to a cage flagged healthy photos (Vincenzo
  ;; 2026-08-29: «le foto dalla 2 in avanti sono flaggate col triangolino —
  ;; sembrano corrette», and they were). The numbers below are the battiscopa
  ;; hand session's own, read from its acquire-state.
  (testing "piatto: le regole storiche restano identiche"
    (is (= :flipped (bridge/registration-verdict
                     {:kind :plate :rms-px 2.0 :behind? true :elevation-deg 80}))
        "camera dietro la faccia = impossibile, anche a rms pulito")
    (is (= :flipped (bridge/registration-verdict
                     {:kind :plate :rms-px nil :behind? true}))
        "e anche senza un rms registrato")
    (is (nil? (bridge/registration-verdict
               {:kind :plate :rms-px 4.5 :behind? false :elevation-deg 40}))
        "sotto gli 8px: sana")
    (is (= :grazing (bridge/registration-verdict
                     {:kind :plate :rms-px 9.4 :behind? false :elevation-deg 19}))
        "sopra gli 8px ma radente: è l'angolo, non i click")
    (is (= :loose (bridge/registration-verdict
                   {:kind :plate :rms-px 9.4 :behind? false :elevation-deg 40}))
        "sopra gli 8px senza scusa: i click sono il sospetto")
    (is (= :loose (bridge/registration-verdict
                   {:kind :plate :rms-px 9.4 :behind? false :elevation-deg nil}))
        "un'elevazione ignota non compra la scusa"))
  (testing "gabbia: giudicata SOLO dalla sua asticella (12px), mai dalla faccia"
    (is (nil? (bridge/registration-verdict
               {:kind :cage :rms-px 7.7 :behind? true :elevation-deg -2}))
        "foto 3 del battiscopa: 7.7px, camera 6mm oltre il piano Z — sana, non 'ribaltata'")
    (is (nil? (bridge/registration-verdict
               {:kind :cage :rms-px 10.7 :behind? false :elevation-deg 10}))
        "10.7px è sopra l'asticella del piatto e sotto quella della gabbia")
    (is (= :loose (bridge/registration-verdict
                   {:kind :cage :rms-px 13.8 :behind? true :elevation-deg 10}))
        "foto 0 del battiscopa: 13.8px è lasca anche per la gabbia — e mai :flipped/:grazing")
    (is (nil? (bridge/registration-verdict {:kind :cage :rms-px nil :behind? true}))
        "mai registrata: niente da dire, non un triangolo"))
  (testing "box: come prima — solo l'asticella del piatto, niente scuse"
    (is (nil? (bridge/registration-verdict {:kind :box :rms-px 7.0 :behind? false})))
    (is (= :loose (bridge/registration-verdict
                   {:kind :box :rms-px 9.0 :behind? false :elevation-deg 10}))
        "l'elevazione non esiste per un box: niente :grazing")))

;; ---------------------------------------------------------------------------
;; cage-faces-from-pose — the faces are READ from the eye-aligned pose, no
;; longer declared photo by photo (Vincenzo, 2026-08-31: three photos in a row
;; had a hand-declared face wrong, and every face error poisons everything
;; downstream). The virtual twin of «prendo in mano la gabbia e la appaio».
;; ---------------------------------------------------------------------------

(def cage-identity-pose
  ;; registration-cage's own :creation-pose — box-basis maps cage x/y/z to
  ;; world x/y/z exactly here
  {:position [0.0 0.0 0.0] :heading [0.0 0.0 1.0] :up [0.0 1.0 0.0]})

(defn- faces [mesh-pose cam-pos & [opts]]
  (bridge/cage-faces-from-pose {:creation-pose mesh-pose}
                               {:position cam-pos}
                               opts))

(deftest cage-faces-camera-on-axis
  (testing "camera dead on +X: the X ring reads face p at full margin, the two
            rings seen edge-on declare NOTHING (nil, not a guess)"
    (let [f (faces cage-identity-pose [500.0 0.0 0.0])]
      (is (= 1 (get-in f [:x :sign])))
      (is (< 89.9 (get-in f [:x :elev-deg])))
      (is (nil? (get-in f [:y :sign])))
      (is (nil? (get-in f [:z :sign])))))
  (testing "camera dead on −X: face m"
    (is (= -1 (get-in (faces cage-identity-pose [-500.0 0.0 0.0]) [:x :sign])))))

(deftest cage-faces-oblique-reads-all-three
  (testing "camera on the diagonal: every ring is 35.3° off its plane, all
            three faces read"
    (let [f (faces cage-identity-pose [300.0 300.0 300.0])]
      (is (= {:x 1 :y 1 :z 1}
             (into {} (map (fn [[a v]] [a (:sign v)])) f)))
      (doseq [a [:x :y :z]]
        (is (< 35.0 (get-in f [a :elev-deg]) 35.5))))))

(deftest cage-faces-profile-guard
  (testing "15° over the X ring's plane is BELOW the 20° guard: X declares
            nothing but still reports its degrees, so the caller can say why
            (grab-05's Y was decided by 13–17° and that verdict held half a
            day of wrong diagnoses)"
    (let [d15 (* 15.0 (/ js/Math.PI 180.0))
          cam [(* 1000.0 (js/Math.sin d15)) (* 1000.0 (js/Math.cos d15)) 0.0]
          f (faces cage-identity-pose cam)]
      (is (nil? (get-in f [:x :sign])))
      (is (< 14.9 (get-in f [:x :elev-deg]) 15.1))
      (is (= 1 (get-in f [:y :sign])))
      (is (< 74.9 (get-in f [:y :elev-deg]) 75.1))))
  (testing "the same vantage with the guard lowered to 10° reads Xp — the
            threshold is the only thing between the two answers"
    (let [d15 (* 15.0 (/ js/Math.PI 180.0))
          cam [(* 1000.0 (js/Math.sin d15)) (* 1000.0 (js/Math.cos d15)) 0.0]]
      (is (= 1 (get-in (faces cage-identity-pose cam {:margin-deg 10.0})
                       [:x :sign]))))))

(deftest cage-faces-follow-the-pose
  (testing "the ring axes are the CAGE's frame at :creation-pose, not the
            world's: with the cage turned so its z axis lies along world +X
            (heading [1 0 0]), a camera on world +X now reads the Z ring"
    (let [pose {:position [10.0 0.0 0.0] :heading [1.0 0.0 0.0] :up [0.0 1.0 0.0]}
          f (faces pose [510.0 0.0 0.0])]
      (is (= 1 (get-in f [:z :sign])))
      (is (nil? (get-in f [:x :sign])))
      (is (nil? (get-in f [:y :sign])))))
  (testing "and the centre is the cage's position, not the origin: a camera on
            the far side of that same cage reads face m"
    (let [pose {:position [10.0 0.0 0.0] :heading [1.0 0.0 0.0] :up [0.0 1.0 0.0]}]
      (is (= -1 (get-in (faces pose [-490.0 0.0 0.0]) [:z :sign]))))))

(deftest cage-faces-flip-aware
  (testing "anello dichiarato ribaltato (:cage-flips sulla mesh): il segno si
            inverte — la camera sul lato +y di una gabbia con :flips #{:y}
            sta guardando la faccia m della STAMPA, perché id e passetto
            nominano la stampa, non il lato geometrico"
    (let [f (bridge/cage-faces-from-pose {:creation-pose cage-identity-pose
                                          :cage-flips #{:y}}
                                         {:position [300.0 300.0 300.0]})]
      (is (= {:x 1 :y -1 :z 1}
             (into {} (map (fn [[a v]] [a (:sign v)])) f))))
    (testing "e la guardia del profilo vince comunque sul flip"
      (is (nil? (get-in (bridge/cage-faces-from-pose
                         {:creation-pose cage-identity-pose :cage-flips #{:y}}
                         {:position [500.0 0.0 0.0]})
                        [:y :sign]))))))

(deftest cage-faces-geo-sign-survives-the-guard
  (testing "sotto la guardia :sign tace ma :geo-sign resta: è la differenza tra
            ciò che si può DICHIARARE (muove i pick) e ciò che si può
            SUGGERIRE — a Vincenzo la guardia lasciava due bottoni spenti e
            nessun indizio (foto 4 di battiscopa3, anello Y a ~15°)"
    (let [d15 (* 15.0 (/ js/Math.PI 180.0))
          cam [(* 1000.0 (js/Math.sin d15)) (* 1000.0 (js/Math.cos d15)) 0.0]
          f (faces cage-identity-pose cam)]
      (is (nil? (get-in f [:x :sign])) "niente dichiarazione a 15°")
      (is (= 1 (get-in f [:x :geo-sign])) "ma la lettura geometrica c'è, ed è Xp")
      (is (= 1 (get-in f [:y :sign])) "Y è ben visibile e si dichiara")
      (is (= 1 (get-in f [:y :geo-sign])) "dove si dichiara, i due coincidono")))
  (testing "e su un anello RIBALTATO il suggerimento è ribaltato come la
            dichiarazione: sono la stessa lettura, una sotto guardia"
    (let [d15 (* 15.0 (/ js/Math.PI 180.0))
          cam [(* 1000.0 (js/Math.sin d15)) (* 1000.0 (js/Math.cos d15)) 0.0]
          f (bridge/cage-faces-from-pose {:creation-pose cage-identity-pose
                                          :cage-flips #{:x}}
                                         {:position cam})]
      (is (nil? (get-in f [:x :sign])))
      (is (= -1 (get-in f [:x :geo-sign])) "gabbia con X ribaltato: direbbe Xm"))))
