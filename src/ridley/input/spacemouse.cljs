(ns ridley.input.spacemouse
  "SpaceMouse → edit-attach commands, as pure functions (dev-docs/
   brief-spacemouse-edit-attach.md). No DOM, Tauri or Three.js here: the reader
   (Rust) hands over raw device values, edit-attach drives `step-gesture` once per
   frame and turns its :commits into add-command! calls.

   One gesture, written once: while the cap is deflected the values integrate as
   velocities and only the preview moves; back at rest, each value rounded to its
   step is committed. Two ways to pick the axes:
   - a family (:step → f/rt/u, :angle → th/tv/tr): one axis, the largest at the
     start, locked — pushing the cap tilts it about as much as it translates
     (dev-docs/accertamento-spacemouse-risultati.md, point 1), so the families
     must not compete;
   - :all — every signal honored, each axis joining when it crosses the start
     threshold; the user corrects any coupling by hand (Vincenzo, 2026-11-02).

   Outside edit-attach — or inside it, after the left button — the same cap
   drives the VIEW (`view-motion`): continuous, nothing to commit.")

(def config
  "Every tunable in one place — to be calibrated on screen. Raw units are the
   device's (±350 at full deflection)."
  {:full-scale        350
   :start-threshold   80     ; over the ±52 a button press wobbles the cap
   :release-threshold 40     ; also the response dead zone
   :release-hold-ms   120
   :curve             2.0    ; power on the normalized deflection
   :max-steps-per-s   10     ; full deflection = 10 steps (or angle-steps) per second
   :max-dt            0.1    ; a stalled frame integrates at most this long (s)
   ;; view (orbit) — full deflection, per second
   :view-deg-per-s    120    ; turn
   :view-pan-per-s    1.0    ; pan, in distances camera→target
   :view-zoom-per-s   1.5})  ; zoom, as a log factor: e^1.5 ≈ 4.5× farther

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

(def ^:private axis-order
  "The order commands are written in at release: translations, then th tv tr. The
   preview composes them the same way (gizmo/preview-commands!)."
  [:f :rt :u :th :tv :tr])

(defn- joining
  "The gesture's axes after this frame. In a family (:step/:angle) only the axis
   picked at the start, and the set never grows; in :all (every signal honored)
   each axis joins the first time it reaches the start threshold."
  [family comps axes cfg]
  (if (= family :all)
    (into (or axes #{})
          (filter #(>= (Math/abs (comps %)) (:start-threshold cfg)))
          axis-order)
    (or axes (some-> (pick-axis family comps cfg) hash-set))))

(defn gesture-commands
  "What an :active gesture would write right now — `[[cmd value] ...]`, each value
   rounded to its step (or to 0.1 with `free?`), zeros dropped, in axis-order.
   Also what the panel shows. nil when idle."
  [{:keys [phase acc]} {:keys [free?] :as opts}]
  (when (= phase :active)
    (vec (for [a axis-order
               :let [x (get acc a)]
               :when x
               :let [v (round-to x (unit a opts) free?)]
               :when (not (zero? v))]
           [a v]))))

(defn gesture-preview
  "The unrounded values of an :active gesture, `[[cmd value] ...]` in axis-order —
   what the preview follows, so the object glides instead of stepping (Vincenzo,
   2026-11-02). Rounding happens only in what is written. nil when idle."
  [{:keys [phase acc]}]
  (when (= phase :active)
    (vec (for [a axis-order :let [x (get acc a)] :when x] [a x]))))

(defn- integrate [acc axes comps dt opts cfg]
  (reduce (fn [acc a]
            (update acc a (fnil + 0)
                    (* (response (comps a) cfg) (:max-steps-per-s cfg) (unit a opts) dt)))
          acc axes))

(defn step-gesture
  "Advance the gesture by one frame. `input` is the latest raw device state
   `{:t [..] :r [..] :buttons n}` (nil = nothing received = at rest), `dt` the
   frame time in seconds, `opts` `{:family :step :angle-step :free? :blocked?}`,
   :family being :step, :angle (one axis of that family, locked at the start) or
   :all (every axis that reaches the start threshold joins).

     :idle   → :active when an eligible axis reaches the start threshold (not
               while :blocked? — a re-eval is pending — nor with a button down,
               since pressing a button moves the cap).
     :active → each joined axis integrates `response × max-steps-per-s × unit`.
               It ends when the device is all zero, when every joined axis stays
               under the release threshold for release-hold-ms, or when a button
               is pressed (the button's own effect is the caller's business).

   Ending returns `{:phase :idle :ended true}` plus `:commits [[cmd value] ...]`
   when anything rounds to non-zero. Those keys live on that one returned state."
  ([state input dt opts] (step-gesture state input dt opts config))
  ([state input dt {:keys [family blocked?] :as opts} cfg]
   (let [input (or input {:t [0 0 0] :r [0 0 0] :buttons 0})
         comps (device->turtle input)
         button? (pos? (:buttons input 0))
         dt (min dt (:max-dt cfg))]
     (if (= :active (:phase state))
       (let [axes (joining family comps (:axes state) cfg)
             all-zero? (every? zero? (concat (:t input) (:r input)))
             quiet-ms (if (every? #(< (Math/abs (comps %)) (:release-threshold cfg)) axes)
                        (+ (:quiet-ms state) (* dt 1000))
                        0)]
         (if (or button? all-zero? (>= quiet-ms (:release-hold-ms cfg)))
           (let [cmds (gesture-commands state opts)]
             (cond-> {:phase :idle :ended true}
               (seq cmds) (assoc :commits cmds)))
           (assoc state
                  :axes axes
                  :acc (integrate (:acc state) axes comps dt opts cfg)
                  :quiet-ms quiet-ms)))
       (let [axes (when-not (or blocked? button?) (joining family comps nil cfg))]
         (if (seq axes)
           {:phase :active :axes axes :quiet-ms 0
            :acc (integrate {} axes comps dt opts cfg)}
           idle))))))

;; ============================================================
;; View (orbit)
;; ============================================================

(defn view-motion
  "One frame of view motion from the raw device state, read with the same
   convention as edit-attach: the cap IS the scene, seen from the camera. Returns
   the scene's motion in the camera frame — :rt / :u pan (in distances
   camera→target), :f (log zoom factor, + = the scene goes away), :th / :tv / :tr
   turns in degrees about the view's up / right / forward axis, signs as the
   turtle's — or nil when there is nothing to do (at rest, a button held)."
  ([input dt] (view-motion input dt config))
  ([input dt {:keys [max-dt view-deg-per-s view-pan-per-s view-zoom-per-s] :as cfg}]
   (when (and input (zero? (:buttons input 0)))
     (let [dt (min dt max-dt)
           comps (device->turtle input)
           v (fn [axis rate] (* rate dt (response (get comps axis) cfg)))
           m {:rt (v :rt view-pan-per-s) :u (v :u view-pan-per-s)
              :f (v :f view-zoom-per-s)
              :th (v :th view-deg-per-s) :tv (v :tv view-deg-per-s)
              :tr (v :tr view-deg-per-s)}]
       (when (some #(not (zero? %)) (vals m)) m)))))
