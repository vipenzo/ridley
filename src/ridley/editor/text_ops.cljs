(ns ridley.editor.text-ops
  "Text extrusion and text-on-path operations."
  (:require [ridley.editor.state :refer [turtle-atom]]
            [ridley.turtle.core :as turtle]
            [ridley.turtle.text :as text]
            [ridley.manifold.core :as manifold]))

;; ============================================================
;; Text extrusion
;; ============================================================

(defn ^:export transform-2d-point-to-3d
  "Transform a 2D point [x y] to 3D using turtle orientation.
   x -> along heading (text reading direction)
   y -> along right vector (perpendicular in text plane)
   The shape plane is perpendicular to 'up' (extrusion direction)."
  [[x y] position heading up]
  (let [right (turtle/cross heading up)]
    (turtle/v+ position
               (turtle/v+ (turtle/v* heading x)
                          (turtle/v* right y)))))

(defn ^:export contour-signed-area
  "Calculate signed area of a 2D contour using shoelace formula.
   Positive = counter-clockwise (outer), negative = clockwise (hole)."
  [contour]
  (let [n (count contour)]
    (when (> n 2)
      (/ (reduce + (for [i (range n)]
                     (let [[x1 y1] (nth contour i)
                           [x2 y2] (nth contour (mod (inc i) n))]
                       (- (* x1 y2) (* x2 y1)))))
         2.0))))

(defn ^:export build-extruded-contour-mesh
  "Build a mesh from extruding a single 2D contour along a direction.
   Returns {:vertices [...] :faces [...]}.

   reverse-winding? should be true for holes to ensure outward-facing normals
   point into the hole (which will be subtracted)."
  [contour-2d position heading up depth & {:keys [reverse-winding?] :or {reverse-winding? false}}]
  (let [;; If reverse-winding?, reverse the contour order
        contour (if reverse-winding? (vec (reverse contour-2d)) contour-2d)
        n (count contour)
        ;; Transform 2D contour to 3D at base position
        base-ring (mapv #(transform-2d-point-to-3d % position heading up) contour)
        ;; Create top ring by moving along 'up' direction
        top-ring (mapv #(turtle/v+ % (turtle/v* up depth)) base-ring)
        ;; Vertices: base ring then top ring
        vertices (vec (concat base-ring top-ring))
        ;; Side faces (quads as 2 triangles each)
        side-faces (vec (for [i (range n)]
                          (let [i0 i
                                i1 (mod (inc i) n)
                                i2 (+ n (mod (inc i) n))
                                i3 (+ n i)]
                            ;; Two triangles for each quad
                            [[i0 i1 i2] [i0 i2 i3]])))
        side-faces-flat (vec (mapcat identity side-faces))
        ;; Cap faces (simple fan triangulation)
        ;; Bottom cap (reversed winding for outward normal)
        bottom-faces (vec (for [i (range 1 (dec n))]
                            [0 (inc i) i]))
        ;; Top cap
        top-faces (vec (for [i (range 1 (dec n))]
                         [(+ n 0) (+ n i) (+ n (inc i))]))
        all-faces (vec (concat side-faces-flat bottom-faces top-faces))]
    {:type :mesh
     :vertices vertices
     :faces all-faces}))

(defn ^:export classify-glyph-contours
  "Classify contours into outer boundary and holes based on signed area.
   Returns {:outer contour :holes [contours]}
   The outer contour has the largest absolute area."
  [contours]
  (when (seq contours)
    (let [with-areas (map (fn [c] {:contour c :area (contour-signed-area c)}) contours)
          ;; Sort by absolute area descending - largest is outer
          sorted (sort-by #(- (Math/abs (or (:area %) 0))) with-areas)
          outer-entry (first sorted)
          rest-entries (rest sorted)]
      {:outer (:contour outer-entry)
       :holes (vec (map :contour rest-entries))})))

(defn ^:export transform-mesh-to-turtle-orientation
  "Transform a mesh from XY plane (Z up) to turtle orientation.
   Manifold's extrude creates mesh in XY plane extruding along +Z.
   We rotate so text flows along heading, letter tops point along up,
   and extrusion depth goes along right — matching (extrude (text-shape ...) (f d)).

   Default orientation (heading=[1,0,0], up=[0,0,1]):
   - Text flows along +X (heading)
   - Letters' top points toward +Z (up)
   - Extrusion depth goes toward -Y (right)"
  [mesh position heading up]
  (let [right (turtle/cross heading up)
        vertices (:vertices mesh)
        faces (:faces mesh)
        ;; Transform each vertex: [x, y, z] -> position + x*heading + y*up + z*right
        ;; This matches text-shape's plane mapping (plane-x=heading, plane-y=up)
        ;; and puts depth along right (perpendicular to the text face).
        transformed-verts
        (mapv (fn [[x y z]]
                (turtle/v+ position
                           (turtle/v+ (turtle/v* heading x)
                                      (turtle/v+ (turtle/v* up y)
                                                 (turtle/v* right z)))))
              vertices)]
    (-> mesh
        (assoc :vertices transformed-verts)
        (assoc :faces faces))))

(defn ^:export implicit-extrude-text
  "Extrude text along the turtle's heading direction.
   Text flows along heading, extrudes along up.
   Uses Manifold's CrossSection for proper handling of holes.

   Options:
   - :size - font size (default 10)
   - :depth - extrusion depth (default 5)
   - :font - font object (optional)

   Returns vector of meshes, one per character."
  [txt & {:keys [size depth font] :or {size 10 depth 5}}]
  (let [glyph-data (text/text-glyph-data txt :size size :font font)
        start-pos (:position @turtle-atom)
        heading (:heading @turtle-atom)
        up (:up @turtle-atom)
        meshes (atom [])]
    (doseq [{:keys [contours x-offset]} glyph-data]
      (when (seq contours)
        (let [{:keys [outer holes]} (classify-glyph-contours contours)]
          (when (and outer (> (count outer) 2))
            ;; Position for this glyph: start + offset along heading
            (let [glyph-pos (turtle/v+ start-pos (turtle/v* heading x-offset))
                  ;; Prepare contours for CrossSection:
                  ;; - Outer must be counter-clockwise (positive area)
                  ;; - Holes must be clockwise (negative area)
                  outer-area (contour-signed-area outer)
                  prepared-outer (if (neg? outer-area) (vec (reverse outer)) outer)
                  prepared-holes (mapv (fn [hole]
                                         (let [hole-area (contour-signed-area hole)]
                                           ;; Holes should be clockwise (negative area)
                                           (if (pos? hole-area)
                                             (vec (reverse hole))
                                             hole)))
                                       holes)
                  ;; Combine outer + holes into single contours vector
                  all-contours (into [prepared-outer] prepared-holes)
                  ;; Use Manifold's CrossSection for proper extrusion with holes
                  raw-mesh (manifold/extrude-cross-section all-contours depth)]
              (when raw-mesh
                (let [;; Transform mesh from XY/Z orientation to turtle orientation
                      transformed-mesh (transform-mesh-to-turtle-orientation raw-mesh glyph-pos heading up)
                      mesh-with-pose (assoc transformed-mesh :creation-pose
                                            {:position glyph-pos
                                             :heading heading
                                             :up up})]
                  (swap! meshes conj mesh-with-pose)
                  (swap! turtle-atom update :meshes conj mesh-with-pose))))))))
    @meshes))

(defn ^:export implicit-text-on-path
  "Place text along a path, extruding each glyph perpendicular to curve.
   Each letter is positioned at its x-offset distance along the path,
   oriented tangent to the curve direction.

   Options:
   - :size - font size (default 10)
   - :depth - extrusion depth (default 5)
   - :font - custom font (optional)
   - :overflow - :truncate (default), :wrap, or :scale
   - :align - :start (default), :center, or :end
   - :spacing - extra letter spacing (default 0)

   Returns vector of meshes, one per glyph."
  [txt path & {:keys [size depth font overflow align spacing]
               :or {size 10 depth 5 overflow :truncate align :start spacing 0}}]
  (let [glyph-data (text/text-glyph-data txt :size size :font font)
        path-len (turtle/path-total-length path)
        ;; Calculate total text width including spacing
        text-len (if (seq glyph-data)
                   (+ (reduce + (map :advance-width glyph-data))
                      (* spacing (max 0 (dec (count glyph-data)))))
                   0)
        ;; Calculate start offset based on alignment
        start-offset (case align
                       :center (/ (- path-len text-len) 2)
                       :end (- path-len text-len)
                       0)
        ;; Scale factor for :scale overflow mode
        scale-factor (if (and (= overflow :scale) (pos? text-len))
                       (/ path-len text-len)
                       1.0)
        ;; Get turtle's starting orientation for path sampling
        turtle-pos (:position @turtle-atom)
        turtle-heading (:heading @turtle-atom)
        turtle-up (:up @turtle-atom)
        meshes (atom [])]
    ;; x-offset in glyph-data is already cumulative, so we use it directly
    ;; We only need to add start-offset (for alignment) and apply scale-factor
    (doseq [[glyph-idx {:keys [contours x-offset advance-width]}] (map-indexed vector glyph-data)]
      (let [;; Distance along path for glyph CENTER (not start)
            ;; This gives better orientation on curves
            ;; x-offset is cumulative position of glyph start
            extra-spacing (* spacing glyph-idx)
            glyph-center-dist (+ start-offset
                                 (* (+ x-offset (/ advance-width 2)) scale-factor)
                                 extra-spacing)
            ;; Sample the path at the CENTER of the glyph for orientation
            sample (turtle/sample-path-at-distance path glyph-center-dist
                     :wrap? (= overflow :wrap)
                     :start-pos turtle-pos
                     :start-heading turtle-heading
                     :start-up turtle-up)]
        (when (and sample (seq contours))
          (let [{:keys [position heading up]} sample
                {:keys [outer holes]} (classify-glyph-contours contours)
                ;; Position is at center, but glyph origin is at x-offset=0
                ;; So we need to shift back by half the advance-width
                half-width (/ (* advance-width scale-factor) 2)
                glyph-position (turtle/v- position (turtle/v* heading half-width))]
            (when (and outer (> (count outer) 2))
              ;; Prepare contours for CrossSection (same as extrude-text)
              (let [outer-area (contour-signed-area outer)
                    prepared-outer (if (neg? outer-area) (vec (reverse outer)) outer)
                    prepared-holes (mapv (fn [hole]
                                           (let [a (contour-signed-area hole)]
                                             (if (pos? a) (vec (reverse hole)) hole)))
                                         holes)
                    all-contours (into [prepared-outer] prepared-holes)
                    raw-mesh (manifold/extrude-cross-section all-contours depth)]
                (when raw-mesh
                  (let [transformed (transform-mesh-to-turtle-orientation raw-mesh glyph-position heading up)
                        with-pose (assoc transformed :creation-pose
                                         {:position glyph-position :heading heading :up up})]
                    (swap! meshes conj with-pose)
                    (swap! turtle-atom update :meshes conj with-pose)))))))))
    @meshes))
