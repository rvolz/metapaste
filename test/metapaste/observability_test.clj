(ns metapaste.observability-test
  "M6 run #5 observability: Q47 metric wiring driven through the real handler
   (read-path expiry attribution, byte counters, delete/create counts, catch-all
   request counting, cleanup pass histogram) plus the /metrics internal
   listener and Q48/Q50 request logging. Exposition syntax is validated with
   metapaste.metrics-test's regex validator."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.api :as api]
            [metapaste.auth :as auth]
            [metapaste.cleanup :as cleanup]
            [metapaste.http :as mhttp]
            [metapaste.metrics :as metrics]
            [metapaste.metrics-test :as exposition]
            [metapaste.storage :as storage])
  (:import [java.io ByteArrayInputStream File]
           [java.net Socket]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- fixtures ----------------------------------------------------------------

(def ^:private admin-token "adm-tok-3f9c2b")

(defn- test-cfg []
  {:min_ttl_seconds 60
   :max_ttl_seconds 31536000
   :default_ttl_seconds 259200
   :allow_never_expire false
   :path_prefix ""
   :rate_limit_rps 100
   :rate_limit_burst 200
   :max_item_bytes 65536
   :admin_token admin-token
   :metrics_port 0
   :port 0})

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp3-obs-" (make-array FileAttribute 0))))

(defn- delete-recursively! [^File f]
  (doseq [c (reverse (file-seq f))]
    (.delete ^File c)))

(defn- with-fresh-store
  "Run (f store handler bearer): fresh temp store, handler over it, bearer is
   the plaintext secret of a minted read/write key."
  [f]
  (let [dir (temp-dir)]
    (try
      (let [store (storage/open! dir)
            bearer (:secret (auth/create-key! store {:label "obs-test"
                                                     :scopes "read,write"}))
            handler (api/make-handler
                      {:store store
                       :config (test-cfg)
                       :multipart-parser (fn [_] {:error {:status 400
                                                          :slug "validation-failed"
                                                          :detail "no"}})})]
        (try
          (f store handler bearer)
          (finally (storage/close-storage store))))
      (finally (delete-recursively! dir)))))

;; --- request helper ----------------------------------------------------------

(defn- call
  "Invoke handler directly. opts: :body (JSON-mapped) or :raw-bytes,
   :headers merged extras, :no-auth drops the bearer, :content-type override."
  [handler bearer method uri & [opts]]
  (let [^bytes raw (or (:raw-bytes opts)
                       (when-let [b (:body opts)]
                         (.getBytes (json/write-str b :escape-slash false
                                                     :escape-unicode false)
                                    "UTF-8")))
        headers (cond-> {}
                  raw (assoc "content-type" (or (:content-type opts)
                                                "application/json")
                             "content-length" (str (alength raw)))
                  (not (:no-auth opts)) (assoc "authorization" (str "Bearer " bearer)))]
    (handler (cond-> {:request-method method
                      :uri uri
                      ;; explicit headers (admin token) win over the bearer default
                      :headers (into headers
                                     (map (fn [[k v]] [(str/lower-case (name k)) (str v)]))
                                     (:headers opts))}
               raw (assoc :body (ByteArrayInputStream. raw))))))

(defn- created-name [resp]
  (:name (json/read-str (:body resp) :key-fn keyword)))

;; --- Q47 wiring through the handler -----------------------------------------

(deftest items-deleted-total-counts-user-delete-and-admin-purge
  (with-fresh-store
    (fn [store handler bearer]
      (metrics/clear!)
      (let [a (created-name (call handler bearer :post "/api/v1/items"
                                  {:body {:content "one" :mime_type "text/plain"}}))
            b (created-name (call handler bearer :post "/api/v1/items"
                                  {:body {:content "two" :mime_type "text/plain"}}))]
        (is (= 204 (:status (call handler bearer :delete (str "/api/v1/items/" a)))))
        (is (= 204 (:status (call handler bearer :delete (str "/manage/v1/items/" b)
                                  {:headers {"authorization" (str "Bearer " admin-token)}}))))
        (testing "key revocation is not an item deletion"
          (let [k (:key_id (auth/create-key! store {:label "tmp" :scopes "read"}))]
            (is (= 204 (:status (call handler bearer :delete (str "/manage/v1/keys/" k)
                                      {:headers {"authorization" (str "Bearer " admin-token)}}))))))
        (is (str/includes? (metrics/render) "items_deleted_total 2"))))))

(deftest byte-metrics-count-accepted-payloads
  (with-fresh-store
    (fn [store handler bearer]
      (metrics/clear!)
      (let [content "hello world!" ;; 12 bytes
            resp (call handler bearer :post "/api/v1/items"
                       {:body {:content content :mime_type "text/plain"}})
            nm (created-name resp)]
        (is (= 201 (:status resp)))
        (testing "uploaded_bytes_total counts stored content bytes (JSON and multipart alike)"
          (is (str/includes? (metrics/render) "uploaded_bytes_total 12"))
          (is (str/includes? (metrics/render) "items_created_total 1")))
        (let [r (call handler bearer :get (str "/api/v1/items/" nm "/content"))]
          (is (= 200 (:status r)))
          (is (str/includes? (metrics/render) "downloaded_bytes_total 12")))
        (testing "metadata GET does not count as a download"
          (call handler bearer :get (str "/api/v1/items/" nm))
          (is (str/includes? (metrics/render) "downloaded_bytes_total 12")))))))

(deftest http-requests-total-covers-every-dispatch-outcome
  (with-fresh-store
    (fn [store handler bearer]
      (metrics/clear!)
      (call handler bearer :get "/no-such-route" {:no-auth true})
      (call handler bearer :get "/api/v1/items/zzzzzzzzzzzz" {:no-auth true})
      (call handler bearer :post "/api/v1/items/zzzzzzzzzzzz" {:body {}})
      (let [text (metrics/render)]
        (is (str/includes? text
                           "http_requests_total{method=\"GET\",route=\"unmatched\",status=\"404\"} 1"))
        (is (str/includes? text
                           "http_requests_total{method=\"GET\",route=\"/api/v1/items/:name\",status=\"401\"} 1"))
        (is (str/includes? text
                           "http_requests_total{method=\"POST\",route=\"/api/v1/items/:name\",status=\"405\"} 1"))))))

(deftest items-expired-total-counts-read-path-expiry-hits
  (with-fresh-store
    (fn [store handler bearer]
      (metrics/clear!)
      (let [row (storage/create-item! store {:content (.getBytes "late" "UTF-8")
                                            :mime_type "text/plain"
                                            :ttl_seconds -1})
            nm (:name row)]
        (is (= 404 (:status (call handler bearer :get (str "/api/v1/items/" nm)))))
        (is (= 404 (:status (call handler bearer :get (str "/api/v1/items/" nm "/content")))))
        (is (str/includes? (metrics/render) "items_expired_total 2"))
        (testing "a plain unknown name is not an expiry hit"
          (call handler bearer :get "/api/v1/items/zzzzzzzzzzzz")
          (is (str/includes? (metrics/render) "items_expired_total 2")))))))

(deftest cleanup-duration-histogram-observes-every-pass
  (with-fresh-store
    (fn [store handler bearer]
      (metrics/clear!)
      (let [before (cleanup/counters)
            result (cleanup/run-cleanup-once! store)
            text (metrics/render)
            hist (Long/parseLong (second (re-find #"cleanup_duration_seconds_count (\d+)" text)))
            runs (Long/parseLong (second (re-find #"cleanup_runs_total (\d+)" text)))
            rows (Long/parseLong (second (re-find #"cleanup_deleted_rows_total (\d+)" text)))]
        (is (= 1 hist) "one pass -> one histogram observation")
        (is (= (inc (long (:runs before))) runs))
        (is (= (+ (long (:deleted_rows_total before)) (long (:deleted result))) rows))))))

;; --- /metrics on the separate internal listener (Q46) ------------------------

(defn- raw-get
  "Minimal HTTP/1.1 GET on host:port; returns {:status :headers :body}. Uses
   java.net.Socket so this suite needs no test-only HTTP client dep."
  [port path]
  (let [s (Socket. "127.0.0.1" (int port))]
    (try
      (let [out (.getOutputStream s)
            in (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream s)))]
        (.write out (.getBytes (str "GET " path " HTTP/1.1\r\n"
                                    "Host: 127.0.0.1\r\nConnection: close\r\n\r\n")
                               "UTF-8"))
        (.flush out)
        (let [status-line (.readLine in)
              headers (loop [acc {}]
                        (let [l (.readLine in)]
                          (if (or (nil? l) (str/blank? l))
                            acc
                            (let [[k v] (str/split l #": " 2)]
                              (recur (assoc acc (str/lower-case (or k "")) (or v "")))))))
              body (str/join "\n" (take-while some? (repeatedly #(.readLine in))))]
          {:status (Integer/parseInt (second (re-find #" (\d{3}) " (str status-line))))
           :headers headers
           :body body}))
      (finally (.close s)))))

(deftest metrics-listener-serves-q47-exposition
  (let [srv (mhttp/start-metrics-server! (assoc (test-cfg) :metrics_port 0) {:port 0})]
    (try
      (let [r (raw-get (:port srv) "/metrics")]
        (is (= 200 (:status r)))
        (is (str/starts-with? (get-in r [:headers "content-type"]) "text/plain"))
        (testing "well-formed Prometheus exposition with every Q47 name"
          (is (= [] (exposition/validate-exposition (:body r))))
          (is (empty? (remove #(str/includes? (:body r) (str "# HELP " % " "))
                              exposition/q47-metrics)))))
      (finally (mhttp/stop-server! srv)))))

(deftest metrics-listener-serves-only-metrics
  (let [srv (mhttp/start-metrics-server! (test-cfg) {:port 0})]
    (try
      (is (= 404 (:status (raw-get (:port srv) "/"))))
      (is (= 404 (:status (raw-get (:port srv) "/api/v1/items"))))
      (testing "the main listener never serves /metrics (separate surface)"
        (with-fresh-store
          (fn [store handler bearer]
            (is (= 404 (:status (call handler bearer :get "/metrics")))))))
      (finally (mhttp/stop-server! srv)))))

;; --- Q48/Q50 request logging -------------------------------------------------

(defn- capture
  "Run (f) with stdout captured; returns [result log-lines] where log-lines are
   the parsed JSON lines (f must not log partial lines)."
  [f]
  (let [box (atom nil)
        out (with-out-str (reset! box (f)))
        lines (into [] (comp (remove str/blank?)
                             (map #(json/read-str % :key-fn keyword)))
                    (str/split-lines out))]
    [@box lines]))

(defn- request-lines [lines]
  (filter #(= "request" (:msg %)) lines))

(deftest request-id-accepted-echoed-and-generated
  (with-fresh-store
    (fn [store handler bearer]
      (testing "client-supplied id is echoed and logged"
        (let [[resp lines] (capture
                             #(call handler bearer :get "/healthz"
                                    {:no-auth true
                                     :headers {"x-request-id" "req-abc-1"}}))]
          (is (= "req-abc-1" (get-in resp [:headers "X-Request-Id"])))
          (is (= "req-abc-1" (:request_id (first lines))))))
      (testing "absent or invalid id is generated (UUID) and echoed"
        (doseq [h [nil ""]]
          (let [[resp lines] (capture
                               #(call handler bearer :get "/healthz"
                                      {:no-auth true
                                       :headers (if h {"x-request-id" h} {})}))
                rid (get-in resp [:headers "X-Request-Id"])]
            (is (re-matches #"[0-9a-f-]{36}" rid))
            (is (= rid (:request_id (first lines))))))
        (let [[resp lines] (capture
                             #(call handler bearer :get "/healthz"
                                    {:no-auth true
                                     :headers {"x-request-id" "bad id \n with control"}}))]
          (is (re-matches #"[0-9a-f-]{36}" (get-in resp [:headers "X-Request-Id"])))
          (is (= (get-in resp [:headers "X-Request-Id"])
                 (:request_id (first lines)))))))))

(deftest request-log-line-carries-q48-fields
  (with-fresh-store
    (fn [store handler bearer]
      (let [[resp lines] (capture
                           #(call handler bearer :post "/api/v1/items"
                                  {:body {:content "hello world!" :mime_type "text/plain"}}))
            line (first (request-lines lines))
            nm (:name (json/read-str (:body resp) :key-fn keyword))]
        (is (= 201 (:status resp)))
        (is (string? (:ts line)))
        (is (= "info" (:level line)))
        (is (string? (:request_id line)))
        (is (= "POST" (:method line)))
        (is (= "/api/v1/items" (:route line)))
        (is (= 201 (:status line)))
        (is (number? (:latency_ms line)))
        (is (string? (:key_id line)))
        (is (= nm (:name line)))
        (is (= 12 (:size line)))))))

(deftest every-request-scoped-log-line-has-request-id
  (with-fresh-store
    (fn [store handler bearer]
      (let [[_ lines] (capture
                        (fn []
                          (call handler bearer :get "/no-such-route" {:no-auth true})
                          (call handler bearer :get "/api/v1/items/zzzzzzzzzzzz" {:no-auth true})
                          (call handler bearer :post "/api/v1/items"
                                {:body {:content "x" :mime_type "text/plain"}})))]
        (is (= 3 (count lines)))
        (is (every? #(and (string? (:request_id %)) (not (str/blank? (:request_id %))))
                    lines))))))

(deftest cleanup-job-log-lines-carry-cleanup-job-request-id
  (let [sw (java.io.StringWriter.)
        orig *out*
        store (reify storage/Storage
                (delete-expired-batch [_ _]
                  (throw (ex-info "forced failure for the log test" {}))))]
    (alter-var-root #'*out* (constantly sw))
    (try
      (let [w (cleanup/start! store {:interval-s 3600 :grace-s 0})]
        ;; bounded wait for the first failing pass to log
        (loop [n 0]
          (when (and (str/blank? (str sw)) (< n 200))
            (Thread/sleep 10)
            (recur (inc n))))
        (is (true? (cleanup/stop!))))
      (finally (alter-var-root #'*out* (constantly orig))))
    (let [line (first (remove str/blank? (str/split-lines (str sw))))]
      (is (some? line) "expected a cleanup log line")
      (is (= "cleanup-job" (:request_id (json/read-str line :key-fn keyword)))))))

(deftest secrets-and-content-never-appear-in-logs
  (with-fresh-store
    (fn [store handler bearer]
      (let [content-marker "SUPER-SECRET-CONTENT-c0ffee"
            [_ lines] (capture
                        (fn []
                          (call handler bearer :post "/api/v1/items"
                                {:body {:content content-marker :mime_type "text/plain"}})
                          (call handler bearer :get "/api/v1/items/zzzzzzzzzzzz/content")
                          (call handler bearer :post "/manage/v1/keys"
                                {:body {:label "leak-test" :scopes "read"}
                                 :headers {"authorization" (str "Bearer " admin-token)}})))
            raw (pr-str lines)]
        (testing "item content never reaches the log stream"
          (is (not (str/includes? raw content-marker))))
        (testing "api secrets and the admin token never reach the log stream"
          (is (not (str/includes? raw admin-token)))
          (is (not (str/includes? raw (str "Bearer " bearer))))
          (is (not (str/includes? raw bearer))))
        (testing "the request log line never carries a body field"
          (is (every? #(nil? (:body %)) lines)))))))
