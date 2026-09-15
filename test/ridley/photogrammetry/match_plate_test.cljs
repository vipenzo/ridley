(ns ridley.photogrammetry.match-plate-test
  "Identity-free plate registration (fetta B): from 4 clicks on ARBITRARY crown
   discs — in any order, no declared ids — the exhaustive search must recover
   which mark each click is, INCLUDING the rotation, which only the asymmetric
   zero-index can fix (the crown is 12-fold symmetric). The judge is injected (a
   synthetic disc-at?), so the whole recovery is node-testable without a photo."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.photogrammetry.camera :as cam]
            [ridley.photogrammetry.match-plate :as mp]
            [ridley.photogrammetry.pnp :as pnp]
            [ridley.photogrammetry.bridge :as bridge]
            [ridley.photogrammetry.synth :as synth]))

;; --- Synthetic plate: 12 crown marks + a zero-index, in the object frame ------

(def CROWN-R 58.0)
(def INDEX-R 52.0)
(def PLATE-Z 1.5)
(def DISC-R 1.25)

(defn- crown-xy [r deg]
  (let [a (* deg (/ Math/PI 180.0))] [(* r (Math/cos a)) (* r (Math/sin a))]))

(def marks
  (mapv (fn [i]
          (let [[x y] (crown-xy CROWN-R (* i 30.0))]
            {:id (keyword (str "m" i)) :obj [x y PLATE-Z]}))
        (range 12)))

(def zero-obj (let [[x y] (crown-xy INDEX-R 0.0)] [x y PLATE-Z]))

(defn- k* [] {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
              :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(defn- dist-px [[ax ay] [bx by]]
  (Math/sqrt (+ (* (- ax bx) (- ax bx)) (* (- ay by) (- ay by)))))

(defn- disc-judge
  "A fake disc-at? for the synthetic scene: true iff the query pixel is within
   `tol` px of a TRUE projected disc centre. `discs` is the set of disc object
   points that actually carry ink (the 12 crown, plus the zero-index when the
   scene includes it). Ignores r — the synthetic discs are points."
  [pose discs tol]
  (let [pix (keep #(cam/project (k*) pose %) discs)]
    (fn [px _r] (boolean (some #(<= (dist-px px %) tol) pix)))))

(defn- crown-pixel [pose i] (cam/project (k*) pose (:obj (nth marks i))))

;; ---------------------------------------------------------------------------

(deftest recovers-scrambled-clicks-including-rotation
  (println "\n=== fetta B: identità dei mark da 4 click qualsiasi (con rotazione) ===")
  (let [crown-objs (mapv :obj marks)
        all-discs (conj crown-objs zero-obj)
        ;; the 4 physical marks the user clicks — an ASYMMETRIC pick so no
        ;; accidental symmetry helps — and a fixed permutation of the click order
        ;; (the software must not rely on the user clicking them in ring order).
        picked [0 2 5 9]
        click-src [5 0 9 2]]
    (doseq [[az el] [[0 40] [70 50] [140 35] [230 55]]]
      (let [pose (synth/viewpoint az el 250.0)
            judge (disc-judge pose all-discs 10.0)
            clicks (mapv #(crown-pixel pose %) click-src)
            res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
        (println (str "  az=" az " el=" el " → crown-hits " (:crown-hits res)
                      " zero-hit? " (:zero-hit? res) " assignment " (:assignment res)))
        (testing (str "az " az " el " el)
          (is (some? res) "must return a candidate")
          (is (= 12 (:crown-hits res)) "the correct pose reprojects the whole crown onto discs")
          (is (:zero-hit? res) "the zero-index must land on its disc (the rotation is right)")
          ;; every click resolves to the exact mark it came from — proves both the
          ;; subset and the rotation were recovered (a 30°-shifted answer would map
          ;; each click to the neighbouring mark index)
          (doseq [j (range 4)]
            (is (= (nth click-src j) (get-in res [:assignment j]))
                (str "click " j " (mark " (nth click-src j) ") misassigned to "
                     (get-in res [:assignment j])))))))))

(deftest zero-index-resolves-a-symmetric-pick
  ;; 4 marks 90° apart is the WORST case for rotation: the crown count ties across
  ;; every quarter-turn (each maps the pick onto another 90°-spaced set), so
  ;; nothing but the asymmetric zero-index can decide which quarter is the true
  ;; one. Recovering the exact ids from a symmetric pick is the proof the zero-
  ;; index does its job — the whole reason the plate carries it.
  (println "\n=== fetta B: lo zero-indice risolve un click simmetrico (90°) ===")
  (doseq [[az el] [[25 45] [110 35] [200 50]]]
    (let [pose (synth/viewpoint az el 250.0)
          all-discs (conj (mapv :obj marks) zero-obj)
          judge (disc-judge pose all-discs 12.0)
          picked [0 3 6 9]                                ; the maximally-symmetric pick
          clicks (mapv #(crown-pixel pose %) picked)
          res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
      (println (str "  az=" az " el=" el " → zero-hit? " (:zero-hit? res)
                    " assignment " (:assignment res) " score " (:score res)))
      (testing (str "az " az)
        (is (some? res))
        (is (:zero-hit? res) "the zero-index must resolve the quarter-turn ambiguity")
        ;; the winning score carries the zero bonus — i.e. the zero-index, not the
        ;; (tied) crown count, is what elected this orientation
        (is (>= (:score res) mp/zero-bonus) "the win includes the zero-index bonus")
        (doseq [j (range 4)]
          (is (= (nth picked j) (get-in res [:assignment j]))
              "the symmetric pick resolves to the exact marks, not a quarter-turn"))))))

(deftest rejects-too-few-clicks
  (testing "fewer than 4 clicks → nil (a homography needs 4 coplanar points)"
    (let [pose (synth/viewpoint 30 45 250.0)
          judge (disc-judge pose (conj (mapv :obj marks) zero-obj) 10.0)
          clicks (mapv #(crown-pixel pose %) [0 3 6])]
      (is (nil? (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})))))
  (testing "a missing zero-index object → nil (nothing to break the symmetry with)"
    (let [pose (synth/viewpoint 30 45 250.0)
          judge (disc-judge pose (mapv :obj marks) 10.0)
          clicks (mapv #(crown-pixel pose %) [0 3 6 9])]
      (is (nil? (mp/assign-marks clicks marks nil (k*) judge {:disc-r DISC-R :face-normal [0 0 1]}))))))

(deftest survives-click-noise
  ;; The batch clicks are blob-snapped (sub-px), so ~2px is already a pessimistic
  ;; hand-click residual; the assignment must still come back exact. The disc
  ;; judge's tolerance models "landed anywhere on the disc" — a real disc images
  ;; ~25px across, so 18px is realistic (not a fudge to pass).
  (println "\n=== fetta B: assegnazione robusta al rumore di click ===")
  (let [rng (synth/rng 31)
        pose (synth/viewpoint 100 40 250.0)
        all-discs (conj (mapv :obj marks) zero-obj)
        judge (disc-judge pose all-discs 18.0)
        picked [0 3 7 10]
        clicks (mapv (fn [i] (let [[u v] (crown-pixel pose i)]
                               [(+ u (* 2.0 (synth/gauss rng)))
                                (+ v (* 2.0 (synth/gauss rng)))]))
                     picked)
        res (mp/assign-marks clicks marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
    (println (str "  zero-hit? " (:zero-hit? res) " crown-hits " (:crown-hits res)
                  " assignment " (:assignment res)))
    (is (some? res))
    (is (:zero-hit? res))
    (doseq [j (range 4)]
      (is (= (nth picked j) (get-in res [:assignment j]))))))

;; ---------------------------------------------------------------------------
;; REAL-GEOMETRY regression — the paper-plate live gate (test-assets/param-plate-
;; paper, photo 1: 12 real disc clicks, iPhone 48mm-eq) exposed two things a
;; SYNTHETIC scene hides: (1) the marked-face normal is +Z-front-facing on real
;; data (so front-facing? with [0 0 1] is right); (2) estimate-homography's
;; UNREFINED seed reprojects the crown ~30px off — comparable to the disc size —
;; so the tight on-disc judge misses it. assign-marks' coarse-shortlist +
;; LM-refine + tight-score two-pass must still recover the exact identities from
;; as few as 4 clicks on this real oblique pose. :obj = plate-local [58cos30i
;; 58sin30i 1.5], zero at [52 0 1.5] — the replay convention that reproduced the
;; real fit. A perfect detector (proximity to the real disc pixels) is injected;
;; disc-at? on real LUMINANCE is validated separately (blob-snap + disc_check.js).

(def real-K {:fx (* 4032 (/ 48.0 36.0)) :fy (* 4032 (/ 48.0 36.0))
             :cx 2016.0 :cy 1512.0 :k1 0.0 :k2 0.0})

(def real-px-1
  [[3218.993 1879.945] [3298.751 1464.619] [3055.781 1093.775] [2610.352 838.678]
   [2065.807 726.64] [1516.539 769.356] [1055.131 965.28] [791.206 1311.538]
   [851.53 1739.381] [1304.244 2130.588] [2028.306 2318.092] [2760.542 2213.503]])

(deftest recovers-identities-on-real-oblique-pose
  (println "\n=== fetta B: recupero identità sulla geometria REALE (param-plate-paper foto 1) ===")
  (let [truesol (pnp/solve-pnp (mapv (fn [m p] {:world (:obj m) :px p}) marks real-px-1)
                               real-K {})
        [_ _ row2] (cam/rodrigues (:rvec (:pose truesol)))
        nz+ (reduce + (map * row2 [0.0 0.0 1.0]))
        zero-px (cam/project real-K (:pose truesol) zero-obj)
        alldisc (conj real-px-1 zero-px)
        ;; a perfect detector: a disc sits within a disc-radius of a real pixel
        judge (fn [p _r] (boolean (some #(<= (dist-px p %) 30.0) alldisc)))]
    (println (str "  true pose: " (name (:method truesol)) " rms " (.toFixed (:rms-px truesol) 2)
                  "px, +Z object " (if (neg? nz+) "front-facing (nz " "BACK-facing (nz ")
                  (.toFixed nz+ 3) ")"))
    (is (neg? nz+) "the marked-face +Z normal must be front-facing on the real pose")
    (doseq [picks [[0 3 7 10] [2 5 8 11] [0 1 3 4 6 7 9 10] (vec (range 12))]]
      (let [clicks (mapv #(nth real-px-1 %) picks)
            res (mp/assign-marks clicks marks zero-obj real-K judge
                                 {:disc-r DISC-R :face-normal [0.0 0.0 1.0]})]
        (println (str "  " (count picks) " click " picks " → "
                      (if res (str "crown " (:crown-hits res) " zero " (:zero-hit? res)
                                   " ok? " (= (mapv (:assignment res) (range (count picks))) picks))
                          "NIL")))
        (testing (str (count picks) " real clicks " picks)
          (is (some? res) "must assign")
          (is (:zero-hit? res) "the zero-index must resolve the rotation on real data")
          (is (>= (:crown-hits res) 10) "the refined pose reprojects the crown onto the discs")
          (doseq [j (range (count picks))]
            (is (= (nth picks j) (get-in res [:assignment j]))
                "each real click resolves to the mark it actually is")))))))

;; ---------------------------------------------------------------------------
;; fit-crown — SELECT the crown from a detector's superset, then identify (fetta C)

(deftest fit-crown-selects-and-identifies-from-a-superset
  ;; The detector hands fit-crown a SUPERSET — the 12 crown pixels, the zero-index,
  ;; and outliers (the part, stray dark spots) in NO order. It must pick a crown
  ;; quartet, identify every mark, and recover the pose — the seedless, zero-click
  ;; front the detector feeds. Built on the REAL param-plate-paper photo-1 geometry,
  ;; so the selection is proven on a real oblique view, not only synthetic.
  (println "\n=== fetta C: fit-crown seleziona+identifica la corona da un sovrainsieme (reale) ===")
  (let [truesol (pnp/solve-pnp (mapv (fn [m p] {:world (:obj m) :px p}) marks real-px-1) real-K {})
        zero-px (cam/project real-K (:pose truesol) zero-obj)
        centre-px (cam/project real-K (:pose truesol) [0.0 0.0 PLATE-Z])
        ;; distractors a real detector would also surface: the plate centre (part)
        ;; and two stray dark spots off the crown — NONE near a real disc.
        outliers [centre-px (mapv + centre-px [220.0 90.0]) (mapv + centre-px [-160.0 260.0])]
        alldisc (conj real-px-1 zero-px)
        judge (fn [p _r] (boolean (some #(<= (dist-px p %) 30.0) alldisc)))
        ;; a NON-ring input order (crown reversed, zero + outliers appended): fit-crown
        ;; must not lean on the order the blobs arrive in
        blobs (concat (reverse real-px-1) [zero-px] outliers)
        res (mp/fit-crown blobs marks zero-obj real-K judge {:disc-r DISC-R :face-normal [0.0 0.0 1.0]})]
    (println (str "  " (count blobs) " blob (12 corona + zero + " (count outliers) " outlier) → "
                  (if res (str "crown " (:crown-hits res) " zero " (:zero-hit? res)) "NIL")))
    (is (some? res) "fit-crown deve registrare da un sovrainsieme")
    (is (:zero-hit? res) "lo zero-indice fissa la rotazione")
    (is (>= (:crown-hits res) 10) "quasi tutta la corona riproietta sui dischi")
    ;; the reprojection under the recovered pose lands on THIS mark's real disc
    ;; (well under the ~450px neighbour spacing → proves the identity, not a 30°
    ;; shift; the slack absorbs the 4-point fit + real lens distortion the k1=k2=0
    ;; model doesn't carry)
    (doseq [i (range 12)]
      (is (< (dist-px (get (:pixels res) i) (nth real-px-1 i)) 40.0)
          (str "mark " i " riproiettato lontano dal disco reale")))))

(deftest fit-crown-across-poses-synthetic
  (println "\n=== fetta C: fit-crown su più pose sintetiche con outlier ===")
  (let [crown-objs (mapv :obj marks)]
    (doseq [[az el] [[15 45] [95 40] [210 55]]]
      (let [pose (synth/viewpoint az el 230.0)
            crown-px (mapv #(cam/project (k*) pose %) crown-objs)
            zero-px (cam/project (k*) pose zero-obj)
            centre-px (cam/project (k*) pose [0.0 0.0 PLATE-Z])
            outliers [centre-px (mapv + centre-px [140.0 60.0])
                      (mapv + centre-px [-110.0 150.0]) (mapv + centre-px [90.0 -170.0])]
            judge (disc-judge pose (conj crown-objs zero-obj) 20.0)
            blobs (concat crown-px [zero-px] outliers)
            res (mp/fit-crown blobs marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
        (println (str "  az=" az " el=" el " → "
                      (if res (str "crown " (:crown-hits res) " zero " (:zero-hit? res)) "NIL")))
        (testing (str "az " az " el " el)
          (is (some? res) "must register")
          (is (:zero-hit? res) "zero-index fixes the rotation")
          (is (>= (:crown-hits res) 11) "the whole crown reprojects onto discs")
          (doseq [i (range 12)]
            (is (< (dist-px (get (:pixels res) i) (nth crown-px i)) 5.0)
                (str "mark " i " reprojected off its disc"))))))))

(deftest fit-crown-fails-safe-without-a-crown
  (testing "no crown among the blobs → nil (leave the photo to ring/manual, never register wrong)"
    (let [pose (synth/viewpoint 40 45 230.0)
          centre-px (cam/project (k*) pose [0.0 0.0 PLATE-Z])
          judge (disc-judge pose (conj (mapv :obj marks) zero-obj) 20.0)
          blobs [centre-px (mapv + centre-px [200.0 0.0]) (mapv + centre-px [0.0 200.0])
                 (mapv + centre-px [-200.0 -50.0]) (mapv + centre-px [150.0 150.0])]
          res (mp/fit-crown blobs marks zero-obj (k*) judge {:disc-r DISC-R :face-normal [0 0 1]})]
      (is (nil? res) "senza corona rilevata, fit-crown non registra (fail-safe)"))))

;; --- the real webcam frame that failed the first live gate (2026-08-11) ---------
;; The 37 blob centres the detector actually returned on a Logitech C922 frame
;; (1920×1080) of the plate with a printer cartridge on it, on a cutting mat, with a
;; tool full of hex holes in the background. Indices 0-4 and 6-11 are the eleven
;; VISIBLE crown discs (the twelfth is behind the cartridge); everything else is mat
;; dirt, the cartridge, and the tool. Kept as literal data because this is the frame
;; that broke it, and a synthetic imitation of clutter is not the same witness.
(def ^:private c922-blobs
  [[464 768] [755 756] [233 650] [984 628] [143 461] [645 1010] [197 276] [1083 446]
   [347 140] [1054 273] [750 74] [931 145] [1327 173] [1366 25] [642 1046] [1459 5]
   [1350 7] [1082 996] [670 981] [979 1019] [701 977] [776 237] [1106 990] [714 1077]
   [636 985] [635 438] [396 882] [474 1050] [1792 328] [786 236] [1380 5] [253 10]
   [653 1043] [810 506] [758 372] [1517 215] [1212 2]])

(def ^:private c922-crown-idx [0 1 2 3 4 6 7 8 9 10 11])

(deftest finds-the-crown-in-a-real-cluttered-webcam-frame
  ;; The first live gate failed here, and the cause was not the webcam. Stage 1 judges
  ;; an ellipse by inlier COUNT — a weak judge — and on this frame a conic through mat
  ;; dirt and the tool's hex holes gathered 12 points against the crown's 11 and won,
  ;; so stage 2 never saw the crown at all. Underneath that was plain arithmetic: a
  ;; RANSAC sample is useful only when all five points are crown, which here is ~1%,
  ;; and 250 draws often produced NONE.
  ;;
  ;; The plate in this frame has its zero-index hidden under the cartridge, so the
  ;; RIGHT answer is not a pose — it is `:zero-not-visible`, which is only reachable
  ;; by having identified the crown among the junk first. That makes this one
  ;; assertion cover both repairs at once.
  (println "\n=== fit-crown: il fotogramma webcam VERO che aveva fallito ===")
  (let [judge (fn [[px py] r]
                (let [r2 (* r r)]
                  (boolean (some (fn [[bx by]]
                                   (<= (+ (* (- bx px) (- bx px)) (* (- by py) (- by py))) r2))
                                 c922-blobs))))
        ;; production hands fit-crown the top-scored blobs, and the intrinsics of the
        ;; seed focal it is trying
        k (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 28.0 (/ 1920 1080)) 1920 1080)
        blobs (vec (take 24 c922-blobs))
        t0 (system-time)
        res (mp/fit-crown-explained blobs marks zero-obj k judge
                                    {:disc-r DISC-R :face-normal [0 0 1]})
        t1 (system-time)
        ;; the live path tries a LADDER of seed focals; stage 1 reads pixels only, so
        ;; it is hoisted out of the ladder and every rung reuses it. This measures
        ;; both halves, because "it seemed to hang" was a real report and the fix has
        ;; to be a number, not an intention.
        rings (mp/crown-ring-hypotheses blobs {})
        t2 (system-time)
        _ (dotimes [_ 6] (mp/fit-crown-explained blobs marks zero-obj k judge
                                                 {:disc-r DISC-R :face-normal [0 0 1]
                                                  :rings rings}))
        t3 (system-time)]
    (println (str "  37 blob (11 corona veri + sporco + fori) → "
                  (or (some-> (:reason res) name) "riconosciuta")
                  " · corona " (:crown-hits res)))
    (println (str "  anelli trovati: " (mapv count rings)
                  " · costo: un passaggio " (.toFixed (- t1 t0) 0) "ms"
                  " · ricerca anelli " (.toFixed (- t2 t1) 0) "ms"
                  " · 6 focali " (.toFixed (- t3 t2) 0) "ms"))
    ;; A grab has to feel like a camera. These bounds are generous on purpose — they
    ;; guard the SHAPE of the cost, not this machine's speed. What they forbid is the
    ;; return of what was measured here before the ring-size filter: 8086ms for one
    ;; pass and 48914ms for six, which is what "it seems to hang" was. The live path
    ;; pays ONE pass on this frame, because a covered zero-index is a fact about the
    ;; picture and the seed ladder stops asking other focals about it.
    (is (< (- t1 t0) 2000)
        (str "un passaggio deve restare nell'ordine del mezzo secondo ("
             (.toFixed (- t1 t0) 0) "ms)"))
    (is (< (- t3 t2) 6000)
        (str "e sei focali — il caso peggiore — non devono sembrare un blocco ("
             (.toFixed (- t3 t2) 0) "ms)"))
    (is (= :zero-not-visible (:reason res))
        "la corona va trovata in mezzo al disordine, e lo zero coperto va detto")
    (is (>= (:crown-hits res) 10)
        (str "almeno 10 dei mark visibili identificati (ne ha " (:crown-hits res) ")")))

  (testing "…e con lo zero-indice scoperto, la stessa scena registra"
    ;; What turning the plate does, done here by adding the blob the cartridge was
    ;; hiding (at the pixel the live diagnosis reprojected it to). The recovered ring
    ;; must land on the ELEVEN REAL crown discs — that is the identity being right,
    ;; not merely a score being high.
    (let [uncovered (conj (vec (take 24 c922-blobs)) [486 718])
          judge (fn [[px py] r]
                  (let [r2 (* r r)]
                    (boolean (some (fn [[bx by]]
                                     (<= (+ (* (- bx px) (- bx px)) (* (- by py) (- by py))) r2))
                                   uncovered))))
          k (cam/intrinsics-from-fov (cam/equiv-focal->hfov-deg 28.0 (/ 1920 1080)) 1920 1080)
          res (mp/fit-crown uncovered marks zero-obj k judge
                            {:disc-r DISC-R :face-normal [0 0 1]})
          true-crown (mapv #(nth c922-blobs %) c922-crown-idx)
          reproj (vals (:pixels res))
          on-a-real-disc (count (filter (fn [b] (some #(< (dist-px % b) 15.0) reproj))
                                        true-crown))]
      (println (str "  con lo zero scoperto → "
                    (if res (str "corona " (:crown-hits res) " · " on-a-real-disc "/"
                                 (count true-crown) " dischetti veri centrati")
                        "NIL")))
      (is (some? res) "scoperto lo zero, la scena registra")
      (when res
        (is (>= on-a-real-disc 10)
            (str "l'anello ricostruito cade sui dischetti VERI (" on-a-real-disc "/"
                 (count true-crown) ")"))))))

(deftest a-speck-cannot-stand-in-for-the-zero-index
  ;; The failure this guards is the worst one the design can have, and it happened:
  ;; on grab-06 (2026-08-11) the printer cartridge covered the zero-index, a speck of
  ;; dirt with radius 4px sat 9px from where it belonged, the presence test asked only
  ;; "is there a blob here", and the frame REGISTERED. The residual was 1.9px and
  ;; proved nothing — a crown of equal marks is 12-fold symmetric, so an identity
  ;; rotated by 30° reprojects just as perfectly. Only the zero-index says which way
  ;; the plate faces, so a speck standing in for it means an unknown rotation
  ;; delivered as a good measurement.
  (println "\n=== il giudizio pesa la TAGLIA: un granello non è un dischetto ===")
  (let [pose (synth/viewpoint 40 45 230.0)
        crown-objs (mapv :obj marks)
        crown-px (mapv #(cam/project (k*) pose %) crown-objs)
        zero-px (cam/project (k*) pose zero-obj)
        ;; The impostor is described by its RATIO to a real disc — 4px against 13px,
        ;; the measured numbers from that frame — scaled to whatever a disc images at
        ;; HERE. Carrying the raw pixels of another scene into this one is how the
        ;; first version of this test rejected its own good case.
        expected-r (* DISC-R (mp/crown-image-scale (into {} (map-indexed vector crown-px)) marks))
        real-r expected-r
        speck-r (* expected-r (/ 4.0 13.0))
        with-speck (conj (mapv (fn [p] {:center p :radius-px real-r}) crown-px)
                         {:center [(+ (first zero-px) 9.0) (second zero-px)]
                          :radius-px speck-r})
        judge (mp/blob-judge with-speck)
        res (mp/fit-crown-explained crown-px marks zero-obj (k*) judge
                                    {:disc-r DISC-R :face-normal [0 0 1]})]
    (println (str "  corona vera + granello dove sta lo zero → "
                  (or (some-> (:reason res) name) "REGISTRATA (male!)")))
    (is (= :zero-not-visible (:reason res))
        "un granello non deve valere come zero-indice: la rotazione resterebbe ignota")

    (testing "…mentre un dischetto VERO nello stesso posto viene accettato"
      (let [with-disc (conj (mapv (fn [p] {:center p :radius-px real-r}) crown-px)
                            {:center zero-px :radius-px real-r})
            ok (mp/fit-crown crown-px marks zero-obj (k*) (mp/blob-judge with-disc)
                             {:disc-r DISC-R :face-normal [0 0 1]})]
        (println (str "  corona vera + zero vero → "
                      (if ok (str "registrata, corona " (:crown-hits ok)) "NIL")))
        (is (some? ok) "il giudizio pesato non deve rifiutare un piatto buono")
        (is (:zero-hit? ok) "…e lo zero vero deve contare")))))

(deftest a-covered-zero-index-is-named-not-lumped-in
  ;; "Not recognised" and "recognised, but the reference is covered" have OPPOSITE
  ;; remedies — frame it better vs TURN THE PLATE — and one message for both sends
  ;; the user to fix the wrong thing. It is also the ordinary case: anything big
  ;; enough to sit on the plate is big enough to hide a mark 6mm inside the crown
  ;; (2026-08-11: a printer cartridge did exactly that, and the diagnosis took half
  ;; an hour).
  (println "\n=== fit-crown: uno zero-indice COPERTO si chiama per nome ===")
  (let [pose (synth/viewpoint 40 45 230.0)
        crown-objs (mapv :obj marks)
        crown-px (mapv #(cam/project (k*) pose %) crown-objs)
        ;; the zero-index is neither among the blobs nor a disc the judge can see:
        ;; that is what "covered" means to everything downstream
        judge (disc-judge pose crown-objs 20.0)
        res (mp/fit-crown-explained crown-px marks zero-obj (k*) judge
                                    {:disc-r DISC-R :face-normal [0 0 1]})]
    (println (str "  → " (name (:reason res)) " · corona " (:crown-hits res)
                  " · «" (:message res) "»"))
    (is (= :zero-not-visible (:reason res)) "il motivo deve essere lo zero coperto")
    (is (>= (:crown-hits res) 10) "…e deve dire che la corona invece si vede")
    (is (re-find #"[Tt]urn the plate" (:message res)) "…e il rimedio, per esteso")
    (is (nil? (mp/fit-crown crown-px marks zero-obj (k*) judge
                            {:disc-r DISC-R :face-normal [0 0 1]}))
        "fit-crown resta fail-safe: senza zero-indice non registra"))

  (testing "una corona che non c'è NON viene annunciata come coperta"
    ;; the ordering trap: a decoy ring also fails the zero test, and reporting it as
    ;; "the crown is recognised but the reference is covered" would be a lie
    (let [pose (synth/viewpoint 40 45 230.0)
          centre-px (cam/project (k*) pose [0.0 0.0 PLATE-Z])
          judge (disc-judge pose (conj (mapv :obj marks) zero-obj) 20.0)
          blobs (mapv (fn [i] (let [a (* i (/ (* 2 Math/PI) 9))]
                                [(+ (first centre-px) (* 400.0 (Math/cos a)))
                                 (+ (second centre-px) (* 400.0 (Math/sin a)))]))
                      (range 9))
          res (mp/fit-crown-explained blobs marks zero-obj (k*) judge
                                      {:disc-r DISC-R :face-normal [0 0 1]})]
      (println (str "  anello finto → " (name (:reason res))))
      (is (not= :zero-not-visible (:reason res))
          "un anello che non è la corona non deve dire 'gira il piatto'"))))

;; ---------------------------------------------------------------------------
;; Turntable ring — register a photo with ZERO clicks from an already-registered
;; one. The plate spins about its axis; a 1-DOF search over the rotation θ must
;; recover another ring photo's plate angle, the zero-index electing the true one
;; among the 12 crown-symmetric candidates.

(defn- spin-obj [p axis pivot angle]
  ;; Rodrigues about a unit axis through pivot (mirrors match-plate's internal spin)
  (let [d (mapv - p pivot) c (Math/cos angle) s (Math/sin angle)
        [kx ky kz] axis [dx dy dz] d
        cx (- (* ky dz) (* kz dy)) cy (- (* kz dx) (* kx dz)) cz (- (* kx dy) (* ky dx))
        kdot (+ (* kx dx) (* ky dy) (* kz dz))]
    (mapv + pivot
          [(+ (* dx c) (* cx s) (* kx kdot (- 1 c)))
           (+ (* dy c) (* cy s) (* ky kdot (- 1 c)))
           (+ (* dz c) (* cz s) (* kz kdot (- 1 c)))])))

(deftest ring-search-recovers-a-known-plate-rotation
  (println "\n=== ring: recupero rotazione del piatto da una foto già registrata ===")
  (let [axis [0.0 0.0 1.0] pivot [0.0 0.0 PLATE-Z]]  ; marks' plane normal + centroid
    (doseq [[az el] [[0 45] [90 35] [210 50]]
            true-deg [40.0 130.0 250.0]]
      (let [ref-pose (synth/viewpoint az el 250.0)
            true-rad (* true-deg (/ Math/PI 180.0))
            ;; the TARGET photo = the same ref camera seeing the plate spun by true-rad
            crown-px (mapv (fn [m] (cam/project (k*) ref-pose (spin-obj (:obj m) axis pivot true-rad))) marks)
            zero-px (cam/project (k*) ref-pose (spin-obj zero-obj axis pivot true-rad))
            discs (conj crown-px zero-px)
            judge (fn [p _r] (boolean (some #(<= (dist-px p %) 14.0) discs)))
            res (mp/find-ring-pose ref-pose marks zero-obj (k*) judge {:disc-r DISC-R})
            got-deg (when res (* (:theta res) (/ 180.0 Math/PI)))]
        (testing (str "ref az " az " el " el ", plate spun " true-deg "°")
          (is (some? res) "must find an angle")
          (is (:zero-hit? res) "the zero-index must elect the true rotation")
          (is (= 12 (:crown-hits res)) "the whole crown reprojects onto discs at the found angle")
          (is (< (min (Math/abs (- got-deg true-deg))
                      (Math/abs (- 360.0 (Math/abs (- got-deg true-deg))))) 1.5)
              (str "recovered " (.toFixed got-deg 1) "°, true " true-deg "°"))
          ;; predicted pixels land on the target discs (blob-snap seeds)
          (doseq [i (range 12)]
            (is (< (dist-px (get-in res [:pixels i]) (nth crown-px i)) 8.0)
                "predicted mark pixel is near its true target pixel")))))))

;; --- REAL ring: Vincenzo's param-plate-one registered camera poses (world) ---
;; Photos 1-7 were shot around the plate at a roughly constant elevation → a ring;
;; 8/9/10 are off it (lower / top views). find-ring-pose, given ONE registered
;; photo as reference, must predict another RING photo's marks close enough to
;; seed blob-snap (the crux of the plate 'f'), and must NOT confidently register
;; an off-ring photo. Both ref and target reproject the SAME replay :obj through
;; the compiled bridge, so this tests the real camera RING geometry, not :obj.

(def real-proxy-pose {:position [0.012774 0.101609 0.067386] :heading [0.002704 -0.009157 -0.999954] :up [-0.003651 0.999951 -0.009166]})

(def real-cams
  {1 {:position [210.759365 173.712344 158.639549] :heading [-0.676706 -0.57378 -0.461352] :up [-0.331328 -0.322257 0.886776]}
   2 {:position [272.180072 -16.457675 157.965506] :heading [-0.887025 0.042135 -0.459794] :up [-0.460391 -0.005196 0.887701]}
   3 {:position [117.487264 -241.564459 162.977935] :heading [-0.394154 0.785705 -0.47677] :up [-0.227671 0.419121 0.878922]}
   4 {:position [-169.639407 -205.558528 165.261435] :heading [0.546251 0.683139 -0.484697] :up [0.280039 0.396417 0.874318]}
   5 {:position [-260.250717 66.391603 163.575195] :heading [0.854414 -0.202803 -0.478379] :up [0.473356 -0.075854 0.877599]}
   6 {:position [-85.953554 255.553948 161.833358] :heading [0.294373 -0.830251 -0.473317] :up [0.184916 -0.43642 0.880536]}
   7 {:position [181.943787 203.465473 156.796115] :heading [-0.582898 -0.671938 -0.456869] :up [-0.27243 -0.368109 0.888976]}
   9 {:position [101.100979 2.778747 349.96048] :heading [-0.245894 0.019185 -0.969107] :up [-0.948904 0.199239 0.244713]}})

(deftest ring-predicts-a-real-photo-from-another
  (println "\n=== ring REALE (param-plate-one): una foto registrata ne predice un'altra ===")
  (let [solver (fn [idx] (bridge/editor->solver-pose (get real-cams idx) real-proxy-pose))
        refs [1 2 3 4 5 6 7]
        target-px (fn [idx] (mapv #(cam/project real-K (solver idx) (:obj %)) marks))
        judge-for (fn [idx tol] (let [px (conj (target-px idx) (cam/project real-K (solver idx) zero-obj))]
                                  (fn [p _r] (boolean (some #(<= (dist-px p %) tol) px)))))
        max-err (fn [res tgt] (reduce max (mapv #(dist-px (get-in res [:pixels %]) (nth (target-px tgt) %)) (range 12))))
        ;; the UI strategy: predict a target from whichever registered reference scores best (the nearest)
        best-ref-for (fn [tgt] (->> (remove #{tgt} refs)
                                    (keep (fn [r] (some-> (mp/find-ring-pose (solver r) marks zero-obj real-K (judge-for tgt 16.0) {})
                                                          (assoc :ref r))))
                                    (apply max-key :score)))]
    ;; the error-vs-separation curve from ONE fixed reference (photo 1): near is
    ;; tight, far drifts (a hand-held ring is not a perfect circle)
    (println "  --- da un solo riferimento (foto 1): errore vs separazione ---")
    (doseq [tgt [7 2 6 3 5 4]]
      (let [res (mp/find-ring-pose (solver 1) marks zero-obj real-K (judge-for tgt 16.0) {})]
        (println (str "  1→" tgt ": crown " (:crown-hits res) " zero " (:zero-hit? res)
                      " err max " (when res (.toFixed (max-err res tgt) 0)) "px"))))
    ;; the actual UI: nearest reference per target — every ring photo must register
    (println "  --- strategia UI: miglior riferimento per ogni foto ---")
    (doseq [tgt refs]
      (let [res (best-ref-for tgt)]
        (println (str "  tgt " tgt " ← ref " (:ref res) ": crown " (:crown-hits res)
                      " zero " (:zero-hit? res) " err max " (.toFixed (max-err res tgt) 0) "px"))
        (testing (str "target " tgt " from its best reference")
          ;; zero-hit? is the clean discriminator: whenever it is true the whole
          ;; prediction is within blob-snap reach (≤~30px here); when the rotation
          ;; is wrong the zero misses and the error is ~2000px. crown-hits is a
          ;; secondary floor (per-mark scatter thins it on the widest gaps).
          (is (:zero-hit? res) "the zero-index elects the rotation")
          (is (>= (:crown-hits res) 4) "enough marks confirm the angle")
          (is (< (max-err res tgt) 40.0) "predicted within blob-snap reach of the real marks"))))
    (testing "an off-ring top view (9) is not confidently registered from a side ref (1)"
      (let [res (mp/find-ring-pose (solver 1) marks zero-obj real-K (judge-for 9 16.0) {})]
        (println (str "  1→9 (fuori anello): "
                      (if res (str "crown " (:crown-hits res) " zero " (:zero-hit? res)) "NIL")))
        (is (or (nil? res) (not (:zero-hit? res)) (< (:crown-hits res) 10))
            "a top-view photo off the side ring must not falsely register")))))
