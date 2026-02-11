(ns ridley.anim.easing
  "Easing functions for animation.
   All functions map t ∈ [0,1] → [0,1].")

(defn ease
  "Apply easing function to t ∈ [0,1]. Returns eased value in [0,1].
   Supported types: :linear :in :out :in-out
                    :in-cubic :out-cubic :in-out-cubic
                    :spring :bounce"
  [type t]
  (let [t (max 0.0 (min 1.0 t))]
    (case type
      :linear t

      :in (* t t)

      :out (- 1 (* (- 1 t) (- 1 t)))

      :in-out (if (< t 0.5)
                (* 2 t t)
                (- 1 (* 2 (- 1 t) (- 1 t))))

      :in-cubic (* t t t)

      :out-cubic (- 1 (Math/pow (- 1 t) 3))

      :in-out-cubic (if (< t 0.5)
                      (* 4 t t t)
                      (- 1 (/ (Math/pow (+ (* -2 t) 2) 3) 2)))

      :spring (let [c4 (/ (* 2 Math/PI) 3)]
                (cond
                  (== t 0) 0
                  (== t 1) 1
                  :else (+ 1
                           (* (Math/pow 2 (* -10 t))
                              (Math/sin (* (- (* t 10) 0.75) c4))))))

      :bounce (let [n1 7.5625
                    d1 2.75]
                (cond
                  (< t (/ 1 d1))
                  (* n1 t t)

                  (< t (/ 2 d1))
                  (let [t' (- t (/ 1.5 d1))]
                    (+ (* n1 t' t') 0.75))

                  (< t (/ 2.5 d1))
                  (let [t' (- t (/ 2.25 d1))]
                    (+ (* n1 t' t') 0.9375))

                  :else
                  (let [t' (- t (/ 2.625 d1))]
                    (+ (* n1 t' t') 0.984375))))

      ;; Default: linear
      t)))
