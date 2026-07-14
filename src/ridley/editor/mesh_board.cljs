(ns ridley.editor.mesh-board
  "The mesh-board family of scaffold display directives (dev-docs/brief-mesh-
   board.md, Part 3) — visualization of a decomposition tree's leaves, in
   place, with an optional pairwise comparison + printed fidelity. Modeled on
   `stamp` (ridley.editor.implicit/implicit-stamp-debug), not on `attach`:
   a PLAIN FUNCTION with a per-eval side effect (pushes ghost-wireframe
   display meshes into the scene accumulator's :scaffolds, drained exactly
   like :stamps — full eval replaces, incremental REPL eval appends), that
   returns its FIRST ARGUMENT unchanged — the referential inertia the design
   doc's citizenship story rests on: mesh-board never alters the values the
   rest of the program computes with, so scaffold geometry structurally never
   reaches CSG or export through the language itself. Deliberately never a
   macro (see brief's 'Alternative scartate') — capturing the caller's
   variable names for the fidelity message isn't worth losing composability
   in run!/map/threading; fixed labels + an optional :label disambiguate
   instead."
  (:require [ridley.editor.state :as state]
            [ridley.manifold.core :as manifold]
            [ridley.sdf.core :as sdf]))

(defn- mesh-shaped? [v] (and (map? v) (:vertices v)))

(defn- board-value?
  "True iff v can stand as a mesh-board show/comparison operand: a mesh, or
   (for free, via manifold's own SDF coercion) an SDF node."
  [v]
  (or (mesh-shaped? v) (sdf/sdf-node? v)))

(defn- record-scaffolds!
  "Push scaffold mesh-data into the per-eval accumulator (mirrors implicit.cljs's
   record-stamps!, minus the turtle-state read — mesh-board operates on already-
   computed values, no turtle involved)."
  [meshes]
  (let [meshes (filterv identity meshes)]
    (when (seq meshes)
      (swap! state/scene-accumulator update :scaffolds into meshes))))

;; ============================================================
;; Show: (mesh-board t) / (mesh-board t {:only [...]})
;; ============================================================

(defn- leaves-of
  "Resolve the meshes to show as scaffold for a `mesh-board t opts` call — a
   tree (map, keys = piece names), a vector (older emit output — no names, so
   no :only), or a single mesh (totality: one leaf). Returns a vector of mesh
   maps. Never a silent no-op: an unsupported input type or an unknown :only
   name is a readable error naming it (brief Part 1's fix applies here too)."
  [t {:keys [only]}]
  (cond
    (mesh-shaped? t)
    (if only
      (throw (js/Error. "mesh-board: :only is only valid for a map (tree) input — got a single mesh"))
      [t])

    (map? t)
    (let [names (if only (vec only) (vec (keys t)))
          unknown (remove #(contains? t %) names)]
      (when (seq unknown)
        (throw (js/Error. (str "mesh-board: :only names unknown piece(s) " (pr-str (vec unknown))
                               " — available: " (pr-str (vec (keys t)))))))
      (mapv t names))

    (sequential? t)
    (if only
      (throw (js/Error. (str "mesh-board: :only is only valid for a map (tree) input — got a vector "
                             "(an older emit body has no names; re-emit to get the map form)")))
      (vec t))

    :else
    (throw (js/Error. (str "mesh-board: unsupported argument — got " (pr-str (type t)))))))

(defn- show!
  [t opts]
  (record-scaffolds! (leaves-of t opts))
  t)

;; ============================================================
;; Compare: (mesh-board foglia candidato {:mode ... :label ...})
;; ============================================================

(defn- fidelity-ratio
  "Symmetric-difference ratio between two meshes: (vol(union) − vol(intersection))
   / vol(reference) — 0 for identical solids, generalizing mirror-ratio's
   technique (manifold/core.cljs) from a self-mirror comparison to two
   arbitrary meshes. nil when WASM isn't initialized or the reference is
   degenerate — the caller degrades gracefully (no print), same convention as
   union/difference/intersection themselves."
  [reference candidate]
  (let [u (manifold/union reference candidate)
        i (manifold/intersection reference candidate)
        vr (:volume (manifold/get-mesh-status reference))]
    (when (and u i (pos? vr))
      (/ (- (:volume (manifold/get-mesh-status u))
            (:volume (manifold/get-mesh-status i)))
         vr))))

(defn- mode-mesh
  "The candidate's mode-dependent scaffold representation — the second
   scaffold item alongside the reference (always shown as scaffold too, per
   brief: 'la foglia come scaffold, il candidato secondo il modo'). nil if the
   boolean op is unavailable (WASM not initialized)."
  [mode reference candidate]
  (case mode
    :overlay candidate
    :intersection (manifold/intersection reference candidate)
    :diff (let [d1 (manifold/difference reference candidate)
                d2 (manifold/difference candidate reference)]
            (when (and d1 d2) (manifold/union d1 d2)))
    (throw (js/Error. (str "mesh-board: unknown :mode " (pr-str mode)
                           " — use :overlay, :intersection, or :diff")))))

(defn- compare!
  [reference candidate {:keys [mode label] :or {mode :overlay}}]
  (when-not (board-value? reference)
    (throw (js/Error. (str "mesh-board: unsupported comparison reference — got " (pr-str (type reference))))))
  (when-not (board-value? candidate)
    (throw (js/Error. (str "mesh-board: unsupported comparison candidate — got " (pr-str (type candidate))))))
  (let [ratio (fidelity-ratio reference candidate)
        second-mesh (mode-mesh mode reference candidate)]
    (record-scaffolds! [reference second-mesh])
    (when ratio
      (let [pct (max 0 (min 100 (* 100 (- 1 ratio))))
            tag (when (seq label) (str " [" label "]"))]
        (println (str "mesh-board:" tag " riferimento vs candidato — fedeltà "
                      (.toFixed pct 1) "%"))))
    reference))

;; ============================================================
;; Dispatch
;; ============================================================

(defn ^:export mesh-board
  "(mesh-board t)                          — show every leaf of t as scaffold, in place
   (mesh-board t {:only [:piece-2 ...]})   — subset by name (map input only)
   (mesh-board foglia candidato)           — compare, :overlay default
   (mesh-board foglia candidato opts)      — compare, opts = {:mode :overlay|:intersection|:diff :label \"...\"}
   Pass-through: always returns its first argument unchanged."
  ([t] (show! t {}))
  ([a b] (if (board-value? b) (compare! a b {}) (show! a b)))
  ([a b opts] (compare! a b opts)))
