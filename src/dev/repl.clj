(ns repl
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server :as server]))

(def build-id :app)

(defn start
  "Start shadow-cljs server and watch the build with autobuild disabled.
   Call this once at the beginning of a Claude Code session."
  []
  (server/start!)
  (shadow/watch build-id {:autobuild false})
  ::started)

(defn stop
  "Stop the build worker."
  []
  (shadow/stop-worker build-id)
  ::stopped)

(defn build
  "Trigger a single recompilation. Call this after modifying source files."
  []
  (shadow/watch-compile! build-id)
  ::compiled)

(defn go
  "Full cycle: stop, recompile, done. Use to recover from a stuck state."
  []
  (stop)
  (Thread/sleep 500)
  (start)
  (build))

(defn repl
  "Switch the current nREPL session to ClojureScript eval.
   Call this after (start) and after the browser has loaded the app."
  []
  (shadow/nrepl-select build-id))
