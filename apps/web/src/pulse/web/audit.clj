(ns pulse.web.audit
  "The portal's audit events, of the shape the api's and the scheduler's have (`pulse.api.audit`): who, what they asked for, and
  what was decided, and never a token or what a person's record holds. A person who signs in is a decision, allowed or not."
  (:require [clojure.string :as str]
            [taoensso.telemere :as t]))

(defn sign-in!
  "One `:pulse.audit/access` event for the decision on a person who has signed in at the identity provider: `outcome` is
  `:allowed` or `:denied`, with the `reason` when it is a denial (such as `:not-a-patient`). `person` is
  `{:id :username :roles}` and `client` the client the portal is at the identity provider."
  [request client {:keys [id username roles]} outcome reason]
  (t/event! :pulse.audit/access
            {:level (if (= :allowed outcome) :info :warn)
             :data (cond-> {:actor {:subject id
                                    :username username
                                    :roles (vec (sort roles))
                                    :client client}
                            :action :portal/sign-in
                            :outcome outcome
                            :method (some-> (:request-method request) name str/upper-case)
                            :path (:uri request)}
                     reason (assoc :reason reason))}))
