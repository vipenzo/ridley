(ns ridley.editor.edit-image-board
  "Interactive editor for an `image-board` (a reference photo on a preserve-position
   rect, traced later with edit-path-2d). Mirrors the edit-path-2d modal scaffold:
   a `(edit-image-board …)` marker in the buffer opens a session; on OK it is
   rewritten to `(image-board \"path\" scale [imx imy] [orx ory] [w h])`.

   Phase 1 — the calibration win:
   - the image is stamped live at the turtle (wrap the form in `(stamp …)`);
   - Shift+Click two points on a feature draws a ruler ANCHORED to the image
     (stored as scale-independent image fractions), and entering the feature's real
     length in the panel solves `scale` so the ruler reads that length;
   - the other params (offset, position, rect) are editable as numbers in the panel.

   Phase 2 (later): graphical handles — drag the rect corners / body / image, and a
   creation-pose crosshair, replacing the numeric fields."
  (:require [ridley.editor.state :as state]
            [ridley.editor.codemirror :as cm]
            [ridley.editor.modal-evaluator :as modal]
            [ridley.math :as m]
            [ridley.turtle.shape :as shape]
            [ridley.viewport.core :as viewport]
            [ridley.export.stl :as stl]))

(declare confirm! cancel! live-reeval! throttled-reeval! update-panel! render!
         create-panel! on-keydown on-pointer-down on-pointer-move on-pointer-up
         apply-expected! autofit-aspect! remove-pointer-handlers!)

(def ^:private marker-prefix "(edit-image-board")
(def ^:private ruler-color 0xffdd00)  ; calibration ruler — yellow
(def ^:private rect-color  0x33ddff)  ; crop rect + corner handles — cyan
(def ^:private cross-color 0xffffff)  ; turtle / creation-pose crosshair — white

(defonce ^:private session (atom nil))

;; ============================================================
;; Numeric formatting + plane math (image lies on the turtle plane)
;; ============================================================

(defn- fmt-num [x]
  (let [r (/ (js/Math.round (* (double x) 1000)) 1000)]
    (if (= r (js/Math.floor r)) (str (long r)) (str r))))

(defn- basis
  "World-group-local basis {:origin :px :py} of the stamp plane through the turtle
   pose: px = right (heading × up), py = up — the same frame shape stamps embed in."
  [s]
  (let [{:keys [position heading up]} (:pose s)]
    {:origin position :px (m/normalize (m/cross heading up)) :py up}))

(defn- world->plane [{:keys [origin px py]} w]
  (let [d (m/v- w origin)] [(m/dot d px) (m/dot d py)]))

(defn- plane->world [{:keys [origin px py]} [a b]]
  (m/v+ origin (m/v+ (m/v* px a) (m/v* py b))))

(defn- image-offset
  "Lower-left of the image in shape coords (= board coords): [orx+imx, ory+imy]."
  [{:keys [orx ory imx imy]}]
  [(+ orx imx) (+ ory imy)])

(defn- plane->frac
  "Shape-coord [a b] → image fraction [u v] (in units of `scale` from the image
   corner). Scale-independent, so a fraction survives a scale change."
  [s [a b]]
  (let [[ox oy] (image-offset s) sc (:scale s)]
    [(/ (- a ox) sc) (/ (- b oy) sc)]))

(defn- frac->plane
  "Image fraction [u v] → shape-coord [a b] at the CURRENT scale/offset."
  [s [u v]]
  (let [[ox oy] (image-offset s) sc (:scale s)]
    [(+ ox (* u sc)) (+ oy (* v sc))]))

(defn- frac->world
  "Image fraction [u v] → world point at the CURRENT scale/offset."
  [s uv]
  (plane->world (basis s) (frac->plane s uv)))

(defn- ruler-norm-dist
  "Distance between the two anchors in fraction space (× scale = world distance)."
  [{:keys [a b]}]
  (let [du (- (first b) (first a)) dv (- (second b) (second a))]
    (js/Math.sqrt (+ (* du du) (* dv dv)))))

;; ============================================================
;; Rect handles (shape-coord geometry; the rect is the crop window)
;; ============================================================

(defn- corners
  "The four rect corners in shape coords, keyed by which edges they join."
  [{:keys [orx ory w h]}]
  {:ll [orx ory] :lr [(+ orx w) ory] :ul [orx (+ ory h)] :ur [(+ orx w) (+ ory h)]})

(defn- handle-size [{:keys [w h]}] (* 0.045 (max 1 w h)))

(defn- plane-pt
  "Shape-coord [a b] where the pointer ray meets the turtle/stamp plane, or nil."
  [s ^js e]
  (let [pose (:pose s)]
    (when-let [w (viewport/raycast-plane-point e (:position pose) (:heading pose))]
      (world->plane (basis s) w))))

(defn- grab-corner
  "Key of the rect corner within grab range of [a b], or nil (nearest wins)."
  [s [a b]]
  (let [snap (* 2.2 (handle-size s))]
    (->> (corners s)
         (keep (fn [[k [cx cy]]]
                 (let [d (js/Math.hypot (- a cx) (- b cy))]
                   (when (<= d snap) [k d]))))
         (sort-by second)
         ffirst)))

(defn- inside-rect? [{:keys [orx ory w h]} [a b]]
  (and (<= orx a (+ orx w)) (<= ory b (+ ory h))))

(defn- grab-ruler
  "Which ruler endpoint (:a / :b) is within grab range of [a b], nearest wins, or nil."
  [s [a b]]
  (when-let [r (:ruler s)]
    (let [snap (* 2.4 (handle-size s))
          [ax ay] (frac->plane s (:a r)) [bx by] (frac->plane s (:b r))
          da (js/Math.hypot (- a ax) (- b ay))
          db (js/Math.hypot (- a bx) (- b by))]
      (cond (and (<= da snap) (<= da db)) :a
            (<= db snap)                  :b))))

(defn- apply-resize
  "swap!-fn: move the grabbed corner to [a b], keeping the opposite corner fixed.
   The image's absolute position is held (crop window resizes over a stationary
   photo) by compensating imx/imy for any change in orx/ory."
  [{:keys [orx ory w h imx imy] :as s} corner [a b]]
  (let [x1 (+ orx w) y1 (+ ory h)
        [nx0 nx1] (case corner
                    (:ll :ul) [(min a (- x1 1)) x1]      ; left edge moves
                    (:lr :ur) [orx (max a (+ orx 1))])   ; right edge moves
        [ny0 ny1] (case corner
                    (:ll :lr) [(min b (- y1 1)) y1]      ; bottom edge moves
                    (:ul :ur) [ory (max b (+ ory 1))])]  ; top edge moves
    (assoc s :orx nx0 :ory ny0 :w (- nx1 nx0) :h (- ny1 ny0)
           :imx (+ imx (- orx nx0)) :imy (+ imy (- ory ny0)))))

;; ============================================================
;; Source code (bake) + live re-eval
;; ============================================================

(defn- current-code []
  (let [{:keys [path scale imx imy orx ory w h]} @session]
    (str "(image-board " (pr-str path) " " (fmt-num scale)
         " [" (fmt-num imx) " " (fmt-num imy) "]"
         " [" (fmt-num orx) " " (fmt-num ory) "]"
         " [" (fmt-num w) " " (fmt-num h) "])")))

(defn- find-marker []
  (modal/find-form-bounds (cm/get-value) marker-prefix))

(defn- build-modified-script []
  (let [[from to] (find-marker)]
    (modal/splice-source (cm/get-value) from to (current-code))))

(defn- live-reeval!
  "Re-run the buffer with the marker replaced by the current (image-board …), so the
   stamped image re-renders at the new params, then redraw the ruler overlay."
  []
  (modal/reeval-script! build-modified-script "edit-image-board eval error:" false)
  (render!))

(defn- throttled-reeval!
  "Re-stamp during a drag at ~20fps so the photo tracks the cyan overlay, with a
   trailing eval so the final frame isn't skipped. render! keeps the overlay itself
   live on every move; this only paces the heavier full-script re-stamp."
  []
  (let [now (js/Date.now)
        last (:last-eval @session 0)]
    (if (> (- now last) 45)
      (do (swap! session assoc :last-eval now) (live-reeval!))
      (do (when-let [t (:eval-timeout @session)] (js/clearTimeout t))
          (swap! session assoc :eval-timeout
                 (js/setTimeout (fn [] (swap! session assoc :last-eval (js/Date.now))
                                  (live-reeval!)) 60))))))

;; ============================================================
;; Viewport overlay (ruler + endpoints + distance label)
;; ============================================================

(defn- cross-segs
  "An ✕ centred on world point `cw`, lying in the plane, so the exact marked point
   reads precisely. `arm` is the half-length. WebGL ignores line width, so each arm
   is drawn as three close parallel segments to read as a thicker stroke."
  [{:keys [px py]} cw arm color]
  (let [s  (/ arm 1.4142)
        d1 (m/v* (m/v+ px py) s)                 ; one diagonal arm
        d2 (m/v* (m/v- px py) s)                 ; the other diagonal arm
        t  (* 0.02 arm)                          ; tiny offset → parallels merge into a ~thick stroke
        o1 (m/v* (m/v- px py) (/ t 1.4142))      ; ⊥ to d1, length t
        o2 (m/v* (m/v+ px py) (/ t 1.4142))      ; ⊥ to d2, length t
        arm-segs (fn [d o]
                   (for [k [-2 -1 0 1 2]]
                     (let [c (m/v+ cw (m/v* o k))]
                       {:from (m/v- c d) :to (m/v+ c d) :color color})))]
    (concat (arm-segs d1 o1) (arm-segs d2 o2))))

(defn- render! []
  (when-let [s @session]
    (let [bs (basis s)
          ->w (fn [pt] (plane->world bs pt))
          cs (corners s)
          ;; crop rect outline (ll → lr → ur → ul → ll)
          ring [(:ll cs) (:lr cs) (:ur cs) (:ul cs)]
          rect-lines (mapv (fn [p q] {:from (->w p) :to (->w q) :color rect-color})
                           ring (conj (vec (rest ring)) (first ring)))
          ;; visual dot is small; the grab area (grab-corner) stays generous
          corner-dots (mapv (fn [[_ p]] {:pos (->w p) :radius (* 0.4 (handle-size s))
                                         :color rect-color}) cs)
          ;; turtle / creation-pose crosshair at shape [0 0]
          ch (* 1.4 (handle-size s))
          cross [{:from (->w [(- ch) 0]) :to (->w [ch 0]) :color cross-color}
                 {:from (->w [0 (- ch)]) :to (->w [0 ch]) :color cross-color}]
          ;; calibration ruler — endpoints drawn as ✕ crosses so the exact picked
          ;; point reads precisely (not a fat dot you have to centre by eye)
          r (:ruler s)
          ruler-pts (when r [(frac->world s (:a r)) (frac->world s (:b r))])
          xarm (* 0.55 (handle-size s))
          ruler-crosses (mapcat #(cross-segs bs % xarm ruler-color) ruler-pts)
          ruler-line (when r [{:from (frac->world s (:a r)) :to (frac->world s (:b r))
                               :color ruler-color}])]
      (viewport/show-preview!
       [{:type :lines :data (vec (concat rect-lines cross (or ruler-line []) ruler-crosses))
         :on-top true}
        {:type :dots :data (vec corner-dots)}])
      (if r
        (let [wa (frac->world s (:a r)) wb (frac->world s (:b r))
              mid (m/v* (m/v+ wa wb) 0.5)
              dist (* (:scale s) (ruler-norm-dist r))]
          (viewport/set-labels! [{:text (fmt-num dist) :position mid :color ruler-color}]))
        (viewport/clear-labels!)))))

;; ============================================================
;; Calibration
;; ============================================================

(defn- apply-expected!
  "Set scale so the anchored ruler measures `expected` world units."
  [expected]
  (let [s @session r (:ruler s) nd (and r (ruler-norm-dist r))]
    (when (and nd (pos? nd) (number? expected) (pos? expected))
      (swap! session assoc :scale (/ expected nd) :expected expected)
      (live-reeval!)
      (update-panel!))))

;; ============================================================
;; Panel
;; ============================================================

(defn- num-field [label cls val step]
  (str "<label>" label " <input class='" cls "' type='number' step='" step
       "' value='" (fmt-num val) "' style='width:62px'></label>"))

(defn- wire-num! [panel cls k]
  (when-let [^js el (.querySelector panel cls)]
    (.addEventListener el "change"
                       (fn [^js e]
                         (let [v (js/parseFloat (.. e -target -value))]
                           (when (js/isFinite v)
                             (swap! session assoc k v)
                             (live-reeval!)
                             (update-panel!)))))))

(defn update-panel! []
  (when-let [panel (:panel-el @session)]
    (let [s @session
          focused (.-activeElement js/document)]
      (doseq [[cls k] [[".eib-scale" :scale] [".eib-imx" :imx] [".eib-imy" :imy]
                       [".eib-orx" :orx] [".eib-ory" :ory] [".eib-w" :w] [".eib-h" :h]]]
        (when-let [^js el (.querySelector panel cls)]
          (when (not= el focused)
            (set! (.-value el) (fmt-num (get s k))))))
      (when-let [^js el (.querySelector panel ".eib-measured")]
        (set! (.-textContent el)
              (if-let [r (:ruler s)]
                (str "ruler measures: " (fmt-num (* (:scale s) (ruler-norm-dist r))) " units")
                ""))))))

(defn- create-panel! []
  (let [s @session
        panel (.createElement js/document "div")]
    (set! (.-id panel) "edit-image-board-panel")
    (set! (.-innerHTML panel)
          (str "<div class='pilot-header'>edit-image-board"
               "<span class='pilot-mode-badge'>image</span></div>"
               "<div class='ep-section'><span class='ep-section-label'>Scale</span>"
               (num-field "scale" "eib-scale" (:scale s) "1")
               "<label>feature len <input class='eib-expected' type='number' step='0.1' "
               "style='width:70px' placeholder='real'></label>"
               "<button class='pilot-btn eib-apply' type='button'>set scale</button>"
               "</div>"
               "<div class='eib-measured' style='opacity:.8;margin:2px 0'></div>"
               "<div class='ep-section'><span class='ep-section-label'>Image</span>"
               (num-field "imx" "eib-imx" (:imx s) "1")
               (num-field "imy" "eib-imy" (:imy s) "1")
               "</div>"
               "<div class='ep-section'><span class='ep-section-label'>Rect</span>"
               (num-field "orx" "eib-orx" (:orx s) "1")
               (num-field "ory" "eib-ory" (:ory s) "1")
               (num-field "w" "eib-w" (:w s) "1")
               (num-field "h" "eib-h" (:h s) "1")
               "</div>"
               "<div class='pilot-commands modal-help'>"
               "drag the yellow ✕ ends onto a known feature, type its real length, "
               "press 'set scale' · drag rect: move (orx/ory) · Alt+drag: pan image "
               "(imx/imy) · drag corner: crop (w/h) · white ✛ = creation pose · "
               "Enter: OK · Esc: cancel</div>"
               "<div class='pilot-buttons'>"
               "<button class='pilot-btn pilot-btn-ok eib-ok'>OK</button>"
               "<button class='pilot-btn pilot-btn-cancel eib-cancel'>Cancel</button>"
               "</div>"))
    (.addEventListener (.querySelector panel ".eib-ok") "click" (fn [_] (confirm!)))
    (.addEventListener (.querySelector panel ".eib-cancel") "click" (fn [_] (cancel!)))
    (wire-num! panel ".eib-scale" :scale)
    (wire-num! panel ".eib-imx" :imx)
    (wire-num! panel ".eib-imy" :imy)
    (wire-num! panel ".eib-orx" :orx)
    (wire-num! panel ".eib-ory" :ory)
    (wire-num! panel ".eib-w" :w)
    (wire-num! panel ".eib-h" :h)
    ;; Scale recompute is explicit (button), never automatic on the field/drag, so the
    ;; image only rescales when you ask — dragging the ✕ ends never moves it under you.
    (when-let [^js apply-btn (.querySelector panel ".eib-apply")]
      (.addEventListener apply-btn "click"
                         (fn [_]
                           (when-let [^js exp (.querySelector panel ".eib-expected")]
                             (apply-expected! (js/parseFloat (.-value exp)))))))
    (modal/mount-panel! panel)
    panel))

;; ============================================================
;; Pointer + keyboard
;; ============================================================

(defn- input-el? [^js el]
  (and el (boolean (#{"INPUT" "TEXTAREA"} (.-tagName el)))))

(defn- on-pointer-down [^js e]
  ;; Grab a handle. Ruler endpoint → move it (yellow ✕, no re-scale); corner →
  ;; resize crop (w/h); inside + Alt → pan image (imx/imy); inside (plain) → move
  ;; rect (orx/ory). Outside → camera (no grab). Scale recompute is a panel button,
  ;; so dragging an endpoint never rescales the image mid-drag.
  (when-let [p (plane-pt @session e)]
    (let [s @session
          rend   (grab-ruler s p)
          corner (when-not rend (grab-corner s p))
          mode (cond rend                                    :ruler
                     corner                                  :resize
                     (and (inside-rect? s p) (.-altKey e))   :pan
                     (inside-rect? s p)                      :move)]
      (when mode
        (.preventDefault e)
        (viewport/set-controls-enabled! false)
        (swap! session assoc :drag
               {:mode mode :end rend :corner corner :p0 p
                :start (select-keys s [:orx :ory :w :h :imx :imy])})))))

(defn- on-pointer-move [^js e]
  (when-let [drag (:drag @session)]
    (when-let [[a b :as p] (plane-pt @session e)]
      (if (= :ruler (:mode drag))
        ;; Moving a ruler endpoint: update its image fraction. No re-stamp — the
        ;; image params are unchanged, only the measured length updates.
        (do (swap! session update :ruler assoc (:end drag) (plane->frac @session p))
            (render!))
        (let [[a0 b0] (:p0 drag) dx (- a a0) dy (- b b0)
              {:keys [orx ory imx imy]} (:start drag)]
          (case (:mode drag)
            :move   (swap! session assoc :orx (+ orx dx) :ory (+ ory dy))
            :pan    (swap! session assoc :imx (+ imx dx) :imy (+ imy dy))
            :resize (swap! session #(apply-resize (merge % (:start drag)) (:corner drag) [a b])))
          (render!)
          (throttled-reeval!))))))

(defn- on-pointer-up [^js _e]
  (viewport/set-controls-enabled! true)
  (when-let [drag (:drag @session)]
    (swap! session dissoc :drag)
    ;; A ruler-endpoint drag changes no image params → no re-stamp needed.
    (when (not= :ruler (:mode drag)) (live-reeval!))
    (update-panel!)))

(defn- on-keydown [^js e]
  (let [k (.-key e)]
    (cond
      (= k "Enter")
      (do (.preventDefault e) (.stopPropagation e)
          (when-let [^js ae (.-activeElement js/document)]
            (when (input-el? ae) (.blur ae)))   ; commit a focused field first
          (confirm!))
      (= k "Escape")
      (do (.preventDefault e) (.stopPropagation e) (cancel!)))))

;; ============================================================
;; Lifecycle
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
  (viewport/set-controls-enabled! true)
  (viewport/lock-interaction! false)
  (viewport/clear-preview!)
  (viewport/clear-labels!))

(defn confirm! []
  (when @session
    (let [[from to] (find-marker)
          code (current-code)]
      (when from
        (modal/replace-source! from to code)
        (state/capture-println (str "edit-image-board: " code)))
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
;; Entry (two-phase, mirrors edit-path)
;; ============================================================

(defn ^:export active? [] (some? @session))

(defn- clear-orphan! []
  (let [s @session
        live? (and s (:entered? s) (some-> (:panel-el s) .-parentNode))]
    (when (and (= :edit-image-board @state/interactive-mode) (not live?))
      (cleanup!)
      (reset! session nil)
      (modal/release!))))

(defn ^:export request!
  "Called by the (edit-image-board …) macro. Returns a live `image-board` shape (so
   a surrounding (stamp …) renders the image during the eval) and, in script mode,
   opens the editor session."
  [path & more]
  (let [[scale imxy orxy wh] more
        scale (or scale 100)
        [imx imy] (or imxy [0 0])
        [w h] (or wh [scale scale])
        [orx ory] (or orxy [(- (/ w 2)) (- (/ h 2))])
        board (shape/image-board path scale [imx imy] [orx ory] [w h])]
    (cond
      (modal/consume-skip!)
      board

      (not= :definitions @state/eval-source-var)
      (do (state/capture-println
           "edit-image-board: open it from the definitions panel (Cmd+Enter), not the REPL")
          board)

      :else
      (do
        (clear-orphan!)
        (when (nil? (find-marker))
          (throw (js/Error. (str "edit-image-board: cannot find '" marker-prefix " …)' in editor"))))
        (modal/claim! :edit-image-board)
        (reset! session {:path path :scale scale :imx imx :imy imy
                         :orx orx :ory ory :w w :h h
                         :auto-rect? (nil? wh)
                         ;; ruler open by default, horizontal across the image centre
                         ;; (image fractions). Drag the ✕ ends onto a feature, then
                         ;; 'set scale' recomputes from the typed real length.
                         :ruler {:a [0.25 0.5] :b [0.75 0.5]} :expected nil
                         :pose (state/get-turtle-pose)
                         :entered? false})
        board))))

(defn requested? []
  (and (some? @session) (not (:entered? @session))))

(defn- autofit-aspect!
  "First open without an explicit rect: read the image aspect and size the rect to
   the whole image (turtle-centered), so the full photo shows. Async, best-effort."
  []
  (let [path (:path @session)]
    (-> (stl/desktop-read-file-blob path)
        (.then (fn [blob]
                 (let [url (js/URL.createObjectURL blob)
                       img (js/Image.)]
                   (set! (.-onload img)
                         (fn []
                           (let [aspect (/ (.-naturalWidth img) (max 1 (.-naturalHeight img)))]
                             (js/URL.revokeObjectURL url)
                             (when (:auto-rect? @session)
                               (let [sc (:scale @session)
                                     w sc h (/ sc aspect)]
                                 (swap! session assoc :w w :h h :imx 0 :imy 0
                                        :orx (- (/ w 2)) :ory (- (/ h 2)))
                                 (live-reeval!)
                                 (update-panel!))))))
                   (set! (.-src img) url))))
        (.catch (fn [_] nil)))))

(defn enter! []
  (when (requested?)
    (swap! session assoc :entered? true)
    (let [handler on-keydown]
      (swap! session assoc :key-handler handler)
      (modal/install-keydown! handler))
    (when-let [^js canvas (viewport/get-canvas)]
      (.addEventListener canvas "pointerdown" on-pointer-down)
      (.addEventListener canvas "pointermove" on-pointer-move)
      (.addEventListener canvas "pointerup" on-pointer-up)
      (swap! session assoc :pointer {:canvas canvas :down on-pointer-down
                                     :move on-pointer-move :up on-pointer-up}))
    ;; Suppress the built-in Shift+Click mesh ruler — we own Shift+Click here.
    (viewport/lock-interaction! true)
    (let [panel (create-panel!)]
      (swap! session assoc :panel-el panel))
    (update-panel!)
    (live-reeval!)
    (when (:auto-rect? @session) (autofit-aspect!))
    (state/capture-println
     (str "edit-image-board: wrap the form in (stamp …) to see the image. "
          "Drag the yellow ✕ ends onto a known feature, type its length, press "
          "'set scale' · drag rect to move · Alt+drag to pan · drag a corner to crop. "
          "Enter=OK, Esc=cancel"))))

(defn- force-close! []
  (when @session
    (cleanup!)
    (modal/release!)
    (reset! session nil)))

(modal/register-kind! :edit-image-board
                      {:requested? requested?
                       :enter!     enter!
                       :active?    active?
                       :cancel!    cancel!
                       :close!     force-close!})
