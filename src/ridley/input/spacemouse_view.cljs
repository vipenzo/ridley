(ns ridley.input.spacemouse-view
  "The SpaceMouse drives the view whenever nothing else owns it (desktop only).
   The device is taken once at startup and kept: the reader (spacemouse.rs) lets
   go by itself while the window is not focused.

   Who the cap moves is `owner`: :view (here), or :object while edit-attach
   holds it. The left button belongs to whoever installs `on-left-button` (only
   edit-attach, to switch owner); without one it does nothing."
  (:require [ridley.input.spacemouse :as sm]
            [ridley.input.spacemouse-device :as device]
            [ridley.viewport.core :as viewport]))

(defonce owner (atom :view))

(defonce on-left-button
  ;; (fn []) or nil
  (atom nil))

(defonce ^:private loop-state
  ;; :t last frame time, :buttons last mask, :latched? wait for rest before orbiting
  (atom {}))

(defn- at-rest? [input]
  (let [th (:release-threshold sm/config)]
    (every? #(< (Math/abs %) th) (concat (:t input) (:r input)))))

(defn set-owner!
  "Give the cap to :view or :object. Handing it to the view waits for the cap
   to come back to rest first, so a push that ended an edit gesture does not
   carry on as an orbit."
  [who]
  (when (not= who @owner)
    (reset! owner who)
    (when (= who :view) (swap! loop-state assoc :latched? true))))

(defn- frame! [_camera]
  (let [now (js/performance.now)
        {:keys [t buttons latched?]} @loop-state
        dt (if t (/ (- now t) 1000) 0)
        input @device/state
        mask (:buttons input 0)
        pressed (bit-and mask (bit-not (or buttons 0)))]
    (swap! loop-state assoc :t now :buttons mask)
    (when (and latched? (or (nil? input) (at-rest? input)))
      (swap! loop-state assoc :latched? false))
    (when (pos? (bit-and pressed 1))
      (when-let [f @on-left-button] (f)))
    (when (and (= :view @owner) (not (:latched? @loop-state)))
      (when-let [m (sm/view-motion input dt)]
        (viewport/move-view! m)))))

(defn start!
  "Take the device and start orbiting. No-op outside the desktop app."
  []
  (when (device/available?)
    (device/acquire!)
    (viewport/register-frame-callback! :spacemouse-view frame!)))
