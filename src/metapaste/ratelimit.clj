(ns metapaste.ratelimit
  "Per-key token-bucket rate limiting (Q31), in-memory and single-node.

   Defaults: 10 rps sustained with burst 20, taken from config
   (rate_limit_rps / rate_limit_burst) by the caller. Buckets are lazy-created
   per authenticated key_id. When a key first appears, buckets idle longer
   than :idle-ms (default 10 minutes) are evicted — that lazy sweep bounds
   map cardinality to keys active within the idle window, so buckets cannot
   accumulate without limit on a long-lived process.")

(def default-idle-ms 600000) ;; 10 minutes

(defn- refill [bucket now rps burst]
  (let [elapsed-s (/ (- now (long (:ts bucket))) 1000.0)]
    {:tokens (min (double burst)
                  (+ (double (:tokens bucket)) (* elapsed-s (double rps))))
     :ts now}))

(defn- evict-idle [buckets now idle-ms]
  (into {}
        (remove (fn [[_ b]] (> (- now (long (:ts b))) (long idle-ms))))
        buckets))

(defn allow?
  "Consume one token for key-id: true = allowed, false = reject with 429.
   opts: {:rps :burst :idle-ms} — rps/burst come from config per call.
   Every read-modify-write of the bucket map happens under a lock on state,
   so concurrent requests on one node cannot double-spend a token."
  [state key-id {:keys [rps burst idle-ms] :or {idle-ms default-idle-ms}}]
  (locking state
    (let [now (System/currentTimeMillis)
          buckets @state
          buckets (if (contains? buckets key-id)
                    buckets
                    (evict-idle buckets now idle-ms))
          bucket (refill (get buckets key-id {:tokens (double burst) :ts now})
                         now rps burst)]
      (if (>= (double (:tokens bucket)) 1.0)
        (do (reset! state (assoc buckets key-id (update bucket :tokens dec)))
            true)
        (do (reset! state (assoc buckets key-id bucket))
            false)))))
