(ns metapaste.cleanup
  "TTL cleanup job (spec section 7, plan M4).

   One pass — run-cleanup-once! — drains expired rows in batches of 500 until
   none match, then sweeps orphan blob files. The interval worker is a plain
   background thread with an interruptible sleep: this milestone may not add
   deps.edn entries, and clojure.core.async is not one of the pinned deps."
  (:require [metapaste.log :as log]
            [metapaste.storage :as storage])
  (:import [java.time Instant]))

(def ^:private batch-size 500)

(def ^:private default-grace-s 300)

(def ^:private default-interval-s 3600)

(def ^:private stop-join-ms 10000)

;; --- counters (delta 5) ------------------------------------------------------

(def ^:private initial-counters
  {:runs 0
   :expired_deleted_total 0
   :deleted_rows_total 0
   :duration_ms_total 0
   :orphans_swept_total 0
   :last_cleanup_timestamp nil})

(defonce ^:private counter-state (atom initial-counters))

(defn counters
  "Snapshot of the cleanup counters M6 will expose on /metrics. Exactly the
   six delta-5 keys; :last_cleanup_timestamp is epoch milliseconds and stays
   nil until a pass completes. :expired_deleted_total and :deleted_rows_total
   both count rows reclaimed by the drain — kept as separate keys because
   M6's exposition names them separately."
  []
  @counter-state)

;; --- delta 3: jitter ---------------------------------------------------------

(defn next-delay-ms
  "Milliseconds to wait before the next pass: interval-ms ±10% uniform, drawn
   from rng — a 0-arg fn returning a double in [0.0,1.0) so tests can pin it."
  [interval-ms rng]
  (let [i (double interval-ms)
        half (* i 0.1)
        r (double (rng))]
    (long (+ (- i half) (* 2.0 half r)))))

;; --- one pass ----------------------------------------------------------------

(defn- sweep-orphans!
  "Delete blob files nothing can be waiting for, counting the removals.

   A candidate is kept when the in-flight registry still holds its ref (a
   create that has not resolved yet), when a fresh parameterized query finds
   ANY items row carrying the ref — expired-but-uncollected rows included —
   or when its mtime is younger than the grace window. The first two checks
   run immediately before the unlink rather than at listing time, so a file
   that became visible mid-listing cannot slip between check and delete."
  [store grace-s now-fn]
  (let [now-ms (.toEpochMilli ^Instant (now-fn))
        grace-ms (* 1000 (long grace-s))]
    (reduce (fn [n {:keys [ref mtime-ms]}]
              (if (or (storage/in-flight-blob-ref? ref)
                      (storage/blob-referenced? store ref)
                      (< (- now-ms (long mtime-ms)) grace-ms))
                n
                (do (storage/delete-blob! store ref) (inc n))))
            0
            (storage/list-blob-files store))))

(defn run-cleanup-once!
  "Exactly one cleanup pass: drain expired rows in batches of 500 until none
   remain, then sweep orphan blobs. M6's manual trigger calls this same fn.

   opts:
     :grace-s  orphan files younger than this are kept (default 300)
     :now-fn   0-arg -> Instant time source (default #(Instant/now))
     :stop?    0-arg -> boolean. The drain checks it BEFORE starting each
               batch, so a signal lands as: the batch in flight completes,
               no further batch starts, and the orphan sweep is skipped. That
               is what bounds stop! to at most one 500-row batch.

   Returns {:deleted N :orphans N :duration-ms d}."
  ([store] (run-cleanup-once! store {}))
  ([store opts]
   (let [now-fn (or (:now-fn opts) #(Instant/now))
         stop? (or (:stop? opts) (constantly false))
         t0 (System/currentTimeMillis)
         deleted (loop [total 0]
                   (if (stop?)
                     total
                     (let [n (count (storage/delete-expired-batch store batch-size))]
                       (if (zero? n) total (recur (+ total n))))))
         orphans (if (stop?)
                   0
                   (sweep-orphans! store (or (:grace-s opts) default-grace-s) now-fn))
         duration (- (System/currentTimeMillis) t0)
         completed (.toEpochMilli ^Instant (now-fn))]
     (swap! counter-state
            (fn [m]
              (-> m
                  (update :runs inc)
                  (update :expired_deleted_total + deleted)
                  (update :deleted_rows_total + deleted)
                  (update :duration_ms_total + duration)
                  (update :orphans_swept_total + orphans)
                  (assoc :last_cleanup_timestamp completed))))
     {:deleted deleted :orphans orphans :duration-ms duration})))

;; --- interval worker ---------------------------------------------------------

(defonce ^:private worker (atom nil))

(defn- pass-loop [store running? interval-s grace-s rng]
  (try
    (while @running?
      (try
        (run-cleanup-once! store {:grace-s grace-s :stop? #(not @running?)})
        (catch Throwable t
          (log/error "cleanup pass failed" {:error (ex-message t)})))
      (when @running?
        (try
          (Thread/sleep (next-delay-ms (* 1000 (long interval-s)) rng))
          (catch InterruptedException _ nil))))
    (catch Throwable t
      (log/error "cleanup worker stopped unexpectedly" {:error (ex-message t)}))))

(defn start!
  "Start the background interval worker. Idempotent — a second call returns
   the worker that is already running.

   opts: :interval-s seconds between passes (callers pass the configured
   cleanup_interval_s; default 3600), :grace-s orphan mtime grace (default
   300), :rng jitter source. Returns {:thread Thread :running? atom}."
  ([store] (start! store {}))
  ([store opts]
   (locking worker
     ;; stop! may have timed out its bounded join: prune the entry once that
     ;; thread has finished, so start! brings up a worker that actually runs
     (when (and @worker (not (.isAlive ^Thread (:thread @worker))))
       (reset! worker nil))
     (or @worker
         (let [running? (atom true)
               rng (or (:rng opts) (fn [] (Math/random)))
               interval-s (long (or (:interval-s opts) default-interval-s))
               grace-s (long (or (:grace-s opts) default-grace-s))
               thread (Thread. ^Runnable (fn [] (pass-loop store running?
                                                           interval-s grace-s rng))
                               "metapaste-cleanup")]
           (reset! worker {:thread thread :running? running?})
           (.start thread)
           @worker)))))

(defn stop!
  "Signal the worker to stop and join it with a bounded wait. Idempotent and
   safe when nothing is running; returns true when no cleanup thread is left
   alive.

   A pass in flight finishes the 500-row batch it is in and skips its orphan
   sweep; no new pass starts after the signal. If the bounded join expires,
   the worker stays registered so a later stop! retries instead of spawning
   a second thread."
  []
  (locking worker
    (if-let [{:keys [thread running?]} @worker]
      (do
        (reset! running? false)
        (.interrupt thread)
        (.join thread stop-join-ms)
        (let [alive (.isAlive thread)]
          (when-not alive
            (reset! worker nil))
          (not alive)))
      true)))
