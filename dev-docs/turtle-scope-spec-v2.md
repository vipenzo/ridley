# Ridley — Turtle Scope Refactor v2

## Spec for Claude Code

### Motivation

Ridley currently uses a global mutable turtle state (an atom) that all commands (`f`, `th`, `tv`, `tr`, etc.) read and write implicitly. This creates several problems:

1. **Impure functions** — `f`, `th` etc. are side-effecting, which is un-Clojure-like
2. **push-state/pop-state** — an imperative workaround for what should be lexical scoping
3. **Implicit coupling** — combining `th` and `tv` in sequence accumulates an implicit `tr` (roll), which surprises users (the spiral text problem: alternating `th`/`tv` rotates letters unexpectedly)
4. **No isolation** — code inside `extrude`, `path`, `attach` must carefully rebind the global state, leading to fragile macro implementations
5. **Mixed concerns** — the turtle state currently bundles navigation (pose, settings) with output (lines, meshes, stamps), making scoping semantics unclear

### Two Core Changes

**Change 1: Separate turtle state from scene output.**
The turtle state becomes pure navigation (pose + settings). Visual output (pen traces, anonymous meshes, stamps) moves to a shared scene accumulator.

**Change 2: Replace global turtle with explicit scopes.**
A `turtle` macro establishes a lexical scope with its own turtle state via SCI dynamic binding. `push-state`/`pop-state` are removed.

---

## Architecture: State Separation

### Before (Current)

Everything in one big turtle state map, held in a single global atom:

```clojure
;; Current: one atom holds everything
{:position [x y z]
 :heading [x y z]
 :up [x y z]
 :pen-mode :on
 :lines [...]                    ;; ← output mixed with navigation
 :meshes [...]                   ;; ← output mixed with navigation
 :stamps [...]                   ;; ← output mixed with navigation
 :resolution {:mode :n :value 16}
 :joint-mode :flat
 :color 0xffffff
 :material {...}
 :anchors {}
 :state-stack [...]              ;; ← push/pop mechanism
 ;; ... sweep state, loft state, etc.
}
```

### After (Proposed)

Two separate concerns:

```clojure
;; 1. TURTLE STATE (scoped by `turtle` macro)
;; Pure navigation: where am I, where am I going, how do I create things
{:position [x y z]
 :heading [x y z]
 :up [x y z]
 :pen-mode :on                   ;; controls whether movement adds lines to accumulator
 :resolution {:mode :n :value 16}
 :joint-mode :flat
 :color 0xffffff
 :material {:metalness 0.5 :roughness 0.5}
 :anchors {}
 :preserve-up false
 :reference-up nil}              ;; captured when :preserve-up scope is entered

;; 2. SCENE ACCUMULATOR (shared across all turtle scopes within one eval)
;; Output: what has been produced
{:lines [...]                    ;; pen traces from f/goto when pen-mode is :on
 :meshes [...]                   ;; anonymous meshes (not registered)
 :stamps [...]}                  ;; shape previews from (stamp ...)
```

### Why This Split?

**Lines, meshes, and stamps are output, not state.** They're the *product* of turtle movement, like a snail's trail. When you enter a `turtle` scope and move with pen :on, those lines should be visible — they don't "belong" to the inner scope. Separating them eliminates the problem Code identified: no output is ever lost when a turtle scope exits.

**The turtle state is pure navigation.** Every field in the turtle state answers a question about *how the turtle behaves*: where is it, which direction does it face, what resolution do its circles have, what color are the meshes it creates. This is exactly what should be scoped.

**Sweep/loft state** (`:sweep-rings`, `:sweep-base-shape`, `:loft-transform-fn`, etc.) is transient operation state — it exists only during an `extrude`/`loft` call and lives on the internal turtle that those operations create. It never appears in the user-facing turtle state and is unaffected by this refactor.

---

## SCI Dynamic Binding

### Critical Implementation Detail

The user code runs in SCI, not raw ClojureScript. SCI has its own dynamic var system. The turtle state binding must use SCI's mechanism:

```clojure
;; In state.cljs (or wherever the var is defined):
(def turtle-state-var
  (sci/new-dynamic-var '*turtle-state* (atom (make-turtle))))

;; In the SCI context setup (repl.cljs):
;; Register *turtle-state* as a SCI namespace var bound to turtle-state-var

;; The turtle macro (SCI macro in repl.cljs):
;; Uses sci/binding to create a new binding for *turtle-state*
```

All turtle commands in implicit.cljs (or wherever they live) dereference the SCI var to get the current atom:

```clojure
;; Pattern for turtle commands:
(defn implicit-f [dist]
  (let [turtle-atom @turtle-state-var]    ;; @sci-var → current binding's atom
    (swap! turtle-atom turtle/move-forward dist)
    ;; If pen-mode is :on, also append line to scene accumulator
    (when (= :on (:pen-mode @turtle-atom))
      (swap! scene-accumulator update :lines conj new-line))))

(defn implicit-th [angle]
  (let [turtle-atom @turtle-state-var]
    (swap! turtle-atom turtle/turn-horizontal angle)))
```

The scene accumulator is a separate atom, NOT a SCI dynamic var — it's shared across all scopes within one evaluation:

```clojure
;; In state.cljs:
(def scene-accumulator (atom {:lines [] :meshes [] :stamps []}))

;; Cleared at the start of each definitions panel eval
;; Cleared at the start of each REPL command (for lines only — meshes persist)
```

---

## `turtle` Macro

### Syntax

```clojure
;; No options — inherit current pose + settings
(turtle & body)

;; Boolean flags as keyword sugar (combinable, order doesn't matter)
(turtle :reset & body)                   ; origin [0 0 0], heading +X, up +Z
(turtle :preserve-up & body)             ; rotations don't accumulate roll
(turtle :reset :preserve-up & body)      ; combined

;; Vector shorthand — specific position, default orientation
(turtle [x y z] & body)

;; Options map — full control (combinable with keyword flags)
(turtle {:pos [x y z]} & body)
(turtle {:pos [x y z] :heading [hx hy hz] :up [ux uy uz]} & body)
(turtle {:preserve-up true} & body)      ; equivalent to :preserve-up flag
(turtle :reset {:pos [10 0 0]} & body)   ; reset + reposition
```

### Argument Parsing

The macro consumes arguments from the front until it hits the body:

1. **Known keyword** (`:reset`, `:preserve-up`): consumed as boolean flag
2. **Vector**: consumed as position shorthand
3. **Map with at least one known key** (`:pos`, `:heading`, `:up`, `:preserve-up`, `:reset`): consumed as options
4. **Anything else**: start of body

A map without any known key is treated as body (e.g. a hashmap literal expression). This makes the parser unambiguous — no valid turtle option can be confused with a body form.

### Semantics

1. **Creates a new atom** containing a turtle state initialized from the options (or cloned from the enclosing scope's turtle)
2. **Binds it** via `sci/binding` to `*turtle-state*` so all turtle commands inside `body` operate on it
3. **Evaluates body** forms sequentially (like `do`)
4. **Returns the last expression** of body
5. **The local turtle atom is abandoned** on exit — the enclosing scope's turtle is unaffected
6. **Scene output is unaffected** — lines, anonymous meshes, and stamps go to the shared scene accumulator regardless of turtle scope depth

### What Gets Inherited (Default, No Flags)

When `(turtle ...)` is called without `:reset` or explicit position, the new turtle state is a **clone** of the current scope's turtle state. All fields are copied:

- position, heading, up
- pen-mode
- resolution, joint-mode
- color, material
- anchors
- preserve-up, reference-up

This means settings "flow down" into child scopes naturally:

```clojure
(resolution :n 32)
(color 0xff0000)
(turtle                                  ;; inherits resolution :n 32 and red color
  (register part (extrude (circle 10) (f 20))))
```

### What `:reset` Does

`:reset` creates a fresh turtle at origin with default settings. It does NOT inherit anything from the parent:

```clojure
(resolution :n 32)
(turtle :reset                           ;; fresh turtle: resolution back to default :n 16
  (register part (sphere 10)))
```

### Top-Level Implicit Scope

The entire user script (definitions panel) runs inside an implicit `(turtle :reset ...)`. The REPL maintains a persistent top-level atom between commands (so turtle state carries over across REPL entries, as it does today).

The scene accumulator is cleared:
- **Definitions panel eval**: lines, anonymous meshes, and stamps all cleared
- **REPL command**: lines cleared, anonymous meshes cleared, stamps cleared (registered objects persist in the scene registry as usual)

---

## Mode: `:preserve-up`

### The Problem

When you alternate `th` (yaw) and `tv` (pitch), each rotation applies relative to the current local frame. `tv` rotates the heading *and* implicitly drags the up vector with it. After several `th`/`tv` pairs, the up vector drifts from its original direction, producing unexpected roll.

The spiral text case:
```clojure
;; CURRENT: tv introduces implicit roll, letters rotate on themselves
(def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5))))
;; Workaround: use (u 0.5) instead of (tv 0.5)

;; WITH PRESERVE-UP: tv changes trajectory but up stays coherent
(turtle :preserve-up
  (def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))))
;; Letters stay upright, trajectory still curves upward
```

### Semantics

When `:preserve-up` is active on the current turtle:

- **`th` (yaw)**: rotates heading around `reference-up` (the up vector captured when the `:preserve-up` scope was entered), not around the turtle's current local up
- **`tv` (pitch)**: rotates heading around the turtle's right axis as usual, then **re-orthogonalizes** up to align as closely as possible with `reference-up`
- **`tr` (roll)**: works as usual — explicit roll is still intentional

The net effect: the turtle's up vector stays aligned with the original up direction throughout the scope, unless explicitly changed with `tr`. Heading can point in any direction (including upward/downward from `tv`), but the "which way is up for this turtle" answer remains stable.

### Implementation

```clojure
;; When entering a :preserve-up scope, capture reference-up from current state:
{:preserve-up true
 :reference-up [0 0 1]}   ;; copy of :up at scope entry

;; Modified th (in turtle/core.cljs pure functions):
(defn turn-horizontal [{:keys [preserve-up reference-up heading up] :as state} angle]
  (if preserve-up
    (let [new-heading (rotate-vec heading reference-up (deg->rad angle))
          new-up (orthogonalize reference-up new-heading)]
      (assoc state :heading (normalize new-heading) :up (normalize new-up)))
    ;; Standard: rotate heading around local up
    (let [new-heading (rotate-vec heading up (deg->rad angle))]
      (assoc state :heading (normalize new-heading)))))

;; Modified tv:
(defn turn-vertical [{:keys [preserve-up reference-up heading up] :as state} angle]
  (let [right (normalize (cross heading up))
        new-heading (rotate-vec heading right (deg->rad angle))]
    (if preserve-up
      ;; Re-orthogonalize up toward reference-up
      (let [new-up (orthogonalize reference-up new-heading)]
        (assoc state :heading (normalize new-heading) :up (normalize new-up)))
      ;; Standard: up rotates with heading
      (let [new-up (rotate-vec up right (deg->rad angle))]
        (assoc state :heading (normalize new-heading) :up (normalize new-up))))))

;; Helper: project ref-up onto the plane perpendicular to heading
(defn orthogonalize [reference-up heading]
  (let [proj (vec-scale heading (dot reference-up heading))]
    (normalize (vec-sub reference-up proj))))
```

### Interaction with `path`

Paths record commands as data (`{:cmd :th :args [8.6]}`). When a path is replayed inside `extrude`, the replay turtle is initialized from the current scope's turtle state — which includes `:preserve-up` and `:reference-up` if the scope has them.

**However**, `:preserve-up` is a property of the turtle that *executes* the path, not of the path itself. This is correct behavior:

```clojure
;; Path is just data — no preserve-up baked in
(def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5))))

;; Extrude runs the path on a turtle that inherits current scope's settings
(turtle :preserve-up
  (register text (text-on-path "Hello" spiral :size 6 :depth 1.2)))
;; The extrude's internal turtle inherits :preserve-up → letters stay upright

;; Same path, different behavior without preserve-up:
(register text2 (text-on-path "Hello" spiral :size 6 :depth 1.2))
;; Standard rotation accumulation → letters may roll
```

This means `extrude`, `loft`, `text-on-path` etc. must initialize their internal turtle by cloning the current scope's turtle (which they effectively already do by reading the current pose — they just need to also copy `:preserve-up` and `:reference-up`).

---

## What Gets Removed

### Removed from User API
- `push-state` — replaced by `(turtle ...)`
- `pop-state` — replaced by exiting the `turtle` scope
- `clear-stack` — no stack to clear
- Interactive `(attach mesh)` ... `(detach)` across REPL commands — replaced by `(attach mesh (f 10) (th 45))` single-expression form which already works

### Removed from Turtle State
- `:state-stack` — no longer needed
- `:lines` — moved to scene accumulator
- `:meshes` — moved to scene accumulator (anonymous meshes only; registered meshes are in the scene registry)
- `:stamps` — moved to scene accumulator

### `reset` Stays as Convenience

`reset` mutates the current scope's turtle in-place (repositions it). This is still useful inside a scope:

```clojure
(turtle
  (f 50) (th 90)
  (register part-a (box 20))
  (reset)                                ;; back to origin within this scope
  (f 50) (th -90)
  (register part-b (box 20)))
```

`reset` does NOT affect the scene accumulator.

---

## Interaction with Existing Systems

### `path`

**No API change.** `path` already creates an internal recording turtle. It records commands as data. When the path is later replayed inside `extrude`/`loft`, the replay turtle inherits the current scope's settings (including `:preserve-up`).

### `extrude`, `loft`, `extrude-closed`, `revolve`, `bloft`

**No API change.** These operations create an internal turtle initialized at the current scope's turtle position and settings. Movements inside the operation modify only that internal turtle. The resulting mesh is returned as a value.

The change: the internal turtle must now clone `:preserve-up` and `:reference-up` from the current scope's turtle, in addition to pose and other settings it already copies.

### `attach` / `attach!`

**No API change for the macro form.** The macro form `(attach mesh (f 10) (th 45))` and `(attach! :name (f 10))` continue to work. Internally they create a turtle scope positioned at the mesh's creation pose and run the body inside it.

**Interactive form removed.** The pattern of calling `(attach mesh)` then separate REPL commands then `(detach)` is no longer supported. This was a vestige of early development. The macro form covers all use cases:

```clojure
;; This works (and is the only form):
(register b (attach b (f 10) (th 45)))
(attach! :b (f 10) (th 45))

;; This no longer works:
;; (attach b)     ← across REPL commands
;; (f 10)
;; (detach)
```

### `attach-face` / `clone-face`

**No API change.** These already work as single-expression macros. Internally they use a turtle scope at the face position with heading = face normal.

### `with-path`

**No API change.** Resolves marks relative to the current scope's turtle position. Reads from the current `*turtle-state*` binding.

### `anim!` / `span`

**No API change.** Animation spans contain turtle commands that are preprocessed into frame arrays at definition time. The preprocessing runs on an internal turtle that is independent of the user's turtle scope.

### Scene Registry (`register`, `show`, `hide`, etc.)

**No change.** The scene registry remains global. `register` writes to the global registry regardless of turtle scope depth. This is correct — the registry is "what exists in the world," not "where the turtle is."

### `color`, `material`, `resolution`, `joint-mode`, `pen`

**No API change.** These modify the current scope's turtle state. They're inherited by child scopes and don't leak to parent scopes:

```clojure
(color 0xff0000)                         ;; red for this scope
(turtle
  (color 0x00ff00)                       ;; green inside this scope only
  (register green-box (box 20)))
(register red-box (box 20))              ;; still red out here
```

### Pen and Lines

`pen :on` / `pen :off` is a turtle setting (scoped). But the *lines themselves* go to the scene accumulator (shared). So:

```clojure
(pen :on)
(f 10)                                   ;; line added to scene accumulator
(turtle
  (f 20)                                 ;; line added to same scene accumulator
  (pen :off)
  (f 30))                                ;; no line (pen off in this scope)
(f 40)                                   ;; line added (pen still on in outer scope)
;; Scene accumulator has 3 line segments total
```

---

## `turtle` Replacing `push-state`/`pop-state`

### Migration Examples

**Branching (L-system):**
```clojure
;; BEFORE
(defn branch [depth length]
  (when (pos? depth)
    (f length)
    (push-state)
    (th 30)
    (branch (dec depth) (* length 0.7))
    (pop-state)
    (push-state)
    (th -30)
    (branch (dec depth) (* length 0.7))
    (pop-state)))

;; AFTER
(defn branch [depth length]
  (when (pos? depth)
    (f length)
    (turtle (th 30) (branch (dec depth) (* length 0.7)))
    (turtle (th -30) (branch (dec depth) (* length 0.7)))))
```

**Temporary positioning:**
```clojure
;; BEFORE
(push-state)
(f 50) (tv 90)
(register column (extrude (circle 10) (f 40)))
(pop-state)

;; AFTER
(turtle
  (f 50) (tv 90)
  (register column (extrude (circle 10) (f 40))))
```

**Multiple parts from same origin:**
```clojure
;; BEFORE
(push-state)
(th 45) (f 30)
(register arm-r (cyl 3 20))
(pop-state)
(push-state)
(th -45) (f 30)
(register arm-l (cyl 3 20))
(pop-state)

;; AFTER
(turtle (th 45) (f 30) (register arm-r (cyl 3 20)))
(turtle (th -45) (f 30) (register arm-l (cyl 3 20)))
```

**Preserve-up spiral text:**
```clojure
;; BEFORE (workaround with u instead of tv)
(def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (u 0.5))))

;; AFTER (clean: tv does what you'd expect)
(turtle :preserve-up
  (def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))))
```

---

## Accessing Turtle State

```clojure
(get-turtle)                     ;; full turtle state map from current scope
(turtle-position)                ;; [x y z]
(turtle-heading)                 ;; [x y z]
(turtle-up)                      ;; [x y z]
```

These read from the current `*turtle-state*` binding.

---

## Implementation Plan

### Phase 1: State Separation

Separate the turtle state atom from the scene accumulator. This is the prerequisite for everything else and can be done without changing any user API.

1. Create `scene-accumulator` atom in state.cljs: `{:lines [] :meshes [] :stamps []}`
2. Remove `:lines`, `:meshes`, `:stamps` from the turtle state map (in `make-turtle`)
3. Update every function that appends lines/meshes/stamps to write to the scene accumulator instead of the turtle state:
   - `implicit-f` (and any movement that draws lines when pen is on)
   - `implicit-goto` (draws line when pen is on)
   - Primitive creators that add anonymous meshes
   - `implicit-stamp`
   - Any other function in implicit.cljs that does `(swap! turtle-atom update :lines conj ...)` or similar
4. Update the rendering pipeline (in core.cljs or wherever meshes/lines are collected for Three.js) to read from the scene accumulator instead of the turtle state
5. Update eval clearing logic: clear scene accumulator at appropriate points (start of definitions eval, start of REPL command)
6. **Test**: everything should work exactly as before — this is a pure refactor with no API changes

### Phase 2: SCI Dynamic Var + `turtle` Macro

Introduce the `turtle` macro. Old API (`push-state`/`pop-state`) still works during this phase.

1. Create `turtle-state-var` as a SCI dynamic var: `(sci/new-dynamic-var '*turtle-state* (atom (make-turtle)))`
2. Migrate all ~30 functions in implicit.cljs from `(swap! turtle-atom ...)` to `(swap! @turtle-state-var ...)` — mechanical but must be thorough. Full list of functions to migrate:
   - Movement: `implicit-f`, `implicit-u`, `implicit-d`, `implicit-rt`, `implicit-lt`
   - Rotation: `implicit-th`, `implicit-tv`, `implicit-tr`
   - Pen: `implicit-pen`
   - Settings: `implicit-resolution`, `implicit-joint-mode`, `implicit-color`, `implicit-material`, `implicit-reset-material`
   - Anchors: `implicit-mark`, `implicit-goto`, `implicit-look-at`, `implicit-path-to`
   - State queries: `implicit-get-turtle`, `implicit-turtle-position`, `implicit-turtle-heading`, `implicit-turtle-up`, `implicit-attached?`, `implicit-last-mesh`
   - Reset: `implicit-reset`
   - Primitives: any function that reads turtle state for positioning (`pure-box`, `circle-with-resolution`, `sphere-with-resolution`, `cyl-with-resolution`, `cone-with-resolution`)
   - Also in state.cljs: `get-turtle-resolution`, `get-turtle-joint-mode`, `get-turtle-pose`, `last-mesh`
3. Implement `turtle` macro as SCI macro in repl.cljs:
   ```clojure
   ;; Pseudo-code for the SCI macro:
   (defmacro turtle [& args]
     (let [{:keys [opts body]} (parse-turtle-args args)]
       `(sci/binding [*turtle-state* (atom (init-turtle ~opts @*turtle-state*))]
          ~@body)))
   ```
4. Wrap definitions panel eval in `(sci/binding [*turtle-state* (atom (make-turtle))] ...)`
5. REPL: maintain a persistent top-level atom that carries across REPL commands (same as current behavior)
6. **Test**: both `push-state`/`pop-state` AND `turtle` should work. Existing scripts unchanged.

### Phase 3: Implement `:preserve-up`

1. Add `:preserve-up` (boolean) and `:reference-up` (vec3 or nil) to turtle state
2. `init-turtle` for `:preserve-up` option: set `:preserve-up true`, capture `:reference-up` from initial `:up`
3. Modify pure `turn-horizontal` in turtle/core.cljs: when `:preserve-up`, rotate around `:reference-up`
4. Modify pure `turn-vertical` in turtle/core.cljs: when `:preserve-up`, re-orthogonalize up toward `:reference-up`
5. Ensure `extrude`/`loft`/`text-on-path` internal turtles copy `:preserve-up` and `:reference-up` from the current scope
6. **Test**: spiral text with `(turtle :preserve-up (def spiral (path ...)))`

### Phase 4: Clean Up

Remove old mechanisms:

1. Remove `push-state`, `pop-state`, `clear-stack` from SCI bindings
2. Remove `:state-stack` from turtle state
3. Remove interactive `attach`/`detach` (the `implicit-attach-interactive` and `implicit-detach` that mutate global state across REPL commands)
4. Keep `attach`/`attach!` macro forms (which use `turtle` internally now)
5. Refactor `attach`/`attach!` internals to use `turtle` scope if they were using push/pop. Note: the macro forms in impl.cljs may already create their own turtle internally — verify and simplify where possible.
6. Remove the global `turtle-atom` if it's no longer referenced anywhere
7. **Test**: all examples from Examples.md, all edge cases

---

## Test Cases

### Basic scoping
```clojure
(f 10)                                   ;; turtle at [10 0 0]
(turtle (f 20))                          ;; inner turtle at [30 0 0], then discarded
(turtle-position)                        ;; => [10 0 0] (outer unchanged)
```

### Nested scopes
```clojure
(turtle :reset
  (f 10)
  (turtle (f 20) (turtle-position))      ;; => [30 0 0]
  (turtle-position))                     ;; => [10 0 0]
```

### Lines survive scope exit
```clojure
(pen :on)
(f 10)                                   ;; line 1
(turtle (f 20))                          ;; line 2 (NOT lost)
(f 10)                                   ;; line 3
;; Scene accumulator has 3 line segments
```

### Settings inheritance
```clojure
(resolution :n 32)
(color 0xff0000)
(turtle
  ;; inherits :n 32 and red
  (register part-a (sphere 10))
  (color 0x00ff00)
  (register part-b (sphere 10)))         ;; green
(register part-c (sphere 10))            ;; red, :n 32 (outer unaffected)
```

### Settings isolation with :reset
```clojure
(resolution :n 32)
(turtle :reset
  ;; does NOT inherit — fresh defaults
  (register lo-res (sphere 10)))         ;; uses default :n 16
```

### Preserve-up spiral
```clojure
(turtle :preserve-up
  (def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))))
(register spiral-text
  (turtle :preserve-up
    (text-on-path "Once upon a time..." spiral :size 6 :depth 1.2)))
;; Letters stay upright — no accumulated roll
```

### Return value
```clojure
(def m (turtle :reset (f 50) (box 20)))  ;; returns the box mesh
(register cube m)
```

### Branching
```clojure
(defn tree [depth len]
  (when (pos? depth)
    (register (keyword (str "b-" depth "-" (rand-int 100)))
      (extrude (circle (* depth 0.5)) (f len)))
    (turtle (th 30) (tree (dec depth) (* len 0.7)))
    (turtle (th -30) (tree (dec depth) (* len 0.7)))))

(tree 4 30)
```

### Pen scoping
```clojure
(pen :on)
(f 10)
(turtle
  (pen :off)
  (f 20)                                 ;; no line
  (turtle
    (pen :on)
    (f 5)))                              ;; line (innermost pen wins)
(f 10)                                   ;; line (outer pen still on)
;; 3 line segments total: f10, f5, f10
```

### Register from nested scope
```clojure
(turtle
  (f 50) (th 90)
  (register far-away (box 20)))
;; :far-away is in the global registry, visible in viewport
;; turtle is back at its pre-scope position
```

---

## Future Possibilities (Not in This Sprint)

Enabled by the architecture but NOT to implement now:

1. **`:on-surface mesh`** — constrain turtle to mesh surface (geodesic movement)
2. **`:2d`** — restrict to XY plane only
3. **Scoped registries** — turtle scope with its own local registry
4. **Turtle as first-class value** — `(def t (make-turtle {:pos [10 0 0]}))` then `(with-turtle t ...)`
