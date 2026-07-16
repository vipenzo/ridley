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
  (:require [clojure.set :as set]
            [ridley.editor.state :as state]
            [ridley.manifold.core :as manifold]
            [ridley.sdf.core :as sdf]
            [ridley.viewport.inset :as inset]))

(defn- mesh-shaped? [v] (and (map? v) (:vertices v)))

(defn- composite-error
  "A raw mesh-split composite is not a tree of named pieces, and mesh-board says
   so by name (brief-split-tree.md Part 3) — the conversion is `split-tree`'s
   job, explicit in the user's source, never an implicit coercion in here. The
   error exists because the alternative is obscure, not loud: a one-cut
   composite would sail through as a two-piece tree named :behind/:ahead, and
   from two cuts up the nested :ahead map reaches the scaffolds AS a mesh."
  [where]
  (js/Error. (str where ": got a raw mesh-split composite ({:behind … :ahead …}), not a tree "
                  "of named pieces — wrap it in (split-tree …)")))

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
   name is a readable error naming it (brief Part 1's fix applies here too).

   The composite check comes first: it is a map too, and a plausible-looking
   one."
  [t {:keys [only]}]
  (cond
    (manifold/split-composite? t)
    (throw (composite-error "mesh-board"))

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
;; Compare: (mesh-board foglia candidato {:views [...] :ghost bool :label ...})
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

(def ^:private all-views [:intersection :missing :excess])

(defn- view-mesh
  "The view's directional boolean result — two diffs (not the symmetric
   difference) plus the intersection, per brief-mesh-board-views.md Parte 2:
   :missing (reference − candidate, what the candidate doesn't cover yet) and
   :excess (candidate − reference, where the candidate overshoots) are
   complementary tuning signals, not one merged blob. nil if the boolean op
   is unavailable (WASM not initialized) — the caller degrades gracefully."
  [view reference candidate]
  (case view
    :intersection (manifold/intersection reference candidate)
    :missing (manifold/difference reference candidate)
    :excess (manifold/difference candidate reference)
    (throw (js/Error. (str "mesh-board: unknown view " (pr-str view)
                           " — use :intersection, :missing, or :excess")))))

;; call-label -> #{view-keyword shown by the LAST compare! call under that
;; label} — lets a compare! call unmount its own now-unwanted views (:views
;; shrunk on re-eval) without touching a differently-labeled call's windows.
;; A full eval's stale-call cleanup (a mesh-board call removed from source
;; entirely) is handled by reset-compare-views!, called once per full eval
;; from repl.cljs — never by compare! itself, which only ever runs when its
;; own call is still in the source.
(defonce ^:private active-views (atom {}))

(defn- call-key [label] (if (seq label) label "default"))
(defn- inset-key [ck view] (str "mesh-board:" ck ":" (name view)))

(defn reset-compare-views!
  "Unmount every comparison-view inset window currently tracked, across all
   labels. Called once per FULL evaluation (repl.cljs) before user code runs
   — mirrors reset-scene-accumulator!'s replace semantics for scaffolds/
   stamps: a mesh-board call removed from the source simply never re-mounts
   its windows this eval. An incremental REPL eval does NOT call this — a
   single REPL command only ever owns and reconciles its own label's views."
  []
  (doseq [[ck views] @active-views
          view views]
    (inset/unmount! (inset-key ck view)))
  (reset! active-views {}))

(defn- view-label
  "Header text for a view's inset window — the view name plus either its
   volume or an explicit 'vuoto' (Parte 4.1: empty is an answer, not a
   non-event — a piece that stopped overlapping is exactly what the user
   needs to see, not the previous eval's stale content)."
  [view mesh]
  (str (name view) ": "
       (if (seq (:vertices mesh))
         (str (.toFixed (:volume (manifold/get-mesh-status mesh)) 1) " mm³")
         "vuoto")))

(defn- update-compare-insets!
  "Mount/refresh one inset window per wanted view, unmount any of this call's
   previously-shown views no longer wanted. Always calls set-content! — never
   skips a view whose boolean result is empty or unavailable (Parte 4.1: a
   skipped update left the inset showing STALE content from the last
   non-empty result, which reads as 'still overlapping' right when the piece
   the user just moved stopped touching). Also passes `reference` as the
   inset's ghost wireframe when it's a real mesh (Parte 4.2): spatial
   anchoring for the diff, and — since set-content! frames on ghost's bounds
   when present — a framing that stays stable across re-evals as long as
   reference doesn't change, instead of jumping with every candidate tweak."
  [label reference candidate views]
  (let [ck (call-key label)
        wanted (set views)
        previous (get @active-views ck #{})
        ghost (when (mesh-shaped? reference) reference)]
    (doseq [view (set/difference previous wanted)]
      (inset/unmount! (inset-key ck view)))
    (doseq [view wanted]
      (let [k (inset-key ck view)
            mesh (view-mesh view reference candidate)
            has-result? (seq (:vertices mesh))]
        (inset/mount! k {:label (name view)})
        (inset/set-content! k {:ghost ghost
                               :highlight (when has-result? mesh)
                               :label (view-label view mesh)})))
    (swap! active-views assoc ck wanted)))

(defn- compare!
  [reference candidate {:keys [views ghost label] :or {views all-views ghost false}}]
  (when (manifold/split-composite? reference)
    (throw (composite-error "mesh-board")))
  (when-not (board-value? reference)
    (throw (js/Error. (str "mesh-board: unsupported comparison reference — got " (pr-str (type reference))))))
  (when-not (board-value? candidate)
    (throw (js/Error. (str "mesh-board: unsupported comparison candidate — got " (pr-str (type candidate))))))
  (doseq [view views]
    (when-not (contains? (set all-views) view)
      (throw (js/Error. (str "mesh-board: unknown :views entry " (pr-str view)
                             " — use :intersection, :missing, or :excess")))))
  (update-compare-insets! label reference candidate views)
  (when ghost
    (record-scaffolds! [reference (assoc candidate :material {:color 0x66ccff})]))
  (let [ratio (fidelity-ratio reference candidate)]
    (when ratio
      (let [pct (max 0 (min 100 (* 100 (- 1 ratio))))
            tag (when (seq label) (str " [" label "]"))]
        (state/capture-println (str "mesh-board:" tag " riferimento vs candidato — fedeltà "
                                    (.toFixed pct 1) "%")))))
  reference)

;; ============================================================
;; Dispatch
;; ============================================================

(defn ^:export mesh-board
  "(mesh-board t)                          — show every leaf of t as scaffold, in place
   (mesh-board t {:only [:piece-2 ...]})   — subset by name (map input only)
   (mesh-board foglia candidato)           — compare: intersection/missing/excess inset windows + printed fidelity
   (mesh-board foglia candidato opts)      — compare, opts = {:views [...] :ghost bool :label \"...\"}
   Pass-through: always returns its first argument unchanged."
  ([t] (show! t {}))
  ([a b] (cond
           (board-value? b) (compare! a b {})
           ;; a composite in the second slot is a comparison candidate the user
           ;; forgot to convert — but it is also a map, so the show! branch would
           ;; take it for an opts map, read no :only, and quietly display `a`
           ;; alone. Never a silent no-op.
           (manifold/split-composite? b) (throw (composite-error "mesh-board"))
           :else (show! a b)))
  ([a b opts] (compare! a b opts)))
