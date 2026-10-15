(ns pulse.scheduler.jobs
  "The catalogue of the scheduler's jobs (ADR-0020). Each is a map as `pulse.scheduler.job` says,
  whose handler closes over the components it needs, so the catalogue is built from the started
  system by a function and not read from the configuration. The rules engine, the device
  simulator and the appointment reminders are added here as they are written.")

(defn catalogue
  "The jobs to run, a vector, built from `options` (the `:pulse.scheduler/jobs` component).
  There are none yet."
  [_options]
  [])
