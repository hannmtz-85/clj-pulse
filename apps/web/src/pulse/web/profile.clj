(ns pulse.web.profile
  "The profile of the portal, `GET /profile` (ADR-0028): who the patient is to the clinic, their doctor and the way out. The portal asks
  the api, with the patient's own token, who they are (`/api/me`), which answers the patient's own resource and their general
  practitioner: one call, and nothing is asked for a patient by name, so there is no other record to ask for. The page is
  `pulse.web.pages.profile`.

  Without that one call there is no page, so a failure of it is a page for the failure (`pulse.web.authz`). A resource that the api
  does not have is a message in the place of the first card, and the doctor's card is there all the same."
  (:require [pulse.web.api :as api]
            [pulse.web.authz :as authz]
            [pulse.web.html :as html]
            [pulse.web.pages :as pages]
            [pulse.web.pages.profile :as profile]
            [pulse.web.return-to :as return-to]
            [pulse.web.session :as session]))

(defn handler
  "The handler of `GET /profile`. `deps` has the `:api`, the portal's client of the api."
  [{:keys [api]}]
  (fn [request]
    (let [me (api/get-json api request "/api/me")
          csrf-token (session/csrf-token-of request)]
      (html/response
       (pages/page {:title "Profile"
                    :current :profile
                    :person (authz/person request)
                    :csrf-token csrf-token}
                   (profile/content {:me me :csrf-token csrf-token} {:retry (return-to/of-request request)}))))))
