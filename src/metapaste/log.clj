(ns metapaste.log
  (:require [clojure.data.json :as json]
            [clojure.string :as str]))

(def ^:dynamic *request-id* nil)

(def ^:private secret-key-re #"(?i)token|secret|password")

(defn- redact [data]
  (into {}
        (map (fn [[k v]]
               [k (if (re-find secret-key-re (name k)) "***REDACTED***" v)]))
        data))

(defn- emit [level msg data]
  (let [base (merge (redact (or data {}))
                    {:ts (str (java.time.Instant/now))
                     :level level
                     :msg msg})
        base (cond-> base
               *request-id* (assoc :request_id *request-id*))]
    (println (json/write-str base :escape-slash false))
    (flush)))

(defn info
  ([msg] (info msg nil))
  ([msg data] (emit "info" msg data)))

(defn warn
  ([msg] (warn msg nil))
  ([msg data] (emit "warn" msg data)))

(defn error
  ([msg] (error msg nil))
  ([msg data] (emit "error" msg data)))

(defmacro with-request-id
  "Binds id for the dynamic extent of body; emitted lines then carry
  request_id. Placeholder hook for the HTTP layer in later milestones."
  [id & body]
  `(binding [*request-id* ~id] ~@body))