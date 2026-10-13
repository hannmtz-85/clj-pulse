(ns env
  "Reads the git-ignored .env file and creates it from .env.example with random secrets."
  (:require [babashka.fs :as fs]
            [clojure.string :as str])
  (:import (java.security SecureRandom)
           (java.util Base64)))

(defn parse
  "Parses dotenv text into a map of strings. Blank lines and `#` comments are ignored."
  [text]
  (->> (str/split-lines text)
       (map str/trim)
       (remove #(or (str/blank? %) (str/starts-with? % "#")))
       (keep #(when-let [[_ k v] (re-matches #"([A-Za-z_][A-Za-z0-9_]*)=(.*)" %)]
                [k v]))
       (into {})))

(defn load-env
  "The variables of ./.env, or {} when the file does not exist."
  []
  (if (fs/exists? ".env") (parse (slurp ".env")) {}))

(defn get-var
  "A variable from the process environment, falling back to ./.env."
  [k]
  (or (System/getenv k) (get (load-env) k)))

(defn- random-secret
  "32 URL-safe characters from 24 random bytes."
  []
  (let [bytes (byte-array 24)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn init!
  "Creates .env from .env.example, replacing every `change-me` value with a random secret.
  Never overwrites an existing .env."
  []
  (if (fs/exists? ".env")
    (println ".env already exists: left untouched")
    (do (spit ".env"
              (str/replace (slurp ".env.example")
                           #"(?m)^(\w+)=change-me\s*$"
                           (fn [[_ k]] (str k "=" (random-secret)))))
        (println "Created .env with random local secrets (it is git-ignored)"))))
