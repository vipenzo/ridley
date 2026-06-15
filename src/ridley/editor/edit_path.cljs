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
            [ridley.viewport.core :as viewport]
            [clojure.string :as str]))

(declare confirm! cancel! render! update-panel! refresh-preview!)

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
(def ^:private node-radius     1.25)  ; filled dot radius (plane units)
(def ^:private node-radius-sel 2.0)   ; selected dot radius

;; Dim the reference image to this opacity while tracing, so the overlay reads.
(def ^:private edit-dim 0.4)
;; Right on a node (within this) a click always grabs it, even on a short segment.
(def ^:private node-snap 6)
;; Looser node-grab radius, used only when the click isn't on a segment.
(def ^:private hit-threshold 12)
;; How close a click must be to a segment to insert a node there (split).
;; Generous: an imprecise click near the path still splits instead of missing.
(def ^:private seg-threshold 16)

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
          (and keep? (dir-eq? dir (right-of ch))) [[{:cmd :rt :args [dist]}] ch]
          (and keep? (dir-eq? dir (left-of ch)))  [[{:cmd :lt :args [dist]}] ch]
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
                to (node-pos node)
                [cmds ch1] (segment-cmds ch from to (:heading node))]
            (recur ch1 to (rest remaining)
                   (-> out (into cmds) (into (:tail node))))))))))

(defn- nodes->path
  "A path value from the current nodes — returned by request! so the downstream
   (path-to-shape …) runs even before the first edit."
  [nodes]
  {:type :path :commands (nodes->commands nodes)})

(defn- nodes->code
  "The replacement source: a complete (path (move-to …) (th …)(f …) …) form,
   carrying preserved marks / side-trips and orientation. The (edit-path …) marker
   is a stand-in for this, so confirming swaps it in."
  [nodes]
  (str "(path"
       (apply str (map #(str " " (cmd->code %)) (nodes->commands nodes)))
       ")"))

(defn- seed->nodes
  "Parse the seed path body into {:nodes [{:pos :heading :tail} …] :dropped […]}.
   f/th/set-heading drive positions and heading; rt/lt are in-plane strafes
   (heading kept); mark/side-trip attach to the current node's :tail; a leading
   move-to anchors the start. The last node keeps its final (exit) heading. Throws
   on a non-leading move-to. Unsupported commands (arcs, beziers, u/tv/tr, …) are
   dropped and reported."
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
                     (:mark :side-trip)
                     (update-in st [:nodes (dec (count nodes)) :tail] conj cmd)
                     (update st :dropped conj (:cmd cmd))))
                 {:pos [sx sy] :heading [1 0]
                  :nodes [{:pos [sx sy] :heading nil :tail []}] :dropped []}
                 cmds)
            ;; the last node keeps the final (exit) heading — captures trailing turns
            nodes (let [ns (:nodes res)]
                    (assoc-in ns [(dec (count ns)) :heading] (:heading res)))]
        {:nodes nodes :dropped (vec (distinct (:dropped res)))}))))

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

;; ============================================================
;; Ephemeral geometry
;; ============================================================

(defn- render!
  "Redraw the ephemeral polyline (lines) + filled node dots from the session state."
  []
  (when-let [{:keys [nodes selected pose]} @session]
    (let [basis (plane-basis pose)
          normal (m/cross (:px basis) (:py basis))   ; working-plane normal (for the start ring)
          pts (mapv #(plane->world basis (node-pos %)) nodes)
          n (count pts)
          edges (when (>= n 2)
                  (mapv (fn [a b] {:from a :to b :color line-color})
                        pts (rest pts)))
          closing (when (>= n 3)
                    [{:from (last pts) :to (first pts) :color close-color}])
          segs (vec (concat edges closing))
          dots (mapv (fn [i pw]
                       (let [marked? (seq (:tail (nth nodes i)))
                             start?  (zero? i)
                             exit?   (= i (dec n))]
                         (cond-> {:pos pw
                                  ;; Selection is shown by SIZE only, so the semantic
                                  ;; colour stays visible while dragging.
                                  :radius (if (= i selected) node-radius-sel node-radius)
                                  ;; mark (green) > endpoints start/exit (orange) >
                                  ;; selected (yellow) > plain (blue)
                                  :color  (cond marked?            mark-color
                                                (or start? exit?)  exit-color
                                                (= i selected)     sel-color
                                                :else              node-color)}
                           ;; the start node is a ring (a dot with a hole) so it's
                           ;; distinct from the solid exit dot
                           start? (assoc :ring true :normal normal))))
                     (range n) pts)]
      ;; :on-top keeps the overlay visible over the (dimmed) image and the live
      ;; extruded result; dots are filled spheres so individual nodes read clearly.
      (viewport/show-preview! [{:type :lines :data segs :on-top true}
                               {:type :dots :data dots}]))))

;; ============================================================
;; Live re-eval (downstream geometry)
;; ============================================================

(defn- current-code [] (nodes->code (:nodes @session)))

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
              (str "nodes: " (count (:nodes s)) " · sel: " (:selected s))))
      (when-let [el (.querySelector panel ".ep-step")]
        (let [buf (:digit-buffer s)]
          (set! (.-textContent el)
                (if (seq buf) (str buf "_") (str (:step s) "mm"))))))))

(defn- create-panel! []
  (let [panel (.createElement js/document "div")]
    (set! (.-id panel) "edit-path-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>edit-path"
               "<span class='pilot-mode-badge'>polyline</span></div>"
               "<div class='pilot-controls'>"
               "<span class='ep-info'>nodes: 0 · sel: 0</span>"
               "<span>Step: <span class='ep-step'>5mm</span></span>"
               "</div>"
               "<div class='pilot-commands'>"
               "click: add · drag node: move · Tab: next · ←→↑↓: nudge · "
               "Del: delete · Enter: OK · Esc: cancel"
               "</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-ok ep-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel ep-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".ep-ok") "click" (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".ep-cancel") "click" (fn [_] (cancel!)))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Editing operations
;; ============================================================

(defn- append-node!
  "Append a node at 2D plane coord `pos` at the end of the path and select it."
  [pos]
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

(defn- notable?
  "A node whose orientation is preserved on move: it carries a mark/side-trip, or
   it is the last (exit) node."
  [nodes idx]
  (or (seq (:tail (nth nodes idx)))
      (= idx (dec (count nodes)))))

(defn- node-moved!
  "After moving node `idx`, drop its explicit heading unless it's notable, so a
   plain corner follows the new geometry while marks / the exit node keep theirs."
  [idx]
  (when-not (notable? (:nodes @session) idx)
    (swap! session assoc-in [:nodes idx :heading] nil)))

(defn- nudge!
  "Move the selected node by sign·step along axis 0=x / 1=y."
  [axis sign]
  (let [s @session i (:selected s) step (:step s)]
    (when (and (seq (:nodes s)) (< i (count (:nodes s))))
      (swap! session update-in [:nodes i :pos axis] + (* sign step))
      (node-moved! i)
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
        (let [nodes' (vec (concat (take i nodes) (drop (inc i) nodes)))]
          (swap! session assoc :nodes nodes'
                 :selected (max 0 (min (dec (count nodes')) i)))
          (refresh-preview!)
          (update-panel!))))))

;; ============================================================
;; Mouse: click empty → add, click/drag a node → select/move
;; ============================================================
;; Orbit is preserved: grabbing a node disables the controls for the drag (and
;; re-enables on release); a drag on empty space still orbits, and a plain click
;; on empty space (no movement) adds a node.

(defn- nearest-node-d2
  "[idx d2] of the node closest to plane point p, or nil if there are none."
  [nodes [px py]]
  (when (seq nodes)
    (let [d2 (fn [i] (let [[nx ny] (node-pos (nth nodes i))
                           dx (- nx px) dy (- ny py)]
                       (+ (* dx dx) (* dy dy))))
          idx (apply min-key d2 (range (count nodes)))]
      [idx (d2 idx)])))

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

(defn- plain-click? [^js e]
  (and (not (.-altKey e)) (not (.-shiftKey e))
       (not (.-ctrlKey e)) (not (.-metaKey e))))

(defn- click-plane-point
  "World point where the pointer ray meets the working plane (no geometry needed)."
  [^js e s]
  (let [pose (:pose s)]
    (viewport/raycast-plane-point e (:position pose) (:heading pose))))

(defn- grab-node! [idx]
  (swap! session assoc :selected idx :dragging idx)
  (viewport/set-controls-enabled! false)
  (render!) (update-panel!))

(defn- on-pointer-down [^js e]
  (when (and (:entered? @session) (plain-click? e))
    (let [s @session
          basis (plane-basis (:pose s))
          w (click-plane-point e s)
          p2 (when w (world->plane basis w))
          [n-idx n-d2] (when p2 (nearest-node-d2 (:nodes s) p2))
          seg (when p2 (nearest-segment (:nodes s) p2))]
      (cond
        ;; Right on a node → grab it (wins even on a short segment).
        (and n-idx (<= n-d2 (* node-snap node-snap)))
        (grab-node! n-idx)

        ;; On a segment → insert a node there (split), even between close nodes,
        ;; then drag it. (Prefers insert over the looser node-grab below.)
        seg
        (do (insert-node! (:index seg) (:point seg))
            (swap! session assoc :dragging (:index seg))
            (viewport/set-controls-enabled! false))

        ;; Looser node grab when the click isn't on a segment.
        (and n-idx (<= n-d2 (* hit-threshold hit-threshold)))
        (grab-node! n-idx)

        ;; Otherwise remember the press: a still click appends, a drag orbits.
        :else
        (swap! session assoc :down {:client [(.-clientX e) (.-clientY e)] :pt w})))))

(defn- on-pointer-move [^js e]
  (let [s @session]
    (when (and (:entered? s) (:dragging s))
      (when-let [w (click-plane-point e s)]
        (swap! session assoc-in [:nodes (:dragging s) :pos]
               (world->plane (plane-basis (:pose s)) w))
        (node-moved! (:dragging s))
        (refresh-preview!)))))

(defn- on-pointer-up [^js e]
  (let [s @session]
    (cond
      (:dragging s)
      (do (swap! session dissoc :dragging)
          (viewport/set-controls-enabled! true)
          (refresh-preview!) (update-panel!))

      (:down s)
      (let [[dx dy] (:client (:down s))
            moved (+ (js/Math.abs (- (.-clientX e) dx))
                     (js/Math.abs (- (.-clientY e) dy)))]
        ;; A click (barely moved) appends a node; a real drag was an orbit.
        (when (and (< moved 5) (:pt (:down s)))
          (append-node! (world->plane (plane-basis (:pose s)) (:pt (:down s)))))
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

(defn- on-keydown [e]
  (when (:entered? @session)
    (let [key (.-key e)
          digit (digit-key key)]
      (cond
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
              (nudge! axis sign)))

        (#{"Delete"} key)
        (do (.preventDefault e) (.stopPropagation e) (delete-node!))

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
  (let [{:keys [nodes dropped]} (seed->nodes seed-path)]
    (cond
      (modal/consume-skip!)
      (nodes->path nodes)

      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-path: open it from the definitions panel (Cmd+Enter), not the REPL")
          (nodes->path nodes))

      :else
      (do
        (clear-orphan!)
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-path: cannot find '" marker-prefix " …)' in editor"))))
        ;; f/th/set-heading/rt/lt/mark/side-trip are handled; arcs, beziers and the
        ;; out-of-plane moves (u/tv/tr) are not yet editable — warn that confirming
        ;; drops them and replaces with straight segments.
        (when (seq dropped)
          (state/capture-println
           (str "edit-path: WARNING — body contains " dropped
                " which this MVP cannot edit; confirming will replace them with "
                "straight segments.")))
        (modal/claim! :edit-path)
        (reset! session {:nodes        nodes
                         :selected     (max 0 (dec (count nodes)))
                         :step         5
                         :digit-buffer ""
                         :pose         (state/get-turtle-pose)
                         :entered?     false})
        (nodes->path nodes)))))

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
     (str "edit-path: click to add nodes, drag a node to move it, Tab cycles, "
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
