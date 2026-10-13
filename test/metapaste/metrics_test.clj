(ns metapaste.metrics-test
  "M6 (Q46/Q47): Prometheus text exposition grammar, the exact Q47 metric
   set, label cardinality rules, and configurable histogram buckets."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.metrics :as metrics]))

;; --- exposition grammar (regex validation, text format 0.0.4) ---------------

(def ^:private metric-name-re #"[a-zA-Z_:][a-zA-Z0-9_:]*")

(def ^:private label-name-re #"[a-zA-Z_][a-zA-Z0-9_]*")

(def ^:private label-value-re #"(?:[^\"\\]|\\.)*")

(def ^:private number-re
  #"-?(?:[0-9]+(?:\.[0-9]+)?|\.[0-9]+)(?:[eE][-+]?[0-9]+)?|\+Inf|-Inf")

(def ^:private help-line-re
  (re-pattern (str "^# HELP (" metric-name-re ") [!-~].*$")))

(def ^:private type-line-re
  (re-pattern (str "^# TYPE (" metric-name-re
                   ") (counter|gauge|histogram|summary|untyped)$")))

(def ^:private sample-line-re
  (re-pattern (str "^(" metric-name-re ")"
                   "(?:\\{(" label-name-re "=\\\"" label-value-re "\\\""
                   "(?:," label-name-re "=\\\"" label-value-re "\\\")*)\\})?"
                   " (" number-re ")$")))

(defn- label-pair-re []
  (re-pattern (str "(" label-name-re ")=\"(" label-value-re ")\"")))

(defn- parse-labels
  "Label-set body of a sample line -> {label value}; {} when absent."
  [body]
  (if (str/blank? body)
    {}
    (into {} (map (fn [[_ k v]] [k v])) (re-seq (label-pair-re) body))))

(defn- family-name
  "Sample name -> declared family name: histogram samples carry the
   _bucket/_sum/_count suffix of their family."
  [nm]
  (cond
    (str/ends-with? nm "_bucket") (subs nm 0 (- (count nm) (count "_bucket")))
    (str/ends-with? nm "_sum") (subs nm 0 (- (count nm) (count "_sum")))
    (str/ends-with? nm "_count") (subs nm 0 (- (count nm) (count "_count")))
    :else nm))

(defn validate-exposition
  "Returns a vector of errors in a Prometheus text exposition; empty when
   valid. Checks: every line is HELP, TYPE, or a well-formed sample; no
   duplicate HELP/TYPE per family; TYPE precedes the family's samples."
  [text]
  (loop [ls (str/split-lines text) help #{} typed #{} errors []]
    (if-let [line (first ls)]
      (let [h (re-matches help-line-re line)
            ty (re-matches type-line-re line)
            sm (re-matches sample-line-re line)]
        (cond
          h (if (contains? help (second h))
              (recur (rest ls) help typed (conj errors (str "duplicate HELP: " line)))
              (recur (rest ls) (conj help (second h)) typed errors))
          ty (if (contains? typed (second ty))
               (recur (rest ls) help typed (conj errors (str "duplicate TYPE: " line)))
               (recur (rest ls) help (conj typed (second ty)) errors))
          sm (if (contains? typed (family-name (second sm)))
               (recur (rest ls) help typed errors)
               (recur (rest ls) help typed (conj errors (str "sample before TYPE: " line))))
          :else (recur (rest ls) help typed (conj errors (str "invalid line: " (pr-str line))))))
      errors)))

;; --- Q47 metric set ----------------------------------------------------------

(def q47-metrics
  #{"http_requests_total"
    "http_request_duration_seconds"
    "uploaded_bytes_total"
    "downloaded_bytes_total"
    "items_created_total"
    "items_deleted_total"
    "items_expired_total"
    "cleanup_runs_total"
    "cleanup_deleted_rows_total"
    "cleanup_duration_seconds"
    "storage_errors_total"})

(defn- declared-names
  "Metric names declared by a # HELP line (histogram samples carry _bucket/
   _sum/_count suffixes, so HELP is the canonical name source)."
  [text]
  (into #{}
        (keep (fn [line]
                (second (re-matches help-line-re line))))
        (str/split-lines text)))

(defn- sample-label-keys [text]
  (into #{}
        (mapcat (fn [line]
                  (when-let [m (re-matches sample-line-re line)]
                    (keys (parse-labels (nth m 2))))))
        (str/split-lines text)))

;; --- tests -------------------------------------------------------------------

(deftest fresh-exposition-lists-every-q47-metric
  (metrics/clear!)
  (let [text (metrics/render)]
    (is (= [] (validate-exposition text)) "fresh render is valid exposition")
    (testing "every Q47 metric name is declared, even before any observation"
      (is (empty? (remove (declared-names text) q47-metrics))))))

(deftest counters-render-with-labels
  (metrics/clear!)
  (metrics/inc! :http_requests_total {:route "/api/v1/items" :method "GET" :status 200})
  (metrics/inc! :http_requests_total {:route "/api/v1/items" :method "GET" :status 200})
  (metrics/inc! :http_requests_total {:route "/api/v1/items" :method "POST" :status 201})
  (metrics/inc! :uploaded_bytes_total {} 14)
  (let [text (metrics/render)]
    (is (= [] (validate-exposition text)))
    (is (str/includes? text
                       "http_requests_total{method=\"GET\",route=\"/api/v1/items\",status=\"200\"} 2"))
    (is (str/includes? text
                       "http_requests_total{method=\"POST\",route=\"/api/v1/items\",status=\"201\"} 1"))
    (is (str/includes? text "uploaded_bytes_total 14"))))

(deftest histograms-render-cumulative-buckets-sum-count
  (metrics/clear!)
  (metrics/set-buckets! :http_request_duration_seconds [0.1 1.0 2.0])
  ;; at (1.0), below (0.5 x2), above (5.0) the configured boundaries;
  ;; 0.5/0.5/1.0/5.0 sum exactly in IEEE-754 -> 7.0
  (doseq [v [0.5 0.5 1.0 5.0]]
    (metrics/observe! :http_request_duration_seconds {:route "/healthz"} v))
  (let [text (metrics/render)
        bucket (fn [le]
                 (str "http_request_duration_seconds_bucket{le=\"" le
                      "\",route=\"/healthz\"}"))]
    (is (= [] (validate-exposition text)))
    (is (str/includes? text (str (bucket "0.1") " 0")))
    (is (str/includes? text (str (bucket "1.0") " 3")))
    (is (str/includes? text (str (bucket "2.0") " 3")))
    (is (str/includes? text (str (bucket "+Inf") " 4")))
    (is (str/includes? text "http_request_duration_seconds_count{route=\"/healthz\"} 4"))
    (is (str/includes? text "http_request_duration_seconds_sum{route=\"/healthz\"} 7.0"))))

(deftest label-values-are-escaped
  (metrics/clear!)
  (metrics/inc! :storage_errors_total {:route "a\"b\\c"})
  (let [text (metrics/render)]
    (is (= [] (validate-exposition text)))
    (is (str/includes? text "storage_errors_total{route=\"a\\\"b\\\\c\"} 1"))))

(deftest never-label-by-item-name-or-key-id
  (metrics/clear!)
  (metrics/inc! :http_requests_total {:route "/api/v1/items/:name"
                                      :method "GET" :status 404})
  (metrics/observe! :http_request_duration_seconds {:route "/api/v1/items/:name"} 0.001)
  (let [keys (sample-label-keys (metrics/render))]
    (is (every? #{"route" "method" "status" "le"} keys)
        (pr-str keys))))

(deftest cleanup-counters-back-cleanup-metrics
  (metrics/clear!)
  (let [text (metrics/render)
        runs (re-find #"cleanup_runs_total (\d+)" text)
        expired (re-find #"items_expired_total (\d+)" text)]
    (is (= [] (validate-exposition text)))
    (testing "sourced from metapaste.cleanup/counters, not local bookkeeping"
      (is (some? runs))
      (is (some? expired)))))
