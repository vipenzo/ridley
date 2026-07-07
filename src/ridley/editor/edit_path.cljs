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
            [ridley.turtle.core :as turtle]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

(declare confirm! cancel! render! update-panel! refresh-preview!
         set-seg-len! set-seg-angle! seg-len seg-angle-deg set-plane!
         arc->bez-handles set-node-mark! node-mark-name
         toggle-closed! bezier-frame-3d)

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
(def ^:private handle-color 0x33ddff) ; FREE bezier handle (sets the tangent) — bright cyan
(def ^:private handle-len-color 0x2b8a99) ; length-only handle (direction locked by smoothness) — muted teal
(def ^:private cusp-color   0xff44cc) ; freed (cusp) outgoing handle — magenta
(def ^:private handle-radius   0.45)  ; bezier handle square half-size
(def ^:private start-arrow-len 6)     ; 3D rail: affordance arrow length at node 0 (+X, the fixed start heading)
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
;; Cosmetic polyline density for a 3D bezier node's rendered/baked-frame-walk
;; points (bezier-frame-3d) — the FRAME itself (bezier/canonical-bezier-frame)
;; no longer depends on this; it only controls how many points draw the curve.
(def ^:private bezier-render-steps 24)

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

;; -- planar 3D frame -----------------------------------------------------------
;; A path-2d trace lives in the (right, up) plane (a = -y, b = z), seeded by the
;; macro's (th -90): heading [0 -1 0], up [0 0 1], right (= heading × up) = [-1 0 0].
;; A bezier that turns the heading far enough makes the recorder's tv-tessellation
;; flip the in-plane normal `right` to [+1 0 0] (verified vs eval). After such a
;; flip, a tv/th/arc-h rotates the OPPOSITE way and a bezier-to :local's
;; right-component is mirrored — which the flat 2D bake/recovery don't see, so the
;; node positions drift. We track the true 3D frame (bezier-frame-3d matches eval)
;; and sign-correct the affected commands. Unflipped paths are byte-identical.

(defn- e2->3
  "Embed a 2D shape point/dir (a, b) into the path-2d plane as 3D (x = 0): [0 -a b]."
  [[a b]] [0 (- a) b])

(defn- frame-flipped?
  "True when the 3D frame's right (heading × up) points +x — opposite the canonical
   plane normal [-1 0 0]. In this state planar turns/curves must be sign-corrected."
  [h3 u3]
  (pos? (first (m/cross h3 u3))))

(defn- straight-frame
  "Advance the planar 3D frame across a straight/arc whose new 2D heading is `dir2`:
   heading follows the chord, up stays the in-plane perpendicular (right preserved,
   so a rotation never flips it). Returns [h3 u3]."
  [h3 u3 dir2]
  (let [right (m/cross h3 u3)
        h3' (e2->3 dir2)]
    [h3' (m/cross right h3')]))

(defn- flip-planar-cmds
  "Sign-correct a segment's baked commands for a flipped in-plane frame: th/arc-h
   reverse direction, and a bezier-to's right-component (the editor's `a`, the
   first slot of each [a 0 c]) negates."
  [cmds]
  (let [flip-pt (fn [[a b cc]] [(- a) b cc])]
    (mapv (fn [{:keys [cmd args] :as c}]
            (case cmd
              :th    (assoc c :args [(- (first args))])
              :arc-h (assoc c :args [(first args) (- (second args))])
              :bezier-to (-> c (update :c1 flip-pt) (update :c2 flip-pt) (update :end flip-pt))
              c))
          cmds)))
(defn- v2-dot [[ax ay] [bx by]] (+ (* ax bx) (* ay by)))
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

;; -- 2D tangent "raccordo" arc -------------------------------------------------
;; Like the 3D arc: leaves the start node TANGENT to the incoming heading and ends
;; at the next node — a rounded corner, no cusp. Determined by A + incoming dir + B,
;; so the node carries just `:arc {}` (no belly). Bakes to a bare (arc-h r sweep)
;; (no leading th, since it's already tangent).

(defn- tangent-arc-2d
  "2D circular arc leaving A tangent to unit dir t, ending at B. Returns
   {:r :sweep-deg :exit-dir :center}, sweep in arc-h convention (+ = left / CCW), or
   nil if t ∥ (B−A) (degenerate → straight). The arc leaves A along +t (smooth)."
  [[ax ay] [tx ty] [bx by]]
  (let [cx (- bx ax) cy (- by ay)
        clen (js/Math.sqrt (+ (* cx cx) (* cy cy)))]
    (when (> clen 1e-9)
      (let [lx (- ty) ly tx                       ; left-normal of t (+90°)
            side (+ (* lx cx) (* ly cy))]         ; >0 B to the left, <0 to the right
        (when (> (js/Math.abs side) 1e-9)         ; t ∦ chord
          (let [left? (>= side 0)
                px (if left? lx (- lx)) py (if left? ly (- ly))   ; perp toward B
                r (/ (* clen clen) (* 2 (js/Math.abs side)))
                ccx (+ ax (* px r)) ccy (+ ay (* py r))           ; center
                vax (- ax ccx) vay (- ay ccy)
                vbx (- bx ccx) vby (- by ccy)
                raw (js/Math.atan2 (- (* vax vby) (* vay vbx))    ; signed (−π, π]
                                   (+ (* vax vbx) (* vay vby)))
                sweep (if left?
                        (if (< raw 0) (+ raw (* 2 js/Math.PI)) raw)    ; CCW  [0, 2π)
                        (if (> raw 0) (- raw (* 2 js/Math.PI)) raw))   ; CW  (−2π, 0]
                sweep-deg (* sweep (/ 180 js/Math.PI))]
            {:r r :sweep-deg sweep-deg :center [ccx ccy]
             :exit-dir (rotate-dir [tx ty] sweep-deg)}))))))

(defn- tangent-arc-tess-2d
  "Tessellated 2D points along the 2D tangent arc (A tangent t → B), inclusive of
   both ends, or nil if degenerate."
  [A t B steps]
  (when-let [{:keys [center sweep-deg]} (tangent-arc-2d A t B)]
    (let [[cx cy] center
          va [(- (first A) cx) (- (second A) cy)]]
      (mapv (fn [k] (let [[rx ry] (rotate-dir va (* (/ k steps) sweep-deg))]
                      [(+ cx rx) (+ cy ry)]))
            (range (inc steps))))))

(defn- walk-2d-segments
  "Per-segment info for the 2D nodes, tracking the heading (straights face the chord,
   tangent arcs leave along their exit tangent, beziers along the end tangent), from
   the start heading [1 0]. Returns one map per segment: {:kind :a :b :ch} and, for an
   arc, {:geom … :pts (2d tessellation)}. Plane coords. Used by render! + arc split;
   the bake (nodes->commands) tracks the same heading via its own (richer) walk."
  [nodes]
  (loop [i 1 ch [1 0] segs []]
    (if (>= i (count nodes))
      segs
      (let [from (node-pos (nth nodes (dec i))) to (node-pos (nth nodes i))
            node (nth nodes i)]
        (cond
          (:arc node)
          (if-let [g (tangent-arc-2d from ch to)]
            (recur (inc i) (:exit-dir g)
                   (conj segs {:kind :arc :a from :b to :ch ch :geom g
                               :pts (tangent-arc-tess-2d from ch to 24)}))
            (recur (inc i) (v2-norm [(- (first to) (first from)) (- (second to) (second from))])
                   (conj segs {:kind :straight :a from :b to :ch ch})))

          (:bez node)
          (let [{:keys [c2]} (:bez node)
                eh (v2-norm [(- (first to) (first c2)) (- (second to) (second c2))])]
            (recur (inc i) eh (conj segs {:kind :bez :a from :b to :ch ch})))

          :else
          (let [dx (- (first to) (first from)) dy (- (second to) (second from))]
            (recur (inc i) (if (> (+ (* dx dx) (* dy dy)) 1e-18) (v2-norm [dx dy]) ch)
                   (conj segs {:kind :straight :a from :b to :ch ch}))))))))

;; -- bezier tangent constraint (directional handles) ----------------------
;; c1 (start handle) stays tangent to how the path ARRIVES at the start node, so
;; a smooth node leaves tangent to what precedes; c2 (end handle) is free and sets
;; the arrival direction. A cusp node (:smooth? false) frees its outgoing c1.

(defn- incoming-tangent
  "Unit direction the path arrives at node i with (= the tangent that a smooth c1
   leaving i must lie along). When `closed?`, node 0's incoming is the closing
   segment (last node → node 0): a curved seam arrives along its c2, a straight one
   along the last-node→node-0 chord — so the seam stays tangent-continuous at node 0."
  ([nodes i] (incoming-tangent nodes i false))
  ([nodes i closed?]
   (cond
     (and (zero? i) closed? (>= (count nodes) 3))
     (if-let [{:keys [c2]} (:bez (first nodes))]
       (v2-norm (let [[bx by] (node-pos (first nodes))] [(- bx (first c2)) (- by (second c2))]))
       (v2-norm (let [[bx by] (node-pos (first nodes))
                      [ax ay] (node-pos (peek nodes))]
                  [(- bx ax) (- by ay)])))
     (zero? i) [1 0]
     (:bez (nth nodes i)) (v2-norm (let [[bx by] (node-pos (nth nodes i))
                                         [cx cy] (:c2 (:bez (nth nodes i)))]
                                     [(- bx cx) (- by cy)]))
     :else (v2-norm (let [[bx by] (node-pos (nth nodes i))
                          [ax ay] (node-pos (nth nodes (dec i)))]
                      [(- bx ax) (- by ay)])))))

;; Smallest c1 handle length, as a fraction of the segment chord. A handle can't
;; collapse onto the start node: a zero-length handle gives the cubic an undefined
;; start tangent and clusters the tessellation points, which makes stroke-shape's
;; offset self-overlap pathologically (pinches / stray faces on extrude).
(def ^:private min-handle-frac 0.1)

(defn- lerp
  "Point at fraction t along A→B. Dimension-generic (2D plane / 3D rail points)."
  [A B t]
  (mapv (fn [a b] (+ a (* (- b a) t))) A B))

(defn- straight-bez?
  "True when the cubic A→c1→c2→B is geometrically the straight segment A→B: both
   control points sit on the chord (tight tolerance) and between the ends, in order.
   Lets a 'straight' default/edited bezier bake as a clean line (f/th, set-heading+f)
   instead of a bezier-to. Dimension-generic."
  [A c1 c2 B]
  (let [vsub  (fn [p q] (mapv - p q))
        vdot  (fn [p q] (reduce + (map * p q)))
        d     (vsub B A)
        chord (js/Math.sqrt (vdot d d))]
    (if (< chord 1e-9)
      false
      (let [u   (mapv #(/ % chord) d)
            eps (* 1e-6 chord)
            ;; projection length of P onto the chord (nil if off-line / past an end)
            on  (fn [P]
                  (let [w    (vsub P A)
                        t    (vdot w u)
                        proj (lerp A B (/ t chord))
                        pd   (let [e (vsub P proj)] (js/Math.sqrt (vdot e e)))]
                    (when (and (< pd eps) (>= t (- eps)) (<= t (+ chord eps))) t)))
            t1 (on c1) t2 (on c2)]
        (boolean (and t1 t2 (<= t1 (+ t2 eps))))))))

(defn- reconstrain-handles
  "Project each bezier segment's c1 onto its start node's incoming tangent (keeping
   its length, with a small minimum so it never collapses onto the start node), so
   smooth nodes stay tangent-continuous. Cusp start nodes are left free, as is the
   first node of an OPEN path (no incoming segment → no tangent to continue, so the
   first segment stays as drawn). Single pass — a node's incoming tangent depends on
   its c2/pos, not on the c1's being constrained. When `closed?`, node 0's :bez is the
   closing segment (start node = the last node), so its c1 is reconstrained too."
  ([nodes] (reconstrain-handles nodes false))
  ([nodes closed?]
   (let [n (count nodes)
         ;; project node `i`'s :bez :c1 onto the incoming tangent at its start node
         ;; `a` (so a smooth seam stays tangent-continuous; a cusp start is left free)
         project (fn [ns i a]
                   (if (or (false? (:smooth? (nth ns a)))
                           (and (not closed?) (zero? a)))   ; open start: free
                     ns
                     (let [[ax ay] (node-pos (nth ns a))
                           [bx by] (node-pos (nth ns i))
                           [c1x c1y] (:c1 (:bez (nth ns i)))
                           [tx ty] (incoming-tangent ns a closed?)
                           chord (js/Math.sqrt (+ (* (- bx ax) (- bx ax)) (* (- by ay) (- by ay))))
                           len (max (* min-handle-frac chord)
                                    (+ (* (- c1x ax) tx) (* (- c1y ay) ty)))]
                       (assoc-in ns [i :bez :c1] [(+ ax (* len tx)) (+ ay (* len ty))]))))
         ns (reduce (fn [ns i] (if (:bez (nth ns i)) (project ns i (dec i)) ns))
                    nodes (range 1 n))]
     (if (and closed? (>= n 3) (:bez (first ns)))
       (project ns 0 (dec n))                   ; closing segment: start node = the last node
       ns))))

;; -- generic command serializer (for node tails and side-trip bodies) -----

(declare cmd->code)

(defn- arg->code [a]
  (cond
    (number? a) (fmt-num a)
    (vector? a) (str "[" (str/join " " (map arg->code a)) "]")
    :else       (pr-str a)))

(defn- cmd->code [{:keys [cmd args] :as c}]
  (cond
    (= cmd :side-trip)
    ;; side-trip's body is a sub-path; re-emit its HIGH-LEVEL commands inline
    ;; (Fase 2a, punto 6 — not path-micro-commands' tessellation, which would
    ;; wall a curve into micro th/tv/f and lose its :smooth continuity on
    ;; re-eval; (:commands sub) is already the recorder's own schema).
    (str "(side-trip " (str/join " " (map cmd->code (:commands (first args)))) ")")

    ;; map-shaped bezier-to (Fase 2a — the recorder's own schema, dev-docs/
    ;; brief-recording-highlevel-fase2a.md): :steps is a bake-time-only hint
    ;; consumed by lowering, not part of the re-emitted source.
    (= cmd :bezier-to)
    (str "(bezier-to " (arg->code (:end c)) " " (arg->code (:c1 c)) " " (arg->code (:c2 c)) " :local)")

    :else
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

(defn- closing-cmds
  "Commands for a *closed* path's closing segment (last node → node 0), using node 0's
   own curve type (its :bez / :arc / straight, reusing the slot that is unused on an
   open path's start node). `ch`/`from` are the heading and position arriving at the
   last node. The trace returns exactly to node 0, so on re-open the closure is
   recoverable structurally and path-to-shape's drop-closing-dup removes the doubled
   seam vertex."
  [ch from n0]
  (let [to (node-pos n0)]
    (cond
      (and (:bez n0) (straight-bez? from (:c1 (:bez n0)) (:c2 (:bez n0)) to))
      (first (segment-cmds ch from to nil))
      (:bez n0)
      (let [{:keys [c1 c2]} (:bez n0)]
        [{:cmd :bezier-to :c1 (pt->local from ch c1) :c2 (pt->local from ch c2)
          :end (pt->local from ch to)}])
      (:arc n0)
      (if-let [{:keys [r sweep-deg]} (tangent-arc-2d from ch to)]
        [{:cmd :arc-h :args [r sweep-deg]}]
        (first (segment-cmds ch from to nil)))
      :else
      (first (segment-cmds ch from to nil)))))

(defn- nodes->commands
  "Path commands for the nodes: a leading move-to anchors the absolute start, then
   per segment the minimal move (f / rt / lt / th+f) that reaches the next node and
   leaves it with the node's heading, with each node's :tail emitted after it. When
   `closed?` (≥3 nodes), a final closing segment back to node 0 is appended (see
   closing-cmds)."
  ([nodes] (nodes->commands nodes false))
  ([nodes closed?]
   (if (empty? nodes)
     []
     (let [n0 (first nodes)
           [sx sy] (node-pos n0)
           ;; A leading move-to is only needed to anchor a start that isn't the
           ;; origin; at the origin it's redundant (path-to-shape / run-path seed
           ;; [0 0] there) so the bake stays clean.
           at-origin? (and (< (js/Math.abs sx) 1e-6) (< (js/Math.abs sy) 1e-6))
           [corr0 ch0] (segment-cmds [1 0] (node-pos n0) (node-pos n0) (:heading n0))
           ;; 3D frame tracked alongside the flat 2D heading, to detect (and correct)
           ;; the in-plane flip a bezier can introduce. Seed = the path-2d frame.
           {:keys [out ch from h3 u3]}
           (loop [ch ch0
                  from (node-pos n0)
                  h3 [0 -1 0]
                  u3 [0 0 1]
                  remaining (rest nodes)
                  out (-> (if at-origin? [] [{:cmd :move-to :args [[sx sy]]}])
                          (into corr0)
                          (into (:tail n0)))]
             (if (empty? remaining)
               {:out out :ch ch :from from :h3 h3 :u3 u3}
               (let [node (first remaining)
                     to (node-pos node)
                     flip? (frame-flipped? h3 u3)
                     emit (fn [cmds] (if flip? (flip-planar-cmds cmds) cmds))]
                 (cond
                   ;; Cubic bezier segment: control points emitted in the start node's
                   ;; local frame (:local) so the curve stays attached when the path
                   ;; before it is edited; the standard macro re-tessellates it. The
                   ;; heading after is the end tangent (c2 → end); the 3D frame advances
                   ;; via bezier-frame-3d (matches eval, may flip the in-plane normal).
                   ;; A bezier whose handles are collinear with the chord is straight —
                   ;; bake it as a clean f/th line (keeps polygons/faceted profiles tidy).
                   (and (:bez node) (straight-bez? from (:c1 (:bez node)) (:c2 (:bez node)) to))
                   (let [[cmds ch1] (segment-cmds ch from to (:heading node))
                         [h3' u3'] (straight-frame h3 u3 ch1)]
                     (recur ch1 to h3' u3' (rest remaining)
                            (-> out (into (emit cmds)) (into (:tail node)))))

                   (:bez node)
                   (let [{:keys [c1 c2]} (:bez node)
                         end-tan (v2-norm [(- (first to) (first c2)) (- (second to) (second c2))])
                         {:keys [exit-h exit-u]} (bezier-frame-3d (e2->3 from) (e2->3 c1)
                                                                  (e2->3 c2) (e2->3 to) u3 bezier-render-steps)]
                     (recur end-tan to exit-h exit-u (rest remaining)
                            (-> out
                                (into (emit [{:cmd :bezier-to
                                              :c1 (pt->local from ch c1)
                                              :c2 (pt->local from ch c2)
                                              :end (pt->local from ch to)}]))
                                (into (:tail node)))))

                   ;; Tangent "raccordo" arc: leaves the start node along the current
                   ;; heading (so no leading th → smooth, no cusp) and ends at the next
                   ;; node, baking to a bare (arc-h r sweep) — heading-relative, attached.
                   ;; A degenerate arc (heading ∥ chord) falls back to a straight segment.
                   (:arc node)
                   (if-let [{:keys [r sweep-deg exit-dir]} (tangent-arc-2d from ch to)]
                     (let [[h3' u3'] (straight-frame h3 u3 exit-dir)]
                       (recur exit-dir to h3' u3' (rest remaining)
                              (-> out
                                  (into (emit [{:cmd :arc-h :args [r sweep-deg]}]))
                                  (into (:tail node)))))
                     (let [[cmds ch1] (segment-cmds ch from to (:heading node))
                           [h3' u3'] (straight-frame h3 u3 ch1)]
                       (recur ch1 to h3' u3' (rest remaining)
                              (-> out (into (emit cmds)) (into (:tail node))))))

                   :else
                   (let [[cmds ch1] (segment-cmds ch from to (:heading node))
                         [h3' u3'] (straight-frame h3 u3 ch1)]
                     (recur ch1 to h3' u3' (rest remaining)
                            (-> out (into (emit cmds)) (into (:tail node)))))))))]
       (if (and closed? (>= (count nodes) 3))
         (into out (cond-> (closing-cmds ch from n0)
                     (frame-flipped? h3 u3) flip-planar-cmds))
         out)))))

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
    :bezier-to (cmd->code (-> c (update :c1 bez-local->2d)
                              (update :c2 bez-local->2d)
                              (update :end bez-local->2d)))
    :side-trip (str "(side-trip "
                    (str/join " " (map cmd->code-2d (:commands (first args)))) ")")
    (cmd->code c)))

(defn- nodes->code
  "The replacement source: a complete (path-2d (move-to …) (tv …)(f …) …) form,
   carrying preserved marks / side-trips and orientation. The (edit-path-2d …)
   marker is a stand-in for this, so confirming swaps it in."
  ([nodes] (nodes->code nodes false))
  ([nodes closed?]
   (str "(path-2d"
        (when closed? " :closed")              ; persists the closed flag (path-2d reads it)
        (apply str (map #(str " " (cmd->code-2d %)) (nodes->commands nodes closed?)))
        ")")))

;; ============================================================
;; 3D nodes ↔ path (edit-path — straight-segment MVP, phase 1)
;; ============================================================
;; A 3D path is a rail (consumed by extrude-along-path / loft in its own frame),
;; so nodes carry full 3D positions and the turtle frame is DERIVED from the
;; geometry (tangent + parallel-transported up), exactly like the 2D editor
;; derives heading from the segment. Curves/marks and the working-plane UI come in
;; later phases.

(def ^:private move-cmd? #{:f :b :u :rt :lt})

(defn- arc-h-endpoint
  "Closed-form endpoint of a hand-written 3D arc-h (rotation axis = up), given
   entry position/heading/up, radius r, and signed sweep angle θ (radians).
   Empirically verified against a 100000-step tessellation (REPL, Fase 2a) —
   the runtime arc-h/arc-v themselves tessellate via chords (turtle/core.cljs),
   so there's no existing closed form to call into; this one is exact."
  [pos heading up r theta]
  (let [right (m/normalize (m/cross heading up))]
    {:end (m/v+ pos (m/v- (m/v* heading (* r (js/Math.sin theta)))
                          (m/v* right (* r (- 1 (js/Math.cos theta))))))
     :exit-h (m/v- (m/v* heading (js/Math.cos theta)) (m/v* right (js/Math.sin theta)))
     :exit-u up}))

(defn- arc-v-endpoint
  "Closed-form endpoint of a hand-written 3D arc-v (rotation axis = right).
   See arc-h-endpoint — verified the same way; the sign pattern genuinely
   differs from arc-h's (rotation axis right vs up), not a copy-paste slip."
  [pos heading up r theta]
  {:end (m/v- pos (m/v+ (m/v* heading (* r (js/Math.sin theta)))
                        (m/v* up (* r (- 1 (js/Math.cos theta))))))
   :exit-h (m/v+ (m/v* heading (js/Math.cos theta)) (m/v* up (js/Math.sin theta)))
   :exit-u (m/v- (m/v* up (js/Math.cos theta)) (m/v* heading (js/Math.sin theta)))})

(defn- seed->nodes-3d
  "Recover 3D editor nodes straight from the recorder's own high-level commands
   (dev-docs/brief-recording-highlevel-fase2a.md, Parte 2) — no more path-
   micro-commands / :pure / :span round-trip: a :bezier-to command IS one
   bezier node (c1/c2/end decoded from its local frame via the entry pose we
   track ourselves), a move command is one straight node, a hand-written
   arc-h/arc-v (the bake never emits these in 3D) becomes an equivalent
   bezier node via the closed-form endpoint + arc->bez-handles. Rotations /
   set-heading add no node. Node 0 is the pinned anchor at the origin."
  [seed-path]
  (let [cmds (when (and (map? seed-path) (= :path (:type seed-path))) (:commands seed-path))]
    (if (empty? cmds)
      ;; empty → just the anchor node at the origin (already present, not inserted
      ;; by the user and not movable); the user clicks to add the rail from there.
      ;; A 0-segment path extrudes to an empty mesh (no error) until the 2nd node.
      [{:pos [0 0 0] :heading nil :up nil :tail []}]
      (:nodes
       (reduce
        (fn [{:keys [pos heading up nodes] :as st} {:keys [cmd args] :as c}]
          (cond
            ;; hard error: a residual tessellated curve fragment must never
            ;; reach a seed after Parte 1 (follow) + Parte 2 (bake) — same
            ;; philosophy as Fase 1's run-path/replay-path-to-recording.
            (or (:smooth c) (:bez-cap c) (:arc-cap c) (:veer-deg c))
            (throw (js/Error. (str "seed->nodes-3d: unexpected tessellated curve fragment "
                                   cmd " in seed — missing a Fase 2a migration?")))

            (= :move-to cmd) st        ; leading anchor only; already at the origin

            (= :bezier-to cmd)
            (let [entry {:position pos :heading heading :up up}
                  c1-w (turtle/local->world entry (:c1 c))
                  c2-w (turtle/local->world entry (:c2 c))
                  end-w (turtle/local->world entry (:end c))
                  [{exit-h :heading exit-u :up}] (bezier/canonical-bezier-frame pos c1-w c2-w end-w up [1.0])]
              (-> st
                  (assoc :pos end-w :heading exit-h :up exit-u)
                  (update :nodes conj {:pos end-w :heading nil :up nil :tail []
                                       :bez {:c1 c1-w :c2 c2-w}})))

            (#{:arc-h :arc-v} cmd)
            (let [[r angle-deg] args
                  theta (* angle-deg (/ js/Math.PI 180))
                  {:keys [end exit-h exit-u]} ((if (= :arc-h cmd) arc-h-endpoint arc-v-endpoint)
                                               pos heading up r theta)
                  {:keys [c1 c2]} (arc->bez-handles pos end heading exit-h angle-deg r)]
              (-> st
                  (assoc :pos end :heading exit-h :up exit-u)
                  (update :nodes conj {:pos end :heading nil :up nil :tail []
                                       :bez {:c1 c1 :c2 c2}})))

            (= :set-heading cmd)
            (let [new-frame (turtle/apply-set-heading {:heading heading :up up} args)]
              (assoc st :heading (:heading new-frame) :up (:up new-frame)))

            (#{:th :tv :tr} cmd)
            ;; bare rotation (a hand-written seed, not the bake — which always
            ;; emits set-heading) — apply via the real turtle primitives so
            ;; this stays byte-identical to how the rest of the app rotates.
            (let [rotate (case cmd :th turtle/th :tv turtle/tv :tr turtle/tr)
                  new-frame (rotate {:heading heading :up up} (first args))]
              (assoc st :heading (:heading new-frame) :up (:up new-frame)))

            (move-cmd? cmd)
            (let [d (first args)
                  right (m/normalize (m/cross heading up))
                  np (case cmd
                       :f (m/v+ pos (m/v* heading d))
                       :b (m/v+ pos (m/v* heading (- d)))
                       :u (m/v+ pos (m/v* up d))
                       :rt (m/v+ pos (m/v* right d))
                       :lt (m/v- pos (m/v* right d)))]
              (-> st (assoc :pos np)
                  (update :nodes conj {:pos np :heading nil :up nil :tail []})))

            ;; a mark / side-trip attaches to the current (last) node's :tail —
            ;; record-only, it rides through edits and re-emits in the bake
            (#{:mark :side-trip} cmd)
            (update-in st [:nodes (dec (count nodes)) :tail] (fnil conj []) c)

            ;; other rotations (already handled above) or unknown record-only
            ;; commands: no new node
            :else st))
        {:pos [0 0 0] :heading [1 0 0] :up [0 0 1]
         :nodes [{:pos [0 0 0] :heading nil :up nil :tail []}]}
        cmds)))))

(defn- rot-axis
  "Rotate vector v around unit axis by ang radians (Rodrigues)."
  [axis v ang]
  (let [ca (js/Math.cos ang) sa (js/Math.sin ang)]
    (m/v+ (m/v* v ca)
          (m/v+ (m/v* (m/cross axis v) sa)
                (m/v* axis (* (m/dot axis v) (- 1 ca)))))))

(defn- rmf-transport-up
  "Parallel-transport up `u` from heading `h` to heading `d` (minimal rotation), so
   the swept section stays twist-free across a straight 3D segment. Mirrors
   shape/rmf-transport (the bake's RMF builder) for the editor's per-segment walk."
  [u h d]
  (let [dt (max -1.0 (min 1.0 (m/dot h d)))]
    (if (> dt 0.999999)
      u
      (let [axis (m/cross h d) am (m/magnitude axis)]
        (if (< am 1e-9)
          u
          (rot-axis (m/v* axis (/ 1.0 am)) u (js/Math.acos dt)))))))

;; -- 3D arc segments (tangent "raccordo" arc) ----------------------------------
;; An arc leaves its start node A TANGENT to the incoming heading and ends at the
;; next node B — a fillet-like rounded corner, so there's no cusp at the start. Given
;; A, the unit incoming tangent t, and B, the circular arc is UNIQUE (its plane and
;; radius are determined), so the node carries just `:arc {}` — no free belly handle.
;; It bakes to (set-heading [t][normal] :local)(arc-h r sweep): the arc-plane normal
;; as up makes arc-h trace the arc; entry = t so the heading is continuous (smooth).
;; Recoverable via the :arc-cap tag machinery, like a hand-written arc-h.

(defn- tangent-arc-geom-3d
  "Circular arc leaving world point A tangent to unit dir t, ending at B. Returns
   {:r :sweep-deg :entry-tan :exit-tan :normal :center}, or nil if t is parallel to
   (B−A) (the arc degenerates to a straight line). entry-tan = t (smooth start);
   normal is the arc-plane normal (the `up` for arc-h); sweep-deg follows arc-h's
   convention (+ = left / CCW around normal); the arc travels CCW around normal,
   which leaves A along +t."
  [A t B]
  (let [c (m/v- B A) clen (m/magnitude c)]
    (when (> clen 1e-9)
      (let [tc (m/cross t c)]
        (when (> (m/magnitude tc) 1e-6)            ; t ∦ chord
          (let [n (m/normalize tc)                 ; plane normal
                perp (m/cross n t)                 ; ⊥ t, toward B (perp·c > 0)
                pc (m/dot perp c)
                r (/ (* clen clen) (* 2 pc))
                center (m/v+ A (m/v* perp r))
                va (m/v- A center) vb (m/v- B center) r2 (* r r)
                cosw (max -1.0 (min 1.0 (/ (m/dot va vb) r2)))
                sinw (/ (m/dot (m/cross va vb) n) r2)
                ang (js/Math.atan2 sinw cosw)      ; (−π, π]
                sweep-rad (if (< ang 0) (+ ang (* 2 js/Math.PI)) ang)]  ; CCW [0, 2π)
            {:r r :sweep-deg (* sweep-rad (/ 180 js/Math.PI))
             :entry-tan t :exit-tan (rot-axis n t sweep-rad)
             :normal n :center center}))))))

(defn- arc->bez-handles
  "Cubic-bezier control points (world) approximating a circular arc from A to B,
   leaving tangent to entry-tan, arriving tangent to exit-tan, with the standard
   L = (4/3)·tan(θ/4)·r handle length. Turns a tangent 'raccordo' arc (and a
   hand-written arc-h opened in the 3D editor) into an editable, twist-free bezier."
  [A B entry-tan exit-tan sweep-deg r]
  (let [theta (* (js/Math.abs sweep-deg) (/ js/Math.PI 180))
        L (* (/ 4.0 3.0) (js/Math.tan (/ theta 4)) r)]
    {:c1 (m/v+ A (m/v* entry-tan L))
     :c2 (m/v- B (m/v* exit-tan L))}))

;; -- 3D bezier segments --------------------------------------------------------
;; The 3D editor's curve primitive is the cubic bezier (node carries :bez {:c1 :c2},
;; world handles). It bakes to ONE compact (bezier-to [end][c1][c2] :local) command —
;; bezier-to tessellates at eval-time with its OWN rotation-minimizing frame (the up
;; is parallel-transported, continuous across the seam → no pinch), and tags the run
;; with :pure so re-edit recovers it as a single node. Arcs are NOT baked in 3D: the
;; `a` key and a hand-written arc-h are converted to an equivalent bezier (see
;; arc->bez-handles), keeping the baked rail compact and twist-free.

(defn- bezier-frame-3d
  "Tessellate the cubic bezier A→c1→c2→B into `render-steps` points (cosmetic
   polyline density only) and read the exit frame off the canonical bezier
   frame (bezier/canonical-bezier-frame) — a property of the curve's control
   points and entry up alone, so it agrees with the recorder (rec-bezier-to*)
   regardless of tessellation resolution on either side. Returns
   {:pts :exit-h :exit-u}: exit-h = the analytic end tangent, exit-u = the
   canonical up at the end."
  [A c1 c2 B entry-u render-steps]
  (let [pts (mapv #(bezier/cubic-bezier-point A c1 c2 B (/ % render-steps)) (range (inc render-steps)))
        [{:keys [heading up]}] (bezier/canonical-bezier-frame A c1 c2 B entry-u [1.0])]
    {:pts pts :exit-h heading :exit-u up}))

(defn- walk-3d-segments
  "Walk the 3D nodes from the rail start (origin, heading [1 0 0], up [0 0 1]),
   tracking the turtle frame, and return one map per segment: a straight
   {:kind :straight :a :b :h :u :dir :dist :up*} or a bezier
   {:kind :bez :a :b :h :u :c1 :c2 :pts …}, where :h/:u is the frame BEFORE the segment
   (for the :local bake). Single source of truth shared by the bake
   (nodes->commands-3d) and render-3d!."
  [nodes]
  (loop [i 1 h [1 0 0] u [0 0 1] segs []]
    (if (>= i (count nodes))
      segs
      (let [a (node-pos (nth nodes (dec i)))
            b (node-pos (nth nodes i))
            node (nth nodes i)
            ;; a bezier with collinear handles is geometrically straight → walk (and
            ;; bake) it as a straight segment, so it emits set-heading+f, not bezier-to.
            bez (when-let [{:keys [c1 c2]} (:bez node)]
                  (when-not (straight-bez? a c1 c2 b) (:bez node)))]
        (if-let [{:keys [c1 c2]} bez]
          (let [{:keys [pts exit-h exit-u]} (bezier-frame-3d a c1 c2 b u bezier-render-steps)]
            (recur (inc i) exit-h exit-u
                   (conj segs {:kind :bez :i i :a a :b b :h h :u u :c1 c1 :c2 c2 :pts pts})))
          (let [d (m/v- b a) dist (m/magnitude d)]
            (if (< dist 1e-9)
              (recur (inc i) h u segs)
              (let [dir (m/normalize d) up* (rmf-transport-up u h dir)]
                (recur (inc i) dir up*
                       (conj segs {:kind :straight :i i :a a :b b :h h :u u
                                   :dir dir :dist dist :up* up*}))))))))))

(defn- reconstrain-handles-3d
  "3D analogue of reconstrain-handles: project each bezier segment's c1 onto the
   tangent the rail arrives at its start node with (from the shared frame walk), so
   smooth nodes stay tangent-continuous; cusp start nodes (`:smooth? false`) are left
   free. Node 0 is the rail's anchor and is ALWAYS smooth (no cusp option — see
   toggle-cusp!): its fixed heading is [1 0 0] (validate-rail-start! in
   extrusion.cljs enforces this on the baked rail), so segment 1's c1 is
   constrained just like every other segment's, not left free. Single forward
   pass — the arrival heading at a node depends on the previous segment's
   c2/positions, not on the c1's being constrained."
  [nodes]
  (let [n (count nodes)]
    (reduce
     (fn [ns i]
       (if (and (:bez (nth ns i))
                (not (false? (:smooth? (nth ns (dec i))))))
         (let [A     (node-pos (nth ns (dec i)))
               B     (node-pos (nth ns i))
               c1    (:c1 (:bez (nth ns i)))
               h     (or (:h (nth (walk-3d-segments ns) (dec i) nil)) [1 0 0])
               chord (m/magnitude (m/v- B A))
               len   (max (* min-handle-frac chord) (m/dot (m/v- c1 A) h))]
           (assoc-in ns [i :bez :c1] (m/v+ A (m/v* h len))))
         ns))
     nodes (range 1 n))))

;; Mirrors extrusion.cljs's rail-start-frame-tol-deg: how far node 1's chord may
;; sit off the anchor's fixed heading [1 0 0] before ensure-node1-tangent seeds a
;; default tangent bezier for it.
(def ^:private rail-axis-tol-deg 1.0)

(defn- angle-deg-3d [u v]
  (* (/ 180 js/Math.PI) (js/Math.acos (max -1.0 (min 1.0 (m/dot u v))))))

(defn- ensure-node1-tangent
  "Node 1 (the rail's first real node) must leave along the anchor's fixed
   heading [1 0 0] (see reconstrain-handles-3d above / validate-rail-start!
   in extrusion.cljs). A node 1 placed or dragged off that ray with NO `:bez` yet
   — from `insert-node!`, the non-bezier branch of `split-segment!`'s 3D split, or
   a recovered seed whose source folded a manual th/tv into its waypoints — has
   nothing for reconstrain-handles-3d to snap, since it only touches nodes that
   already carry `:bez`. Seed a default tangent bezier here: c1 along +X at ⅓ the
   chord (the same default `append-node!` gives any other node's incoming
   handle), c2 at the usual ⅔-chord belly. A node already on-axis, or one that
   already has a `:bez` (reconstrain-handles-3d will snap it), is left untouched
   — idempotent, safe to call unconditionally."
  [nodes]
  (if (or (< (count nodes) 2) (:bez (nth nodes 1)))
    nodes
    (let [A (node-pos (first nodes)) B (node-pos (nth nodes 1))
          chord (m/v- B A) clen (m/magnitude chord)]
      (if (or (< clen 1e-9)
              (< (angle-deg-3d [1 0 0] (m/v* chord (/ 1.0 clen))) rail-axis-tol-deg))
        nodes
        (assoc-in nodes [1 :bez] {:c1 (m/v+ A (m/v* [1 0 0] (/ clen 3)))
                                  :c2 (lerp A B (/ 2.0 3))})))))

(defn- conform-rail-start-3d
  "Ensure the 3D nodes' first segment satisfies the rail-start invariant: seed a
   default tangent bezier on node 1 when it needs one (`ensure-node1-tangent`),
   then re-snap every bezier handle (`reconstrain-handles-3d`, which now locks
   node 1's c1 too). Pure — safe to call on freshly-recovered seed nodes before
   any session exists (`request!`), and from `reconstrain!` after every edit."
  [nodes]
  (reconstrain-handles-3d (ensure-node1-tangent nodes)))

(defn- bezier-steps-at-bake
  "Resolve :steps for a baked bezier segment A→B exactly like rec-bezier-to*
   (macros.cljs, Fase 1) does at record time — same {:mode :value} read, same
   :s-mode formula off the chord length — so the pre-confirm `live` value's
   tessellation matches what re-evaluating the confirmed source would
   produce (dev-docs/brief-recording-highlevel-fase2a.md, punto 4)."
  [a b]
  (let [{:keys [mode value]} (or (state/get-turtle-resolution) {:mode :n :value 16})
        approx-length (m/magnitude (m/v- b a))]
    (case mode
      :n value
      :a value
      :s (max 1 (js/Math.ceil (/ approx-length value))))))

(defn- nodes->commands-3d
  "Commands tracing the 3D nodes as a TWIST-FREE rail. Per straight segment:
   (set-heading [dir][up] :local)(f dist) with the rotation-minimizing up. Per bezier
   segment: ONE high-level {:cmd :bezier-to :c1 :c2 :end :steps} (Fase 2a — the
   recorder's own schema, dev-docs/brief-recording-highlevel-fase2a.md) — compact
   in the source (cmd->code re-renders it as (bezier-to [end][c1][c2] :local)), and
   lower-commands frames it with a rotation-minimizing sweep at lowering time, so the
   up is continuous across the seam (no pinch) and the tessellation carries a :pure
   tag for re-edit. c1/c2/end are LOCAL to the segment's entry frame, composing under
   the consumption pose like every other Fase 1 high-level command. Node 0 is the
   pinned anchor at the origin. With no curves this delegates to the proven
   positions->rmf-commands (byte-identical to ensure-untwisted)."
  [nodes]
  (cond
    (< (count nodes) 2) (vec (:tail (first nodes)))   ; lone anchor: just its marks
    ;; fast path only when there's nothing to interleave (no curves, no marks)
    (and (not (some :bez nodes)) (not (some (comp seq :tail) nodes)))
    (vec (shape/positions->rmf-commands (mapv :pos nodes)))
    :else
    ;; per-segment builder, interleaving each node's :tail (mark / side-trip) after the
    ;; geometry that reaches it — node 0's tail leads, then each end node's tail.
    (vec (concat
          (:tail (first nodes))
          (mapcat
           (fn [{:keys [kind i a b h u c1 c2 dir dist up*]}]
             (let [right (m/normalize (m/cross h u))
                   w->l (fn [v] [(m/dot v right) (m/dot v u) (m/dot v h)])
                   to-local (fn [p] (w->l (m/v- p a)))
                   geom (if (= kind :bez)
                          [{:cmd :bezier-to :c1 (to-local c1) :c2 (to-local c2) :end (to-local b)
                            :steps (bezier-steps-at-bake a b)}]
                          [{:cmd :set-heading :args [(w->l dir) (w->l up*) :local]}
                           {:cmd :f :args [dist]}])]
               (into geom (:tail (nth nodes i)))))
           (walk-3d-segments nodes))))))

(defn- nodes->code-3d
  "The replacement source for a 3D edit: a (path (set-heading …)(f …) …) rail. The
   (edit-path …) marker is a stand-in for this."
  [nodes]
  (str "(path"
       (apply str (map #(str " " (cmd->code %)) (nodes->commands-3d nodes)))
       ")"))

;; -- 2D arc segments -----------------------------------------------------------
;; The DSL's in-plane arc-h/arc-v alias (see path-2d's local rebinding, macros.cljs)
;; always records as a genuine :arc-v — rotation axis = right, the fixed plane
;; normal (never touched by an in-plane turn, see the :th case below) — regardless
;; of which name the user wrote. project-2d-to-xy renames the tag to :arc-h purely
;; so this reads uniformly with a native 3D command stream; the physics stays
;; axis=right. Verified against turtle/arc-v directly over nREPL (both from the
;; path-2d seed pose and from the 3D-standard [1 0 0]/[0 0 1] pose) — NOT the same
;; formula as edit-path's own arc-v-endpoint (3D, seed->nodes-3d), whose :end
;; disagrees with turtle/arc-v by a sign (pre-existing, out of scope here; this is
;; an independent, checked closed form).
(defn- arc-2d-endpoint
  [pos heading up r theta]
  (let [s (js/Math.sin theta) c (js/Math.cos theta)]
    {:end    (m/v+ pos (m/v+ (m/v* heading (* r s)) (m/v* up (* r (- 1 c)))))
     :exit-h (m/v+ (m/v* heading c) (m/v* up s))
     :exit-u (m/v- (m/v* up c) (m/v* heading s))}))

(defn- seed->nodes
  "Recover 2D editor nodes straight from the recorder's own high-level commands
   (project-2d-to-xy's species-normalized stream) — the 2D twin of seed->nodes-3d,
   dev-docs/brief-recording-highlevel-lettura-2d.md: the SAME pose walk (reduce
   threading {pos heading up} in full 3D, decoding :bezier-to's c1/c2/end with
   turtle/local->world against the entry pose and advancing the exit frame with
   canonical-bezier-frame), followed by a projection onto the shape's (a,b) plane
   (a,b) = (-y,z). A :bezier-to IS one bezier node; an :arc-h IS one arc node with
   radius/sweep read straight from its args (arc-2d-endpoint, exact) — no
   reconstruction from tessellation, and no separate 'frame flip' tracking either
   ([[project-edit-path-2d]]'s wps-indexing hack): the projection always reads off
   whichever way the (genuinely-3D) frame is actually facing, so a bezier that
   turns the in-plane normal around just falls out correct.

   f/th/set-heading drive position/heading/up; a leading move-to is threaded into
   the pose (embedded at [0 -a b]) instead of offsetting bezier riders by hand;
   mark/side-trip attach to the current node's :tail. The last node keeps its
   final (exit) heading. Throws on a non-leading move-to, or on a residual
   tessellated curve fragment (:pure/:span/:smooth/:bez-cap/:arc-cap/:veer-deg) —
   after Fase 1 a 2D seed never carries those (same hard-error philosophy as
   seed->nodes-3d). Unsupported commands are dropped (unchanged from before).

   `closed?` (the path's :closed? flag): the bake always emits a closing segment
   back to node 0, so the LAST recovered node is that segment's endpoint (≈ node 0,
   modulo bake rounding). Fold it away — a curved seam's :bez moves onto node 0
   (whose :bez slot holds the closing segment)."
  ([seed-path] (seed->nodes seed-path false))
  ([seed-path closed?]
   (let [cmds (when (and (map? seed-path) (= :path (:type seed-path))) (:commands seed-path))]
     (cond
       (empty? cmds)
       {:nodes (mapv (fn [p] {:pos p :heading nil :tail []}) default-nodes) :closed? false :dropped []}

       (some (fn [[i c]] (and (pos? i) (= :move-to (:cmd c))))
             (map-indexed vector cmds))
       (throw (js/Error. "edit-path: (move-to …) is only supported as the first command."))

       :else
       (let [proj-pt (fn [[_ y z]] [(- y) z])
             proj-dir (fn [[_ y z]] (v2-norm [(- y) z]))
             move (fn [st dir d]
                    (let [np (m/v+ (:pos st) (m/v* dir d))]
                      (-> st (assoc :pos np)
                          (update :nodes conj {:pos (proj-pt np) :heading (proj-dir (:heading st)) :tail []}))))
             res (reduce
                  (fn [{:keys [pos heading up nodes] :as st} {:keys [cmd args] :as c}]
                    (cond
                      (or (:smooth c) (:bez-cap c) (:arc-cap c) (:veer-deg c) (:pure c) (:span c))
                      (throw (js/Error. (str "seed->nodes: unexpected tessellated curve fragment "
                                             cmd " in 2D seed — missing a Fase 3 migration?")))

                      (= :move-to cmd)
                      (let [[a b] (first args)]
                        (-> st (assoc :pos [0 (- a) b])
                            (assoc-in [:nodes 0 :pos] [a b])))

                      (= :f cmd) (move st heading (first args))
                      (= :b cmd) (move st heading (- (first args)))

                      ;; the DSL's in-plane turn — recorded as a genuine :tv
                      ;; (rotate heading & up together around the fixed right
                      ;; axis), renamed :th by project-2d-to-xy.
                      (= :th cmd)
                      (let [right (m/normalize (m/cross heading up))
                            rad (* (first args) (/ js/Math.PI 180))]
                        (assoc st :heading (rot-axis right heading rad)
                               :up (rot-axis right up rad)))

                      (= :set-heading cmd)
                      (let [new-frame (turtle/apply-set-heading {:heading heading :up up} args)]
                        (assoc st :heading (:heading new-frame) :up (:up new-frame)))

                      (= :bezier-to cmd)
                      (let [entry {:position pos :heading heading :up up}
                            c1-w (turtle/local->world entry (:c1 c))
                            c2-w (turtle/local->world entry (:c2 c))
                            end-w (turtle/local->world entry (:end c))
                            [{exit-h :heading exit-u :up}]
                            (bezier/canonical-bezier-frame pos c1-w c2-w end-w up [1.0])]
                        (-> st
                            (assoc :pos end-w :heading exit-h :up exit-u)
                            (update :nodes conj {:pos (proj-pt end-w) :heading nil :tail []
                                                 :bez {:c1 (proj-pt c1-w) :c2 (proj-pt c2-w)}})))

                      ;; the DSL's in-plane arc — recorded as a genuine :arc-v,
                      ;; renamed :arc-h by project-2d-to-xy (see arc-2d-endpoint).
                      (= :arc-h cmd)
                      (let [[r angle-deg] args
                            theta (* angle-deg (/ js/Math.PI 180))
                            {:keys [end exit-h exit-u]} (arc-2d-endpoint pos heading up r theta)]
                        (-> st
                            (assoc :pos end :heading exit-h :up exit-u)
                            (update :nodes conj {:pos (proj-pt end) :heading nil :tail [] :arc {}})))

                      (#{:mark :side-trip} cmd)
                      (update-in st [:nodes (dec (count nodes)) :tail] conj c)

                      :else (update st :dropped conj cmd)))
                  {:pos [0 0 0] :heading [0 -1 0] :up [0 0 1]
                   :nodes [{:pos [0 0] :heading nil :tail []}] :dropped []}
                  cmds)
             ns (:nodes res)
             n (count ns)
             ;; Closed (flag-driven): the bake always appends a closing segment back
             ;; to node 0, so the LAST recovered node is that segment's endpoint. Fold
             ;; it away — a curved seam's :bez moves onto node 0 (whose :bez slot holds
             ;; the closing segment). Need ≥3 real nodes left, i.e. ≥4 traced.
             cl? (and closed? (>= n 4))
             nodes (if cl?
                     (let [closing (peek ns)
                           base (vec (butlast ns))]
                       (cond-> base (:bez closing) (assoc-in [0 :bez] (:bez closing))))
                     ;; open: the last node keeps the final (exit) heading
                     (assoc-in ns [(dec n) :heading] (proj-dir (:heading res))))]
         {:nodes nodes :closed? cl? :dropped (vec (distinct (:dropped res)))})))))

(defn- project-2d-to-xy
  "Normalize a :2d path's RAW high-level commands (dev-docs/brief-recording-
   highlevel-lettura-2d.md) into the canonical stream seed->nodes reads: drop the
   leading (th -90) seed the path-2d macro prepends (seed->nodes' own starting
   pose already reflects it — heading [0 -1 0], up [0 0 1]) and rename tv→th /
   arc-v→arc-h (path-2d's own DSL aliasing, inverted, so the command names read
   uniformly whether they came from path-2d or a native 3D path).

   A side-trip's sub-path is left untouched: its body is its own independently-
   scoped (path …) recording (verified over nREPL — a (side-trip (tv …)) written
   inside path-2d records a genuine :tv, NOT a renamed :th, because the nested
   `path` macro re-binds th/tv/tr/rt/lt/arc-h/arc-v fresh for its own body,
   shadowing path-2d's aliasing), so applying this rename to it would mislabel a
   real 3D command. seed->nodes carries a side-trip's sub-path opaquely into the
   node's :tail regardless (never interprets it for node-building), and
   cmd->code-2d's bake already walks (:commands sub) verbatim — this mirrors that.

   A :3d path is returned unchanged (its commands are already native)."
  [path]
  (if-not (= :2d (:species path))
    path
    (let [cmds (:commands path)
          cmds (if (and (= :th (:cmd (first cmds)))
                        (= -90 (first (:args (first cmds)))))
                 (rest cmds)
                 cmds)
          conv (fn [{:keys [cmd] :as c}]
                 (case cmd
                   :tv    (assoc c :cmd :th)
                   :arc-v (assoc c :cmd :arc-h)
                   c))]
      {:type :path :commands (mapv conv cmds)})))

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

;; -- closed (2D only): the closing segment (last node → node 0) is a real, editable
;; segment whose curve lives in node 0's :bez slot. Useful for path-to-shape profiles,
;; where it lets the seam's bezier handles be controlled (an open path leaves one
;; handle missing at each end). 3D rails are always open.
(defn- closed? [s] (and (:closed? s) (not (three-d? s)) (>= (count (:nodes s)) 3)))

(defn- prev-idx
  "Index of the node before node `i` along the path: i−1, or the last node when
   `i` is 0 in a closed path (the closing segment's start), else nil."
  [nodes closed? i]
  (cond (pos? i)                              (dec i)
        (and closed? (>= (count nodes) 3))    (dec (count nodes))
        :else                                 nil))

(defn- next-idx
  "Index of the node after node `i` along the path: i+1, or node 0 when `i` is the
   last node of a closed path (the closing segment's end), else nil."
  [nodes closed? i]
  (cond (< (inc i) (count nodes))             (inc i)
        (and closed? (>= (count nodes) 3))    0
        :else                                 nil))

(defn- reconstrain!
  "swap!-fn: re-snap the session's bezier handles to their tangents so smooth nodes
   stay tangent-continuous. 2D honours the closed flag (node 0's closing handle is
   constrained too); 3D rails are open."
  [s]
  (if (three-d? s)
    (update s :nodes conform-rail-start-3d)
    (update s :nodes reconstrain-handles (boolean (closed? s)))))

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

(defn- update-mark-labels!
  "Push billboard labels for the marked nodes (their mark name floats at the node and
   faces the camera). `pts` are the nodes' world positions in the render's frame. The
   labels can be hidden (Shift+m) when they get in the way of editing a node."
  [nodes pts]
  (viewport/set-labels!
   (when-not (:labels-hidden? @session)
     (keep (fn [i] (when-let [nm (node-mark-name (nth nodes i))]
                     {:text (name nm) :position (nth pts i)}))
           (range (count nodes))))))

(defn- toggle-labels!
  "Show/hide the mark billboard labels (they occlude the node they sit on while editing)."
  []
  (swap! session update :labels-hidden? not)
  (render!))

(defn- render-3d!
  "Redraw the 3D rail: node positions in world space, drawn as rings oriented to the
   active working plane — the ring foreshortens to a line when the plane is edge-on to
   the camera, signalling 'orbit to edit here'. A bezier segment is drawn as its
   tessellation with its two control handles (squares + guide lines). A faint grid in
   the active plane (centred on the selected node) gives a spatial reference. Mark
   names float at their nodes as billboard labels."
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
        ;; a 3D path is an OPEN rail (extrude/loft trajectory) — no closing segment.
        ;; Bezier segments tessellate (via the shared frame walk); straights are a line.
        segs (walk-3d-segments nodes)
        seg-lines (mapcat
                   (fn [{:keys [kind a b pts]}]
                     (if (and (= kind :bez) (seq pts))
                       (mapv (fn [p q] {:from p :to q :color line-color}) pts (rest pts))
                       [{:from a :to b :color line-color}]))
                   segs)
        ;; bezier handle guide lines: start→c1, end→c2. c1 (the incoming/direction
        ;; handle) reads cusp (magenta) when its start node is a cusp, else
        ;; length-only/locked (teal) — mirrors render!'s 2D convention. Node 0 can
        ;; never be a cusp (toggle-cusp! guards it), so segment 1's c1 always
        ;; reads locked, never the free/cyan color.
        handle-lines (mapcat
                      (fn [{:keys [kind i a b c1 c2]}]
                        (when (= kind :bez)
                          (let [c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-len-color)]
                            [{:from a :to c1 :color c1col}
                             {:from b :to c2 :color handle-color}])))
                      segs)
        handle-dots (mapcat
                     (fn [{:keys [kind i c1 c2]}]
                       (when (= kind :bez)
                         (let [c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-len-color)]
                           [{:pos c1 :radius handle-radius :color c1col :square true :normal normal}
                            {:pos c2 :radius handle-radius :color handle-color :square true :normal normal}])))
                     segs)
        node-dots (mapv (fn [i pw]
                          (let [marked? (seq (:tail (nth nodes i)))
                                start? (zero? i) exit? (= i (dec n))]
                            {:pos pw :ring true :normal normal
                             :radius (if (= i selected) node-radius-sel node-radius)
                             :color (cond marked?           mark-color
                                          (or start? exit?) exit-color
                                          (= i selected)    sel-color
                                          :else             node-color)}))
                        (range n) pts)
        ;; affordance: the rail's start direction is FIXED at +X (the anchor's
        ;; heading) — a short arrow at node 0 shows it before the user drags
        ;; anything off-axis and hits the rail-start invariant.
        start-arrow (when (seq pts)
                      [{:from (first pts) :to (m/v+ (first pts) (m/v* [1 0 0] start-arrow-len))
                        :color handle-len-color}])]
    (viewport/show-preview! [{:type :lines :data grid-lines}
                             {:type :lines :data (vec (concat seg-lines handle-lines start-arrow)) :on-top true}
                             {:type :dots :data (vec (concat node-dots handle-dots))}])
    (update-mark-labels! nodes pts)))

(defn- render!
  "Redraw the ephemeral path (straight + bezier segments), bezier handles, and the
   node dots from the current session state."
  []
  (if (three-d? @session)
    (render-3d! @session)
    (when-let [{:keys [nodes selected pose] :as s} @session]
      (let [basis (plane-basis pose)
            normal (m/cross (:px basis) (:py basis))   ; working-plane normal (start ring)
            ->w #(plane->world basis %)
            pts (mapv #(->w (node-pos %)) nodes)
            n (count pts)
            cl (closed? s)
            ;; arc tessellations (world) keyed by end-node index, with the right
            ;; incoming heading per node (shared walk; tangent arcs leave smooth)
            arc-pts (into {} (keep-indexed
                              (fn [k seg]
                                (when (and (= :arc (:kind seg)) (seq (:pts seg)))
                                  [(inc k) (mapv ->w (:pts seg))]))
                              (walk-2d-segments nodes)))
          ;; per-segment lines: bezier-tessellated / arc-tessellated / straight
            seg-lines (mapcat
                       (fn [i]
                         (let [a (nth pts (dec i)) b (nth pts i)]
                           (cond
                             (:bez (nth nodes i))
                             (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                   c1w (->w c1) c2w (->w c2) steps 24
                                   cp (mapv #(bezier/cubic-bezier-point a c1w c2w b (/ % steps))
                                            (range (inc steps)))]
                               (mapv (fn [p q] {:from p :to q :color line-color}) cp (rest cp)))

                             (arc-pts i)
                             (let [tw (arc-pts i)]
                               (mapv (fn [p q] {:from p :to q :color line-color}) tw (rest tw)))

                             :else [{:from a :to b :color line-color}])))
                       (range 1 n))
            ;; closing segment (last → node 0). Open: a dim hint line. Closed: a real
            ;; segment, drawn from node 0's :bez (the seam's curve) when present.
            closing (cond
                      (and cl (:bez (first nodes)))
                      (let [{:keys [c1 c2]} (:bez (first nodes))
                            a (peek pts) b (first pts)
                            c1w (->w c1) c2w (->w c2) steps 24
                            cp (mapv #(bezier/cubic-bezier-point a c1w c2w b (/ % steps))
                                     (range (inc steps)))]
                        (mapv (fn [p q] {:from p :to q :color line-color}) cp (rest cp)))
                      cl       [{:from (peek pts) :to (first pts) :color line-color}]
                      (>= n 3) [{:from (peek pts) :to (first pts) :color close-color}])
          ;; handle lines: bezier endpoint → control point (c1 magenta when its
          ;; start node is a cusp). Tangent arcs have no handle.
            handle-lines (mapcat
                          (fn [i]
                            (when (:bez (nth nodes i))
                              (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                    c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-len-color)]
                                [{:from (nth pts (dec i)) :to (->w c1) :color c1col}
                                 {:from (nth pts i) :to (->w c2) :color handle-color}])))
                          (range 1 n))
            ;; closed seam handles: node 0's :bez c1 (near the last node) / c2 (near node 0)
            closing-handle-lines (when (and cl (:bez (first nodes)))
                                   (let [{:keys [c1 c2]} (:bez (first nodes))
                                         c1col (if (false? (:smooth? (peek nodes))) cusp-color handle-len-color)]
                                     [{:from (peek pts) :to (->w c1) :color c1col}
                                      {:from (first pts) :to (->w c2) :color handle-color}]))
            segs (vec (concat seg-lines closing handle-lines closing-handle-lines))
            node-dots (mapv (fn [i pw]
                              (let [marked? (seq (:tail (nth nodes i)))
                                    start?  (zero? i)
                                    ;; closed: no distinct exit node; node 0 keeps its
                                    ;; ring as the anchor (creation-pose) marker.
                                    exit?   (and (not cl) (= i (dec n)))]
                                (cond-> {:pos pw
                                         :radius (if (= i selected) node-radius-sel node-radius)
                                         :color  (cond marked?                   mark-color
                                                       (or start? exit?)         exit-color
                                                       (= i selected)            sel-color
                                                       :else                     node-color)}
                                  start? (assoc :ring true :normal normal))))
                            (range n) pts)
            handle-dots (mapcat
                         (fn [i]
                           (when (:bez (nth nodes i))
                             (let [{:keys [c1 c2]} (:bez (nth nodes i))
                                   c1col (if (false? (:smooth? (nth nodes (dec i)))) cusp-color handle-len-color)]
                             ;; control points are little squares (shape sets them
                             ;; apart from the round nodes — no extra colour needed)
                               [{:pos (->w c1) :radius handle-radius :color c1col :square true :normal normal}
                                {:pos (->w c2) :radius handle-radius :color handle-color :square true :normal normal}])))
                         (range 1 n))
            closing-handle-dots (when (and cl (:bez (first nodes)))
                                  (let [{:keys [c1 c2]} (:bez (first nodes))
                                        c1col (if (false? (:smooth? (peek nodes))) cusp-color handle-len-color)]
                                    [{:pos (->w c1) :radius handle-radius :color c1col :square true :normal normal}
                                     {:pos (->w c2) :radius handle-radius :color handle-color :square true :normal normal}]))
            dots (vec (concat node-dots handle-dots closing-handle-dots))]
        (viewport/show-preview! [{:type :lines :data segs :on-top true}
                                 {:type :dots :data dots}])
        (update-mark-labels! nodes pts)))))

;; ============================================================
;; Live re-eval (downstream geometry)
;; ============================================================

(defn- current-code []
  (if (three-d? @session)
    (nodes->code-3d (:nodes @session))
    (nodes->code (:nodes @session) (closed? @session))))

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
                     (let [p (some-> (:nodes s) (nth (:selected s) nil) :pos)
                           r1 #(/ (js/Math.round (* % 10)) 10)]
                       (when (and p (vector? p))
                         (str " · [" (r1 (nth p 0)) " " (r1 (nth p 1)) " " (r1 (nth p 2)) "]")))))))
      ;; plane radios reflect the active plane
      (when (three-d? s)
        (doseq [[cls pl] [[".ep-plane-f" :f] [".ep-plane-r" :r] [".ep-plane-u" :u]]]
          (when-let [^js rb (.querySelector panel cls)]
            (set! (.-checked rb) (= pl (:plane s :f))))))
      (when-let [^js el (.querySelector panel ".ep-step")]
        (let [buf (:digit-buffer s)]
          (when (not= el (.-activeElement js/document))
            (set! (.-value el) (str (if (seq buf) buf (:step s)))))))
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
                (not= el focused) (set! (.-value el) (str (r1 val))))))))
      ;; closed toggle (2D): label + active class reflect the flag
      (when-let [^js el (.querySelector panel ".ep-closed")]
        (let [c? (boolean (:closed? s))]
          (set! (.-textContent el) (if c? "closed" "open"))
          (.toggle (.-classList el) "active" c?)))
      ;; mark name of the selected node (2D + 3D); don't clobber while editing it
      (when-let [^js el (.querySelector panel ".ep-mark")]
        (when (not= el (.-activeElement js/document))
          (let [nm (some-> (:nodes s) (nth (:selected s) nil) node-mark-name)]
            (set! (.-value el) (if nm (name nm) ""))))))))

(defn- create-panel! []
  (let [panel (.createElement js/document "div")
        td? (three-d? @session)]
    (set! (.-id panel) "edit-path-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>" (if td? "edit-path" "edit-path-2d")
               "<span class='pilot-mode-badge'>" (if td? "3D rail" "polyline") "</span></div>"
               ;; status line
               "<div class='ep-info'>nodes: 0 · sel: 0</div>"
               ;; global options for the whole path / editor (working plane + nudge step)
               "<div class='ep-section'><span class='ep-section-label'>Path</span>"
               (when td?
                 (str "<span class='ep-planes'>plane "
                      "<label><input type='radio' name='ep-plane' class='ep-plane-f'>f</label>"
                      "<label><input type='radio' name='ep-plane' class='ep-plane-r'>r</label>"
                      "<label><input type='radio' name='ep-plane' class='ep-plane-u'>u</label></span>"))
               "<label>step <input class='ep-step' type='number' step='1' min='0' "
               "style='width:52px'> mm</label>"
               ;; 2D: close the path so the seam (last → first) is an editable segment
               (when-not td?
                 "<button class='pilot-btn ep-closed' type='button'>open</button>")
               "</div>"
               ;; parameters of the currently selected node (segment len/angle + mark)
               "<div class='ep-section'><span class='ep-section-label'>Node</span>"
               (when td?
                 (str "<label>len <input class='ep-len' type='number' step='1' "
                      "style='width:60px' disabled></label>"
                      "<label>ang° <input class='ep-ang' type='number' step='1' "
                      "style='width:60px' disabled></label>"))
               "<label>mark <input id='ep-mark-input' class='ep-mark' type='text' "
               "placeholder='(none)' style='width:90px'></label>"
               "</div>"
               ;; keyboard help (collapsed behind the header "?" toggle)
               "<div class='pilot-commands modal-help'>"
               (if td?
                 (str "click: add · drag node/handle · Shift+drag node: axis-lock · "
                      "Shift+drag handle: length · Tab: next · c: curve · t: raccordo · "
                      "x: cusp · Shift+A: smooth all · Shift+X: lines all · "
                      "m: mark · Shift+m: labels · Ins/i: split · f/r/u: plane · ←→↑↓: move · "
                      "Shift/Alt+↑↓: handles · ⌘Z: undo · Del · Enter: OK · Esc: cancel")
                 (str "click: add · drag node/handle: move · Alt+drag: handle only · "
                      "Tab: next · c: curve · a: arc · t: raccordo · x: cusp · "
                      "Shift+A: smooth all · Shift+X: lines all · m: mark · "
                      "Ins/i: split · ←→↑↓: node · Shift+↑↓: c1 · Alt+↑↓: c2 · "
                      "⌘Z: undo · Del · Enter: OK · Esc: cancel"))
               "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-ok ep-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel ep-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".ep-ok") "click" (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".ep-cancel") "click" (fn [_] (cancel!)))
    (when-let [^js closed-btn (.querySelector panel ".ep-closed")]
      (.addEventListener closed-btn "click" (fn [_] (toggle-closed!))))
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
    (when-let [^js step-in (.querySelector panel ".ep-step")]
      (.addEventListener step-in "change"
                         (fn [^js e] (let [v (js/parseFloat (.. e -target -value))]
                                       (when (and (js/isFinite v) (pos? v))
                                         (swap! session assoc :step v :digit-buffer ""))))))
    (doseq [[cls pl] [[".ep-plane-f" :f] [".ep-plane-r" :r] [".ep-plane-u" :u]]]
      (when-let [^js rb (.querySelector panel cls)]
        (.addEventListener rb "change" (fn [_] (set-plane! pl)))))
    (when-let [^js mark-in (.querySelector panel ".ep-mark")]
      ;; commit on Tab / blur (the `change` event), exactly like the numeric fields —
      ;; Enter is left to mean only "OK the editor", never "commit the field".
      (.addEventListener mark-in "change" (fn [^js e] (set-node-mark! (.. e -target -value)))))
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
  (let [snap (select-keys @session [:nodes :selected :closed?])]
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
                                   :closed? (boolean (:closed? snap))
                                   :undo (pop u))
                            (dissoc :dragging :dragging-handle)))
        (refresh-preview!)
        (update-panel!))
      (state/capture-println "edit-path: nothing to undo"))))

;; ============================================================
;; Editing operations
;; ============================================================

(defn- append-plain-node!
  "Append a STRAIGHT node at coord `pos` at the end of the path and select it (no
   curve). Used where a split must preserve a straight segment (the closing seam)."
  [pos]
  (push-undo!)
  (let [nodes (vec (:nodes @session))]
    (swap! session assoc :nodes (conj nodes {:pos pos :tail []})
           :selected (count nodes))
    (refresh-preview!)
    (update-panel!)))

(defn- append-node!
  "Append a node at coord `pos` at the end of the path and select it. New segments
   default to a SMOOTH bezier (handles collinear with the chord, so it looks straight
   but is immediately shapeable, and bakes as a clean line until curved); the first
   node has no incoming segment so it stays a bare point. 2D + 3D."
  [pos]
  (push-undo!)
  (let [nodes (vec (:nodes @session))
        node  (if (seq nodes)
                (let [A (node-pos (peek nodes))]
                  {:pos pos :tail [] :bez {:c1 (lerp A pos (/ 1.0 3))
                                           :c2 (lerp A pos (/ 2.0 3))}})
                {:pos pos :tail []})]
    (swap! session assoc :nodes (conj nodes node) :selected (count nodes))
    (swap! session reconstrain!)
    (refresh-preview!)
    (update-panel!)))

(defn- insert-node!
  "Insert a node at 2D plane coord `pos` at index `idx` (split) and select it."
  [idx pos]
  (swap! session update :nodes
         #(vec (concat (take idx %) [{:pos pos :tail []}] (drop idx %))))
  (swap! session assoc :selected idx)
  ;; a plain node landing at index 1 in 3D (e.g. a split) has no :bez for
  ;; reconstrain-handles-3d to snap — conform-rail-start-3d seeds one when it's
  ;; off the anchor's fixed heading.
  (swap! session reconstrain!)
  (refresh-preview!)
  (update-panel!))

(defn- mid2 [[ax ay] [bx by]] [(/ (+ ax bx) 2) (/ (+ ay by) 2)])
(defn- midv "Midpoint of two vectors of any dimension." [a b] (mapv #(/ (+ %1 %2) 2) a b))

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
    (cond
      ;; 3D: open rail — split the incoming segment. A bezier splits with de Casteljau
      ;; at t=0.5 (curve shape preserved); a straight splits at the 3D midpoint. Node 0
      ;; is the pinned anchor, so there's nothing before it.
      (three-d? s)
      (when (and (>= sel 1) (< sel n))
        (push-undo!)
        (let [b (nth nodes sel)
              pa (node-pos (nth nodes (dec sel))) pb (node-pos b)]
          (if-let [{:keys [c1 c2]} (:bez b)]
            (let [m01 (midv pa c1) m12 (midv c1 c2) m23 (midv c2 pb)
                  m012 (midv m01 m12) m123 (midv m12 m23)
                  mp (midv m012 m123)
                  newn {:pos mp :tail [] :bez {:c1 m01 :c2 m012}}]
              (swap! session
                     (fn [st]
                       (-> st
                           (assoc-in [:nodes sel :bez] {:c1 m123 :c2 m23})
                           (update :nodes #(vec (concat (take sel %) [newn] (drop sel %))))
                           (assoc :selected sel))))
              ;; splitting the first segment (sel=1) puts a new bezier at node 1
              ;; whose c1 (m01) isn't necessarily on the anchor's fixed heading.
              (swap! session reconstrain!)
              (refresh-preview!) (update-panel!))
            (insert-node! sel (midv pa pb)))))

      (< n 2) nil

      :else
      (if (and (zero? sel) (>= n 3))
        ;; closing segment is straight → append the chord midpoint as a plain node
        ;; (append-plain-node! pushes undo and selects it; keeps the split straight).
        (append-plain-node! (mid2 (node-pos (last nodes)) (node-pos (first nodes))))
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
              (swap! session reconstrain!)
              (refresh-preview!) (update-panel!))

            ;; tangent arc: split into two tangent arcs at the mid-sweep point. Both
            ;; halves stay tangent to their incoming heading, so the overall arc is
            ;; preserved (the new node just carries the :arc flag).
            (:arc b)
            (let [seg (nth (walk-2d-segments nodes) (dec i) nil)]
              (if (and (= :arc (:kind seg)) (seq (:pts seg)))
                (let [pts (:pts seg)
                      mid (nth pts (quot (count pts) 2))
                      newn {:pos mid :tail [] :arc {}}]
                  (swap! session
                         (fn [s]
                           (-> s
                               (update :nodes #(vec (concat (take i %) [newn] (drop i %))))
                               (assoc :selected i))))
                  (refresh-preview!) (update-panel!))
                (insert-node! i (mid2 pa pb))))

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
  "Set node `idx` to absolute `new-pos` (2D [a b] or 3D [x y z]), dragging its attached
   bezier handles (its incoming :c2 and the next segment's :c1) along by the same
   delta, and dropping its explicit heading unless it's notable."
  [idx new-pos]
  (when-not (pinned? @session idx)
    (let [nodes (:nodes @session)
          delta (mapv - new-pos (node-pos (nth nodes idx)))
          tr (fn [p] (mapv + p delta))
          ;; closed: the LAST node's outgoing handle is the closing segment's c1,
          ;; stored on node 0 (the closing segment runs last → node 0).
          closing-c1? (and (closed? @session)
                           (= idx (dec (count nodes)))
                           (get-in nodes [0 :bez]))]
      (swap! session
             (fn [s]
               (cond-> (assoc-in s [:nodes idx :pos] new-pos)
                 (get-in nodes [idx :bez])       (update-in [:nodes idx :bez :c2] tr)
                 (get-in nodes [(inc idx) :bez]) (update-in [:nodes (inc idx) :bez :c1] tr)
                 closing-c1?                     (update-in [:nodes 0 :bez :c1] tr))))
      (when-not (notable? (:nodes @session) idx)
        (swap! session assoc-in [:nodes idx :heading] nil))
      ;; moving a node changes incoming tangents → re-snap smooth bezier handles
      ;; (cusp nodes are left free). 2D + 3D.
      (swap! session reconstrain!))))

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

;; -- 3D precision fields: the selected node's incoming segment as length + angle,
;; both measured IN the active plane (so they change when you switch f/r/u): the
;; segment is projected onto the plane; the out-of-plane component is preserved
;; when editing. Angle is relative to the previous segment for node ≥ 2, else
;; absolute (vs the plane's px axis).

(defn- seg-dir [s i]
  (m/normalize (m/v- (:pos (nth (:nodes s) i)) (:pos (nth (:nodes s) (dec i))))))

(defn- in-plane-angle [s v]
  (let [b (active-basis s)]
    (js/Math.atan2 (m/dot v (:py b)) (m/dot v (:px b)))))

(defn- seg-inplane
  "The incoming segment vector of node i split as [in-plane out-of-plane] in the
   active plane (out-of-plane is along the plane normal)."
  [s i]
  (let [nrm (active-plane-normal s)
        seg (m/v- (:pos (nth (:nodes s) i)) (:pos (nth (:nodes s) (dec i))))
        op (m/v* nrm (m/dot seg nrm))]
    [(m/v- seg op) op]))

(defn- seg-len
  "Length of the incoming segment's projection onto the active plane (so it changes
   with f/r/u, like the angle; 0 when the segment is perpendicular to the plane)."
  [s i]
  (m/magnitude (first (seg-inplane s i))))

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
      (let [[ip op] (seg-inplane s i)
            ipm (m/magnitude ip)]
        ;; scale the in-plane component to `len`, preserving the out-of-plane part
        (when (> ipm 1e-6)
          (push-undo!)
          (let [prev (:pos (nth (:nodes s) (dec i)))]
            (move-node! i (m/v+ prev (m/v+ (m/v* ip (/ len ipm)) op))))
          (refresh-preview!) (update-panel!))))))

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
  "Move control point `which` (:c1/:c2) of the selected node's bezier by sign·step.
   2D: along axis 0=x / 1=y. 3D: along the active plane axis (px/py). Then re-snap, so
   a smooth c1 slides along its tangent (length-only) while a cusp's stays free."
  [which axis sign]
  (let [s @session i (:selected s) step (:step s)]
    (when (get-in (:nodes s) [i :bez which])
      (push-undo!)
      (if (three-d? s)
        (let [b (active-basis s) ax (if (zero? axis) (:px b) (:py b))]
          (swap! session update-in [:nodes i :bez which] #(m/v+ % (m/v* ax (* sign step)))))
        (swap! session update-in [:nodes i :bez which axis] + (* sign step)))
      (swap! session reconstrain!)
      (refresh-preview!)
      (update-panel!))))

(defn- incoming-heading-3d
  "Unit heading the rail arrives at node (dec i) with (= the tangent a curve leaving
   that node should start along), from the shared frame walk."
  [nodes i]
  (or (:h (nth (walk-3d-segments nodes) (dec i) nil)) [1 0 0]))

(defn- toggle-bezier!
  "Toggle the selected node's incoming segment between straight and cubic bezier.
   On enabling, the control points start at 1/3 (along the incoming tangent, so the
   start is smooth) and 2/3 along the chord. 2D handles then snap to the tangent;
   3D handles are free."
  []
  (let [s @session i (:selected s) nodes (:nodes s)
        cl (closed? s)
        ;; the start of node i's incoming segment: i−1, or (closed, node 0) the last
        ;; node — the closing segment. nil = no incoming segment (open path's node 0).
        pi (if (three-d? s) (when (pos? i) (dec i)) (prev-idx nodes cl i))]
    (when (and (< i (count nodes)) pi)
      (push-undo!)
      (if (:bez (nth nodes i))
        (swap! session update-in [:nodes i] dissoc :bez)
        (if (three-d? s)
          (let [A (node-pos (nth nodes pi)) B (node-pos (nth nodes i))
                h (incoming-heading-3d nodes i)
                chord (m/magnitude (m/v- B A))]
            (swap! session assoc-in [:nodes i :bez]
                   {:c1 (m/v+ A (m/v* h (/ chord 3.0)))            ; along incoming → smooth
                    :c2 (m/v+ A (m/v* (m/v- B A) (/ 2.0 3)))}))
          (let [[ax ay] (node-pos (nth nodes pi))
                [bx by] (node-pos (nth nodes i))
                along (fn [t] [(+ ax (* (- bx ax) t)) (+ ay (* (- by ay) t))])]
            ;; bezier and arc are mutually exclusive segment types
            (swap! session update-in [:nodes i] dissoc :arc)
            (swap! session assoc-in [:nodes i :bez] {:c1 (along (/ 1.0 3)) :c2 (along (/ 2.0 3))}))))
      (swap! session reconstrain!)
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-arc!
  "2D `a`: toggle the selected node's incoming segment to a true tangent `arc-v` on/off
   — a circular arc leaving the start node tangent to the incoming heading (smooth start
   only; shape from the incoming heading + node positions, no free belly). For a
   both-ends-tangent smooth corner use `t` (toggle-tangent!). 2D only — 3D rounds with
   a bezier (`t`), since arc-h on a 3D rail twists the tube."
  []
  (let [s @session i (:selected s) nodes (:nodes s)]
    (when (and (pos? i) (< i (count nodes)) (not (three-d? s)))
      (push-undo!)
      (if (:arc (nth nodes i))
        (swap! session update-in [:nodes i] dissoc :arc)
        (swap! session (fn [st]
                         (-> st
                             (update-in [:nodes i] dissoc :bez)
                             (assoc-in [:nodes i :arc] {})))))
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-tangent!
  "`t` (2D + 3D): make/refit the selected node's incoming segment a 'raccordo' — a
   BOTH-ends-tangent bezier. c1 leaves the start node along the incoming heading AND c2
   arrives along the OUTGOING direction (toward the next node), so the corner is smooth
   on both sides. This is the deliberate exception to 'each curve sets its own arrival
   heading' — a circular arc can't do it (over-determined), a bezier can. Always
   (re)applies (never reverts to a line — that's `c`); re-pressing re-fits the tangents
   after a neighbour moves. On the last node (no outgoing) it falls back to a
   start-tangent-only curve. Handles stay editable; in 2D the start handle then re-snaps
   to the tangent via reconstrain-handles."
  []
  (let [s @session i (:selected s) nodes (:nodes s)
        cl (closed? s)
        pi (if (three-d? s) (when (pos? i) (dec i)) (prev-idx nodes cl i))]
    (when (and (< i (count nodes)) pi)
      (push-undo!)
      (let [A (node-pos (nth nodes pi)) B (node-pos (nth nodes i))
            L (/ (m/magnitude (m/v- B A)) 3.0)
            nxt-i (if (three-d? s) (when (< (inc i) (count nodes)) (inc i)) (next-idx nodes cl i))
            nxt (when nxt-i (node-pos (nth nodes nxt-i)))
            norm (if (three-d? s) m/normalize v2-norm)
            out-dir (when nxt (let [d (m/v- nxt B)] (when (> (m/magnitude d) 1e-9) (norm d))))
            h (if (three-d? s) (incoming-heading-3d nodes i) (incoming-tangent nodes pi cl))
            handles (cond
                      out-dir                       ; tangent at BOTH ends
                      {:c1 (m/v+ A (m/v* h L)) :c2 (m/v- B (m/v* out-dir L))}
                      ;; last node, 3D: an arc-shaped bezier (start-tangent only)
                      (three-d? s)
                      (if-let [g (tangent-arc-geom-3d A h B)]
                        (arc->bez-handles A B (:entry-tan g) (:exit-tan g) (:sweep-deg g) (:r g))
                        {:c1 (m/v+ A (m/v* h L)) :c2 (m/v+ A (m/v* (m/v- B A) (/ 2.0 3)))})
                      ;; last node, 2D: start-tangent c1, c2 at 2/3 chord
                      :else
                      {:c1 (m/v+ A (m/v* h L)) :c2 (m/v+ A (m/v* (m/v- B A) (/ 2.0 3)))})]
        (swap! session (fn [st] (-> st (update-in [:nodes i] dissoc :arc)
                                    (assoc-in [:nodes i :bez] handles)))))
      (swap! session reconstrain!)
      (refresh-preview!)
      (update-panel!))))

(defn- toggle-cusp!
  "Toggle the selected node between smooth and cusp. A cusp frees its OUTGOING
   bezier handle (c1 of the next segment) so the curve can leave at any angle.
   In 3D, node 0 (the rail's anchor) has no cusp option: it is always smooth, so
   segment 1's c1 stays locked to the anchor's fixed heading (the rail-start
   invariant) — see reconstrain-handles-3d."
  []
  (let [s @session i (:selected s)]
    (when (and (< i (count (:nodes s))) (not (and (three-d? s) (zero? i))))
      (push-undo!)
      (swap! session update-in [:nodes i :smooth?] #(if (false? %) true false))
      (swap! session reconstrain!)
      (refresh-preview!)
      (update-panel!))))

(defn- smooth-all!
  "Shift+A: make EVERY segment a smooth bezier and clear all cusps. Segments that are
   already bezier keep their handles (only the cusp flag is cleared); straight/arc
   segments gain default chord-proportional handles. The single reconstrain then makes
   every node tangent-continuous (G1) — a corner rounds, a straight run stays straight.
   2D (incl. the closed seam) + 3D."
  []
  (let [s @session
        td (three-d? s)
        cl (closed? s)
        n (count (:nodes s))
        idxs (cond-> (vec (range 1 n))
               (and (not td) cl (>= n 3)) (conj 0))]   ; closed 2D: node 0's seam too
    (when (seq idxs)
      (push-undo!)
      (swap! session update :nodes
             (fn [nds]
               (reduce (fn [acc i]
                         (let [pi   (if (zero? i) (dec n) (dec i))   ; closed seam → last
                               A    (node-pos (nth acc pi))
                               B    (node-pos (nth acc i))
                               node (dissoc (nth acc i) :arc :smooth?)
                               node (if (:bez node)
                                      node
                                      (assoc node :bez {:c1 (lerp A B (/ 1.0 3))
                                                        :c2 (lerp A B (/ 2.0 3))}))]
                           (assoc acc i node)))
                       nds idxs)))
      (swap! session reconstrain!)
      (refresh-preview!)
      (update-panel!))))

(defn- linear-all!
  "Shift+X: the inverse of Shift+A — drop every curve, turning all segments back into
   straight lines (removes :bez and :arc, and the cusp flag). 2D + 3D."
  []
  (when (seq (:nodes @session))
    (push-undo!)
    (swap! session update :nodes #(mapv (fn [nd] (dissoc nd :bez :arc :smooth?)) %))
    (refresh-preview!)
    (update-panel!)))

(defn- toggle-closed!
  "2D: toggle the path between OPEN and CLOSED. Closed makes the closing segment
   (last node → node 0) real and editable — select node 0 and press c / t to curve
   the seam and drag its handles — and bakes it so path-to-shape gets a controlled
   join (instead of the straight auto-close). 3D rails are always open."
  []
  (let [s @session]
    (when-not (three-d? s)
      (push-undo!)
      (swap! session update :closed? not)
      (swap! session reconstrain!)              ; seam tangents change with the flag
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

;; -- marks: a named anchor point on the path, carried on the node's :tail as a
;; record-only (mark :name) command (it rides edits and re-emits in the bake). Each
;; node holds at most one mark for editing; side-trips on the tail are preserved.

(defn- node-mark-name
  "The keyword name of the first :mark on the node's :tail, or nil."
  [node]
  (some (fn [c] (when (= :mark (:cmd c)) (first (:args c)))) (:tail node)))

(defn- set-node-mark!
  "Set the selected node's mark to `nm` (a string, sans colon): blank removes the mark,
   otherwise add it / rename it, keeping any side-trips already on the tail."
  [nm]
  (let [s @session i (:selected s) nodes (:nodes s)]
    (when (< i (count nodes))
      (let [nm (str/replace (str/trim (str nm)) #"\s+" "-")
            without (vec (remove #(= :mark (:cmd %)) (:tail (nth nodes i))))
            new-tail (if (seq nm)
                       (vec (cons {:cmd :mark :args [(keyword nm)]} without))
                       without)]
        (when (not= new-tail (vec (:tail (nth nodes i))))
          (push-undo!)
          (swap! session assoc-in [:nodes i :tail] new-tail)
          (refresh-preview!)
          (update-panel!))))))

(defn- add-mark-quick!
  "`m` key: give the selected node a mark with a default unique name (:m1, :m2, …) and
   focus the panel field to rename. If it already has a mark, just focus the field."
  []
  (let [s @session i (:selected s) nodes (:nodes s)
        focus! #(some-> (.getElementById js/document "ep-mark-input") (doto .focus .select))]
    (when (< i (count nodes))
      (when-not (node-mark-name (nth nodes i))
        (let [used (set (keep node-mark-name nodes))
              nm (first (remove used (map #(keyword (str "m" %)) (iterate inc 1))))]
          (set-node-mark! (name nm))))
      (focus!))))

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
  "The bezier control handle closest to plane point p within handle-snap, as
   {:idx i :which :c1/:c2}, or nil. (Tangent arcs have no handle.)"
  [nodes [px py]]
  (let [cands (mapcat (fn [i]
                        (when (:bez (nth nodes i))
                          (let [{:keys [c1 c2]} (:bez (nth nodes i))]
                            [{:idx i :which :c1 :pos c1} {:idx i :which :c2 :pos c2}])))
                      (range (count nodes)))]
    (when (seq cands)
      (let [d2 (fn [h] (let [[hx hy] (:pos h)]
                         (+ (* (- hx px) (- hx px)) (* (- hy py) (- hy py)))))
            best (apply min-key d2 cands)]
        (when (<= (d2 best) (* handle-snap handle-snap))
          (select-keys best [:idx :which]))))))

(defn- nearest-bez-handle-screen
  "The 3D bezier control handle (c1/c2 of any node) whose screen projection is closest
   to the pointer, within node-px-snap, as {:idx i :which :c1/:c2}, or nil. Screen-space
   so a handle at any depth is grabbable, like the nodes."
  [s ^js e]
  (let [nodes (:nodes s) mx (.-clientX e) my (.-clientY e)
        cands (mapcat (fn [i]
                        (when-let [{:keys [c1 c2]} (:bez (nth nodes i))]
                          (keep (fn [[which p]]
                                  (when-let [[sx sy] (viewport/world->screen p)]
                                    {:idx i :which which
                                     :d2 (+ (* (- sx mx) (- sx mx)) (* (- sy my) (- sy my)))}))
                                [[:c1 c1] [:c2 c2]])))
                      (range (count nodes)))]
    (when (seq cands)
      (let [best (apply min-key :d2 cands)]
        (when (<= (:d2 best) (* node-px-snap node-px-snap))
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

(defn- shift-grab?
  "Shift (only) held in 3D — used to start an axis-locked node drag. Other
   modifiers fall through to the camera."
  [^js e]
  (and (three-d? @session) (.-shiftKey e)
       (not (.-altKey e)) (not (.-ctrlKey e)) (not (.-metaKey e))))

(defn- handle-only-grab?
  "Alt (only) held in 2D — grab a bezier control handle even when it sits on top of
   its node (a node otherwise wins the hit-test). Lets you pick a control point on a
   short segment where it overlaps its node. With no handle nearby it's a no-op (the
   camera still orbits)."
  [^js e]
  (and (not (three-d? @session)) (.-altKey e)
       (not (.-shiftKey e)) (not (.-ctrlKey e)) (not (.-metaKey e))))

(defn- on-pointer-down [^js e]
  (when (and (:entered? @session) (or (plain-click? e) (shift-grab? e) (handle-only-grab? e)))
    (let [s @session
          basis (active-basis s)
          w (click-plane-point e s)
          p2 (when w (world->plane basis w))
          two-d? (not (three-d? s))
          ;; bezier control handles: 2D in plane units, 3D in screen space (found even
          ;; under Shift, which grabs the handle in length-only mode — see move).
          handle (if two-d?
                   (when p2 (nearest-handle (:nodes s) p2))
                   (nearest-bez-handle-screen s e))
          ;; 3D grabs in screen space (pixels); 2D in plane units.
          [n-idx n-d2] (if two-d?
                         (when p2 (nearest-node-d2 s p2))
                         (nearest-node-screen s e))
          node-snap2 (if two-d? (* node-snap node-snap) (* node-px-snap node-px-snap))
          seg (when (and two-d? p2) (nearest-segment (:nodes s) p2))]
      (cond
        ;; Alt (2D): handle-only — grab the nearest control handle, ignoring nodes, so
        ;; a handle overlapping its node (short segment) is reachable. No handle nearby
        ;; → no-op (the camera still orbits, controls left enabled).
        (handle-only-grab? e)
        (when handle
          (swap! session assoc :dragging-handle handle)
          (viewport/set-controls-enabled! false))

        ;; Right on a node → grab it (wins over a nearby handle/segment, so moving a
        ;; node doesn't accidentally catch a control point). Shift+grab → axis-lock.
        (and n-idx (<= n-d2 node-snap2))
        (grab-node! n-idx)

        ;; A bezier control handle (when the click isn't on a node). Wins over the
        ;; camera even under Shift (Shift+drag = length-only). Undo push deferred to the
        ;; first drag move. In 3D record the handle's depth (drag plane) plus its anchor
        ;; node + direction, for the plane-constrained and length-only drags.
        handle
        (let [h3 (if (three-d? s)
                   (let [{:keys [idx which]} handle
                         anchor (node-pos (nth (:nodes s) (if (= which :c1) (dec idx) idx)))
                         hpos (get-in (:nodes s) [idx :bez which])
                         d (m/v- hpos anchor)]
                     (swap! session assoc :drag-anchor hpos)
                     (cond-> handle (> (m/magnitude d) 1e-9)
                             (assoc :anchor anchor :dir (m/normalize d))))
                   handle)]
          (swap! session assoc :dragging-handle h3)
          (viewport/set-controls-enabled! false))

        ;; Shift with no node/handle under the cursor → leave it to the camera.
        (not (plain-click? e))
        nil

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
          (let [{:keys [idx which anchor dir]} (:dragging-handle s)]
            (if (three-d? s)
              (if (and (.-shiftKey e) dir)
                ;; Shift: length-only — slide the handle along its fixed direction from
                ;; the anchor node (escapes the active-plane constraint).
                (let [len (max 0.5 (m/dot (m/v- w anchor) dir))]
                  (swap! session assoc-in [:nodes idx :bez which] (m/v+ anchor (m/v* dir len))))
                ;; plain: a free world point on the active plane (deproject target)
                (swap! session assoc-in [:nodes idx :bez which] w))
              (swap! session assoc-in [:nodes idx :bez which]
                     (world->plane (plane-basis (:pose s)) w)))
            ;; c1 → snap to the start node's tangent (unless cusp); c2 free but re-snaps
            ;; the next segment's c1 (its incoming tangent changed). 2D + 3D.
            (swap! session reconstrain!)
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
      (do (swap! session dissoc :dragging-handle :drag-pushed? :drag-anchor)
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
              ;; bezier (2D + 3D; no-op if the node isn't a bezier); plain arrows move
              ;; the node. (Ctrl/Cmd are reserved by macOS for spaces.)
              (cond
                (.-shiftKey e) (nudge-handle! :c1 axis sign)
                (.-altKey e)   (nudge-handle! :c2 axis sign)
                :else          (nudge! axis sign))))

        (#{"Delete"} key)
        (do (.preventDefault e) (.stopPropagation e) (delete-node!))

        ;; Insert a node at the midpoint of the segment entering the selected node
        ;; (2D and 3D). Match by .-key, by .-code (PC keyboards send code "Insert"
        ;; regardless of the produced key), and the Mac "Help" key; "i" is the alias.
        (or (#{"Insert" "Help" "i" "I"} key) (= (.-code e) "Insert"))
        (do (.preventDefault e) (.stopPropagation e) (split-segment!))

        ;; toggle the selected node's incoming segment straight ↔ bezier curve (2D + 3D)
        (#{"c" "C"} key)
        (do (.preventDefault e) (.stopPropagation e) (toggle-bezier!))

        ;; `t` (2D + 3D): both-ends-tangent bezier raccordo (smooth corner).
        (#{"t" "T"} key)
        (do (.preventDefault e) (.stopPropagation e) (toggle-tangent!))

        ;; Shift+A: make ALL segments smooth beziers (no cusps) (2D + 3D).
        (and (.-shiftKey e) (#{"a" "A"} key))
        (do (.preventDefault e) (.stopPropagation e) (smooth-all!))

        ;; Shift+X: the inverse — turn ALL segments back into straight lines (2D + 3D).
        (and (.-shiftKey e) (#{"x" "X"} key))
        (do (.preventDefault e) (.stopPropagation e) (linear-all!))

        ;; `a` (2D only): a true tangent arc-v (smooth start only; `a` means the planar
        ;; arc in 2D — 3D rounds with `t`'s bezier instead, see toggle-arc!).
        (and (not (three-d? @session)) (#{"a" "A"} key))
        (do (.preventDefault e) (.stopPropagation e) (toggle-arc!))

        ;; toggle the selected node smooth ↔ cusp (frees its outgoing handle) (2D + 3D)
        (#{"x" "X"} key)
        (do (.preventDefault e) (.stopPropagation e) (toggle-cusp!))

        ;; Shift+m toggles the mark billboard labels (they can occlude a node you're editing)
        (and (.-shiftKey e) (#{"m" "M"} key))
        (do (.preventDefault e) (.stopPropagation e) (toggle-labels!))

        ;; mark the selected node (named anchor) and focus the panel field to rename
        (#{"m" "M"} key)
        (do (.preventDefault e) (.stopPropagation e) (add-mark-quick!))

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
  (viewport/clear-preview!)
  (viewport/clear-labels!))

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
        {:keys [nodes dropped closed?]} (if (= mode :2d)
                                          ;; seed->nodes' own pose walk (dev-docs/brief-
                                          ;; recording-highlevel-lettura-2d.md) tracks the
                                          ;; real 3D frame, so it recovers correct node
                                          ;; positions on its own — no need to cross-
                                          ;; reference ensure-path-2d's trace anymore.
                                          (seed->nodes (project-2d-to-xy seed-path)
                                                       (boolean (:closed? seed-path)))
                                          ;; conform-rail-start-3d: a recovered seed can fold a
                                          ;; manual th/tv into its first waypoint (Fuori-scope
                                          ;; case in dev-docs/brief-rail-start-tangent.md) — fix
                                          ;; node 1 up before it's baked below (`live`) or stored.
                                          {:nodes (conform-rail-start-3d (seed->nodes-3d seed-path)) :dropped []})
        ;; The value returned to the SCRIPT (consumed by a surrounding
        ;; path-to-shape / embroid / loft) must be a fully-traceable path so the
        ;; script proceeds with the SAME result as confirming. In 2D the node
        ;; reconstruction (nodes->commands) carries HIGH-LEVEL :bezier-to commands in
        ;; the path-2d frame — only the path-2d macro can re-tessellate those, so
        ;; the waypoint tracers (ensure-path-2d) would drop them (→ "< 2 waypoints"
        ;; in any 2D consumer). The seed IS that tessellated path-2d, so return it
        ;; directly. (Interactive edits don't flow through here: live-reeval splices
        ;; current-code = a (path-2d …) rewrite, so this only feeds the initial,
        ;; unedited eval where nodes == seed.)
        live (fn [] (if (= mode :3d)
                      (turtle/with-micro-commands {:type :path :commands (nodes->commands-3d nodes)})
                      seed-path))]
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
                         :closed?      (boolean closed?)
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
