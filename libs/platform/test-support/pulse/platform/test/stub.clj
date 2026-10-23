(ns pulse.platform.test.stub
  "A stand-in for another service, for the tests of what calls one: a small HTTP server on a free
  port that answers every request with what a function says and keeps the requests it got.

  Test code only: it is on the classpath of the `:test` alias and nowhere else."
  (:require [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(defn start!
  "Starts a server whose answer to each request is `(handler request)`, a map of `:status` (200),
  `:headers`, `:body` (a string; a map or vector is sent as JSON text by whoever builds it, not
  here) and `:delay-ms` (0). `request` is `{:method :path :query :headers :body}` with the header
  names in lower case. Returns `{:url \"http://127.0.0.1:port\" :requests atom :server s}`.

  The requests are answered one at a time, in the order they came, unless `:concurrent?` is true: each is then
  answered on a thread of its own, as a real service does, so that a test can see that a caller asks for several
  things at once (the order of `:requests` is then the order they began in, which is not fixed)."
  ([handler] (start! handler nil))
  ([handler {:keys [concurrent?]}]
   (let [requests (atom [])
         server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
     (.createContext server "/"
                     (reify HttpHandler
                       (handle [_ ex]
                         (let [^HttpExchange ex ex
                               request {:method (.getRequestMethod ex)
                                       ;; as it is on the wire: encoded
                                        :path (.getRawPath (.getRequestURI ex))
                                        :query (.getRawQuery (.getRequestURI ex))
                                        :headers (into {} (map (fn [[k v]] [(str/lower-case k) (first v)])) (.getRequestHeaders ex))
                                        :body (slurp (.getRequestBody ex))}
                               _ (swap! requests conj request)
                               {:keys [status headers ^String body delay-ms] :or {status 200 body "" delay-ms 0}} (handler request)
                               payload (.getBytes body StandardCharsets/UTF_8)]
                           (when (pos? delay-ms) (Thread/sleep (long delay-ms)))
                           (doseq [[k v] (merge {"Content-Type" "application/json"} headers)]
                             (.add (.getResponseHeaders ex) k v))
                           (.sendResponseHeaders ex (int status) (if (zero? (alength payload)) -1 (long (alength payload))))
                           (when (pos? (alength payload))
                             (with-open [out (.getResponseBody ex)] (.write out payload)))
                           (.close ex)))))
     (when concurrent?
       (.setExecutor server (Executors/newVirtualThreadPerTaskExecutor)))
     (.start server)
     {:url (str "http://127.0.0.1:" (.getPort (.getAddress server)))
      :requests requests
      :server server})))

(defn stop! [{:keys [^HttpServer server]}]
  (.stop server 0))
