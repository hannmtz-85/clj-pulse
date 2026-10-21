(ns pulse.web.values
  "Values that must not be guessed, and the one comparison that may be made of them (ADR-0028): the `state`, the `nonce`, the
  PKCE verifier and the CSRF token of the portal."
  (:import (java.nio.charset StandardCharsets)
           (java.security MessageDigest SecureRandom)
           (java.util Base64)))

(def ^:private ^SecureRandom random (SecureRandom.))

(defn random-value
  "A random value that is safe in an address, with `n` bytes of entropy, from the system's secure random number generator.
  RFC 7636 wants a PKCE verifier of 43 to 128 characters: 32 bytes are 43."
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes random bytes)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bytes)))

(defn same?
  "Whether two strings are equal, taking the same time whatever they have in common: for a value that came from a browser and
  is compared with one that must not be guessed. Anything that is not a string is equal to nothing, so a value that is not
  there never matches a value that is not there."
  [a b]
  (and (string? a)
       (string? b)
       (MessageDigest/isEqual (.getBytes ^String a StandardCharsets/UTF_8) (.getBytes ^String b StandardCharsets/UTF_8))))
