(ns ridley.turtle.shapefn-partials-test
  "Verification for the PARTIAL form of profile-safe shape-fn combinators
   (brief-shapefn-partials). A combinator called without a leading profile
   returns the bare transform `(fn [shape t] -> shape)` — no `:shape-fn`
   metadata — so it drops into loft's legacy branch / loft+'s `:else` with no
   dispatch change.

   Covers: the partial's nature (shape-fn? => false, invocable as (xf s t));
   strong equivalence full-form == partial-in-legacy (mesh identical) for
   tapered / twisted / capped (capped exercises the *path-length* binding added
   to pure-loft-path*); displaced's 1-arity partial; the loft+ guard message;
   and invocation from interpreted SCI code."
  (:require [cljs.test :refer [deftest testing is]]
            [ridley.editor.sci-harness :as h]
            [ridley.editor.impl :as impl]
            [ridley.turtle.shape :as shape]
            [ridley.turtle.shape-fn :as sfn]
            [sci.core :as sci]))

(defn- path* [code] (:result (h/eval-dsl code)))
(defn- try* [thunk] (try (thunk) nil (catch :default e (.-message e))))
(defn- rad [s] (apply max (map (fn [[x y]] (Math/sqrt (+ (* x x) (* y y)))) (:points s))))

(def ^:private circle (shape/circle-shape 20 24))

;; ── 1. nature of a partial ─────────────────────────────────────────

(deftest partial-is-a-bare-transform
  (testing "a partial combinator has no :shape-fn metadata"
    (is (false? (sfn/shape-fn? (sfn/tapered :to 0.5))))
    (is (false? (sfn/shape-fn? (sfn/twisted :angle 90))))
    (is (false? (sfn/shape-fn? (sfn/fluted :flutes 8))))
    (is (false? (sfn/shape-fn? (sfn/capped 3))))
    (is (false? (sfn/shape-fn? (sfn/noisy :amplitude 1))))
    (is (false? (sfn/shape-fn? (sfn/displaced (fn [_p _t] 0))))))

  (testing "it is invocable as (xf shape t) and transforms the shape"
    (let [xf (sfn/tapered :to 0.5)
          out (xf circle 1)]                     ;; t=1 → scale 0.5 → radius 10
      (is (shape/shape? out))
      (is (< (Math/abs (- (rad out) 10)) 1e-9)))))

;; ── 2. strong equivalence: full-form == partial-in-legacy ──────────

(defn- mesh= [a b]
  (and (= (:vertices a) (:vertices b))
       (= (:faces a) (:faces b))))

(deftest full-equals-partial-in-legacy
  (testing "tapered"
    (let [p (path* "(path (f 30))")
          full (impl/loft-impl (sfn/tapered (shape/circle-shape 20 24) :to 0.5) p)
          part (impl/loft-impl (shape/circle-shape 20 24) (sfn/tapered :to 0.5) p)]
      (is (mesh= full part))))

  (testing "twisted"
    (let [p (path* "(path (f 40))")
          full (impl/loft-impl (sfn/twisted (shape/rect-shape 30 20) :angle 90) p)
          part (impl/loft-impl (shape/rect-shape 30 20) (sfn/twisted :angle 90) p)]
      (is (mesh= full part))))

  (testing "capped — equivalence hinges on *path-length* binding in pure-loft-path*"
    (let [p (path* "(path (f 50))")
          full (impl/loft-impl (sfn/capped (shape/rect-shape 40 20) 3) p)
          part (impl/loft-impl (shape/rect-shape 40 20) (sfn/capped 3) p)]
      (is (mesh= full part)))))

;; ── 3. displaced 1-arity partial ───────────────────────────────────

(deftest displaced-partial
  (testing "(displaced displace-fn) in a legacy loft == full (displaced shape displace-fn)"
    (let [p (path* "(path (f 30))")
          dfn (fn [pnt t] (* 2 (Math/sin (+ (* (sfn/angle pnt) 6) (* t 3)))))
          full (impl/loft-impl (sfn/displaced (shape/circle-shape 15 64) dfn) p)
          part (impl/loft-impl (shape/circle-shape 15 64) (sfn/displaced dfn) p)]
      (is (mesh= full part)))))

;; ── 4. loft+ guard message points at the partial form ──────────────

(deftest guard-suggests-partial
  (testing "a full shape-fn as loft+'s transform is rejected with a helpful message"
    (let [msg (try* #(impl/loft+-impl circle
                                      (sfn/tapered (shape/circle-shape 10 24) :to 0.5)
                                      (path* "(path (f 30))")))]
      (is (some? msg))
      (is (re-find #"partial form" msg))
      (is (re-find #"tapered :to" msg)))))

;; ── 5. the motivating use case: partial as a loft+ transform ───────

(deftest partial-as-loft+-transform
  (testing "(loft+ (tapered :to 1.3) …) reads cleanly and builds the right end section"
    (let [res (impl/loft+-impl circle (sfn/tapered :to 1.3) (path* "(path (f 30))"))
          end (:end-face res)]
      (is (seq (:vertices (:mesh res))))
      ;; straight rail → last ring at t=1 → section expanded to 1.3× (radius 26)
      (is (< (Math/abs (- (rad (:shape end)) 26)) 1e-6)))))

;; ── 6. invoked from interpreted SCI code ───────────────────────────

(deftest partial-from-interpreted-sci
  (testing "a partial built and applied inside interpreted SCI code works"
    (let [ctx (sci/init {:bindings {'tapered   sfn/tapered
                                    'circle    shape/circle-shape
                                    'shape-fn? sfn/shape-fn?}})
          not-sfn (sci/eval-string "(shape-fn? (tapered :to 0.5))" ctx)
          npoints (sci/eval-string "(count (:points ((tapered :to 0.5) (circle 20) 1)))" ctx)]
      (is (false? not-sfn) "partial is not a shape-fn even through SCI")
      (is (pos? npoints) "the partial is callable as (xf shape t) in SCI"))))
