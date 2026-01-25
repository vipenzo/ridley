(ns ridley.scene.panel
  "3D text panels for the Ridley viewport.

   Panels are 3D objects that display text and always face the camera (billboard).
   They are registered and managed like meshes but NOT exported to STL.

   Usage:
   (register P1 (panel 40 60))      ; create panel at turtle position
   (out P1 \"Hello world\")          ; set text content
   (append P1 \"More text\")         ; append to content
   (clear P1)                        ; clear content
   (hide :P1)                        ; hide panel
   (show :P1)                        ; show panel")

;; ============================================================
;; Panel data structure
;; ============================================================

(defn make-panel
  "Create a panel data structure.
   Options:
     :font-size - text size in world units (default 3)
     :bg - background color as hex int (default 0x333333cc)
     :fg - foreground (text) color as hex int (default 0xffffff)
     :padding - padding in world units (default 2)
     :line-height - line height multiplier (default 1.4)"
  [width height position heading up & {:keys [font-size bg fg padding line-height]
                                        :or {font-size 3
                                             bg 0x333333cc
                                             fg 0xffffff
                                             padding 2
                                             line-height 1.4}}]
  {:type :panel
   :width width
   :height height
   :position position
   :heading heading
   :up up
   :content ""
   :style {:font-size font-size
           :bg bg
           :fg fg
           :padding padding
           :line-height line-height}
   ;; Three.js objects will be attached by viewport
   :three-mesh nil
   :canvas nil
   :texture nil})

(defn panel?
  "Check if x is a panel."
  [x]
  (and (map? x) (= :panel (:type x))))

(defn set-content
  "Set the panel's text content (replaces existing)."
  [panel text]
  (assoc panel :content (str text)))

(defn append-content
  "Append text to the panel's content."
  [panel text]
  (update panel :content str text))

(defn clear-content
  "Clear the panel's content."
  [panel]
  (assoc panel :content ""))

(defn get-content
  "Get the panel's current text content."
  [panel]
  (:content panel))

;; ============================================================
;; Color utilities
;; ============================================================

(defn- hex-to-rgba
  "Convert hex color (with optional alpha in high byte) to RGBA string.
   0xRRGGBB -> 'rgba(r, g, b, 1)'
   0xRRGGBBAA -> 'rgba(r, g, b, a)'"
  [hex]
  (let [has-alpha? (> hex 0xffffff)
        r (bit-and (bit-shift-right hex (if has-alpha? 24 16)) 0xff)
        g (bit-and (bit-shift-right hex (if has-alpha? 16 8)) 0xff)
        b (bit-and (bit-shift-right hex (if has-alpha? 8 0)) 0xff)
        a (if has-alpha?
            (/ (bit-and hex 0xff) 255.0)
            1.0)]
    (str "rgba(" r "," g "," b "," a ")")))

(defn- hex-to-rgb
  "Convert hex color to RGB string for canvas."
  [hex]
  (let [r (bit-and (bit-shift-right hex 16) 0xff)
        g (bit-and (bit-shift-right hex 8) 0xff)
        b (bit-and hex 0xff)]
    (str "rgb(" r "," g "," b ")")))

;; ============================================================
;; Canvas rendering
;; ============================================================

(def ^:private px-per-unit
  "Pixels per world unit for texture resolution."
  10)

(defn create-canvas
  "Create an OffscreenCanvas for the panel."
  [width height]
  (let [px-width (* width px-per-unit)
        px-height (* height px-per-unit)]
    (js/OffscreenCanvas. px-width px-height)))

(defn render-to-canvas
  "Render panel content to its canvas. Returns the canvas."
  [canvas panel]
  (let [{:keys [width height content style]} panel
        {:keys [font-size bg fg padding line-height]} style
        px-width (* width px-per-unit)
        px-height (* height px-per-unit)
        px-padding (* padding px-per-unit)
        px-font-size (* font-size px-per-unit 0.1)  ; Scale font for texture
        ctx (.getContext canvas "2d")]
    ;; Clear and draw background
    (.clearRect ctx 0 0 px-width px-height)
    (set! (.-fillStyle ctx) (hex-to-rgba bg))
    (.fillRect ctx 0 0 px-width px-height)
    ;; Draw border
    (set! (.-strokeStyle ctx) (hex-to-rgba (bit-or fg 0x40)))
    (set! (.-lineWidth ctx) 2)
    (.strokeRect ctx 1 1 (- px-width 2) (- px-height 2))
    ;; Setup text rendering
    (set! (.-fillStyle ctx) (hex-to-rgb fg))
    (set! (.-font ctx) (str px-font-size "px sans-serif"))
    (set! (.-textBaseline ctx) "top")
    ;; Word wrap and render text
    (let [max-width (- px-width (* 2 px-padding))
          lines (clojure.string/split content #"\n")
          line-h (* px-font-size line-height)]
      (loop [y px-padding
             remaining-lines lines]
        (when (and (seq remaining-lines) (< y (- px-height px-padding)))
          (let [line (first remaining-lines)
                ;; Simple word wrapping
                words (clojure.string/split line #" ")
                wrapped (reduce
                         (fn [{:keys [current-line lines]} word]
                           (let [test-line (if (empty? current-line)
                                             word
                                             (str current-line " " word))
                                 metrics (.measureText ctx test-line)]
                             (if (> (.-width metrics) max-width)
                               {:current-line word
                                :lines (conj lines current-line)}
                               {:current-line test-line
                                :lines lines})))
                         {:current-line "" :lines []}
                         words)
                final-lines (if (empty? (:current-line wrapped))
                              (:lines wrapped)
                              (conj (:lines wrapped) (:current-line wrapped)))]
            ;; Draw wrapped lines
            (doseq [[idx text] (map-indexed vector final-lines)]
              (let [line-y (+ y (* idx line-h))]
                (when (< line-y (- px-height px-padding))
                  (.fillText ctx text px-padding line-y))))
            (recur (+ y (* (count final-lines) line-h))
                   (rest remaining-lines))))))
    canvas))

(defn update-panel-texture!
  "Update the Three.js texture from the panel's canvas.
   Call after content changes."
  [panel]
  (when-let [texture (:texture panel)]
    (let [canvas (:canvas panel)]
      (render-to-canvas canvas panel)
      (set! (.-needsUpdate texture) true)))
  panel)
