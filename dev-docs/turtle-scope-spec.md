# Ridley — Turtle Scope Refactor

## Spec for Claude Code

### Motivation

Ridley currently uses a global mutable turtle state (an atom) that all commands (`f`, `th`, `tv`, `tr`, etc.) read and write implicitly. This creates several problems:

1. **Impure functions** — `f`, `th` etc. are side-effecting, which is un-Clojure-like
2. **push-state/pop-state** — an imperative workaround for what should be lexical scoping
3. **Implicit coupling** — combining `th` and `tv` in sequence accumulates an implicit `tr` (roll), which surprises users (the spiral text problem: alternating `th`/`tv` rotates letters unexpectedly)
4. **No isolation** — code inside `extrude`, `path`, `attach` must carefully rebind the global state, leading to fragile macro implementations

### Core Change

Replace the global turtle atom with **explicit turtle scopes**. A `turtle` macro establishes a scope in which all turtle commands operate on a local turtle state via dynamic binding.

---

## `turtle` Macro

### Syntax

```clojure
(turtle & body)                          ; inherit current pose
(turtle :reset & body)                   ; origin [0 0 0], heading +X, up +Z
(turtle [x y z] & body)                  ; specific position, default orientation
(turtle {:pos [x y z]                    ; full control
         :heading [hx hy hz]
         :up [ux uy uz]} & body)

;; Mode flags (combinable, before body)
(turtle :preserve-up & body)             ; rotations don't accumulate roll
(turtle :reset :preserve-up & body)      ; combined
```

### Semantics

1. **Creates a new turtle state** initialized from the options (or inherited from the enclosing scope)
2. **Binds it dynamically** so that all turtle commands inside `body` operate on it
3. **Evaluates body** forms sequentially (like `do`)
4. **Returns the last expression** of body (like `let`, `do`)
5. **Discards the local turtle state** on exit — the enclosing turtle is unaffected
6. Meshes created inside the scope (via `register`, primitives, `extrude`) are **not** discarded — they affect the global scene registry as usual

### Dynamic Binding Implementation

```clojure
;; In turtle/core.cljs — the binding var
(def ^:dynamic *turtle-state* (atom (make-turtle)))

;; turtle macro (defined in repl.cljs as SCI macro)
(defmacro turtle [& args]
  (let [{:keys [opts body]} (parse-turtle-args args)]
    `(binding [*turtle-state* (atom (init-turtle-from-opts ~opts *turtle-state*))]
       ~@body)))

;; All turtle commands use the dynamic var:
(defn f [dist]
  (swap! *turtle-state* move-forward dist))

(defn th [angle]
  (swap! *turtle-state* turn-horizontal angle))
;; etc.
```

### Top-Level Implicit Scope

The entire user script runs inside an implicit `(turtle :reset ...)`. This means:
- Existing scripts work unchanged (the top-level turtle starts at origin)
- There is no user-visible "global" turtle — there's always a scope, it's just implicit at the top level
- The REPL maintains its turtle between commands (the REPL's top-level binding persists)

---

## Mode: `:preserve-up`

### The Problem

When you alternate `th` (yaw) and `tv` (pitch), each rotation is applied relative to the *current* local frame. This means `tv` changes the heading *and* implicitly rotates the local "up" axis relative to the world. After several `th`/`tv` pairs, the turtle's up vector drifts, producing an unexpected roll.

Concrete example — the spiral text:
```clojure
;; BEFORE (current): tv introduces implicit roll, letters rotate
(def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5))))
;; Workaround: use (u 0.5) instead of (tv 0.5) to avoid the roll

;; AFTER: preserve-up mode eliminates the implicit roll
(turtle :preserve-up
  (def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5))))))
```

### Semantics

When `:preserve-up` is active:
- `th` (yaw): rotates heading around the **world up** (the up vector the turtle had when the scope was entered), not the turtle's current local up
- `tv` (pitch): rotates heading around the turtle's right axis as usual, but then **re-orthogonalizes up** to match the original up direction as closely as possible
- `tr` (roll): works as usual (explicit roll is still allowed)

In practice this means the turtle's up vector stays aligned with the original up throughout the scope, unless explicitly changed with `tr`.

### Implementation Sketch

```clojure
;; In turtle state, when :preserve-up is active:
{:preserve-up true
 :reference-up [0 0 1]}   ; captured at scope entry

;; Modified th:
(defn turn-horizontal [{:keys [preserve-up reference-up] :as state} angle]
  (if preserve-up
    ;; Rotate heading around reference-up instead of local up
    (let [new-heading (rotate-around-axis (:heading state) reference-up angle)
          new-up (recalc-up new-heading reference-up)]
      (assoc state :heading new-heading :up new-up))
    ;; Standard: rotate around local up
    (standard-th state angle)))

;; Modified tv:
(defn turn-vertical [{:keys [preserve-up reference-up] :as state} angle]
  (if preserve-up
    (let [right (cross (:heading state) (:up state))
          new-heading (rotate-around-axis (:heading state) right angle)
          ;; Re-project up to stay as close to reference-up as possible
          new-up (orthogonalize-up new-heading reference-up)]
      (assoc state :heading new-heading :up new-up))
    (standard-tv state angle)))
```

---

## What Gets Removed

### Removed Commands
- `push-state` — replaced by `(turtle ...)`
- `pop-state` — replaced by exiting the `turtle` scope
- `clear-stack` — no stack to clear

### Removed State
- `:state-stack` field from turtle state — no longer needed

### `reset` Becomes Sugar

```clojure
;; Old:
(reset)
(reset [x y z])
(reset [x y z] :heading [hx hy hz])

;; New: these become mutations of the current scope's turtle
;; Semantically identical but now clearly scoped
(reset)                                  ; re-initialize current turtle to origin
(reset [x y z])                          ; re-initialize to position
(reset [x y z] :heading [hx hy hz])     ; re-initialize with heading
```

`reset` still exists as a convenience for repositioning within a scope. The difference is conceptual: it's not resetting "the global turtle" — it's resetting "this scope's turtle."

---

## Interaction with Existing Macros

### `path`

`path` already creates an internal recording turtle. No change in user API:

```clojure
(def my-path (path (f 20) (th 90) (f 20)))
```

Internally, `path` creates its own turtle scope for recording. The user doesn't see this.

### `extrude`, `loft`, `extrude-closed`

These already run movements on an internal turtle. No API change:

```clojure
(register tube (extrude (circle 5) (f 30) (th 45) (f 20)))
```

The movements inside `extrude` run on extrude's internal turtle, which starts at the current scope's turtle position. This is unchanged.

### `attach` / `attach!`

`attach` currently does an implicit push-state. With the refactor, `attach` becomes a turtle scope internally:

```clojure
;; User API unchanged:
(register b (attach b (f 10) (th 45)))
(attach! :b (f 10) (th 45))
```

Internally, `attach` creates a turtle scope initialized at the mesh's creation pose. `attach!` does the same but updates the registry.

### `attach-face` / `clone-face`

Same pattern — internally they create a turtle scope positioned at the face center with heading = face normal.

### `with-path`

`with-path` resolves marks from a path relative to the current turtle position. It can remain as-is — it reads the current scope's turtle to resolve anchors.

### `anim!` / `span`

Animation spans contain turtle commands (`f`, `th`, `tv`, `tr`, `u`, `rt`, `lt`). These are preprocessed into frame arrays at definition time. The preprocessing runs the commands on an internal turtle — this is already isolated and doesn't change.

---

## `turtle` Replacing `push-state`/`pop-state`

### Before → After Examples

**Branching structure (L-system):**
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

---

## Accessing Turtle State

```clojure
(get-turtle)                     ; full turtle state map (current scope)
(turtle-position)                ; [x y z]
(turtle-heading)                 ; [x y z]
(turtle-up)                      ; [x y z]
```

These read the current scope's turtle, same as today but without the global atom ambiguity.

---

## Settings That Live on the Turtle

Currently these are turtle state fields and should remain scoped to the turtle:

- **pen-mode** (`:on`, `:off`) — `(pen :on)`, `(pen :off)`
- **joint-mode** (`:flat`, `:round`, `:tapered`) — `(joint-mode :round)`
- **resolution** — `(resolution :n 32)`
- **color / material** — `(color 0xff0000)`, `(material :metalness 0.8)`
- **anchors** — `(mark :name)`, `(goto :name)` — scoped to the turtle that created them

This means a `turtle` scope inherits these settings from its parent by default, and changes inside the scope don't leak out. This is a feature, not a limitation:

```clojure
(resolution :n 8)                        ; draft mode
(turtle
  (resolution :n 32)                     ; high-res only inside this scope
  (register detail-part (extrude (circle 10) (f 20))))
;; back to :n 8 here
```

---

## What Does NOT Change

- **Scene registry** — remains global. `register`, `show`, `hide`, `export` work on the global scene
- **`extrude`, `loft`, `revolve`** — user API unchanged, they still take a shape + movements
- **`path`** — user API unchanged, records movements as data
- **`attach` / `attach!`** — user API unchanged, internally uses turtle scope
- **`anim!` / `span`** — user API unchanged
- **Navigation** — `goto`, `look-at`, `path-to`, `mark` work within the current scope
- **Primitives** — `box`, `sphere`, `cyl`, `cone` create meshes at current scope's position
- **Lateral movement** — `u`, `d`, `rt`, `lt` work in the current scope

---

## Future Possibilities (Not in This Sprint)

These are enabled by the architecture but should NOT be implemented now:

1. **`:on-surface mesh`** — constrain turtle to mesh surface (geodesic movement)
2. **`:2d`** — restrict to XY plane (useful for `shape` definitions)
3. **Scoped registries** — `(turtle :registry :local ...)` with isolated scene
4. **Turtle as value** — `(def t (turtle-state :reset))` then `(with-turtle t ...)`

---

## Implementation Plan

### Phase 1: Core Refactor
1. Add `^:dynamic *turtle-state*` binding var alongside existing global atom
2. Implement `turtle` macro in `repl.cljs` (SCI macro)
3. Migrate all turtle commands (`f`, `th`, `tv`, `tr`, `u`, `d`, `rt`, `lt`, `pen`, etc.) to use `*turtle-state*` instead of the global atom
4. Wrap REPL evaluation in a top-level binding (persistent across REPL commands)
5. Wrap definitions panel evaluation in `(turtle :reset ...)`

### Phase 2: Implement `:preserve-up`
1. Add `:preserve-up` and `:reference-up` fields to turtle state
2. Modify `th` to rotate around reference-up when preserve-up is active
3. Modify `tv` to re-orthogonalize up after pitch
4. Test with spiral text example

### Phase 3: Clean Up
1. Remove `push-state`, `pop-state`, `clear-stack` from SCI bindings
2. Remove `:state-stack` from turtle state
3. Refactor `attach`/`attach!` to use `turtle` internally instead of push/pop
4. Update `attach-face`/`clone-face` similarly
5. Update any internal code that uses push/pop

### Phase 4: Verify
1. Run all existing examples
2. Test spiral text with `:preserve-up`
3. Test nested `turtle` scopes
4. Test that `extrude`, `loft`, `path` still work correctly
5. Test animations
6. Test WebRTC sync (script sharing)

---

## Test Cases

### Basic scoping
```clojure
(f 10)                                   ; turtle at [10 0 0]
(turtle (f 20))                          ; inner turtle goes to [30 0 0]
(turtle-position)                        ; => [10 0 0] (outer unchanged)
```

### Nested scopes
```clojure
(turtle :reset
  (f 10)
  (turtle (f 20) (turtle-position))      ; => [30 0 0]
  (turtle-position))                     ; => [10 0 0]
```

### Preserve-up spiral
```clojure
(turtle :preserve-up
  (def spiral (path (dotimes [_ 85] (f 3) (th 8.6) (tv 0.5)))))
(register spiral-text
  (text-on-path "Once upon a time..." spiral :size 6 :depth 1.2))
;; Letters should NOT rotate/roll — they stay upright
```

### Settings isolation
```clojure
(resolution :n 8)
(turtle
  (resolution :n 32)
  (register hires (sphere 20)))
(register lores (sphere 20))             ; uses :n 8
```

### Return value
```clojure
(def m (turtle :reset (f 50) (box 20)))  ; returns the box mesh
(register cube m)
```

### Branching
```clojure
(defn tree [depth len]
  (when (pos? depth)
    (register (keyword (str "branch-" depth))
      (extrude (circle (* depth 0.5)) (f len)))
    (turtle (th 30) (tree (dec depth) (* len 0.7)))
    (turtle (th -30) (tree (dec depth) (* len 0.7)))))

(tree 4 30)
```
