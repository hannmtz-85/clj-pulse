(ns pulse.web.record
  "The record of the portal, `GET /record` (ADR-0028): what the clinic has on file about a patient, their medication, their conditions and
  their allergies. The portal asks the api, with the patient's own token, who they are (`/api/me`: the id of their record) and then for
  their summary (`/api/patients/{id}/summary`), which is where the api keeps the three lists. Which patient is never in an address of the
  portal: it is the one the api says the token is for, so there is no other record to ask for. The page is `pulse.web.pages.record`.

  Who the patient is cannot be done without, and a failure of that call is a page for the failure. The summary can: the api could not be
  reached, or was too slow, or failed on its own side, and the page is the record with a message where the lists should be and a button to
  try again. A list that the summary names as one it could not load is a message too, and never an empty list. A failure of another
  kind (the api refusing the portal's token, say) is a failure of the whole page."
  (:require [pulse.platform.http.client :as http]
            [pulse.web.api :as api]
            [pulse.web.authz :as authz]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.record :as record]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]))

(defn handler
  "The handler of `GET /record`. `deps` has the `:api`, the portal's client of the api."
  [{:keys [api]}]
  (fn [request]
    (let [me (api/get-json api request "/api/me")
          id (api/patient-id me)
          summary (api/degrading "summary" #(api/get-json api request (http/path "api" "patients" id "summary")))]
      (html/response
       (pages/page {:title "My record"
                    :current :record
                    :person (authz/person request)
                    :csrf-token (session/csrf-token-of request)}
                   (record/content {:summary summary} {:retry (return-to/of-request request)}))))))
