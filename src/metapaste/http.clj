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
                           :host (or host "127.0.0.1")
                           ;; explicit: never :fibers on this stack (see ns docstring)
                           :strategy :threads
                           ;; transport cap = request cap + 64 KiB so our
                           ;; problem+json 411/413 answers beat the adapter's
                           ;; plain-text ones (Q3/Q5 size policy)
                           :max-request-bytes (api/transport-cap (:max_item_bytes cfg))}))))

(defn stop-server!
  "Stop a handle returned by start-server!: stop accepting, drain in-flight
   requests, close live connections."
  [handle]
  (adapter/stop-server handle))

(defn start-metrics-server!
  "Start the INTERNAL metrics listener (Q46/Q47): GET /metrics on :metrics_port
   (default main+1), a second socket of the same adapter — not a route on the
   public table. opts override {:port :host}. Returns the adapter handle."
  ([cfg] (start-metrics-server! cfg {}))
  ([cfg {:keys [port host]}]
   (adapter/run-server api/metrics-handler
                       {:port (if (nil? port) (:metrics_port cfg) port)
                        :host (or host "127.0.0.1")
                        :strategy :threads
                        ;; scrapes carry no body; cap is transport hygiene only
                        :max-request-bytes 65536})))

(defn serve!
  "CLI `metapaste serve`: load config, open storage at :data_dir, start the
   adapter on :port plus the internal metrics listener on :metrics_port, log a
   startup line, block forever (a jolt thread does not keep the process alive
   after main returns — serve must own its lifetime)."
  []
  (let [cfg (config/load-config)]
    (doseq [w (:warnings cfg)]
      (log/warn w))
    (let [store (storage/open! (:data_dir cfg))
          handle (start-server! store cfg)
          metrics-handle (start-metrics-server! cfg)]
      (log/info "metapaste serving" {:port (:port handle)
                                     :metrics_port (:port metrics-handle)
                                     :path_prefix (:path_prefix cfg)
                                     :data_dir (:data_dir cfg)})
      @(promise))))
