(ns metapaste.api-test
  "Unit tests for the pure request/response logic in metapaste.api: mime/desc/
   ttl validation, list query parsing, ETag matching, serving posture, and the
   problem+json shape. End-to-end behavior lives in http-integration-test; the
   M4 expiry-semantics regressions drive make-handler directly so they can set
   allow_never_expire per case."
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [metapaste.api :as api]
            [metapaste.storage :as storage])
  (:import [java.io ByteArrayInputStream File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp3-api-" (make-array FileAttribute 0))))

(defn- delete-recursively! [^File f]
  (doseq [c (reverse (file-seq f))]
    (.delete ^File c)))

(defn- with-db-conn [dir f]
  (let [c (jdbc/connection (str "sqlite:" (.getAbsolutePath (File. ^File dir "metapaste.db"))))]
    (try
      (f c)
      (finally (.close c)))))

(def cfg
  {:min_ttl_seconds 60
   :max_ttl_seconds 31536000
   :default_ttl_seconds 259200
   :allow_never_expire false
   :path_prefix ""})

(deftest mime-essence-validation
  (testing "valid essences are lowercased with parameters stripped"
    (is (= "text/plain" (api/mime-essence "text/plain; charset=utf-8")))
    (is (= "text/plain" (api/mime-essence "TEXT/Plain")))
    (is (= "application/vnd.x+json" (api/mime-essence "application/vnd.x+json"))))
  (testing "invalid syntax is rejected"
    (is (nil? (api/mime-essence "not-a-mime")))
    (is (nil? (api/mime-essence "text/")))
    (is (nil? (api/mime-essence "/plain")))
    (is (nil? (api/mime-essence "a/b/c")))
    (is (nil? (api/mime-essence "text/plain junk")))
    (is (nil? (api/mime-essence "")))))

(deftest description-code-point-limit
  (testing "absent description is fine"
    (is (= {:value nil} (api/check-description nil))))
  (testing "at most 1000 code points accepted — counted as code points, not bytes"
    (let [thousand (apply str (repeat 1000 "😀"))] ;; 1000 code points, 4000 bytes
      (is (= {:value thousand} (api/check-description thousand))))
    (is (= {:value "x"} (api/check-description "x"))))
  (testing "1001 code points rejected (limit is 1000, not openapi's 1024)"
    (is (contains? (api/check-description (apply str (repeat 1001 "a"))) :error))
    (is (contains? (api/check-description (apply str (repeat 1001 "😀"))) :error)))
  (testing "non-strings rejected"
    (is (contains? (api/check-description 5) :error))))

(deftest ttl-bounds
  (testing "absent falls back to default_ttl_seconds"
    (is (= {:value 259200} (api/check-ttl cfg nil))))
  (testing "in-range values pass, including both boundaries"
    (is (= {:value 60} (api/check-ttl cfg 60)))
    (is (= {:value 31536000} (api/check-ttl cfg 31536000))))
  (testing "out of range -> error (never re-clamped)"
    (is (contains? (api/check-ttl cfg 59) :error))
    (is (contains? (api/check-ttl cfg 31536001) :error)))
  (testing "0 means never-expire and needs allow_never_expire"
    (is (contains? (api/check-ttl cfg 0) :error))
    (is (= {:value 0} (api/check-ttl (assoc cfg :allow_never_expire true) 0))))
  (testing "non-integers rejected"
    (is (contains? (api/check-ttl cfg "abc") :error))
    (is (contains? (api/check-ttl cfg 60.5) :error)))
  (testing "numeric strings (multipart form fields) are accepted"
    (is (= {:value 3600} (api/check-ttl cfg "3600")))))

(deftest content-length-gate
  (testing "missing Content-Length -> 411"
    (let [[status slug detail] (api/content-length-gate {} 100)]
      (is (= 411 status))
      (is (= "length-required" slug))
      (is (string? detail))))
  (testing "declared length within cap passes"
    (is (nil? (api/content-length-gate {:headers {"content-length" "100"}} 100)))
    (is (nil? (api/content-length-gate {:headers {"content-length" "5"}} 100))))
  (testing "repeated Content-Length (RFC 7230 comma-joined identical values)"
    (is (nil? (api/content-length-gate {:headers {"content-length" "5, 5"}} 100))))
  (testing "declared length over cap -> 413"
    (let [[status slug _] (api/content-length-gate
                            {:headers {"content-length" "101"}} 100)]
      (is (= 413 status))
      (is (= "payload-too-large" slug)))))

(deftest list-query-parsing
  (testing "defaults"
    (is (= {:limit 50 :include-expired? false}
           (:value (api/parse-list-query "")))))
  (testing "limit bounds"
    (is (= 500 (:limit (:value (api/parse-list-query "limit=500")))))
    (is (= 1 (:limit (:value (api/parse-list-query "limit=1")))))
    (is (contains? (api/parse-list-query "limit=501") :error))
    (is (contains? (api/parse-list-query "limit=0") :error))
    (is (contains? (api/parse-list-query "limit=abc") :error)))
  (testing "include_expired must be true or false"
    (is (true? (:include-expired? (:value (api/parse-list-query "include_expired=true")))))
    (is (false? (:include-expired? (:value (api/parse-list-query "include_expired=false")))))
    (is (contains? (api/parse-list-query "include_expired=yes") :error)))
  (testing "created range parses ISO-8601 instants, normalized to stored form"
    (is (= "2026-01-02T00:00:00.000000000Z"
           (:created-after (:value (api/parse-list-query "created_after=2026-01-02T00:00:00Z")))))
    (is (contains? (api/parse-list-query "created_after=yesterday") :error))
    (is (contains? (api/parse-list-query "created_before=2026-01-01") :error))
    (is (= "2026-01-01T00:00:00.000000000Z"
           (:created-before (:value (api/parse-list-query "created_before=2026-01-01T00:00:00Z"))))))
  (testing "whole-second filter values match fractional stored timestamps"
    ;; stored created_at has a 9-digit fraction; a raw whole-second value
    ;; ("...00Z") compares against "...00.500000000Z" with 'Z' > '.' and
    ;; silently drops boundary rows — parse-list-query must normalize first
    (let [dir (temp-dir)]
      (try
        (let [store (storage/open! dir)
              nm "k3v9qx2m4pta"
              at "2026-01-01T00:00:00.500000000Z"]
          (with-db-conn dir
            (fn [c]
              (jdbc/execute! c
                ["INSERT INTO items (name, description, mime_type, content_kind, size_bytes, checksum_sha256, storage_ref, ttl_seconds, created_at, expires_at, owner_key_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                 nm nil "text/plain" "text" 0 "abc" (str "blobs/aa/abc-" nm) nil at nil nil])))
          (testing "created_after at the whole second: row is after it, so included"
            (let [opts (:value (api/parse-list-query
                                 "created_after=2026-01-01T00:00:00Z&include_expired=true"))]
              (is (= [nm] (map :name (storage/list-items store opts))))))
          (testing "created_before at the whole second: row is after it, so excluded"
            (let [opts (:value (api/parse-list-query
                                 "created_before=2026-01-01T00:00:00Z&include_expired=true"))]
              (is (= [] (map :name (storage/list-items store opts))))))
          (storage/close-storage store))
        (finally (delete-recursively! dir)))))
  (testing "mime_prefix passes through"
    (is (= "text/" (:mime-prefix (:value (api/parse-list-query "mime_prefix=text%2F"))))))
  (testing "cursor roundtrips through the opaque encoding"
    (let [c {:created-at "2026-01-02T00:00:00.000000001Z" :name "k3v9qx2m4pta"}
          enc (api/encode-cursor c)]
      (is (string? enc))
      (is (not (re-find #"[|]" enc)))
      (is (= c (api/decode-cursor enc)))))
  (testing "invalid cursors rejected"
    (is (contains? (api/parse-list-query "cursor=not-base64!!") :error))
    (is (contains? (api/parse-list-query "cursor=aGk=") :error)) ;; "hi", no | parts
    (is (contains? (api/parse-list-query
                     (str "cursor="
                          (api/encode-cursor {:created-at "not-a-time"
                                              :name "k3v9qx2m4pta"})))
                   :error))
    (is (contains? (api/parse-list-query
                     (str "cursor="
                          (api/encode-cursor {:created-at "2026-01-02T00:00:00.000000001Z"
                                              :name "UPPER_case!!"})))
                   :error))))

(deftest if-none-match-semantics
  (is (api/if-none-match? "\"abc\"" "abc"))
  (is (api/if-none-match? "abc" "abc"))
  (is (api/if-none-match? "*" "abc"))
  (is (api/if-none-match? "\"zzz\", \"abc\"" "abc"))
  (is (not (api/if-none-match? "\"other\"" "abc")))
  (is (not (api/if-none-match? nil "abc"))))

(deftest serving-posture-q21
  (testing "text-ish kinds inline with CSP sandbox, except html/svg -> attachment"
    (is (= {"Content-Security-Policy" "sandbox"}
           (api/serving-headers "text/plain" "text")))
    (is (= {"Content-Security-Policy" "sandbox"}
           (api/serving-headers "text/x-python" "text")))
    (is (= {"Content-Security-Policy" "sandbox"}
           (api/serving-headers "application/json" "text")))
    (is (= {"Content-Disposition" "attachment"}
           (api/serving-headers "text/html" "text")))
    (is (= {"Content-Disposition" "attachment"}
           (api/serving-headers "image/svg+xml" "text")))
    (is (= {"Content-Disposition" "attachment"}
           (api/serving-headers "application/zip" "binary")))))

(deftest problem-shape-rfc7807
  (let [resp (api/problem 404 "not-found" "no such item")
        body (:body resp)]
    (is (= 404 (:status resp)))
    (is (= "application/problem+json" (get-in resp [:headers "Content-Type"])))
    (is (re-find #"\"type\":\"urn:metapaste:problem:not-found\"" body))
    (is (re-find #"\"title\":" body))
    (is (re-find #"\"status\":404" body))
    (is (re-find #"\"detail\":\"no such item\"" body))))

;; --- M4: expiry semantics regression (Q35-Q38) -------------------------------

(defn- handler-cfg [overrides]
  (merge {:min_ttl_seconds 60
          :max_ttl_seconds 31536000
          :default_ttl_seconds 259200
          :allow_never_expire false
          :path_prefix ""
          :dev_api_key "testkey"
          :max_item_bytes 65536}
         overrides))

(defn- make-test-handler [store cfg]
  (api/make-handler
    {:store store
     :config (handler-cfg cfg)
     :multipart-parser (fn [_] {:error {:status 400
                                        :slug "validation-failed"
                                        :detail "multipart not used here"}})}))

(defn- send-json
  "Ring call through make-handler; returns [status parsed-json-body]."
  [handler method uri body]
  (let [raw (.getBytes (json/write-str body :escape-slash false :escape-unicode false)
                       "UTF-8")
        resp (handler {:request-method method
                       :uri uri
                       :headers {"content-type" "application/json"
                                 "authorization" "Bearer testkey"
                                 "content-length" (str (alength raw))}
                       :body (ByteArrayInputStream. raw)})]
    [(:status resp) (json/read-str (:body resp) :key-fn keyword)]))

(deftest create-expires-at-is-created-at-plus-ttl
  (let [dir (temp-dir)]
    (try
      (let [store (storage/open! dir)]
        (try
          (let [handler (make-test-handler store {})
                t0 (Instant/now)
                [st m] (send-json handler :post "/api/v1/items"
                                  {:content "t" :mime_type "text/plain"
                                   :ttl_seconds 3600})
                created (Instant/parse (:created_at m))
                expires (Instant/parse (:expires_at m))]
            (is (= 201 st))
            (testing "expires_at = created_at + ttl within the 2 s tolerance"
              (is (<= 3598 (- (.getEpochSecond expires)
                              (.getEpochSecond created))
                      3602))
              (let [[st2 m2] (send-json handler :post "/api/v1/items"
                                        {:content "u" :mime_type "text/plain"
                                         :ttl_seconds 120})]
                (is (= 201 st2))
                (is (<= 118 (- (.getEpochSecond (Instant/parse (:expires_at m2)))
                               (.getEpochSecond (Instant/parse (:created_at m2))))
                        122))))
            (testing "and within 2 s of the server clock at create time"
              (is (<= -2 (- (.getEpochSecond expires)
                            (+ (.getEpochSecond t0) 3600))
                      2))))
          (finally (storage/close-storage store))))
      (finally (delete-recursively! dir)))))

(deftest patch-recomputes-expires-from-now-not-from-creation
  (let [dir (temp-dir)]
    (try
      (let [store (storage/open! dir)]
        (try
          (let [handler (make-test-handler store {})
                [st m] (send-json handler :post "/api/v1/items"
                                  {:content "t" :mime_type "text/plain"
                                   :ttl_seconds 3600})
                nm (:name m)]
            (is (= 201 st))
            ;; T+10min: backdate created_at so the two candidate formulas
            ;; differ by 600 s — created_at+ttl would land 600 s early
            (with-db-conn dir
              (fn [c]
                (jdbc/execute! c
                  ["UPDATE items SET created_at = ? WHERE name = ?"
                   (storage/iso-utc (.minusSeconds (Instant/now) 600)) nm])))
            (let [t-patch (Instant/now)
                  [pst pm] (send-json handler :patch (str "/api/v1/items/" nm)
                                      {:ttl_seconds 3600})
                  expires (Instant/parse (:expires_at pm))
                  created (Instant/parse (:created_at pm))]
              (is (= 200 pst) (pr-str pm))
              (testing "extension recomputes expires_at from now (Q35)"
                (is (<= -5 (- (.getEpochSecond expires)
                              (+ (.getEpochSecond t-patch) 3600))
                        5)))
              (testing "and not from creation"
                (is (>= (Math/abs (long (- (.getEpochSecond expires)
                                           (+ (.getEpochSecond created) 3600))))
                        500))))
            (testing "shortening is allowed"
              (let [[sst sm] (send-json handler :patch (str "/api/v1/items/" nm)
                                        {:ttl_seconds 60})]
                (is (= 200 sst))
                (is (= 60 (:ttl_seconds sm)))))
            (testing "out-of-bounds TTL is 400, never re-clamped (Q32/Q33)"
              (is (= 400 (first (send-json handler :patch (str "/api/v1/items/" nm)
                                           {:ttl_seconds 59}))))
              (is (= 400 (first (send-json handler :patch (str "/api/v1/items/" nm)
                                           {:ttl_seconds 31536001}))))))
          (finally (storage/close-storage store))))
      (finally (delete-recursively! dir)))))

(deftest ttl-zero-only-with-allow-never-expire
  (let [dir (temp-dir)]
    (try
      (let [store (storage/open! dir)]
        (try
          (testing "allow_never_expire=false: ttl 0 is 400 on create and on PATCH"
            (let [handler (make-test-handler store {})]
              (is (= 400 (first (send-json handler :post "/api/v1/items"
                                           {:content "x" :mime_type "text/plain"
                                            :ttl_seconds 0}))))
              (let [[st m] (send-json handler :post "/api/v1/items"
                                      {:content "x" :mime_type "text/plain"
                                       :ttl_seconds 3600})]
                (is (= 201 st))
                (is (= 400 (first (send-json handler :patch
                                             (str "/api/v1/items/" (:name m))
                                             {:ttl_seconds 0})))))))
          (testing "allow_never_expire=true: ttl 0 accepted, expires_at NULL"
            (let [handler (make-test-handler store {:allow_never_expire true})
                  [st m] (send-json handler :post "/api/v1/items"
                                    {:content "forever" :mime_type "text/plain"
                                     :ttl_seconds 0})]
              (is (= 201 st) (pr-str m))
              (is (nil? (:expires_at m)))
              ;; Spec Q37 also asks for stored ttl_seconds = 0 shown as "never".
              ;; Spec 3.2 (ttl_seconds INTEGER NULL) and the FROZEN
              ;; docs/openapi.yaml ItemMetadata.ttl_seconds ("null when the item
              ;; never expires") both say null, so null is what ships; the
              ;; conflict is reported in the M4 summary, not silently resolved
              ;; by editing a frozen contract.
              (is (nil? (:ttl_seconds m)))
              (let [[pst pm] (send-json handler :patch (str "/api/v1/items/" (:name m))
                                        {:ttl_seconds 0})]
                (is (= 200 pst) (pr-str pm))
                (is (nil? (:expires_at pm))))))
          (finally (storage/close-storage store))))
      (finally (delete-recursively! dir)))))
