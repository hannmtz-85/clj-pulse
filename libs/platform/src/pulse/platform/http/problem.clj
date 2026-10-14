(ns pulse.platform.http.problem
  "Errors as RFC 9457 problem details (`application/problem+json`): one shape for every error
  a service returns, whoever raised it. The body is encoded here, to a string, so a problem
  looks the same whether it comes from a handler, a middleware that runs before content
  negotiation or the router's own 404.

  What a client sees is the HTTP status, its title, an optional `detail` written for the
  occasion, the request path as `instance` and the request id. Never put internals in
  `detail`: stack traces and exception messages belong in the logs."
  (:require [jsonista.core :as json]))

(def titles
  {400 "Bad Request"
   401 "Unauthorized"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   406 "Not Acceptable"
   409 "Conflict"
   415 "Unsupported Media Type"
   422 "Unprocessable Content"
   429 "Too Many Requests"
   500 "Internal Server Error"
   502 "Bad Gateway"
   503 "Service Unavailable"
   504 "Gateway Timeout"})

(defn response
  "A complete Ring response for HTTP `status`. Options:

  `:detail`   text specific to this occurrence, safe to show
  `:headers`  response headers to add, such as `WWW-Authenticate` or `Allow`
  `:extra`    more members of the problem object (RFC 9457 allows extensions)
  `:request`  the Ring request, for `instance` and `requestId`"
  [status {:keys [detail headers extra request]}]
  (let [body (cond-> (merge {:type "about:blank"
                             :title (get titles status "Error")
                             :status status}
                            extra)
               detail (assoc :detail detail)
               (:uri request) (assoc :instance (:uri request))
               (:request-id request) (assoc :requestId (:request-id request)))]
    {:status status
     :headers (merge {"Content-Type" "application/problem+json"} headers)
     :body (json/write-value-as-string body)}))

(defn ex
  "An exception that `pulse.platform.http.middleware/wrap-exceptions` turns into a problem
  response with this `status`. `detail` is shown to the client. Options: `:extra`, `:headers`
  (as in `response`) and `:cause`."
  ([status detail] (ex status detail nil))
  ([status detail {:keys [extra headers cause]}]
   (ex-info detail {:type ::problem :status status :detail detail :extra extra :headers headers} cause)))
