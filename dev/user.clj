(ns user
  "REPL entry point. Start with `clojure -M:dev -m nrepl.cmdline` or Calva jack-in (alias :dev),
  then evaluate `(go)`: it starts the api with the profile in API_PROFILE (default dev), and
  `(reset)` reloads what changed and starts it again. The system lives in
  `integrant.repl.state/system`."
  (:require [clj-reload.core :as reload]
            [integrant.repl :as ig-repl :refer [go halt reset]]))

;; `reset` reloads changed namespaces with clj-reload: ours only, never the dependencies.
(reload/init {:dirs ["libs/fhir/src" "libs/platform/src" "apps/api/src" "apps/scheduler/src"]})

(ig-repl/set-prep!
 (fn []
   ;; Resolved here, not required above, so a mistake in the api cannot stop the REPL from
   ;; starting. Requiring the system namespace is what defines how each component starts.
   (require 'pulse.api.system)
   ((requiring-resolve 'pulse.api.config/load-config)
    {:profile (keyword (or (System/getenv "API_PROFILE") "dev"))})))

(comment
  (go)
  (halt)
  (reset)

  ;; Anything on the classpath can be tried from here.
  (require '[pulse.fhir.reference :as ref])
  (ref/parse "Patient/pt-ana"))
