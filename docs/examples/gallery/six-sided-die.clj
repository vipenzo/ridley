;; Six-Sided Die (D6)
;; A classic D6 with pips subtracted from each face. Built from a box,
;; then spheres placed on all six faces with turtle scopes for orientation,
;; subtracted with mesh-difference. Opposite faces sum to 7.
;; Try changing: die-size, pip-radius / pip-depth, pip-spread.

(def die-size 20)
(def half (/ die-size 2))
(def pip-radius 2.0)
(def pip-depth 1.2)
(def pip-spread 5.5)

(def body (box die-size))

(defn pip [x-off y-off]
  (turtle
    (f (- half pip-depth))
    (rt x-off) (u y-off)
    (sphere pip-radius)))

(defn face-1 [] [(pip 0 0)])
(defn face-2 [] [(pip (- pip-spread) pip-spread)
                  (pip pip-spread (- pip-spread))])
(defn face-3 [] (concat (face-1) (face-2)))
(defn face-4 [] [(pip (- pip-spread) pip-spread)
                  (pip pip-spread pip-spread)
                  (pip (- pip-spread) (- pip-spread))
                  (pip pip-spread (- pip-spread))])
(defn face-5 [] (concat (face-4) (face-1)))
(defn face-6 [] [(pip (- pip-spread) pip-spread)
                  (pip (- pip-spread) 0)
                  (pip (- pip-spread) (- pip-spread))
                  (pip pip-spread pip-spread)
                  (pip pip-spread 0)
                  (pip pip-spread (- pip-spread))])

(def all-pips
  (concat-meshes
    (concat
      (face-1)
      (turtle (th 180) (face-6))
      (turtle (th -90) (face-2))
      (turtle (th 90) (face-5))
      (turtle (tv 90) (face-3))
      (turtle (tv -90) (face-4)))))

(def die (mesh-difference body all-pips))
(register die die)
