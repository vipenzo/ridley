(ns ridley.manifold.native
  "Manifold CSG operations via HTTP to the Rust backend on :12321.
   Synchronous calls — the JVM thread blocks until Rust responds.
   Automatically materializes SDF nodes when mixed with mesh ops."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]
            [ridley.sdf.core :as sdf]))

(def ^:private server-url "http://127.0.0.1:12321")

(defn- mesh->json [mesh]
  {:vertices (:vertices mesh)
   :faces (:faces mesh)})

(defn- json->mesh [body]
  (let [data (if (string? body) (json/read-str body :key-fn keyword) body)]
    {:type :mesh
     :vertices (mapv vec (:vertices data))
     :faces (mapv (fn [f] (mapv int f)) (:faces data))
     :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}))

(defn- invoke [endpoint payload]
  (let [body-str (json/write-str payload)
        resp (http/post (str server-url endpoint)
               {:body body-str
                :content-type :json
                :as :string
                :throw-exceptions false})]
    (if (= 200 (:status resp))
      (json->mesh (:body resp))
      (throw (Exception. (str "Rust server " endpoint " returned " (:status resp) ": " (:body resp)))))))

(defn- ensure-all-mesh
  "Materialize any SDF nodes in a list of operands.
   Uses the first mesh found as bounds reference for SDF nodes."
  [items]
  (let [first-mesh (first (remove sdf/sdf-node? items))]
    (mapv #(sdf/ensure-mesh % first-mesh) items)))

(defn union
  "Union meshes/SDF via native Rust Manifold. SDF nodes auto-materialized."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))
        ;; If ALL are SDF, combine as SDF tree
        all-sdf? (every? sdf/sdf-node? meshes)]
    (if all-sdf?
      (reduce sdf/sdf-union meshes)
      (let [meshes (ensure-all-mesh meshes)]
        (if (<= (count meshes) 1)
          (first meshes)
          (invoke "/union" (mapv mesh->json meshes)))))))

(defn difference
  "Difference meshes/SDF via native Rust Manifold. SDF nodes auto-materialized."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))
        all-sdf? (every? sdf/sdf-node? meshes)]
    (if all-sdf?
      (reduce sdf/sdf-difference meshes)
      (let [meshes (ensure-all-mesh meshes)]
        (when (>= (count meshes) 2)
          (invoke "/difference" {:base (mesh->json (first meshes))
                                 :cutters (mapv mesh->json (rest meshes))}))))))

(defn intersection
  "Intersection meshes/SDF via native Rust Manifold."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))
        all-sdf? (every? sdf/sdf-node? meshes)]
    (if all-sdf?
      (reduce sdf/sdf-intersection meshes)
      (let [meshes (ensure-all-mesh meshes)]
        (if (<= (count meshes) 1)
          (first meshes)
          (invoke "/intersection" (mapv mesh->json meshes)))))))

(defn hull
  "Convex hull via native Rust Manifold."
  [& args]
  (let [meshes (if (and (= 1 (count args)) (sequential? (first args)))
                 (first args)
                 args)
        meshes (ensure-all-mesh meshes)]
    (invoke "/hull" (mapv mesh->json meshes))))

(defn manifold? [_mesh] true) ;; Stub — assume valid for spike

(defn get-mesh-status [_mesh] {:manifold? true :status :ok})

(defn solidify [mesh] (union mesh)) ;; Self-union

(defn concat-meshes
  "Concatenate meshes without boolean (just merge vertex/face arrays)."
  [meshes]
  (reduce (fn [acc m]
            (let [offset (count (:vertices acc))]
              {:type :mesh
               :vertices (into (:vertices acc) (:vertices m))
               :faces (into (:faces acc)
                            (mapv (fn [f] (mapv #(+ % offset) f)) (:faces m)))
               :creation-pose (:creation-pose acc)}))
          {:type :mesh :vertices [] :faces []
           :creation-pose {:position [0 0 0] :heading [1 0 0] :up [0 0 1]}}
          meshes))
