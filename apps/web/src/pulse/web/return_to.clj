(ns pulse.web.return-to
  "Where a person goes back to after signing in, or after a failure (ADR-0028): a place in the portal, and nothing else.
  An address that comes from a request is only ever a path of this server, so a link that someone made cannot take a
  person who signs in somewhere else (an open redirect). Anything that is not such a path is the home page.")

(def home "/")

(def ^:private max-length 2000)

(defn path?
  "Whether `s` is a path of this server: it starts with one slash and goes on with printable ASCII, so no backslash
  (which a browser takes for a slash, so `/\\evil.example` would be `//evil.example`), no space, no control character and
  nothing that needs encoding to be in a header; and it has no more than 2000 characters. A second slash at the start
  is a scheme-relative address (`//evil.example`), which is not a path."
  [s]
  (boolean (and (string? s)
                (<= 1 (count s) max-length)
                (re-matches #"/(?!/)[\x21-\x5b\x5d-\x7e]*" s))))

(defn safe
  "`s` when it is a path of this server, otherwise the home page."
  [s]
  (if (path? s) s home))

(defn of-request
  "The place to come back to for `request`: the page it asked for, with its query string, when it is a GET (a POST sent
  again would be a GET of a place that does not take one). For a request that htmx made, it is the page the person was on
  (`HX-Current-URL`) and not the address of the fragment it asked for. Never anything but a path of this server."
  [{:keys [request-method uri query-string headers]}]
  (let [current (get headers "hx-current-url")]
    (cond
      (and (= "true" (get headers "hx-request")) (string? current))
      (let [^java.net.URI u (try (java.net.URI. current) (catch java.net.URISyntaxException _ nil))]
        (if (and u (.getRawPath u))
          (safe (str (.getRawPath u) (when-let [q (.getRawQuery u)] (str "?" q))))
          home))

      (= :get request-method)
      (safe (str uri (when (seq query-string) (str "?" query-string))))

      :else home)))
