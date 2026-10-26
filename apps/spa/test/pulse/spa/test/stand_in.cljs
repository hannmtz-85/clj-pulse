(ns pulse.spa.test.stand-in
  "A stand-in for the api, for the tests of the workspace that run on Node: a real HTTP server on a free port of this machine, which
  answers each request as the test says, and keeps what it was sent. The calls of the workspace are made against it as they are made
  against the api, with `fetch`, so what is checked is what goes over the wire. Not a test namespace: the name does not end in
  `-test`."
  (:require ["http" :as http-node]))

(defn json
  "`x`, data, as the text of JSON."
  [x]
  (js/JSON.stringify (clj->js x)))

(defn reply!
  "Answers the request of `res` with `status` and `body`, as JSON unless `content-type` says another thing."
  ([res status body] (reply! res status body "application/json"))
  ([^js res status body content-type]
   (.writeHead res status #js {"Content-Type" content-type})
   (.end res body)))

(defn serve!
  "A promise of a server on a free port, which answers each request by calling `(answer n request res)`: `n` is the number of the
  request (from 1) and `request` is `{:method :url :headers :body}`, the headers with lower-case names. What it gives is
  `{:server :port :seen :aborted}`: `:seen` is the atom of the requests the server was sent, in order, and `:aborted` the atom of how
  many of them the client gave up before they were answered (the connection was closed under them)."
  [answer]
  (js/Promise.
   (fn [resolve _]
     (let [seen (atom [])
           aborted (atom 0)
           handle (fn [^js req ^js res]
                    (let [chunks (atom [])]
                      (.on res "close" (fn [] (when-not (.-writableFinished res) (swap! aborted inc))))
                      (.on req "data" (fn [chunk] (swap! chunks conj (str chunk))))
                      (.on req "end" (fn []
                                       (let [request {:method (.-method req)
                                                      :url (.-url req)
                                                      :headers (js->clj (.-headers req))
                                                      :body (apply str @chunks)}
                                             n (count (swap! seen conj request))]
                                         (answer n request res))))))
           ^js server (.createServer http-node handle)]
       (.listen server 0 "127.0.0.1" (fn [] (resolve {:server server :port (.-port (.address server)) :seen seen :aborted aborted})))))))

(defn stop!
  "A promise of the server being stopped: its connections are closed, which `fetch` keeps open for the next request."
  [^js server]
  (.closeAllConnections server)
  (js/Promise. (fn [resolve _] (.close server (fn [& _] (resolve nil))))))

(defn until
  "A promise that is kept when `(pred)` is true, which it looks at every few milliseconds, and broken after three seconds."
  [pred]
  (js/Promise.
   (fn [resolve reject]
     (let [started (js/Date.now)]
       ((fn poll []
          (cond
            (pred) (resolve true)
            (> (- (js/Date.now) started) 3000) (reject (js/Error. "what the test waited for did not happen"))
            :else (js/setTimeout poll 5))))))))

(defn pause
  "A promise that is kept after `ms` milliseconds."
  [ms]
  (js/Promise. (fn [resolve _] (js/setTimeout resolve ms))))
