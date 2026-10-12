(ns metapaste.cleanup-test
  "Tests for the TTL cleanup worker (M4): batch drain, orphan sweep, jittered
   interval loop with an idempotent start/stop, counters, and the no-race
   acceptance stress test. No test here waits on a wall-clock TTL."
  (:require [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [metapaste.cleanup :as cleanup]
            [metapaste.storage :as storage])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp4-cln-" (make-array FileAttribute 0))))

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

(defn- with-db-conn [dir f]
  (let [c (jdbc/connection (str "sqlite:" (.getAbsolutePath (File. ^File dir "metapaste.db"))))]
    (try
      (f c)
      (finally (.close c)))))

(defn- plant-expired!
  "Direct parameterized inserts of already-expired rows with real blob files,
   so no test ever sleeps for a TTL. Returns the number planted."
  [store n]
  (let [conn (:conn store)
        dir (:dir store)]
    (dotimes [i n]
      (let [nm (format "e%011d" i)
            sha (storage/sha256-hex (.getBytes (str "plant-" nm) "UTF-8"))
            ref (str "blobs/" (subs sha 0 2) "/" sha "-" nm)
            f (File. ^File dir ref)]
        (.mkdirs (.getParentFile f))
        (with-open [out (java.io.FileOutputStream. f)]
          (.write out (.getBytes (str "plant-" nm) "UTF-8")))
        (jdbc/execute! conn
          ["INSERT INTO items (name, description, mime_type, content_kind, size_bytes, checksum_sha256, storage_ref, ttl_seconds, created_at, expires_at, owner_key_id) VALUES (?,?,?,?,?,?,?,?,?,?,?)"
           nm nil "text/plain" "text" 0 sha ref -5
           (storage/iso-utc (Instant/now))
           (storage/iso-utc (.minusSeconds (Instant/now) 60)) nil])))
    n))

(defn- write-orphan!
  "An unreferenced blob file of valid ref shape at path dir/ref."
  [dir ref]
  (let [f (File. ^File dir ref)]
    (.mkdirs (.getParentFile f))
    (with-open [out (java.io.FileOutputStream. f)]
      (.write out (.getBytes "orphan" "UTF-8")))
    f))

(defn- backdate! [^File f seconds]
  (.setLastModified f (* 1000 (- (.getEpochSecond (Instant/now)) seconds)))
  f)

(defn- await-until
  "Poll pred every 20 ms until it holds or timeout-ms elapses. Bounded awaits
   keep the worker tests fast instead of sleeping for whole intervals."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) (long timeout-ms))]
    (loop []
      (cond
        (pred) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

;; --- delta 3: jitter ---------------------------------------------------------

(deftest next-delay-ms-jitter-bounds
  (testing "bounds are exactly interval ±10%, driven by the injected rng"
    (is (= 900 (cleanup/next-delay-ms 1000 (constantly 0.0))))
    (is (= 1000 (cleanup/next-delay-ms 1000 (constantly 0.5))))
    (is (= 1099 (cleanup/next-delay-ms 1000 (constantly 0.999999)))))
  (testing "every draw in [0,1) lands in [0.9i, 1.1i)"
    (doseq [r (map #(/ % 100.0) (range 100))]
      (let [d (cleanup/next-delay-ms 10000 (constantly r))]
        (is (<= 9000 d 11000) (str "rng=" r " -> " d)))))
  (testing "non-uniform rng values are still bounded"
    (let [draws (atom [0.0 0.13 0.37 0.5 0.71 0.99 0.999999])
          rng (fn [] (let [v (first @draws)] (swap! draws subvec 1) v))]
      (doseq [_ (range 7)]
        (is (<= 900 (cleanup/next-delay-ms 1000 rng) 1099))))))

;; --- delta 5: counters -------------------------------------------------------

(deftest counters-have-exact-key-set
  (is (= #{:runs :expired_deleted_total :deleted_rows_total
           :duration_ms_total :orphans_swept_total :last_cleanup_timestamp}
         (set (keys (cleanup/counters)))))
  (testing "nil before the first completed pass"
    (let [initial @(ns-resolve 'metapaste.cleanup 'initial-counters)]
      (is (nil? (:last_cleanup_timestamp initial)))))
  (testing "set once a pass has completed"
    (with-store [store dir]
      (cleanup/run-cleanup-once! store)
      (is (integer? (long (:last_cleanup_timestamp (cleanup/counters))))))))

;; --- run-cleanup-once! -------------------------------------------------------

(deftest run-cleanup-once-drains-in-batches
  (with-store [store dir]
    (let [never (storage/create-item! store {:content (.getBytes "keep" "UTF-8")
                                             :mime_type "text/plain"})
          planted 1050
          _ (plant-expired! store planted)
          before (cleanup/counters)
          r (cleanup/run-cleanup-once! store {:grace-s 0})]
      (testing "returns the documented shape"
        (is (= #{:deleted :orphans :duration-ms} (set (keys r))))
        (is (= planted (long (:deleted r))))
        (is (zero? (long (:orphans r))))
        (is (integer? (long (:duration-ms r)))))
      (testing "the never-expire row survives and is still readable"
        (is (some? (storage/get-item store (:name never))))
        (is (some? (storage/get-blob store (:storage_ref never)))))
      (testing "every expired blob is gone from the directory listing"
        (is (= [(:storage_ref never)]
               (mapv :ref (storage/list-blob-files store)))))
      (testing "expired rows are gone, never-expire rows are not"
        (is (= 0 (:items-expired (storage/stats store))))
        (is (= 1 (:items-live (storage/stats store)))))
      (testing "second pass deletes nothing"
        (let [r2 (cleanup/run-cleanup-once! store {:grace-s 0})]
          (is (zero? (long (:deleted r2))))
          (is (zero? (long (:orphans r2))))
          (testing "counters moved by exactly what happened"
            (let [after (cleanup/counters)]
              (is (= 2 (- (long (:runs after)) (long (:runs before)))))
              (is (= planted (- (long (:expired_deleted_total after))
                                (long (:expired_deleted_total before)))))
              (is (= planted (- (long (:deleted_rows_total after))
                                (long (:deleted_rows_total before)))))
              (is (= (+ (long (:duration-ms r)) (long (:duration-ms r2)))
                     (- (long (:duration_ms_total after))
                        (long (:duration_ms_total before))))
                  "duration total grows by the passes' own durations")
              (is (integer? (long (:last_cleanup_timestamp after)))))))))))

(deftest stop-signal-caps-pass-at-one-batch
  (with-store [store dir]
    (plant-expired! store 700)
    (let [orphan (write-orphan! dir "blobs/ff/ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff-aaaaaaaaaaaa")
          _ (backdate! orphan 600)
          ;; the signal lands while batch 1 is in flight: the drain checks it
          ;; before starting each batch, so exactly one more batch runs
          checks (atom 0)
          r (cleanup/run-cleanup-once! store
                                       {:grace-s 0
                                        :stop? (fn [] (>= (swap! checks inc) 2))})]
      (is (= 500 (long (:deleted r))) "exactly one 500-row batch after the signal")
      (is (zero? (long (:orphans r))) "sweep skipped once stopped")
      (is (.exists orphan))
      (is (= 200 (:items-expired (storage/stats store))) "the rest wait for the next pass"))
    (testing "a signal already present starts no batch at all"
      (let [r (cleanup/run-cleanup-once! store {:grace-s 0 :stop? (constantly true)})]
        (is (zero? (long (:deleted r))))
        (is (zero? (long (:orphans r))))))
    (testing "a pass without the signal finishes the drain"
      (let [r (cleanup/run-cleanup-once! store {:grace-s 0})]
        (is (= 200 (long (:deleted r))))
        (is (zero? (:items-expired (storage/stats store))))))))

;; --- orphan sweep ------------------------------------------------------------

(deftest orphan-sweep-removes-unreferenced-keeps-referenced
  (with-store [store dir]
    (let [live (storage/create-item! store {:content (.getBytes "live" "UTF-8")
                                            :mime_type "text/plain"})
          orphan (write-orphan! dir "blobs/aa/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-aaaaaaaaaaaa")
          _ (backdate! orphan 600)]
      (testing "grace 0: unreferenced goes, referenced survives"
        (let [r (cleanup/run-cleanup-once! store {:grace-s 0})]
          (is (= 1 (long (:orphans r))))
          (is (not (.exists orphan)))
          (is (some? (storage/get-item store (:name live))))
          (is (some? (storage/get-blob store (:storage_ref live))))))
      (testing "grace gates on file age"
        (let [fresh (write-orphan! dir "blobs/bb/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb-bbbbbbbbbbbb")]
          (is (zero? (long (:orphans (cleanup/run-cleanup-once! store)))) "fresh file inside the default 300 s grace")
          (is (.exists fresh))
          (is (= 1 (long (:orphans (cleanup/run-cleanup-once!
                                     store {:now-fn (fn [] (.plusSeconds (Instant/now) 600))}))))
              "injected now-fn puts the same file past the grace window")
          (is (not (.exists fresh))))))))

(deftest orphan-sweep-never-takes-an-in-flight-blob
  (with-store [store dir]
    (let [v (ns-resolve 'metapaste.storage 'write-blob-file!)
          orig @v
          passes (atom [])]
      ;; run a grace-0 sweep at the exact moment the blob file becomes visible:
      ;; no items row exists yet, so the in-flight registry is the only gate
      (with-redefs-fn
        {v (fn [d ref content]
             (orig d ref content)
             (swap! passes conj (cleanup/run-cleanup-once! store {:grace-s 0})))}
        (fn []
          (let [it (storage/create-item! store {:content (.getBytes "inflight" "UTF-8")
                                                :mime_type "text/plain"})]
            (testing "the sweep run mid-create removed nothing"
              (is (= 1 (count @passes)))
              (is (zero? (long (:orphans (first @passes)))))
              (is (some? (storage/get-blob store (:storage_ref it)))))
            (testing "once the row commits it is held by the referenced check"
              (let [r (cleanup/run-cleanup-once! store {:grace-s 0})]
                (is (zero? (long (:orphans r))))
                (is (some? (storage/get-item store (:name it))))))
            (testing "control: with the row gone and grace 0 the same file is swept"
              (with-db-conn dir
                (fn [c]
                  (jdbc/execute! c ["DELETE FROM items WHERE name = ?" (:name it)])))
              (let [r (cleanup/run-cleanup-once! store {:grace-s 0})]
                (is (= 1 (long (:orphans r))))
                (is (empty? (storage/list-blob-files store)))))))))))

;; --- interval worker ---------------------------------------------------------

(deftest start-stop-worker-is-idempotent-and-leaves-no-thread
  (with-store [store dir]
    (let [runs0 (:runs (cleanup/counters))
          w1 (cleanup/start! store {:interval-s 1 :grace-s 0})]
      (testing "start! twice returns the one live worker"
        (let [w2 (cleanup/start! store {:interval-s 1})]
          (is (identical? (:thread w1) (:thread w2)))))
      (testing "the first pass runs without waiting a whole interval"
        (is (await-until #(> (long (:runs (cleanup/counters))) (long runs0)) 5000)
            "worker completed no pass"))
      (testing "stop! signals, joins with a bounded wait, and is idempotent"
        (is (true? (cleanup/stop!)))
        (is (not (.isAlive ^Thread (:thread w1))) "thread still alive after stop!")
        (is (true? (cleanup/stop!)) "second stop! is a no-op"))
      (let [runs1 (:runs (cleanup/counters))]
        (Thread/sleep 250)
        (testing "no pass starts after the signal"
          (is (= (long runs1) (long (:runs (cleanup/counters)))))))
      (testing "start! after stop! brings up a fresh thread that also stops"
        (let [runs2 (:runs (cleanup/counters))
              w3 (cleanup/start! store {:interval-s 1 :grace-s 0})]
          (is (not (identical? (:thread w1) (:thread w3))))
          (is (await-until #(> (long (:runs (cleanup/counters))) (long runs2)) 5000))
          (is (true? (cleanup/stop!)))
          (is (not (.isAlive ^Thread (:thread w3)))))))))

;; --- delta 7: no-race parallel write + cleanup stress ------------------------

(deftest parallel-writes-and-cleanup-do-not-race
  (with-store [store dir]
    (plant-expired! store 600)
    (let [writers 3
          per-writer 30
          made (atom [])
          errs (atom [])
          passes (atom 0)
          sweeper-done (atom false)
          ;; A SQLite connection may not be shared across threads: BEGIN and
          ;; COMMIT interleave and SQLite answers "cannot start a transaction
          ;; within a transaction". Every thread opens its own connection to
          ;; the same WAL database, which is the file-level concurrency a real
          ;; deployment has; the in-flight registry is a process-wide atom, so
          ;; the sweep still races the creates.
          sweeper-store (storage/open! dir)
          writer-stores (mapv (fn [_] (storage/open! dir)) (range writers))
          thread-of (fn [label f]
                      (Thread. ^Runnable
                               (fn []
                                 (try
                                   (f)
                                   (catch Throwable t (swap! errs conj [label t]))))
                               label))
          sweeper (thread-of "sweeper"
                             (fn []
                               (loop [n 0]
                                 (when (and (not @sweeper-done) (< n 5000))
                                   (try
                                     (cleanup/run-cleanup-once! sweeper-store {:grace-s 0})
                                     (swap! passes inc)
                                     (catch Throwable t (swap! errs conj ["sweeper" t])))
                                   (recur (inc n))))))
          wthreads (mapv (fn [w store-w]
                           (thread-of (str "writer-" w)
                                      (fn []
                                        (dotimes [i per-writer]
                                          (let [content (.getBytes (str "w" w "-" i) "UTF-8")
                                                it (storage/create-item!
                                                     store-w
                                                     {:content content :mime_type "text/plain"})]
                                            (swap! made conj [(:name it)
                                                              (storage/sha256-hex content)]))))))
                         (range writers) writer-stores)]
      (try
        (.start sweeper)
        (doseq [t wthreads] (.start t))
        (doseq [t wthreads] (.join t 60000))
        (reset! sweeper-done true)
        (.join sweeper 60000)
        (testing "no exception escaped any thread"
          (is (empty? @errs)
              (pr-str (mapv (fn [[l t]] [l (ex-message t)]) @errs))))
        (testing "the sweeper really ran at grace 0 while writers were creating"
          (is (pos? (long @passes))))
        (testing "every created item is afterwards readable with a matching sha256"
          (is (= (* writers per-writer) (count @made)))
          (doseq [[nm sha] @made]
            (let [row (storage/get-item store nm)]
              (is (some? row) (str "lost item " nm))
              (when row
                (is (= sha (storage/sha256-hex (storage/get-blob store (:storage_ref row))))
                    (str "blob mismatch for " nm))))))
        (testing "DB and blob store agree after a final grace-0 pass"
          (cleanup/run-cleanup-once! store {:grace-s 0})
          (let [refs (set (map :storage_ref
                               (storage/list-items store {:include-expired? true})))
                files (set (map :ref (storage/list-blob-files store)))]
            (is (= refs files) "no dangling blob, no missing blob")))
        (finally
          (storage/close-storage sweeper-store)
          (doseq [s writer-stores] (storage/close-storage s)))))))
