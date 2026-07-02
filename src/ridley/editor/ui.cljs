(ns ridley.editor.ui
  "Shared DOM helpers for the modal editor panels (tweak, edit-bezier, pilot).
   Currently: a reusable labeled slider row with zoom buttons and click-to-type.

   These are PURE DOM builders. The caller supplies the `:on-input` callback
   (and does its own debouncing / live re-eval); the helpers never touch session
   state or the viewport. Styling reuses the slider CSS classes, which are scoped
   to `.modal-panel` (added by modal-evaluator/mount-panel!), so the rows look the
   same in every modal panel."
  (:require [clojure.string :as str]))

(defn format-value
  "Format a numeric value for display: integers as-is, floats to <=2 decimals
   with trailing zeros trimmed."
  [v]
  (if (== v (Math/round v))
    (str (long v))
    (let [s (.toFixed v 2)]
      (cond
        (str/ends-with? s "00") (subs s 0 (- (count s) 3))
        (str/ends-with? s "0")  (subs s 0 (dec (count s)))
        :else s))))

(defn slider-range
  "Default [min max step] for a slider given an initial value.

   Step is chosen from the *span* (a power of ten giving ~100–1000 stops), NOT
   from `(integer? value)`: in ClojureScript there is no int/float distinction,
   so `1.0` reads as the integral JS number `1` and `(integer? 1.0)` is `true` —
   keying the step off that gave a coarse step of 1 for a value like `1.0`, and
   with a fractional min (value*0.1) the reachable stops became 0.1/1.1/2.1
   instead of round numbers. Min/max are snapped to the step grid so stops land
   on round values."
  [value]
  (if (zero? value)
    [-50 50 1]
    (let [lo   (min (* value 0.1) (* value 3))
          hi   (max (* value 0.1) (* value 3))
          span (- hi lo)
          ;; power-of-ten step giving ~100 stops across the range
          step (Math/pow 10 (Math/floor (/ (Math/log (/ span 100)) (Math/log 10))))
          mn   (* step (Math/floor (/ lo step)))
          mx   (* step (Math/ceil (/ hi step)))]
      [mn mx step])))

(defn create-slider-row
  "Build one labeled slider row: `<label> [−] <range> [+] <value>`. Reuses the
   slider CSS classes (slider-row / slider-label / test-slider / slider-value /
   slider-value-input / test-zoom-btn), so it must live inside a `.modal-panel`.

   opts:
   - :label    string shown on the left
   - :value    initial numeric value
   - :on-input (fn [new-val]) called on every drag and on a committed typed value.
               NOT called by the returned `:set-value!`, so external syncing (e.g.
               keyboard nudges pushing the current value back in) can't loop.
   - :range-fn (fn [v] -> [min max step]); defaults to `slider-range`.

   Returns {:row :slider :value-el :set-value!}. `:set-value!` pushes an external
   value into the slider thumb + display without firing `:on-input`."
  [{:keys [label value on-input range-fn] :or {range-fn slider-range}}]
  (let [[smin smax step] (range-fn value)
        row       (.createElement js/document "div")
        label-el  (.createElement js/document "span")
        slider    (.createElement js/document "input")
        value-el  (.createElement js/document "span")
        zoom-out  (.createElement js/document "button")
        zoom-in   (.createElement js/document "button")
        set-value! (fn [v]
                     (set! (.-textContent value-el) (format-value v))
                     (set! (.-value slider) (str v)))]
    (.add (.-classList row) "slider-row")
    (.add (.-classList label-el) "slider-label")
    (set! (.-textContent label-el) label)
    (.add (.-classList value-el) "slider-value")
    (set! (.-textContent value-el) (format-value value))
    (set! (.-title value-el) "Click to type a value")
    (set! (.. value-el -style -cursor) "pointer")
    (set! (.-type slider) "range")
    (set! (.-min slider) (str smin))
    (set! (.-max slider) (str smax))
    (set! (.-step slider) (str step))
    (set! (.-value slider) (str value))
    (.add (.-classList slider) "test-slider")
    ;; Slider drag → on-input (value text tracks immediately).
    (.addEventListener slider "input"
                       (fn [_e]
                         (let [v (js/parseFloat (.-value slider))]
                           (set! (.-textContent value-el) (format-value v))
                           (when on-input (on-input v)))))
    ;; Click on the value → inline number input for an exact value.
    (.addEventListener value-el "click"
                       (fn [_e]
                         (let [input (.createElement js/document "input")]
                           (set! (.-type input) "number")
                           (set! (.-value input) (.-textContent value-el))
                           (.add (.-classList input) "slider-value-input")
                           (.replaceWith value-el input)
                           (.focus input)
                           (.select input)
                           (let [commit! (fn []
                                           (let [v (js/parseFloat (.-value input))]
                                             (when (and (js/isFinite v) (not (js/isNaN v)))
                                               (let [[nmin nmax nstep] (range-fn v)]
                                                 (set! (.-min slider) (str nmin))
                                                 (set! (.-max slider) (str nmax))
                                                 (set! (.-step slider) (str nstep)))
                                               (set-value! v)
                                               (when on-input (on-input v)))
                                             (.replaceWith input value-el)))]
                             (.addEventListener input "blur" (fn [_] (commit!)))
                             (.addEventListener input "keydown"
                                                (fn [e]
                                                  (cond
                                                    (= (.-key e) "Enter")
                                                    (do (.preventDefault e) (commit!))
                                                    (= (.-key e) "Escape")
                                                    (do (.preventDefault e)
                                                        (.replaceWith input value-el)))))))))
    ;; Zoom buttons: re-center the range on the current value (for unbounded values).
    (let [zoom-fn (fn [factor]
                    (let [cur       (js/parseFloat (.-value slider))
                          old-min   (js/parseFloat (.-min slider))
                          old-max   (js/parseFloat (.-max slider))
                          half-span (/ (* (- old-max old-min) factor) 2)
                          old-step  (js/parseFloat (.-step slider))
                          new-step  (if (> factor 1) (* old-step 2) (max 0.001 (/ old-step 2)))]
                      (set! (.-min slider) (str (- cur half-span)))
                      (set! (.-max slider) (str (+ cur half-span)))
                      (set! (.-step slider) (str new-step))
                      (set! (.-value slider) (str cur))))]
      (.add (.-classList zoom-out) "test-zoom-btn")
      (.add (.-classList zoom-in) "test-zoom-btn")
      (set! (.-textContent zoom-out) "+")
      (set! (.-textContent zoom-in) "−") ;; minus sign
      (set! (.-title zoom-out) "Wider range")
      (set! (.-title zoom-in) "Narrower range (more precise)")
      (.addEventListener zoom-out "click" (fn [_] (zoom-fn 2)))
      (.addEventListener zoom-in "click" (fn [_] (zoom-fn 0.5))))
    (.appendChild row label-el)
    (.appendChild row zoom-in)
    (.appendChild row slider)
    (.appendChild row zoom-out)
    (.appendChild row value-el)
    {:row row :slider slider :value-el value-el :set-value! set-value!}))
