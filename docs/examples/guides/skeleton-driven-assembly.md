# Skeleton-driven assembly

> Build complex assemblies by walking a single skeleton path with named marks, then snapping each component into place.

## When to use this

Reach for skeleton-driven assembly when:

- Multiple components share an axis, a chain, or a structural backbone (hinges, kinematic chains, brackets along a beam, modular grids).
- The relative positions of components matter more than their absolute coordinates.
- You expect to resize, retune, or extend the assembly later. With a skeleton, geometry is centralised in one place.
- Computing offsets between components by hand would be tedious or fragile.

Skip it when the assembly is two pieces glued together, or when there is no meaningful "spine" connecting the parts. A pair of nested `attach` calls is fine; a skeleton would be overkill.

## How it works

The pattern has three steps.

**1. Build a skeleton path.** A `path` walks the structure of the assembly and drops named marks at every relevant point.

```clojure
(def skel
  (path
   (mark :base)
   (f 30) (mark :mid)
   (f 30) (mark :top)))
```

**2. Model each component independently.** Build each component in its own local frame, with the origin at the point where it should anchor onto the skeleton. Don't pre-position components — that defeats the pattern.

```clojure
(def disk (cyl 15 4))
```

**3. Compose by snapping.** For each component, attach it to the right mark on the skeleton:

```clojure
(register stack
  (mesh-union
   (attach disk (move-to skel :at :base))
   (attach disk (move-to skel :at :mid))
   (attach disk (move-to skel :at :top))))
```

Use `:align` when the mark's heading matters (orientation, not just position):

```clojure
(attach component (move-to skel :at :some-mark :align))
```

The skeleton is the assembly's source of truth. Move a mark, and every component that snaps to it follows.

## Example

A vertical axle with three disks at fixed offsets:

```clojure
(def axle-skel
  (path
   (mark :base)
   (f 30) (mark :mid)
   (f 30) (mark :top)))

(def disk (cyl 15 4))

(register stack
  (mesh-union
   (attach disk (move-to axle-skel :at :base))
   (attach disk (move-to axle-skel :at :mid))
   (attach disk (move-to axle-skel :at :top))))
```

To move a disk, edit the path. The disks themselves stay untouched.

## Refinement: proportional marks

Instead of hard-coding distances in the path, express them as fractions of a reference length. The marks scale with the reference; their relative positions stay stable.

```clojure
(def H 60)

(def axle-skel
  (path
   (mark :base)
   (f (* 0.5 H)) (mark :mid)
   (f (* 0.5 H)) (mark :top)))
```

Now changing `H` rescales the whole assembly. Useful when the same design needs multiple sizes, or when a single dimension drives many derived offsets.

The pilot example (`assembly/01-hinge-c-profile.cljs`) uses this to position bracket pairs along the hinge axis:

```clojure
(def bracket-axials [0.625 -0.33])
```

Each value is a fraction of `H` from the pin axis. Setting `H` once propagates to every bracket position automatically.

## Common pitfalls

- **Pre-positioning components.** If you build a component already shifted to its final pose, `move-to` translates it twice and the assembly breaks. Build at origin, snap with `move-to`.

- **Forgetting `:align`.** Without `:align`, `move-to` only translates. If the mark carries a heading that should orient the component (left vs right brackets, for instance), use `:align` explicitly.

- **Treating skeleton as a registry.** A skeleton is a coherent assembly path. Don't pile unrelated marks into one path just to have them in one place. Use multiple skeletons if your assembly has multiple unrelated structures, or use the registry for unrelated meshes.

- **Anchoring at the wrong point.** If a component's natural origin isn't where you want it to attach, shift its creation pose first — see `creation-pose-shifting.md`.

## See also

- `creation-pose-shifting.md` — relocate a component's anchor point so it snaps to the right place at compose time.
- `marks-as-discovery.md` — generate marks programmatically and recover them with `(anchors path)`.
- `assembly/01-hinge-c-profile.cljs` — full pilot example using all three patterns together.