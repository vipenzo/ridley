(ns ridley.export.threemf-test
  (:require [cljs.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ridley.export.threemf :as t]))

;; threemf/meshes->model-xml is private; reach into it via the var.
(def ^:private build-color-index @#'t/build-color-index)
(def ^:private model-xml-impl @#'t/meshes->model-xml)
(def ^:private model-settings-config @#'t/model-settings-config)

(defn- model-xml [meshes]
  (let [{:keys [palette mesh->color]} (build-color-index meshes)]
    (model-xml-impl meshes palette mesh->color)))

(defn- settings-config [meshes]
  (let [{:keys [mesh->color]} (build-color-index meshes)]
    (model-settings-config meshes mesh->color)))

(defn- mk-mesh
  ([] (mk-mesh nil nil))
  ([color] (mk-mesh color nil))
  ([color export-name]
   (cond-> {:vertices [[0.0 0.0 0.0] [1.0 0.0 0.0] [0.0 1.0 0.0]]
            :faces    [[0 1 2]]}
     color       (assoc :material {:color color})
     export-name (assoc :export-name export-name))))

(deftest no-color-is-noop
  (testing "meshes without color produce no <basematerials> and no pid/pindex"
    (let [xml (model-xml [(mk-mesh) (mk-mesh)])]
      (is (not (str/includes? xml "<basematerials")))
      (is (not (str/includes? xml "pid=")))
      (is (not (str/includes? xml "pindex=")))
      (is (str/includes? xml "<object id=\"1\" type=\"model\">"))
      (is (str/includes? xml "<object id=\"2\" type=\"model\">")))))

(deftest registered-name-emits-name-attr
  (testing "mesh tagged with :export-name gets a name attribute on <object>"
    (let [xml (model-xml [(mk-mesh nil :supporto)])]
      (is (str/includes? xml "name=\"supporto\"")))))

(deftest single-color-emits-basematerials
  (testing "a single colored mesh emits <basematerials> + pid/pindex"
    (let [xml (model-xml [(mk-mesh 0xff0000 :supporto)])]
      (is (str/includes? xml "<basematerials id=\"100\">"))
      (is (str/includes? xml "displaycolor=\"#FF0000FF\""))
      (is (str/includes? xml "name=\"supporto\""))
      (is (str/includes? xml "pid=\"100\""))
      (is (str/includes? xml "pindex=\"0\"")))))

(deftest distinct-colors-get-distinct-base-entries
  (testing "two distinct colors → two <base> entries with pindex 0 and 1"
    (let [xml (model-xml [(mk-mesh 0xff0000 :supporto)
                          (mk-mesh 0x0066ff :scritta)])]
      (is (str/includes? xml "displaycolor=\"#FF0000FF\""))
      (is (str/includes? xml "displaycolor=\"#0066FFFF\""))
      (is (str/includes? xml "name=\"supporto\""))
      (is (str/includes? xml "name=\"scritta\""))
      (is (str/includes? xml "pindex=\"0\""))
      (is (str/includes? xml "pindex=\"1\""))
      ;; basematerials block contains exactly two <base>
      (let [bm-block (-> xml
                         (str/split #"<basematerials")
                         second
                         (str/split #"</basematerials")
                         first)]
        (is (= 2 (count (re-seq #"<base " bm-block))))))))

(deftest same-color-deduped
  (testing "two meshes with the same color share one <base> and same pindex"
    (let [xml (model-xml [(mk-mesh 0xff0000 :a)
                          (mk-mesh 0xff0000 :b)])
          bm-block (-> xml
                       (str/split #"<basematerials")
                       second
                       (str/split #"</basematerials")
                       first)]
      (is (= 1 (count (re-seq #"<base " bm-block))))
      ;; both objects point at pindex=0
      (is (= 2 (count (re-seq #"pindex=\"0\"" xml)))))))

(deftest mixed-colored-and-uncolored
  (testing "uncolored meshes stay without pid; colored ones get pid/pindex"
    (let [xml (model-xml [(mk-mesh 0xff0000 :tinted)
                          (mk-mesh nil      :plain)])]
      (is (str/includes? xml "<basematerials id=\"100\">"))
      ;; First object has pid, second does not
      (let [obj1 (-> xml (str/split #"<object id=\"1\"") second
                     (str/split #">") first)
            obj2 (-> xml (str/split #"<object id=\"2\"") second
                     (str/split #">") first)]
        (is (str/includes? obj1 "pid=\"100\""))
        (is (str/includes? obj1 "pindex=\"0\""))
        (is (not (str/includes? obj2 "pid=")))
        (is (not (str/includes? obj2 "pindex=")))))))

(deftest fallback-color-name-when-mesh-anonymous
  (testing "if no colored mesh has :export-name, base gets a synthetic name"
    (let [xml (model-xml [(mk-mesh 0x123456)])]
      (is (str/includes? xml "name=\"color-1\""))
      ;; <object> itself has no name attribute (mesh is anonymous)
      (is (not (re-find #"<object id=\"1\"[^>]*name=" xml))))))

(deftest header-and-build-section-unchanged
  (testing "the wrapping XML structure (model, build) is preserved"
    (let [xml (model-xml [(mk-mesh)])]
      (is (str/includes? xml "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
      (is (str/includes? xml "<model unit=\"millimeter\""))
      (is (str/includes? xml "<build>"))
      (is (str/includes? xml "<item objectid=\"1\"/>"))
      (is (str/includes? xml "</model>")))))

;; ── Bambu/Orca model_settings.config ────────────────────────
;; <basematerials> alone is ignored by BambuStudio 2.5+ and current OrcaSlicer.
;; Both honor a Metadata/model_settings.config that assigns extruder per object.

(deftest no-color-no-config
  (testing "no colored meshes → no model_settings.config emitted"
    (is (nil? (settings-config [(mk-mesh) (mk-mesh)])))))

(deftest single-color-emits-config-with-extruder-1
  (testing "one colored mesh → config with object id=1, extruder=1"
    (let [cfg (settings-config [(mk-mesh 0xff0000 :supporto)])]
      (is (str/includes? cfg "<config>"))
      (is (str/includes? cfg "<object id=\"1\">"))
      (is (str/includes? cfg "<metadata key=\"name\" value=\"supporto\"/>"))
      (is (str/includes? cfg "<metadata key=\"extruder\" value=\"1\"/>")))))

(deftest distinct-colors-get-distinct-extruder-indices
  (testing "two distinct colors → extruder=1 and extruder=2 (1-based)"
    (let [cfg (settings-config [(mk-mesh 0xff0000 :a)
                                (mk-mesh 0x0066ff :b)])]
      (is (str/includes? cfg "<object id=\"1\">"))
      (is (str/includes? cfg "<object id=\"2\">"))
      (is (re-find #"object id=\"1\">[\s\S]*?extruder\" value=\"1\"" cfg))
      (is (re-find #"object id=\"2\">[\s\S]*?extruder\" value=\"2\"" cfg)))))

(deftest same-color-shares-extruder
  (testing "same color → same extruder slot (matches basematerials dedup)"
    (let [cfg (settings-config [(mk-mesh 0xff0000 :a)
                                (mk-mesh 0xff0000 :b)])]
      (is (re-find #"object id=\"1\">[\s\S]*?extruder\" value=\"1\"" cfg))
      (is (re-find #"object id=\"2\">[\s\S]*?extruder\" value=\"1\"" cfg)))))

(deftest mixed-colored-uncolored-config-skips-uncolored
  (testing "uncolored objects are absent from config (printer uses default slot)"
    (let [cfg (settings-config [(mk-mesh 0xff0000 :tinted)
                                (mk-mesh nil      :plain)])]
      (is (str/includes? cfg "<object id=\"1\">"))
      (is (not (str/includes? cfg "<object id=\"2\">"))))))

(deftest content-types-includes-config-extension
  (testing "[Content_Types].xml declares the .config extension"
    (let [ct @#'t/content-types-xml]
      (is (str/includes? ct "Extension=\"config\"")))))
