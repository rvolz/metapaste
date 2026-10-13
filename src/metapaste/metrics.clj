(ns metapaste.metrics
  "Hand-rolled Prometheus registry and text exposition (Q46/Q47).

   Counters and histograms live in one atom; label values may only be
   route/method/status/le — never item names or key ids (Q47 cardinality
   rule). cleanup_runs_total and cleanup_deleted_rows_total are sourced from
   metapaste.cleanup/counters at render time so every cleanup pass counts no
   matter who triggered it; the cleanup_duration_seconds histogram is observed
   per pass through the observer hook metrics wires at load. items_expired_total
   is recorded by the API read path (404s attributable to expiry) — cleanup's
   own expired_deleted_total is not added again (that would double count a pass
   that already reaped rows the read path saw)."
  (:require [clojure.string :as str]
            [metapaste.cleanup :as cleanup]))

(def default-buckets
  "Ascending per-bucket upper bounds in seconds, by histogram metric."
  {:http_request_duration_seconds [0.005 0.01 0.025 0.05 0.1 0.25 0.5 1.0 2.5 5.0 10.0]
   :cleanup_duration_seconds [0.001 0.005 0.01 0.05 0.1 0.5 1.0 5.0 10.0 60.0]})

(def ^:private metric-defs
  {:http_requests_total
   {:type :counter :help "Total HTTP requests handled, by route, method and status."}
   :http_request_duration_seconds
   {:type :histogram :help "HTTP request duration in seconds, by route."}
   :uploaded_bytes_total
   {:type :counter :help "Total bytes accepted for item uploads."}
   :downloaded_bytes_total
   {:type :counter :help "Total bytes served for item content downloads."}
   :items_created_total
   {:type :counter :help "Items created."}
   :items_deleted_total
   {:type :counter :help "Items deleted through the API, including admin purge."}
   :items_expired_total
   {:type :counter :help "Read-path 404s attributable to expired-but-uncollected items (rows reaped by cleanup show in cleanup_deleted_rows_total)."}
   :cleanup_runs_total
   {:type :counter :help "Cleanup passes completed."}
   :cleanup_deleted_rows_total
   {:type :counter :help "Item rows deleted by cleanup passes."}
   :cleanup_duration_seconds
   {:type :histogram :help "Cleanup pass duration in seconds."}
   :storage_errors_total
   {:type :counter :help "Storage errors surfaced as exceptions while serving requests."}})

(def ^:private derived
  "Metric -> cleanup/counters snapshot key, read at render time. items_expired_total
   is NOT here: it counts read-path expiry misses recorded by the API, while
   cleanup's expired_deleted_total feeds cleanup_deleted_rows_total."
  {:cleanup_runs_total :runs
   :cleanup_deleted_rows_total :deleted_rows_total})

(defonce ^:private state
  (atom {:counters {} :histograms {} :buckets {}}))

;; --- recording ---------------------------------------------------------------

(defn inc!
  "Add n to a counter. Labels: keyword -> stringable value."
  ([k] (inc! k {} 1))
  ([k labels] (inc! k labels 1))
  ([k labels n]
   (swap! state update :counters update-in [[k (or labels {})]]
          (fnil + 0) n)))

(defn- effective-buckets [st k]
  (or (get-in st [:buckets k])
      (get default-buckets k)
      (get default-buckets :http_request_duration_seconds)))

(defn observe!
  "Record one histogram observation in SECONDS. A series is created with the
   buckets in effect at its first observation."
  [k labels v]
  (swap! state
         (fn [st]
           (let [path [:histograms [k (or labels {})]]
                 h (get-in st path)
                 h (or h (let [bs (effective-buckets st k)]
                           {:buckets bs
                            :counts (vec (repeat (inc (count bs)) 0))
                            :sum 0.0 :count 0}))
                 v (double v)
                 ;; cumulative: bump every bucket whose bound is >= v
                 start (count (take-while #(< (double %) v) (:buckets h)))]
             (assoc-in st path
                       (-> h
                           (update :counts
                                   (fn [cs]
                                     (mapv (fn [i c] (if (>= i start) (inc c) c))
                                           (range) cs)))
                           (update :sum + v)
                           (update :count inc)))))))

(defn set-buckets!
  "Override histogram buckets (ascending, seconds). Applies to series created
   afterwards; call clear! first when data already exists."
  [k buckets]
  (swap! state assoc-in [:buckets k] (vec buckets)))

;; Every completed cleanup pass observes cleanup_duration_seconds here, no
;; matter who triggered the pass (worker, POST /manage/v1/cleanup, tests).
(cleanup/set-observer! (fn [seconds] (observe! :cleanup_duration_seconds {} seconds)))

(defn clear!
  "Reset every counter, histogram and bucket override (tests)."
  []
  (reset! state {:counters {} :histograms {} :buckets {}}))

;; --- exposition --------------------------------------------------------------

(defn- escape-value [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")))

(defn- labels-str [labels]
  (when (seq labels)
    (str "{"
         (str/join ","
                   (for [[k v] (sort-by (comp str key) labels)]
                     (str (name k) "=\"" (escape-value v) "\"")))
         "}")))

(defn- declared-series [st k]
  (into [] (filter (fn [[id _]] (= k (first id)))) (:counters st)))

(defn- histogram-series-of [st k]
  (into [] (filter (fn [[id _]] (= k (first id)))) (:histograms st)))

(defn- le-str [h i]
  (if (= i (count (:buckets h)))
    "+Inf"
    (str (double (nth (:buckets h) i)))))

(defn- render-counter [k st cc]
  (if-let [ck (get derived k)]
    [(str (name k) " " (long (or (get cc ck) 0)))]
    (let [series (sort-by (fn [[[_ labels] _]] (str (labels-str labels)))
                          (declared-series st k))]
      (if (seq series)
        (mapv (fn [[[_ labels] v]]
                (str (name k) (labels-str labels) " " v))
              series)
        [(str (name k) " 0")]))))

(defn- render-histogram [k st]
  (let [series (histogram-series-of st k)
        series (if (seq series)
                 series
                 (let [bs (effective-buckets st k)]
                   [[[k {}] {:buckets bs
                             :counts (vec (repeat (inc (count bs)) 0))
                             :sum 0.0 :count 0}]]))]
    (mapcat (fn [[[_ labels] h]]
              (concat
                (map-indexed
                  (fn [i c]
                    (str (name k) "_bucket"
                         (labels-str (assoc labels :le (le-str h i))) " " c))
                  (:counts h))
                [(str (name k) "_sum" (labels-str labels) " " (str (double (:sum h))))
                 (str (name k) "_count" (labels-str labels) " " (:count h))]))
            series)))

(defn render
  "Full Prometheus text exposition (0.0.4): HELP + TYPE + one line per
   series, metric names sorted, ending with a newline. Declared metrics with
   no observations are emitted at zero."
  []
  (let [st @state
        cc (cleanup/counters)
        lines (mapcat (fn [k]
                        (let [{:keys [type help]} (metric-defs k)]
                          (concat [(str "# HELP " (name k) " " help)
                                   (str "# TYPE " (name k) " " (name type))]
                                  (if (= :counter type)
                                    (render-counter k st cc)
                                    (render-histogram k st)))))
                      (sort (keys metric-defs)))]
    (str (str/join "\n" lines) "\n")))
