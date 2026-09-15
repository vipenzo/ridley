(ns ridley.schema
  "Lightweight specs for core data structures. Used for development-time
   assertions to catch malformed meshes/paths early."
  (:require [cljs.spec.alpha :as s]))

;; --- Basic components ---

(s/def :geom/coord number?)
(s/def :geom/vertex
  (s/and vector?
         (fn [v] (= 3 (count v)))
         (fn [v] (every? number? v))))
(s/def :geom/face
  (s/and vector?
         (fn [v] (= 3 (count v)))
         (fn [v] (every? int? v))))

;; --- Mesh ---

(s/def :mesh/type keyword?)
(s/def :mesh/vertices (s/coll-of :geom/vertex :kind vector? :min-count 0))
(s/def :mesh/faces (s/coll-of :geom/face :kind vector? :min-count 0))
(s/def :mesh/primitive keyword?)
(s/def :mesh/registry-id int?)
(s/def :mesh/material map?)

(s/def :ridley/mesh
  (s/keys :req-un [:mesh/type :mesh/vertices :mesh/faces]
          :opt-un [:mesh/primitive :mesh/registry-id :mesh/material]))

;; --- Path ---

(s/def :path/type #{:path})
(s/def :path/point :geom/vertex)
(s/def :path/from :path/point)
(s/def :path/to :path/point)
(s/def :path/segment
  (s/and map?
         #(contains? % :type) ; allow raw :type keyword (:line, :arc, etc.)
         (s/keys :req-un [:path/from :path/to])))
(s/def :path/segments (s/coll-of :path/segment :kind vector?))

;; Two path representations coexist: the RECORDED path the `path` macro builds
;; (:commands, replayed by each consumer — Architecture §6.3) and the older
;; segment list of path-from-state. Both are :type :path; register-path! sees
;; the recorded one, so the spec must admit it (it did not until 2026-09-10:
;; (register skeleton (path …)) threw "Invalid path" in every dev build).
(s/def :path/cmd keyword?)
(s/def :path/command (s/keys :req-un [:path/cmd]))
(s/def :path/commands (s/coll-of :path/command :kind vector?))

(s/def :ridley/path
  (s/or :recorded (s/keys :req-un [:path/type :path/commands])
        :segments (s/keys :req-un [:path/type :path/segments])))

;; --- Helpers ---

(defn assert-mesh!
  "Assert mesh validity in dev mode; no-op in optimized builds."
  [mesh]
  (when ^boolean js/goog.DEBUG
    (when-not (s/valid? :ridley/mesh mesh)
      (throw (js/Error. (str "Invalid mesh: " (s/explain-str :ridley/mesh mesh))))))
  mesh)

(defn assert-path!
  "Assert path validity in dev mode."
  [path]
  (when ^boolean js/goog.DEBUG
    (when-not (s/valid? :ridley/path path)
      (throw (js/Error. (str "Invalid path: " (s/explain-str :ridley/path path))))))
  path)
