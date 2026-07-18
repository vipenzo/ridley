(ns ridley.library.obj-test
  "Tests for Wavefront OBJ parsing (scanner channel, Fase 1)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [ridley.library.obj :as obj]
            [ridley.library.mesh-import :as mesh-import]))

(deftest parses-a-triangle
  (testing "the minimal OBJ"
    (let [{:keys [vertices faces]}
          (obj/parse-obj "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n")]
      (is (= [[0 0 0] [1 0 0] [0 1 0]] vertices))
      (is (= [[0 1 2]] faces)))))

(deftest triangulates-polygons
  (testing "a quad becomes two triangles by fan"
    (let [{:keys [faces]}
          (obj/parse-obj "v 0 0 0\nv 1 0 0\nv 1 1 0\nv 0 1 0\nf 1 2 3 4\n")]
      (is (= [[0 1 2] [0 2 3]] faces))))
  (testing "a pentagon becomes three triangles"
    (let [{:keys [faces]}
          (obj/parse-obj (str "v 0 0 0\nv 1 0 0\nv 2 1 0\nv 1 2 0\nv 0 2 0\n"
                              "f 1 2 3 4 5\n"))]
      (is (= 3 (count faces)))
      (is (= [[0 1 2] [0 2 3] [0 3 4]] faces)))))

(deftest handles-index-forms
  (let [verts "v 0 0 0\nv 1 0 0\nv 0 1 0\n"]
    (testing "v/vt"
      (is (= [[0 1 2]] (:faces (obj/parse-obj (str verts "f 1/1 2/2 3/3\n"))))))
    (testing "v//vn"
      (is (= [[0 1 2]] (:faces (obj/parse-obj (str verts "f 1//1 2//2 3//3\n"))))))
    (testing "v/vt/vn"
      (is (= [[0 1 2]] (:faces (obj/parse-obj (str verts "f 1/1/1 2/2/2 3/3/3\n"))))))
    (testing "negative (relative) indices"
      (is (= [[0 1 2]] (:faces (obj/parse-obj (str verts "f -3 -2 -1\n"))))))))

(deftest ignores-non-geometry
  (testing "materials, normals, texcoords, groups and comments are skipped"
    ;; This is the brief's requirement that an OBJ whose .mtl is missing
    ;; still imports: materials are never consulted at all.
    (let [{:keys [vertices faces]}
          (obj/parse-obj (str "# exported by a scanner\n"
                              "mtllib model.mtl\n"
                              "o Object001\n"
                              "g group1\n"
                              "s off\n"
                              "usemtl material0\n"
                              "vt 0.5 0.5\n"
                              "vn 0 0 1\n"
                              "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
                              "f 1 2 3\n"))]
      (is (= 3 (count vertices)))
      (is (= [[0 1 2]] faces))))
  (testing "a vertex line is not confused with vt/vn"
    ;; 'vt' and 'vn' both start with 'v'; only 'v' followed by whitespace is
    ;; a vertex. A prefix check that forgot this would read texture
    ;; coordinates as geometry.
    (let [{:keys [vertices]}
          (obj/parse-obj "vt 9 9\nvn 9 9 9\nv 1 2 3\nf 1 1 1\n")]
      (is (= [[1 2 3]] vertices)))))

(deftest handles-whitespace-and-line-endings
  (testing "CRLF and repeated spaces"
    (let [{:keys [vertices faces]}
          (obj/parse-obj "v  0  0  0\r\nv 1 0 0\r\nv 0 1 0\r\nf  1  2  3\r\n")]
      (is (= [[0 0 0] [1 0 0] [0 1 0]] vertices))
      (is (= [[0 1 2]] faces))))
  (testing "trailing blank lines"
    (is (= [[0 1 2]]
           (:faces (obj/parse-obj "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n\n\n"))))))

(deftest handles-decimals-and-negatives
  (testing "float coordinates including negatives and exponents"
    (let [{:keys [vertices]}
          (obj/parse-obj "v -1.5 2.25e1 0.001\nv 1 0 0\nv 0 1 0\nf 1 2 3\n")]
      (is (= [-1.5 22.5 0.001] (first vertices))))))

(deftest rejects-non-obj
  (testing "a file with no vertices gives a readable error"
    (is (thrown-with-msg? js/Error #"no vertices found"
                          (obj/parse-obj "this is not an obj file\n")))))

(deftest import-mesh-dispatch
  (testing "an unsupported extension is reported readably"
    ;; Dispatch happens before any file read, so this needs no desktop server.
    (is (thrown-with-msg? js/Error #"unsupported file type"
                          (mesh-import/import-mesh "/tmp/model.3mf"))))
  (testing "the error names the supported formats"
    (is (thrown-with-msg? js/Error #"\.stl, \.obj"
                          (mesh-import/import-mesh "/tmp/model.ply")))))

(deftest multiple-groups-share-the-vertex-list
  (testing "faces after a group change still index the global vertex list"
    ;; Scans routinely carry several o/g sections; indices are global, not
    ;; per-group. Getting this wrong silently scrambles a scan.
    (let [{:keys [vertices faces]}
          (obj/parse-obj (str "v 0 0 0\nv 1 0 0\nv 0 1 0\n"
                              "g a\nf 1 2 3\n"
                              "v 2 0 0\n"
                              "g b\nf 2 4 3\n"))]
      (is (= 4 (count vertices)))
      (is (= [[0 1 2] [1 3 2]] faces)))))
