(ns metapaste.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [jdbc.core :as jdbc]
            [metapaste.names :as names]
            [metapaste.storage :as storage])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp2-test-" (make-array FileAttribute 0))))

(defn- delete-recursively! [^File f]
  (doseq [c (reverse (file-seq f))]
    (.delete ^File c)))

(defmacro with-store [[store dir] & body]
  `(let [~dir (temp-dir)]
     (try
       (let [~store (storage/open! ~dir)]
         (try
           ~@body
           (finally (storage/close-storage ~store))))
       (finally (delete-recursively! ~dir)))))

(defn- db-url [dir]
  (str "sqlite:" (.getAbsolutePath (File. ^File dir "metapaste.db"))))

(defn- with-db-conn [dir f]
  (let [c (jdbc/connection (db-url dir))]
    (try
      (f c)
      (finally (.close c)))))

(deftest migrate-from-empty-then-remigrate-noop
  (let [dir (temp-dir)]
    (try
      (let [s (storage/open! dir)]
        (with-db-conn dir
          (fn [c]
            (let [tables (set (map :name (jdbc/fetch c
                          ["SELECT name FROM sqlite_master WHERE type='table'"])))]
              (is (contains? tables "items"))
              (is (contains? tables "api_keys"))
              (is (contains? tables "schema_migrations")))
            (is (= [1] (map :id (jdbc/fetch c ["SELECT id FROM schema_migrations ORDER BY id"]))))
            (let [items-cols (set (map :name (jdbc/fetch c ["PRAGMA table_info(items)"])))]
              (is (= #{"name" "description" "mime_type" "content_kind" "size_bytes"
                       "checksum_sha256" "storage_ref" "ttl_seconds" "created_at"
                       "expires_at" "owner_key_id"}
                     items-cols)))
            (let [keys-cols (set (map :name (jdbc/fetch c ["PRAGMA table_info(api_keys)"])))]
              (is (= #{"key_id" "key_hash" "label" "scopes" "created_at" "revoked_at"}
                     keys-cols)))))
        (storage/close-storage s))
      ;; re-run: no-op, still exactly one recorded version, schema unchanged
      (let [s (storage/open! dir)]
        (with-db-conn dir
          (fn [c]
            (is (= [1] (map :id (jdbc/fetch c ["SELECT id FROM schema_migrations ORDER BY id"]))))
            (let [items-cols (set (map :name (jdbc/fetch c ["PRAGMA table_info(items)"])))]
              (is (contains? items-cols "storage_ref")))))
        (storage/close-storage s))
      (finally (delete-recursively! dir)))))

(deftest crud-roundtrip
  (with-store [store dir]
    (let [content (.getBytes "hello world" "UTF-8")
          created (storage/create-item! store {:content content
                                               :mime_type "text/plain"
                                               :description "greeting"
                                               :ttl_seconds 0
                                               :owner_key_id "key-1"})
          sha (storage/sha256-hex content)]
      (is (= 12 (count (:name created))))
      (is (= (:name created) (names/normalize (:name created))))
      (is (= "greeting" (:description created)))
      (is (= "text/plain" (:mime_type created)))
      (is (= "text" (:content_kind created)))
      (is (= 11 (long (:size_bytes created))))
      (is (= sha (:checksum_sha256 created)))
      (is (nil? (:ttl_seconds created)))
      (is (nil? (:expires_at created)))
      (is (re-matches #"\d{4}-\d{2}-\d{2}T[0-9:.]+Z" (:created_at created)))
      (is (= (str "blobs/" (subs sha 0 2) "/" sha "-" (:name created))
             (:storage_ref created)))
      ;; get: case-insensitive, exact roundtrip of the stored row
      (is (= created (storage/get-item store (:name created))))
      (is (= created (storage/get-item store (str/upper-case (:name created)))))
      (is (nil? (storage/get-item store "zzzzzzzzzzzz")))
      ;; blob behind the row
      (is (= sha (storage/sha256-hex (storage/get-blob store (:storage_ref created)))))
      ;; update: partial patch, derived content_kind, expires_at recomputed
      (let [patched (storage/update-item! store (:name created)
                                          {:description "new desc"
                                           :mime_type "text/x-python"
                                           :ttl_seconds 3600})]
        (is (= "new desc" (:description patched)))
        (is (= "text/x-python" (:mime_type patched)))
        (is (= "text" (:content_kind patched)))
        (is (= 3600 (long (:ttl_seconds patched))))
        (let [delta (- (.getEpochSecond (Instant/parse (:expires_at patched)))
                       (.getEpochSecond (Instant/now)))]
          (is (<= 3590 delta 3610))))
      ;; ttl back to 0/NULL clears expires_at
      (let [cleared (storage/update-item! store (:name created) {:ttl_seconds 0})]
        (is (nil? (:expires_at cleared))))
      (is (nil? (storage/update-item! store "zzzzzzzzzzzz" {:description "x"})))
      ;; delete: idempotent, row and blob both gone
      (let [ref (:storage_ref created)]
        (is (true? (storage/delete-item! store (:name created))))
        (is (nil? (storage/get-item store (:name created))))
        (is (false? (storage/delete-item! store (:name created))))
        (is (nil? (storage/get-blob store ref)))))))

(deftest ttl-nil-means-never-expires
  (with-store [store dir]
    (let [it (storage/create-item! store {:content (byte-array 0)
                                          :mime_type "application/octet-stream"})]
      (is (nil? (:ttl_seconds it)))
      (is (nil? (:expires_at it))))))

(deftest binary-blob-roundtrip-4mib
  (with-store [store dir]
    (let [content (byte-array (* 4 1024 1024) (byte 7))
          sha (storage/sha256-hex content)
          it (storage/create-item! store {:content content
                                          :mime_type "application/octet-stream"
                                          :content_kind "binary"})]
      (is (= (* 4 1024 1024) (long (:size_bytes it))))
      (is (= sha (:checksum_sha256 it)))
      (let [back (storage/get-blob store (:storage_ref it))]
        (is (= (* 4 1024 1024) (alength back)))
        (is (= sha (storage/sha256-hex back)))
        (is (= sha (:checksum_sha256 it))))
      ;; Q42: no dedup — second identical item gets its own blob path
      (let [it2 (storage/create-item! store {:content content
                                             :mime_type "application/octet-stream"
                                             :content_kind "binary"})]
        (is (not= (:storage_ref it) (:storage_ref it2)))
        (storage/delete-item! store (:name it))
        (is (some? (storage/get-blob store (:storage_ref it2))))
        (is (= sha (storage/sha256-hex (storage/get-blob store (:storage_ref it2)))))))))

(deftest blob-put-get-delete-idempotent
  (with-store [store dir]
    (let [content (.getBytes "blob \u0000 content" "UTF-8")
          ref (storage/put-blob! store "aaaaaaaaaaaa" content)]
      (is (re-matches #"blobs/[0-9a-f]{2}/[0-9a-f]{64}-[0-9a-z]+" ref))
      (is (= (storage/sha256-hex content)
             (storage/sha256-hex (storage/get-blob store ref))))
      (is (true? (storage/delete-blob! store ref)))
      (is (true? (storage/delete-blob! store ref)))
      (is (nil? (storage/get-blob store ref))))
    ;; traversal-shaped refs are ignored by readers/deleters, rejected by writers
    (is (nil? (storage/get-blob store "blobs/../../etc/passwd")))
    (is (true? (storage/delete-blob! store "blobs/../../etc/passwd")))
    (is (thrown? Exception (storage/put-blob! store "../../evil" (.getBytes "x" "UTF-8"))))))

(deftest thousand-forced-same-name-inserts
  (with-store [store dir]
    (let [forced "k3v9qx2m4pta"
          calls (atom 0)]
      (with-redefs [names/random-name (fn [] (swap! calls inc) forced)]
        (let [first-item (storage/create-item! store {:content (.getBytes "one" "UTF-8")
                                                      :mime_type "text/plain"})]
          (is (= forced (:name first-item)))
          (let [errs (doall
                      (for [_ (range 999)]
                        (try
                          (storage/create-item! store {:content (.getBytes "x" "UTF-8")
                                                       :mime_type "text/plain"})
                          nil
                          (catch Throwable t t))))]
            (is (every? some? errs))
            (is (every? #(= :metapaste.names/exhausted (:type (ex-data %))) errs))))
        ;; 1 initial + 999 * (1 attempt + 5 retries)
        (is (= (inc (* 999 6)) @calls)))
      (is (= 1 (count (storage/list-items store {:include-expired? true})))))))

(deftest create-retries-with-fresh-name-on-collision
  (with-store [store dir]
      (let [script (atom ["k3v9qx2m4pta" "k3v9qx2m4pta" "b7hjm2npq3vw"])]
      (with-redefs [names/random-name
                    (fn []
                      (let [n (first @script)]
                        (swap! script subvec 1)
                        n))]
        (let [a (storage/create-item! store {:content (.getBytes "a" "UTF-8")
                                             :mime_type "text/plain"})
              b (storage/create-item! store {:content (.getBytes "b" "UTF-8")
                                             :mime_type "text/plain"})]
          (is (= "k3v9qx2m4pta" (:name a)))
          (is (= "b7hjm2npq3vw" (:name b)))
          (is (empty? @script)))))))

(deftest persistence-across-close-and-reopen
  (let [dir (temp-dir)
        snapshot (atom nil)]
    (try
      (let [s (storage/open! dir)
            it (storage/create-item! s {:content (.getBytes "persist me" "UTF-8")
                                        :mime_type "text/plain"
                                        :description "d"
                                        :ttl_seconds 3600
                                        :owner_key_id "k"})]
        (reset! snapshot it)
        (storage/close-storage s))
      (let [s (storage/open! dir)
            it2 (storage/get-item s (:name @snapshot))]
        (is (= @snapshot it2))
        (is (= (storage/sha256-hex (.getBytes "persist me" "UTF-8"))
               (storage/sha256-hex (storage/get-blob s (:storage_ref it2)))))
        (storage/close-storage s))
      (finally (delete-recursively! dir)))))

(deftest expired-rows-filtered-from-reads
  (with-store [store dir]
    (let [live (storage/create-item! store {:content (.getBytes "live" "UTF-8")
                                            :mime_type "text/plain"
                                            :ttl_seconds 3600})
          exp (storage/create-item! store {:content (.getBytes "e1" "UTF-8")
                                           :mime_type "text/plain"
                                           :ttl_seconds -5})
          exp2 (storage/create-item! store {:content (.getBytes "e2" "UTF-8")
                                            :mime_type "text/plain"
                                            :ttl_seconds -4})]
      (is (some? (storage/get-item store (:name live))))
      (is (nil? (storage/get-item store (:name exp))))
      (is (nil? (storage/get-item store (str/upper-case (:name exp2)))))
      (is (= [(:name live)] (map :name (storage/list-items store {}))))
      (is (= #{(:name live) (:name exp) (:name exp2)}
             (set (map :name (storage/list-items store {:include-expired? true})))))
      (is (nil? (storage/update-item! store (:name exp) {:description "z"})))
      (is (false? (storage/delete-item! store (:name exp))))
      (let [st (storage/stats store)]
        (is (= 1 (:items-live st)))
        (is (= 2 (:items-expired st))))
      ;; delete-expired-batch (M4 entry point): limit honored, oldest first, blobs removed
      (let [batch (storage/delete-expired-batch store 1)]
        (is (= [(:name exp)] batch))
        (is (nil? (storage/get-blob store (:storage_ref exp))))
        (is (some? (storage/get-blob store (:storage_ref exp2)))))
      (is (= [(:name exp2)] (storage/delete-expired-batch store 10)))
      (is (nil? (storage/get-blob store (:storage_ref exp2))))
      (is (= [] (storage/delete-expired-batch store 10)))
      (is (= 0 (:items-expired (storage/stats store))))
      (is (= 1 (count (storage/list-items store {})))))))

(deftest stats-counts
  (with-store [store dir]
    (storage/create-item! store {:content (byte-array 5) :mime_type "text/plain"
                                 :content_kind "text" :ttl_seconds 0})
    (storage/create-item! store {:content (byte-array 7) :mime_type "text/plain"
                                 :content_kind "text" :ttl_seconds 3600})
    (storage/create-item! store {:content (byte-array 3) :mime_type "text/plain"
                                 :content_kind "text" :ttl_seconds -5})
    (let [st (storage/stats store)]
      (is (= 2 (:items-live st)))
      (is (= 1 (:items-expired st)))
      (is (= 15 (long (:bytes-total st))))
      (is (zero? (long (:api-keys st))))
      (is (string? (:oldest-created st)))
      (is (string? (:newest-created st))))))
