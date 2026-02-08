(ns ridley.ai.batch
  "AI batch testing — run multiple prompts, capture screenshots, export ZIP."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [ridley.ai.core :as ai]
            [ridley.viewport.core :as viewport]
            [ridley.scene.registry :as registry]
            [ridley.editor.repl :as repl]
            [ridley.settings :as settings]
            ["jszip" :as JSZip]))

;; ============================================================
;; Utilities
;; ============================================================

(defn- zero-pad [n width]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- width (count s))) "0")) s)))

(defn- wait-frames
  "Wait for n animation frames. Returns a Promise."
  [n]
  (if (<= n 0)
    (js/Promise.resolve nil)
    (js/Promise.
      (fn [resolve _]
        (js/requestAnimationFrame
          (fn [_] (.then (wait-frames (dec n)) resolve)))))))

(defn- timestamp-str []
  (let [d (js/Date.)]
    (str (.getFullYear d) "-"
         (zero-pad (inc (.getMonth d)) 2) "-"
         (zero-pad (.getDate d) 2) "-"
         (zero-pad (.getHours d) 2)
         (zero-pad (.getMinutes d) 2))))

(defn- delay-ms
  "Return a Promise that resolves after ms milliseconds."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))

;; ============================================================
;; Parsing
;; ============================================================

(defn parse-test-suite
  "Parse an EDN string into a test suite. Returns map with :name and :tests."
  [edn-str]
  (let [data (edn/read-string edn-str)]
    (when-not (and (map? data) (sequential? (:tests data)))
      (throw (js/Error. "Invalid test suite: must be a map with :tests vector")))
    (when-not (every? #(and (:id %) (:prompt %)) (:tests data))
      (throw (js/Error. "Each test must have :id and :prompt keys")))
    data))

(defn parse-inline-prompts
  "Parse pipe-separated inline prompts into test definitions.
   'a cube | a sphere' => [{:id \"inline-1\" :prompt \"a cube\"} ...]"
  [input]
  (let [prompts (mapv str/trim (str/split input #"\|"))]
    (vec (map-indexed
           (fn [i p] {:id (str "inline-" (inc i))
                      :prompt p})
           (filter seq prompts)))))

;; ============================================================
;; Single test execution
;; ============================================================

(defn- evaluate-script
  "Evaluate a script string through repl/evaluate-definitions and update the scene.
   Returns the evaluation result or throws on error."
  [script]
  (let [result (repl/evaluate-definitions script)]
    (when-let [error (:error result)]
      (throw (js/Error. (str "Script eval error: " error))))
    (when-let [render-data (repl/extract-render-data result)]
      (registry/set-lines! (:lines render-data))
      (registry/set-definition-meshes! (:meshes render-data)))
    (registry/refresh-viewport! true)
    result))

(defn- run-single-test
  "Run a single test case. Returns a Promise resolving to a result map."
  [{:keys [id prompt script tier]} results-so-far on-progress]
  (let [start-time (js/Date.now)
        initial-script (or script "")]
    (when on-progress
      (on-progress {:index (count results-so-far)
                    :total nil
                    :id id
                    :status "running"}))
    (-> (js/Promise.resolve nil)
        ;; Step 1: Reset scene
        (.then (fn [_]
                 (registry/clear-all!)
                 (viewport/update-scene {:lines [] :meshes [] :panels []
                                         :reset-camera? false})))
        ;; Step 2: Load pre-existing script if provided
        (.then (fn [_]
                 (when (seq (str/trim initial-script))
                   (evaluate-script initial-script))))
        ;; Step 3: Call AI with tier override
        (.then (fn [_]
                 (ai/generate prompt
                              (cond-> {:script-content initial-script}
                                tier (assoc :tier-override tier)))))
        ;; Step 4: Process AI response — preserve code even on eval error
        (.then (fn [{:keys [type code question]}]
                 (case type
                   :code
                   (let [full-script (if (seq (str/trim initial-script))
                                       (str initial-script "\n;; AI: " prompt "\n" code "\n")
                                       (str ";; AI: " prompt "\n" code "\n"))]
                     (try
                       (registry/clear-all!)
                       (evaluate-script full-script)
                       {:code code :final-script full-script}
                       (catch :default e
                         {:code code :final-script full-script
                          :error (.-message e)})))

                   :clarification
                   {:clarification question :final-script initial-script}

                   ;; Unknown type
                   {:error "Unknown AI response type" :final-script initial-script})))
        ;; Step 5: Wait for render to settle
        (.then (fn [result]
                 (-> (wait-frames 3)
                     (.then (fn [_]
                              (viewport/fit-camera)
                              (wait-frames 2)))
                     (.then (fn [_] result)))))
        ;; Step 6: Capture screenshot
        (.then (fn [result]
                 (-> (viewport/capture-screenshot-blob)
                     (.then (fn [blob]
                              (merge result {:screenshot-blob blob})))
                     (.catch (fn [_]
                               (merge result {:screenshot-blob nil}))))))
        ;; Step 7: Finalize result
        (.then (fn [result]
                 (let [duration (- (js/Date.now) start-time)
                       meshes (registry/visible-meshes)
                       final {:id id
                              :prompt prompt
                              :tier (or tier (settings/get-effective-tier))
                              :initial-script (when (seq (str/trim initial-script))
                                                initial-script)
                              :code (:code result)
                              :clarification (:clarification result)
                              :error (:error result)
                              :final-script (:final-script result)
                              :screenshot-blob (:screenshot-blob result)
                              :duration-ms duration
                              :mesh-count (count meshes)
                              :vertex-count (reduce + 0 (map #(count (:vertices %)) meshes))
                              :model (settings/get-ai-model)
                              :provider (name (or (settings/get-ai-setting :provider) :unknown))}]
                   (when on-progress
                     (on-progress {:index (count results-so-far)
                                   :id id
                                   :status (if (:error final) "error" "done")
                                   :duration-ms duration}))
                   final)))
        ;; Error handler — still return a result
        (.catch (fn [err]
                  (let [duration (- (js/Date.now) start-time)
                        final {:id id
                               :prompt prompt
                               :tier (or tier (settings/get-effective-tier))
                               :error (.-message err)
                               :duration-ms duration
                               :model (settings/get-ai-model)
                               :provider (name (or (settings/get-ai-setting :provider) :unknown))}]
                    (when on-progress
                      (on-progress {:index (count results-so-far)
                                    :id id
                                    :status "error"
                                    :error (.-message err)}))
                    final))))))

;; ============================================================
;; Batch runner
;; ============================================================

(defn run-batch
  "Run a batch of tests sequentially. Returns a Promise resolving to results vector.
   on-progress receives {:index :total :id :status} per test.
   Resolves :after references from already-completed tests."
  [tests on-progress]
  (let [total (count tests)
        results-atom (atom [])
        results-by-id (atom {})
        progress-with-total (fn [m]
                              (when on-progress
                                (on-progress (assoc m :total total))))]
    (-> (reduce
          (fn [chain test-def]
            (.then chain
                   (fn [_]
                     ;; Resolve :after reference
                     (let [resolved-test
                           (if-let [after-id (:after test-def)]
                             (if-let [prev (get @results-by-id after-id)]
                               (assoc test-def :script (:final-script prev))
                               (assoc test-def :error (str ":after reference not found: " after-id)))
                             test-def)]
                       (if (:error resolved-test)
                         ;; Skip test if :after reference failed
                         (let [result {:id (:id resolved-test)
                                       :prompt (:prompt resolved-test)
                                       :tier (:tier resolved-test)
                                       :error (:error resolved-test)
                                       :duration-ms 0
                                       :model (settings/get-ai-model)
                                       :provider (name (or (settings/get-ai-setting :provider) :unknown))}]
                           (swap! results-atom conj result)
                           (swap! results-by-id assoc (:id result) result)
                           (js/Promise.resolve nil))
                         ;; Run test then wait 2s to avoid rate limits
                         (-> (run-single-test resolved-test @results-atom progress-with-total)
                             (.then (fn [result]
                                      (swap! results-atom conj result)
                                      (swap! results-by-id assoc (:id result) result)))
                             (.then (fn [_] (delay-ms 2000)))))))))
          (js/Promise.resolve nil)
          tests)
        (.then (fn [_] @results-atom)))))

;; ============================================================
;; ZIP export
;; ============================================================

(defn- build-summary-json [results suite-name]
  (let [success (count (filter :code results))
        errors (count (filter :error results))
        clarifications (count (filter :clarification results))]
    (js/JSON.stringify
      (clj->js
        {:suite_name suite-name
         :run_date (.toISOString (js/Date.))
         :model (settings/get-ai-model)
         :provider (name (or (settings/get-ai-setting :provider) :unknown))
         :total_tests (count results)
         :passed success
         :failed errors
         :clarifications clarifications
         :results (mapv (fn [{:keys [id tier prompt code error clarification
                                     duration-ms mesh-count vertex-count]}]
                          {:id id
                           :tier (when tier (name tier))
                           :prompt prompt
                           :status (cond error "error"
                                         clarification "clarification"
                                         :else "success")
                           :duration_ms duration-ms
                           :has_screenshot (boolean code)
                           :mesh_count (or mesh-count 0)
                           :vertex_count (or vertex-count 0)
                           :error error
                           :clarification clarification})
                        results)})
      nil 2)))

(defn- build-readme [results suite-name]
  (let [model (or (settings/get-ai-model) "unknown")]
    (str "# AI Batch Test Results\n\n"
         "**Suite**: " suite-name "\n"
         "**Date**: " (.toISOString (js/Date.)) "\n"
         "**Model**: " model "\n"
         "**Provider**: " (name (or (settings/get-ai-setting :provider) :unknown)) "\n\n"
         "## Results\n\n"
         "| # | Test | Tier | Status | Time |\n"
         "|---|------|------|--------|------|\n"
         (str/join "\n"
           (map-indexed
             (fn [i {:keys [id tier error clarification duration-ms]}]
               (let [status (cond error (str "error: " error)
                                  clarification "clarification"
                                  :else "success")
                     tier-str (if tier (name tier) "auto")
                     time-str (str (.toFixed (/ duration-ms 1000) 1) "s")]
                 (str "| " (inc i) " | " id " | " tier-str " | " status " | " time-str " |")))
             results))
         "\n")))

(defn- download-blob
  "Trigger a browser file download from a Blob."
  [blob filename]
  (let [url (js/URL.createObjectURL blob)
        link (.createElement js/document "a")]
    (set! (.-href link) url)
    (set! (.-download link) filename)
    (.appendChild (.-body js/document) link)
    (.click link)
    (.removeChild (.-body js/document) link)
    (js/URL.revokeObjectURL url)))

(defn export-zip
  "Package batch results into a ZIP file with screenshots and trigger download.
   Returns a Promise."
  [results suite-name]
  (let [zip (JSZip.)
        folder-name (str "batch-" (str/replace (or suite-name "test") #"[^a-zA-Z0-9_-]" "_")
                         "-" (timestamp-str))]
    ;; Add summary.json at root
    (.file zip (str folder-name "/summary.json")
           (build-summary-json results suite-name))
    ;; Add README.md at root
    (.file zip (str folder-name "/README.md")
           (build-readme results suite-name))
    ;; Add per-test folders
    (doseq [[i result] (map-indexed vector results)]
      (let [test-folder (str folder-name "/" (zero-pad (inc i) 2) "-" (:id result))]
        ;; prompt.txt
        (.file zip (str test-folder "/prompt.txt") (:prompt result))
        ;; initial-script.clj (if any)
        (when-let [s (:initial-script result)]
          (.file zip (str test-folder "/initial-script.clj") s))
        ;; generated-code.clj (if AI produced code)
        (when-let [code (:code result)]
          (.file zip (str test-folder "/generated-code.clj") code))
        ;; final-script.clj (complete script after AI addition)
        (when-let [fs (:final-script result)]
          (.file zip (str test-folder "/final-script.clj") fs))
        ;; screenshot.png (if captured)
        (when-let [blob (:screenshot-blob result)]
          (.file zip (str test-folder "/screenshot.png") blob))
        ;; error.txt (if failed)
        (when-let [err (:error result)]
          (.file zip (str test-folder "/error.txt") err))
        ;; clarification.txt (if AI asked for clarification)
        (when-let [q (:clarification result)]
          (.file zip (str test-folder "/clarification.txt") q))
        ;; metadata.json
        (.file zip (str test-folder "/metadata.json")
               (js/JSON.stringify
                 (clj->js {:id (:id result)
                            :tier (when (:tier result) (name (:tier result)))
                            :prompt (:prompt result)
                            :status (cond (:error result) "error"
                                          (:clarification result) "clarification"
                                          :else "success")
                            :duration_ms (:duration-ms result)
                            :mesh_count (:mesh-count result)
                            :vertex_count (:vertex-count result)
                            :model (:model result)
                            :provider (:provider result)})
                 nil 2))))
    ;; Generate ZIP and trigger download
    (-> (.generateAsync zip #js {:type "blob"})
        (.then (fn [blob]
                 (download-blob blob (str folder-name ".zip")))))))

;; ============================================================
;; File picker
;; ============================================================

(defn- pick-edn-file
  "Open a file picker for .edn files. Returns a Promise<string> with file text."
  []
  (js/Promise.
    (fn [resolve reject]
      (let [input (.createElement js/document "input")]
        (set! (.-type input) "file")
        (set! (.-accept input) ".edn")
        (.addEventListener input "change"
          (fn [e]
            (if-let [file (aget (.-files (.-target e)) 0)]
              (-> (.text file)
                  (.then resolve)
                  (.catch reject))
              (reject (js/Error. "No file selected")))))
        (.click input)))))

;; ============================================================
;; Public entry points (called from core.cljs)
;; ============================================================

(defn start-batch-from-file!
  "Open file picker, parse EDN test suite, run batch, export ZIP.
   on-entry: (fn [msg]) callback for REPL display messages."
  [on-entry]
  (-> (pick-edn-file)
      (.then (fn [edn-str]
               (let [suite (parse-test-suite edn-str)
                     tests (:tests suite)
                     suite-name (or (:name suite) "unnamed")]
                 (on-entry (str "Loaded " (count tests) " tests from '" suite-name "'"))
                 (-> (run-batch tests
                       (fn [{:keys [index total id status duration-ms error]}]
                         (on-entry
                           (case status
                             "running" (str "  [" (inc index) "/" total "] " id "...")
                             "done"    (str "  [" (inc index) "/" total "] " id
                                            " (" (.toFixed (/ duration-ms 1000) 1) "s)")
                             "error"   (str "  [" (inc index) "/" total "] " id
                                            " ERROR: " error)
                             (str "  " id ": " status)))))
                     (.then (fn [results]
                              (let [success (count (filter :code results))
                                    total (count results)]
                                (on-entry (str "Batch complete: " success "/" total
                                               " successful. Exporting ZIP..."))
                                (-> (export-zip results suite-name)
                                    (.then (fn [_]
                                             (on-entry "ZIP downloaded.")))))))))))
      (.catch (fn [err]
                (on-entry (str "Batch error: " (.-message err)))))))

(defn start-batch-inline!
  "Run pipe-separated inline prompts as a batch, export ZIP.
   on-entry: (fn [msg]) callback for REPL display messages."
  [prompts-str on-entry]
  (let [tests (parse-inline-prompts prompts-str)]
    (if (empty? tests)
      (on-entry "No prompts found. Use: /ai-batch-inline prompt1 | prompt2 | prompt3")
      (do
        (on-entry (str "Running " (count tests) " inline tests..."))
        (-> (run-batch tests
              (fn [{:keys [index total id status duration-ms error]}]
                (on-entry
                  (case status
                    "running" (str "  [" (inc index) "/" total "] " id "...")
                    "done"    (str "  [" (inc index) "/" total "] " id
                                   " (" (.toFixed (/ duration-ms 1000) 1) "s)")
                    "error"   (str "  [" (inc index) "/" total "] " id
                                   " ERROR: " error)
                    (str "  " id ": " status)))))
            (.then (fn [results]
                     (let [success (count (filter :code results))
                           total (count results)]
                       (on-entry (str "Batch complete: " success "/" total
                                      " successful. Exporting ZIP..."))
                       (-> (export-zip results "inline")
                           (.then (fn [_]
                                    (on-entry "ZIP downloaded.")))))))
            (.catch (fn [err]
                      (on-entry (str "Batch error: " (.-message err))))))))))
