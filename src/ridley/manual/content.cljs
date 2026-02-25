(ns ridley.manual.content
  "Manual content structure and internationalization.
   For Phase 10a, content is hardcoded. Later will load from EDN files.")

;; Page structure - defines hierarchy and code examples
;; Examples are language-agnostic (code is the same in all languages)
;; KEY PRINCIPLE: Every example must produce visible output in the viewport
(def structure
  {:sections
   [{:id :part-1
     :pages
     [{:id :hello-ridley
       :examples
       [{:id :first-cube
         :code "(register cube (box 30))"
         :auto-run true}
        {:id :first-tube
         :code "(register tube\n  (extrude (circle 10) (f 30)))"}]}
      {:id :ui-overview
       :see-also [:editor-commands]}
      {:id :editor-commands
       :see-also [:ui-overview :repl-usage]}
      {:id :the-turtle
       :see-also [:control-structures :extrude-basics]
       :examples
       [{:id :movement
         :code "(f 50)\n(th 90)\n(f 50)"}
        {:id :spiral-path
         :code "(dotimes [i 50]\n  (f i)\n  (th 90))"}]}
      {:id :clojure-basics
       :examples
       [{:id :define-value
         :code "(def size 20)\n(register cube (box size))"}
        {:id :define-function
         :code "(defn tube [r len]\n  (extrude (circle r) (f len)))\n\n(register t1 (tube 10 40))"}]}]}
    {:id :part-2
     :pages
     [{:id :extrude-basics
       :examples
       [{:id :circle-extrude
         :code "(register tube\n  (extrude (circle 15) (f 40)))"}
        {:id :rect-extrude
         :code "(register bar\n  (extrude (rect 30 20) (f 50)))"}
        {:id :bent-extrude
         :code "(register bent\n  (extrude (circle 8)\n    (f 30) (th 45) (f 30)))"}]}
      {:id :builtin-shapes
       :examples
       [{:id :circle-extrude
         :code "(register tube\n  (extrude (circle 15) (f 40)))"}
        {:id :rect-extrude
         :code "(register bar\n  (extrude (rect 30 20) (f 50)))"}
        {:id :polygon-extrude
         :code "(register hex\n  (extrude (polygon 6 15) (f 25)))"}
        {:id :star-extrude
         :code "(register star-bar\n  (extrude (star 5 20 10) (f 15)))"}
        {:id :poly-extrude
         :code "(register arrow\n  (extrude\n    (poly -3 -2  5 0  -3 2)\n    (f 15)))"}]}
      {:id :custom-shapes
       :examples
       [{:id :triangle-shape
         :code "(register bar\n  (extrude\n    (shape (th 120) (f 15) (th 150) (f 8) (th -90) (f 20) (th -90) (f 8) (th 150) (f 15))\n    (f 40)))"}
        {:id :l-shape
         :code "(register l-beam\n  (extrude\n    (shape\n      (f 30) (th 90) (f 10) (th 90)\n      (f 20) (th -90) (f 10) (th 90)\n      (f 10) (th 90) (f 20))\n    (f 50)))"}]}
      {:id :shape-booleans-2d
       :see-also [:builtin-shapes :custom-shapes]
       :examples
       [{:id :shape-diff-washer
         :code "(register washer\n  (extrude\n    (shape-difference (circle 20) (circle 15))\n    (f 10)))"}
        {:id :shape-union-blob
         :code "(register blob\n  (extrude\n    (shape-union (circle 15)\n      (translate-shape (circle 15) 12 0))\n    (f 10)))"}
        {:id :shape-intersection-lens
         :code "(register lens\n  (extrude\n    (shape-intersection (circle 15)\n      (translate-shape (circle 15) 12 0))\n    (f 5)))"}
        {:id :shape-offset-frame
         :code "(register frame\n  (extrude\n    (shape-difference\n      (shape-offset (rect 30 20) 5)\n      (rect 30 20))\n    (f 8)))"}
        {:id :shape-xor-cross
         :code "(register cross\n  (extrude\n    (shape-xor (rect 40 15) (rect 15 40))\n    (f 5)))"}]}
      {:id :reusable-shapes
       :examples
       [{:id :def-shape
         :code "(def my-circle (circle 12))\n\n(register tube1 (extrude my-circle (f 30)))\n(tv 90) (f 50) (tv -90) (register tube2 (extrude my-circle (f 50)))"}
        {:id :def-path
         :code "(def corner (path (f 30) (th 90) (f 30)))\n\n(register pipe\n  (extrude (circle 8) corner))"}
        {:id :follow-path
         :code "(def curve (path (f 30) (tv 30) (f 20) (bezier-to [0 60 0] [0 20 30] [0 40 -30])))\n\n;; Draw the curve with the turtle\n(follow-path curve)"}]}
      {:id :quick-path
       :see-also [:reusable-shapes :extrude-basics]
       :examples
       [{:id :qp-basic
         :code "(def zigzag (quick-path 30 90 30 -90 30))\n(register tube\n  (extrude (circle 5) zigzag))"}
        {:id :qp-vector
         :code "(def steps [20 45 20 45 20 45 20])\n(register pipe\n  (extrude (circle 6) (quick-path steps)))"}]}
      {:id :extrude-closed
       :examples
       [{:id :square-torus
         :code "(register torus\n  (extrude-closed (circle 5)\n    (f 30) (th 90)\n    (f 30) (th 90)\n    (f 30) (th 90)\n    (f 30)))"}
        {:id :hex-ring
         :code "(register hex-ring\n  (extrude-closed (polygon 6 4)\n    (dotimes [_ 6]\n      (f 25) (th 60))))"}]}
      {:id :shape-transforms
       :examples
       [{:id :scale-shape
         :code "(register tapered\n  (loft (circle 20) (circle 10) (f 40)))"}
        {:id :morph-shapes
         :code "(register morph\n  (loft (rect 30 30) (circle 15) (f 40)))"}
        {:id :loft-transform
         :code "(register cone\n  (loft (circle 20)\n    #(scale %1 (- 1 %2))\n    (f 40)))"}
        {:id :loft-twist
         :code "(register twisted\n  (loft (rect 20 10)\n    #(rotate-shape %1 (* %2 90))\n    (f 40)))"}]}
      {:id :joint-modes
       :examples
       [{:id :joint-flat
         :code "(joint-mode :flat)\n(register flat-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}
        {:id :joint-round
         :code "(joint-mode :round)\n(register round-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}
        {:id :joint-tapered
         :code "(joint-mode :tapered)\n(register tapered-bend\n  (extrude (rect 15 15)\n    (f 30) (th 90) (f 30)))"}]}
      {:id :resolution-settings
       :examples
       [{:id :resolution-n
         :code "(resolution :n 20)\n(joint-mode :round)\n(register res-demo\n  (extrude (circle 15)\n    (f 50) (th 90) (f 50)))"}
        {:id :resolution-a
         :code "(resolution :a 15)\n(register angle-res\n  (extrude (circle 15)\n    (f 20) (arc-h 15 180) (f 20)))"}
        {:id :resolution-steps
         :code "(register custom-steps\n  (extrude (circle 15)\n    (f 20) (arc-h 15 180 :steps 32) (f 20)))"}]}]}
    {:id :part-3
     :pages
     [{:id :mesh-basics
       :see-also [:boolean-operations :attach-meshes]
       :examples
       [{:id :mesh-register
         :code "(register cube (box 30))"}
        {:id :mesh-info
         :code "(register tube\n  (extrude (circle 10) (f 40)))\n(println (info :tube))"}
        {:id :mesh-get-mesh
         :code "(def a (box 20))\n(def b (cyl 8 30))\n(register ab\n  (mesh-union a b))"}]}
      {:id :boolean-operations
       :examples
       [{:id :bool-union
         :code "(def a (box 30))\n(def b (attach (cyl 12 40)\n  (f 15) (tv 90) (f 15) (tv -90)))\n(register ab (mesh-union a b))"}
        {:id :bool-difference
         :code "(def a (box 30))\n(def b (attach (cyl 12 40)\n  (f 15) (tv 90) (f 15) (tv -90)))\n(register ab (mesh-difference a b))"}
        {:id :bool-intersection
         :code "(def a (box 30))\n(def b (attach (cyl 12 40)\n  (f 15) (tv 90) (f 15) (tv -90)))\n(register ab (mesh-intersection a b))"}
        {:id :bool-hull
         :code "(def p1 (sphere 10))\n(def p2 (attach (sphere 10) (f 40)))\n(def p3 (attach (sphere 10) (f 20) (th 90) (f 30)))\n(register hull-shape\n  (mesh-hull p1 p2 p3))"}
        {:id :bool-union-for
         :code "(register row\n  (mesh-union\n    (for [i (range 10)]\n      (attach (cyl 5 30) (lt (* i 9))))))"}]}
      {:id :slice-mesh
       :examples
       [{:id :slice-basic
         :code "(def cup\n  (revolve\n    (shape (f 20) (th -45) (f 30) (th -90) (f 30) (th -60) (f 15))))\n(stamp (slice-mesh cup))\n(register cup (attach cup (u 50)))"}
        {:id :slice-multiple
         :code "(def donut\n  (attach\n    (extrude (circle 8) (arc-h 25 360))\n    (f 25)))\n(f 20)\n(stamp (slice-mesh donut))\n(tv 90)\n(stamp (slice-mesh donut))"}]}
      {:id :attach-meshes
       :examples
       [{:id :attach-basic
         :code "(register base (box 40 40 10))\n\n(register top\n  (attach (cyl 10 50) (f 31) (tv 90)))"}
        {:id :attach-boolean
         :code "(register drilled\n  (mesh-difference\n    (box 40)\n    (attach (cyl 10 50)\n      (f 20) (tv 90) (f 20))))"}
        {:id :attach-copy
         :code "(register original (box 10))\n(register copy1 (attach original (f 30)))\n(register copy2 (attach copy1 (tv 90) (f 30)))"}
        {:id :attach-copy-rotated
         :code "(register original (box 10 30 5))\n(register copy1 (attach original (f 30)))\n(register copy2 (attach copy1 (tv 90) (tr 90) (f 30)))"}]}
      {:id :attach-faces
       :examples
       [{:id :attach-face-frustum
         :code "(register frustum\n  (attach-face (box 30) :top\n    (inset 10) (f 30)))"}
        {:id :clone-face-spike
         :code "(register spike\n  (clone-face (box 30) :top\n    (inset 10) (f 30)))"}]}
      {:id :warp-deformation
       :see-also [:boolean-operations :attach-meshes]
       :examples
       [{:id :warp-inflate
         :code "(register bump\n  (warp (box 33)\n    (attach (sphere 24.5) (f 13))\n    (inflate 10.5) :subdivide 4))"
         :auto-run true}
        {:id :warp-dent
         :code "(register dented\n  (warp (sphere 30 32 16)\n    (attach (sphere 17.5) (f 23))\n    (dent 11.8)))"}
        {:id :warp-roughen
         :code "(register rough\n  (warp (sphere 20 54.2 31.6) (attach (sphere 12) (f 13)) (roughen 4.2 5.3) :subdivide 3))"}]}]}
    {:id :part-4
     :pages
     [{:id :mark-goto-pushpop
       :see-also [:the-turtle :arcs-beziers]
       :examples
       [{:id :mark-goto-basic
         :code ";; Define a layout path with named marks\n(def layout (path (f 50) (mark :top) (th 90) (f 30) (mark :side)))\n\n;; with-path resolves marks as anchors\n(register base (box 20 20 5))\n(with-path layout\n  (goto :top)\n  (register tower (cyl 8 60))\n  (goto :side)\n  (register beacon (cyl 5 30)))"}
        {:id :turtle-branch
         :code "(register trunk (extrude (circle 5) (f 50)))\n(turtle\n  (th 45) (register b1 (extrude (circle 3) (f 30))))\n(turtle\n  (th -45) (register b2 (extrude (circle 3) (f 30))))\n(register top (extrude (circle 3) (f 20)))"}
        {:id :path-follow
         :code ";; follow splices another path into the current recording\n(def segment (path (f 20) (th 90)))\n(def full (path (follow segment) (follow segment) (follow segment) (follow segment)))\n\n(register square-tube\n  (extrude-closed (circle 5) full))"}]}
      {:id :arcs-beziers
       :see-also [:mark-goto-pushpop :resolution-settings]
       :examples
       [{:id :arc-h-basic
         :code "(register curved-tube\n  (extrude (circle 8)\n    (f 20) (arc-h 30 180) (f 20)))"}
        {:id :arc-v-basic
         :code "(register arc-loop\n  (extrude (circle 5)\n    (f 20) (arc-v 25 180) (f 20)))"}
        {:id :bezier-auto
         :code ";; Define a path with marks for start and end\n(def layout (path (mark :start) (f 60) (th 90) (f 40) (mark :end)))\n\n(with-path layout\n  (goto :start)\n  (register curve\n    (extrude (circle 4)\n      (bezier-to-anchor :end))))"}
        {:id :bezier-control
         :code "(register s-curve\n  (extrude (circle 4)\n    (bezier-to [0 60 0] [0 20 30] [0 40 -30])))"}
        {:id :bezier-as
         :code "(register smooth-bend\n  (extrude (circle 5)\n    (bezier-as\n      (path (f 30) (th 90) (f 30)))))"}
        {:id :bezier-as-cubic
         :code "(register smooth-spline\n  (extrude (circle 5)\n    (bezier-as\n      (path (f 30) (th 90) (f 20) (th -45) (f 25))\n      :cubic true)))"}]}
      {:id :bloft-curves
       :see-also [:arcs-beziers :shape-transforms :resolution-settings]
       :examples
       [{:id :bloft-basic
         :code "(def curved-path\n  (path (f 40) (th 120) (f 30) (th 120) (f 40)))\n\n(register tube\n  (bloft (circle 6) identity\n    (path (bezier-as curved-path))))"}
        {:id :bloft-taper
         :code "(def tight-curve\n  (path (f 30) (th 150) (f 25)))\n\n(register tapered\n  (bloft (circle 8)\n    #(scale %1 (- 1 (* 0.6 %2)))\n    (path (bezier-as tight-curve))))"}
        {:id :bloft-steps
         :code "(def snake-path\n  (path (f 50) (th 100) (f 20) (th 80)\n    (f 20) (th 50) (f 40)))\n\n(register smooth-loop\n  (bloft-n 128 (tapered (circle 5 64) 0.2)\n    (path (bezier-as snake-path\n            :tension 0.4 :steps 64))))"}]}
      {:id :colors-materials
       :see-also [:extrude-basics :mesh-basics]
       :examples
       [{:id :color-basic
         :code "(color 0xff0000)\n(register red-box (box 20))\n(f 40)\n(color 0x00ff00)\n(register green-cyl (cyl 10 30))"}
        {:id :material-pbr
         :code "(material :color 0x8888ff :metalness 0.8 :roughness 0.2)\n(register shiny (sphere 15))\n(f 40)\n(material :color 0xffaa00 :metalness 0.1 :roughness 0.9)\n(register matte (sphere 15))"}
        {:id :material-reset
         :code "(color 0xff0000)\n(register red (box 15))\n(f 30)\n(reset-material)\n(register default (box 15))"}]}
      {:id :text-shapes
       :see-also [:extrude-basics :custom-shapes :curves-beziers]
       :examples
       [{:id :text-basic
         :code "(register title\n  (extrude (text-shape \"HI\" :size 30) (f 5)))"}
        {:id :text-large
         :code "(register sign\n  (extrude (text-shape \"RIDLEY\" :size 20) (f 3)))"}
        {:id :extrude-text
         :code "(register hello\n  (extrude-text \"Hello\" :size 25 :depth 4))"}
        {:id :text-on-arc
         :code "(def arc-path (path (arc-h 50 180)))\n\n(register curved\n  (text-on-path \"CURVED TEXT\" arc-path\n    :size 12 :depth 3 :align :center))"}
        {:id :text-on-spiral
         :code "(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))\n\n(register spiral-text\n  (turtle :preserve-up\n    (text-on-path\n      \"Once upon a time in a galaxy far far away there was a spiral that went up and up and up and never stopped and she's buying a stairway to heaven\"\n      spiral :size 6 :depth 1.2)))"}]}]}
    {:id :part-5
     :pages
     [{:id :debug-panels
       :see-also [:debug-techniques]
       :examples
       [{:id :panel-basic
         :code "(register P1 (panel 60 40))\n(out P1 \"Hello World!\")"}
        {:id :panel-attach
         :code "(register cube (box 30))\n(register P1\n  (attach (panel 25 8)\n    (f 25) (tv 90) (f 25)))\n(out P1 \"This is a cube\")"}
        {:id :panel-multiline
         :code "(register P1 (panel 80 60))\n(out P1 \"Line 1\")\n(append P1 \"\\nLine 2\")\n(append P1 \"\\nLine 3\")"}]}
      {:id :debug-techniques
       :see-also [:debug-panels :repl-usage]
       :examples
       [{:id :tap-function
         :code "(register cube\n  (box (T \"size\" (* 10 3))))"
         :no-run true}
        {:id :inspect-turtle
         :code "(f 50)\n(println \"pos:\" (turtle-position))\n(th 45)\n(println \"heading:\" (turtle-heading))"
         :no-run true}
        {:id :step-by-step
         :code "(println \"Step 1: create base\")\n(register base (box 40 40 10))\n(println \"Step 2: move up\")\n(f 10)\n(println \"Step 3: create top\")\n(register top (cyl 15 20))"
         :no-run true}]}
      {:id :tweak-interactive
       :see-also [:debug-techniques]
       :examples
       [{:id :tweak-basic
         :code "(tweak (extrude (circle 15) (f 30)))"
         :auto-run true
         :no-run true}
        {:id :tweak-indexed
         :code "(tweak 2 (extrude (circle 15)\n  (f 30) (th 90) (f 20)))"
         :no-run true}
        {:id :tweak-all
         :code "(tweak :all (extrude (circle 10)\n  (f 30) (th 90) (f 20)))"
         :no-run true}
        {:id :tweak-registered
         :code "(register A (sphere 20))\n(tweak :A)"
         :no-run true}]}
      {:id :stamp-preview
       :see-also [:debug-techniques :builtin-shapes :shape-booleans-2d]
       :examples
       [{:id :stamp-basic
         :code "(stamp (circle 15))"}
        {:id :stamp-positioned
         :code "(f 30) (tv 45)\n(stamp (circle 10))\n(f 20)\n(stamp (rect 15 10))"}
        {:id :stamp-with-holes
         :code "(stamp\n  (shape-difference\n    (circle 20) (circle 15)))"}]}
      {:id :follow-path-lines
       :see-also [:extrude-basics :debug-techniques]
       :examples
       [{:id :follow-basic
         :code "(def curve\n  (path (f 30) (tv 30) (f 20)\n    (bezier-to [0 60 0] [0 20 30] [0 40 -30])))\n\n(follow-path curve)"}
        {:id :follow-visibility
         :code "(def star-path\n  (path (dotimes [_ 5] (f 40) (th 144))))\n\n(follow-path star-path)\n\n;; Toggle visibility:\n;; (hide-lines)\n;; (show-lines)\n;; (lines-visible?)"}]}
      {:id :measurement
       :see-also [:debug-techniques :mesh-basics :tweak-interactive]
       :examples
       [{:id :distance-basic
         :code "(register A (box 30))\n(register B\n  (attach (box 20) (f 60)))\n\n(println (distance :A :B))"}
        {:id :ruler-faces
         :code "(register A (box 30))\n(register B\n  (attach (box 20) (f 60)))\n\n(ruler :A :top :B :bottom)"}
        {:id :bounds-info
         :code "(register A (box 30 20 10))\n(println (bounds :A))"}
        {:id :area-face
         :code "(register A (box 30))\n(println (area :A :top))"}]}]}
    {:id :part-6
     :pages
     [{:id :control-structures
       :see-also [:clojure-basics :local-variables]
       :examples
       [{:id :dotimes-ignore
         :code "(dotimes [_ 4]\n  (f 20)\n  (th 90))"}
        {:id :for-comprehension
         :code "(register tubes\n  (for [size [10 20 30]]\n    (attach (extrude (circle size) (f 20))\n      (f size))))"}
        {:id :when-conditional
         :code "(dotimes [i 10]\n  (f 10)\n  (when (odd? i)\n    (th 30)))"}]}
      {:id :local-variables
       :see-also [:clojure-basics :control-structures]
       :examples
       [{:id :let-basics
         :code "(let [r 15\n      h 40]\n  (register cyl\n    (extrude (circle r) (f h))))"}
        {:id :let-computed
         :code "(let [base 20\n      half (/ base 2)\n      quarter (/ half 2)]\n  (register box1 (box base))\n  (f (+ half quarter))\n  (register box2 (box half)))"}]}
      {:id :anonymous-functions
       :see-also [:clojure-basics :control-structures :shape-transforms]
       :examples
       [{:id :anon-shorthand
         :code "(register columns\n  (map #(attach (box 5) (th (* % 36)) (f 10))\n    (range 10)))"}
        {:id :anon-fn-form
         :code "(register cone\n  (loft (circle 20)\n    (fn [shape t] (scale shape t))\n    (f 40)))"}
        {:id :anon-loft-twist
         :code "(register twisted\n  (loft (rect 20 10)\n    #(rotate-shape %1 (* %2 90))\n    (f 40)))"}]}
      {:id :map-and-reduce
       :see-also [:control-structures :anonymous-functions]
       :examples
       [{:id :map-basic
         :code "(register sizes\n  (for [r (map #(* % 5) [1 2 3 4])]\n    (attach (sphere r) (f (* r 3)))))"}
        {:id :reduce-union
         :code "(register merged\n  (reduce mesh-union\n    (for [i (range 5)]\n      (attach (box 10) (f (* i 15))))))"}
        {:id :map-indexed
         :code "(register staircase\n  (for [[i size] (map-indexed vector [8 12 16 20])]\n    (attach (extrude (circle size) (f 5))\n      (f (* i 8)))))"}]}
      {:id :repl-usage
       :examples
       [{:id :repl-quick-test
         :code "(+ 1 2 3)"
         :no-run true}
        {:id :repl-println
         :code "(println \"Hello from REPL!\")\n(println \"2 + 2 =\" (+ 2 2))"
         :no-run true}]}]}
    {:id :gallery
     :pages
     [{:id :gallery-twisted-vase
       :examples
       [{:id :twisted-vase-code
         :code "(def n-flutes 12)\n(def twist-amount 90)\n(def height 80)\n(def base-radius 20)\n\n(def vase\n  (loft-n 96\n    (-> (circle base-radius 64)\n        (fluted :flutes n-flutes :depth 0.15)\n        (twisted :angle twist-amount)\n        (shape-fn (fn [shape t]\n                    (let [belly (+ 1.0 (* 0.3 (sin (* t PI))))\n                          neck (max 0.15 (- 1.0 (* 0.25 (pow (max 0 (- t 0.6)) 2) 40)))\n                          lip (if (> t 0.85)\n                                (+ 1.0 (* 90 (pow (- t 0.85) 2)))\n                                1.0)\n                          s (* belly neck lip)]\n                      (scale-shape shape s s)))))\n    (f height)))\n\n(register vase vase)"}]}
      {:id :gallery-recursive-tree
       :examples
       [{:id :recursive-tree-code
         :code "(def max-depth 6)\n(def n-branches 3)\n(def spread-angle 35)\n(def taper 0.65)\n(def golden-angle 137.508)\n\n(defn branch [depth length radius]\n  (when (> depth 0)\n    (let [trunk (loft (tapered (circle radius) :to taper) (f length))]\n      (f length)\n      (cons trunk\n        (mapcat (fn [i]\n          (turtle\n            (tr (* i (/ 360 n-branches)))\n            (tv spread-angle)\n            (tr (* i golden-angle))\n            (branch (dec depth)\n                    (* length taper)\n                    (* radius taper))))\n          (range n-branches))))))\n\n(tv 90)\n(register tree (branch max-depth 30 3))"}]}
      {:id :gallery-dice
       :examples
       [{:id :dice-code
         :code "(def die-size 20)\n(def half (/ die-size 2))\n(def pip-radius 2.0)\n(def pip-depth 1.2)\n(def pip-spread 5.5)\n\n(def body (box die-size))\n\n(defn pip [x-off y-off]\n  (turtle\n    (f (- half pip-depth))\n    (rt x-off) (u y-off)\n    (sphere pip-radius)))\n\n(defn face-1 [] [(pip 0 0)])\n(defn face-2 [] [(pip (- pip-spread) pip-spread)\n                  (pip pip-spread (- pip-spread))])\n(defn face-3 [] (concat (face-1) (face-2)))\n(defn face-4 [] [(pip (- pip-spread) pip-spread)\n                  (pip pip-spread pip-spread)\n                  (pip (- pip-spread) (- pip-spread))\n                  (pip pip-spread (- pip-spread))])\n(defn face-5 [] (concat (face-4) (face-1)))\n(defn face-6 [] [(pip (- pip-spread) pip-spread)\n                  (pip (- pip-spread) 0)\n                  (pip (- pip-spread) (- pip-spread))\n                  (pip pip-spread pip-spread)\n                  (pip pip-spread 0)\n                  (pip pip-spread (- pip-spread))])\n\n(def all-pips\n  (concat-meshes\n    (concat\n      (face-1)\n      (turtle (th 180) (face-6))\n      (turtle (th -90) (face-2))\n      (turtle (th 90) (face-5))\n      (turtle (tv 90) (face-3))\n      (turtle (tv -90) (face-4)))))\n\n(def die (mesh-difference body all-pips))\n(register die die)"}]}
      {:id :gallery-embossed-column
       :examples
       [{:id :embossed-column-code
         :code "(def col-radius 15)\n(def col-height 80)\n(def n-flutes 16)\n(def flute-depth 0.12)\n\n(def letters (text-shape \"RIDLEY\" :size 20))\n(def text-mesh (turtle (tv 90) (concat-meshes (extrude letters (f 3)))))\n(def text-bounds (bounds text-mesh))\n(def text-hm\n  (mesh-to-heightmap text-mesh\n    :resolution 128\n    :offset-x (get-in text-bounds [:min 0])\n    :offset-y (get-in text-bounds [:min 1])\n    :length-x (- (get-in text-bounds [:max 0]) (get-in text-bounds [:min 0]))\n    :length-y (- (get-in text-bounds [:max 1]) (get-in text-bounds [:min 1]))))\n\n(def shaft\n  (loft-n 96\n    (-> (circle col-radius 64)\n        (fluted :flutes n-flutes :depth flute-depth)\n        (heightmap text-hm :amplitude 1.5 :center true :tile-x 2 :tile-y 1))\n    (f col-height)))\n\n(def base\n  (attach\n    (loft-n 8\n      (tapered (circle (* col-radius 1.4) 64) :to (/ 1 1.4))\n      (f 6))\n    (tv 180)))\n\n(def capital\n  (attach\n    (loft-n 8\n      (tapered (circle col-radius 64) :to 1.3)\n      (f 5))\n    (f col-height)))\n\n(def column (concat-meshes [base shaft capital]))\n(register column column)"}]}
      {:id :gallery-canvas-weave
       :examples
       [{:id :canvas-weave-code
         :code "(defn canvas [thickness compactness]\n  (let [step121 (* thickness 1.1314)\n        spacing (* thickness 1.8)\n        wave (+ (* 2 thickness) (* 2 step121 (cos (/ PI 4))))\n        n-strands 6\n        n-waves 4\n\n        make-strands (fn [n]\n                       (concat (for [i (range n)]\n                                 (let [[g ud] (if (odd? i) [180 0] [0 (* -0.5 thickness)])\n                                       pat (path (dotimes [_ n-waves]\n                                                   (f thickness) (tv 45) (f step121) (tv -45)\n                                                   (f thickness) (tv -45) (f step121) (tv 45)))]\n                                   (attach (loft (circle (* thickness compactness)) identity\n                                             (bezier-as pat :tension 0.3))\n                                     (lt (* i spacing)) (tr g) (u ud))))))\n\n        m1 (concat-meshes (make-strands n-strands))\n        m2 (attach m1 (tr 180)\n             (th 90) (rt -1) (f 1)\n             (d (* thickness compactness 0.5)))\n        weave (concat-meshes [m1 m2])\n\n        b (bounds weave)\n        center-x (* 0.5 (+ (get-in b [:min 0]) (get-in b [:max 0])))\n        center-y (* 0.5 (+ (get-in b [:min 1]) (get-in b [:max 1])))\n        tile-x (* 2 wave)\n        tile-y (* 2 wave)\n\n        hm (mesh-to-heightmap weave :resolution 256\n             :offset-x (- center-x (* 0.5 tile-x))\n             :offset-y (- center-y (* 0.5 tile-y))\n             :length-x tile-x\n             :length-y tile-y)]\n    [weave hm]))\n\n(def weave-hm (canvas 2 0.6))\n\n(def tazza\n  (let [esterno (attach\n                  (loft-n 128\n                    (heightmap (circle 30 128) (weave-hm 1)\n                      :amplitude 4 :center true\n                      :tile-x 8 :tile-y 4)\n                    (f 60)))\n        interno (attach (extrude (circle 26 128) (f 60)) (f 4))]\n    (mesh-difference esterno interno)))\n\n(register A tazza)"}]}]}]})

;; Internationalization - text content for each language
(def i18n
  {:en
   {:sections
    {:part-1 {:title "Getting Started"}
     :part-2 {:title "Shapes & Extrusion"}
     :part-3 {:title "Mesh Manipulation"}
     :part-4 {:title "Advanced Features"}
     :part-5 {:title "Debug Help"}
     :part-6 {:title "Advanced Clojure"}
     :gallery {:title "Gallery"}}
    :pages
    {:hello-ridley
     {:title "Hello Ridley"
      :content "Welcome to Ridley, a 3D modeling environment based on turtle graphics.

In Ridley, you create 3D objects by writing code. The simplest way is with primitives like `box` and `cyl`. But the real power comes from **extrusion** — sweeping a 2D shape along a path to create 3D geometry.

Let's see both approaches:"
      :examples
      {:first-cube {:caption "A simple cube"
                    :description "The `register` command gives a name to your shape so it appears in the viewport. `box` creates a cube with the given size."}
       :first-tube {:caption "Your first extrusion"
                    :description "`extrude` sweeps a 2D shape (here, a circle) along a path (here, forward 30 units). This creates a tube."}}}

     :ui-overview
     {:title "The Interface"
      :content "Ridley has three main areas:

**Editor** (left) — Write your code here. This is where you define shapes, functions, and geometry.

**Viewport** (right) — The 3D view showing your models. You can rotate (drag), zoom (scroll), and pan (right-drag) to explore. Hold **X**, **Y**, or **Z** while dragging to rotate around that world axis only.

**REPL** (bottom) — An interactive command line. Type expressions and press Enter to evaluate them instantly.

**Editor Toolbar:**
- **▶ Run** — Execute your code (same as Cmd+Enter)
- **Save** — Download your code as a .clj file
- **Load** — Load a .clj file from disk
- **Manual** — Open this manual

**Viewport Toolbar:**
- **Grid** — Toggle the reference grid
- **Axes** — Toggle X/Y/Z axis lines
- **Turtle** — Toggle the turtle indicator (shows position/direction)
- **Lines** — Toggle construction lines (pen traces)
- **Normals** — Toggle face normal visualization
- **Reset** — Reset camera to default view
- **Export STL** — Download visible meshes as STL file"}

     :editor-commands
     {:title "Editor Commands"
      :content "**Editor:**
- **Cmd+Enter** (Mac) or **Ctrl+Enter** (Windows/Linux) — Run all code
- Click the **▶ Run** button for the same effect

**REPL:**
- **Enter** — Evaluate the current line
- **↑/↓** — Navigate command history

**Paredit** (structural editing):
Paredit keeps your parentheses balanced automatically. Key commands:

- **Tab** — Indent current line
- **(** or **[** or **{** — Auto-insert matching bracket
- **Backspace** on empty brackets — Delete the pair
- **Ctrl+K** — Kill (cut) to end of line
- **Ctrl+Shift+K** — Kill the enclosing expression
- **Shift+Cmd+K** — Slurp: pull next element into parentheses
- **Shift+Cmd+J** — Barf: push last element out of parentheses

Example — Slurp: with cursor in `(+ 1 2|) 3`, Slurp → `(+ 1 2 3)`
Barf: with cursor in `(+ 1 2 3|)`, Barf → `(+ 1 2) 3`

**Search:**
- **Cmd+F** / **Ctrl+F** — Find in editor
- **Cmd+G** / **Ctrl+G** — Find next

**Undo/Redo:**
- **Cmd+Z** / **Ctrl+Z** — Undo
- **Cmd+Shift+Z** / **Ctrl+Shift+Z** — Redo"}

     :the-turtle
     {:title "The Turtle"
      :content "Ridley uses a **turtle** — an invisible cursor that moves through 3D space. As it moves, it draws lines that you can see in the viewport.

The turtle has:
- A **position** in 3D space
- A **heading** (the direction it's facing)
- An **up** vector (which way is \"up\" for the turtle)

Basic movement commands:
- `(f distance)` - move forward
- `(th angle)` - turn horizontally (yaw)
- `(tv angle)` - turn vertically (pitch)
- `(tr angle)` - roll"
      :examples
      {:movement {:caption "Basic movement"
                  :description "Move forward 50 units, turn 90°, then move forward again. The turtle draws visible lines as it moves."}
       :spiral-path {:caption "Drawing a spiral"
                     :description "`dotimes` repeats commands. The variable `i` counts from 0 to 49, so each forward step gets longer, creating a spiral."}}}

     :clojure-basics
     {:title "Clojure Basics"
      :content "Ridley code uses Clojure syntax. Here's what you need to know:

**Parentheses** — Every command is wrapped in parentheses: `(command arg1 arg2)`

**def** — Create a named value: `(def name value)`

**defn** — Create a function: `(defn name [args] body)`

These let you build reusable pieces and keep your code organized.

**Learning More**

To learn more about Clojure, check out these resources:

- [Clojure for the Brave and True](https://www.braveclojure.com/) — A free online book that's both fun and comprehensive

- [ClojureDocs](https://clojuredocs.org/) — Quick reference with examples for all core functions"
      :examples
      {:define-value {:caption "Define a value"
                      :description "Use `def` to name a value. Here we define `size` and use it to create a cube."}
       :define-function {:caption "Define a function"
                         :description "Use `defn` to create reusable functions. This `tube` function takes radius and length parameters."}}}

     :extrude-basics
     {:title "Extrusion"
      :content "**Extrusion** is the core concept in Ridley. You take a 2D shape and sweep it along a path to create 3D geometry.

```
(extrude shape movements...)
```

The **shape** defines the cross-section. The **movements** define the path the turtle follows. As the turtle moves, the shape is swept along, creating a solid."
      :examples
      {:circle-extrude {:caption "Circle → Tube"
                        :description "A circle extruded forward becomes a tube (cylinder)."}
       :rect-extrude {:caption "Rectangle → Bar"
                      :description "A rectangle extruded forward becomes a rectangular bar."}
       :bent-extrude {:caption "Bent path"
                      :description "The path can include turns. Here we create a bent tube by turning 45° mid-extrusion."}}}

     :builtin-shapes
     {:title "Built-in Shapes"
      :content "Ridley provides several 2D shapes you can extrude:

- `(circle radius)` — a circle
- `(rect width height)` — a rectangle
- `(polygon n radius)` — regular polygon with n sides
- `(poly x1 y1 x2 y2 ...)` — arbitrary polygon from coordinate pairs
- `(star points outer inner)` — a star shape

Each shape becomes the cross-section of your extruded solid."
      :examples
      {:circle-extrude {:caption "Circle"
                        :description "`circle` creates a circle with the given radius. Extruded, it becomes a tube."}
       :rect-extrude {:caption "Rectangle"
                      :description "`rect` creates a rectangle with width and height. Extruded, it becomes a bar."}
       :polygon-extrude {:caption "Polygon"
                         :description "`polygon` creates regular n-sided polygons. A hexagon (6 sides) extruded becomes a hex bar."}
       :star-extrude {:caption "Star"
                      :description "`star` creates star shapes. The parameters are: points, outer radius, inner radius."}
       :poly-extrude {:caption "Poly"
                      :description "`poly` creates arbitrary polygons from flat x y coordinate pairs. The origin [0,0] is at the turtle's position. Also accepts a vector: `(poly [x1 y1 x2 y2 ...])`."}}}


     :custom-shapes
     {:title "Custom Shapes"
      :content "The `shape` macro lets you draw your own 2D shapes using turtle movements. The turtle traces a closed path, and that path becomes your shape.

```
(shape movements...)
```

The shape automatically closes — the last point connects back to the first."
      :examples
      {:triangle-shape {:caption "Triangle"
                        :description "Turtle turns and forward movements trace a triangular cross-section. Extruded, it becomes a bar."}
       :l-shape {:caption "L-shape"
                 :description "More complex paths create more complex cross-sections. This L-shape becomes an L-beam when extruded."}}}

     :shape-booleans-2d
     {:title "2D Shape Booleans"
      :content "Combine 2D shapes using boolean operations before extruding. This lets you create complex cross-sections from simple shapes.

```
(shape-union a b)        ; merge two shapes
(shape-difference a b)   ; cut B from A
(shape-intersection a b) ; keep overlap only
(shape-xor a b)          ; keep non-overlapping parts
(shape-offset shape d)   ; expand (d>0) or shrink (d<0)
```

These operate on 2D shapes (circles, rects, polygons, custom shapes) and return new shapes you can extrude, loft, or revolve.

Use `translate-shape` to position shapes before combining:
```
(translate-shape (circle 10) 15 0)  ; move 15 units in X
```"
      :examples
      {:shape-diff-washer {:caption "Washer (difference)"
                           :description "`shape-difference` subtracts the inner circle from the outer, creating a ring cross-section. Extruded, it becomes a tube with a hollow center."}
       :shape-union-blob {:caption "Blob (union)"
                          :description "`shape-union` merges two overlapping circles into one shape. `translate-shape` offsets the second circle before combining."}
       :shape-intersection-lens {:caption "Lens (intersection)"
                                 :description "`shape-intersection` keeps only the area where both circles overlap, creating a lens-shaped cross-section."}
       :shape-offset-frame {:caption "Frame (offset)"
                            :description "`shape-offset` expands a shape outward. Subtracting the original from the expanded version creates a uniform-width frame."}
       :shape-xor-cross {:caption "Cross (xor)"
                         :description "`shape-xor` keeps the parts that don't overlap — the symmetric difference. Two crossing rectangles produce a cross with the center cut out."}}}

     :reusable-shapes
     {:title "Reusable Shapes"
      :content "Shapes are just data — you can store them in variables and reuse them.

```
(def my-shape (circle 10))
(extrude my-shape (f 30))
```

Similarly, paths can be captured with the `path` macro and reused across multiple extrusions."
      :examples
      {:def-shape {:caption "Reusing a shape"
                   :description "Define a shape once, use it multiple times. Both tubes use the same circle but different lengths."}
       :def-path {:caption "Reusing a path"
                  :description "Paths can also be stored and reused. The `path` macro captures turtle movements for later use."}
       :follow-path {:caption "Drawing a path"
                     :description "Use `follow-path` to make the turtle trace a stored path, drawing visible lines in the viewport."}}}

     :quick-path
     {:title "Quick Path"
      :content "`quick-path` creates a path from a compact list of numbers. Values alternate between **forward distance** and **turn angle**:

```
(quick-path f1 th1 f2 th2 ... fN)
```

This is equivalent to:

```
(path (f f1) (th th1) (f f2) (th th2) ... (f fN))
```

You can pass the numbers as separate arguments or as a single vector — useful when the sequence is computed or stored in a variable."
      :examples
      {:qp-basic {:caption "Zigzag path"
                   :description "Numbers alternate: forward 30, turn 90°, forward 30, turn -90°, forward 30. The result is a zigzag tube."}
       :qp-vector {:caption "Path from vector"
                   :description "When the numbers are in a vector, `quick-path` accepts it directly. Handy for computed or parameterized paths."}}}

     :extrude-closed
     {:title "Closed Extrusion"
      :content "`extrude-closed` creates shapes where the path forms a closed loop — like a torus or a frame. The last ring connects back to the first ring, with no end caps.

```
(extrude-closed shape movements...)
```

The path must return to (approximately) the starting position and orientation."
      :examples
      {:square-torus {:caption "Square torus"
                      :description "A square path with a circular cross-section creates a torus with flat sides."}
       :hex-ring {:caption "Hexagonal ring"
                  :description "A hexagonal path with a hexagonal cross-section. The shape sweeps around the entire loop."}}}

     :shape-transforms
     {:title "Shape Transitions"
      :content "The `loft` command lets you transition between different shapes along the extrusion path.

**Two-shape loft** — smoothly morphs from one shape to another:

```
(loft start-shape end-shape movements...)
```

**Transform function loft** — applies a function that controls the shape at each step:

```
(loft shape transform-fn movements...)
```

The transform function receives two arguments: the original shape and a progress value `t` from 0.0 (start) to 1.0 (end). Use `loft-n` for a custom number of steps (default is 16):

```
(loft-n steps shape transform-fn movements...)
```"
      :examples
      {:scale-shape {:caption "Tapered cone"
                     :description "Loft from a large circle to a small circle creates a tapered cone."}
       :morph-shapes {:caption "Shape morphing"
                      :description "Loft can transition between completely different shapes — here, from a square to a circle."}
       :loft-transform {:caption "Transform function"
                        :description "A transform function scales the shape based on progress, creating a cone from a single shape."}
       :loft-twist {:caption "Twist"
                    :description "Rotating the shape as it extrudes creates a twist effect."}}}

     :joint-modes
     {:title "Joint Modes"
      :content "When an extrusion path has corners (turns), the `joint-mode` command controls how the corner junction is generated.

```
(joint-mode :flat)    ; direct connection (default)
(joint-mode :round)   ; smooth rounded corner
(joint-mode :tapered) ; tapered intermediate ring
```

The joint mode affects how the shape transitions through corners, impacting both aesthetics and geometry."
      :examples
      {:joint-flat {:caption "Flat joint"
                    :description "`:flat` connects rings directly at corners. Simple but can show sharp edges."}
       :joint-round {:caption "Round joint"
                     :description "`:round` adds intermediate rings that arc smoothly through the corner."}
       :joint-tapered {:caption "Tapered joint"
                       :description "`:tapered` adds a scaled intermediate ring, creating a tapered transition at corners."}}}

     :resolution-settings
     {:title "Resolution"
      :content "The `resolution` command controls curve smoothness for arcs, beziers, and round joints.

```
(resolution :n 32)  ; fixed number of segments
(resolution :a 5)   ; max angle per segment (degrees)
(resolution :s 0.5) ; max segment length (units)
```

You can also override resolution per-command using the `:steps` argument:
```
(arc-h 20 90 :steps 16)
```"
      :examples
      {:resolution-n {:caption "Fixed segments"
                      :description "`:n` sets a fixed number of segments for curves. Lower values = faster but coarser."}
       :resolution-a {:caption "Angle-based"
                      :description "`:a` sets max degrees per segment. Good for consistent smoothness regardless of arc size."}
       :resolution-steps {:caption "Per-command steps"
                          :description "Use `:steps` to override global resolution for a specific command."}}}

     :control-structures
     {:title "Control Structures"
      :content "Clojure provides several ways to control program flow:

**dotimes** — Repeat n times. Use `_` when you don't need the index:
```
(dotimes [_ 4] body)   ; repeat 4 times
(dotimes [i 10] body)  ; i goes 0,1,2...9
```

**for** — Create a sequence by iterating over values:
```
(for [x [1 2 3]] (f x))
```

**when** — Execute only if condition is true:
```
(when (> x 5) (do-something))
```

**if** — Choose between two branches:
```
(if condition then-expr else-expr)
```"
      :examples
      {:dotimes-ignore {:caption "Repeat without index"
                        :description "Use `_` when you don't need the loop counter. This draws a square by repeating 4 times."}
       :for-comprehension {:caption "For comprehension"
                           :description "`for` iterates over a collection and returns a vector. Here we register 3 tubes with different radii. Use `(hide tubes 1)` to hide the second."}
       :when-conditional {:caption "Conditional execution"
                          :description "`when` executes its body only if the condition is true. Here we turn only on odd iterations."}}}

     :local-variables
     {:title "Local Variables"
      :content "The `let` form creates local variables that exist only within its body:

```
(let [name1 value1
      name2 value2]
  body)
```

Variables can depend on earlier ones in the same `let`. This keeps your code clean and avoids repetition."
      :examples
      {:let-basics {:caption "Basic let"
                    :description "Define local variables `r` and `h`, then use them to create a cylinder."}
       :let-computed {:caption "Computed values"
                      :description "Variables can be computed from earlier ones. Note: in Clojure, operators like `+`, `-`, `*`, `/` use prefix notation: `(/ base 2)` instead of `base / 2`."}}}

     :anonymous-functions
     {:title "Anonymous Functions"
      :content "Not every function needs a name. Clojure has two ways to write anonymous (inline) functions:

**Short form** — `#(...)` with `%`, `%1`, `%2` for arguments:
```
#(* % 2)           ; one argument
#(scale %1 %2)     ; two arguments
```

**Long form** — `(fn [args] body)` for more complex cases:
```
(fn [shape t]
  (scale shape (- 1 t)))
```

Use the short form for simple one-liners. Use `fn` when the body is more complex or when naming the arguments improves clarity.

Anonymous functions are especially useful with `loft`, `map`, `reduce`, and `filter`."
      :examples
      {:anon-shorthand {:caption "Short form #()"
                        :description "`#()` creates a quick inline function. `%` (or `%1`, `%2`) refers to the arguments. Here we multiply each radius by a scaling factor."}
       :anon-fn-form {:caption "Long form (fn)"
                      :description "`(fn [args] body)` is clearer when the function has multiple arguments with specific meaning — here `shape` and `t` (progress)."}
       :anon-loft-twist {:caption "Loft with #()"
                         :description "The short form works well for loft transforms. `%1` is the shape, `%2` is the progress from 0 to 1."}}}

     :map-and-reduce
     {:title "Map and Reduce"
      :content "Transform and combine collections with functional tools:

**map** — Apply a function to each element:
```
(map #(* % 2) [1 2 3])   ; => (2 4 6)
```

**reduce** — Combine all elements into one value:
```
(reduce + [1 2 3 4])      ; => 10
```

**map-indexed** — Like `map` but also provides the index:
```
(map-indexed vector [:a :b :c])
; => ([0 :a] [1 :b] [2 :c])
```

**filter** — Keep only elements matching a predicate:
```
(filter odd? [1 2 3 4 5]) ; => (1 3 5)
```

These compose naturally: `(map f (filter pred coll))`. Combined with `for`, they give you powerful ways to generate geometry from data."
      :examples
      {:map-basic {:caption "Map"
                   :description "`map` applies a function to each element. Here we scale a list of values to get radii, then create spheres."}
       :reduce-union {:caption "Reduce"
                      :description "`reduce` combines elements pairwise. Here we merge multiple boxes into a single mesh with `mesh-union`."}
       :map-indexed {:caption "Map-indexed"
                     :description "`map-indexed` pairs each element with its index. Useful for positioning objects along a sequence."}}}

     :repl-usage
     {:title "The REPL"
      :content "The **REPL** (Read-Eval-Print Loop) at the bottom of the editor is your interactive playground. Type expressions and press Enter to evaluate them immediately.

**Why use the REPL?**
- Test small pieces of code without modifying your main definitions
- Inspect values and meshes
- Explore Clojure functions interactively
- Debug by evaluating parts of expressions

**Mechanics:**
- Type an expression and press **Enter** to evaluate
- Use **↑/↓** arrows to navigate command history
- Results appear above the input line
- The REPL shares state with your definitions — you can access registered meshes

**Useful commands:**
```
(info :name)      ; show mesh info (vertices, faces)
(get-mesh :name)  ; retrieve a mesh by name
(hide :name)      ; hide a mesh from viewport
(show :name)      ; show a hidden mesh
```"
      :examples
      {:repl-quick-test {:caption "Quick calculations"
                         :description "Use the REPL as a calculator. Try arithmetic, string operations, or any Clojure expression."}
       :repl-println {:caption "Print output"
                      :description "`println` output appears in the REPL history. Use it for debugging your scripts."}}}

     :mesh-basics
     {:title "What is a Mesh"
      :content "A **mesh** is a 3D object made of vertices (points in space) and faces (polygons connecting vertices). In Ridley, meshes are the final output of your modeling operations.

**The mesh data structure:**
- **vertices** — a list of 3D points `[x y z]`
- **faces** — a list of vertex indices forming triangles

**Registering meshes:**
The `register` command gives a mesh a name and adds it to the scene:
```
(register name mesh-expression)
```

**Working with meshes:**
```
(get-mesh :name)   ; retrieve mesh by name (for boolean ops)
(info :name)       ; show mesh statistics in REPL
(hide :name)       ; hide from viewport
(show :name)       ; show again
```

All primitives (`box`, `cyl`, `sphere`) and extrusions produce meshes."
      :examples
      {:mesh-register {:caption "Register a mesh"
                       :description "`register` creates a named mesh that appears in the viewport. The name becomes a keyword you can use later."}
       :mesh-info {:caption "Inspect with info"
                   :description "`info` returns mesh statistics. Wrap with `println` to see the output in the REPL."}
       :mesh-get-mesh {:caption "Boolean with def"
                       :description "Use `def` to store intermediate meshes, then combine them with boolean operations. Only `register` the final result."}}}

     :boolean-operations
     {:title "Boolean Operations"
      :content "Boolean operations combine meshes in different ways. Use `def` to store intermediate meshes, then `register` only the final result.

```
(mesh-union a b)        ; combine A and B
(mesh-difference a b)   ; subtract B from A
(mesh-intersection a b) ; keep only where A and B overlap
(mesh-hull a b c ...)   ; convex hull of all meshes
```

These operations require **manifold** meshes (watertight, no self-intersections). The result is a new mesh that you can register."
      :examples
      {:bool-union {:caption "Union"
                    :description "Combine two meshes into one. Overlapping regions are merged."}
       :bool-difference {:caption "Difference"
                         :description "Subtract one mesh from another. Creates holes, cuts, and carving effects."}
       :bool-intersection {:caption "Intersection"
                           :description "Keep only the volume where both meshes overlap. Creates lens-like shapes from spheres."}
       :bool-hull {:caption "Convex Hull"
                   :description "Create the smallest convex shape that contains all input meshes. Like wrapping them in shrink-wrap."}
       :bool-union-for {:caption "Union with for"
                        :description "Boolean operations accept vectors, so you can use `for` to generate multiple meshes and combine them in one call."}}}

     :slice-mesh
     {:title "Slice Mesh"
      :content "`slice-mesh` cuts a mesh with a horizontal plane, returning a 2D cross-section as a shape. Use `stamp` to display the result.

```
(stamp (slice-mesh :name))       ; slice at turtle height
(stamp (slice-mesh :name 10.5))  ; slice at explicit Y
```

The slicing plane is horizontal at the turtle's current Y position (or at an explicit height). The result is a flat shape you can inspect, extrude, or use in further operations."
      :examples
      {:slice-cup {:caption "Slice a cup"
                   :description "A revolved cup sliced at a given height. The cross-section shows the wall profile at that level."}
       :slice-donut {:caption "Multiple slices"
                     :description "The same donut sliced at two different planes. The slice plane is perpendicular to the turtle's heading at its current position."}}}

     :attach-meshes
     {:title "Attach Meshes"
      :content "The `attach` function transforms a mesh with turtle movements, returning a new mesh. Use `attach!` to transform a registered mesh in-place by name.

```
(attach mesh (f 20) (th 45))   ; returns transformed copy
(attach! :name (f 20) (th 45)) ; updates registered mesh in-place
```

`attach` is functional — the original is unchanged. `attach!` is a shortcut for `(register name (attach name ...))`."
      :examples
      {:attach-basic {:caption "Basic attach"
                      :description "The base is registered at origin. The cylinder is attached with movements that position it relative to the turtle."}
       :attach-boolean {:caption "Attach for boolean"
                        :description "`attach` can be used inline to position one mesh relative to another for boolean operations."}
       :attach-copy {:caption "Copy a mesh"
                     :description "Use `attach` to create transformed copies of an existing mesh at different positions."}
       :attach-copy-rotated {:caption "Copy with rotation"
                             :description "Copies can include rotations. Here `tv` pitches and `tr` rolls the turtle before placing the copy."}}}

     :attach-faces
     {:title "Attach to Faces"
      :content "The `attach-face` function attaches geometry to a specific face of a mesh. Use face keywords (`:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`) or indices 0-5.

```
(attach-face mesh :top body)
```

The turtle is positioned at the face center, oriented outward. Use `inset` to shrink the face before extruding:

```
(inset dist)  ; move each vertex dist units toward center
```

Positive values shrink the face, negative values expand it."
      :examples
      {:attach-face-frustum {:caption "Frustum (attach-face)"
                             :description "`attach-face` modifies the original face. `inset` shrinks it, `f` moves it outward. Creates a frustum (tapered box)."}
       :clone-face-spike {:caption "Spike (clone-face)"
                          :description "`clone-face` creates new vertices. `inset` creates a smaller inner face, `f` extrudes it. Creates a spike."}}}

     :warp-deformation
     {:title "Spatial Deformation (Warp)"
      :content "The `warp` function deforms mesh vertices inside a volume. The volume shape (sphere, box, cylinder) determines the deformation zone. Position the volume using `attach`.

```
(warp mesh volume deform-fn)
(warp mesh volume fn1 fn2)          ; chain deformations
(warp mesh volume (inflate 3) :subdivide 2)  ; subdivide first
```

**Preset deformations:**
- `(inflate amount)` — push outward along normals
- `(dent amount)` — push inward
- `(attract strength)` — pull toward volume center (0–1)
- `(twist angle)` — rotate around auto-detected axis
- `(squash axis)` / `(squash axis amount)` — flatten
- `(roughen amplitude)` / `(roughen amplitude freq)` — noise

Use `:subdivide n` to add geometry before deforming (each pass: 1→3 triangles). Essential for low-poly meshes."
      :examples
      {:warp-inflate {:caption "Inflate"
                      :description "`inflate` pushes vertices outward along their normals. `:subdivide 2` adds geometry so the box deforms smoothly."}
       :warp-dent {:caption "Dent"
                   :description "`dent` pushes vertices inward along their normals. The volume is offset with `attach` to create an asymmetric dent."}
       :warp-roughen {:caption "Roughen"
                      :description "`roughen` displaces vertices with Perlin noise. The second argument controls frequency."}}}

     :debug-panels
     {:title "3D Text Panels"
      :content "**Panels** are 3D text displays that float in the viewport. They always face the camera (billboard effect) and are perfect for debugging or annotating your models.

**Creating panels:**
```
(register P1 (panel width height))
```

**Writing to panels:**
- `(out P1 \"text\")` — replace content
- `(append P1 \"text\")` — add to content
- `(clear P1)` — clear content

**Positioning panels:**
Use `attach` to position panels relative to the turtle:
```
(register P1 (attach (panel 50 30) (f 20)))
```

Panels are NOT exported to STL — they're for visualization only."
      :examples
      {:panel-basic {:caption "Basic panel"
                     :description "Create a panel and write text to it. The panel appears at the turtle's current position."}
       :panel-attach {:caption "Positioned panel"
                      :description "Use `attach` to position the panel relative to geometry. The panel floats near the cube."}
       :panel-multiline {:caption "Multi-line text"
                         :description "Use `append` with `\\n` to add new lines. Build up complex messages incrementally."}}}

     :debug-techniques
     {:title "Debugging Techniques"
      :content "When your code doesn't work as expected, these techniques help you understand what's happening.

**The T (tap) function:**
`T` is a built-in debug helper that prints a value with a label and returns it unchanged:
```
(T \"label\" value)  ; prints \"label: value\", returns value
```

Insert `T` anywhere to see intermediate values without changing behavior. Its implementation is simply:
```
(defn T [label x] (println label \":\" x) x)
```
You can define your own variant if needed.

**Turtle inspection:**
- `(turtle-position)` — current [x y z] position
- `(turtle-heading)` — current direction vector
- `(turtle-up)` — current up vector

**Step-by-step execution:**
Add `println` statements between operations to see the sequence of events. This helps identify which step causes problems.

**Common issues:**
- Mesh not visible? Check `(hide :name)` / `(show :name)`
- Wrong position? Print turtle state before creating geometry
- Unexpected shape? Simplify and test each part separately"
      :examples
      {:tap-function {:caption "The T function"
                      :description "`T` is built-in. It prints the value with a label, then returns it unchanged. Here we see the computed size (30) in the REPL output."}
       :inspect-turtle {:caption "Inspect turtle state"
                        :description "Print the turtle's position and heading at any point to understand where geometry will be created."}
       :step-by-step {:caption "Step-by-step debugging"
                      :description "Add println between operations to trace execution. The output appears in the REPL history."}}}

     :tweak-interactive
     {:title "Interactive Tweaking"
      :content "The `tweak` macro turns numeric literals in your expression into interactive sliders, letting you explore parameter values in real time.

```
(tweak expr)              ; slider for first literal
(tweak n expr)            ; slider at index n
(tweak -1 expr)           ; last literal (Python-style)
(tweak [0 2] expr)        ; multiple indices
(tweak :all expr)         ; all literals
```

**Registry-aware mode** — pass a keyword to tweak a registered mesh directly. The original is hidden during tweaking:
```
(tweak :A)                ; uses stored source form
(tweak :A expr)           ; explicit expression
(tweak :all :A)           ; all sliders + registry
```

**Controls:** drag the slider to change a value. Use `−`/`+` to zoom the range. Press **OK** to keep the result or **Cancel** (Esc) to discard."
      :examples
      {:tweak-basic {:caption "Tweak first literal"
                     :description "Drag the slider to change the value `30` (the forward distance). The viewport updates in real time."}
       :tweak-indexed {:caption "Tweak by index"
                       :description "Index `2` selects the third numeric literal. Negative indices count from the end."}
       :tweak-all {:caption "All sliders"
                   :description "`:all` creates sliders for every numeric literal in the expression."}
       :tweak-registered {:caption "Tweak registered mesh"
                          :description "Pass a keyword to tweak a registered mesh. The original is hidden and the slider preview replaces it. OK re-registers; Cancel restores the original."}}}

     :stamp-preview
     {:title "Shape Preview (Stamp)"
      :content "The `stamp` command visualizes a 2D shape at the current turtle position and orientation as a semi-transparent surface. It shows exactly where the initial face of an `extrude` or `revolve` would appear.

```
(stamp shape)
```

Stamps are purely visual — they don't affect the turtle's position or heading, and they're not exported to STL. They're perfect for debugging: verify your shape and position before committing to an extrusion.

**Visibility control:**
- Toggle the **Stamps** button in the viewport toolbar
- Or use DSL commands: `(show-stamps)` / `(hide-stamps)`
- Query visibility: `(stamps-visible?)`

Stamps support shapes with holes — the holes are rendered correctly as transparent areas within the surface."
      :examples
      {:stamp-basic {:caption "Basic stamp"
                     :description "Place a circle outline at the origin. The stamp appears as a semi-transparent orange surface."}
       :stamp-positioned {:caption "Stamp at position"
                          :description "Move and tilt the turtle, then stamp. Each stamp appears at the turtle's current pose — exactly where extrusion would start."}
       :stamp-with-holes {:caption "Stamp with holes"
                          :description "Shapes with holes (like this washer from `shape-difference`) render correctly — the inner area is cut out."}}}

     :follow-path-lines
     {:title "Follow Path"
      :content "`follow-path` makes the turtle trace a stored path, drawing visible lines in the 3D viewport. Unlike `extrude`, it doesn't create geometry — it just moves the turtle along the path and leaves a visual trail.

```
(follow-path my-path)
```

This is useful for visualizing paths before extruding, debugging complex path constructions, or simply drawing 3D wireframes.

**Visibility control:**
- Toggle the **Lines** button in the viewport toolbar
- Or use DSL commands: `(show-lines)` / `(hide-lines)`
- Query visibility: `(lines-visible?)`"
      :examples
      {:follow-basic {:caption "Draw a curve"
                      :description "`follow-path` traces the path in 3D, showing the curve the turtle would follow during an extrusion."}
       :follow-visibility {:caption "Visibility toggle"
                           :description "Use `hide-lines` / `show-lines` to toggle the visibility of all path lines. Useful when lines overlap with mesh geometry."}}}

     :measurement
     {:title "Measurement"
      :content "Measure distances, areas, and bounding boxes of your models directly from code or the REPL.

**Distance** between meshes, faces, or arbitrary points:
```
(distance :A :B)                  ; centroid to centroid
(distance :A :top :B :bottom)     ; face center to face center
(distance [0 0 0] [100 0 0])     ; point to point
```

**Visual rulers** — same syntax as `distance`, but draws a line in the viewport:
```
(ruler :A :top :B :bottom)
(clear-rulers)
```

**Bounding box** and **face area**:
```
(bounds :A)        ; → {:min [...] :max [...] :size [...] :center [...]}
(area :A :top)     ; → area of the :top face
```

**Interactive measurement:** hold **Shift** and click two points on meshes in the viewport to create a ruler. Press **Esc** to cancel or clear.

**Lifecycle:** rulers persist across REPL commands but are cleared automatically when you re-run the editor (Cmd+Enter). In **tweak mode**, rulers update live as you drag sliders."
      :examples
      {:distance-basic {:caption "Distance between meshes"
                        :description "`distance` computes the Euclidean distance between two points. With keyword arguments, it resolves mesh centroids automatically."}
       :ruler-faces {:caption "Ruler between faces"
                     :description "`ruler` works like `distance` but also draws a yellow line in the viewport. Here it connects the top face of A to the bottom face of B."}
       :bounds-info {:caption "Bounding box"
                     :description "`bounds` returns min, max, size, and center of a mesh. Works on registered meshes (by keyword), mesh data, 2D shapes, and paths."}
       :area-face {:caption "Face area"
                   :description "`area` returns the surface area of a named face. Standard face names are `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`."}}}

     :mark-goto-pushpop
     {:title "Marks, Paths, and State Stack"
      :content "When creating complex geometry, you often need to return to previous positions or branch out from a point. Ridley provides two mechanisms:

**Marks and with-path:**
- `(mark :name)` — Inside a `path`, save the current pose with a name
- `(with-path p ...)` — Resolve marks from path `p` as anchors, then execute body
- `(goto :name)` — Teleport to a named anchor (draws line if pen is on)

Marks are embedded in paths and resolved with `with-path`. This makes paths reusable — the same path can be pinned at different turtle positions.

**Path composition with follow:**
- `(follow other-path)` — Inside a `path`, splice another path's commands

**Turtle scopes for branching:**
- `(turtle ...)` — Create an isolated turtle scope: the child inherits the parent's pose and settings, but changes inside don't affect the outer turtle

Turtle scopes are perfect for branching: open a scope before a branch, create geometry, and the turtle automatically returns to the parent's position when the scope ends. No manual save/restore needed.

**Key difference:**
- `mark`/`with-path`/`goto` = named layout points for navigation
- `turtle` = lexical scope for branching patterns"
      :examples
      {:mark-goto-basic {:caption "Marks and with-path"
                         :description "Define a path with named marks, then use `with-path` to resolve them as anchors. `goto` navigates to the marked positions. Marks are relative to the turtle's pose when `with-path` is called."}
       :turtle-branch {:caption "Branching with turtle scopes"
                       :description "Each `turtle` scope inherits the parent's position and settings. Geometry created inside (branches) is visible, but the outer turtle is unchanged — both branches originate from the same point."}
       :path-follow {:caption "Path composition with follow"
                     :description "`follow` splices another path's commands into the current recording. Here we compose a square from a single segment repeated four times."}}}

     :arcs-beziers
     {:title "Arcs and Bezier Curves"
      :content "Smooth curves are essential for organic shapes. Ridley provides arc and bezier commands that integrate with extrusion.

**Horizontal Arc:**
```
(arc-h radius angle)      ; turn left/right while moving
(arc-h radius angle :steps 16)
```
Positive angle = left turn, negative = right turn. The turtle ends up at `radius × angle_rad` distance away, heading rotated by `angle` degrees.

**Vertical Arc:**
```
(arc-v radius angle)      ; pitch up/down while moving
(arc-v radius angle :steps 16)
```
Positive angle = pitch up, negative = pitch down.

**Bezier Curves:**
```
(bezier-to [x y z])                    ; auto control points
(bezier-to [x y z] [c1])               ; quadratic (1 control)
(bezier-to [x y z] [c1] [c2])          ; cubic (2 controls)
(bezier-to-anchor :name)               ; bezier to named anchor
(bezier-as (path ...))                 ; one bezier per segment
(bezier-as (path ...) :tension 0.5)    ; wider curve
(bezier-as (path ...) :cubic true)     ; Catmull-Rom spline
(bezier-as (path ...) :max-segment-length 20) ; subdivide long segments
```

`bezier-to` with no control points creates a curve tangent to the current heading.

`bezier-to-anchor` is smarter: it respects **both** the current heading AND the anchor's saved heading, creating a smooth S-curve that honors both directions.

`bezier-as` takes a turtle path and replaces it with a chain of smooth bezier curves, one per segment. Use `:tension` to control how wide the curves are (default 0.33). With `:cubic true`, interior waypoints use Catmull-Rom directions for globally smoother curves. Use `:max-segment-length` to subdivide long segments."
      :examples
      {:arc-h-basic {:caption "Horizontal arc (U-turn)"
                     :description "`arc-h 30 180` creates a 180° arc with radius 30 — a U-turn in the horizontal plane. Combined with straight segments."}
       :arc-v-basic {:caption "Vertical arc (loop)"
                     :description "`arc-v 25 180` creates a half-loop in the vertical plane, like going over a hill."}
       :bezier-auto {:caption "Auto bezier to anchor"
                     :description "Define marks in a path, resolve them with `with-path`, then extrude with `bezier-to-anchor`. The curve respects both the starting direction and the anchor's saved direction."}
       :bezier-control {:caption "Bezier with control points"
                        :description "Explicit control points give precise control. Here `[0 20 30]` and `[0 40 -30]` create an S-curve."}
       :bezier-as {:caption "Smooth a path with bezier-as"
                   :description "`bezier-as` takes a turtle path and draws a chain of smooth bezier curves, one per segment. Use `:tension` to control how wide the curves are (default 0.33)."}
       :bezier-as-cubic {:caption "Catmull-Rom spline with :cubic"
                         :description "With `:cubic true`, interior waypoints use Catmull-Rom directions (based on neighboring points) instead of turtle headings, producing globally smoother curves through multiple turns."}}}

     :bloft-curves
     {:title "Bezier-Safe Loft (bloft)"
      :content "When lofting along tight bezier curves, standard `loft` can produce self-intersecting geometry. `bloft` (bezier-safe loft) handles this by detecting intersections and bridging them with convex hulls.

**Basic usage** — same signature as loft:
```
(bloft shape transform-fn (path (bezier-as ...)))
```

**When to use bloft vs loft:**
- Use `loft` for straight paths or gentle curves — faster
- Use `bloft` for tight bezier curves that might self-intersect — slower but correct

**Step count:**
```
(bloft-n 64 shape transform-fn path)  ; 64 steps (smoother)
```

**Performance and resolution:**

`bloft` can take several seconds for complex curves. The bezier density is controlled by `(resolution :n ...)`:
- Low values (`:n 10`) → fast preview, may show visual artifacts
- High values (`:n 60`) → smooth final render, slower

A loading spinner appears during long operations.

**Tip:** Work with low resolution during design, then increase for final render."
      :examples
      {:bloft-basic {:caption "Basic bloft"
                     :description "`bloft` lofts a circle along a curved path. Unlike `loft`, it handles tight turns without self-intersection."}
       :bloft-taper {:caption "Tapered bloft"
                     :description "Transform functions work the same as with `loft` — here scaling to create a tapered tube along a tight curve."}
       :bloft-steps {:caption "Smooth snake with :steps"
                     :description "`bezier-as` accepts `:steps` to control the number of interpolation points per bezier segment — higher values produce smoother curves without changing the global `resolution`. Combined with `tapered` and `bloft-n` for a polished result."}}}

     :colors-materials
     {:title "Colors and Materials"
      :content "Every mesh can have its own color and material properties. Set them **before** creating geometry — they apply to all meshes created afterward.

**Color only:**
```
(color 0xff0000)        ; red (hex RGB)
(color 255 0 0)         ; red (RGB values 0-255)
```

**Full material control:**
```
(material :color 0x8888ff
          :metalness 0.8    ; 0-1, how metallic
          :roughness 0.2    ; 0-1, how smooth/rough
          :opacity 1.0      ; 0-1, transparency
          :flat-shading true)
```

When you call `material`, any unspecified options reset to defaults. To reset everything:
```
(reset-material)
```

PBR (Physically Based Rendering) guidelines:
- **Metals** (gold, chrome): metalness 0.8-1.0, roughness 0.1-0.4
- **Plastics**: metalness 0.0-0.1, roughness 0.3-0.7
- **Matte surfaces**: metalness 0.0, roughness 0.8-1.0"
      :examples
      {:color-basic {:caption "Setting colors"
                     :description "Use `color` to quickly set the color for subsequent geometry. Each mesh remembers the color that was active when it was created."}
       :material-pbr {:caption "PBR materials"
                      :description "Full control over material appearance. High metalness + low roughness = shiny metal. Low metalness + high roughness = matte plastic."}
       :material-reset {:caption "Reset to defaults"
                        :description "`reset-material` returns all material properties to the default blue appearance."}}}

     :text-shapes
     {:title "Text Shapes"
      :content "Convert text to 2D shapes using the built-in Roboto font.

**Basic usage:**
```
(text-shape \"Hello\")           ; default size
(text-shape \"Hello\" :size 30)  ; larger text
```

**Extrude for 3D text:**
```
(register sign (extrude (text-shape \"RIDLEY\" :size 20) (f 5)))
```

**Quick extrusion with `extrude-text`:**
```
(extrude-text \"Hello\" :size 25 :depth 4)
```
This is a shortcut that extrudes text at the turtle position, flowing along heading.

**Text on a path:**
```
(def my-arc (path (arc-h 50 180)))
(text-on-path \"CURVED\" my-arc :size 12 :depth 3)
```

Options for `text-on-path`:
- `:align` — `:start`, `:center`, or `:end`
- `:spacing` — extra letter spacing (default 0)
- `:overflow` — `:truncate`, `:wrap`, or `:scale`

**3D spiral text with preserve-up:**
```
(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))
(turtle :preserve-up
  (text-on-path \"spiral text\" spiral :size 6 :depth 1.2))
```
Use `:preserve-up` to keep letters upright on 3D paths. Set the pitch with `tv` before the horizontal loop.

**Built-in fonts:**
- `:roboto` — Roboto Regular (default)
- `:roboto-mono` — Roboto Mono (monospace)

**Load custom font:**
```
(load-font! \"/path/to/font.ttf\")  ; returns a promise
```"
      :examples
      {:text-basic {:caption "Simple 3D text"
                    :description "Extrude text shapes to create 3D letters. The :size option controls the font size in world units."}
       :text-large {:caption "Signage"
                    :description "Larger text for signs and titles. Adjust :size and extrusion depth (f 3) for desired proportions."}
       :extrude-text {:caption "Quick extrusion"
                      :description "`extrude-text` is a convenient shortcut: text flows along the turtle's heading and extrudes perpendicular to it."}
       :text-on-arc {:caption "Text on arc"
                     :description "Place text along a curved path. Each letter follows the curve tangent. Use `:align :center` to center text on the path."}
       :text-on-spiral {:caption "Text on spiral"
                        :description "Text on a 3D spiral using `:preserve-up` to keep letters upright. Set pitch once with `tv`, then loop horizontal turns."}}}

     :gallery-twisted-vase
     {:title "Twisted Fluted Vase"
      :content "A parametric vase showcasing **shape-fn composition** with the `->` threading macro.

The profile is a circle that gets **fluted** (scalloped edges), **twisted** along the height, and shaped with a **custom taper function**. The taper uses math to create a belly that widens, a neck that narrows, and a lip that flares.

**Features demonstrated:**
- `fluted` — adds radial undulations to the cross-section
- `twisted` — progressively rotates the profile along the path
- `shape-fn` with a custom `(fn [shape t] ...)` for non-linear scaling
- `loft-n` for high-resolution extrusion
- `->` threading to compose multiple shape-fns

**Try changing:**
- `n-flutes` for more or fewer scallops
- `twist-amount` for tighter or looser spiral
- The taper curve math for different silhouettes"
      :examples
      {:twisted-vase-code {:caption "Twisted Fluted Vase"}}}

     :gallery-recursive-tree
     {:title "Recursive Tree"
      :content "A 3D **fractal tree** built with `turtle` scope branching.

At each level of recursion, the trunk splits into several branches. Each branch is thinner and shorter than its parent, angled outward using the **golden angle** for even spatial distribution. Branches are tapered cylinders placed at fork points.

**Features demonstrated:**
- `turtle` scopes for branching (isolated child inherits parent pose)
- Recursive functions with `defn`
- Parametric design with `def` variables
- Tapered `cyl` primitives for natural-looking limbs

**Try changing:**
- `max-depth` for more or fewer levels (3-6 is a good range)
- `n-branches` for bushier or sparser trees
- `spread-angle` for wider or tighter branching
- `taper` for how quickly branches thin out"
      :examples
      {:recursive-tree-code {:caption "Recursive Tree"}}}

     :gallery-dice
     {:title "Six-Sided Die"
      :content "A classic **D6 die** with subtracted pips on each face.

The die is built by starting from a `box`, then placing spherical pips on all six faces using `turtle` scopes for orientation. The pips are subtracted with `mesh-difference` (boolean CSG). Pip layouts follow the standard Western convention where opposite faces sum to 7.

**Features demonstrated:**
- `mesh-difference` for boolean subtraction (CSG)
- `turtle` scopes to orient geometry on each face (`th`, `tv`)
- Parametric pip placement with functions and loops
- Building complex objects from simple primitives (`box`, `sphere`)

**Try changing:**
- `die-size` for a bigger or smaller die
- `pip-radius` / `pip-depth` for different pip styles
- Add a `shape-offset` to round the die edges"
      :examples
      {:dice-code {:caption "Six-Sided Die"}}}

     :gallery-embossed-column
     {:title "Embossed Column"
      :content "A classical **column with text embossed** around its surface.

The text is converted to a 2D shape with `text-shape`, extruded into a thin 3D mesh, then **rasterized as a heightmap** with `mesh-to-heightmap`. That heightmap is applied as radial displacement on a cylindrical `loft-n`, making the letters stand out from the surface. The column shaft is also `fluted`, and has a simple capital and base assembled with `attach`.

**Features demonstrated:**
- `text-shape` for generating 2D letter outlines
- `mesh-to-heightmap` to rasterize 3D geometry into a displacement map
- `heightmap` shape-fn for surface displacement
- Combining multiple shape-fns (`fluted` + `heightmap`)
- `concat-meshes` for assembling base + shaft + capital

**Try changing:**
- The text string for different inscriptions
- `:tile-x` to control how many times the text wraps around
- `:amplitude` for deeper or shallower embossing
- `n-flutes` / `flute-depth` for the column shaft"
      :examples
      {:embossed-column-code {:caption "Embossed Column"}}}

     :gallery-canvas-weave
     {:title "Canvas Weave Cup"
      :content "A cup with **woven canvas texture** on the outside. This is the most complex example in the gallery.

Two sets of strands are lofted along sinusoidal paths with `bezier-as`, then rotated 90 degrees and interlocked with `concat-meshes`. The resulting mesh is sampled as a **2D heightmap tile** with `mesh-to-heightmap`, which is then applied as radial displacement to a cylindrical loft. The tile repeats seamlessly around the cup. Finally, `mesh-difference` hollows out the interior.

**WARNING:** This example is computationally expensive and takes **30-60 seconds** to render. The bottleneck is the heightmap rasterization at 256x256 and the 128-step loft with per-step heightmap sampling.

**Features demonstrated:**
- Procedural weave generation with interlocking strands
- `mesh-to-heightmap` to rasterize 3D weave geometry
- `heightmap` shape-fn with `:tile-x` / `:tile-y` for seamless repetition
- `mesh-difference` to hollow out the interior
- `concat-meshes`, `bounds`, `bezier-as`, `attach`

**Try changing:**
- `thickness` and `compactness` parameters
- `:tile-x` / `:tile-y` to control pattern repetition
- `:amplitude` for deeper or shallower texture
- Replace `circle` with `rect` for flat ribbon strands"
      :examples
      {:canvas-weave-code {:caption "Canvas Weave Cup"}}}}}

   :it
   {:sections
    {:part-1 {:title "Per Iniziare"}
     :part-2 {:title "Forme & Estrusione"}
     :part-3 {:title "Manipolazione Mesh"}
     :part-4 {:title "Funzionalità Avanzate"}
     :part-5 {:title "Aiuto al Debug"}
     :part-6 {:title "Clojure Avanzato"}
     :gallery {:title "Galleria"}}
    :pages
    {:hello-ridley
     {:title "Ciao Ridley"
      :content "Benvenuto in Ridley, un ambiente di modellazione 3D basato sulla grafica turtle.

In Ridley, crei oggetti 3D scrivendo codice. Il modo più semplice è con primitive come `box` e `cyl`. Ma il vero potere viene dall'**estrusione** — trascinare una forma 2D lungo un percorso per creare geometria 3D.

Vediamo entrambi gli approcci:"
      :examples
      {:first-cube {:caption "Un semplice cubo"
                    :description "Il comando `register` dà un nome alla tua forma così appare nel viewport. `box` crea un cubo della dimensione specificata."}
       :first-tube {:caption "La tua prima estrusione"
                    :description "`extrude` trascina una forma 2D (qui, un cerchio) lungo un percorso (qui, avanti di 30 unità). Questo crea un tubo."}}}

     :ui-overview
     {:title "L'Interfaccia"
      :content "Ridley ha tre aree principali:

**Editor** (sinistra) — Scrivi il tuo codice qui. È dove definisci forme, funzioni e geometria.

**Viewport** (destra) — La vista 3D che mostra i tuoi modelli. Puoi ruotare (trascinare), zoomare (scroll) e spostare (tasto destro) per esplorare. Tieni premuto **X**, **Y** o **Z** mentre trascini per ruotare solo attorno a quell'asse.

**REPL** (in basso) — Una linea di comando interattiva. Scrivi espressioni e premi Invio per valutarle istantaneamente.

**Barra Editor:**
- **▶ Run** — Esegui il codice (come Cmd+Invio)
- **Save** — Scarica il codice come file .clj
- **Load** — Carica un file .clj dal disco
- **Manual** — Apri questo manuale

**Barra Viewport:**
- **Grid** — Mostra/nascondi la griglia di riferimento
- **Axes** — Mostra/nascondi le linee degli assi X/Y/Z
- **Turtle** — Mostra/nascondi l'indicatore turtle (posizione/direzione)
- **Lines** — Mostra/nascondi le linee di costruzione (tracce della penna)
- **Normals** — Mostra/nascondi le normali delle facce
- **Reset** — Reimposta la camera alla vista predefinita
- **Export STL** — Scarica le mesh visibili come file STL"}

     :editor-commands
     {:title "Comandi Editor"
      :content "**Editor:**
- **Cmd+Invio** (Mac) o **Ctrl+Invio** (Windows/Linux) — Esegui tutto il codice
- Clicca il bottone **▶ Run** per lo stesso effetto

**REPL:**
- **Invio** — Valuta la linea corrente
- **↑/↓** — Naviga la cronologia dei comandi

**Paredit** (editing strutturale):
Paredit mantiene le parentesi bilanciate automaticamente. Comandi principali:

- **Tab** — Indenta la linea corrente
- **(** o **[** o **{** — Inserisce automaticamente la parentesi di chiusura
- **Backspace** su parentesi vuote — Elimina la coppia
- **Ctrl+K** — Taglia fino a fine linea
- **Ctrl+Shift+K** — Taglia l'espressione che racchiude
- **Shift+Cmd+K** — Slurp: ingloba l'elemento successivo nelle parentesi
- **Shift+Cmd+J** — Barf: espelle l'ultimo elemento dalle parentesi

Esempio — Slurp: con cursore in `(+ 1 2|) 3`, Slurp → `(+ 1 2 3)`
Barf: con cursore in `(+ 1 2 3|)`, Barf → `(+ 1 2) 3`

**Ricerca:**
- **Cmd+F** / **Ctrl+F** — Cerca nell'editor
- **Cmd+G** / **Ctrl+G** — Trova successivo

**Annulla/Ripeti:**
- **Cmd+Z** / **Ctrl+Z** — Annulla
- **Cmd+Shift+Z** / **Ctrl+Shift+Z** — Ripeti"}

     :the-turtle
     {:title "La Tartaruga"
      :content "Ridley usa una **tartaruga** — un cursore invisibile che si muove nello spazio 3D. Mentre si muove, disegna linee che puoi vedere nel viewport.

La tartaruga ha:
- Una **posizione** nello spazio 3D
- Una **direzione** (dove sta guardando)
- Un vettore **up** (quale direzione è \"su\" per la tartaruga)

Comandi di movimento base:
- `(f distanza)` - muovi avanti
- `(th angolo)` - gira orizzontalmente (imbardata)
- `(tv angolo)` - gira verticalmente (beccheggio)
- `(tr angolo)` - rollio"
      :examples
      {:movement {:caption "Movimento base"
                  :description "Muovi avanti di 50 unità, gira di 90°, poi muovi ancora avanti. La tartaruga disegna linee visibili mentre si muove."}
       :spiral-path {:caption "Disegnare una spirale"
                     :description "`dotimes` ripete i comandi. La variabile `i` conta da 0 a 49, quindi ogni passo in avanti diventa più lungo, creando una spirale."}}}

     :clojure-basics
     {:title "Basi di Clojure"
      :content "Il codice Ridley usa la sintassi Clojure. Ecco cosa devi sapere:

**Parentesi** — Ogni comando è racchiuso tra parentesi: `(comando arg1 arg2)`

**def** — Crea un valore con nome: `(def nome valore)`

**defn** — Crea una funzione: `(defn nome [args] corpo)`

Questi ti permettono di costruire pezzi riutilizzabili e mantenere il codice organizzato.

**Per Approfondire**

Per imparare di più su Clojure, consulta queste risorse:

- [Clojure for the Brave and True](https://www.braveclojure.com/) — Un libro online gratuito, divertente e completo (in inglese)

- [ClojureDocs](https://clojuredocs.org/) — Riferimento rapido con esempi per tutte le funzioni core"
      :examples
      {:define-value {:caption "Definire un valore"
                      :description "Usa `def` per dare un nome a un valore. Qui definiamo `size` e lo usiamo per creare un cubo."}
       :define-function {:caption "Definire una funzione"
                         :description "Usa `defn` per creare funzioni riutilizzabili. Questa funzione `tube` prende raggio e lunghezza come parametri."}}}

     :extrude-basics
     {:title "Estrusione"
      :content "L'**estrusione** è il concetto centrale in Ridley. Prendi una forma 2D e la trascini lungo un percorso per creare geometria 3D.

```
(extrude forma movimenti...)
```

La **forma** definisce la sezione trasversale. I **movimenti** definiscono il percorso che la tartaruga segue. Mentre la tartaruga si muove, la forma viene trascinata lungo, creando un solido."
      :examples
      {:circle-extrude {:caption "Cerchio → Tubo"
                        :description "Un cerchio estruso in avanti diventa un tubo (cilindro)."}
       :rect-extrude {:caption "Rettangolo → Barra"
                      :description "Un rettangolo estruso in avanti diventa una barra rettangolare."}
       :bent-extrude {:caption "Percorso curvo"
                      :description "Il percorso può includere curve. Qui creiamo un tubo curvo girando di 45° a metà estrusione."}}}

     :builtin-shapes
     {:title "Forme Predefinite"
      :content "Ridley fornisce diverse forme 2D che puoi estrudere:

- `(circle raggio)` — un cerchio
- `(rect larghezza altezza)` — un rettangolo
- `(polygon n raggio)` — poligono regolare con n lati
- `(poly x1 y1 x2 y2 ...)` — poligono arbitrario da coppie di coordinate
- `(star punte esterno interno)` — una stella

Ogni forma diventa la sezione trasversale del tuo solido estruso."
      :examples
      {:circle-extrude {:caption "Cerchio"
                        :description "`circle` crea un cerchio con il raggio dato. Estruso, diventa un tubo."}
       :rect-extrude {:caption "Rettangolo"
                      :description "`rect` crea un rettangolo con larghezza e altezza. Estruso, diventa una barra."}
       :polygon-extrude {:caption "Poligono"
                         :description "`polygon` crea poligoni regolari con n lati. Un esagono (6 lati) estruso diventa una barra esagonale."}
       :star-extrude {:caption "Stella"
                      :description "`star` crea forme a stella. I parametri sono: punte, raggio esterno, raggio interno."}
       :poly-extrude {:caption "Poly"
                      :description "`poly` crea poligoni arbitrari da coppie di coordinate x y. L'origine [0,0] corrisponde alla posizione della tartaruga. Accetta anche un vettore: `(poly [x1 y1 x2 y2 ...])`."}}}


     :custom-shapes
     {:title "Forme Personalizzate"
      :content "La macro `shape` ti permette di disegnare le tue forme 2D usando movimenti turtle. La tartaruga traccia un percorso chiuso, e quel percorso diventa la tua forma.

```
(shape movimenti...)
```

La forma si chiude automaticamente — l'ultimo punto si ricollega al primo."
      :examples
      {:triangle-shape {:caption "Triangolo"
                        :description "Rotazioni e avanzamenti della tartaruga tracciano una sezione triangolare. Estrusa, diventa una barra."}
       :l-shape {:caption "Forma a L"
                 :description "Percorsi più complessi creano sezioni più complesse. Questa forma a L diventa una trave a L quando estrusa."}}}

     :shape-booleans-2d
     {:title "Booleane 2D sulle Forme"
      :content "Combina forme 2D usando operazioni booleane prima di estrudere. Questo ti permette di creare sezioni trasversali complesse da forme semplici.

```
(shape-union a b)        ; unisci due forme
(shape-difference a b)   ; taglia B da A
(shape-intersection a b) ; tieni solo la sovrapposizione
(shape-xor a b)          ; tieni le parti non sovrapposte
(shape-offset forma d)   ; espandi (d>0) o restringi (d<0)
```

Queste operano su forme 2D (cerchi, rettangoli, poligoni, forme personalizzate) e restituiscono nuove forme che puoi estrudere, loftare o rivolvere.

Usa `translate-shape` per posizionare le forme prima di combinarle:
```
(translate-shape (circle 10) 15 0)  ; sposta 15 unità in X
```"
      :examples
      {:shape-diff-washer {:caption "Rondella (differenza)"
                           :description "`shape-difference` sottrae il cerchio interno da quello esterno, creando una sezione ad anello. Estrusa, diventa un tubo con centro cavo."}
       :shape-union-blob {:caption "Blob (unione)"
                          :description "`shape-union` unisce due cerchi sovrapposti in un'unica forma. `translate-shape` sposta il secondo cerchio prima di combinarli."}
       :shape-intersection-lens {:caption "Lente (intersezione)"
                                 :description "`shape-intersection` mantiene solo l'area in cui entrambi i cerchi si sovrappongono, creando una sezione a forma di lente."}
       :shape-offset-frame {:caption "Cornice (offset)"
                            :description "`shape-offset` espande una forma verso l'esterno. Sottraendo l'originale dalla versione espansa si crea una cornice di larghezza uniforme."}
       :shape-xor-cross {:caption "Croce (xor)"
                         :description "`shape-xor` mantiene le parti che non si sovrappongono — la differenza simmetrica. Due rettangoli incrociati producono una croce con il centro ritagliato."}}}

     :reusable-shapes
     {:title "Forme Riutilizzabili"
      :content "Le forme sono solo dati — puoi memorizzarle in variabili e riutilizzarle.

```
(def mia-forma (circle 10))
(extrude mia-forma (f 30))
```

Allo stesso modo, i percorsi possono essere catturati con la macro `path` e riutilizzati in più estrusioni."
      :examples
      {:def-shape {:caption "Riutilizzare una forma"
                   :description "Definisci una forma una volta, usala più volte. Entrambi i tubi usano lo stesso cerchio ma lunghezze diverse."}
       :def-path {:caption "Riutilizzare un percorso"
                  :description "Anche i percorsi possono essere memorizzati e riutilizzati. La macro `path` cattura i movimenti turtle per uso futuro."}
       :follow-path {:caption "Disegnare un percorso"
                     :description "Usa `follow-path` per far tracciare alla tartaruga un percorso memorizzato, disegnando linee visibili nel viewport."}}}

     :quick-path
     {:title "Quick Path"
      :content "`quick-path` crea un percorso da una lista compatta di numeri. I valori si alternano tra **distanza avanti** e **angolo di svolta**:

```
(quick-path f1 th1 f2 th2 ... fN)
```

Equivale a:

```
(path (f f1) (th th1) (f f2) (th th2) ... (f fN))
```

Puoi passare i numeri come argomenti separati o come un singolo vettore — utile quando la sequenza è calcolata o memorizzata in una variabile."
      :examples
      {:qp-basic {:caption "Percorso a zigzag"
                   :description "I numeri si alternano: avanti 30, gira 90°, avanti 30, gira -90°, avanti 30. Il risultato è un tubo a zigzag."}
       :qp-vector {:caption "Percorso da vettore"
                   :description "Quando i numeri sono in un vettore, `quick-path` lo accetta direttamente. Comodo per percorsi calcolati o parametrizzati."}}}

     :extrude-closed
     {:title "Estrusione Chiusa"
      :content "`extrude-closed` crea forme dove il percorso forma un anello chiuso — come un toro o una cornice. L'ultimo anello si collega al primo, senza tappi alle estremità.

```
(extrude-closed forma movimenti...)
```

Il percorso deve ritornare (approssimativamente) alla posizione e orientamento iniziali."
      :examples
      {:square-torus {:caption "Toro quadrato"
                      :description "Un percorso quadrato con sezione circolare crea un toro con lati piatti."}
       :hex-ring {:caption "Anello esagonale"
                  :description "Un percorso esagonale con sezione esagonale. La forma viene trascinata attorno all'intero anello."}}}

     :shape-transforms
     {:title "Transizioni di Forma"
      :content "Il comando `loft` ti permette di fare transizioni tra forme lungo il percorso di estrusione.

**Loft a due forme** — transizione graduale da una forma all'altra:

```
(loft forma-inizio forma-fine movimenti...)
```

**Loft con funzione di trasformazione** — applica una funzione che controlla la forma ad ogni passo:

```
(loft forma funzione-trasformazione movimenti...)
```

La funzione di trasformazione riceve due argomenti: la forma originale e un valore di progresso `t` da 0.0 (inizio) a 1.0 (fine). Usa `loft-n` per un numero personalizzato di passi (default 16):

```
(loft-n passi forma funzione-trasformazione movimenti...)
```"
      :examples
      {:scale-shape {:caption "Cono rastremato"
                     :description "Loft da un cerchio grande a uno piccolo crea un cono rastremato."}
       :morph-shapes {:caption "Morphing di forme"
                      :description "Loft può fare transizioni tra forme completamente diverse — qui, da un quadrato a un cerchio."}
       :loft-transform {:caption "Funzione di trasformazione"
                        :description "Una funzione di trasformazione scala la forma in base al progresso, creando un cono da una singola forma."}
       :loft-twist {:caption "Torsione"
                    :description "Ruotare la forma durante l'estrusione crea un effetto di torsione."}}}

     :joint-modes
     {:title "Modalità Giunzione"
      :content "Quando un percorso di estrusione ha angoli (curve), il comando `joint-mode` controlla come viene generata la giunzione all'angolo.

```
(joint-mode :flat)    ; connessione diretta (default)
(joint-mode :round)   ; angolo arrotondato
(joint-mode :tapered) ; anello intermedio rastremato
```

La modalità giunzione influenza come la forma transita attraverso gli angoli, impattando sia l'estetica che la geometria."
      :examples
      {:joint-flat {:caption "Giunzione piatta"
                    :description "`:flat` connette gli anelli direttamente agli angoli. Semplice ma può mostrare spigoli."}
       :joint-round {:caption "Giunzione arrotondata"
                     :description "`:round` aggiunge anelli intermedi che curvano dolcemente attraverso l'angolo."}
       :joint-tapered {:caption "Giunzione rastremata"
                       :description "`:tapered` aggiunge un anello intermedio scalato, creando una transizione rastremata agli angoli."}}}

     :resolution-settings
     {:title "Risoluzione"
      :content "Il comando `resolution` controlla la levigatezza delle curve per archi, bezier e giunzioni arrotondate.

```
(resolution :n 32)  ; numero fisso di segmenti
(resolution :a 5)   ; angolo massimo per segmento (gradi)
(resolution :s 0.5) ; lunghezza massima del segmento (unità)
```

Puoi anche sovrascrivere la risoluzione per singolo comando usando l'argomento `:steps`:
```
(arc-h 20 90 :steps 16)
```"
      :examples
      {:resolution-n {:caption "Segmenti fissi"
                      :description "`:n` imposta un numero fisso di segmenti per le curve. Valori bassi = più veloce ma più grezzo."}
       :resolution-a {:caption "Basato sull'angolo"
                      :description "`:a` imposta i gradi massimi per segmento. Buono per levigatezza consistente indipendentemente dalla dimensione dell'arco."}
       :resolution-steps {:caption "Steps per comando"
                          :description "Usa `:steps` per sovrascrivere la risoluzione globale per un comando specifico."}}}

     :control-structures
     {:title "Strutture di Controllo"
      :content "Clojure fornisce diversi modi per controllare il flusso del programma:

**dotimes** — Ripeti n volte. Usa `_` quando non ti serve l'indice:
```
(dotimes [_ 4] corpo)   ; ripeti 4 volte
(dotimes [i 10] corpo)  ; i va 0,1,2...9
```

**for** — Crea una sequenza iterando su valori:
```
(for [x [1 2 3]] (f x))
```

**when** — Esegui solo se la condizione è vera:
```
(when (> x 5) (fai-qualcosa))
```

**if** — Scegli tra due rami:
```
(if condizione expr-then expr-else)
```"
      :examples
      {:dotimes-ignore {:caption "Ripetere senza indice"
                        :description "Usa `_` quando non ti serve il contatore. Questo disegna un quadrato ripetendo 4 volte."}
       :for-comprehension {:caption "Comprensione for"
                           :description "`for` itera su una collezione e ritorna un vettore. Qui registriamo 3 tubi con raggi diversi. Usa `(hide tubes 1)` per nascondere il secondo."}
       :when-conditional {:caption "Esecuzione condizionale"
                          :description "`when` esegue il corpo solo se la condizione è vera. Qui giriamo solo nelle iterazioni dispari."}}}

     :local-variables
     {:title "Variabili Locali"
      :content "La forma `let` crea variabili locali che esistono solo nel suo corpo:

```
(let [nome1 valore1
      nome2 valore2]
  corpo)
```

Le variabili possono dipendere da quelle precedenti nello stesso `let`. Questo mantiene il codice pulito ed evita ripetizioni."
      :examples
      {:let-basics {:caption "Let base"
                    :description "Definisci variabili locali `r` e `h`, poi usale per creare un cilindro."}
       :let-computed {:caption "Valori calcolati"
                      :description "Le variabili possono essere calcolate da quelle precedenti. Nota: in Clojure, operatori come `+`, `-`, `*`, `/` usano notazione prefissa: `(/ base 2)` invece di `base / 2`."}}}

     :anonymous-functions
     {:title "Funzioni Anonime"
      :content "Non tutte le funzioni hanno bisogno di un nome. Clojure ha due modi per scrivere funzioni anonime (inline):

**Forma breve** — `#(...)` con `%`, `%1`, `%2` per gli argomenti:
```
#(* % 2)           ; un argomento
#(scale %1 %2)     ; due argomenti
```

**Forma estesa** — `(fn [args] corpo)` per casi più complessi:
```
(fn [shape t]
  (scale shape (- 1 t)))
```

Usa la forma breve per semplici one-liner. Usa `fn` quando il corpo è più complesso o quando dare un nome agli argomenti migliora la chiarezza.

Le funzioni anonime sono particolarmente utili con `loft`, `map`, `reduce` e `filter`."
      :examples
      {:anon-shorthand {:caption "Forma breve #()"
                        :description "`#()` crea una funzione inline rapida. `%` (o `%1`, `%2`) si riferisce agli argomenti. Qui moltiplichiamo ogni raggio per un fattore di scala."}
       :anon-fn-form {:caption "Forma estesa (fn)"
                      :description "`(fn [args] corpo)` è più chiaro quando la funzione ha più argomenti con significato specifico — qui `shape` e `t` (progresso)."}
       :anon-loft-twist {:caption "Loft con #()"
                         :description "La forma breve funziona bene per le trasformazioni loft. `%1` è la forma, `%2` è il progresso da 0 a 1."}}}

     :map-and-reduce
     {:title "Map e Reduce"
      :content "Trasforma e combina collezioni con strumenti funzionali:

**map** — Applica una funzione a ogni elemento:
```
(map #(* % 2) [1 2 3])   ; => (2 4 6)
```

**reduce** — Combina tutti gli elementi in un singolo valore:
```
(reduce + [1 2 3 4])      ; => 10
```

**map-indexed** — Come `map` ma fornisce anche l'indice:
```
(map-indexed vector [:a :b :c])
; => ([0 :a] [1 :b] [2 :c])
```

**filter** — Mantieni solo gli elementi che soddisfano un predicato:
```
(filter odd? [1 2 3 4 5]) ; => (1 3 5)
```

Si compongono naturalmente: `(map f (filter pred coll))`. Combinati con `for`, offrono modi potenti per generare geometria a partire da dati."
      :examples
      {:map-basic {:caption "Map"
                   :description "`map` applica una funzione a ogni elemento. Qui scaliamo una lista di valori per ottenere raggi, poi creiamo sfere."}
       :reduce-union {:caption "Reduce"
                      :description "`reduce` combina elementi a coppie. Qui uniamo più box in una singola mesh con `mesh-union`."}
       :map-indexed {:caption "Map-indexed"
                     :description "`map-indexed` accoppia ogni elemento con il suo indice. Utile per posizionare oggetti lungo una sequenza."}}}

     :repl-usage
     {:title "La REPL"
      :content "La **REPL** (Read-Eval-Print Loop) in fondo all'editor è il tuo spazio interattivo. Scrivi espressioni e premi Invio per valutarle immediatamente.

**Perché usare la REPL?**
- Testare piccoli pezzi di codice senza modificare le definizioni principali
- Ispezionare valori e mesh
- Esplorare funzioni Clojure interattivamente
- Debug valutando parti di espressioni

**Meccanica:**
- Scrivi un'espressione e premi **Invio** per valutare
- Usa le frecce **↑/↓** per navigare la cronologia dei comandi
- I risultati appaiono sopra la linea di input
- La REPL condivide lo stato con le tue definizioni — puoi accedere alle mesh registrate

**Comandi utili:**
```
(info :nome)      ; mostra info mesh (vertici, facce)
(get-mesh :nome)  ; recupera mesh per nome
(hide :nome)      ; nascondi mesh dal viewport
(show :nome)      ; mostra mesh nascosta
```"
      :examples
      {:repl-quick-test {:caption "Calcoli veloci"
                         :description "Usa la REPL come calcolatrice. Prova operazioni aritmetiche, stringhe o qualsiasi espressione Clojure."}
       :repl-println {:caption "Output stampato"
                      :description "L'output di `println` appare nella cronologia della REPL. Usalo per debuggare i tuoi script."}}}

     :mesh-basics
     {:title "Cos'è una Mesh"
      :content "Una **mesh** è un oggetto 3D fatto di vertici (punti nello spazio) e facce (poligoni che connettono i vertici). In Ridley, le mesh sono l'output finale delle operazioni di modellazione.

**La struttura dati mesh:**
- **vertices** — una lista di punti 3D `[x y z]`
- **faces** — una lista di indici di vertici che formano triangoli

**Registrare mesh:**
Il comando `register` dà un nome a una mesh e la aggiunge alla scena:
```
(register nome espressione-mesh)
```

**Lavorare con le mesh:**
```
(get-mesh :nome)   ; recupera mesh per nome (per op. booleane)
(info :nome)       ; mostra statistiche nella REPL
(hide :nome)       ; nascondi dal viewport
(show :nome)       ; mostra di nuovo
```

Tutte le primitive (`box`, `cyl`, `sphere`) e le estrusioni producono mesh."
      :examples
      {:mesh-register {:caption "Registrare una mesh"
                       :description "`register` crea una mesh con nome che appare nel viewport. Il nome diventa una keyword che puoi usare dopo."}
       :mesh-info {:caption "Ispezionare con info"
                   :description "`info` ritorna statistiche della mesh. Usa `println` per vedere l'output nella REPL."}
       :mesh-get-mesh {:caption "Booleane con def"
                       :description "Usa `def` per salvare mesh intermedie, poi combinale con operazioni booleane. Registra con `register` solo il risultato finale."}}}

     :boolean-operations
     {:title "Operazioni Booleane"
      :content "Le operazioni booleane combinano mesh in modi diversi. Usa `def` per salvare mesh intermedie, poi registra solo il risultato finale.

```
(mesh-union a b)        ; combina A e B
(mesh-difference a b)   ; sottrai B da A
(mesh-intersection a b) ; tieni solo dove A e B si sovrappongono
(mesh-hull a b c ...)   ; inviluppo convesso di tutte le mesh
```

Queste operazioni richiedono mesh **manifold** (a tenuta stagna, senza auto-intersezioni). Il risultato è una nuova mesh che puoi registrare."
      :examples
      {:bool-union {:caption "Unione"
                    :description "Combina due mesh in una. Le regioni sovrapposte vengono fuse."}
       :bool-difference {:caption "Differenza"
                         :description "Sottrai una mesh dall'altra. Crea fori, tagli ed effetti di intaglio."}
       :bool-intersection {:caption "Intersezione"
                           :description "Tieni solo il volume dove entrambe le mesh si sovrappongono. Crea forme a lente dalle sfere."}
       :bool-hull {:caption "Inviluppo Convesso"
                   :description "Crea la più piccola forma convessa che contiene tutte le mesh di input. Come avvolgerle in pellicola termoretraibile."}
       :bool-union-for {:caption "Unione con for"
                        :description "Le operazioni booleane accettano vettori, quindi puoi usare `for` per generare più mesh e combinarle in una sola chiamata."}}}

     :slice-mesh
     {:title "Sezione Mesh"
      :content "`slice-mesh` taglia una mesh con un piano orizzontale, restituendo una sezione 2D come shape. Usa `stamp` per visualizzare il risultato.

```
(stamp (slice-mesh :nome))       ; taglia all'altezza della tartaruga
(stamp (slice-mesh :nome 10.5))  ; taglia a un'altezza esplicita
```

Il piano di taglio è orizzontale all'altezza Y corrente della tartaruga (o a un'altezza esplicita). Il risultato è una forma piatta che puoi ispezionare, estrudere o usare in ulteriori operazioni."
      :examples
      {:slice-cup {:caption "Sezione di una tazza"
                   :description "Una tazza ottenuta per rivoluzione, tagliata a una data altezza. La sezione mostra il profilo della parete a quel livello."}
       :slice-donut {:caption "Sezioni multiple"
                     :description "Lo stesso toroide tagliato su due piani diversi. Il piano di taglio è perpendicolare alla direzione della tartaruga nella sua posizione corrente."}}}

     :attach-meshes
     {:title "Attaccare Mesh"
      :content "La funzione `attach` trasforma una mesh con movimenti turtle, restituendo una nuova mesh. Usa `attach!` per trasformare una mesh registrata in-place per nome.

```
(attach mesh (f 20) (th 45))   ; restituisce copia trasformata
(attach! :nome (f 20) (th 45)) ; aggiorna mesh registrata in-place
```

`attach` è funzionale — l'originale non cambia. `attach!` è scorciatoia per `(register nome (attach nome ...))`."
      :examples
      {:attach-basic {:caption "Attach base"
                      :description "La base è registrata all'origine. Il cilindro è attaccato con movimenti che lo posizionano relativamente alla tartaruga."}
       :attach-boolean {:caption "Attach per booleane"
                        :description "`attach` può essere usato inline per posizionare una mesh relativamente a un'altra per operazioni booleane."}
       :attach-copy {:caption "Copiare una mesh"
                     :description "Usa `attach` per creare copie trasformate di una mesh esistente in posizioni diverse."}
       :attach-copy-rotated {:caption "Copia con rotazione"
                             :description "Le copie possono includere rotazioni. Qui `tv` inclina e `tr` ruota la tartaruga prima di posizionare la copia."}}}

     :attach-faces
     {:title "Attaccare alle Facce"
      :content "La funzione `attach-face` attacca geometria a una faccia specifica di una mesh. Usa keyword (`:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`) o indici 0-5.

```
(attach-face mesh :top body)
```

La tartaruga viene posizionata al centro della faccia, orientata verso l'esterno. Usa `inset` per rimpicciolire la faccia prima di estrudere:

```
(inset dist)  ; sposta ogni vertice di dist unità verso il centro
```

Valori positivi rimpiccioliscono la faccia, valori negativi la espandono."
      :examples
      {:attach-face-frustum {:caption "Tronco (attach-face)"
                             :description "`attach-face` modifica la faccia originale. `inset` la rimpicciolisce, `f` la sposta verso l'esterno. Crea un tronco di piramide."}
       :clone-face-spike {:caption "Punta (clone-face)"
                          :description "`clone-face` crea nuovi vertici. `inset` crea una faccia interna più piccola, `f` la estrude. Crea una punta."}}}

     :warp-deformation
     {:title "Deformazione Spaziale (Warp)"
      :content "La funzione `warp` deforma i vertici di una mesh all'interno di un volume. La forma del volume (sfera, box, cilindro) determina la zona di deformazione. Posiziona il volume usando `attach`.

```
(warp mesh volume deform-fn)
(warp mesh volume fn1 fn2)          ; concatena deformazioni
(warp mesh volume (inflate 3) :subdivide 2)  ; suddividi prima
```

**Deformazioni predefinite:**
- `(inflate amount)` — spinge verso l'esterno lungo le normali
- `(dent amount)` — spinge verso l'interno
- `(attract strength)` — attrae verso il centro del volume (0–1)
- `(twist angle)` — ruota attorno all'asse auto-rilevato
- `(squash axis)` / `(squash axis amount)` — appiattisce
- `(roughen amplitude)` / `(roughen amplitude freq)` — rumore

Usa `:subdivide n` per aggiungere geometria prima di deformare (ogni passaggio: 1→3 triangoli). Essenziale per mesh a bassa risoluzione."
      :examples
      {:warp-inflate {:caption "Inflate"
                      :description "`inflate` spinge i vertici verso l'esterno lungo le normali. `:subdivide 2` aggiunge geometria per una deformazione più fluida."}
       :warp-dent {:caption "Dent"
                   :description "`dent` spinge i vertici verso l'interno lungo le normali. Il volume è spostato con `attach` per creare un'ammaccatura asimmetrica."}
       :warp-roughen {:caption "Roughen"
                      :description "`roughen` sposta i vertici con rumore Perlin. Il secondo argomento controlla la frequenza."}}}

     :debug-panels
     {:title "Pannelli 3D di Testo"
      :content "I **pannelli** sono display di testo 3D che fluttuano nel viewport. Guardano sempre verso la camera (effetto billboard) e sono perfetti per il debug o per annotare i tuoi modelli.

**Creare pannelli:**
```
(register P1 (panel larghezza altezza))
```

**Scrivere sui pannelli:**
- `(out P1 \"testo\")` — sostituisce il contenuto
- `(append P1 \"testo\")` — aggiunge al contenuto
- `(clear P1)` — cancella il contenuto

**Posizionare pannelli:**
Usa `attach` per posizionare i pannelli relativamente alla turtle:
```
(register P1 (attach (panel 50 30) (f 20)))
```

I pannelli NON vengono esportati in STL — sono solo per visualizzazione."
      :examples
      {:panel-basic {:caption "Pannello base"
                     :description "Crea un pannello e scrivi del testo. Il pannello appare alla posizione corrente della turtle."}
       :panel-attach {:caption "Pannello posizionato"
                      :description "Usa `attach` per posizionare il pannello relativo alla geometria. Il pannello fluttua vicino al cubo."}
       :panel-multiline {:caption "Testo multi-riga"
                         :description "Usa `append` con `\\n` per aggiungere nuove righe. Costruisci messaggi complessi incrementalmente."}}}

     :debug-techniques
     {:title "Tecniche di Debug"
      :content "Quando il codice non funziona come previsto, queste tecniche ti aiutano a capire cosa sta succedendo.

**La funzione T (tap):**
`T` è un helper di debug built-in che stampa un valore con un'etichetta e lo restituisce invariato:
```
(T \"etichetta\" valore)  ; stampa \"etichetta: valore\", ritorna valore
```

Inserisci `T` ovunque per vedere i valori intermedi senza cambiare il comportamento. La sua implementazione è semplicemente:
```
(defn T [label x] (println label \":\" x) x)
```
Puoi definire una tua variante se necessario.

**Ispezione della turtle:**
- `(turtle-position)` — posizione [x y z] corrente
- `(turtle-heading)` — vettore direzione corrente
- `(turtle-up)` — vettore up corrente

**Esecuzione passo-passo:**
Aggiungi istruzioni `println` tra le operazioni per vedere la sequenza degli eventi. Questo aiuta a identificare quale passo causa problemi.

**Problemi comuni:**
- Mesh non visibile? Controlla `(hide :nome)` / `(show :nome)`
- Posizione sbagliata? Stampa lo stato della turtle prima di creare geometria
- Forma inaspettata? Semplifica e testa ogni parte separatamente"
      :examples
      {:tap-function {:caption "La funzione T"
                      :description "`T` è built-in. Stampa il valore con un'etichetta, poi lo restituisce invariato. Qui vediamo la dimensione calcolata (30) nell'output della REPL."}
       :inspect-turtle {:caption "Ispezionare lo stato turtle"
                        :description "Stampa posizione e direzione della turtle in qualsiasi punto per capire dove verrà creata la geometria."}
       :step-by-step {:caption "Debug passo-passo"
                      :description "Aggiungi println tra le operazioni per tracciare l'esecuzione. L'output appare nella cronologia della REPL."}}}

     :tweak-interactive
     {:title "Tweaking Interattivo"
      :content "La macro `tweak` trasforma i letterali numerici della tua espressione in slider interattivi, permettendoti di esplorare i valori dei parametri in tempo reale.

```
(tweak expr)              ; slider per il primo letterale
(tweak n expr)            ; slider all'indice n
(tweak -1 expr)           ; ultimo letterale (stile Python)
(tweak [0 2] expr)        ; indici multipli
(tweak :all expr)         ; tutti i letterali
```

**Modalità registry** — passa una keyword per tweakare direttamente una mesh registrata. L'originale viene nascosta durante il tweaking:
```
(tweak :A)                ; usa il form sorgente memorizzato
(tweak :A expr)           ; espressione esplicita
(tweak :all :A)           ; tutti gli slider + registry
```

**Controlli:** trascina lo slider per cambiare un valore. Usa `−`/`+` per zoomare il range. Premi **OK** per confermare o **Cancel** (Esc) per annullare."
      :examples
      {:tweak-basic {:caption "Tweak primo letterale"
                     :description "Trascina lo slider per cambiare il valore `30` (la distanza in avanti). Il viewport si aggiorna in tempo reale."}
       :tweak-indexed {:caption "Tweak per indice"
                       :description "L'indice `2` seleziona il terzo letterale numerico. Indici negativi contano dalla fine."}
       :tweak-all {:caption "Tutti gli slider"
                   :description "`:all` crea slider per ogni letterale numerico nell'espressione."}
       :tweak-registered {:caption "Tweak mesh registrata"
                          :description "Passa una keyword per tweakare una mesh registrata. L'originale viene nascosta e l'anteprima con slider la sostituisce. OK ri-registra; Cancel ripristina l'originale."}}}

     :stamp-preview
     {:title "Anteprima Forme (Stamp)"
      :content "Il comando `stamp` visualizza una forma 2D alla posizione e orientamento correnti della turtle come superficie semitrasparente. Mostra esattamente dove apparirebbe la faccia iniziale di un `extrude` o `revolve`.

```
(stamp forma)
```

Gli stamp sono puramente visivi — non influenzano la posizione o la direzione della turtle, e non vengono esportati in STL. Sono perfetti per il debug: verifica la forma e la posizione prima di procedere con un'estrusione.

**Controllo visibilità:**
- Usa il bottone **Stamps** nella barra del viewport
- Oppure da DSL: `(show-stamps)` / `(hide-stamps)`
- Verifica visibilità: `(stamps-visible?)`

Gli stamp supportano forme con buchi — i buchi vengono renderizzati correttamente come aree trasparenti all'interno della superficie."
      :examples
      {:stamp-basic {:caption "Stamp base"
                     :description "Posiziona un cerchio all'origine. Lo stamp appare come superficie semitrasparente arancione."}
       :stamp-positioned {:caption "Stamp in posizione"
                          :description "Muovi e inclina la turtle, poi fai stamp. Ogni stamp appare alla posizione corrente della turtle — esattamente dove inizierebbe l'estrusione."}
       :stamp-with-holes {:caption "Stamp con buchi"
                          :description "Forme con buchi (come questa rondella da `shape-difference`) vengono renderizzate correttamente — l'area interna viene ritagliata."}}}

     :follow-path-lines
     {:title "Follow Path"
      :content "`follow-path` fa tracciare alla tartaruga un percorso memorizzato, disegnando linee visibili nel viewport 3D. A differenza di `extrude`, non crea geometria — muove solo la tartaruga lungo il percorso lasciando una traccia visiva.

```
(follow-path mio-percorso)
```

Utile per visualizzare percorsi prima di estrudere, debuggare costruzioni complesse di path, o semplicemente disegnare wireframe 3D.

**Controllo visibilità:**
- Pulsante **Lines** nella toolbar del viewport
- Comandi DSL: `(show-lines)` / `(hide-lines)`
- Stato: `(lines-visible?)`"
      :examples
      {:follow-basic {:caption "Disegnare una curva"
                      :description "`follow-path` traccia il percorso in 3D, mostrando la curva che la tartaruga seguirebbe durante un'estrusione."}
       :follow-visibility {:caption "Visibilità"
                           :description "Usa `hide-lines` / `show-lines` per alternare la visibilità di tutte le linee dei percorsi. Utile quando le linee si sovrappongono alla geometria."}}}

     :measurement
     {:title "Misurazione"
      :content "Misura distanze, aree e bounding box dei tuoi modelli direttamente dal codice o dalla REPL.

**Distanza** tra mesh, facce o punti arbitrari:
```
(distance :A :B)                  ; da centroide a centroide
(distance :A :top :B :bottom)     ; da centro faccia a centro faccia
(distance [0 0 0] [100 0 0])     ; da punto a punto
```

**Righelli visivi** — stessa sintassi di `distance`, ma disegna una linea nel viewport:
```
(ruler :A :top :B :bottom)
(clear-rulers)
```

**Bounding box** e **area della faccia**:
```
(bounds :A)        ; → {:min [...] :max [...] :size [...] :center [...]}
(area :A :top)     ; → area della faccia :top
```

**Misurazione interattiva:** tieni premuto **Shift** e clicca due punti sulle mesh nel viewport per creare un righello. Premi **Esc** per annullare o cancellare.

**Ciclo di vita:** i righelli persistono tra i comandi REPL ma vengono cancellati automaticamente quando ri-esegui l'editor (Cmd+Enter). In **modalità tweak**, i righelli si aggiornano in tempo reale mentre trascini gli slider."
      :examples
      {:distance-basic {:caption "Distanza tra mesh"
                        :description "`distance` calcola la distanza euclidea tra due punti. Con argomenti keyword, risolve automaticamente i centroidi delle mesh."}
       :ruler-faces {:caption "Righello tra facce"
                     :description "`ruler` funziona come `distance` ma disegna anche una linea gialla nel viewport. Qui collega la faccia superiore di A alla faccia inferiore di B."}
       :bounds-info {:caption "Bounding box"
                     :description "`bounds` restituisce min, max, dimensione e centro di una mesh. Funziona su mesh registrate (per keyword), dati mesh, forme 2D e path."}
       :area-face {:caption "Area di una faccia"
                   :description "`area` restituisce l'area della superficie di una faccia nominata. I nomi standard sono `:top`, `:bottom`, `:front`, `:back`, `:left`, `:right`."}}}

     :mark-goto-pushpop
     {:title "Marcatori, Path e Stack di Stato"
      :content "Quando crei geometrie complesse, spesso devi tornare a posizioni precedenti o diramarti da un punto. Ridley fornisce due meccanismi:

**Marcatori e with-path:**
- `(mark :nome)` — Dentro un `path`, salva la posizione corrente con un nome
- `(with-path p ...)` — Risolvi i mark del path `p` come ancore, poi esegui il body
- `(goto :nome)` — Teletrasportati a un'ancora nominata (disegna una linea se la penna è attiva)

I mark sono definiti nei path e risolti con `with-path`. Questo rende i path riutilizzabili — lo stesso path può essere posizionato in punti diversi.

**Composizione di path con follow:**
- `(follow altro-path)` — Dentro un `path`, inserisci i comandi di un altro path

**Scope turtle per le diramazioni:**
- `(turtle ...)` — Crea uno scope turtle isolato: il figlio eredita la posizione e le impostazioni del genitore, ma le modifiche interne non influenzano la turtle esterna

Gli scope turtle sono perfetti per le diramazioni: apri uno scope prima di un ramo, crea la geometria, e la turtle torna automaticamente alla posizione del genitore quando lo scope finisce. Nessun salvataggio/ripristino manuale necessario.

**Differenza chiave:**
- `mark`/`with-path`/`goto` = punti di layout nominati per la navigazione
- `turtle` = scope lessicale per pattern di diramazione"
      :examples
      {:mark-goto-basic {:caption "Mark e with-path"
                         :description "Definisci un path con mark nominati, poi usa `with-path` per risolverli come ancore. `goto` naviga alle posizioni marcate. I mark sono relativi alla posizione della turtle quando si chiama `with-path`."}
       :turtle-branch {:caption "Diramazioni con scope turtle"
                       :description "Ogni scope `turtle` eredita la posizione e le impostazioni del genitore. La geometria creata dentro (rami) è visibile, ma la turtle esterna non cambia — entrambi i rami originano dallo stesso punto."}
       :path-follow {:caption "Composizione di path con follow"
                     :description "`follow` inserisce i comandi di un altro path nella registrazione corrente. Qui componiamo un quadrato da un singolo segmento ripetuto quattro volte."}}}

     :arcs-beziers
     {:title "Archi e Curve di Bezier"
      :content "Le curve morbide sono essenziali per forme organiche. Ridley fornisce comandi arc e bezier che si integrano con l'estrusione.

**Arco Orizzontale:**
```
(arc-h raggio angolo)      ; gira sinistra/destra mentre ti muovi
(arc-h raggio angolo :steps 16)
```
Angolo positivo = gira a sinistra, negativo = gira a destra. La turtle finisce a distanza `raggio × angolo_rad`, con direzione ruotata di `angolo` gradi.

**Arco Verticale:**
```
(arc-v raggio angolo)      ; beccheggia su/giù mentre ti muovi
(arc-v raggio angolo :steps 16)
```
Angolo positivo = beccheggia su, negativo = beccheggia giù.

**Curve di Bezier:**
```
(bezier-to [x y z])                    ; punti di controllo automatici
(bezier-to [x y z] [c1])               ; quadratica (1 controllo)
(bezier-to [x y z] [c1] [c2])          ; cubica (2 controlli)
(bezier-to-anchor :nome)               ; bezier verso ancora nominata
(bezier-as (path ...))                 ; una bezier per segmento
(bezier-as (path ...) :tension 0.5)    ; curva più ampia
(bezier-as (path ...) :cubic true)     ; spline Catmull-Rom
(bezier-as (path ...) :max-segment-length 20) ; suddividi segmenti lunghi
```

`bezier-to` senza punti di controllo crea una curva tangente alla direzione corrente.

`bezier-to-anchor` è più intelligente: rispetta **sia** la direzione corrente CHE la direzione salvata nell'ancora, creando una curva a S morbida che onora entrambe le direzioni.

`bezier-as` prende un path turtle e lo sostituisce con una catena di curve bezier smooth, una per segmento. Usa `:tension` per controllare l'ampiezza (default 0.33). Con `:cubic true`, i waypoint interni usano direzioni Catmull-Rom per curve globalmente più morbide. Usa `:max-segment-length` per suddividere segmenti lunghi."
      :examples
      {:arc-h-basic {:caption "Arco orizzontale (inversione a U)"
                     :description "`arc-h 30 180` crea un arco di 180° con raggio 30 — un'inversione a U nel piano orizzontale. Combinato con segmenti rettilinei."}
       :arc-v-basic {:caption "Arco verticale (loop)"
                     :description "`arc-v 25 180` crea un mezzo-loop nel piano verticale, come passare sopra una collina."}
       :bezier-auto {:caption "Bezier automatica verso ancora"
                     :description "Definisci i mark in un path, risolvili con `with-path`, poi estrudi con `bezier-to-anchor`. La curva rispetta sia la direzione di partenza che la direzione salvata nell'ancora."}
       :bezier-control {:caption "Bezier con punti di controllo"
                        :description "Punti di controllo espliciti danno controllo preciso. Qui `[0 20 30]` e `[0 40 -30]` creano una curva a S."}
       :bezier-as {:caption "Smussare un path con bezier-as"
                   :description "`bezier-as` prende un path turtle e disegna una catena di curve bezier smooth, una per segmento. Usa `:tension` per controllare l'ampiezza della curva (default 0.33)."}
       :bezier-as-cubic {:caption "Spline Catmull-Rom con :cubic"
                         :description "Con `:cubic true`, i waypoint interni usano direzioni Catmull-Rom (basate sui punti vicini) invece delle direzioni del turtle, producendo curve globalmente più morbide attraverso più svolte."}}}

     :bloft-curves
     {:title "Loft Sicuro per Bezier (bloft)"
      :content "Quando si fa il loft lungo curve bezier strette, il `loft` standard può produrre geometria auto-intersecante. `bloft` (bezier-safe loft) gestisce questo rilevando le intersezioni e collegandole con convex hull.

**Uso base:**
```
(bloft shape transform-fn path)
```

**Con step espliciti:**
```
(bloft-n 64 shape transform-fn path)
```

**Parametri:**
- `shape` — forma 2D da estrudere
- `transform-fn` — funzione `(fn [shape t] ...)` o `identity`
- `path` — path che contiene comandi bezier

**Quando usare bloft:**
- Curve bezier strette (angoli > 90°)
- Shape larghe rispetto al raggio di curvatura
- Quando loft mostra auto-intersezione

**Risoluzione:**
Eredita dalla globale `(resolution :n ...)`. Per la sperimentazione usa valori bassi (:n 10-20), poi aumenta per il render finale (:n 60+).

**Suggerimento:** Lavora con bassa risoluzione durante il design, poi aumenta per il render finale."
      :examples
      {:bloft-basic {:caption "Bloft base"
                     :description "`bloft` fa il loft di un cerchio lungo un path curvo. A differenza di `loft`, gestisce curve strette senza auto-intersezione."}
       :bloft-taper {:caption "Bloft rastremato"
                     :description "Le funzioni di trasformazione funzionano come con `loft` — qui scalando per creare un tubo rastremato lungo una curva stretta."}
       :bloft-steps {:caption "Serpente smooth con :steps"
                     :description "`bezier-as` accetta `:steps` per controllare il numero di punti di interpolazione per segmento bezier — valori più alti producono curve più smooth senza cambiare la `resolution` globale. Combinato con `tapered` e `bloft-n` per un risultato curato."}}}
     :colors-materials
     {:title "Colori e Materiali"
      :content "Ogni mesh può avere le proprie proprietà di colore e materiale. Impostale **prima** di creare la geometria — si applicano a tutte le mesh create successivamente.

**Solo colore:**
```
(color 0xff0000)        ; rosso (hex RGB)
(color 255 0 0)         ; rosso (valori RGB 0-255)
```

**Controllo completo del materiale:**
```
(material :color 0x8888ff
          :metalness 0.8    ; 0-1, quanto metallico
          :roughness 0.2    ; 0-1, quanto liscio/ruvido
          :opacity 1.0      ; 0-1, trasparenza
          :flat-shading true)
```

Quando chiami `material`, le opzioni non specificate tornano ai default. Per resettare tutto:
```
(reset-material)
```

Linee guida PBR (Physically Based Rendering):
- **Metalli** (oro, cromo): metalness 0.8-1.0, roughness 0.1-0.4
- **Plastiche**: metalness 0.0-0.1, roughness 0.3-0.7
- **Superfici opache**: metalness 0.0, roughness 0.8-1.0"
      :examples
      {:color-basic {:caption "Impostare colori"
                     :description "Usa `color` per impostare rapidamente il colore per la geometria successiva. Ogni mesh ricorda il colore che era attivo quando è stata creata."}
       :material-pbr {:caption "Materiali PBR"
                      :description "Controllo completo sull'aspetto del materiale. Alta metalness + bassa roughness = metallo lucido. Bassa metalness + alta roughness = plastica opaca."}
       :material-reset {:caption "Reset ai default"
                        :description "`reset-material` riporta tutte le proprietà del materiale all'aspetto blu predefinito."}}}

     :text-shapes
     {:title "Forme di Testo"
      :content "Converti testo in forme 2D usando il font Roboto integrato.

**Uso base:**
```
(text-shape \"Hello\")           ; dimensione default
(text-shape \"Hello\" :size 30)  ; testo più grande
```

**Estrudi per testo 3D:**
```
(register sign (extrude (text-shape \"RIDLEY\" :size 20) (f 5)))
```

**Estrusione rapida con `extrude-text`:**
```
(extrude-text \"Hello\" :size 25 :depth 4)
```
Scorciatoia che estrude il testo alla posizione della turtle, fluendo lungo l'heading.

**Testo su percorso:**
```
(def mio-arco (path (arc-h 50 180)))
(text-on-path \"CURVO\" mio-arco :size 12 :depth 3)
```

Opzioni per `text-on-path`:
- `:align` — `:start`, `:center`, o `:end`
- `:spacing` — spaziatura extra tra lettere (default 0)
- `:overflow` — `:truncate`, `:wrap`, o `:scale`

**Testo 3D su spirale con preserve-up:**
```
(def spiral (path (tv 7) (dotimes [_ 500] (f 1) (th 3))))
(turtle :preserve-up
  (text-on-path \"testo spirale\" spiral :size 6 :depth 1.2))
```
Usa `:preserve-up` per mantenere le lettere verticali su percorsi 3D. Imposta l'inclinazione con `tv` prima del loop orizzontale.

**Font integrati:**
- `:roboto` — Roboto Regular (default)
- `:roboto-mono` — Roboto Mono (monospace)

**Carica font personalizzato:**
```
(load-font! \"/path/to/font.ttf\")  ; restituisce una promise
```"
      :examples
      {:text-basic {:caption "Testo 3D semplice"
                    :description "Estrudi forme di testo per creare lettere 3D. L'opzione :size controlla la dimensione del font in unità mondo."}
       :text-large {:caption "Segnaletica"
                    :description "Testo più grande per insegne e titoli. Regola :size e profondità estrusione (f 3) per le proporzioni desiderate."}
       :extrude-text {:caption "Estrusione rapida"
                      :description "`extrude-text` è una scorciatoia conveniente: il testo fluisce lungo l'heading della turtle e viene estruso perpendicolarmente."}
       :text-on-arc {:caption "Testo su arco"
                     :description "Posiziona testo lungo un percorso curvo. Ogni lettera segue la tangente alla curva. Usa `:align :center` per centrare il testo sul percorso."}
       :text-on-spiral {:caption "Testo su spirale"
                        :description "Testo su spirale 3D con `:preserve-up` per mantenere le lettere verticali. Imposta il pitch una volta con `tv`, poi gira in orizzontale."}}}

     :gallery-twisted-vase
     {:title "Vaso Torto Scanalato"
      :content "Un vaso parametrico che mostra la **composizione di shape-fn** con il macro `->`.

Il profilo è un cerchio che viene **scanalato** (bordi smerlati), **torto** lungo l'altezza, e sagomato con una **funzione di rastremazione personalizzata**. La rastremazione usa la matematica per creare una pancia che si allarga, un collo che si stringe e un labbro che si apre.

**Funzionalità dimostrate:**
- `fluted` — aggiunge ondulazioni radiali alla sezione
- `twisted` — ruota progressivamente il profilo lungo il percorso
- `shape-fn` con `(fn [shape t] ...)` personalizzata per scalatura non lineare
- `loft-n` per estrusione ad alta risoluzione
- `->` threading per comporre più shape-fn

**Prova a cambiare:**
- `n-flutes` per più o meno smerlature
- `twist-amount` per spirale più stretta o larga
- I coefficienti della curva di rastremazione per sagome diverse"
      :examples
      {:twisted-vase-code {:caption "Vaso Torto Scanalato"}}}

     :gallery-recursive-tree
     {:title "Albero Ricorsivo"
      :content "Un **albero frattale** 3D costruito con ramificazione tramite scope `turtle`.

Ad ogni livello di ricorsione, il tronco si divide in diversi rami. Ogni ramo è più sottile e corto del genitore, angolato verso l'esterno usando l'**angolo aureo** per una distribuzione spaziale uniforme. I rami sono cilindri rastremati posizionati nei punti di biforcazione.

**Funzionalità dimostrate:**
- Scope `turtle` per la ramificazione (il figlio eredita la posa del genitore)
- Funzioni ricorsive con `defn`
- Design parametrico con variabili `def`
- Primitive `cyl` rastremate per arti dall'aspetto naturale

**Prova a cambiare:**
- `max-depth` per più o meno livelli (3-6 è un buon intervallo)
- `n-branches` per alberi più folti o radi
- `spread-angle` per ramificazione più larga o stretta
- `taper` per quanto velocemente i rami si assottigliano"
      :examples
      {:recursive-tree-code {:caption "Albero Ricorsivo"}}}

     :gallery-dice
     {:title "Dado a Sei Facce"
      :content "Un classico **dado D6** con incavi sottratti su ogni faccia.

Il dado è costruito partendo da un `box`, poi posizionando sfere su tutte e sei le facce con scope `turtle` per l'orientamento. Gli incavi sono sottratti con `mesh-difference` (booleana CSG). La disposizione segue la convenzione occidentale dove le facce opposte sommano a 7.

**Funzionalità dimostrate:**
- `mesh-difference` per sottrazione booleana (CSG)
- Scope `turtle` per orientare la geometria su ogni faccia (`th`, `tv`)
- Posizionamento parametrico con funzioni e cicli
- Costruzione di oggetti complessi da primitive semplici (`box`, `sphere`)

**Prova a cambiare:**
- `die-size` per un dado più grande o piccolo
- `pip-radius` / `pip-depth` per stili diversi degli incavi
- Aggiungi `shape-offset` per arrotondare gli spigoli"
      :examples
      {:dice-code {:caption "Dado a Sei Facce"}}}

     :gallery-embossed-column
     {:title "Colonna con Testo in Rilievo"
      :content "Una **colonna classica con testo in rilievo** sulla superficie.

Il testo viene convertito in forma 2D con `text-shape`, estruso in una mesh 3D sottile, poi **rasterizzato come heightmap** con `mesh-to-heightmap`. Quella heightmap viene applicata come displacement radiale su un `loft-n` cilindrico, facendo sporgere le lettere dalla superficie. Il fusto della colonna è anche `fluted`, e ha un semplice capitello e base assemblati con `attach`.

**Funzionalità dimostrate:**
- `text-shape` per generare contorni 2D delle lettere
- `mesh-to-heightmap` per rasterizzare geometria 3D in una mappa di displacement
- Shape-fn `heightmap` per displacement superficiale
- Combinazione di più shape-fn (`fluted` + `heightmap`)
- `concat-meshes` per assemblare base + fusto + capitello

**Prova a cambiare:**
- La stringa di testo per iscrizioni diverse
- `:tile-x` per controllare quante volte il testo si avvolge
- `:amplitude` per rilievo più profondo o leggero
- `n-flutes` / `flute-depth` per le scanalature del fusto"
      :examples
      {:embossed-column-code {:caption "Colonna con Testo in Rilievo"}}}

     :gallery-canvas-weave
     {:title "Tazza con Trama a Intreccio"
      :content "Una tazza con **texture a intreccio** sulla superficie esterna. Questo è l'esempio più complesso della galleria.

Due set di fili vengono loftati lungo percorsi sinusoidali con `bezier-as`, poi ruotati di 90 gradi e intrecciati con `concat-meshes`. La mesh risultante viene campionata come **tile heightmap 2D** con `mesh-to-heightmap`, che viene poi applicata come displacement radiale a un loft cilindrico. Il tile si ripete senza giunture attorno alla tazza. Infine, `mesh-difference` svuota l'interno.

**ATTENZIONE:** Questo esempio è computazionalmente costoso e richiede **30-60 secondi** per il rendering. Il collo di bottiglia è la rasterizzazione heightmap a 256x256 e il loft a 128 step con campionamento heightmap per ogni step.

**Funzionalità dimostrate:**
- Generazione procedurale di intreccio con fili intrecciati
- `mesh-to-heightmap` per rasterizzare geometria 3D dell'intreccio
- Shape-fn `heightmap` con `:tile-x` / `:tile-y` per ripetizione seamless
- `mesh-difference` per svuotare l'interno
- `concat-meshes`, `bounds`, `bezier-as`, `attach`

**Prova a cambiare:**
- Parametri `thickness` e `compactness`
- `:tile-x` / `:tile-y` per controllare la ripetizione del pattern
- `:amplitude` per texture più profonda o leggera
- Sostituisci `circle` con `rect` per fili a nastro piatto"
      :examples
      {:canvas-weave-code {:caption "Tazza con Trama a Intreccio"}}}}}})

;; Helper to find a page in the structure
(defn- find-page-structure [page-id]
  (some (fn [section]
          (some (fn [page]
                  (when (= (:id page) page-id)
                    (assoc page :section-id (:id section))))
                (:pages section)))
        (:sections structure)))

;; Get parent section for a page
(defn get-parent-section
  "Get the section ID that contains the given page.
   Returns nil for the :toc page."
  [page-id]
  (when (not= page-id :toc)
    (some (fn [section]
            (when (some #(= (:id %) page-id) (:pages section))
              (:id section)))
          (:sections structure))))

;; Get the "up" destination for a page
(defn get-up-destination
  "Get the destination for the Up button.
   - For pages within a section: go to :toc
   - For :toc: returns nil (no up)"
  [page-id]
  (when (not= page-id :toc)
    :toc))

;; Check if page is TOC
(defn toc-page?
  "Check if page-id is the table of contents."
  [page-id]
  (= page-id :toc))

;; Get full structure for TOC rendering
(defn get-toc-structure
  "Get the full manual structure for table of contents."
  []
  (:sections structure))

;; Get merged page data (structure + i18n)
(defn get-page
  "Get page data merged with i18n content for the given language.
   Returns nil if page not found."
  [page-id lang]
  (when-let [page-struct (find-page-structure page-id)]
    (let [lang-data (get-in i18n [lang :pages page-id])
          ;; Fall back to English if translation missing
          fallback-data (when (and (not= lang :en) (nil? lang-data))
                          (get-in i18n [:en :pages page-id]))
          text-data (or lang-data fallback-data)
          ;; Merge examples with their i18n captions
          examples (mapv (fn [ex]
                           (merge ex (get-in text-data [:examples (:id ex)])))
                         (:examples page-struct))]
      (merge page-struct
             (dissoc text-data :examples)
             {:examples examples}))))

;; Get section title
(defn get-section-title
  "Get the title of a section in the given language."
  [section-id lang]
  (or (get-in i18n [lang :sections section-id :title])
      (get-in i18n [:en :sections section-id :title])
      (name section-id)))

;; Get all pages for navigation
(defn get-all-pages
  "Get a flat list of all page IDs in order."
  []
  (mapcat (fn [section]
            (map :id (:pages section)))
          (:sections structure)))

;; Get next/previous page
(defn get-adjacent-page
  "Get the next or previous page ID. Direction is :next or :prev."
  [current-page direction]
  (let [pages (vec (get-all-pages))
        idx (.indexOf pages current-page)]
    (when (>= idx 0)
      (case direction
        :next (get pages (inc idx))
        :prev (when (pos? idx) (get pages (dec idx)))
        nil))))

;; Get see-also links for a page
(defn get-see-also
  "Get the list of 'see also' page IDs for a given page.
   Returns nil if no see-also links defined."
  [page-id]
  (when-let [page-struct (find-page-structure page-id)]
    (:see-also page-struct)))
