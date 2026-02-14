;; =========================
;; Puppet Library for Ridley
;; Articulated humanoid figures with preset actions
;; =========================

;; Default proportions (all relative to total height)
(def puppet-default-proportions
  {:head-radius     0.06
   :neck-len        0.03
   :torso-height    0.30
   :torso-width     0.14
   :torso-depth     0.08
   :shoulder-width  0.09
   :hip-width       0.06
   :upper-arm-len   0.18
   :upper-arm-radius 0.025
   :forearm-len     0.15
   :forearm-radius  0.02
   :upper-leg-len   0.22
   :upper-leg-radius 0.03
   :lower-leg-len   0.20
   :lower-leg-radius 0.025})

;; =========================
;; Naming helpers
;; =========================

(defn- qn
  "Build qualified keyword: (qn :bob \"r-arm\" \"upper\") => :bob/r-arm/upper"
  [puppet-name & parts]
  (keyword (clojure.string/join "/" (cons (name puppet-name) parts))))

;; =========================
;; Skeleton builders
;; =========================

(defn- make-body-skeleton
  "Body skeleton path: origin at torso center.
   Marks: :shoulder-r, :shoulder-l, :neck, :hip-r, :hip-l.
   Starts by pitching heading upward (tv 90) so f = vertical."
  [torso-h shoulder-w hip-w neck-len]
  (path
    (tv 90) ;; heading up [0,0,1], up [-1,0,0]
    ;; Go to top of torso (torso-h/2 above center)
    (f (/ torso-h 2))
    ;; Right shoulder: turn heading right, move, mark
    (th -90) (f shoulder-w) (mark :shoulder-r)
    ;; Left shoulder: reverse, go 2x, mark
    (th 180) (f (* 2 shoulder-w)) (mark :shoulder-l)
    ;; Return to center top
    (th 180) (f shoulder-w) (th 90)
    ;; Neck: above top of torso
    (f neck-len) (mark :neck)
    ;; Go back down for hips: reverse heading, go to base
    (tv 180)
    (f (+ neck-len torso-h)) ;; from neck down to torso base (hip level)
    ;; Right hip
    (th -90) (f hip-w) (mark :hip-r)
    ;; Left hip
    (th 180) (f (* 2 hip-w)) (mark :hip-l)))

(defn- make-limb-skeleton
  "Limb skeleton: origin at attachment point.
   Marks: :top (origin), :joint (elbow/knee), :tip (wrist/ankle)"
  [upper-len lower-len]
  (path
    (mark :top)
    (f upper-len) (mark :joint)
    (f lower-len) (mark :tip)))

(defn- make-neck-skeleton
  "Neck skeleton: origin at neck base.
   Marks: :base (origin), :head-base (top of neck)"
  [neck-len]
  (path
    (mark :base)
    (f neck-len) (mark :head-base)))

;; =========================
;; Limb builder helper
;; =========================

(defn- build-limb
  "Build a two-segment limb (arm or leg).
   Assumes turtle is at the attachment point with heading pointing
   along the limb direction (downward for rest pose)."
  [puppet-name side limb-type upper-r upper-len lower-r lower-len
   parent-name parent-anchor limb-sk]
  (let [prefix (str side "-" limb-type)
        upper-name (qn puppet-name prefix "upper")
        lower-name (qn puppet-name prefix "lower")]
    ;; Upper segment: extrude from current position along heading
    (let [upper-mesh (extrude (circle upper-r) (f upper-len))]
      (register-mesh! upper-name upper-mesh)
      (show-mesh! upper-name)
      (attach-path upper-name limb-sk)
      (link! upper-name parent-name :at parent-anchor :inherit-rotation true))
    ;; Lower segment: goto joint mark, extrude along heading
    (goto :joint)
    (let [lower-mesh (extrude (circle lower-r) (f lower-len))]
      (register-mesh! lower-name lower-mesh)
      (show-mesh! lower-name)
      (link! lower-name upper-name :at :joint :inherit-rotation true))))

;; =========================
;; Action definitions
;; =========================

(defn- stop-action
  "Stop only animations belonging to this puppet."
  [pname]
  (let [pname-str (name pname)
        anims (anim-list)]
    (doseq [{anim-name :name} anims]
      (when (and anim-name
                 ;; Match both :bob-walk-bounce and :bob/torso-fn12345 patterns
                 (let [full (subs (str anim-name) 1)]
                   (or (clojure.string/starts-with? full (str pname-str "-"))
                       (clojure.string/starts-with? full (str pname-str "/")))))
        (stop! anim-name)))))

(defn- walk-action [pname & {:keys [speed] :or {speed 1.0}}]
  (stop-action pname)
  (let [dur (/ 1.0 speed)
        fns [;; Torso bounce — 2x per stride (once per step)
             (anim-fn dur (qn pname "torso") :loop
               (span 0.25 :out (u 2))
               (span 0.25 :in (u -2))
               (span 0.25 :out (u 2))
               (span 0.25 :in (u -2)))
             ;; Right leg upper: forward → idle → backward → idle
             (anim-fn dur (qn pname "r-leg" "upper") :loop
               (span 0.25 :in-out (tv 30))
               (span 0.25 :in-out (tv -30))
               (span 0.25 :in-out (tv -30))
               (span 0.25 :in-out (tv 30)))
             ;; Right knee: bends during forward swing, slight during push-off
             (anim-fn dur (qn pname "r-leg" "lower") :loop
               (span 0.25 :in-out (tv 40))
               (span 0.25 :in-out (tv -40))
               (span 0.25 :in-out (tv 10))
               (span 0.25 :in-out (tv -10)))
             ;; Left leg upper: opposite phase
             (anim-fn dur (qn pname "l-leg" "upper") :loop
               (span 0.25 :in-out (tv -30))
               (span 0.25 :in-out (tv 30))
               (span 0.25 :in-out (tv 30))
               (span 0.25 :in-out (tv -30)))
             ;; Left knee: opposite phase
             (anim-fn dur (qn pname "l-leg" "lower") :loop
               (span 0.25 :in-out (tv 10))
               (span 0.25 :in-out (tv -10))
               (span 0.25 :in-out (tv 40))
               (span 0.25 :in-out (tv -40)))
             ;; Right arm upper: opposite to right leg
             (anim-fn dur (qn pname "r-arm" "upper") :loop
               (span 0.25 :in-out (tv -20))
               (span 0.25 :in-out (tv 20))
               (span 0.25 :in-out (tv 20))
               (span 0.25 :in-out (tv -20)))
             ;; Right forearm: elbow bends on backward swing
             (anim-fn dur (qn pname "r-arm" "lower") :loop
               (span 0.25 :in-out (tv 10))
               (span 0.25 :in-out (tv -10))
               (span 0.25 :in-out (tv 35))
               (span 0.25 :in-out (tv -35)))
             ;; Left arm upper: opposite to left leg
             (anim-fn dur (qn pname "l-arm" "upper") :loop
               (span 0.25 :in-out (tv 20))
               (span 0.25 :in-out (tv -20))
               (span 0.25 :in-out (tv -20))
               (span 0.25 :in-out (tv 20)))
             ;; Left forearm: elbow bends on backward swing
             (anim-fn dur (qn pname "l-arm" "lower") :loop
               (span 0.25 :in-out (tv 35))
               (span 0.25 :in-out (tv -35))
               (span 0.25 :in-out (tv 10))
               (span 0.25 :in-out (tv -10)))]]
    (mapv (fn [f] (f)) fns)))

(defn- wave-action [pname & {:keys [hand] :or {hand :right}}]
  (stop-action pname)
  (let [side (if (= hand :left) "l" "r")
        n-upper (keyword (str (name pname) "-wave-upper"))
        n-lower (keyword (str (name pname) "-wave-lower"))]
    ;; Raise arm up and hold with gentle sway
    (anim! n-upper 2.0 (qn pname (str side "-arm") "upper") :loop-bounce
      (span 1.0 :in-out (tv -80)))
    (play! n-upper)
    ;; Forearm oscillates — the actual waving motion (synced cycle)
    (anim! n-lower 1.0 (qn pname (str side "-arm") "lower") :loop-bounce
      (span 1.0 :in-out (tv 40)))
    (play! n-lower)))

(defn- idle-action [pname]
  (stop-action pname)
  (let [n-breathe (keyword (str (name pname) "-idle-breathe"))
        n-head (keyword (str (name pname) "-idle-head"))]
    ;; Subtle breathing
    (anim! n-breathe 3.0 (qn pname "torso") :loop-bounce
      (span 1.0 :in-out (u 1)))
    (play! n-breathe)
    ;; Head subtle sway
    (anim! n-head 4.0 (qn pname "head") :loop-bounce
      (span 1.0 :in-out (tv 3)))
    (play! n-head)))

(defn- nod-action [pname]
  (stop-action pname)
  (let [n-nod (keyword (str (name pname) "-nod"))]
    ;; Head pitch down then back up (single cycle)
    (anim! n-nod 0.8 (qn pname "head")
      (span 0.4 :in (tv 20))
      (span 0.6 :out (tv -20)))
    (play! n-nod)))

;; =========================
;; Main constructor
;; =========================

(defn humanoid
  "Create an articulated humanoid puppet.

   Options:
     :name        - keyword name for the puppet (required)
     :height      - total height in units (default 100)
     :proportions - map of proportion overrides

   Creates 11 mesh parts with hierarchical links:
     <name>/torso, <name>/head,
     <name>/r-arm/upper, <name>/r-arm/lower,
     <name>/l-arm/upper, <name>/l-arm/lower,
     <name>/r-leg/upper, <name>/r-leg/lower,
     <name>/l-leg/upper, <name>/l-leg/lower

   Registers actions map at <name>/actions with keys:
     :walk, :wave, :idle, :nod, :stop"
  [& {:keys [name height proportions] :or {height 100}}]
  (let [;; Merge proportions
        props (merge puppet-default-proportions proportions)
        h height
        s (fn [k] (* h (get props k)))

        ;; Compute dimensions
        head-r       (s :head-radius)
        neck-len     (s :neck-len)
        torso-h      (s :torso-height)
        torso-w      (s :torso-width)
        torso-d      (s :torso-depth)
        shoulder-w   (s :shoulder-width)
        hip-w        (s :hip-width)
        upper-arm-l  (s :upper-arm-len)
        upper-arm-r  (s :upper-arm-radius)
        forearm-l    (s :forearm-len)
        forearm-r    (s :forearm-radius)
        upper-leg-l  (s :upper-leg-len)
        upper-leg-r  (s :upper-leg-radius)
        lower-leg-l  (s :lower-leg-len)
        lower-leg-r  (s :lower-leg-radius)

        ;; Derived heights
        leg-h (+ upper-leg-l lower-leg-l)
        torso-center-z (+ leg-h (/ torso-h 2))

        ;; Build skeletons
        body-sk (make-body-skeleton torso-h shoulder-w hip-w neck-len)
        arm-sk  (make-limb-skeleton upper-arm-l forearm-l)
        leg-sk  (make-limb-skeleton upper-leg-l lower-leg-l)
        neck-sk (make-neck-skeleton neck-len)

        ;; Qualified name helper
        n (fn [& parts] (apply qn name parts))

        ;; Torso name for linking
        torso-name (n "torso")]

    ;; Set low-poly resolution for puppet parts
    (resolution :n 8)

    ;; Position turtle at torso center
    ;; Default orientation: heading +X, up +Z
    (pen-up)
    (u torso-center-z)

    ;; -- TORSO --
    ;; box(sx, sy, sz) maps to right(+Y)=width, up(+Z)=height, heading(+X)=depth
    (let [torso-mesh (box torso-w torso-h torso-d)]
      (register-mesh! torso-name torso-mesh)
      (show-mesh! torso-name)
      (attach-path torso-name body-sk))

    ;; Pin body skeleton marks for goto navigation
    (with-path body-sk

      ;; -- HEAD --
      ;; At :neck mark, heading is up [0,0,1]
      (goto :neck)
      (with-path neck-sk
        (goto :head-base)
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
        (let [skull (sphere head-r)]
          ;; Move turtle back to neck base so creation-pose pivot is at the neck joint.
          ;; This makes tv rotations swing the head around the neck (visible nod)
          ;; instead of rotating the sphere around its own center (invisible).
          (goto :base)
          (register-mesh! (n "head") (set-creation-pose skull))
          (show-mesh! (n "head"))
          (link! (n "head") torso-name :at :neck :inherit-rotation true)))

      ;; -- RIGHT ARM --
      ;; At :shoulder-r mark: heading [0,-1,0], up [-1,0,0]
      ;; th(-90) turns heading to [0,0,-1] (down) for arm hanging
      (goto :shoulder-r)
      (th -90)
      (with-path arm-sk
        (build-limb name "r" "arm" upper-arm-r upper-arm-l forearm-r forearm-l
                    torso-name :shoulder-r arm-sk))

      ;; -- LEFT ARM --
      ;; At :shoulder-l mark: heading [0,1,0], up [-1,0,0]
      ;; th(90) turns heading to [0,0,-1] (down)
      (goto :shoulder-l)
      (th 90)
      (with-path arm-sk
        (build-limb name "l" "arm" upper-arm-r upper-arm-l forearm-r forearm-l
                    torso-name :shoulder-l arm-sk))

      ;; -- RIGHT LEG --
      ;; At :hip-r mark: heading [0,-1,0], up [1,0,0]
      ;; th(90) turns heading to [0,0,-1] (down)
      (goto :hip-r)
      (th 90)
      (with-path leg-sk
        (build-limb name "r" "leg" upper-leg-r upper-leg-l lower-leg-r lower-leg-l
                    torso-name :hip-r leg-sk))

      ;; -- LEFT LEG --
      ;; At :hip-l mark: heading [0,1,0], up [1,0,0]
      ;; th(-90) turns heading to [0,0,-1] (down)
      (goto :hip-l)
      (th -90)
      (with-path leg-sk
        (build-limb name "l" "leg" upper-leg-r upper-leg-l lower-leg-r lower-leg-l
                    torso-name :hip-l leg-sk)))

    ;; Register actions
    (register-value! (n "actions")
      {:walk  (fn [& opts] (apply walk-action name opts))
       :wave  (fn [& opts] (apply wave-action name opts))
       :idle  (fn [& opts] (apply idle-action name opts))
       :nod   (fn [& opts] (apply nod-action name opts))
       :stop  (fn [] (stop-action name))})

    ;; Restore default resolution
    (resolution :n 32)

    ;; Return puppet name
    name))

;; =========================
;; Convenience function
;; =========================

(defn act
  "Execute a puppet action.
   (puppet/act :bob :walk :speed 1.5)
   (puppet/act :bob :wave)
   (puppet/act :bob :stop)"
  [puppet-name action & opts]
  (let [actions ($ (qn puppet-name "actions"))]
    (when-let [f (get actions action)]
      (apply f opts))))

;; =========================
;; AI Context
;; =========================

(def AI-PROMPT
  "Puppet library for Ridley. Creates articulated humanoid figures.
   Use (puppet/humanoid :name :bob) to create a default puppet.
   Access actions via ($ :bob/actions) — returns map of :walk :wave :idle :nod :stop.
   All parts have qualified names: :bob/torso, :bob/r-arm/upper, :bob/r-leg/lower, etc.
   Custom proportions override defaults: :height, :proportions {:head-radius 0.08}.
   Animations use the standard anim!/anim-proc! system with link-based hierarchy.
   Convenience: (puppet/act :bob :walk :speed 1.5)")

(def EXAMPLES
  ";; Create a puppet and make it walk
   (puppet/humanoid :name :bob)
   ((:walk ($ :bob/actions)))

   ;; Wave hello
   ((:wave ($ :bob/actions)))

   ;; Stop all animations for bob
   ((:stop ($ :bob/actions)))

   ;; Convenience function
   (puppet/act :bob :walk :speed 1.5)
   (puppet/act :bob :wave)
   (puppet/act :bob :stop)

   ;; Two puppets
   (puppet/humanoid :name :alice)
   (puppet/humanoid :name :bob :height 120)
   (puppet/act :alice :walk :speed 0.8)
   (puppet/act :bob :wave)

   ;; Custom proportions (bigger head)
   (puppet/humanoid :name :tiny
     :height 50
     :proportions {:head-radius 0.08
                   :torso-height 0.25})")

;; =========================
;; Quick test
;; =========================

(comment
  ;; 1. Create a puppet
  (puppet/humanoid :name :test)

  ;; 2. Verify parts exist
  (registered-names) ;; should include :test/torso, :test/r-arm/upper, etc.

  ;; 3. Test actions
  (puppet/act :test :idle)
  (puppet/act :test :walk)
  (puppet/act :test :wave)
  (puppet/act :test :nod)
  (puppet/act :test :stop)

  ;; 4. Verify hierarchy
  (hide :test/torso)
  (show :test/torso))
