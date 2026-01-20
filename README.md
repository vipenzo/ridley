# Ridley

A 3D CAD environment powered by turtle graphics and ClojureScript. Create 3D models using an intuitive, programmable approach inspired by Logo.

**[Try it now in your browser](https://vipenzo.github.io/Ridley/)** — no installation required!

![Ridley Screenshot](dev-docs/screenshot.png)

## Overview

Ridley combines the simplicity of turtle graphics with powerful 3D modeling capabilities. Write Clojure code to define shapes, extrude profiles along paths, and create complex geometry through boolean operations.

**Key Features:**
- **Turtle-based modeling**: Move, turn, and place geometry in 3D space
- **Shape extrusion**: Sweep 2D profiles along arbitrary paths
- **Loft with transformations**: Scale, rotate, and morph shapes during extrusion
- **Path recording**: Define reusable movement sequences with loops and conditionals
- **Boolean operations**: Union, difference, and intersection via Manifold WASM
- **Live REPL**: Interactive development with command history

## Quick Start

```bash
# Install dependencies
npm install

# Start development server
npx shadow-cljs watch app

# Open http://localhost:9000
```

## Usage

The interface has two panels:

- **Definitions** (top-left): Define reusable shapes, paths, and functions. Press `Cmd+Enter` to evaluate.
- **REPL** (bottom-left): Execute commands interactively. Press `Enter` to run, `↑↓` for history.

### Basic Commands

```clojure
;; Movement
(f 30)        ; Move forward 30 units
(th 90)       ; Turn horizontal (yaw) 90°
(tv 45)       ; Turn vertical (pitch) 45°
(tr 30)       ; Turn roll 30°

;; 3D Primitives
(box 20)              ; Cube
(box 30 20 10)        ; Rectangular box
(sphere 15)           ; Sphere
(cyl 10 30)           ; Cylinder
(cone 15 5 25)        ; Cone (r1, r2, height)
```

### Shape Extrusion

Create 3D geometry by extruding 2D shapes along the turtle's path:

```clojure
;; Basic cylinder
(extrude (circle 10) (f 30))

;; Rectangle extruded with a turn
(extrude (rect 20 10) (f 20) (th 45) (f 20))

;; Cone via loft (shape transforms during extrusion)
(loft (circle 20) #(scale %1 (- 1 %2)) (f 30))

;; Twisted extrusion
(loft (rect 20 10) #(rotate-shape %1 (* %2 90)) (f 40))
```

### Paths

Record movement sequences for reuse:

```clojure
;; Define a square path
(def square-path (path (dotimes [_ 4] (f 20) (th 90))))

;; Extrude along the path
(extrude (circle 5) square-path)

;; Create a closed torus
(extrude-closed (circle 5) square-path)
```

### Boolean Operations

Combine meshes using CSG operations (requires Manifold WASM):

```clojure
;; Create two overlapping shapes
(box 20)
(def a (last-mesh))

(f 10)
(sphere 15)
(def b (last-mesh))

;; Boolean operations
(mesh-union a b)        ; Combine
(mesh-difference a b)   ; Subtract
(mesh-intersection a b) ; Intersect
```

## Available Shapes

| Shape | Description |
|-------|-------------|
| `(circle r)` | Circle with radius r |
| `(circle r n)` | Circle with n points |
| `(rect w h)` | Rectangle w×h |
| `(polygon pts)` | Custom polygon from points |
| `(star n outer inner)` | Star with n points |

## Shape Transformations

Use these in loft transform functions:

```clojure
(scale shape factor)        ; Uniform scale
(scale shape fx fy)         ; Non-uniform scale
(rotate-shape shape angle)  ; Rotate (degrees)
(translate shape dx dy)     ; Translate shape
(morph shape-a shape-b t)   ; Interpolate between shapes
(resample shape n)          ; Resample to n points
```

## Architecture

```
src/ridley/
├── core.cljs           # Application entry point, UI handling
├── editor/
│   └── repl.cljs       # SCI-based code evaluation
├── turtle/
│   ├── core.cljs       # Turtle state and movement
│   ├── shape.cljs      # 2D shape constructors
│   ├── path.cljs       # Path utilities
│   └── transform.cljs  # Shape transformations
├── geometry/
│   ├── primitives.cljs # Box, sphere, cylinder, cone
│   ├── operations.cljs # Extrude, revolve, sweep, loft
│   └── faces.cljs      # Face identification
├── viewport/
│   └── core.cljs       # Three.js rendering
└── manifold/
    └── core.cljs       # Manifold WASM integration
```

## Dependencies

- [ClojureScript](https://clojurescript.org/) - Language
- [shadow-cljs](https://shadow-cljs.github.io/docs/UsersGuide.html) - Build tool
- [SCI](https://github.com/babashka/sci) - Small Clojure Interpreter for REPL
- [Three.js](https://threejs.org/) - 3D rendering
- [Manifold](https://github.com/elalish/manifold) - Mesh boolean operations (WASM)

## Development

```bash
# Watch mode with hot reload
npx shadow-cljs watch app

# Production build
npx shadow-cljs release app

# REPL connection
npx shadow-cljs cljs-repl app
```

## Examples

See [dev-docs/Examples.md](dev-docs/Examples.md) for comprehensive examples including:
- Basic turtle drawing
- 3D primitives
- Shape extrusion and sweeping
- Loft with transformations
- Closed extrusions (torus-like shapes)
- Manifold boolean operations

## License

MIT
