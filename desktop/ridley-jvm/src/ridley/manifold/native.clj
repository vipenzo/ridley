(ns ridley.manifold.native
  "Manifold CSG operations via HTTP to the Rust backend on :12321.
   Synchronous calls — the JVM thread blocks until Rust responds."
  (:require [clj-http.client :as http]
            [clojure.data.json :as json]))

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

(defn union
  "Union meshes via native Rust Manifold."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (if (<= (count meshes) 1)
      (first meshes)
      (invoke "/union" (mapv mesh->json meshes)))))

(defn difference
  "Difference meshes via native Rust Manifold."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (when (>= (count meshes) 2)
      (invoke "/difference" {:base (mesh->json (first meshes))
                             :cutters (mapv mesh->json (rest meshes))}))))

(defn intersection
  "Intersection meshes via native Rust Manifold."
  [first-arg & more]
  (let [meshes (if (and (empty? more) (sequential? first-arg))
                 (vec first-arg)
                 (into [first-arg] more))]
    (if (<= (count meshes) 1)
      (first meshes)
      (invoke "/intersection" (mapv mesh->json meshes)))))

(defn hull
  "Convex hull via native Rust Manifold."
  [& args]
  (let [meshes (if (and (= 1 (count args)) (sequential? (first args)))
                 (first args)
                 args)]
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
