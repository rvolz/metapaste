(ns metapaste.http
  "The ONE namespace that imports ring-chez.* — the adapter wiring seam for
   RQ1 mitigation. Everything else (metapaste.api handlers) speaks plain
   Ring-style maps, so swapping the transport means rewriting only this file.

   Adapter facts (v0.7.12) this seam is built on:
   - the transport BUFFERS the whole request body (capped at
     :max-request-bytes) before the handler runs; there is no socket-level
     request streaming, so 'streaming' here is the handler consuming the
     buffered InputStream through ONE counted-cap function (in metapaste.api)
   - :strategy :threads is mandatory — jolt-lang/db's sqlite bindings lack
     :blocking, so fibers are unsafe (RQ1/RQ5 memo)"
  (:require [ring-chez.adapter :as adapter]
            [ring-chez.multipart.core :as multipart]
            [metapaste.api :as api]
            [metapaste.cleanup :as cleanup]
            [metapaste.config :as config]
            [metapaste.log :as log]
            [metapaste.storage :as storage]))

(defn make-multipart-parser
  "The ONE replaceable multipart seam (never the ring middleware, which
   buffers without limits): parses through ring-chez.multipart.core with
   :memory-limit and :max-segment-size set from max_item_bytes. In strict
   mode the parser throws parser-limit-reached MID-parse; that maps to a 413
   problem error, other multipart errors to 400. Returns {:params :files} or
   {:error {:status :slug :detail}}."
  [max-item-bytes]
  (fn [request]
    (try
      (multipart/parse-form-data request
                                 {:strict true
                                  :memory-limit max-item-bytes
                                  :max-segment-size max-item-bytes})
      (catch Throwable e
        (cond
          (multipart/parser-limit-reached? e)
          {:error {:status 413
                   :slug "payload-too-large"
                   :detail (str "multipart payload exceeds the limit of "
                                max-item-bytes " bytes")}}

          (multipart/multipart-error? e)
          {:error {:status 400
                   :slug "validation-failed"
                   :detail (or (ex-message e) "malformed multipart body")}}

          :else (throw e))))))

(defn start-server!
  "Start the ring-chez adapter serving the items API. opts override config:
   {:port p :host h} — :port 0 binds an ephemeral port named by the handle's
   :port. Returns the adapter handle {:socket :port :host :running}."
  ([store cfg] (start-server! store cfg {}))
  ([store cfg {:keys [port host]}]
   (let [handler (api/make-handler
                   {:store store
                    :config cfg
                    :multipart-parser (make-multipart-parser (:max_item_bytes cfg))})]
     (adapter/run-server handler
                          {:port (if (nil? port) (:port cfg) port)
                           ;; bind_host (Q54 packaging): 127.0.0.1 by default,
                           ;; 0.0.0.0 behind a container port publish
                           :host (or host (:bind_host cfg) "127.0.0.1")
                           ;; explicit: never :fibers on this stack (see ns docstring)
                           :strategy :threads
                           ;; transport cap = request cap + 64 KiB so our
                           ;; problem+json 411/413 answers beat the adapter's
                           ;; plain-text ones (Q3/Q5 size policy)
                           :max-request-bytes (api/transport-cap (:max_item_bytes cfg))}))))

(defn stop-server!
  "Stop a handle returned by start-server!: stop accepting, drain in-flight
   requests, close live connections. opts pass through to the adapter — the
   shutdown path uses {:drain-timeout-ms 10000} as its bounded drain."
  ([handle] (stop-server! handle nil))
  ([handle opts]
   (adapter/stop-server handle opts)))

(defn start-metrics-server!
  "Start the INTERNAL metrics listener (Q46/Q47): GET /metrics on :metrics_port
   (default main+1), a second socket of the same adapter — not a route on the
   public table. opts override {:port :host}. Returns the adapter handle."
  ([cfg] (start-metrics-server! cfg {}))
  ([cfg {:keys [port host]}]
   (adapter/run-server api/metrics-handler
                       {:port (if (nil? port) (:metrics_port cfg) port)
                        :host (or host (:bind_host cfg) "127.0.0.1")
                        :strategy :threads
                        ;; scrapes carry no body; cap is transport hygiene only
                        :max-request-bytes 65536})))

(defn shutdown-hook!
  "Graceful shutdown (spec section 14 risk 7): stop every listener with the
   bounded drain, stop the cleanup worker, close storage, then exit 0.
   serve! registers the returned 0-arg fn with the runtime; it runs at most
   once. listeners are 0-arg stop fns, cleanup-stop! stops the worker
   (cleanup/stop!), exit-fn defaults to System/exit — probes showed jolt
   exits 143 after a hook returns, so the hook must exit 0 itself."
  [{:keys [store listeners cleanup-stop! exit-fn]}]
  (let [ran? (atom false)
        exit! (or exit-fn (fn [code] (System/exit code)))]
    (fn []
      (when (compare-and-set! ran? false true)
        (log/info "shutting down")
        (doseq [stop listeners]
          (try (stop)
               (catch Throwable t
                 (log/warn "listener stop failed" {:error (ex-message t)}))))
        (try
          (cleanup-stop!)
          (log/info "cleanup worker stopped")
          (catch Throwable t
            (log/warn "cleanup stop failed" {:error (ex-message t)})))
        (try (storage/close-storage store)
             (catch Throwable t
               (log/warn "storage close failed" {:error (ex-message t)})))
        (exit! 0)))))

(defn serve!
  "CLI `metapaste serve`: load config, open storage at :data_dir, start the
   adapter on :port plus the internal metrics listener on :metrics_port, start
   the cleanup worker, register the graceful-shutdown hook, log a startup
   line, block forever (a jolt thread does not keep the process alive after
   main returns — serve must own its lifetime). On SIGTERM the hook drains
   both listeners (10 s bound), stops the worker, closes storage, exits 0."
  []
  (let [cfg (config/load-config)]
    (doseq [w (:warnings cfg)]
      (log/warn w))
    (let [store (storage/open! (:data_dir cfg))
          handle (start-server! store cfg)
          metrics-handle (start-metrics-server! cfg)
          _ (cleanup/start! store {:interval-s (:cleanup_interval_s cfg)})
          hook (shutdown-hook!
                 {:store store
                  :listeners [(fn [] (stop-server! handle {:drain-timeout-ms 10000}))
                              (fn [] (stop-server! metrics-handle {:drain-timeout-ms 10000}))]
                  :cleanup-stop! cleanup/stop!})]
      ;; registered BEFORE the startup line: a SIGTERM arriving the moment
      ;; the port is observable must find the hook, not the default kill
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable hook "metapaste-shutdown"))
      (log/info "metapaste serving" {:port (:port handle)
                                     :metrics_port (:port metrics-handle)
                                     :path_prefix (:path_prefix cfg)
                                     :data_dir (:data_dir cfg)})
      @(promise))))
