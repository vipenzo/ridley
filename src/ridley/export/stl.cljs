(ns ridley.export.stl
  "Binary STL export for Ridley meshes.

   STL binary format:
   - 80 bytes header (arbitrary text)
   - 4 bytes: number of triangles (uint32 little-endian)
   - For each triangle:
     - 12 bytes: normal vector (3x float32 LE)
     - 36 bytes: 3 vertices (3x 3x float32 LE)
     - 2 bytes: attribute byte count (usually 0)

   Also exposes `download-mesh` — a format-aware downloader that lets the
   user pick the destination file name and STL/3MF format via the native
   file picker (or falls back to a download link)."
  (:require [clojure.string :as str]
            [ridley.env :as env]
            [ridley.export.threemf :as threemf]
            [ridley.manifold.core :as manifold]))

(defn- compute-normal
  "Compute normal for a triangle from three vertices."
  [[x0 y0 z0] [x1 y1 z1] [x2 y2 z2]]
  (let [;; Edge vectors
        ux (- x1 x0) uy (- y1 y0) uz (- z1 z0)
        vx (- x2 x0) vy (- y2 y0) vz (- z2 z0)
        ;; Cross product
        nx (- (* uy vz) (* uz vy))
        ny (- (* uz vx) (* ux vz))
        nz (- (* ux vy) (* uy vx))
        ;; Normalize
        len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))]
    (if (> len 0)
      [(/ nx len) (/ ny len) (/ nz len)]
      [0 0 1])))

(defn- write-float32-le
  "Write a float32 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setFloat32 data-view offset value true))

(defn- write-uint32-le
  "Write a uint32 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setUint32 data-view offset value true))

(defn- write-uint16-le
  "Write a uint16 to DataView at offset in little-endian."
  [^js data-view offset value]
  (.setUint16 data-view offset value true))

(defn- write-stl-header!
  "Write the 80-byte STL header and triangle count to a DataView."
  [^js view num-triangles]
  (let [header "Ridley STL Export"
        header-bytes (.from js/Uint8Array (map #(.charCodeAt % 0) header))]
    (doseq [i (range (min 80 (.-length header-bytes)))]
      (.setUint8 view i (aget header-bytes i)))
    (write-uint32-le view 80 num-triangles)))

(defn- mesh->stl-binary-raw
  "Fast path: write STL directly from typed arrays (no CLJS vector access)."
  [^js vert-props ^js tri-verts num-triangles]
  (let [buffer-size (+ 80 4 (* num-triangles 50))
        buffer (js/ArrayBuffer. buffer-size)
        view (js/DataView. buffer)]
    (write-stl-header! view num-triangles)
    (loop [face-idx 0, offset 84]
      (when (< face-idx num-triangles)
        (let [fi (* face-idx 3)
              i0 (aget tri-verts fi)
              i1 (aget tri-verts (+ fi 1))
              i2 (aget tri-verts (+ fi 2))
              vi0 (* i0 3) vi1 (* i1 3) vi2 (* i2 3)
              x0 (aget vert-props vi0) y0 (aget vert-props (+ vi0 1)) z0 (aget vert-props (+ vi0 2))
              x1 (aget vert-props vi1) y1 (aget vert-props (+ vi1 1)) z1 (aget vert-props (+ vi1 2))
              x2 (aget vert-props vi2) y2 (aget vert-props (+ vi2 1)) z2 (aget vert-props (+ vi2 2))
              ;; Normal: cross product of edges
              ux (- x1 x0) uy (- y1 y0) uz (- z1 z0)
              vx (- x2 x0) vy (- y2 y0) vz (- z2 z0)
              nx (- (* uy vz) (* uz vy))
              ny (- (* uz vx) (* ux vz))
              nz (- (* ux vy) (* uy vx))
              len (js/Math.sqrt (+ (* nx nx) (* ny ny) (* nz nz)))
              nx (if (> len 0) (/ nx len) 0)
              ny (if (> len 0) (/ ny len) 0)
              nz (if (> len 0) (/ nz len) 1)]
          (write-float32-le view offset nx)
          (write-float32-le view (+ offset 4) ny)
          (write-float32-le view (+ offset 8) nz)
          (write-float32-le view (+ offset 12) x0)
          (write-float32-le view (+ offset 16) y0)
          (write-float32-le view (+ offset 20) z0)
          (write-float32-le view (+ offset 24) x1)
          (write-float32-le view (+ offset 28) y1)
          (write-float32-le view (+ offset 32) z1)
          (write-float32-le view (+ offset 36) x2)
          (write-float32-le view (+ offset 40) y2)
          (write-float32-le view (+ offset 44) z2)
          (write-uint16-le view (+ offset 48) 0)
          (recur (inc face-idx) (+ offset 50)))))
    buffer))

(defn mesh->stl-binary
  "Convert a Ridley mesh to STL binary format.
   Returns an ArrayBuffer containing the STL data.
   Fast path when ::manifold/raw-arrays available (skips CLJS vector access)."
  [mesh]
  (if-let [{:keys [vert-props tri-verts num-prop]} (::manifold/raw-arrays mesh)]
    (when (= num-prop 3)
      (mesh->stl-binary-raw vert-props tri-verts (/ (.-length tri-verts) 3)))
    ;; Slow path: CLJS vectors
    (let [{:keys [vertices faces]} mesh
          num-triangles (count faces)
          buffer-size (+ 80 4 (* num-triangles 50))
          buffer (js/ArrayBuffer. buffer-size)
          view (js/DataView. buffer)]
      (write-stl-header! view num-triangles)
      (loop [face-idx 0
             offset 84]
        (when (< face-idx num-triangles)
          (let [[i0 i1 i2] (nth faces face-idx)
                v0 (nth vertices i0)
                v1 (nth vertices i1)
                v2 (nth vertices i2)
                [nx ny nz] (compute-normal v0 v1 v2)
                [x0 y0 z0] v0
                [x1 y1 z1] v1
                [x2 y2 z2] v2]
            (write-float32-le view offset nx)
            (write-float32-le view (+ offset 4) ny)
            (write-float32-le view (+ offset 8) nz)
            (write-float32-le view (+ offset 12) x0)
            (write-float32-le view (+ offset 16) y0)
            (write-float32-le view (+ offset 20) z0)
            (write-float32-le view (+ offset 24) x1)
            (write-float32-le view (+ offset 28) y1)
            (write-float32-le view (+ offset 32) z1)
            (write-float32-le view (+ offset 36) x2)
            (write-float32-le view (+ offset 40) y2)
            (write-float32-le view (+ offset 44) z2)
            (write-uint16-le view (+ offset 48) 0)
            (recur (inc face-idx) (+ offset 50)))))
      buffer)))

(defn meshes->stl-binary
  "Convert multiple meshes to a single STL binary.
   Combines all meshes into one STL file.
   For single meshes with raw arrays, delegates to fast path directly."
  [meshes]
  (if (= 1 (count meshes))
    (mesh->stl-binary (first meshes))
    ;; Multiple meshes: merge into one then export
    (let [merged (reduce
                  (fn [{:keys [vertices faces vertex-offset]} mesh]
                    (let [mesh-verts (:vertices mesh)
                          mesh-faces (:faces mesh)
                          offset-faces (mapv (fn [[i0 i1 i2]]
                                               [(+ i0 vertex-offset)
                                                (+ i1 vertex-offset)
                                                (+ i2 vertex-offset)])
                                             mesh-faces)]
                      {:vertices (into vertices mesh-verts)
                       :faces (into faces offset-faces)
                       :vertex-offset (+ vertex-offset (count mesh-verts))}))
                  {:vertices [] :faces [] :vertex-offset 0}
                  meshes)]
      (mesh->stl-binary {:vertices (:vertices merged)
                         :faces (:faces merged)}))))

(def ^:private revoke-delay-ms
  "How long an object URL is kept alive after the click that downloads it.

   NOT zero, which is what revoking on the same tick amounts to: the click only
   ASKS for a download, and on a multi-megabyte export the browser is still
   opening the stream when the next statement runs. Sixty seconds costs one blob
   of memory and removes a race whose failure mode is a truncated file."
  60000)

(defonce ^:private async-notify
  ;; Where an ASYNC failure goes to be seen. Everything in this namespace that
  ;; saves returns a Promise, and a Promise the caller drops — which SCI callers
  ;; do, deliberately: the print buffer is read at end-of-eval, so an async
  ;; println reappears inside the NEXT evaluation — takes its rejections to the
  ;; console, where no user has ever looked. Measured cost (2026-08-24): the
  ;; desktop app wrote three 3MFs into nowhere and said nothing. core.cljs
  ;; registers its error panel here at startup; until then, console.error.
  (atom (fn [msg] (js/console.error msg))))

(defn set-async-notify!
  "Register the function async save failures are shown through."
  [f] (reset! async-notify f))

(defn- notify-async-failure!
  "Attach the last-resort error surface to a save Promise: on rejection, SAY SO
   where the user is looking. Returns the promise (with the catch attached) so
   callers that do consume it still can."
  [p what]
  (.catch p (fn [err]
              (let [msg (str what " NON riuscito: "
                             (or (some-> err .-message) (str err)))]
                (@async-notify msg)
                msg))))

(defn- download-blob-fallback
  "Download a blob using the traditional createElement('a') method.

   The anchor is put IN the document before the click and taken out after, and
   the object URL is revoked on a timer rather than on the spot. Both look like
   ceremony and neither is: a detached anchor is the form browsers are least
   consistent about honouring `download` on, and when they decline, the download
   still succeeds — it just arrives named after the blob URL's UUID, with no
   extension, which is how it was found (Vincenzo, 2026-08-18: a 388kB 3MF
   downloaded correctly as `be831e7a-9785-…`, unopenable because nothing could
   tell what it was). The failure is invisible from here: the file is whole, the
   status line says it was saved, and only the Downloads folder disagrees."
  [blob filename]
  (let [url (js/URL.createObjectURL blob)
        link (js/document.createElement "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (set! (.. link -style -display) "none")
    (.appendChild js/document.body link)
    (.click link)
    (.removeChild js/document.body link)
    (js/setTimeout (fn [] (js/URL.revokeObjectURL url)) revoke-delay-ms)))

(defn- save-in-browser!
  "Put a blob on the user's disk from the web build, and SAY where it went.

   `build-blob` is a thunk returning Promise<Blob>, deliberately not a blob:
   `showSaveFilePicker` needs the click that started all this to still be the
   browser's current user activation, so the dialog has to open BEFORE the
   seconds of CSG and zipping, not after.

   Why a dialog at all, when `save-*-at` was written as the no-picker path: on
   the desktop the destination really is part of what the user wrote, and honouring
   it silently is right. In a browser there is no filesystem to honour it with,
   and the silent alternative failed in the only way that matters — the download
   arrived named after the blob's UUID, unopenable, and then (Vincenzo,
   2026-08-18) did not arrive in the Downloads folder at all, with Chrome
   declining to ask even with 'ask where to save' switched on. A file that the
   status line calls saved and that nobody can find is worse than a dialog.

   The download is kept as the fallback for browsers without the picker, and for
   a picker that refuses (no user activation left)."
  [build-blob filename]
  (let [downloaded (fn []
                     (-> (build-blob)
                         (.then (fn [blob]
                                  (download-blob-fallback blob filename)
                                  (str "Nel browser non c'è un filesystem: " filename
                                       " è stato SCARICATO nella cartella dei download "
                                       "(la cartella che hai scritto è stata ignorata)."))))) ]
    (if (exists? js/window.showSaveFilePicker)
      (-> (js/window.showSaveFilePicker #js {:suggestedName filename})
          (.then (fn [handle]
                   (-> (build-blob)
                       (.then (fn [blob]
                                (-> (.createWritable handle)
                                    (.then (fn [w]
                                             (-> (.write w blob)
                                                 (.then (fn [_] (.close w)))
                                                 (.then (fn [_] (str "Salvato come "
                                                                     (.-name handle)))))))))))))
          (.catch (fn [err]
                    (if (and err (= "AbortError" (.-name err)))
                      (js/Promise.resolve "Salvataggio annullato.")
                      (do (js/console.warn "save picker unavailable:" err)
                          (downloaded))))))
      (downloaded))))

(defn- normalize-meshes
  "Coerce a mesh or seq of meshes into a vector of meshes."
  [mesh-or-meshes]
  (cond
    (and (map? mesh-or-meshes) (:vertices mesh-or-meshes))
    [mesh-or-meshes]

    (and (sequential? mesh-or-meshes) (seq mesh-or-meshes))
    (vec mesh-or-meshes)

    :else
    [mesh-or-meshes]))

(defn- ext->fmt [filename]
  (let [lower (.toLowerCase (str filename))]
    (cond
      (.endsWith lower ".3mf") :3mf
      (.endsWith lower ".stl") :stl
      :else nil)))

(defn- swap-ext [filename ext]
  (let [dot (.lastIndexOf filename ".")]
    (if (pos? dot)
      (str (.substring filename 0 dot) "." (name ext))
      (str filename "." (name ext)))))

(defn- meshes->stl-blob [meshes]
  (let [buffer (meshes->stl-binary meshes)]
    (js/Blob. #js [buffer] #js {:type "application/octet-stream"})))

(def service-unreachable
  "Tag carried by the error a desktop file call rejects with when NOBODY answered
   — the local geo-server is not running. Untagged it arrives as an empty answer,
   and an empty answer is indistinguishable from an empty folder: on 2026-08-23 a
   folder of photos opened as an empty session because of exactly that."
  "FILE-SERVICE-DOWN")

(def ^:private geo-server-url "http://127.0.0.1:12321")

(defn desktop-pick-save-path
  "Open native save dialog via Rust geo_server. Returns Promise<string|nil>
   (path or nil if cancelled).

   Options:
     :title    dialog title (default \"Save\")
     :filters  vec of {:name str :extensions [str ...]}
               (default: STL and 3MF)"
  ([suggested-name] (desktop-pick-save-path suggested-name nil))
  ([suggested-name {:keys [title filters]}]
   (js/Promise.
    (fn [resolve reject]
      (let [xhr (js/XMLHttpRequest.)
            body (cond-> {:suggested_name suggested-name}
                   title   (assoc :title title)
                   filters (assoc :filters (mapv (fn [{:keys [name extensions]}]
                                                   {:name name :extensions (vec extensions)})
                                                 filters)))]
        (.open xhr "POST" (str geo-server-url "/pick-save-path") true)
        (.setRequestHeader xhr "Content-Type" "application/json")
        (set! (.-onload xhr)
              (fn [_]
                (if (= 200 (.-status xhr))
                  (let [resp (js/JSON.parse (.-responseText xhr))]
                    (if (nil? resp)
                      (resolve nil)
                      (resolve (.-path resp))))
                  (reject (js/Error. (.-responseText xhr))))))
        (set! (.-onerror xhr)
              (fn [_] (reject (js/Error. "pick-save-path request failed"))))
        (.send xhr (js/JSON.stringify (clj->js body))))))))

(defn desktop-pick-open-path
  "Open native open dialog via Rust geo_server. Returns Promise<string|nil>
   (path or nil if cancelled).

   Options:
     :title    dialog title (default \"Open\")
     :filters  vec of {:name str :extensions [str ...]}
               (default: .clj/.cljs/.edn)"
  ([] (desktop-pick-open-path nil))
  ([{:keys [title filters]}]
   (js/Promise.
    (fn [resolve reject]
      (let [xhr (js/XMLHttpRequest.)
            body (cond-> {}
                   title   (assoc :title title)
                   filters (assoc :filters (mapv (fn [{:keys [name extensions]}]
                                                   {:name name :extensions (vec extensions)})
                                                 filters)))]
        (.open xhr "POST" (str geo-server-url "/pick-open-path") true)
        (.setRequestHeader xhr "Content-Type" "application/json")
        (set! (.-onload xhr)
              (fn [_]
                (if (= 200 (.-status xhr))
                  (let [resp (js/JSON.parse (.-responseText xhr))]
                    (if (nil? resp) (resolve nil) (resolve (.-path resp))))
                  (reject (js/Error. (.-responseText xhr))))))
        (set! (.-onerror xhr)
              (fn [_] (reject (js/Error. "pick-open-path request failed"))))
        (.send xhr (js/JSON.stringify (clj->js body))))))))

(defn desktop-read-file
  "Read a text file from disk via Rust geo_server. Returns Promise<string>."
  [file-path]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/read-file") true)
       (.setRequestHeader xhr "X-File-Path" file-path)
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (resolve (.-responseText xhr))
                 (reject (js/Error. (.-responseText xhr))))))
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. "read-file request failed"))))
       (.send xhr "")))))

(defn desktop-read-file-blob
  "Read a binary file from disk via Rust geo_server. Returns Promise<Blob>.
   Used for images and other binary assets (see desktop-read-file for text)."
  [file-path]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/read-file") true)
       (.setRequestHeader xhr "X-File-Path" file-path)
       (set! (.-responseType xhr) "blob")
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (resolve (.-response xhr))
                 (reject (js/Error. (str "read-file failed: " (.-status xhr)))))))
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. "read-file request failed"))))
       (.send xhr "")))))

(defn desktop-write-file
  "Write a Blob (or string) to a file path via Rust geo_server. Returns Promise<nil>."
  [blob file-path]
  (-> (.arrayBuffer (if (string? blob) (js/Blob. #js [blob]) blob))
      (.then (fn [ab]
               (js/Promise.
                (fn [resolve reject]
                  (let [xhr (js/XMLHttpRequest.)]
                    (.open xhr "POST" (str geo-server-url "/write-file") true)
                    (.setRequestHeader xhr "Content-Type" "application/octet-stream")
                    (.setRequestHeader xhr "X-File-Path" file-path)
                    (set! (.-onload xhr)
                          (fn [_]
                            (if (= 200 (.-status xhr))
                              (resolve nil)
                              (reject (js/Error. (.-responseText xhr))))))
                    (set! (.-onerror xhr)
                          (fn [_] (reject (js/Error. "write-file request failed"))))
                    (.send xhr (js/Uint8Array. ab)))))))))

(defn desktop-delete-file
  "Delete the file at `file-path` via Rust geo_server. Returns Promise<nil>.
   Rejects when the file isn't there or can't be removed — the caller decides
   whether that matters (removing a view whose JPEG is already gone should still
   remove it from the session)."
  [file-path]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/delete-file") true)
       (.setRequestHeader xhr "X-File-Path" file-path)
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (resolve nil)
                 (reject (js/Error. (.-responseText xhr))))))
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. "delete-file request failed"))))
       (.send xhr "")))))

(defn desktop-list-dir
  "List the directory at `dir` via Rust geo_server. Returns
   Promise<#js [{name, is_dir, size}, …]>. NB: the Rust handler CREATES the
   directory if it doesn't exist, so a nonexistent path resolves to []."
  [dir]
  (js/Promise.
   (fn [resolve reject]
     (let [xhr (js/XMLHttpRequest.)]
       (.open xhr "POST" (str geo-server-url "/read-dir") true)
       (.setRequestHeader xhr "Content-Type" "application/json")
       (set! (.-onload xhr)
             (fn [_]
               (if (= 200 (.-status xhr))
                 (resolve (js/JSON.parse (.-responseText xhr)))
                 (reject (js/Error. (.-responseText xhr))))))
       ;; TRANSPORT failure — nobody answered at all — which is a different fact
       ;; from "the server answered with an error", and the caller must be able to
       ;; tell them apart: one means the service is not there, the other means the
       ;; request was bad. Tagged, because it crosses a promise boundary as a
       ;; plain Error.
       (set! (.-onerror xhr)
             (fn [_] (reject (js/Error. (str service-unreachable
                                             ": read-dir got no answer from "
                                             geo-server-url)))))
       (.send xhr (js/JSON.stringify #js {:path dir}))))))

(defn- pick-and-write
  "Open native showSaveFilePicker offering both STL and 3MF, build the blob
   based on the picked filename, and write it. Falls back to anchor download.
   Returns a Promise<string> describing the result, or a string when no
   picker is available."
  [meshes suggested-name preferred-fmt]
  (let [;; Always offer both formats; default the suggested name to the
        ;; preferred extension so the picker preselects the right type.
        suggested (swap-ext suggested-name preferred-fmt)
        types #js [#js {:description "3D models"
                        :accept #js {"application/octet-stream" #js [".stl" ".3mf"]
                                     "model/3mf" #js [".3mf"]}}]
        build-blob (fn [filename]
                     (case (or (ext->fmt filename) preferred-fmt :stl)
                       :3mf (threemf/meshes->3mf-blob meshes)
                       (js/Promise.resolve (meshes->stl-blob meshes))))]
    (cond
      ;; Desktop mode: pick path first, then build correct format, then write
      (env/desktop?)
      (-> (desktop-pick-save-path suggested)
          (.then (fn [chosen-path]
                   (if chosen-path
                     (-> (build-blob chosen-path)
                         (.then (fn [blob]
                                  (-> (desktop-write-file blob chosen-path)
                                      (.then (fn [_]
                                               (str "Exported " (count meshes)
                                                    " mesh(es) to " chosen-path)))))))
                     ;; no path chosen. Say so — the same silence that made the
                     ;; web branch print a Promise and leave nothing behind.
                     "Esportazione annullata: nessun percorso scelto.")))
          (.catch (fn [err]
                    (js/console.warn "native save error:" err)
                    (str "Esportazione FALLITA: il salvataggio nativo ha risposto «"
                         (or (some-> err .-message) err)
                         "». Nessun file scritto."))))

      ;; Chrome/Edge: File System Access API
      (exists? js/window.showSaveFilePicker)
      (-> (js/window.showSaveFilePicker
           #js {:suggestedName suggested
                :types types})
          (.then (fn [handle]
                   (let [filename (.-name handle)]
                     (-> (build-blob filename)
                         (.then (fn [blob]
                                  (-> (.createWritable handle)
                                      (.then (fn [writable]
                                               (-> (.write writable blob)
                                                   (.then #(.close writable))
                                                   (.then (fn [_]
                                                            (str "Exported "
                                                                 (count meshes)
                                                                 " mesh(es) to "
                                                                 filename)))))))))))))
          (.catch (fn [err]
                    (if (and err (= "AbortError" (.-name err)))
                      ;; the user closed the dialog: a decision, not a failure
                      (js/Promise.resolve "Esportazione annullata.")
                      ;; ANYTHING else and the export must still produce a file.
                      ;; This used to return nil, and nil is how an export comes
                      ;; to print a Promise and leave nothing on disk (Vincenzo,
                      ;; 2026-08-18). The usual cause is not a broken picker but
                      ;; a spent one: showSaveFilePicker may only open while the
                      ;; click that started the evaluation is still the browser's
                      ;; current user activation, and a few seconds of CSG is
                      ;; enough to lose it. Falling back to the anchor download
                      ;; needs no activation at all, so the file lands either way
                      ;; — and the message says which happened rather than
                      ;; leaving the Downloads folder to be searched.
                      (do (js/console.warn "save picker unavailable:" err)
                          (-> (build-blob suggested)
                              (.then (fn [blob]
                                       (download-blob-fallback blob suggested)
                                       (str "Il browser non ha aperto la finestra "
                                            "di salvataggio: " suggested
                                            " è stato SCARICATO nella cartella dei "
                                            "download.")))))))))

      ;; Fallback: anchor download with the suggested filename and preferred fmt
      :else
      (let [filename suggested]
        (-> (build-blob filename)
            (.then (fn [blob]
                     (download-blob-fallback blob filename)
                     (str "Exported " (count meshes) " mesh(es) to " filename))))))))

(defn download-mesh
  "Download mesh(es) in STL or 3MF format via the native save picker.
   - mesh-or-meshes: single mesh or vector of meshes
   - filename: suggested name (extension is normalized to the chosen format)
   - format: :stl (default) or :3mf — used as the suggested extension and
             as the writer when the user keeps the suggested name.
   The user can override the format by typing a different extension in the
   picker (.stl ↔ .3mf)."
  ([mesh-or-meshes]
   (download-mesh mesh-or-meshes "model.stl" :stl))
  ([mesh-or-meshes filename]
   (download-mesh mesh-or-meshes filename
                  (or (ext->fmt filename) :stl)))
  ([mesh-or-meshes filename format]
   (let [meshes (normalize-meshes mesh-or-meshes)]
     (pick-and-write meshes filename format))))

(defn download-stl
  "Download mesh(es) as an STL file (back-compat wrapper around download-mesh).
   mesh-or-meshes: single mesh or vector of meshes
   filename: name for the downloaded file (default 'model.stl')"
  ([mesh-or-meshes]
   (download-mesh mesh-or-meshes "model.stl" :stl))
  ([mesh-or-meshes filename]
   (download-mesh mesh-or-meshes filename :stl)))

(defn download-3mf
  "Download mesh(es) as a 3MF file."
  ([mesh-or-meshes]
   (download-mesh mesh-or-meshes "model.3mf" :3mf))
  ([mesh-or-meshes filename]
   (download-mesh mesh-or-meshes filename :3mf)))

(defn download-text
  "Write a text string (e.g. an SVG) to a file via the native save picker
   (desktop path / File System Access), falling back to an anchor download —
   the text analogue of pick-and-write, for the plate paper-variant sheet.
   Returns a Promise<string> describing the result (or nil on cancel)."
  ([text filename] (download-text text filename "text/plain"))
  ([text filename mime]
   (let [blob (js/Blob. #js [text] #js {:type mime})]
     (cond
       (env/desktop?)
       (-> (desktop-pick-save-path filename)
           (.then (fn [chosen]
                    (when chosen
                      (-> (desktop-write-file blob chosen)
                          (.then (fn [_] (str "Salvato in " chosen)))))))
           (.catch (fn [err] (js/console.warn "native save error:" err) nil)))

       (exists? js/window.showSaveFilePicker)
       (-> (js/window.showSaveFilePicker #js {:suggestedName filename})
           (.then (fn [handle]
                    (-> (.createWritable handle)
                        (.then (fn [w]
                                 (-> (.write w blob)
                                     (.then #(.close w))
                                     (.then (fn [_] (str "Salvato " (.-name handle))))))))))
           (.catch (fn [err]
                     (when-not (and err (= "AbortError" (.-name err)))
                       (js/console.warn "save picker error:" err))
                     nil)))

       :else
       (do (download-blob-fallback blob filename)
           (js/Promise.resolve (str "Scaricato " filename)))))))

(defn download-svg
  "Save an SVG string to a .svg file (native picker / download)."
  ([svg] (download-svg svg "plate.svg"))
  ([svg filename] (download-text svg (swap-ext filename :svg) "image/svg+xml")))

(defn expand-home
  "Turn a leading `~` into the user's home directory (desktop only). Anyone
   writing a path by hand writes `~/Downloads`, and a `~` that reaches the
   filesystem verbatim creates a directory literally called `~` next to wherever
   the app happened to be — a failure that looks like success."
  [path]
  (if (and (string? path) (str/starts-with? path "~"))
    (let [xhr (js/XMLHttpRequest.)]
      (try
        (.open xhr "POST" (str geo-server-url "/home-dir") false)
        (.send xhr "")
        (if (= 200 (.-status xhr))
          (let [home (.-path (js/JSON.parse (.-responseText xhr)))]
            (str home (subs path 1)))
          path)
        (catch :default _ path)))
    path))

(defn save-text-at
  "Write `text` to `path` — no picker. The picker is the right thing when a human
   is choosing a destination; it is the wrong thing when the destination is part
   of what the user WROTE, because then the dialog asks a question already
   answered. Returns Promise<string> describing what happened.

   On the web there is no filesystem: the file is downloaded instead and the
   directory in `path` is dropped, which the message says out loud rather than
   pretending the path was honoured."
  [text path]
  (let [full (expand-home path)
        filename (last (str/split full #"/"))]
    (if (env/desktop?)
      (-> (desktop-write-file (js/Blob. #js [text]) full)
          (.then (fn [_] (str "Salvato in " full))))
      (save-in-browser! (fn [] (js/Promise.resolve
                                (js/Blob. #js [text] #js {:type "image/svg+xml"})))
                        filename))))

(defn save-3mf-set-at
  "Write SEVERAL 3MFs into the directory `dir` — `named` is a seq of
   [filename meshes] pairs. Returns Promise<string>.

   One call, one destination question. Saving N files by calling the single-file
   writer N times looks equivalent and is not: in a browser each save opens a
   dialog, and a dialog may only be opened while the click that started the
   evaluation is still the current user activation — which the FIRST dialog
   consumes. Files two and three would silently fall back to blind downloads,
   which is the failure this whole path exists to remove. So the browser is asked
   for the FOLDER once, and the files are written into it.

   Why several files at all, when one 3MF can hold everything: a ring and its
   discs are separate objects, and a slicer lets you drag one without the other.
   Six loose objects on a plate is six chances to move a ring off its own marks
   (Vincenzo, 2026-08-18) — and a ring whose discs stayed behind still slices,
   still prints, and is scrap."
  [named dir]
  (let [named (vec named)
        build (fn [meshes] (threemf/meshes->3mf-blob meshes))
        summary (fn [where] (str (count named) " file salvati in " where))]
    (if (env/desktop?)
      (let [full (expand-home dir)]
        (-> (js/Promise.all
             (into-array
              (for [[filename meshes] named]
                (-> (build meshes)
                    (.then (fn [blob] (desktop-write-file blob (str full "/" filename))))))))
            (.then (fn [_] (summary full)))
            (notify-async-failure! (str "Salvataggio di " (count named) " file in " full))))
      (let [downloads (fn []
                        ;; SEQUENTIAL, half a second apart — not Promise.all. Three
                        ;; programmatic anchor clicks in the same tick are one
                        ;; download: the later clicks supersede the earlier ones
                        ;; before the browser commits them. Measured (Firefox,
                        ;; 2026-08-24): of gabbia-{big,medium,small} only small —
                        ;; the LAST — ever arrived, twice in a row.
                        (-> (reduce (fn [p [filename meshes]]
                                      (-> p
                                          (.then (fn [_] (build meshes)))
                                          (.then (fn [blob]
                                                   (download-blob-fallback blob filename)
                                                   (js/Promise.
                                                    (fn [res _] (js/setTimeout res 600)))))))
                                    (js/Promise.resolve nil)
                                    named)
                            (.then (fn [_]
                                     (str "Nel browser non c'è un filesystem: i "
                                          (count named) " file sono stati SCARICATI "
                                          "nella cartella dei download.")))))]
        (if (exists? js/window.showDirectoryPicker)
          (-> (js/window.showDirectoryPicker #js {:mode "readwrite"})
              (.then (fn [dir-handle]
                       (-> (js/Promise.all
                            (into-array
                             (for [[filename meshes] named]
                               (-> (build meshes)
                                   (.then (fn [blob]
                                            (-> (.getFileHandle dir-handle filename
                                                                #js {:create true})
                                                (.then (fn [fh] (.createWritable fh)))
                                                (.then (fn [w]
                                                         (-> (.write w blob)
                                                             (.then (fn [_] (.close w))))))))))))) 
                           (.then (fn [_] (summary (.-name dir-handle)))))))
              (.catch (fn [err]
                        (if (and err (= "AbortError" (.-name err)))
                          (js/Promise.resolve "Salvataggio annullato.")
                          (do (js/console.warn "directory picker unavailable:" err)
                              (downloads)))))
              (notify-async-failure! (str "Salvataggio di " (count named) " file")))
          (downloads))))))

(defn save-3mf-at
  "Write mesh(es) as a 3MF to `path` — no picker, same reasoning as save-text-at.
   Returns Promise<string>."
  [mesh-or-meshes path]
  (let [meshes (if (map? mesh-or-meshes) [mesh-or-meshes] (vec mesh-or-meshes))
        ;; meshes->3mf-blob is a PROMISE (the zip is generated asynchronously),
        ;; and on the web it must NOT be started before the destination dialog —
        ;; see save-in-browser!
        build (fn [] (threemf/meshes->3mf-blob meshes))]
    (if (env/desktop?)
      (let [full (expand-home path)]
        (-> (build)
            (.then (fn [blob]
                     (-> (desktop-write-file blob full)
                         (.then (fn [_] (str "Salvato in " full))))))))
      (save-in-browser! build (last (str/split path #"/"))))))
