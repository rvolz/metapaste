(ns metapaste.storage
  "Storage layer: SQLite metadata plus filesystem blobs behind a protocol.
   Nothing outside this namespace knows SQLite or the on-disk layout."
  (:require [db.jdbc] ;; must load before jdbc.core so the java.sql shim registers
            [jdbc.core :as jdbc]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [metapaste.names :as names])
  (:import [java.nio.file Files]
           [java.security MessageDigest]
           [java.time Instant]))

;; --- timestamps --------------------------------------------------------------

(defn- iso-utc
  "ISO-8601 UTC with a fixed 9-digit fraction, so lexicographic ordering of
   stored strings matches chronological ordering."
  [^Instant t]
  (let [base (str (Instant/ofEpochSecond (.getEpochSecond t)))
        n (str (.getNano t))
        padded (str (apply str (repeat (- 9 (count n)) \0)) n)]
    (str (subs base 0 (dec (count base))) "." padded "Z")))

;; --- checksum ----------------------------------------------------------------

(defn sha256-hex
  "Lowercase hex SHA-256 of a byte array (integrity checks, future ETag)."
  [^bytes content]
  (let [d (.digest (MessageDigest/getInstance "SHA-256") content)
        sb (StringBuilder. (* 2 (alength d)))]
    (doseq [b d]
      (let [v (bit-and (int b) 0xff)]
        (.append sb (.charAt "0123456789abcdef" (quot v 16)))
        (.append sb (.charAt "0123456789abcdef" (rem v 16)))))
    (str sb)))

;; --- mime type -> content kind (Q7) ------------------------------------------

(defn- content-kind [mime]
  (let [m (or mime "")]
    (if (or (str/starts-with? m "text/")
            (#{"application/json" "application/xml" "application/javascript"} m)
            (str/ends-with? m "+json")
            (str/ends-with? m "+xml"))
      "text"
      "binary")))

;; --- ttl -> (ttl_seconds, expires_at) ----------------------------------------

(defn- ttl-fields
  "ttl nil or 0 never expires. Anything else (including a negative ttl, which
   this layer allows and which yields an already-expired row) expires at
   now + ttl, computed from the given instant."
  [^Instant now ttl]
  (if (or (nil? ttl) (zero? (long ttl)))
    [nil nil]
    [(long ttl) (iso-utc (.plusSeconds now (long ttl)))]))

;; --- blob paths --------------------------------------------------------------

(def ^:private blob-name-re (re-pattern (str "[" names/alphabet "]{12}")))

(def ^:private blob-ref-re
  (re-pattern (str "blobs/[0-9a-f]{2}/[0-9a-f]{64}-[" names/alphabet "]{12}")))

(defn- blob-ref [sha nm]
  (str "blobs/" (subs sha 0 2) "/" sha "-" nm))

(defn- valid-blob-name? [nm]
  (and (string? nm) (boolean (re-matches blob-name-re nm))))

(defn- valid-blob-ref? [ref]
  (and (string? ref) (boolean (re-matches blob-ref-re ref))))

(defn- write-blob-file! [dir ref ^bytes content]
  (let [f (io/file dir ref)]
    (io/make-parents f)
    (with-open [out ^java.io.OutputStream (io/output-stream f)]
      (.write out content))))

(defn- delete-blob-file! [dir ref]
  (.delete (io/file dir ref)))

;; --- connections and write transactions --------------------------------------

(defn- busy? [e]
  (and (instance? java.sql.SQLException e)
       (let [m (or (ex-message e) "")]
         (or (str/includes? m "database is locked")
             (str/includes? m "SQLITE_BUSY")))))

(def ^:private write-retries 3)

(defn- with-write-tx
  "Run f inside BEGIN IMMEDIATE ... COMMIT (rollback on any throw). The driver
   has no SQLITE_BUSY retry of its own beyond busy_timeout, so busy failures
   are retried a small, bounded number of times at this level."
  [conn f]
  (loop [retries write-retries]
    (let [outcome (try
                    (jdbc/execute! conn ["BEGIN IMMEDIATE"])
                    (try
                      (let [v (f)]
                        (jdbc/execute! conn ["COMMIT"])
                        [:done v])
                      (catch Throwable t
                        (try (jdbc/execute! conn ["ROLLBACK"]) (catch Throwable _ nil))
                        (throw t)))
                    (catch Throwable t
                      (if (and (busy? t) (pos? retries))
                        [:retry]
                        (throw t))))]
      (if (= :retry (first outcome))
        (recur (dec retries))
        (second outcome)))))

;; --- migrations (Q44): numbered, embedded, forward-only ----------------------

(def ^:private migrations
  [{:id 1 :resource "migrations/001-init.sql"}])

(defn- split-statements [sql]
  (->> (str/split sql #";")
       (map str/trim)
       (remove str/blank?)))

(defn- apply-migrations! [conn]
  (jdbc/execute! conn ["CREATE TABLE IF NOT EXISTS schema_migrations (id INTEGER PRIMARY KEY)"])
  (let [applied (set (map :id (jdbc/fetch conn ["SELECT id FROM schema_migrations"])))]
    (doseq [{:keys [id resource]} migrations
            :when (not (contains? applied id))]
      (let [url (or (io/resource resource)
                    (throw (ex-info "migration resource missing" {:resource resource})))]
        (with-write-tx conn
          (fn []
            (doseq [stmt (split-statements (slurp url))]
              (jdbc/execute! conn [stmt]))
            (jdbc/execute! conn ["INSERT INTO schema_migrations (id) VALUES (?)" id])))))))

(defn- name-taken? [e]
  (and (instance? java.sql.SQLException e)
       (str/includes? (or (ex-message e) "") "UNIQUE constraint failed")))

;; --- protocol ----------------------------------------------------------------

(defprotocol Storage
  (create-item! [store opts]
    "Insert an item from {:content bytes :mime_type s ...} plus optional
     :description, :content_kind, :ttl_seconds, :owner_key_id; returns the row.")
  (get-item [store name]
    "Row map for a case-insensitive name, nil when unknown or expired.")
  (update-item! [store name patch]
    "Apply a partial patch (:description, :mime_type, :ttl_seconds); nil when
     the row is unknown or expired.")
  (delete-item! [store name]
    "Delete a live row and its blob; true only when a live row was removed.")
  (list-items [store opts]
    "Item rows, expired hidden unless {:include-expired? true}.")
  (stats [store]
    "Counts/sums for the metrics surface.")
  (delete-expired-batch [store limit]
    "Delete up to limit expired rows, oldest first; returns deleted names.")
  (put-blob! [store name content]
    "Write bytes to a fresh content-addressed blob; returns its storage_ref.")
  (get-blob [store ref]
    "Blob bytes for a storage_ref, nil when absent or ref-shaped invalid.")
  (delete-blob! [store ref]
    "Remove a blob; always true, even for invalid or already-gone refs."))

;; --- SQLite + filesystem implementation --------------------------------------

(defrecord SqliteStore [conn dir]
  Storage
  (create-item! [this opts]
    (let [content (:content opts)
          mime (:mime_type opts)
          kind (or (:content_kind opts) (content-kind mime))
          sha (sha256-hex content)]
      (names/unique-name
        (fn [raw]
          (let [nm (names/normalize raw)
                ref (put-blob! this nm content)]
            (try
              (with-write-tx conn
                (fn []
                  (let [now (Instant/now)
                        [ttl expires] (ttl-fields now (:ttl_seconds opts))]
                    (jdbc/execute! conn
                      ["INSERT INTO items (name, description, mime_type, content_kind, size_bytes, checksum_sha256, storage_ref, ttl_seconds, created_at, expires_at, owner_key_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
                       nm (:description opts) mime kind (alength content) sha ref
                       ttl (iso-utc now) expires (:owner_key_id opts)])
                    (jdbc/fetch-one conn ["SELECT * FROM items WHERE name = ?" nm]))))
              (catch Throwable t
                (delete-blob-file! dir ref)
                (if (name-taken? t)
                  (throw (names/taken "name already used"))
                  (throw t)))))))))

  (get-item [_ nm]
    (jdbc/fetch-one conn
      ["SELECT * FROM items WHERE name = ? AND (expires_at IS NULL OR expires_at > ?)"
       (names/normalize nm) (iso-utc (Instant/now))]))

  (update-item! [_ nm patch]
    (with-write-tx conn
      (fn []
        (let [now (Instant/now)
              n (names/normalize nm)
              row (jdbc/fetch-one conn
                    ["SELECT * FROM items WHERE name = ? AND (expires_at IS NULL OR expires_at > ?)"
                     n (iso-utc now)])]
          (when row
            (let [mime (if (contains? patch :mime_type) (:mime_type patch) (:mime_type row))
                  kind (if (contains? patch :mime_type)
                         (content-kind (:mime_type patch))
                         (:content_kind row))
                  desc (if (contains? patch :description) (:description patch) (:description row))
                  [ttl expires] (if (contains? patch :ttl_seconds)
                                  (ttl-fields now (:ttl_seconds patch))
                                  [(:ttl_seconds row) (:expires_at row)])]
              (jdbc/execute! conn
                ["UPDATE items SET description = ?, mime_type = ?, content_kind = ?, ttl_seconds = ?, expires_at = ? WHERE name = ?"
                 desc mime kind ttl expires n])
              (jdbc/fetch-one conn ["SELECT * FROM items WHERE name = ?" n])))))))

  (delete-item! [_ nm]
    (let [n (names/normalize nm)
          now (iso-utc (Instant/now))
          ref (with-write-tx conn
                (fn []
                  (when-let [row (jdbc/fetch-one conn
                                   ["SELECT storage_ref FROM items WHERE name = ? AND (expires_at IS NULL OR expires_at > ?)"
                                    n now])]
                    (when (pos? (long (jdbc/execute! conn
                                       ["DELETE FROM items WHERE name = ? AND (expires_at IS NULL OR expires_at > ?)"
                                        n now])))
                      (:storage_ref row)))))]
      (when ref (delete-blob-file! dir ref))
      (boolean ref)))

  (list-items [_ opts]
    (if (:include-expired? opts)
      (jdbc/fetch conn ["SELECT * FROM items ORDER BY created_at, rowid"])
      (jdbc/fetch conn
        ["SELECT * FROM items WHERE expires_at IS NULL OR expires_at > ? ORDER BY created_at, rowid"
         (iso-utc (Instant/now))])))

  (stats [_]
    (let [now (iso-utc (Instant/now))
          a (jdbc/fetch-one conn
              ["SELECT
                 COALESCE(SUM(CASE WHEN expires_at IS NULL OR expires_at > ? THEN 1 ELSE 0 END), 0) AS items_live,
                 COALESCE(SUM(CASE WHEN expires_at IS NOT NULL AND expires_at <= ? THEN 1 ELSE 0 END), 0) AS items_expired,
                 COALESCE(SUM(size_bytes), 0) AS bytes_total,
                 MIN(created_at) AS oldest_created,
                 MAX(created_at) AS newest_created
               FROM items"
               now now])
          k (jdbc/fetch-one conn ["SELECT COUNT(*) AS api_keys FROM api_keys"])]
      {:items-live (:items_live a)
       :items-expired (:items_expired a)
       :bytes-total (:bytes_total a)
       :api-keys (:api_keys k)
       :oldest-created (:oldest_created a)
       :newest-created (:newest_created a)}))

  (delete-expired-batch [_ limit]
    (let [now (iso-utc (Instant/now))
          deleted (with-write-tx conn
                    (fn []
                      (->> (jdbc/fetch conn
                             ["SELECT name, storage_ref FROM items
                               WHERE expires_at IS NOT NULL AND expires_at <= ?
                               ORDER BY created_at, rowid LIMIT ?"
                              now limit])
                           (into []
                                 (keep (fn [r]
                                         (when (pos? (long (jdbc/execute! conn
                                                            ["DELETE FROM items WHERE name = ? AND expires_at IS NOT NULL AND expires_at <= ?"
                                                             (:name r) now])))
                                           [(:name r) (:storage_ref r)])))))))]
      (doseq [[_ ref] deleted] (delete-blob-file! dir ref))
      (mapv first deleted)))

  (put-blob! [_ nm content]
    (when-not (valid-blob-name? nm)
      (throw (ex-info "invalid blob name" {:name nm})))
    (let [ref (blob-ref (sha256-hex content) nm)]
      (write-blob-file! dir ref content)
      ref))

  (get-blob [_ ref]
    (when (valid-blob-ref? ref)
      (let [f (io/file dir ref)]
        (when (.isFile f)
          (Files/readAllBytes (.toPath f))))))

  (delete-blob! [_ ref]
    (when (valid-blob-ref? ref)
      (delete-blob-file! dir ref))
    true))

;; --- lifecycle ---------------------------------------------------------------

(defn open!
  "Open the store rooted at dir (created if absent): applies pending
   migrations — re-running against an already-migrated database is a no-op —
   with WAL and a busy timeout, and returns a store."
  [dir]
  (let [d (io/file dir)]
    (.mkdirs d)
    (let [conn (jdbc/connection (str "sqlite:" (.getAbsolutePath (io/file d "metapaste.db"))))]
      (jdbc/fetch-one conn ["PRAGMA journal_mode=WAL"])
      (jdbc/fetch-one conn ["PRAGMA busy_timeout=5000"])
      (apply-migrations! conn)
      (->SqliteStore conn d))))

(defn close-storage [store]
  (.close (:conn store)))
