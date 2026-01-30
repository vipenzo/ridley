# Task: Extend Registry to Support Shapes for AI Integration

## Context

Ridley's AI voice extension needs visibility into all registered objects (meshes, shapes, paths) to understand what the user can reference. Currently, `def` creates SCI symbols invisible to the AI, while `register` adds objects to a registry the AI can query.

The registry already supports meshes, paths, and panels. We need to add shape support and expose query functions for AI context.

## Current State

### registry.cljs atoms:
```clojure
(defonce ^:private scene-meshes (atom []))   ; [{:mesh data :name kw :visible bool}]
(defonce ^:private scene-paths (atom []))    ; [{:path data :name kw :visible bool}]
(defonce ^:private scene-panels (atom []))   ; [{:panel data :name kw :visible bool}]
```

### Type detection (already in codebase):
- mesh: `(and (map? x) (:vertices x) (:faces x))`
- path: `(and (map? x) (= :path (:type x)))`
- panel: `(and (map? x) (= :panel (:type x)))`
- shape: `(and (map? x) (= :shape (:type x)))` — in shape.cljs

## Required Changes

### 1. registry.cljs — Add shape registry

Add after `scene-panels` definition:

```clojure
;; Registered shapes: [{:shape data :name keyword} ...]
;; Shapes have no visibility concept (not directly renderable)
(defonce ^:private scene-shapes (atom []))
```

Add shape registration functions (similar pattern to paths):

```clojure
(defn- find-shape-index
  "Find index of shape entry by name."
  [name]
  (first (keep-indexed (fn [i entry] (when (= (:name entry) name) i)) @scene-shapes)))

(defn register-shape!
  "Register a named shape. Returns the shape."
  [name shape]
  (when (and name shape (map? shape) (= :shape (:type shape)))
    (if-let [idx (find-shape-index name)]
      ;; Update existing
      (swap! scene-shapes assoc idx {:shape shape :name name})
      ;; Add new
      (swap! scene-shapes conj {:shape shape :name name}))
    shape))

(defn get-shape
  "Get shape data by name."
  [name]
  (:shape (first (filter #(= (:name %) name) @scene-shapes))))

(defn shape-names
  "Get names of all registered shapes."
  []
  (vec (keep :name @scene-shapes)))
```

Update `clear-all!` to also clear shapes:

```clojure
(defn clear-all!
  "Clear all meshes, lines, paths, panels, and shapes. Called on code re-evaluation."
  []
  (reset! scene-meshes [])
  (reset! scene-lines [])
  (reset! scene-paths [])
  (reset! scene-panels [])
  (reset! scene-shapes []))
```

### 2. repl.cljs — Extend register macro

In the `macro-defs` string, find the `register` macro and add shape detection.

Current dispatch in register macro checks:
1. Panel (`:type :panel`)
2. Single mesh (has `:vertices`)
3. Vector of meshes
4. Map of meshes
5. Path (`:type :path`)

Add shape check. Update the `cond` inside register macro:

```clojure
;; After the panel case and before mesh case, add:

;; Shape (has :type :shape)
(and (map? value#) (= :shape (:type value#)))
(register-shape! name-kw# value#)
```

The full updated cond should be:
```clojure
(cond
  ;; Single mesh (has :vertices)
  (and (map? value#) (:vertices value#))
  (let [already-registered# (contains? (set (registered-names)) name-kw#)]
    (register-mesh! name-kw# value#)
    (when-not already-registered#
      (show-mesh! name-kw#)))

  ;; Shape (has :type :shape)
  (and (map? value#) (= :shape (:type value#)))
  (register-shape! name-kw# value#)

  ;; Vector of meshes - add each anonymously
  (mesh-vector? value#)
  (doseq [mesh# value#]
    (add-mesh! mesh#))

  ;; Map of meshes - add each anonymously
  (mesh-map? value#)
  (doseq [[_# mesh#] value#]
    (add-mesh! mesh#))

  ;; Path (has :type :path)
  (and (map? value#) (= :path (:type value#)))
  (do
    (register-path! name-kw# value#)
    (show-path! name-kw#)))
```

### 3. repl.cljs — Expose new bindings

Add to `base-bindings`:

```clojure
'register-shape!    registry/register-shape!
'get-shape          registry/get-shape
'shape-names        registry/shape-names
```

### 4. repl.cljs — Add AI context query functions

Add these helper functions that return registry state for AI context.

In `base-bindings`, add:

```clojure
;; AI context helpers - return registered object names by type
'registered-meshes  registry/registered-names    ; already exists as registered-names
'registered-shapes  registry/shape-names
'registered-paths   (fn [] (vec (keep :name @registry/scene-paths)))  ; need to expose or add function
```

Actually, `scene-paths` is private. Better to add a `path-names` function to registry.cljs:

```clojure
(defn path-names
  "Get names of all registered paths."
  []
  (vec (keep :name @scene-paths)))
```

Then in base-bindings:
```clojure
'registered-paths   registry/path-names
```

## Summary of Changes

### registry.cljs:
1. Add `scene-shapes` atom
2. Add `find-shape-index`, `register-shape!`, `get-shape`, `shape-names` functions
3. Add `path-names` function
4. Update `clear-all!` to clear shapes

### repl.cljs:
1. Add shape case to `register` macro in `macro-defs`
2. Add to `base-bindings`:
   - `'register-shape!`
   - `'get-shape`
   - `'shape-names`
   - `'path-names`
   - `'registered-shapes`
   - `'registered-paths`

## Testing

After implementation, these should work:

```clojure
;; Register a shape
(register my-circle (circle 10))
(register my-rect (rect 20 10))

;; Query registered shapes
(shape-names)  ;; => [:my-circle :my-rect]

;; Use registered shape
(extrude my-circle (f 20))

;; AI context queries
(registered-meshes)  ;; => [:torus :cube]
(registered-shapes)  ;; => [:my-circle :my-rect]
(registered-paths)   ;; => [:spiral :square-path]
```

## Files to Modify

1. `src/ridley/scene/registry.cljs`
2. `src/ridley/editor/repl.cljs`
