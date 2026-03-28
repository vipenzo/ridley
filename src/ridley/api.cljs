(ns ridley.api
  "Public API surface for ridley-core (Node library target).
   Re-exports pure computation modules — no browser dependencies.
   Requiring this namespace pulls all core modules into the build."
  (:require
    ;; Foundations
    [ridley.math]
    [ridley.schema]
    ;; Geometry
    [ridley.geometry.faces]
    [ridley.geometry.primitives]
    [ridley.geometry.operations]
    [ridley.geometry.warp]
    ;; Turtle
    [ridley.turtle.shape]
    [ridley.turtle.bezier]
    [ridley.turtle.extrusion]
    [ridley.turtle.path]
    [ridley.turtle.transform]
    [ridley.turtle.attachment]
    [ridley.turtle.shape-fn]
    [ridley.turtle.core]
    [ridley.turtle.loft]
    ;; 2D/3D operations
    [ridley.clipper.core]
    [ridley.voronoi.core]
    [ridley.manifold.core]))

;; Re-export key functions for convenient access from JS.
;; All namespaces are also available via their full CLJS names.

;; Turtle
(def make-turtle ridley.turtle.core/make-turtle)

;; Geometry primitives
(def box-mesh ridley.geometry.primitives/box-mesh)
(def sphere-mesh ridley.geometry.primitives/sphere-mesh)
(def cyl-mesh ridley.geometry.primitives/cyl-mesh)
(def cone-mesh ridley.geometry.primitives/cone-mesh)

;; 2D shapes
(def circle-shape ridley.turtle.shape/circle-shape)
(def rect-shape ridley.turtle.shape/rect-shape)
(def polygon-shape ridley.turtle.shape/polygon-shape)

;; Geometry operations
(def extrude ridley.geometry.operations/extrude)
(def revolve ridley.geometry.operations/revolve)

;; Manifold CSG
(def manifold-union ridley.manifold.core/union)
(def manifold-difference ridley.manifold.core/difference)
(def manifold-intersection ridley.manifold.core/intersection)
(def manifold-init! ridley.manifold.core/init!)

;; Math
(def v+ ridley.math/v+)
(def v- ridley.math/v-)
(def v* ridley.math/v*)
(def dot ridley.math/dot)
(def cross ridley.math/cross)
(def normalize ridley.math/normalize)

;; Clipper 2D
(def shape-union ridley.clipper.core/shape-union)
(def shape-difference ridley.clipper.core/shape-difference)
(def shape-intersection ridley.clipper.core/shape-intersection)
(def shape-offset ridley.clipper.core/shape-offset)

(def exports
  "Marker — all ridley-core namespaces are loaded via this ns's requires."
  true)
