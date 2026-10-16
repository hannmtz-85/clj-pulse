(ns env
  "Reads the git-ignored .env file, creates it from .env.example with random secrets, and completes
  an existing one with the variables it lacks."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.util Base64)))

(defn entries
  "The `[name value]` pairs of dotenv `text`, in order. A line is `NAME=value`, with spaces around it
  allowed; anything else, such as a blank line or a `#` comment, is not a variable."
  [text]
  (->> (str/split-lines text)
       (keep #(when-let [[_ k v] (re-matches #"\s*([A-Za-z_][A-Za-z0-9_]*)=(.*?)\s*" %)]
                [k v]))))

(defn parse
  "Parses dotenv text into a map of strings. Blank lines and `#` comments are ignored."
  [text]
  (into {} (entries text)))

(defn load-env
  "The variables of ./.env, or {} when the file does not exist."
  []
  (if (.exists (io/file ".env")) (parse (slurp ".env")) {}))

(defn get-var
  "A variable from the process environment, falling back to ./.env."
  [k]
  (or (System/getenv k) (get (load-env) k)))

(defn vars
  "The variables of `names` that are set, from the process environment or ./.env, as a map: what a
  process that a task starts needs to be given (`.env` is read by compose and by `bb`, and by
  nothing else)."
  [names]
  (into {} (keep (fn [k] (when-let [v (get-var k)] [k v]))) names))

(defn- random-secret
  "32 URL-safe characters from 24 random bytes, which make them with no padding."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getUrlEncoder) bytes)))

(def ^:private placeholder
  "What .env.example says where a value has to be a secret of one's own."
  "change-me")

(defn fill
  "dotenv `text` with a secret from the function `random-secret` in every `NAME=change-me` line.
  Nothing else changes, line endings included."
  [text random-secret]
  (str/replace text #"(?m)^(\w+)=change-me[ \t]*$" (fn [[_ k]] (str k "=" (random-secret)))))

(defn complete
  "What the .env made of `current`, the text of one that exists, and `example`, the text of
  .env.example, is: every variable of the example that `current` lacks is added at the end, with
  the value the example gives it or, where the example says `change-me`, a secret from the function
  `random-secret`. What `current` has is kept as it is, and the added lines end as its own do.
  Returns `{:text s :added [names]}`; `:text` is `current` itself when nothing was missing."
  [current example random-secret]
  (let [have (set (keys (parse current)))
        missing (vec (remove (comp have first) (entries example)))
        eol (if (str/includes? current "\r\n") "\r\n" "\n")]
    {:added (mapv first missing)
     :text (if (empty? missing)
             current
             (str current
                  ;; a blank line to set the note apart, after the end of the last line if it has none
                  (cond
                    (str/blank? current) nil
                    (str/ends-with? current "\n") eol
                    :else (str eol eol))
                  "# Added by `bb env:init`: in .env.example, and not in this file when it was made" eol
                  (str/join eol (map (fn [[k v]] (str k "=" (if (= placeholder v) (random-secret) v)))
                                     missing))
                  eol))}))

(defn init!
  "Creates .env from .env.example, replacing every `change-me` value with a random secret. An
  existing .env keeps every value it has and gets the variables it lacks, so that a variable added
  to the example later does not leave `bb up` asking for one that `bb env:init` says is fine."
  []
  (let [example (slurp ".env.example")]
    (if (.exists (io/file ".env"))
      (let [{:keys [text added]} (complete (slurp ".env") example random-secret)]
        (if (seq added)
          (do (spit ".env" text)
              (println "Added to .env:" (str/join ", " added)))
          (println ".env has every variable of .env.example: left untouched")))
      (do (spit ".env" (fill example random-secret))
          (println "Created .env with random local secrets (it is git-ignored)")))))
