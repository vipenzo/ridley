# Ridley — Examples

Working examples demonstrating Ridley's features.

---

## Basic Turtle Drawing (2D)

```clojure
;; Simple square
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)
(th 90)
(f 50)

;; Triangle
(f 60)
(th 120)
(f 60)
(th 120)
(f 60)

;; Zigzag pattern
(f 20) (th 45) (f 20) (th -90) (f 20) (th 45) (f 20)
```

---

## 3D Primitives

```clojure
;; Box at origin
(box 30)

;; Rectangular box
(box 40 20 10)

;; Sphere
(sphere 25)

;; Cylinder
(cyl 15 40)

;; Cone
(cone 20 5 30)
```

### Positioned Primitives

```clojure
;; Move then place
(f 50)
(box 20)

;; Multiple primitives
(box 20)
(f 40)
(sphere 15)
(f 40)
(cyl 10 30)
```

---

## Generative Operations

### Extrude

```clojure
;; Extrude a path along Z
(def my-shape
  (shape
    (f 30)
    (th 90)
    (f 20)
    (th 90)
    (f 30)))

(extrude-z my-shape 15)
```

### Revolve

```clojure
;; Revolve a profile to create a vase
(def vase-profile
  (path
    (f 20)
    (th 90)
    (f 5)
    (th -45)
    (f 15)
    (th 45)
    (f 10)))

(revolve vase-profile [0 1 0] 360 32)
```

---

## Profile Extrusion (Phase 3)

The new face-based modeling workflow: define a plane, draw a 2D profile, extrude.

### Basic Cylinder via Profile

```clojure
;; Set up a drawing plane at origin
(pen :3d :at [0 0 0] :normal [0 0 1] :heading [1 0 0])

;; Draw a circle profile
(circle 15)

;; Extrude along plane normal
(f 30)
```

### Box via Rectangle Profile

```clojure
(pen :3d :at [0 0 0] :normal [0 0 1] :heading [1 0 0])
(rect 40 20)
(f 25)
```

### Custom Polygon Extrusion

```clojure
(pen :3d :at [0 0 0] :normal [0 0 1] :heading [1 0 0])
(polygon [[-10 -10] [10 -10] [15 0] [10 10] [-10 10] [-15 0]])
(f 20)
```

### Offset Profile

Move the turtle before drawing to offset the profile center:

```clojure
(pen :3d :at [0 0 0] :normal [0 0 1] :heading [1 0 0])
(f 20)           ; Move 20 units along X on the plane
(circle 10)      ; Circle centered at [20, 0] on plane
(f 15)           ; Extrude
```

### Tilted Plane

Extrude on a non-axis-aligned plane:

```clojure
;; Plane tilted 45 degrees
(pen :3d
  :at [0 0 0]
  :normal [0 0.707 0.707]   ; 45 deg between Y and Z
  :heading [1 0 0])
(circle 12)
(f 25)
```

---

## Multiple Shapes

```clojure
;; Two cylinders side by side
(pen :3d :at [-30 0 0] :normal [0 0 1] :heading [1 0 0])
(circle 10)
(f 20)

(pen :3d :at [30 0 0] :normal [0 0 1] :heading [1 0 0])
(circle 10)
(f 20)
```

---

## Coming Soon

### Face-Based Modeling (not yet implemented)

```clojure
;; Create box, select face, draw profile, extrude
(box 50 50 20)
(pen :top)           ; Select top face
(circle 15)          ; Draw circle on face
(f 30)               ; Extrude up (add boss)

;; Create hole
(pen :top)
(circle 10)
(f -25)              ; Extrude down (subtract hole)
```
