# Task: Puppet Library for Ridley

## Overview

Create a minimal puppet library at `dev-docs/libraries/puppet.cljs` that demonstrates the hierarchical assembly system with articulated humanoid figures. The library defines a `humanoid` constructor that builds a puppet from skeletons and meshes, with preset actions (walk, wave, idle, nod) stored as functions in the registry.

The puppet should be minimal but visually clear — enough to show articulation and animation, not photorealistic.

## Prerequisites

This library depends on features that should already be implemented:
- `with-path` + `register` map → hierarchical assembly with implicit links
- `attach-path` → mesh anchors from path marks
- `anim!` / `anim-proc!` → preprocessed and procedural animations
- `link!` with `:at`, `:from`, `:inherit-rotation` → anchor-based linking
- `$` → registry value access

Verify these work before starting. If any are missing, flag it.

## File Location

`dev-docs/libraries/puppet.cljs`

Follow the same pattern as `dev-docs/libraries/gears.clj` — pure SCI code, no namespace declaration (it's loaded into the user's SCI context).

## Puppet Structure

### Skeleton Definitions

The puppet needs 4 skeletons:

```
body-skeleton:
  origin (center of torso base)
  ├─ (f torso-height)      → top of torso
  │  ├─ (rt shoulder-w)    → mark :shoulder-r
  │  ├─ (lt shoulder-w)    → mark :shoulder-l  
  │  ├─ (f neck-len)       → mark :neck
  │  ├─ (rt hip-w)         → mark :hip-r (at origin level, offset right)
  │  └─ (lt hip-w)         → mark :hip-l
  
arm-skeleton:
  mark :top (attachment point)
  ├─ (f upper-arm-len)     → mark :elbow
  └─ (f forearm-len)       → mark :wrist

leg-skeleton:
  mark :top (attachment point)
  ├─ (f upper-leg-len)     → mark :knee
  └─ (f lower-leg-len)     → mark :ankle

neck-skeleton:
  mark :base (attachment point)
  └─ (f neck-len)          → mark :head-base
```

### Part Hierarchy

```
puppet/
├── torso          (box)           ← root, no parent
├── head           (sphere)        ← linked to torso at :neck
├── r-arm/
│   ├── upper      (cylinder)      ← linked to torso at :shoulder-r
│   └── lower      (cylinder)      ← linked to upper at :elbow
├── l-arm/
│   ├── upper      (cylinder)      ← linked to torso at :shoulder-l
│   └── lower      (cylinder)      ← linked to upper at :elbow
├── r-leg/
│   ├── upper      (cylinder)      ← linked to torso at :hip-r
│   └── lower      (cylinder)      ← linked to upper at :knee
└── l-leg/
    ├── upper      (cylinder)      ← linked to torso at :hip-l
    └── lower      (cylinder)      ← linked to upper at :knee
```

Total: 11 parts, 10 links (all implicit via with-path nesting).

### Proportions

All proportions relative to total height. Default height: 100 units.

| Parameter | Proportion | Default (h=100) |
|-----------|-----------|-----------------|
| head-radius | 0.06 | 6 |
| neck-len | 0.03 | 3 |
| torso-height | 0.30 | 30 |
| torso-width | 0.14 | 14 |
| torso-depth | 0.08 | 8 |
| shoulder-width | 0.09 | 9 (from center) |
| hip-width | 0.06 | 6 (from center) |
| upper-arm-len | 0.18 | 18 |
| upper-arm-radius | 0.025 | 2.5 |
| forearm-len | 0.15 | 15 |
| forearm-radius | 0.02 | 2 |
| upper-leg-len | 0.22 | 22 |
| upper-leg-radius | 0.03 | 3 |
| lower-leg-len | 0.20 | 20 |
| lower-leg-radius | 0.025 | 2.5 |

### Style Options

The `style` parameter controls mesh type:
- `:blocky` — all box-based (Minecraft style)
- `:smooth` — cylinders for limbs, box for torso, sphere for head (default)

Only implement `:smooth` for now. `:blocky` can be added later.

## Constructor API

```clojure
;; Default puppet
(puppet/humanoid :name :bob)

;; Custom height
(puppet/humanoid :name :alice :height 150)

;; Custom proportions (override individual ones)
(puppet/humanoid :name :tiny
  :height 50
  :proportions {:head-radius 0.08    ;; bigger head
                :torso-height 0.25}) ;; shorter torso
```

### What `humanoid` Does

1. Compute absolute dimensions from height + proportions
2. Build skeleton paths (body-sk, arm-sk, leg-sk, neck-sk)
3. Position turtle at the puppet's feet (origin = ground level, center)
4. Move up to torso base
5. Use `with-path` + `register` map to build the assembly:

```clojure
(with-path body-sk
  (register <n>
    {:torso (box torso-w torso-d torso-h)
     :head (do (goto :neck)
               (with-path neck-sk
                 {:neck (cyl neck-r neck-len)  ;; optional: skip if too small
                  :skull (do (goto :head-base) (sphere head-r))}))
     :r-arm (do (goto :shoulder-r)
                (with-path arm-sk
                  {:upper (cyl arm-r upper-arm-len)
                   :lower (do (goto :elbow) (cyl forearm-r forearm-len))}))
     :l-arm (do (goto :shoulder-l)
                (with-path arm-sk
                  {:upper (cyl arm-r upper-arm-len)
                   :lower (do (goto :elbow) (cyl forearm-r forearm-len))}))
     :r-leg (do (goto :hip-r)
                (with-path leg-sk
                  {:upper (cyl leg-r upper-leg-len)
                   :lower (do (goto :knee) (cyl shin-r lower-leg-len))}))
     :l-leg (do (goto :hip-l)
                (with-path leg-sk
                  {:upper (cyl leg-r upper-leg-len)
                   :lower (do (goto :knee) (cyl shin-r lower-leg-len))}))}))
```

6. Register action functions in the value store via `register-value!` or by storing them in a separate map accessible via `$`.

## Actions

Actions are functions stored alongside the puppet. They create animations targeting the puppet's qualified part names.

### Implementation Pattern

Each action is a function that takes the puppet name (keyword) and optional parameters, and calls `anim!` or `anim-proc!` on the appropriate parts.

```clojure
(defn walk-action [puppet-name & {:keys [speed] :or {speed 1.0}}]
  (let [dur (/ 1.0 speed)
        torso (keyword (str (name puppet-name) "/torso"))
        r-arm-upper (keyword (str (name puppet-name) "/r-arm/upper"))
        l-arm-upper (keyword (str (name puppet-name) "/l-arm/upper"))
        r-leg-upper (keyword (str (name puppet-name) "/r-leg/upper"))
        l-leg-upper (keyword (str (name puppet-name) "/l-leg/upper"))]
    ;; Torso slight bounce
    (anim! (keyword (str (name puppet-name) "-walk-bounce")) dur torso :loop-bounce
      (span 1.0 :in-out (u 2)))
    ;; Right leg forward, left leg back (then reverse)
    (anim! (keyword (str (name puppet-name) "-walk-r-leg")) dur r-leg-upper :loop-bounce
      (span 1.0 :in-out :ang-velocity 10 (tv 30)))
    (anim! (keyword (str (name puppet-name) "-walk-l-leg")) dur l-leg-upper :loop-bounce
      (span 1.0 :in-out :ang-velocity 10 (tv -30)))
    ;; Arms opposite to legs
    (anim! (keyword (str (name puppet-name) "-walk-r-arm")) dur r-arm-upper :loop-bounce
      (span 1.0 :in-out :ang-velocity 10 (tv -20)))
    (anim! (keyword (str (name puppet-name) "-walk-l-arm")) dur l-arm-upper :loop-bounce
      (span 1.0 :in-out :ang-velocity 10 (tv 20)))
    (play!)))
```

### Actions to Implement

#### `:walk` 
- Legs alternate forward/back (tv ±30°)
- Arms swing opposite to legs (tv ∓20°)
- Torso slight vertical bounce (u 2, loop-bounce)
- Parameter: `:speed` (default 1.0)

#### `:wave`
- Right arm up (tv -60° from rest, so it points somewhat up)
- Right forearm oscillates (tv ±30°, loop-bounce)
- Rest of body idle
- Parameter: `:hand` (:right or :left, default :right)

#### `:idle`
- Torso very slight breathing (u 1, slow loop-bounce, ~3 second cycle)
- Head tiny random-looking sway (tv ±3°, slow)
- Everything else still

#### `:nod`
- Head pitches down then up (tv 15° then back)
- Single cycle, not looped
- Duration ~0.5s

### Registering Actions

After building the assembly, store the actions so they're accessible via `$`:

```clojure
;; Store actions map in the value registry
(register-value! (keyword (str (name puppet-name) "/actions"))
  {:walk  (fn [& opts] (apply walk-action puppet-name opts))
   :wave  (fn [& opts] (apply wave-action puppet-name opts))
   :idle  (fn [& opts] (apply idle-action puppet-name opts))
   :nod   (fn [& opts] (apply nod-action puppet-name opts))
   :stop  (fn [] (stop!))})
```

Usage:
```clojure
(puppet/humanoid :name :bob)

;; Call actions
((:walk ($ :bob/actions)))
((:walk ($ :bob/actions)) :speed 2.0)
((:wave ($ :bob/actions)))
((:stop ($ :bob/actions)))
```

Or with a convenience function:
```clojure
(defn puppet-do [puppet-name action & opts]
  (let [actions ($ (keyword (str (name puppet-name) "/actions")))]
    (when-let [f (get actions action)]
      (apply f opts))))

;; Usage
(puppet/do :bob :walk :speed 1.5)
(puppet/do :bob :wave)
(puppet/do :bob :stop)
```

## Face Placeholder

The head should have a `:face` structure defined but not visually implemented yet. This is a hook for future expression support.

In the head construction, add a comment block showing the planned structure:

```clojure
;; Future: face sub-parts for expressions
;; The head mesh would become an assembly:
;; {:skull (sphere head-r)
;;  :eye-l (...)        ;; ellipse, scalable Y for blink
;;  :eye-r (...)
;;  :mouth (...)        ;; bezier curve, deformable
;;  :brow-l (...)       ;; line segment, rotatable
;;  :brow-r (...)}
;;
;; Expressions via anim-proc!:
;; :blink   — eye scale Y: 1 → 0 → 1
;; :smile   — mouth curve up
;; :surprise — eyes scale up, brows up, mouth open
;; :sad     — brows angle down, mouth curve down
;; :angry   — brows down+in, mouth tight
```

## AI Context (Future)

Include placeholder `AI-PROMPT` and `EXAMPLES` definitions:

```clojure
(def AI-PROMPT
  "Puppet library for Ridley. Creates articulated humanoid figures.
   Use (puppet/humanoid :name :bob) to create a default puppet.
   Access actions via ($ :bob/actions) — returns map of :walk :wave :idle :nod :stop.
   All parts have qualified names: :bob/torso, :bob/r-arm/upper, :bob/r-leg/lower, etc.
   Custom proportions override defaults: :height, :proportions {:head-radius 0.08}.
   Animations use the standard anim!/anim-proc! system with link-based hierarchy.")

(def EXAMPLES
  ";; Create a puppet and make it walk
   (puppet/humanoid :name :bob)
   ((:walk ($ :bob/actions)))

   ;; Wave hello
   ((:wave ($ :bob/actions)))

   ;; Stop all animations
   ((:stop ($ :bob/actions)))

   ;; Two puppets
   (puppet/humanoid :name :alice)
   (puppet/humanoid :name :bob :height 120)
   ((:walk ($ :alice/actions)) :speed 0.8)
   ((:wave ($ :bob/actions)))")
```

## Testing

After creating the file, test with:

```clojure
;; 1. Load the library (copy-paste or load-file)

;; 2. Create a puppet
(puppet/humanoid :name :test)

;; 3. Verify parts exist
(registered-names)  ;; should include :test/torso, :test/r-arm/upper, etc.

;; 4. Verify links (check animation system)
(anim-list)

;; 5. Test actions
((:idle ($ :test/actions)))
((:walk ($ :test/actions)))
((:wave ($ :test/actions)))
((:nod ($ :test/actions)))
((:stop ($ :test/actions)))

;; 6. Verify hierarchy — hide torso, children should still be visible
;;    but when torso animates, children follow
(hide :test/torso)
(show :test/torso)
```

## Notes

- The puppet stands along the Z axis (Z up). Torso base is at the origin or slightly above to leave room for legs extending downward.
- All `anim!` names should be prefixed with the puppet name to avoid collisions between multiple puppets: `:bob-walk-r-leg`, `:alice-walk-r-leg`.
- The `:stop` action calls `(stop!)` which stops ALL animations. For per-puppet stop, it should stop only animations whose name starts with the puppet name prefix. Implement this.
- Cylinder limbs should be oriented along the turtle heading at their creation point. Since the skeleton marks define position AND heading, the `goto` before each limb sets the correct orientation.
- Keep mesh resolution low for performance (limbs don't need 32-segment cylinders). Use `(resolution :n 8)` inside the constructor, restore original after.
