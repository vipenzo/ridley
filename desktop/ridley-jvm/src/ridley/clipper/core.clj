(ns ridley.clipper.core
  "Stub — 2D boolean operations not ported to JVM spike.
   These are only needed for advanced loft features (shell, woven)
   which the multiboard benchmark doesn't use.")

(defn shape-union [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-difference [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-intersection [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-xor [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-offset [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-hull [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn shape-bridge [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn pattern-tile [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn point-in-polygon? [& _] (throw (Exception. "clipper not available in JVM spike")))
(defn dedup-consecutive [pts] pts)
