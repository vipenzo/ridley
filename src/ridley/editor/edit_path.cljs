(ns ridley.editor.edit-path
  "Modal evaluator for tracing a polyline path interactively — a pen tool for
   drawing over a reference image (see set-image) and clipping the piece you need.

   `(edit-path …)` wraps a path body written 'alla Ridley'. The editor snapshots
   the body's nodes (running the path), lets you ADD nodes by clicking on the
   image, MOVE the selected node with the arrows, and DELETE it — then on confirm
   it re-bakes a plain `(path …)` from the edited node positions. Because the
   result is an ordinary `(path …)`, re-running the script does NOT re-enter
   editing; to edit again, rename `path` → `edit-path`.

   The baked path is anchored with a leading `(move-to [x0 y0])` so path-to-shape
   reproduces the absolute traced coordinates (same 2D frame as the board), and
   `(shape-intersection board (path-to-shape (edit-path …)))` clips correctly.

   MVP: straight segments only. Arc / bezier per-segment are later phases.

   Built on modal-evaluator like edit-bezier: state, mutex, panel, key handler,
   two-phase entry and source commit all come from the shared layer. Its specific
   work is the keymap, the click-to-place node interaction, the ephemeral overlay,
   and the node↔(path …) bake."
  (:require [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.math :as m]
            [ridley.turtle.bezier :as bezier]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

(declare confirm! cancel! render! update-panel! refresh-preview!
         set-seg-len! set-seg-angle! seg-len seg-angle-deg)

;; ============================================================
;; State
;; ============================================================

(defonce ^:private session (atom nil))
;; When active:
;; {:nodes        [[x y] …]   — 2D node positions in the world XY plane
;;  :selected     idx          — index into :nodes (Tab cycles; click appends after)
;;  :step         5            — arrow nudge size
;;  :digit-buffer ""           — accumulating step input
;;  :eval-timeout <id>
;;  :panel-el :key-handler :entered?}

(def ^:private marker-prefix "(edit-path")

;; A small starting triangle when the body is empty, so the downstream is valid
;; immediately and there is something to drag / extend.
(def ^:private default-nodes [[-20 -20] [20 -20] [0 20]])

(def ^:private line-color  0xff3030)  ; polyline — red (reads on a dimmed image)
(def ^:private close-color 0x8a4a4a)  ; closing segment — dim red
(def ^:private node-color   0x2277ff) ; unselected node — blue
(def ^:private sel-color    0xffdd00) ; selected node — yellow
(def ^:private mark-color   0x00dd66) ; node carrying a mark / side-trip — green
(def ^:private exit-color   0xff8800) ; last (exit) node — orange (defines exit heading)
(def ^:private handle-color 0x33ddff) ; bezier control handles — cyan
(def ^:private cusp-color   0xff44cc) ; freed (cusp) outgoing handle — magenta
(def ^:private handle-radius   0.45)  ; bezier handle square half-size
(def ^:private node-radius     0.625) ; filled dot radius (plane units)
(def ^:private node-radius-sel 1.0)   ; selected dot radius

;; Dim the reference image to this opacity while tracing, so the overlay reads.
(def ^:private edit-dim 0.4)
;; Grab a node only when the click is essentially within its dot (tight, so it
;; doesn't swallow nearby control points).
(def ^:private node-snap 1.5)
;; Grab radius for the (small) control-point squares.
(def ^:private handle-snap 2.5)
;; How close a click must be to a segment to insert a node there (split).
;; Generous: an imprecise click near the path still splits instead of missing.
(def ^:private seg-threshold 16)
;; 3D nodes are grabbed in SCREEN space (so any visible node can be picked
;; regardless of the active plane): max click distance in pixels.
(def ^:private node-px-snap 16)

;; ============================================================
;; Number formatting (shared style with edit-bezier)
;; ============================================================

(defn- fmt-num [v]
  (let [r (js/Math.round v)]
    (if (< (js/Math.abs (- v r)) 1e-9)
      (str r)
      (let [s (.toFixed v 2)]
        (cond
          (str/ends-with? s "00") (subs s 0 (- (count s) 3))
          (str/ends-with? s "0")  (subs s 0 (dec (count s)))
          :else s)))))

;; ============================================================
;; Nodes ↔ path (the bake)
;; ============================================================
;; A node is {:pos [px py] :heading [hx hy]|nil :tail [cmd …]}.
;;  :heading — the desired turtle heading AT this node, or nil = follow geometry.
;;             Kept for notable nodes (marks, the last/exit node) and strafes
;;             (rt/lt), so orientation is preserved; nil for plain corners.
;;  :tail    — record-only commands (mark, side-trip) issued at this node; they
;;             travel with it through insert / move / delete and are re-emitted.

(defn- node-pos [node] (:pos node))

(defn- v2-norm [[x y]]
  (let [m (js/Math.sqrt (+ (* x x) (* y y)))]
    (if (> m 1e-9) [(/ x m) (/ y m)] [1 0])))
(defn- v2-dot [[ax ay] [bx by]] (+ (* ax bx) (* ay by)))
(defn- right-of [[hx hy]] [hy (- hx)])     ; turtle 'right' in the 2D trace frame
(defn- left-of  [[hx hy]] [(- hy) hx])
(defn- dir-eq? [a b] (> (v2-dot a b) 0.99995))

(defn- signed-angle
  "Signed angle (degrees) turning from 2D unit dir a to dir b."
  [[ax ay] [bx by]]
  (* (/ 180 js/Math.PI)
     (js/Math.atan2 (- (* ax by) (* ay bx))
                    (+ (* ax bx) (* ay by)))))

(defn- rotate-dir [[hx hy] deg]
  (let [r (* deg (/ js/Math.PI 180)) c (js/Math.cos r) s (js/Math.sin r)]
    [(- (* hx c) (* hy s)) (+ (* hx s) (* hy c))]))

;; -- arc segments (3-point circular arc: A → belly M → B) -----------------
;; An arc segment carries a single free "belly" handle M, a point the arc passes
;; through. A, M, B define a unique circle; the minor arc through M is the segment.
;; It bakes to a heading-relative (th …)(arc-h r sweep), so it stays attached like
;; the straight segments (no absolute coords).

(defn- circle-from-3
  "Circumcircle of three 2D points → {:center [cx cy] :r r}, or nil if collinear."
  [[ax ay] [mx my] [bx by]]
  (let [d (* 2 (+ (* ax (- my by)) (* mx (- by ay)) (* bx (- ay my))))]
    (when (> (js/Math.abs d) 1e-6)
      (let [a2 (+ (* ax ax) (* ay ay)) m2 (+ (* mx mx) (* my my)) b2 (+ (* bx bx) (* by by))
            cx (/ (+ (* a2 (- my by)) (* m2 (- by ay)) (* b2 (- ay my))) d)
            cy (/ (+ (* a2 (- bx mx)) (* m2 (- ax bx)) (* b2 (- mx ax))) d)]
        {:center [cx cy]
         :r (js/Math.sqrt (+ (* (- ax cx) (- ax cx)) (* (- ay cy) (- ay cy))))}))))

(defn- norm-2pi [a]
  (let [t (mod a (* 2 js/Math.PI))] (if (< t 0) (+ t (* 2 js/Math.PI)) t)))

(defn- arc-geom
  "Geometry of the arc A → M → B: {:center :r :sweep-deg :entry-tan :start-ang}, or
   nil if the points are collinear (caller bakes a straight segment instead).
   sweep-deg follows arc-h's convention (positive = left / CCW). entry-tan is the
   unit tangent leaving A along the arc."
  [A M B]
  (when-let [{:keys [center r]} (circle-from-3 A M B)]
    (let [[cx cy] center
          ang (fn [[x y]] (js/Math.atan2 (- y cy) (- x cx)))
          aA (ang A) aM (ang M) aB (ang B)
          ccw-sweep (norm-2pi (- aB aA))           ; CCW arc length A→B
          ccw-to-M  (norm-2pi (- aM aA))
          ccw? (<= ccw-to-M ccw-sweep)             ; does the CCW arc A→B pass through M?
          sweep (if ccw? ccw-sweep (- (- (* 2 js/Math.PI) ccw-sweep)))
          radial [(- (first A) cx) (- (second A) cy)]
          tan (if ccw? [(- (second radial)) (first radial)]   ; +90° from radius
                  [(second radial) (- (first radial))])]  ; -90°
      {:center center :r r :start-ang aA
       :sweep-deg (* sweep (/ 180 js/Math.PI))
       :entry-tan (v2-norm tan)})))

(defn- arc-tess
  "Tessellated 2D points along the arc A → M → B (inclusive of both ends), or nil
   if degenerate."
  [A M B steps]
  (when-let [{:keys [center r start-ang sweep-deg]} (arc-geom A M B)]
    (let [[cx cy] center
          sweep (* sweep-deg (/ js/Math.PI 180))]
      (mapv (fn [i] (let [a (+ start-ang (* (/ i steps) sweep))]
                      [(+ cx (* r (js/Math.cos a))) (+ cy (* r (js/Math.sin a)))]))
            (range (inc steps))))))

;; -- bezier tangent constraint (directional handles) ----------------------
;; c1 (start handle) stays tangent to how the path ARRIVES at the start node, so
;; a smooth node leaves tangent to what precedes; c2 (end handle) is free and sets
;; the arrival direction. A cusp node (:smooth? false) frees its outgoing c1.

(defn- incoming-tangent
  "Unit direction the path arrives at node i with (= the tangent that a smooth c1
   leaving i must lie along)."
  [nodes i]
  (cond
    (zero? i) [1 0]
    (:bez (nth nodes i)) (v2-norm (let [[bx by] (node-pos (nth nodes i))
                                        [cx cy] (:c2 (:bez (nth nodes i)))]
                                    [(- bx cx) (- by cy)]))
    :else (v2-norm (let [[bx by] (node-pos (nth nodes i))
                         [ax ay] (node-pos (nth nodes (dec i)))]
                     [(- bx ax) (- by ay)]))))

;; Smallest c1 handle length, as a fraction of the segment chord. A handle can't
;; collapse onto the start node: a zero-length handle gives the cubic an undefined
;; start tangent and clusters the tessellation points, which makes stroke-shape's
;; offset self-overlap pathologically (pinches / stray faces on extrude).
(def ^:private min-handle-frac 0.1)

(defn- reconstrain-handles
  "Project each bezier segment's c1 onto its start node's incoming tangent (keeping
   its length, with a small minimum so it never collapses onto the start node), so
   smooth nodes stay tangent-continuous. Cusp start nodes are left free. Single
   pass — a node's incoming tangent depends on its c2/pos, not on the c1's being
   constrained."
  [nodes]
  (reduce
   (fn [ns i]
     (if (:bez (nth ns i))
       (let [a (dec i)]
         (if (false? (:smooth? (nth ns a)))
           ns                                   ; cusp start node → c1 free
           (let [[ax ay] (node-pos (nth ns a))
                 [bx by] (node-pos (nth ns i))
                 [c1x c1y] (:c1 (:bez (nth ns i)))
                 [tx ty] (incoming-tangent ns a)
                 chord (js/Math.sqrt (+ (* (- bx ax) (- bx ax)) (* (- by ay) (- by ay))))
                 ;; length = projection of the (possibly free-dragged) c1 onto the
                 ;; tangent, floored at a fraction of the chord so the handle slides
                 ;; along the fixed line but never degenerates to the node itself
                 len (max (* min-handle-frac chord)
                          (+ (* (- c1x ax) tx) (* (- c1y ay) ty)))]
             (assoc-in ns [i :bez :c1] [(+ ax (* len tx)) (+ ay (* len ty))]))))
       ns))
   nodes
   (range 1 (count nodes))))

;; -- generic command serializer (for node tails and side-trip bodies) -----

(declare cmd->code)

(defn- arg->code [a]
  (cond
    (number? a) (fmt-num a)
    (vector? a) (str "[" (str/join " " (map arg->code a)) "]")
    :else       (pr-str a)))

(defn- cmd->code [{:keys [cmd args]}]
  (if (= cmd :side-trip)
    ;; side-trip's body is a sub-path; re-emit its commands inline.
    (str "(side-trip " (str/join " " (map cmd->code (:commands (first args)))) ")")
    (str "(" (name cmd) (apply str (map #(str " " (arg->code %)) args)) ")")))

;; -- nodes → path commands / code ----------------------------------------

(defn- th-cmd [ch to-dir] {:cmd :th :args [(signed-angle ch to-dir)]})

(defn- pt->local
  "Express 2D plane point P in the turtle-local [right up heading] frame at a bezier
   start (position `from`, heading `ch`), as [a 0 c] for (bezier-to … :local). The
   in-plane right is heading × up = [chy -chx]; up is out of plane → component 0.
   Emitting the bezier relative to the start node keeps it attached when the path
   that precedes it is edited (the curve follows the turtle), instead of detaching
   like absolute control points do."
  [from ch P]
  (let [dx (- (first P) (first from)) dy (- (second P) (second from))
        [hx hy] ch]
    [(+ (* dx hy) (* dy (- hx)))    ; d · right = d · [hy -hx]
     0
     (+ (* dx hx) (* dy hy))]))     ; d · heading

(defn- segment-cmds
  "Commands to reach `to` from `from` given current heading `ch`, leaving the
   turtle with heading `desired-h` (nil = face the movement). Returns
   [cmds new-heading]. Uses (rt)/(lt) only for a true strafe — when the desired
   heading is unchanged from `ch` and the move is perpendicular — otherwise faces
   the movement with (th)(f) and adds a heading correction if `desired-h` differs."
  [ch from to desired-h]
  (let [dx (- (first to) (first from)) dy (- (second to) (second from))
        dist (js/Math.sqrt (+ (* dx dx) (* dy dy)))]
    (if (< dist 1e-9)
      ;; no move: just a heading correction if asked
      (if (and desired-h (not (dir-eq? ch desired-h)))
        [[(th-cmd ch desired-h)] desired-h]
        [[] ch])
      (let [dir (v2-norm [dx dy])
            keep? (and desired-h (dir-eq? desired-h ch))]  ; strafe / forward intent
        (cond
          (and keep? (dir-eq? dir ch))            [[{:cmd :f :args [dist]}] ch]
          ;; A perpendicular strafe bakes as (th ±90)(f)(th ∓90) rather than rt/lt:
          ;; in the path-2d frame the turtle's right is the plane normal, so native
          ;; rt/lt would leave the plane — th+f keeps the bake genuinely planar.
          :else
          (let [base (if (dir-eq? dir ch)
                       [{:cmd :f :args [dist]}]
                       [(th-cmd ch dir) {:cmd :f :args [dist]}])
                corr (when (and desired-h (not (dir-eq? dir desired-h)))
                       (th-cmd dir desired-h))]
            [(cond-> base corr (conj corr)) (if corr desired-h dir)]))))))

(defn- nodes->commands
  "Path commands for the nodes: a leading move-to anchors the absolute start, then
   per segment the minimal move (f / rt / lt / th+f) that reaches the next node and
   leaves it with the node's heading, with each node's :tail emitted after it."
  [nodes]
  (if (empty? nodes)
    []
    (let [n0 (first nodes)
          [sx sy] (node-pos n0)
          ;; A leading move-to is only needed to anchor a start that isn't the
          ;; origin; at the origin it's redundant (path-to-shape / run-path seed
          ;; [0 0] there) so the bake stays clean.
          at-origin? (and (< (js/Math.abs sx) 1e-6) (< (js/Math.abs sy) 1e-6))
          [corr0 ch0] (segment-cmds [1 0] (node-pos n0) (node-pos n0) (:heading n0))]
      (loop [ch ch0
             from (node-pos n0)
             remaining (rest nodes)
             out (-> (if at-origin? [] [{:cmd :move-to :args [[sx sy]]}])
                     (into corr0)
                     (into (:tail n0)))]
        (if (empty? remaining)
          out
          (let [node (first remaining)
                to (node-pos node)]
            (cond
              ;; Cubic bezier segment: control points emitted in the start node's
              ;; local frame (:local) so the curve stays attached when the path
              ;; before it is edited; the standard macro re-tessellates it. The
              ;; heading after is the end tangent (c2 → end).
              (:bez node)
              (let [{:keys [c1 c2]} (:bez node)
                    end-tan (v2-norm [(- (first to) (first c2)) (- (second to) (second c2))])]
                (recur end-tan to (rest remaining)
                       (-> out
                           (conj {:cmd :bezier-to
                                  :args [(pt->local from ch to)
                                         (pt->local from ch c1)
                                         (pt->local from ch c2)
                                         :local]})
                           (into (:tail node)))))

              ;; Circular arc through the belly point: heading-relative
              ;; (th entry)(arc-h r sweep) — already attached (no absolute coords).
              ;; A collinear belly is degenerate → fall back to a straight segment.
              (:arc node)
              (if-let [{:keys [r sweep-deg entry-tan]} (arc-geom from (:belly (:arc node)) to)]
                (let [entry-th (signed-angle ch entry-tan)
                      new-h (rotate-dir entry-tan sweep-deg)]
                  (recur new-h to (rest remaining)
                         (-> out
                             (cond-> (> (js/Math.abs entry-th) 1e-6)
                               (conj {:cmd :th :args [entry-th]}))
                             (conj {:cmd :arc-h :args [r sweep-deg]})
                             (into (:tail node)))))
                (let [[cmds ch1] (segment-cmds ch from to (:heading node))]
                  (recur ch1 to (rest remaining)
                         (-> out (into cmds) (into (:tail node))))))

              :else
              (let [[cmds ch1] (segment-cmds ch from to (:heading node))]
                (recur ch1 to (rest remaining)
                       (-> out (into cmds) (into (:tail node))))))))))))

(defn- nodes->path
  "A path value from the current nodes — returned by request! so the downstream
   (path-to-shape …) runs even before the first edit."
  [nodes]
  {:type :path :commands (nodes->commands nodes)})

(declare cmd->code-2d)

(defn- bez-local->2d
  "Rewrite a bezier-to :local control point for a path-2d bake. The editor frames
   it as [a 0 c] (a along the in-plane right of the (a,b) frame, c along heading),
   but in path-2d's frame the in-plane perpendicular is up, not right (right is the
   plane normal), and that up points opposite the editor's right — so the
   perpendicular component moves to the up slot, negated: [a 0 c] → [0 -a c]."
  [[a _ c]]
  [0 (- a) c])

(defn- cmd->code-2d
  "Serialize a command for a (path-2d …) bake. The trace lives in the (right,up)
   plane, where the in-plane turn is tv and the in-plane arc is arc-v, so map
   th→tv and arc-h→arc-v — emitting the natural planar names for readability (the
   path-2d macro also aliases th/arc-h, so either reads the same). bezier-to :local
   control points are re-framed into path-2d's local frame."
  [{:keys [cmd args] :as c}]
  (case cmd
    :th    (cmd->code (assoc c :cmd :tv))
    :arc-h (cmd->code (assoc c :cmd :arc-v))
    :bezier-to (if (= :local (last args))
                 (cmd->code (assoc c :args (conj (mapv bez-local->2d (butlast args)) :local)))
                 (cmd->code c))
    :side-trip (str "(side-trip "
                    (str/join " " (map cmd->code-2d (:commands (first args)))) ")")
    (cmd->code c)))

(defn- nodes->code
  "The replacement source: a complete (path-2d (move-to …) (tv …)(f …) …) form,
   carrying preserved marks / side-trips and orientation. The (edit-path-2d …)
   marker is a stand-in for this, so confirming swaps it in."
  [nodes]
  (str "(path-2d"
       (apply str (map #(str " " (cmd->code-2d %)) (nodes->commands nodes)))
       ")"))

;; ============================================================
;; 3D nodes ↔ path (edit-path — straight-segment MVP, phase 1)
;; ============================================================
;; A 3D path is a rail (consumed by extrude-along-path / loft in its own frame),
;; so nodes carry full 3D positions and the turtle frame is DERIVED from the
;; geometry (tangent + parallel-transported up), exactly like the 2D editor
;; derives heading from the segment. Curves/marks and the working-plane UI come in
;; later phases.

(defn- seed->nodes-3d
  "Recover 3D editor nodes from a :3d path: one node per traced waypoint, carrying
   the full turtle frame {:pos :heading :up}. Straight-segment MVP."
  [seed-path]
  (let [wps (shape/path-to-3d-waypoints seed-path)]
    (if (and wps (>= (count wps) 2))
      (mapv (fn [{:keys [pos heading up]}]
              {:pos pos :heading heading :up up :tail []})
            wps)
      ;; empty → just the anchor node at the origin (already present, not inserted
      ;; by the user and not movable); the user clicks to add the rail from there.
      ;; A 0-segment path extrudes to an empty mesh (no error) until the 2nd node.
      [{:pos [0 0 0] :heading nil :up nil :tail []}])))

(defn- safe-up
  "An up vector perpendicular to `dir`, derived from reference `ref`. Falls back to
   a different reference when `ref` is (near-)parallel to `dir`, so the result is
   never the zero vector — a zero up collapses the swept section's frame
   (right = heading × up = 0 → the tube tapers to a point)."
  [dir ref]
  (let [u (m/v- ref (m/v* dir (m/dot ref dir)))]
    (if (> (m/magnitude u) 1e-6)
      (m/normalize u)
      (let [alt (if (> (js/Math.abs (nth dir 2)) 0.9) [1 0 0] [0 0 1])]
        (m/normalize (m/v- alt (m/v* dir (m/dot alt dir))))))))

(defn- transport-up
  "Parallel-transport `prev-up` from `prev-dir` to `new-dir`: rotate it by the
   minimal rotation aligning the directions (Rodrigues), then re-orthogonalize. A
   smooth, twist-free frame along the rail that never degenerates."
  [prev-up prev-dir new-dir]
  (let [d (max -1.0 (min 1.0 (m/dot prev-dir new-dir)))]
    (if (> d 0.9999)
      (safe-up new-dir prev-up)
      (let [axis (m/cross prev-dir new-dir)
            am (m/magnitude axis)]
        (if (< am 1e-6)
          (safe-up new-dir prev-up)                  ; ~opposite → just reproject
          (let [ax (m/v* axis (/ 1.0 am))
                ang (js/Math.acos d)
                ca (js/Math.cos ang) sa (js/Math.sin ang)
                rot (m/v+ (m/v* prev-up ca)
                          (m/v+ (m/v* (m/cross ax prev-up) sa)
                                (m/v* ax (* (m/dot ax prev-up) (- 1 ca)))))]
            (safe-up new-dir rot)))))))

(defn- nodes->commands-3d
  "Commands tracing the 3D nodes: per segment a (set-heading dir up)(f dist). The up
   is parallel-transported along the rail from a seeded initial up (frame derived
   from geometry — positions-only philosophy), so it stays smooth and non-degenerate.
   The trace is shifted so the first node sits at the origin (a relative rail).
   Coincident nodes are skipped."
  [nodes]
  (if (< (count nodes) 2)
    []
    (let [origin (:pos (first nodes))]
      (loop [cur [0 0 0]
             prev-dir nil
             prev-up nil
             ps (rest nodes)
             cmds []]
        (if (empty? ps)
          cmds
          (let [tgt (m/v- (:pos (first ps)) origin)
                delta (m/v- tgt cur)
                dist (m/magnitude delta)]
            (if (< dist 1e-6)
              (recur cur prev-dir prev-up (rest ps) cmds)
              (let [dir (m/normalize delta)
                    up* (if prev-dir
                          (transport-up prev-up prev-dir dir)
                          (safe-up dir [0 0 1]))]
                (recur tgt dir up* (rest ps)
                       (conj cmds
                             {:cmd :set-heading :args [dir up*]}
                             {:cmd :f :args [dist]}))))))))))

(defn- nodes->code-3d
  "The replacement source for a 3D edit: a (path (set-heading …)(f …) …) rail. The
   (edit-path …) marker is a stand-in for this."
  [nodes]
  (str "(path"
       (apply str (map #(str " " (cmd->code %)) (nodes->commands-3d nodes)))
       ")"))

;; -- arc-run recovery (re-editing a baked arc) ---------------------------
;; rec-arc-h* tessellates an arc into f/th steps, tagging the leading and trailing
;; half-rotations with :arc-cap :lead / :trail. Those tags ride along in the path
;; value's :commands, so we can find an arc run and collapse it back into ONE arc
;; node (belly = a point sampled on the run, which fits the exact circle).

(defn- in-plane-cmd?
  "A command that keeps the trace in the working plane (recoverable as an arc node)."
  [c]
  (contains? #{:f :th :rt :lt} (:cmd c)))

(defn- group-curve-runs
  "Collapse each tessellated curve run back into a single group so seed->nodes can
   rebuild it as one node:
   - a bezier carries a :pure {:cmd :bezier-to :c1 :c2 :end :span n} tag on its first
     command (added by rec-bezier-to*) → {:cmd :bezier-node :pure …}, skipping :span;
   - an arc is a :arc-cap :lead … :arc-cap :trail run of in-plane commands →
     {:cmd :arc-run :sub […]}.
   Out-of-plane curves are left as-is (they fall through to the normal drop)."
  [cmds]
  (loop [cs cmds out []]
    (if (empty? cs)
      out
      (let [c (first cs)]
        (cond
          (and (:pure c) (= :bezier-to (:cmd (:pure c))))
          (recur (drop (:span (:pure c)) cs) (conj out {:cmd :bezier-node :pure (:pure c)}))

          (= :lead (:arc-cap c))
          (let [[run more] (split-with #(not= :trail (:arc-cap %)) cs)
                run (vec (concat run (take 1 more)))   ; include the :trail command
                more (rest more)]
            (if (and (>= (count run) 2) (every? in-plane-cmd? run))
              (recur more (conj out {:cmd :arc-run :sub run}))
              (recur more (into out run))))

          :else (recur (rest cs) (conj out c)))))))

(defn- walk-arc-sub
  "Walk an arc run's in-plane sub-commands from [pos heading], collecting the f-step
   points (inclusive of the start). Returns {:pts [...] :heading exit-heading}. The
   points lie on the arc's circle, so any of them fits the exact arc (3-point)."
  [pos heading sub]
  (let [step (fn [{:keys [pos heading pts]} dir d]
               (let [np [(+ (first pos) (* (first dir) d))
                         (+ (second pos) (* (second dir) d))]]
                 {:pos np :heading heading :pts (conj pts np)}))]
    (reduce (fn [{:keys [heading] :as s} c]
              (case (:cmd c)
                :f  (step s heading (first (:args c)))
                :rt (step s (right-of heading) (first (:args c)))
                :lt (step s (left-of heading) (first (:args c)))
                :th (assoc s :heading (rotate-dir heading (first (:args c))))
                s))
            {:pos pos :heading heading :pts [pos]}
            sub)))

(defn- seed->nodes
  "Parse the seed path body into {:nodes [{:pos :heading :tail} …] :dropped […]}.
   f/th/set-heading drive positions and heading; rt/lt are in-plane strafes
   (heading kept); a baked arc run (:arc-cap-tagged f/th) is recovered as one arc
   node; mark/side-trip attach to the current node's :tail; a leading move-to
   anchors the start. The last node keeps its final (exit) heading. Throws on a
   non-leading move-to. Unsupported commands (beziers, u/tv/tr, …) are dropped."
  [seed-path]
  (let [cmds (when (and (map? seed-path) (= :path (:type seed-path)))
               (:commands seed-path))]
    (cond
      (empty? cmds)
      {:nodes (mapv (fn [p] {:pos p :heading nil :tail []}) default-nodes) :dropped []}

      (some (fn [[i c]] (and (pos? i) (= :move-to (:cmd c))))
            (map-indexed vector cmds))
      (throw (js/Error. "edit-path: (move-to …) is only supported as the first command."))

      :else
      (let [[sx sy] (let [c0 (first cmds)]
                      (if (= :move-to (:cmd c0))
                        (let [t (first (:args c0))] [(first t) (second t)])
                        [0 0]))
            move (fn [st delta-dir d]
                   (let [pos (:pos st) heading (:heading st)
                         np [(+ (first pos) (* (first delta-dir) d))
                             (+ (second pos) (* (second delta-dir) d))]]
                     (-> st (assoc :pos np)
                         (update :nodes conj {:pos np :heading heading :tail []}))))
            res (reduce
                 (fn [{:keys [heading nodes] :as st} cmd]
                   (case (:cmd cmd)
                     :move-to st                       ; leading one already used
                     :f  (move st heading (first (:args cmd)))
                     :rt (move st (right-of heading) (first (:args cmd)))
                     :lt (move st (left-of heading) (first (:args cmd)))
                     :th (assoc st :heading (rotate-dir heading (first (:args cmd))))
                     :set-heading (let [[h _] (:args cmd)]
                                    (assoc st :heading (v2-norm [(first h) (second h)])))
                     ;; a baked bezier → one bezier node (resolved c1/c2/end from
                     ;; the :pure tag; planar path → take x,y). Exit heading ∝ end−c2.
                     :bezier-node
                     (let [{:keys [c1 c2 end]} (:pure cmd)
                           xy (fn [p] [(nth p 0) (nth p 1)])
                           ehead (v2-norm [(- (nth end 0) (nth c2 0))
                                           (- (nth end 1) (nth c2 1))])]
                       (-> st
                           (assoc :pos (xy end) :heading ehead)
                           (update :nodes conj {:pos (xy end) :heading nil :tail []
                                                :bez {:c1 (xy c1) :c2 (xy c2)}})))
                     ;; a baked arc run → one arc node (belly sampled mid-run)
                     :arc-run
                     (let [w (walk-arc-sub (:pos st) heading (:sub cmd))
                           pts (:pts w)
                           end (last pts)
                           belly (nth pts (quot (count pts) 2))]
                       (-> st
                           (assoc :pos end :heading (:heading w))
                           (update :nodes conj
                                   (cond-> {:pos end :heading nil :tail []}
                                     (>= (count pts) 3) (assoc :arc {:belly belly})))))
                     (:mark :side-trip)
                     (update-in st [:nodes (dec (count nodes)) :tail] conj cmd)
                     (update st :dropped conj (:cmd cmd))))
                 {:pos [sx sy] :heading [1 0]
                  :nodes [{:pos [sx sy] :heading nil :tail []}] :dropped []}
                 (group-curve-runs cmds))
            ;; the last node keeps the final (exit) heading — captures trailing turns
            nodes (let [ns (:nodes res)]
                    (assoc-in ns [(dec (count ns)) :heading] (:heading res)))]
        {:nodes nodes :dropped (vec (distinct (:dropped res)))}))))

(defn- project-2d-to-xy
  "Normalize a :2d path value (its commands trace the (right,up) plane) into an
   (a,b)-plane path that seed->nodes can read — the inverse of the path-2d macro +
   nodes->code bake. Drops the leading (th -90) seed, maps tv→th / arc-v→arc-h, and
   projects every 3D coordinate (set-heading directions and the :pure bezier rider's
   c1/c2/end) onto shape coords (a,b) = (-y, z). The :arc-cap / :pure rider tags ride
   along so arc and bezier recovery keep working on a re-opened :2d path.

   A :3d path is returned unchanged (its commands are already in (a,b) = (x,y))."
  [path]
  (if-not (= :2d (:species path))
    path
    (let [cmds (:commands path)
          ;; drop the leading seed the path-2d macro prepends ((th -90))
          cmds (if (and (= :th (:cmd (first cmds)))
                        (= -90 (first (:args (first cmds)))))
                 (rest cmds)
                 cmds)
          ;; A leading (move-to [a b]) anchors the start; seed->nodes traces the
          ;; straight/arc nodes from there, but a bezier's :pure rider carries
          ;; origin-frame coords (the recorder doesn't apply move-to while
          ;; recording), so offset those by the anchor to land in the same frame.
          mv (some (fn [{:keys [cmd args]}] (when (= :move-to cmd) (first args))) cmds)
          ox (if mv (first mv) 0)
          oy (if mv (second mv) 0)
          proj-pt (fn [p] [(- (nth p 1)) (nth p 2)])              ; [x y z] → [-y z]
          proj-pt+off (fn [p] (let [[a b] (proj-pt p)] [(+ a ox) (+ b oy)]))
          conv (fn conv [{:keys [cmd args] :as c}]
                 (case cmd
                   :tv          (assoc c :cmd :th)
                   :arc-v       (assoc c :cmd :arc-h)
                   :set-heading (assoc c :args [(proj-pt (first args))])
                   :side-trip   (assoc c :args [(update (first args) :commands #(mapv conv %))])
                   c))
          proj-rider (fn [c]
                       (if-let [pure (:pure c)]
                         (assoc c :pure (-> pure
                                            (update :c1 proj-pt+off)
                                            (update :c2 proj-pt+off)
                                            (update :end proj-pt+off)))
                         c))]
      {:type :path :commands (mapv (comp proj-rider conv) cmds)})))

;; ============================================================
;; Working plane (the turtle's stamp plane at the call site)
;; ============================================================
;; Nodes are stored in the shape's own 2D coordinates — the same frame the board
;; (and path-to-shape) use — NOT raw world XY. The plane is the turtle pose's
;; stamp plane: x-axis = right (heading × up), y-axis = up. By default that is the
;; YZ world plane (right = −Y, up = +Z), so horizontal arrows move along world Y
;; and vertical arrows along Z, and world X is never touched.

(defn- plane-basis
  "World-space basis of the working plane from a {:position :heading :up} pose."
  [{:keys [position heading up]}]
  {:origin position
   :px (m/normalize (m/cross heading up))
   :py up})

(defn- world->plane
  "Project a world point to 2D [px py] in the working plane."
  [{:keys [origin px py]} w]
  (let [d (m/v- w origin)]
    [(m/dot d px) (m/dot d py)]))

(defn- plane->world
  "Lift a 2D node [px py] back to a world point on the working plane."
  [{:keys [origin px py]} [a b]]
  (m/v+ origin (m/v+ (m/v* px a) (m/v* py b))))

;; -- mode seam: 2D edits in a fixed plane (nodes store [a b]); 3D edits a rail in
;; world space (nodes store [x y z]) within a SELECTABLE working plane of the
;; turtle frame, named by its normal: :f ⊥forward = (right,up) = the 2D plane,
;; :r ⊥right = (forward,up), :u ⊥up = (forward,right).

(defn- three-d? [s] (= :3d (:mode s)))

(defn- active-basis
  "World-space basis {:origin :px :py} of the session's active working plane."
  [s]
  (let [{:keys [position heading up]} (:pose s)]
    (if (three-d? s)
      (let [right (m/normalize (m/cross heading up))]
        (case (:plane s :f)
          :f {:origin position :px right        :py up}
          :r {:origin position :px heading      :py up}
          :u {:origin position :px heading      :py right}))
      (plane-basis (:pose s)))))

(defn- node->world
  "World position of a node: in 3D :pos is already world; in 2D lift [a b]."
  [s node]
  (if (three-d? s) (:pos node) (plane->world (active-basis s) (:pos node))))

(defn- world->stored
  "Convert a world point on the active plane to a node's stored :pos for the mode:
   3D keeps world, 2D projects to [a b]."
  [s w]
  (if (three-d? s) w (world->plane (active-basis s) w)))

(defn- node-plane-pos
  "Active-plane [a b] coords of a node, for screen-ish hit-testing: 2D :pos is
   already plane coords; 3D projects the world :pos onto the active plane."
  [s node]
  (if (three-d? s) (world->plane (active-basis s) (:pos node)) (:pos node)))

(defn- active-plane-normal [s]
  (let [b (active-basis s)] (m/normalize (m/cross (:px b) (:py b)))))

;; ============================================================
;; Ephemeral geometry
;; ============================================================

;; A faint grid drawn in the active working plane, centred on the selected node, so
;; you can read the plane's orientation and gauge distances while editing in 3D.
(def ^:private grid-color 0x3a4a66)
(def ^:private grid-step  10)
(def ^:private grid-half  60)

(defn- plane-grid-lines
  "Grid lines spanning the active plane (px,py) centred at `c`."
  [{:keys [px py]} c]
  (let [ks (range (- grid-half) (inc grid-half) grid-step)
        seg (fn [u v k]
              (let [a (m/v+ c (m/v+ (m/v* u k) (m/v* v (- grid-half))))
                    b (m/v+ c (m/v+ (m/v* u k) (m/v* v grid-half)))]
                {:from a :to b :color grid-color}))]
    (vec (concat (map #(seg px py %) ks)
                 (map #(seg py px %) ks)))))

(defn- render-3d!
  "Redraw the 3D rail (straight-segment MVP): node positions in world space, drawn
   as rings oriented to the active working plane — the ring foreshortens to a line
   when the plane is edge-on to the camera, signalling 'orbit to edit here'. A faint
   grid in the active plane (centred on the selected node) gives a spatial reference."
  [s]
  (let [{:keys [nodes selected]} s
        basis (active-basis s)
        normal (m/normalize (m/cross (:px basis) (:py basis)))
        pts (mapv #(node->world s %) nodes)
        n (count pts)
        ;; grid stays anchored at the plane origin in-plane (fixed), shifted only
        ;; along the normal (the axis that moved) to sit at the selected node's depth
        grid-c (let [o (:origin basis)]
                 (if (and (seq pts) (< selected n))
                   (m/v+ o (m/v* normal (m/dot (m/v- (nth pts selected) o) normal)))
                   o))
        grid-lines (plane-grid-lines basis grid-c)
        ;; a 3D path is an OPEN rail (extrude/loft trajectory) — no closing segment
        seg-lines (mapv (fn [i] {:from (nth pts (dec i)) :to (nth pts i) :color line-color})
                        (range 1 n))
        node-dots (mapv (fn [i pw]
                          (let [marked? (seq (:tail (nth nodes i)))
                                start? (zero? i) exit? (= i (dec n))]
                            {:pos pw :ring true :normal normal
                             :radius (if (= i selected) node-radius-sel node-radius)
                             :color (cond marked?           mark-color
                                          (or start? exit?) exit-color
                                          (= i selected)    sel-color
                                          :else             node-color)}))
                        (range n) pts)]
    (viewport/show-preview! [{:type :lines :data grid-lines}
                             {:type :lines :data (vec seg-lines) :on-top true}
                             {:type :dots :data node-dots}])))

(defn- render!
  "Redraw the ephemeral path (straight + bezier segments), bezier handles, and the
   node dots from the current session state."
  []
  (if (three-d? @session)
    (render-3d! @session)
    (when-let [{:keys [nodes selected pose]} @session]
      (let [basis (plane-basis pose)
            normal (m/cross (:px basis) (:py basis))   ; working-plane normal (start ring)
            ->w #(plane->world basis %)
            pts (mapv #(->w (node-pos %)) nodes)
            n (count pts)
          ;; per-segment lines: bezier-tessellated / arc-tessellated / straight
            seg-lines (mapcat
                       (fn [i]
                         (let [a (nth pts (dec i)) b (nth pts i)
                               a2 (node-pos (nth nodes (dec i))) b2 (node-pos (nth nodes i))]
                           (cond
                             (:bez (nth nodes i))
                             (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                   c1w (->w c1) c2w (->w c2) steps 24
                                   cp (mapv #(bezier/cubic-bezier-point a c1w c2w b (/ % steps))
                                            (range (inc steps)))]
                               (mapv (fn [p q] {:from p :to q :color line-color}) cp (rest cp)))

                             (:arc (nth nodes i))
                             (if-let [tp (arc-tess a2 (:belly (:arc (nth nodes i))) b2 24)]
                               (let [tw (mapv ->w tp)]
                                 (mapv (fn [p q] {:from p :to q :color line-color}) tw (rest tw)))
                               [{:from a :to b :color line-color}])

                             :else [{:from a :to b :color line-color}])))
                       (range 1 n))
            closing (when (>= n 3) [{:from (last pts) :to (first pts) :color close-color}])
          ;; handle lines: bezier endpoint → control point (c1 magenta when its
          ;; start node is a cusp); arc chord-midpoint → belly
            handle-lines (mapcat
                          (fn [i]
                            (cond
                              (:bez (nth nodes i))
                              (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                    c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-color)]
                                [{:from (nth pts (dec i)) :to (->w c1) :color c1col}
                                 {:from (nth pts i) :to (->w c2) :color handle-color}])

                              (:arc (nth nodes i))
                              (let [a (nth pts (dec i)) b (nth pts i)
                                    cmid (m/v* (m/v+ a b) 0.5)]
                                [{:from cmid :to (->w (:belly (:arc (nth nodes i)))) :color handle-color}])))
                          (range 1 n))
            segs (vec (concat seg-lines closing handle-lines))
            node-dots (mapv (fn [i pw]
                              (let [marked? (seq (:tail (nth nodes i)))
                                    start?  (zero? i)
                                    exit?   (= i (dec n))]
                                (cond-> {:pos pw
                                         :radius (if (= i selected) node-radius-sel node-radius)
                                         :color  (cond marked?            mark-color
                                                       (or start? exit?)  exit-color
                                                       (= i selected)     sel-color
                                                       :else              node-color)}
                                  start? (assoc :ring true :normal normal))))
                            (range n) pts)
            handle-dots (mapcat
                         (fn [i]
                           (cond
                             (:bez (nth nodes i))
                             (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                   c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-color)]
                             ;; control points are little squares (shape sets them
                             ;; apart from the round nodes — no extra colour needed)
                               [{:pos (->w c1) :radius handle-radius :color c1col :square true :normal normal}
                                {:pos (->w c2) :radius handle-radius :color handle-color :square true :normal normal}])

                             (:arc (nth nodes i))
                             [{:pos (->w (:belly (:arc (nth nodes i)))) :radius handle-radius
                               :color handle-color :square true :normal normal}]))
                         (range 1 n))
            dots (vec (concat node-dots handle-dots))]
        (viewport/show-preview! [{:type :lines :data segs :on-top true}
                                 {:type :dots :data dots}])))))

;; ============================================================
;; Live re-eval (downstream geometry)
;; ============================================================

(defn- current-code []
  (if (three-d? @session)
    (nodes->code-3d (:nodes @session))
    (nodes->code (:nodes @session))))

(defn- find-marker []
  (modal/find-form-bounds (cm/get-value) marker-prefix))

(defn- build-modified-script []
  (let [[from to] (find-marker)]
    (modal/splice-source (cm/get-value) from to (current-code))))

(defn- live-reeval! []
  (viewport/clear-rulers!)
  (modal/reeval-script! build-modified-script "edit-path eval error:" false)
  (render!))

(defn- debounced-reeval! []
  (when-let [t (:eval-timeout @session)] (js/clearTimeout t))
  (swap! session assoc :eval-timeout
         (js/setTimeout (fn [] (live-reeval!)) 80)))

(defn- refresh-preview! []
  (render!)
  (debounced-reeval!))

;; ============================================================
;; UI panel
;; ============================================================

(defn update-panel! []
  (when-let [panel (:panel-el @session)]
    (let [s @session]
      (when-let [el (.querySelector panel ".ep-info")]
        (set! (.-textContent el)
              (str "nodes: " (count (:nodes s)) " · sel: " (:selected s)
                   (when (three-d? s)
                     (let [sel (:selected s)
                           p (some-> (:nodes s) (nth sel nil) :pos)
                           r1 #(/ (js/Math.round (* % 10)) 10)]
                       (str " · plane: " (name (:plane s :f)) " (f/r/u)"
                            (when (and p (vector? p))
                              (str " · [" (r1 (nth p 0)) " " (r1 (nth p 1)) " " (r1 (nth p 2)) "]"))))))))
      (when-let [el (.querySelector panel ".ep-step")]
        (let [buf (:digit-buffer s)]
          (set! (.-textContent el)
                (if (seq buf) (str buf "_") (str (:step s) "mm")))))
      ;; 3D precision fields: the selected node's incoming segment (len + in-plane
      ;; angle). Node 0 (the anchor) and 2D have none — disabled. Don't clobber a
      ;; field the user is currently editing.
      (when (three-d? s)
        (let [i (:selected s) n (count (:nodes s))
              seg? (and (>= i 1) (< i n))
              focused (.-activeElement js/document)
              r1 #(/ (js/Math.round (* % 10)) 10)]
          (doseq [[cls val] [[".ep-len" (when seg? (seg-len s i))]
                             [".ep-ang" (when seg? (seg-angle-deg s i))]]]
            (when-let [^js el (.querySelector panel cls)]
              (set! (.-disabled el) (not seg?))
              (cond
                (not seg?)        (set! (.-value el) "")
                (not= el focused) (set! (.-value el) (str (r1 val)))))))))))

(defn- create-panel! []
  (let [panel (.createElement js/document "div")
        td? (three-d? @session)]
    (set! (.-id panel) "edit-path-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>" (if td? "edit-path" "edit-path-2d")
               "<span class='pilot-mode-badge'>" (if td? "3D rail" "polyline") "</span></div>"
               "<div class='pilot-controls'>"
               "<span class='ep-info'>nodes: 0 · sel: 0</span>"
               "<span>Step: <span class='ep-step'>5mm</span></span>"
               "</div>"
               "<div class='pilot-commands'>"
               (if td?
                 (str "click: add · drag node: move · Shift+drag: axis-lock · Tab: next · "
                      "f/r/u: plane · ←→↑↓: move in plane · ⌘Z: undo · Del: delete · "
                      "Enter: OK · Esc: cancel")
                 (str "click: add · drag node/handle: move · Tab: next · c: curve · "
                      "a: arc · x: cusp · Ins/i: split · ←→↑↓: node · Shift+↑↓: c1 · Alt+↑↓: c2 · "
                      "⌘Z: undo · Del: delete · Enter: OK · Esc: cancel"))
               "</div>"
               ;; 3D precision: the selected node's incoming segment (len + in-plane angle)
               (when td?
                 (str "<div class='ep-precision' style='display:flex;gap:10px;align-items:center'>"
                      "<label>len <input class='ep-len' type='number' step='1' "
                      "style='width:64px' disabled></label>"
                      "<label>ang° <input class='ep-ang' type='number' step='1' "
                      "style='width:64px' disabled></label>"
                      "</div>"))
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-ok ep-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel ep-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".ep-ok") "click" (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".ep-cancel") "click" (fn [_] (cancel!)))
    (when-let [^js len-in (.querySelector panel ".ep-len")]
      (.addEventListener len-in "change"
                         (fn [^js e] (let [v (js/parseFloat (.. e -target -value))
                                           i (:selected @session)]
                                       (when (js/isFinite v) (set-seg-len! i v))))))
    (when-let [^js ang-in (.querySelector panel ".ep-ang")]
      (.addEventListener ang-in "change"
                         (fn [^js e] (let [v (js/parseFloat (.. e -target -value))
                                           i (:selected @session)]
                                       (when (js/isFinite v) (set-seg-angle! i v))))))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Undo
;; ============================================================
;; A simple snapshot stack of {:nodes :selected}. push-undo! is called once at the
;; START of each user action (before it mutates), so a drag is one undo step, not
;; one per pointermove. Low-level helpers (move-node!, insert-node!) do NOT push;
;; their callers do.

(def ^:private undo-limit 100)

(defn- push-undo! []
  (let [snap (select-keys @session [:nodes :selected])]
    (swap! session update :undo
           (fn [u] (let [u (conj (vec u) snap)]
                     (if (> (count u) undo-limit)
                       (subvec u (- (count u) undo-limit))
                       u))))))

(defn- undo! []
  (let [u (:undo @session)]
    (if (seq u)
      (let [snap (peek u)]
        (swap! session #(-> %
                            (assoc :nodes (:nodes snap)
                                   :selected (:selected snap)
                                   :undo (pop u))
                            (dissoc :dragging :dragging-handle)))
        (refresh-preview!)
        (update-panel!))
      (state/capture-println "edit-path: nothing to undo"))))

;; ============================================================
;; Editing operations
;; ============================================================

(defn- append-node!
  "Append a node at 2D plane coord `pos` at the end of the path and select it."
  [pos]
  (push-undo!)
  (let [nodes (vec (:nodes @session))]
    (swap! session assoc :nodes (conj nodes {:pos pos :tail []})
           :selected (count nodes))
    (refresh-preview!)
    (update-panel!)))

(defn- insert-node!
  "Insert a node at 2D plane coord `pos` at index `idx` (split) and select it."
  [idx pos]
  (swap! session update :nodes
         #(vec (concat (take idx %) [{:pos pos :tail []}] (drop idx %))))
  (swap! session assoc :selected idx)
  (refresh-preview!)
  (update-panel!))

(defn- mid2 [[ax ay] [bx by]] [(/ (+ ax bx) 2) (/ (+ ay by) 2)])

(defn- split-segment!
  "Insert a node at the midpoint of the segment ENTERING the selected node (between
   sel-1 and sel) and select it. A straight segment splits at the chord midpoint; a
   bezier splits with de Casteljau at t=0.5 so the curve's shape is preserved.

   With the FIRST node selected (no incoming segment) the **closing segment**
   (last → first) is split instead: a node is appended in the tail, at the midpoint
   between the last and first nodes. This is consistent with the mouse (the dim
   closing segment is visible and divisible) and gives a way to extend the tail;
   used in a `path-to-shape` it lands exactly on the polygon's closing edge. With
   only two nodes there is no closing loop, so the single segment is split."
  []
  (let [s @session nodes (:nodes s) sel (:selected s) n (count nodes)]
    (when (>= n 2)
      (if (and (zero? sel) (>= n 3))
        ;; closing segment is straight → append the chord midpoint (append-node!
        ;; pushes undo and selects the new node).
        (append-node! (mid2 (node-pos (last nodes)) (node-pos (first nodes))))
        (let [i (if (pos? sel) sel 1)          ; new node goes at index i (before node i)
              a (nth nodes (dec i))
              b (nth nodes i)
              pa (node-pos a) pb (node-pos b)]
          (push-undo!)
          (cond
            ;; bezier: de Casteljau split at t=0.5 (curve shape preserved)
            (:bez b)
            (let [{:keys [c1 c2]} (:bez b)
                  m01 (mid2 pa c1) m12 (mid2 c1 c2) m23 (mid2 c2 pb)
                  m012 (mid2 m01 m12) m123 (mid2 m12 m23)
                  mp (mid2 m012 m123)
                  newn {:pos mp :tail [] :bez {:c1 m01 :c2 m012}}]
              (swap! session
                     (fn [s]
                       (-> s
                           (assoc-in [:nodes i :bez] {:c1 m123 :c2 m23})
                           (update :nodes #(vec (concat (take i %) [newn] (drop i %))))
                           (assoc :selected i))))
              (swap! session update :nodes reconstrain-handles)
              (refresh-preview!) (update-panel!))

            ;; arc: split into two arcs at t=0.5, each keeping a belly on the
            ;; original circle (the t=0.25 / t=0.75 points) → same overall curve
            (:arc b)
            (if-let [tp (arc-tess pa (:belly (:arc b)) pb 4)]
              (let [newn {:pos (nth tp 2) :tail [] :arc {:belly (nth tp 1)}}]
                (swap! session
                       (fn [s]
                         (-> s
                             (assoc-in [:nodes i :arc :belly] (nth tp 3))
                             (update :nodes #(vec (concat (take i %) [newn] (drop i %))))
                             (assoc :selected i))))
                (refresh-preview!) (update-panel!))
              (insert-node! i (mid2 pa pb)))

            :else
            (insert-node! i (mid2 pa pb)))))))) ; insert-node! selects i

(defn- notable?
  "A node whose orientation is preserved on move: it carries a mark/side-trip, or
   it is the last (exit) node."
  [nodes idx]
  (or (seq (:tail (nth nodes idx)))
      (= idx (dec (count nodes)))))

(defn- pinned?
  "In 3D the first node is the rail's anchor (the origin of the relative path) — it
   is fixed at the origin and cannot be moved; its world placement comes from the
   consuming turtle pose (moves / cp-*)."
  [s idx]
  (and (three-d? s) (zero? idx)))

(defn- move-node!
  "Set node `idx` to absolute 2D `new-pos`, dragging its attached bezier handles
   (its incoming :c2 and the next segment's :c1) along by the same delta, and
   dropping its explicit heading unless it's notable."
  [idx new-pos]
  (when-not (pinned? @session idx)
    (let [nodes (:nodes @session)
          [ox oy] (node-pos (nth nodes idx))
          dx (- (first new-pos) ox) dy (- (second new-pos) oy)
          tr (fn [[x y]] [(+ x dx) (+ y dy)])]
      (swap! session
             (fn [s]
               (cond-> (assoc-in s [:nodes idx :pos] new-pos)
                 (get-in nodes [idx :bez])       (update-in [:nodes idx :bez :c2] tr)
                 (get-in nodes [(inc idx) :bez]) (update-in [:nodes (inc idx) :bez :c1] tr))))
      (when-not (notable? (:nodes @session) idx)
        (swap! session assoc-in [:nodes idx :heading] nil))
      ;; moving a node changes incoming tangents → re-snap smooth bezier handles
      (swap! session update :nodes reconstrain-handles))))

(defn- nudge!
  "Move the selected node by sign·step along the active plane's axis 0 (px) / 1 (py).
   In 3D the node moves in world space along that plane axis; in 2D along [a b]."
  [axis sign]
  (let [s @session i (:selected s) step (:step s)]
    (when (and (seq (:nodes s)) (< i (count (:nodes s))) (not (pinned? s i)))
      (push-undo!)
      (let [np (if (three-d? s)
                 (let [b (active-basis s)
                       ax (if (zero? axis) (:px b) (:py b))]
                   (m/v+ (:pos (nth (:nodes s) i)) (m/v* ax (* sign step))))
                 (let [[x y] (node-pos (nth (:nodes s) i))]
                   (if (zero? axis) [(+ x (* sign step)) y] [x (+ y (* sign step))])))]
        (move-node! i np))
      (refresh-preview!)
      (update-panel!))))

;; -- 3D precision fields: the selected node's incoming segment as length + angle.
;; Length is the segment magnitude; angle is measured IN the active plane — relative
;; to the previous segment for node ≥ 2, else absolute (vs the plane's px axis).

(defn- rot-axis
  "Rotate vector v around unit axis by ang radians (Rodrigues)."
  [axis v ang]
  (let [ca (js/Math.cos ang) sa (js/Math.sin ang)]
    (m/v+ (m/v* v ca)
          (m/v+ (m/v* (m/cross axis v) sa)
                (m/v* axis (* (m/dot axis v) (- 1 ca)))))))

(defn- seg-dir [s i]
  (m/normalize (m/v- (:pos (nth (:nodes s) i)) (:pos (nth (:nodes s) (dec i))))))

(defn- in-plane-angle [s v]
  (let [b (active-basis s)]
    (js/Math.atan2 (m/dot v (:py b)) (m/dot v (:px b)))))

(defn- seg-len [s i]
  (m/magnitude (m/v- (:pos (nth (:nodes s) i)) (:pos (nth (:nodes s) (dec i))))))

(defn- seg-angle-deg
  "Incoming-segment angle (degrees) of node i in the active plane: relative to the
   previous segment for i ≥ 2, else absolute. Normalized to (-180,180]."
  [s i]
  (let [cur (in-plane-angle s (seg-dir s i))
        ref (if (>= i 2) (in-plane-angle s (seg-dir s (dec i))) 0)
        deg (* (- cur ref) (/ 180 js/Math.PI))]
    (- (mod (+ deg 180) 360) 180)))

(defn- set-seg-len! [i len]
  (let [s @session]
    (when (and (>= i 1) (< i (count (:nodes s))) (pos? len))
      (push-undo!)
      (let [prev (:pos (nth (:nodes s) (dec i)))]
        (move-node! i (m/v+ prev (m/v* (seg-dir s i) len))))
      (refresh-preview!) (update-panel!))))

(defn- set-seg-angle! [i deg]
  (let [s @session]
    (when (and (>= i 1) (< i (count (:nodes s))))
      (push-undo!)
      (let [prev (:pos (nth (:nodes s) (dec i)))
            cur-v (m/v- (:pos (nth (:nodes s) i)) prev)
            len (m/magnitude cur-v)
            ref (if (>= i 2) (in-plane-angle s (seg-dir s (dec i))) 0)
            target (+ ref (* deg (/ js/Math.PI 180)))
            delta (- target (in-plane-angle s cur-v))
            new-dir (rot-axis (active-plane-normal s) (m/normalize cur-v) delta)]
        (move-node! i (m/v+ prev (m/v* new-dir len))))
      (refresh-preview!) (update-panel!))))

(defn- nudge-handle!
  "Move control point `which` (:c1/:c2) of the selected node's bezier by sign·step
   along axis 0=x / 1=y, then re-snap (a smooth c1 stays on its tangent = length)."
  [which axis sign]
  (let [s @session i (:selected s) step (:step s)]
    (when (get-in (:nodes s) [i :bez which])
      (push-undo!)
      (swap! session update-in [:nodes i :bez which axis] + (* sign step))
      (swap! session update :nodes reconstrain-handles)
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-bezier!
  "Toggle the selected node's incoming segment between straight and cubic bezier.
   On enabling, the control points start at 1/3 and 2/3 along the chord; the start
   handle (c1) is then snapped to the start node's tangent."
  []
  (let [s @session i (:selected s) nodes (:nodes s)]
    (when (and (pos? i) (< i (count nodes)))
      (push-undo!)
      (if (:bez (nth nodes i))
        (swap! session update-in [:nodes i] dissoc :bez)
        (let [[ax ay] (node-pos (nth nodes (dec i)))
              [bx by] (node-pos (nth nodes i))
              along (fn [t] [(+ ax (* (- bx ax) t)) (+ ay (* (- by ay) t))])]
          ;; bezier and arc are mutually exclusive segment types
          (swap! session update-in [:nodes i] dissoc :arc)
          (swap! session assoc-in [:nodes i :bez] {:c1 (along (/ 1.0 3)) :c2 (along (/ 2.0 3))})))
      (swap! session update :nodes reconstrain-handles)
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-arc!
  "Toggle the selected node's incoming segment between straight and a circular arc.
   On enabling, the belly handle starts bulged ~20% of the chord to one side; drag
   it to any point the arc should pass through."
  []
  (let [s @session i (:selected s) nodes (:nodes s)]
    (when (and (pos? i) (< i (count nodes)))
      (push-undo!)
      (if (:arc (nth nodes i))
        (swap! session update-in [:nodes i] dissoc :arc)
        (let [[ax ay] (node-pos (nth nodes (dec i)))
              [bx by] (node-pos (nth nodes i))
              mx (/ (+ ax bx) 2.0) my (/ (+ ay by) 2.0)
              dx (- bx ax) dy (- by ay)
              chord (js/Math.sqrt (+ (* dx dx) (* dy dy)))
              [px py] (if (> chord 1e-6) [(/ (- dy) chord) (/ dx chord)] [0 1])
              bulge (* 0.2 chord)
              belly [(+ mx (* px bulge)) (+ my (* py bulge))]]
          (swap! session (fn [st]
                           (-> st
                               (update-in [:nodes i] dissoc :bez)
                               (assoc-in [:nodes i :arc] {:belly belly}))))))
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-cusp!
  "Toggle the selected node between smooth and cusp. A cusp frees its OUTGOING
   bezier handle (c1 of the next segment) so the curve can leave at any angle."
  []
  (let [s @session i (:selected s)]
    (when (< i (count (:nodes s)))
      (push-undo!)
      (swap! session update-in [:nodes i :smooth?] #(if (false? %) true false))
      (swap! session update :nodes reconstrain-handles)
      (refresh-preview!)
      (update-panel!))))

(defn- delete-node! []
  (let [s @session nodes (:nodes s) i (:selected s)]
    (when (seq nodes)
      (if (seq (:tail (nth nodes i)))
        ;; A node carrying a mark / side-trip is protected (shown green): deleting
        ;; it would drop that data, so refuse rather than lose it.
        (state/capture-println
         "edit-path: this node carries a mark/side-trip (green) and can't be deleted.")
        (do
          (push-undo!)
          (let [nodes' (vec (concat (take i nodes) (drop (inc i) nodes)))]
            (swap! session assoc :nodes nodes'
                   :selected (max 0 (min (dec (count nodes')) i)))
            (refresh-preview!)
            (update-panel!)))))))

;; ============================================================
;; Mouse: click empty → add, click/drag a node → select/move
;; ============================================================
;; Orbit is preserved: grabbing a node disables the controls for the drag (and
;; re-enables on release); a drag on empty space still orbits, and a plain click
;; on empty space (no movement) adds a node.

(defn- nearest-node-d2
  "[idx d2] of the node closest to active-plane point p, or nil if there are none."
  [s [px py]]
  (let [nodes (:nodes s)]
    (when (seq nodes)
      (let [d2 (fn [i] (let [[nx ny] (node-plane-pos s (nth nodes i))
                             dx (- nx px) dy (- ny py)]
                         (+ (* dx dx) (* dy dy))))
            idx (apply min-key d2 (range (count nodes)))]
        [idx (d2 idx)]))))

(defn- nearest-node-screen
  "[idx d2-pixels] of the 3D node whose screen projection is closest to the pointer
   event, or nil. Screen-space so a node at any depth (any editing plane) can be
   grabbed if it's visible under the cursor."
  [s ^js e]
  (let [nodes (:nodes s)
        mx (.-clientX e) my (.-clientY e)]
    (when (seq nodes)
      (let [d2 (fn [i] (if-let [[sx sy] (viewport/world->screen (:pos (nth nodes i)))]
                         (let [dx (- sx mx) dy (- sy my)] (+ (* dx dx) (* dy dy)))
                         js/Infinity))
            idx (apply min-key d2 (range (count nodes)))]
        [idx (d2 idx)]))))

(defn- seg-closest
  "Closest point on segment a→b to p, with squared distance. {:point :d2}."
  [[ax ay] [bx by] [px py]]
  (let [dx (- bx ax) dy (- by ay)
        len2 (+ (* dx dx) (* dy dy))
        t (if (pos? len2)
            (max 0 (min 1 (/ (+ (* (- px ax) dx) (* (- py ay) dy)) len2)))
            0)
        cx (+ ax (* t dx)) cy (+ ay (* t dy))]
    {:point [cx cy]
     :d2 (+ (* (- px cx) (- px cx)) (* (- py cy) (- py cy)))}))

(defn- nearest-segment
  "Nearest path segment to plane point p, within seg-threshold. Returns
   {:index <insert-index> :point [x y]} or nil. Includes the closing segment."
  [nodes p]
  (when (>= (count nodes) 2)
    (let [n (count nodes)
          pairs (concat (map (fn [i] [i (inc i)]) (range (dec n)))
                        (when (>= n 3) [[(dec n) 0]]))
          cands (map (fn [[i j]]
                       (let [r (seg-closest (node-pos (nth nodes i)) (node-pos (nth nodes j)) p)]
                         (assoc r :index (inc i))))
                     pairs)
          best (apply min-key :d2 cands)]
      (when (<= (:d2 best) (* seg-threshold seg-threshold))
        best))))

(defn- nearest-handle
  "The bezier control handle or arc belly closest to plane point p within
   handle-snap, as {:idx i :which :c1/:c2/:belly}, or nil."
  [nodes [px py]]
  (let [cands (mapcat (fn [i]
                        (cond
                          (:bez (nth nodes i))
                          (let [{:keys [c1 c2]} (:bez (nth nodes i))]
                            [{:idx i :which :c1 :pos c1} {:idx i :which :c2 :pos c2}])
                          (:arc (nth nodes i))
                          [{:idx i :which :belly :pos (:belly (:arc (nth nodes i)))}]))
                      (range (count nodes)))]
    (when (seq cands)
      (let [d2 (fn [h] (let [[hx hy] (:pos h)]
                         (+ (* (- hx px) (- hx px)) (* (- hy py) (- hy py)))))
            best (apply min-key d2 cands)]
        (when (<= (d2 best) (* handle-snap handle-snap))
          (select-keys best [:idx :which]))))))

(defn- plain-click? [^js e]
  (and (not (.-altKey e)) (not (.-shiftKey e))
       (not (.-ctrlKey e)) (not (.-metaKey e))))

(defn- click-plane-point
  "World point where the pointer ray meets the active working plane. In 3D the plane
   normal is the active-plane normal and it passes through the drag anchor (the node
   being dragged, to preserve its depth) or the pose origin; in 2D it's the stamp
   plane through the pose."
  [^js e s]
  (if (three-d? s)
    (viewport/raycast-plane-point e (or (:drag-anchor s) (:position (:pose s)))
                                  (active-plane-normal s))
    (let [pose (:pose s)]
      (viewport/raycast-plane-point e (:position pose) (:heading pose)))))

(defn- grab-node! [idx]
  ;; No undo push here: a bare click that only selects shouldn't add an undo step.
  ;; The push is deferred to the first actual drag move (see on-pointer-move).
  ;; In 3D, anchor the drag plane at the node's depth so it moves within its plane.
  (let [anchor (when (three-d? @session) (:pos (nth (:nodes @session) idx)))]
    (swap! session assoc :selected idx :dragging idx :drag-anchor anchor))
  (viewport/set-controls-enabled! false)
  (render!) (update-panel!))

(defn- on-pointer-down [^js e]
  (when (and (:entered? @session) (plain-click? e))
    (let [s @session
          basis (active-basis s)
          w (click-plane-point e s)
          p2 (when w (world->plane basis w))
          two-d? (not (three-d? s))
          handle (when (and two-d? p2) (nearest-handle (:nodes s) p2))
          ;; 3D grabs in screen space (pixels); 2D in plane units.
          [n-idx n-d2] (if two-d?
                         (when p2 (nearest-node-d2 s p2))
                         (nearest-node-screen s e))
          node-snap2 (if two-d? (* node-snap node-snap) (* node-px-snap node-px-snap))
          seg (when (and two-d? p2) (nearest-segment (:nodes s) p2))]
      (cond
        ;; Right on a node → grab it (wins over a nearby handle/segment, so moving a
        ;; node doesn't accidentally catch a control point).
        (and n-idx (<= n-d2 node-snap2))
        (grab-node! n-idx)

        ;; Otherwise a bezier control handle (when the click isn't on a node).
        ;; Undo push deferred to the first drag move (a bare click doesn't change it).
        handle
        (do (swap! session assoc :dragging-handle handle)
            (viewport/set-controls-enabled! false))

        ;; On a segment → insert a node there (split), even between close nodes,
        ;; then drag it. Inserting IS a mutation, so push undo now and mark the
        ;; drag as already pushed.
        seg
        (do (push-undo!)
            (insert-node! (:index seg) (:point seg))
            (swap! session assoc :dragging (:index seg) :drag-pushed? true)
            (viewport/set-controls-enabled! false))

        ;; Otherwise remember the press: a still click appends, a drag orbits.
        :else
        (swap! session assoc :down {:client [(.-clientX e) (.-clientY e)] :pt w})))))

(defn- on-pointer-move [^js e]
  (let [s @session]
    (when (:entered? s)
      (cond
        (:dragging-handle s)
        (when-let [w (click-plane-point e s)]
          (when-not (:drag-pushed? s) (push-undo!) (swap! session assoc :drag-pushed? true))
          (let [{:keys [idx which]} (:dragging-handle s)
                p2 (world->plane (plane-basis (:pose s)) w)]
            (if (= which :belly)
              ;; arc belly: a free through-point; the 3-point arc reshapes to fit
              (swap! session assoc-in [:nodes idx :arc :belly] p2)
              (do
                (swap! session assoc-in [:nodes idx :bez which] p2)
                ;; c1 → snap to the start node's tangent (unless cusp); c2 free but
                ;; re-snaps the next segment's c1 (its incoming tangent changed)
                (swap! session update :nodes reconstrain-handles)))
            (refresh-preview!)))

        (:dragging s)
        (when-let [w (click-plane-point e s)]
          (when-not (:drag-pushed? s) (push-undo!) (swap! session assoc :drag-pushed? true))
          ;; 3D Shift+drag → axis-lock: keep only the dominant in-plane axis of the
          ;; move (relative to the drag anchor), so the node slides along one axis.
          (let [w (if (and (three-d? s) (.-shiftKey e) (:drag-anchor s))
                    (let [b (active-basis s)
                          d (m/v- w (:drag-anchor s))
                          da (m/dot d (:px b)) db (m/dot d (:py b))
                          along-x? (>= (js/Math.abs da) (js/Math.abs db))]
                      (m/v+ (:drag-anchor s)
                            (m/v* (if along-x? (:px b) (:py b)) (if along-x? da db))))
                    w)]
            (move-node! (:dragging s) (world->stored s w)))
          (refresh-preview!))))))

(defn- on-pointer-up [^js e]
  (let [s @session]
    (cond
      (:dragging-handle s)
      (do (swap! session dissoc :dragging-handle :drag-pushed?)
          (viewport/set-controls-enabled! true)
          (refresh-preview!) (update-panel!))

      (:dragging s)
      (do (swap! session dissoc :dragging :drag-pushed? :drag-anchor)
          (viewport/set-controls-enabled! true)
          (refresh-preview!) (update-panel!))

      (:down s)
      (let [[dx dy] (:client (:down s))
            moved (+ (js/Math.abs (- (.-clientX e) dx))
                     (js/Math.abs (- (.-clientY e) dy)))]
        ;; A click (barely moved) appends a node; a real drag was an orbit.
        (when (and (< moved 5) (:pt (:down s)))
          (append-node! (world->stored s (:pt (:down s)))))
        (swap! session dissoc :down)))))

;; ============================================================
;; Keyboard handler
;; ============================================================

(defn- digit-key [key]
  (when (and (= 1 (count key)) (re-matches #"[0-9]" key)) key))

(defn- flush-digit! []
  (let [buf (:digit-buffer @session)]
    (when (seq buf)
      (let [v (js/parseFloat buf)]
        (when (and (pos? v) (js/isFinite v))
          (swap! session assoc :step v)))
      (swap! session assoc :digit-buffer "")
      (update-panel!))))

(defn- arrow->axis [key]
  (case key
    "ArrowLeft"  [0 -1]
    "ArrowRight" [0 1]
    "ArrowUp"    [1 1]
    "ArrowDown"  [1 -1]
    nil))

(defn- set-plane!
  "Select the active 3D working plane (named by its normal axis): :f ⊥forward,
   :r ⊥right, :u ⊥up. Re-renders so the node rings reorient to the new plane."
  [plane]
  (swap! session assoc :plane plane)
  (render!) (update-panel!))

(defn- input-focused?
  "True when a panel <input> (the 3D precision fields) has focus — then editor
   keys must be left to the input (so digits/Enter edit the field, not the path)."
  []
  (when-let [^js a (.-activeElement js/document)]
    (= "INPUT" (.-tagName a))))

(defn- on-keydown [e]
  (when (and (:entered? @session) (not (input-focused?)))
    (let [key (.-key e)
          digit (digit-key key)]
      (cond
        ;; Cmd/Ctrl+Z → undo the last action.
        (and (or (.-metaKey e) (.-ctrlKey e)) (#{"z" "Z"} key))
        (do (.preventDefault e) (.stopPropagation e) (flush-digit!) (undo!))

        (= key "Tab")
        (do (.preventDefault e) (.stopPropagation e)
            (flush-digit!)
            (when (seq (:nodes @session))
              (swap! session update :selected #(mod (inc %) (count (:nodes @session)))))
            (render!) (update-panel!))

        digit
        (do (.preventDefault e) (.stopPropagation e)
            (swap! session update :digit-buffer str digit)
            (update-panel!))

        (and (= key ".") (seq (:digit-buffer @session)))
        (do (.preventDefault e) (.stopPropagation e)
            (when-not (str/includes? (:digit-buffer @session) ".")
              (swap! session update :digit-buffer str "."))
            (update-panel!))

        (#{"ArrowUp" "ArrowDown" "ArrowLeft" "ArrowRight"} key)
        (do (.preventDefault e) (.stopPropagation e) (flush-digit!)
            (when-let [[axis sign] (arrow->axis key)]
              ;; Shift+arrows nudge c1, Alt+arrows nudge c2 of the selected node's
              ;; bezier (Ctrl/Cmd are reserved by macOS for spaces); plain arrows
              ;; move the node.
              (cond
                (and (not (three-d? @session)) (.-shiftKey e)) (nudge-handle! :c1 axis sign)
                (and (not (three-d? @session)) (.-altKey e))   (nudge-handle! :c2 axis sign)
                :else                                          (nudge! axis sign))))

        (#{"Delete"} key)
        (do (.preventDefault e) (.stopPropagation e) (delete-node!))

        ;; Insert a node at the midpoint of the segment entering the selected node.
        ;; Match by .-key, by .-code (PC keyboards send code "Insert" regardless of
        ;; the produced key), and the Mac "Help" key; "i" is the laptop alias.
        ;; (2D only for now — 3D split comes with the curve phase.)
        (and (not (three-d? @session))
             (or (#{"Insert" "Help" "i" "I"} key) (= (.-code e) "Insert")))
        (do (.preventDefault e) (.stopPropagation e) (split-segment!))

        ;; toggle the selected node's incoming segment straight ↔ bezier curve (2D)
        (and (not (three-d? @session)) (#{"c" "C"} key))
        (do (.preventDefault e) (.stopPropagation e) (toggle-bezier!))

        ;; toggle the selected node's incoming segment straight ↔ circular arc (2D)
        (and (not (three-d? @session)) (#{"a" "A"} key))
        (do (.preventDefault e) (.stopPropagation e) (toggle-arc!))

        ;; toggle the selected node smooth ↔ cusp (frees its outgoing handle) (2D)
        (and (not (three-d? @session)) (#{"x" "X"} key))
        (do (.preventDefault e) (.stopPropagation e) (toggle-cusp!))

        ;; 3D only: select the active working plane (named by its normal axis)
        ;; f ⊥forward (= the 2D plane), r ⊥right, u ⊥up.
        (and (three-d? @session) (#{"f" "F" "r" "R" "u" "U"} key))
        (do (.preventDefault e) (.stopPropagation e)
            (set-plane! (case key ("f" "F") :f ("r" "R") :r :u)))

        (= key "Backspace")
        (do (.preventDefault e) (.stopPropagation e)
            (swap! session update :digit-buffer
                   #(subs % 0 (max 0 (dec (count %)))))
            (update-panel!))

        (= key "Enter")
        (do (.preventDefault e) (.stopPropagation e) (flush-digit!) (confirm!))

        (= key "Escape")
        (do (.preventDefault e) (.stopPropagation e) (cancel!))

        :else nil))))

;; ============================================================
;; Confirm / Cancel / Cleanup
;; ============================================================

(defn- remove-pointer-handlers! []
  (when-let [{:keys [^js canvas down move up]} (:pointer @session)]
    (.removeEventListener canvas "pointerdown" down)
    (.removeEventListener canvas "pointermove" move)
    (.removeEventListener canvas "pointerup" up)))

(defn- cleanup! []
  (when-let [t (:eval-timeout @session)] (js/clearTimeout t))
  (modal/unmount-panel! (:panel-el @session))
  (modal/remove-keydown! (:key-handler @session))
  (remove-pointer-handlers!)
  (viewport/set-controls-enabled! true)        ; in case a drag was interrupted
  (viewport/lock-interaction! false)           ; restore measure/pick
  (viewport/set-image-stamp-opacity! 1.0)      ; restore the reference image
  (viewport/clear-preview!))

(defn confirm! []
  (when @session
    (let [[from to] (find-marker)
          code (current-code)]
      (when from
        (modal/replace-source! from to code)
        (state/capture-println (str "edit-path: " code)))
      (cleanup!)
      (modal/release!)
      (reset! session nil)
      (modal/run-definitions!))))

(defn cancel! []
  (cleanup!)
  (modal/release!)
  (reset! session nil)
  (modal/arm-skip!)
  (modal/run-definitions!))

;; ============================================================
;; Entry points (two-phase)
;; ============================================================

(defn ^:export active? [] (some? @session))

(defn- clear-orphan! []
  (let [s @session
        live? (and s (:entered? s) (some-> (:panel-el s) .-parentNode))]
    (when (and (= :edit-path @state/interactive-mode) (not live?))
      (cleanup!)
      (reset! session nil)
      (modal/release!))))

(defn ^:export request!
  "Called by the (edit-path …) macro with the seed path built from its body.
   Opens the session and ALWAYS returns a path value (from the current/seed nodes)
   so the surrounding (path-to-shape …) runs during the eval. Script-mode only —
   from the REPL it just returns the path with a hint, like edit-bezier."
  [seed-path]
  (let [mode (if (= :2d (:species seed-path)) :2d :3d)
        ;; seed->nodes (2D) returns {:nodes :dropped}; seed->nodes-3d returns a
        ;; plain node vector — wrap it so the destructuring works in both modes.
        {:keys [nodes dropped]} (if (= mode :2d)
                                  (seed->nodes (project-2d-to-xy seed-path))
                                  {:nodes (seed->nodes-3d seed-path) :dropped []})
        live (fn [] (if (= mode :3d)
                      {:type :path :commands (nodes->commands-3d nodes)}
                      (nodes->path nodes)))]
    (cond
      (modal/consume-skip!)
      (live)

      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-path: open it from the definitions panel (Cmd+Enter), not the REPL")
          (live))

      :else
      (do
        (clear-orphan!)
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-path: cannot find '" marker-prefix " …)' in editor"))))
        ;; 2D: f/th/set-heading/mark/side-trip handled; arcs/beziers recovered.
        ;; 3D MVP: straight segments only — curves/marks come in a later phase.
        (when (seq dropped)
          (state/capture-println
           (str "edit-path: WARNING — body contains " dropped
                " which this editor cannot edit yet; confirming will replace them "
                "with straight segments.")))
        (modal/claim! :edit-path)
        (reset! session {:nodes        nodes
                         :selected     (max 0 (dec (count nodes)))
                         :step         5
                         :digit-buffer ""
                         :undo         []
                         :mode         mode
                         :plane        :f
                         :pose         (state/get-turtle-pose)
                         :entered?     false})
        (live)))))

(defn requested? []
  (and (some? @session) (not (:entered? @session))))

(defn enter! []
  (when (requested?)
    (swap! session assoc :entered? true)
    (let [handler on-keydown]
      (swap! session assoc :key-handler handler)
      (modal/install-keydown! handler))
    ;; Mouse: own pointer listeners on the canvas (click add / drag move).
    (when-let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" on-pointer-down)
      (.addEventListener canvas "pointermove" on-pointer-move)
      (.addEventListener canvas "pointerup" on-pointer-up)
      (swap! session assoc :pointer {:canvas canvas
                                     :down on-pointer-down
                                     :move on-pointer-move
                                     :up on-pointer-up}))
    ;; Dim the reference image so the overlay reads on a light background.
    (viewport/set-image-stamp-opacity! edit-dim)
    ;; Suppress measure/pick clicks so a stray click can't open a ruler mid-trace.
    (viewport/lock-interaction! true)
    (let [panel (create-panel!)]
      (swap! session assoc :panel-el panel))
    (update-panel!)
    (live-reeval!)
    (state/capture-println
     (str "edit-path-2d: click to add nodes, drag a node to move it, Tab cycles, "
          "arrows nudge, Del deletes, Enter to confirm, Esc to cancel"))))

;; ============================================================
;; Modal-evaluator registration
;; ============================================================

(defn- force-close! []
  (when @session
    (cleanup!)
    (modal/release!)
    (reset! session nil)))

(modal/register-kind! :edit-path
                      {:requested? requested?
                       :enter!     enter!
                       :active?    active?
                       :cancel!    cancel!
                       :close!     force-close!})
