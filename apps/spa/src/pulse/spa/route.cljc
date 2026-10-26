(ns pulse.spa.route
  "Where the workspace is, as far as the first thing it does depends on it (ADR-0029). There is no router yet: it arrives with the
  panel (step 5 of the phase), and until then the only path that matters is the one the identity provider sends a person back to
  when they have signed out. Pure.")

(def signed-out-path
  "Where the identity provider sends the browser once it has signed the person out (the realm registers every address of the
  workspace as a place it may do that). It is a page of the workspace that does **not** ask the person to sign in: it says they
  have signed out, and a person who wants to sign in again says so."
  "/signed-out")

(defn signed-out?
  "Whether `pathname`, the path of the address of the page, is the page of someone who has signed out. The server answers the
  page for a path with one slash at its end too."
  [pathname]
  (contains? #{signed-out-path (str signed-out-path "/")} pathname))
