(ns user
  "REPL entry point. Start with `clojure -M:dev -m nrepl.cmdline` or Calva jack-in (alias :dev),
  then evaluate `(go)`: it starts the api, the scheduler and the portal as `bb dev` does (`pulse.dev`), with the
  profile in API_PROFILE (default dev), and `(reset)` reloads what changed and starts them again. The
  system lives in `integrant.repl.state/system`."
  (:require [clj-reload.core :as reload]
            [integrant.repl :as ig-repl :refer [go halt reset]]))

;; `reset` reloads changed namespaces with clj-reload: ours only, never the dependencies.
(reload/init {:dirs ["libs/fhir/src" "libs/platform/src" "libs/ui/src" "apps/api/src" "apps/scheduler/src" "apps/web/src" "dev/src"]})

(ig-repl/set-prep!
 (fn []
   ;; Resolved here, not required above, so a mistake in one of the apps cannot stop the REPL from
   ;; starting. Requiring `pulse.dev` is what defines how each component starts.
   (require 'pulse.dev)
   ((requiring-resolve 'pulse.dev/config)
    {:profile (keyword (or (System/getenv "API_PROFILE") "dev"))})))

(comment
  (go)
  (halt)
  (reset)

  ;; Anything on the classpath can be tried from here.
  (require '[pulse.fhir.reference :as ref])
  (ref/parse "Patient/pt-ana"))
