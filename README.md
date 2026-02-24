# Ridley

A programmable 3D CAD environment powered by turtle graphics and ClojureScript. Create 3D models using an intuitive, code-driven approach inspired by Logo.

**[Try it in your browser](https://vipenzo.github.io/Ridley/)** — no installation required.

![Ridley Screenshot](dev-docs/screenshot.png)

## Overview

Ridley combines turtle graphics with full 3D modeling: extrude 2D profiles along arbitrary paths, apply procedural displacements, perform boolean operations, animate assemblies, and export printable STL files — all from a live REPL.

### Features

- **Turtle-based modeling** — move, turn, and place geometry in 3D space
- **Shape extrusion & loft** — sweep 2D profiles along paths with scale, twist, morph, and procedural shape-functions
- **Procedural displacement** — noise, heightmaps, weave patterns, and custom per-vertex functions
- **Boolean operations** — union, difference, intersection via Manifold WASM
- **Face operations** — select, extrude, inset, and clone individual faces
- **Spatial deformation (warp)** — inflate, dent, twist, attract, squash with smooth falloff
- **Animation system** — timeline-based keyframe animation with easing, parent-child linking, and procedural animation
- **Text & typography** — 3D text shapes, text-on-path, custom font loading
- **2D shape booleans** — union, difference, intersection, offset via Clipper2
- **Interactive tweaking** — real-time parameter sliders for rapid design iteration
- **SVG import** — parse SVG paths into 2D shapes for extrusion and revolution
- **STL import/export** — import external meshes, export for 3D printing
- **AI assistant** — LLM-powered code generation and editing from natural language
- **Voice input** — multilingual voice commands (Italian/English) via Web Speech API
- **Live REPL** — interactive development with command history
- **Library system** — persistent user-defined code libraries with dependency management
- **VR/AR support** — view models on WebXR headsets with passthrough AR
- **Desktop-headset sync** — real-time code sync via WebRTC

## Quick Start

```bash
npm install
npx shadow-cljs watch app
# Open http://localhost:9000
```

## Usage

The interface has two panels: **Editor** (left) for definitions and a **REPL** (bottom-left) for interactive commands. The 3D viewport is on the right.

### Movement & Primitives

```clojure
(f 30)            ; Move forward
(th 90)           ; Turn horizontal (yaw)
(tv 45)           ; Turn vertical (pitch)

(box 20)          ; Cube at current pose
(sphere 15)       ; Sphere
(cyl 10 30)       ; Cylinder
```

### Extrusion & Loft

```clojure
;; Extrude a circle along a path
(extrude (circle 10) (f 30) (th 45) (f 20))

;; Loft with procedural shape-function
(loft (twisted (circle 15) :angle 180)
      (f 40))

;; Tapered + fluted column
(loft (fluted (circle 20) :flutes 12 :depth 2)
      (tapered (circle 20) :to 0.6)
      (f 50))
```

### Paths & Profiles

```clojure
;; Record a path
(def my-path (path (f 20) (th 90) (f 20) (th 90) (f 20) (th 90) (f 20)))

;; Revolution from a 2D profile
(revolve (poly 10 0  20 0  20 5  15 8  10 5))
```

### Boolean Operations

```clojure
(def a (box 20))
(f 10)
(def b (sphere 15))
(mesh-difference a b)
```

### Registration & Scene

```clojure
;; Register named objects for the scene
(register :base (box 40 40 5))
(register :pillar (attach (cyl 5 30) (u 5)))

;; Control visibility
(hide :base)
(show :base)
```

## Examples

The [`examples/`](examples/) directory contains complete models:

| File | Description |
|------|-------------|
| `procedural-bowl.clj` | Parametric two-piece pistachio bowl with interlock ledge |
| `spiral-shell.clj` | Organic shell with logarithmic spiral growth |
| `twisted-vase.clj` | Vase with procedural twist and fluting |
| `embossed-column.clj` | Column with heightmap displacement |
| `canvas-weave.clj` | Woven fabric texture via weave-heightmap |
| `dice.clj` | Dice with boolean-carved pips |
| `recursive-tree.clj` | Recursive branching tree structure |

## Architecture

```
src/ridley/
├── core.cljs            # Entry point, UI setup
├── version.cljs         # Build version (injected at release)
├── editor/
│   ├── repl.cljs        # SCI-based code evaluation
│   ├── bindings.cljs    # SCI context bindings
│   ├── macros.cljs      # DSL macros (loft, attach, etc.)
│   └── implicit.cljs    # Implicit turtle commands
├── turtle/
│   ├── core.cljs        # Turtle state and movement
│   ├── shape.cljs       # 2D shape constructors
│   ├── shape_fn.cljs    # Shape-fn system (noise, heightmap)
│   ├── path.cljs        # Path utilities
│   └── transform.cljs   # Shape transformations
├── geometry/
│   ├── primitives.cljs  # Box, sphere, cylinder, cone
│   ├── operations.cljs  # Extrude, revolve, loft
│   ├── faces.cljs       # Face identification
│   └── warp.cljs        # Spatial deformation
├── viewport/
│   └── core.cljs        # Three.js rendering + picking
├── manifold/
│   └── core.cljs        # Manifold WASM boolean ops
├── clipper/
│   └── core.cljs        # Clipper2 2D shape booleans
├── anim/
│   └── core.cljs        # Animation system
├── scene/
│   └── registry.cljs    # Named mesh registry
├── library/
│   └── core.cljs        # User library system
├── sync/
│   └── peer.cljs        # WebRTC desktop-headset sync
└── voice/
    └── core.cljs        # Voice input system
```

## Dependencies

- [ClojureScript](https://clojurescript.org/) + [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html)
- [SCI](https://github.com/babashka/sci) — Small Clojure Interpreter
- [Three.js](https://threejs.org/) — 3D rendering
- [Manifold](https://github.com/elalish/manifold) — CSG boolean operations (WASM)
- [Clipper2](https://github.com/nicedoc/clipper2-js) — 2D shape booleans
- [CodeMirror 6](https://codemirror.net/) — Code editor
- [opentype.js](https://opentype.js.org/) — Font parsing for text shapes

## Development

```bash
npx shadow-cljs watch app    # Dev server with hot reload
npx shadow-cljs release app  # Production build
npx shadow-cljs compile test # Run tests
```

## License

MIT
