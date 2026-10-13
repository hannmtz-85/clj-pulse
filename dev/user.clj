(ns user
  "REPL entry point. Start with `clojure -M:dev -m nrepl.cmdline` or Calva jack-in
  (alias :dev). The Integrant `go`/`reset`/`halt` workflow arrives with the first app.")

(comment
  (require '[pulse.fhir.reference :as ref])
  (ref/parse "Patient/pt-ana"))
