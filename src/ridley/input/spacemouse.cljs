(ns ridley.input.spacemouse
  "SpaceMouse → edit-attach commands, as pure functions (dev-docs/
   brief-spacemouse-edit-attach.md, commit 1). No DOM, Tauri or Three.js here: the
   reader (Rust) hands over raw device values, edit-attach drives `step-gesture`
   once per frame and turns its :commit into one add-command!.

   One gesture = one command: while the cap is deflected the value integrates as a
   velocity and only the preview moves; back at rest, the value rounded to the
   step is committed once. The axis family is the session's (:step → f/rt/u,
   :angle → th/tv/tr), never inferred: pushing the cap tilts it about as much as
   it translates (dev-docs/accertamento-spacemouse-risultati.md, point 1), so the
   two families must not compete.")

(def config
  "Every tunable in one place — to be calibrated on screen. Raw units are the
   device's (±350 at full deflection)."
  {:full-scale        350
   :start-threshold   80     ; over the ±52 a button press wobbles the cap
   :release-threshold 40     ; also the response dead zone
   :release-hold-ms   120
   :curve             2.0    ; power on the normalized deflection
   :max-steps-per-s   10     ; full deflection = 10 steps (or angle-steps) per second
   :max-dt            0.1})  ; a stalled frame integrates at most this long (s)

(def idle {:phase :idle})

(def ^:private families
  {:step  [:f :rt :u]
   :angle [:th :tv :tr]})

(def ^:private rotation? #{:th :tv :tr})

(defn device->turtle
  "Raw device state `{:t [tx ty tz] :r [rx ry rz]}` → the six turtle components.

   Device frame (measured, point 1): X right, Y toward the user, Z down; rotations
   right-handed about the same axes. Turtle reading: the cap IS the object seen
   from behind the turtle.
     push away = f+      (ty −)          push right = rt+   (tx +)
     lift      = u+      (tz −)
     tilt far edge down   = nose down = tv−   (rx −)
     tilt right edge down = roll right = tr+  (ry −)
     twist clockwise seen from above = yaw right = th−  (rz +)
   Rotation signs from the commands themselves, all right-handed Rodrigues
   (math.cljs:59): th + turns LEFT, about up (turtle/core.cljs:546); tv + pitches
   UP, about right = heading × up (turtle/core.cljs:581); tr + rolls clockwise
   seen from behind, i.e. right edge down, about heading (turtle/core.cljs:617).
   Attached meshes rotate the same way (turtle/attachment.cljs:195)."
  [{[tx ty tz] :t [rx ry rz] :r}]
  {:f (- ty) :rt tx :u (- tz)
   :th (- rz) :tv rx :tr (- ry)})

(defn pick-axis
  "The component of `family` (:step or :angle) with the largest absolute value, or
   nil if it is under the start threshold (or the family has no axes — :scale)."
  ([family comps] (pick-axis family comps config))
  ([family comps cfg]
   (when-let [ks (families family)]
     (let [k (apply max-key #(Math/abs (get comps % 0)) ks)]
       (when (>= (Math/abs (get comps k 0)) (:start-threshold cfg))
         k)))))

(defn response
  "Raw deflection → speed in [-1, 1]: zero inside the dead zone, then a power
   curve on what is left, so small deflections are fine and full ones fast."
  ([raw] (response raw config))
  ([raw {:keys [full-scale release-threshold curve]}]
   (let [a (min (Math/abs raw) full-scale)]
     (if (<= a release-threshold)
       0
       (* (Math/sign raw)
          (Math/pow (/ (- a release-threshold) (- full-scale release-threshold)) curve))))))

(defn- round-to
  "Same rounding as the gizmo (gizmo.cljs snap-round / free-round): `free?` → one
   decimal, else the nearest multiple of `step` (step <= 0: one decimal too)."
  [v step free?]
  (if (or free? (nil? step) (<= step 0))
    (/ (Math/round (* v 10)) 10)
    (* step (Math/round (/ v step)))))

(defn- unit [axis {:keys [step angle-step]}]
  (if (rotation? axis) angle-step step))

(defn gesture-value
  "The value an :active gesture would commit right now — also what the preview and
   the panel show. nil when idle."
  [{:keys [phase axis acc]} {:keys [free?] :as opts}]
  (when (= phase :active)
    (round-to acc (unit axis opts) free?)))

(defn- end [state opts]
  (let [v (gesture-value state opts)]
    (cond-> {:phase :idle :ended true}
      (and v (not (zero? v))) (assoc :commit [(:axis state) v]))))

(defn step-gesture
  "Advance the gesture by one frame. `input` is the latest raw device state
   `{:t [..] :r [..] :buttons n}` (nil = nothing received = at rest), `dt` the
   frame time in seconds, `opts` `{:family :step :angle-step :free? :blocked?}`.

     :idle   → :active when the family's largest axis reaches the start
               threshold (not while :blocked? — a re-eval is pending — nor with a
               button down, since pressing a button moves the cap).
     :active → the locked axis integrates `response × max-steps-per-s × unit`.
               It ends when the device is all zero, when the locked axis stays
               under the release threshold for release-hold-ms, or when a button
               is pressed (the button's own effect is the caller's business).

   Ending returns `{:phase :idle :ended true}` plus `:commit [cmd value]` when the
   rounded value is not zero. Those two keys live on that one returned state only."
  ([state input dt opts] (step-gesture state input dt opts config))
  ([state input dt {:keys [family blocked?] :as opts} cfg]
   (let [input (or input {:t [0 0 0] :r [0 0 0] :buttons 0})
         comps (device->turtle input)
         button? (pos? (:buttons input 0))
         dt (min dt (:max-dt cfg))]
     (if (= :active (:phase state))
       (let [{:keys [axis]} state
             raw (get comps axis)
             all-zero? (every? zero? (concat (:t input) (:r input)))
             quiet-ms (if (< (Math/abs raw) (:release-threshold cfg))
                        (+ (:quiet-ms state) (* dt 1000))
                        0)]
         (if (or button? all-zero? (>= quiet-ms (:release-hold-ms cfg)))
           (end state opts)
           (assoc state
                  :acc (+ (:acc state) (* (response raw cfg) (:max-steps-per-s cfg)
                                          (unit axis opts) dt))
                  :quiet-ms quiet-ms)))
       (let [axis (when-not (or blocked? button?) (pick-axis family comps cfg))]
         (if axis
           {:phase :active :axis axis :quiet-ms 0
            :acc (* (response (get comps axis) cfg) (:max-steps-per-s cfg)
                    (unit axis opts) dt)}
           idle))))))
