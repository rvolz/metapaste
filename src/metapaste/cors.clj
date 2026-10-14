(ns metapaste.cors
  "CORS policy (Q55): disabled by default; config cors_allow_origins (list)
   enables headers for exactly those origins; preflight OPTIONS is answered
   for allowed origins only. Pure response-header policy over Ring-style
   maps — no framework imports, so the transport seam stays local.

   Security: Access-Control-Allow-Origin only ever echoes a request Origin
   that case-insensitively EQUALS a configured entry, and entries are
   validated at config load as scheme://host[:port] (no whitespace, no
   path), so no client input is reflected into response headers."
  (:require [clojure.string :as str]))

(def ^:private allow-headers "Authorization, Content-Type, X-Request-Id")

(def ^:private preflight-vary
  "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")

(defn enabled?
  "True when the allowlist is non-empty. An absent/nil key counts as off."
  [cfg]
  (boolean (seq (:cors_allow_origins cfg))))

(defn preflight?
  "A CORS preflight is OPTIONS with both Origin and
   Access-Control-Request-Method."
  [request]
  (boolean (and (get-in request [:headers "origin"])
                (get-in request [:headers "access-control-request-method"]))))

(defn allowed?
  "True when the request's Origin is on the allowlist (case-insensitive —
   scheme and host are case-insensitive per RFC 6454)."
  [cfg request]
  (let [origin (get-in request [:headers "origin"])]
    (boolean (and origin
                  (some #(= (str/lower-case %) (str/lower-case origin))
                        (:cors_allow_origins cfg))))))

(defn- vary-append [v add]
  (if (some #(= (str/lower-case %) (str/lower-case add))
            (map str/trim (str/split (or v "") #",")))
    v
    (if (str/blank? v) add (str v ", " add))))

(defn preflight-response
  "204 answering a preflight for an allowed origin. allow-methods is the
   matched route's method list (the api layer supplies it from route data);
   allow-headers is a fixed list — requested headers are never echoed."
  [origin allow-methods]
  {:status 204
   :headers {"Access-Control-Allow-Origin" origin
             "Access-Control-Allow-Methods" allow-methods
             "Access-Control-Allow-Headers" allow-headers
             "Access-Control-Max-Age" "600"
             "Vary" preflight-vary}})

(defn attach
  "CORS response headers for one request: while CORS is enabled every
   response carries Vary: Origin (cache hygiene); Access-Control-Allow-Origin
   is added only when the request's Origin is allowed. No-op when disabled —
   a default-config deployment emits no CORS headers at all."
  [response cfg request]
  (if (enabled? cfg)
    (let [response (update-in response [:headers "Vary"] vary-append "Origin")]
      (if (allowed? cfg request)
        (assoc-in response [:headers "Access-Control-Allow-Origin"]
                  (get-in request [:headers "origin"]))
        response))
    response))
