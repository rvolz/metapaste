(ns metapaste.api-test
  "Unit tests for the pure request/response logic in metapaste.api: mime/desc/
   ttl validation, list query parsing, ETag matching, serving posture, and the
   problem+json shape. End-to-end behavior lives in http-integration-test."
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [metapaste.api :as api]
            [metapaste.storage :as storage])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

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
