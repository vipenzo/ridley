(ns ridley.editor.transforms
  "Polymorphic top-level transforms for mesh / SDF / shape.
   These dispatch on the type of the first argument and delegate to the
   appropriate per-type implementation."
  (:require [ridley.sdf.core :as sdf]
            [ridley.turtle.attachment :as attachment]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.transform :as xform]
            [ridley.editor.implicit :as ed-impl]))

(defn- mesh? [x]
  (and (map? x) (:vertices x) (:faces x) (not (sdf/sdf-node? x))))

(defn- shape? [x]
  (and (map? x) (or (= :shape (:type x)) (vector? (:points x)))))

(defn- type-error [op-name x]
  (throw (js/Error. (str op-name ": unsupported argument type. Expected mesh, SDF, or 2D shape."))))

(defn- sdf-pivot
  "Position component of an SDF's creation-pose. Every SDF carries one
   (defaulted to world origin at construction), so this is always defined."
  [sdf-node]
  (or (get-in sdf-node [:creation-pose :position]) [0 0 0]))

;; ============================================================
;; translate
;; ============================================================

(defn ^:export translate
  "Translate a mesh / SDF / 2D shape.
   - Mesh / SDF: (translate thing dx dy dz)
   - 2D shape:   (translate shape dx dy)"
  [thing & args]
  (cond
    (sdf/sdf-node? thing)
    (let [[dx dy dz] args]
      (sdf/sdf-move thing dx dy dz))

    (mesh? thing)
    (let [[dx dy dz] args]
      (attachment/translate-mesh thing [dx dy dz]))

    (shape? thing)
    (let [[dx dy] args]
      (xform/translate thing dx dy))

    :else (type-error "translate" thing)))

;; ============================================================
;; scale
;; ============================================================

(defn- sdf-scale-around-pivot
  "Scale an SDF in place around its creation-pose position.
   Sandwich: translate pivot to origin, scale, translate back."
  [sdf-node sx sy sz]
  (let [[px py pz] (sdf-pivot sdf-node)]
    (-> sdf-node
        (sdf/sdf-move (- px) (- py) (- pz))
        (sdf/sdf-scale sx sy sz)
        (sdf/sdf-move px py pz))))

(defn ^:export scale
  "Scale a mesh / SDF / 2D shape, OR (legacy) scale the currently-attached
   mesh during an attach.
   - Value form:   (scale thing s)            uniform
                   (scale thing sx sy [sz])   non-uniform
   - Legacy form:  (scale s)                  scales attached mesh
                   (scale sx sy sz)           non-uniform on attached mesh"
  [first-arg & rest-args]
  (cond
    ;; Legacy turtle attach form: first arg is a number
    (number? first-arg)
    (case (count rest-args)
      0 (ed-impl/implicit-scale-mesh first-arg)
      2 (ed-impl/implicit-scale-mesh first-arg (first rest-args) (second rest-args))
      (throw (js/Error. "scale: legacy turtle form takes 1 or 3 number args")))

    (sdf/sdf-node? first-arg)
    (case (count rest-args)
      1 (let [s (first rest-args)] (sdf-scale-around-pivot first-arg s s s))
      3 (let [[sx sy sz] rest-args] (sdf-scale-around-pivot first-arg sx sy sz))
      (throw (js/Error. "scale: SDF form takes 1 (uniform) or 3 (per-axis) factors")))

    (mesh? first-arg)
    (case (count rest-args)
      1 (attachment/scale-mesh first-arg (first rest-args))
      3 (attachment/scale-mesh first-arg (vec rest-args))
      (throw (js/Error. "scale: mesh form takes 1 (uniform) or 3 (per-axis) factors")))

    (shape? first-arg)
    (case (count rest-args)
      1 (xform/scale first-arg (first rest-args))
      2 (xform/scale first-arg (first rest-args) (second rest-args))
      (throw (js/Error. "scale: 2D shape form takes 1 (uniform) or 2 (per-axis) factors")))

    :else (type-error "scale" first-arg)))

;; ============================================================
;; rotate
;; ============================================================

(defn- z-axis? [axis]
  (cond
    (keyword? axis) (= :z axis)
    (sequential? axis)
    (let [[ax ay az] axis]
      (and (zero? (or ax 0)) (zero? (or ay 0)) (not (zero? (or az 0)))))
    :else false))

(defn- sdf-rotate-around-pivot
  "Rotate an SDF in place around its creation-pose position. The axis is
   interpreted in world space; only the pivot location moves to the
   creation-pose. Sandwich: translate pivot to origin, rotate, translate back."
  [sdf-node axis angle-deg]
  (let [[px py pz] (sdf-pivot sdf-node)]
    (-> sdf-node
        (sdf/sdf-move (- px) (- py) (- pz))
        (sdf/sdf-rotate axis angle-deg)
        (sdf/sdf-move px py pz))))

(defn ^:export rotate
  "Rotate a mesh / SDF / 2D shape.
   - Mesh / SDF: (rotate thing axis angle-deg)
                  axis = :x | :y | :z (cardinal) or [ax ay az] (arbitrary)
                  Mesh rotates around its centroid; SDF around its
                  creation-pose position.
   - 2D shape:   (rotate shape angle-deg)            implicit Z axis
                 (rotate shape :z angle-deg)         explicit Z (same)
                 (rotate shape :x|:y angle-deg)      ERROR: not meaningful for
                   a 2D shape — to position out of plane, set turtle heading
                   before extrude/loft. For Y-foreshortening write
                   (scale shape 1 (cos angle)) explicitly."
  ([thing axis-or-angle]
   ;; Single-arg form is only valid for shapes: (rotate shape angle).
   (cond
     (shape? thing)
     (xform/rotate thing axis-or-angle)

     :else
     (throw (js/Error. "rotate: 2-arg form (thing angle) is only valid for 2D shapes; mesh and SDF require an axis"))))

  ([thing axis angle-deg]
   (cond
     (shape? thing)
     (if (z-axis? axis)
       (xform/rotate thing angle-deg)
       (throw (js/Error.
               (str "rotate on 2D shape: only :z (or no axis) is meaningful — "
                    "to position a shape out of plane, set turtle heading before "
                    "extrude/loft. For Y-foreshortening use (scale shape 1 (cos angle))."))))

     (sdf/sdf-node? thing)
     (sdf-rotate-around-pivot thing axis angle-deg)

     (mesh? thing)
     (let [angle-rad (* angle-deg (/ js/Math.PI 180))
           axis-vec (cond
                      (= axis :x) [1 0 0]
                      (= axis :y) [0 1 0]
                      (= axis :z) [0 0 1]
                      (sequential? axis) (vec axis)
                      :else (throw (js/Error. "rotate: axis must be :x :y :z or [ax ay az]")))]
       (attachment/rotate-mesh thing axis-vec angle-rad))

     :else (type-error "rotate" thing))))
