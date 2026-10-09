(ns metapaste.names
  "Crockford Base32 name generation for paste identifiers."
  (:require [clojure.string :as str]))

(def alphabet "0123456789abcdefghjkmnpqrstvwxyz")

(def ^:private name-length 12)

(def ^:private max-retries 5)

(def ^:private rng
  (java.security.SecureRandom.))

(defn normalize
  "Case-insensitive name normalization for lookups and comparisons."
  [s]
  (str/lower-case s))

(defn random-name
  "12-char lowercase Crockford Base32 name from the CSPRNG."
  []
  (let [n (count alphabet)
        sb (StringBuilder. name-length)]
    (dotimes [_ name-length]
      (.append sb (.charAt alphabet (.nextInt rng n))))
    (str sb)))

(defn taken
  "Marker ex-info for a unique-constraint violation on a name."
  [msg]
  (ex-info msg {:type ::taken}))

(defn taken? [e]
  (= ::taken (:type (ex-data e))))

(defn unique-name
  "Calls (f random-name); returns f's value on success. On a `taken`
  collision, retries with a fresh name up to 5 times, then throws
  {:type ::exhausted}. Non-collision errors propagate untouched."
  [f]
  (loop [attempt 0]
    (let [result (try
                   [:ok (f (random-name))]
                   (catch Throwable e
                     (cond
                       (not (taken? e)) (throw e)
                       (< attempt max-retries) [:retry]
                       :else (throw (ex-info "name collision retries exhausted"
                                             {:type ::exhausted})))))]
      (if (= :retry (first result))
        (recur (inc attempt))
        (second result)))))
