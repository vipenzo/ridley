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
            [clojure.string :as cstr]
            [ridley.editor.state :as state]
            [ridley.manifold.core :as manifold]
            [ridley.sdf.core :as sdf]
            [ridley.scene.registry :as registry]
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

;; ── Assembly views (dev-docs/brief-mesh-board-assembly.md) ─────────────

(def ^:private piece-palette
  "One color per leaf, in map order — solid scaffolds and section windows."
  [0x4477aa 0xcc7733 0x33aa66 0xaa44aa 0xccaa22 0x4499cc 0xcc4444 0x77aa33])

(defn- named-leaves
  "[[name mesh] …] for a show call — leaves-of's meshes paired with their
   names (a map's keys, or :piece-N for a vector / :piece for a single mesh)."
  [t opts]
  (let [leaves (leaves-of t opts)
        names (cond
                (map? t) (if (:only opts) (vec (:only opts)) (vec (keys t)))
                (mesh-shaped? t) [:piece]
                :else (mapv #(keyword (str "piece-" (inc %))) (range (count leaves))))]
    (mapv vector names leaves)))

(defn- strip-fast-path
  "A transformed copy must not keep the CSG typed arrays: create-three-mesh
   prefers them over :vertices and would draw the piece where it WAS."
  [mesh]
  (dissoc mesh :ridley.manifold.core/raw-arrays))

(defn- explode-direction
  "Unit vector a leaf moves along in an exploded view: AWAY from its cut
   faces, i.e. minus the sum of its cut anchors' outward headings (tagged
   :cut true by mesh-split — outward through the cut face means TOWARD the
   neighbour, so the piece backs off the other way; measured the wrong sign
   live: the two halves swapped places). No cut anchor (a pin, a whole part)
   or a zero sum (a middle piece with two opposite faces) → nil, the leaf
   stays."
  [mesh]
  (let [hs (keep (fn [[_ a]] (when (:cut a) (:heading a))) (:anchors mesh))
        [x y z :as v] (reduce #(mapv + %1 %2) [0 0 0] hs)
        n (Math/sqrt (+ (* x x) (* y y) (* z z)))]
    (when (> n 1e-6) (mapv #(/ (- %) n) v))))

(defn- explode-leaf
  "The leaf translated by `d` along its explode direction — a VIEW copy; the
   value mesh-board returns is untouched."
  [mesh d]
  (if-let [[dx dy dz] (when (and (number? d) (not (zero? d))) (explode-direction mesh))]
    (-> mesh
        strip-fast-path
        (update :vertices (fn [vs] (mapv (fn [[x y z]] [(+ x (* d dx)) (+ y (* d dy)) (+ z (* d dz))]) vs))))
    mesh))

(defn- resolve-section-pose
  "The pose a :section entry names: a pose map is itself; a keyword is looked
   up in `extra` (the :anchors opt — e.g. layout-anchors' map) and then in
   every leaf's :anchors. Throws naming what is available."
  [x extra leaves]
  (cond
    (and (map? x) (:position x) (:heading x)) x
    (keyword? x)
    (or (get extra x)
        (some (fn [[_ m]] (get-in m [:anchors x])) leaves)
        (throw (js/Error. (str "mesh-board: :section anchor " (pr-str x) " not found — available: "
                               (pr-str (vec (distinct (concat (keys extra)
                                                              (mapcat #(keys (:anchors (second %))) leaves)))))))))
    :else (throw (js/Error. (str "mesh-board: :section wants an anchor name or a pose map — got " (pr-str x))))))

(defn- section-plane
  "The anchor's own plane, Ridley's one convention (slice-mesh, mesh-split):
   heading = normal, through its position. For a cut anchor that is the cut
   face itself, with every pin's cross-section on it; for a pin anchor (its
   heading is the cut normal) the same face. A first version used the plane
   CONTAINING the axis (normal = right) — a lengthwise cut showing only the
   pins of one column, rejected by Vincenzo 2026-09-27: for a lengthwise
   section pass a pose whose heading is the wanted normal. Returns [normal
   offset]."
  [{:keys [position heading]}]
  (let [[nx ny nz] heading [px py pz] position]
    [heading (+ (* nx px) (* ny py) (* nz pz))]))

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

(declare view-key)

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
    (inset/unmount! (view-key ck view)))
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

(def ^:private boolean-views #{:intersection :missing :excess})

(defn- normalize-view
  "One :views entry → {:id :type …}. `:id` is what active-views tracks and the
   inset key derives from: the bare keyword for a default-pair boolean view
   (what the tests and the legacy compare form know), the vector otherwise.
     :intersection            — boolean view on :reference/:candidate (pair form only)
     [:intersection :B :pins] — boolean view between two named elements
     [:section at & {:keys [offset]}] — the assembly cut by at's plane, slid by offset"
  [v pair?]
  (let [[t & args] (if (vector? v) v [v])
        pair-default (fn [] (if pair?
                              {:id t :type t :a :reference :b :candidate :default? true}
                              (throw (js/Error. (str "mesh-board: " (pr-str t) " on an assembly needs two names — "
                                                     "[" (name t) " :a :b]")))))]
    (cond
      (boolean-views t)
      (let [[a b] args]
        (cond (and a b) {:id v :type t :a a :b b}
              (or a b) (throw (js/Error. (str "mesh-board: " (pr-str v) " — a boolean view takes two names")))
              :else (pair-default)))
      (= :section t)
      (let [[at & kv] args
            {:keys [offset]} (apply hash-map kv)]
        (when (nil? at) (throw (js/Error. "mesh-board: [:section at …] needs an anchor name or a pose")))
        {:id v :type :section :at at :offset (or offset 0)})
      :else
      (throw (js/Error. (str "mesh-board: unknown view " (pr-str v)
                             " — use :intersection, :missing, :excess, or [:section at]"))))))

(defn- view-key
  "Inset key for a view id: the legacy `mesh-board:<label>:<view>` for a
   default-pair keyword, a name-joined path otherwise (a pose map reads
   'plane', the :offset keyword is dropped, its value kept)."
  [ck id]
  (if (keyword? id)
    (inset-key ck id)
    (str "mesh-board:" ck ":"
         (cstr/join ":" (keep #(cond (= :offset %) nil
                                     (keyword? %) (name %)
                                     (map? %) "plane"
                                     :else (str %))
                              id)))))

(defn- element
  "The mesh named `nm` in a board, or an error naming what there is."
  [named nm]
  (or (some (fn [[n m]] (when (= n nm) m)) named)
      (throw (js/Error. (str "mesh-board: no element " (pr-str nm) " — available: "
                             (pr-str (mapv first named)))))))

(defn- boolean-content
  "Inset content for a boolean view between two named elements: the
   directional boolean solid, the first element as ghost (Parte 4.2 framing),
   and a label with the volume or 'vuoto' (Parte 4.1) — the legacy text for
   the default pair, the two names added otherwise."
  [named {:keys [type a b default?]}]
  (let [ma (element named a) mb (element named b)
        mesh (view-mesh type ma mb)
        base (view-label type mesh)]
    {:ghost (when (mesh-shaped? ma) ma)
     :highlight (when (seq (:vertices mesh)) mesh)
     :label (if default? base (str (name type) " " (name a) "/" (name b) (subs base (count (name type)))))}))

(defn- section-content
  "Inset content for a section view: every element cut by `at`'s plane
   (heading = normal, Ridley's one convention — a first version used the plane
   CONTAINING the axis, a lengthwise cut showing one column of pins, rejected
   by Vincenzo 2026-09-27) slid by `offset` along the normal; the :ahead
   halves solid, one color per element — the halves whose cut face points
   back toward the −normal side, i.e. the face a viewer there sees. No ghost:
   the whole assembly's edge wireframe over the halves read as three planes
   and a web of pin silhouettes (2026-09-26). The window follows the main
   viewport like every inset (a fixed camera was tried and rejected)."
  [named extra {:keys [at offset]} label-text]
  (let [{:keys [position heading] :as pose} (resolve-section-pose at extra named)
        [nx ny nz :as normal] heading
        [px py pz] (mapv (fn [p h] (+ p (* offset h))) position heading)
        off (+ (* nx px) (* ny py) (* nz pz))]
    (when-not pose (throw (js/Error. "mesh-board: section pose unresolved")))
    {:highlights (vec (keep-indexed
                       (fn [i [_ m]]
                         (when (seq (:faces m))
                           (let [half (:ahead (manifold/split-by-plane m normal off))]
                             (when (seq (:faces half))
                               {:mesh half :color (nth piece-palette (mod i (count piece-palette)))}))))
                       named))
     :label label-text}))

(defn- section-label [{:keys [at offset]} label]
  (str "section " (when (seq label) (str label " "))
       (if (keyword? at) (name at) "plane")
       (when-not (zero? offset) (str " +" offset))))

(defn- views!
  "Mount/refresh one inset window per view, unmount this call's previously
   shown views no longer wanted (a full eval's cleanup is
   reset-compare-views!). Always calls set-content! — never skips a view whose
   result is empty (Parte 4.1: a skipped update left STALE content that read as
   'still overlapping' right when the piece stopped touching)."
  [named extra views label]
  (let [ck (call-key label)
        wanted (set (map :id views))
        previous (get @active-views ck #{})]
    (doseq [id (set/difference previous wanted)]
      (inset/unmount! (view-key ck id)))
    (doseq [{:keys [id type] :as view} views]
      (let [k (view-key ck id)
            content (if (= :section type)
                      (section-content named extra view (section-label view label))
                      (boolean-content named view))]
        (inset/mount! k {:label (:label content)})
        (inset/set-content! k content)))
    (swap! active-views assoc ck wanted)))

(defn- board!
  "The one path behind every mesh-board form (addendum 2026-09-27 of
   brief-mesh-board-assembly.md): a set of NAMED meshes plus views. `pair?`
   marks the legacy compare form ({:reference :candidate}): default boolean
   views, :ghost scaffolds and the printed fidelity, exactly as before.
   Options: :only, :solid/:opacity, :explode, :views, :section (short form),
   :anchors, :label, :ghost. Returns `t` untouched."
  [t {:keys [solid opacity explode views section anchors label ghost] :or {opacity 0.35} :as opts} pair?]
  (let [named (named-leaves t opts)
        views (vec (concat (map #(normalize-view % pair?) (or views (when pair? all-views)))
                           (map #(normalize-view [:section %] pair?)
                                (cond (nil? section) [] (sequential? section) section :else [section]))))
        exploded (mapv (fn [[nm m]] [nm (explode-leaf m explode)]) named)
        scaffolds (map-indexed
                   (fn [i [_ m]]
                     (if solid
                       (-> m strip-fast-path
                           (assoc :scaffold-solid? true
                                  :material {:color (nth piece-palette (mod i (count piece-palette)))
                                             :opacity opacity}))
                       m))
                   exploded)]
    (cond
      (or solid (and (number? explode) (not (zero? explode))) (not pair?))
      (record-scaffolds! (vec scaffolds))
      ;; legacy compare :ghost — reference grey, candidate blue, in place
      (and pair? ghost)
      (record-scaffolds! [(second (first named)) (assoc (second (second named)) :material {:color 0x66ccff})]))
    ;; An assembly view replaces the registered originals: a leaf whose NAME
    ;; is a registered mesh holding the SAME geometry (register stores the
    ;; value's own :vertices vector, so identity on it is exact and free) is
    ;; hidden for this eval — or the opaque solid in place would cover the
    ;; translucent/exploded copy entirely (measured: explode 30 on 40-long
    ;; halves stays inside the original's own volume). The plain ghost
    ;; wireframe show keeps the originals, as it always has.
    (when (or solid (and (number? explode) (not (zero? explode))))
      (doseq [[nm m] named
              :let [reg (registry/get-mesh nm)]
              :when (and reg (identical? (:vertices reg) (:vertices m)))]
        (registry/hide-mesh! nm)))
    (when (or (seq views) pair?)
      (views! named anchors views label))
    (when pair?
      (let [[[_ reference] [_ candidate]] named
            ratio (fidelity-ratio reference candidate)]
        (when ratio
          (let [pct (max 0 (min 100 (* 100 (- 1 ratio))))
                tag (when (seq label) (str " [" label "]"))]
            (state/capture-println (str "mesh-board:" tag " reference vs candidate — fidelity "
                                        (.toFixed pct 1) "%"))))))
    t))

(defn- compare-board!
  "The legacy two-solid form as a board of {:reference :candidate}; returns
   `reference`, its first argument, as always."
  [reference candidate opts]
  (when (manifold/split-composite? reference)
    (throw (composite-error "mesh-board")))
  (when-not (board-value? reference)
    (throw (js/Error. (str "mesh-board: unsupported comparison reference — got " (pr-str (type reference))))))
  (when-not (board-value? candidate)
    (throw (js/Error. (str "mesh-board: unsupported comparison candidate — got " (pr-str (type candidate))))))
  (board! (array-map :reference reference :candidate candidate) opts true)
  reference)

;; ============================================================
;; Dispatch
;; ============================================================

(defn ^:export mesh-board
  "One display directive over a SET of named meshes plus views; the two-solid
   comparison is the pair case (addendum 2026-09-27).
   (mesh-board t)                          — show every leaf of t as scaffold, in place
   (mesh-board t {:only [:piece-2 ...]})   — subset by name (map input only)
   (mesh-board t {:solid true :opacity 0.35 :explode 30
                  :views [[:section :cut :offset 8] [:intersection :B :pins]]
                  :section :cut :anchors L :label \"…\"})
                                           — assembly views: translucent solids, exploded
                                             along the cut anchors, section windows,
                                             boolean windows between two named elements
   (mesh-board foglia candidato)           — compare: intersection/missing/excess windows + printed fidelity
   (mesh-board foglia candidato opts)      — compare, opts = {:views [...] :ghost bool :label \"...\"}
                                             (:views also takes [:section pose])
   Pass-through: always returns its first argument unchanged."
  ([t] (board! t {} false))
  ([a b] (cond
           (board-value? b) (compare-board! a b {})
           ;; a composite in the second slot is a comparison candidate the user
           ;; forgot to convert — but it is also a map, so the show branch would
           ;; take it for an opts map, read no :only, and quietly display `a`
           ;; alone. Never a silent no-op.
           (manifold/split-composite? b) (throw (composite-error "mesh-board"))
           :else (board! a b false)))
  ([a b opts] (compare-board! a b opts)))
