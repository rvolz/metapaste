(ns metapaste.api
  "Items API handlers (spec §5 / docs/openapi.yaml section 5).

  Pure request -> response over plain Ring-style maps: no ring-chez or adapter
  imports here. The server wiring, size limits at the transport, and the ONE
  multipart parsing seam live in metapaste.http; this namespace receives the
  parser as a function in its context map."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [metapaste.auth :as auth]
            [metapaste.cleanup :as cleanup]
            [metapaste.config :as config]
            [metapaste.cors :as cors]
            [metapaste.log :as log]
            [metapaste.metrics :as metrics]
            [metapaste.names :as names]
            [metapaste.ratelimit :as ratelimit]
            [metapaste.storage :as storage])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream InputStream]
           [java.nio ByteBuffer]
           [java.nio.charset Charset CodingErrorAction]
           [java.time Instant]
           [java.util Base64 UUID]))

;; reitit reads its :clj reader branches: load under :clj, then restore —
;; the pattern from the jolt-lang/examples ring-app.
(let [prev (__reader-features)]
  (__reader-features-set! ["clj" "jolt" "default"])
  (require '[reitit.trie-jolt] '[reitit.core :as reitit])
  (__reader-features-set! prev))

;; --- size policy (Q3/Q5) ----------------------------------------------------

(def kiB 65536)

(defn request-body-cap
  "Caps OUR body reads at 2x max_item_bytes + 64 KiB (JSON envelopes may
   escape chars up to ~6x; beyond 2x overhead we reject as oversize)."
  [max-item-bytes]
  (+ (* 2 max-item-bytes) kiB))

(defn transport-cap
  "Transport :max-request-bytes = request cap + 64 KiB, so our problem+json
   411/413 answers always beat the adapter's plain-text ones."
  [max-item-bytes]
  (+ (request-body-cap max-item-bytes) kiB))

;; --- errors (RFC 7807, Q23) -------------------------------------------------

(def ^:private titles
  {400 "Bad Request"
   401 "Unauthorized"
   403 "Forbidden"
   404 "Not Found"
   405 "Method Not Allowed"
   411 "Length Required"
   413 "Payload Too Large"
   415 "Unsupported Media Type"
   429 "Too Many Requests"
   500 "Internal Server Error"
   503 "Service Unavailable"})

(defn problem
  "RFC 7807 problem+json response; type/title/status/detail always present."
  ([status slug detail] (problem status slug detail nil))
  ([status slug detail headers]
   {:status status
    :headers (merge {"Content-Type" "application/problem+json"} headers)
    :body (json/write-str {:type (str "urn:metapaste:problem:" slug)
                           :title (get titles status "Error")
                           :status status
                           :detail detail}
                          :escape-slash false :escape-unicode false)}))

(defn- json-response [status m]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/write-str m :escape-slash false :escape-unicode false)})

(defn- no-content []
  {:status 204 :headers {}})

(defn- accepted-key
  "M5 admin-token check (constant-time). Returns the presented token when
   accepted (Bearer prefix tolerated), else nil."
  [ctx request]
  (let [raw (get-in request [:headers "authorization"])
        presented (if (and raw (str/starts-with? (str/lower-case raw) "bearer "))
                    (subs raw 7)
                    raw)]
    (when (auth/admin-token-ok? (:config ctx) presented)
      presented)))

(defn- record-http!
  "Q47 request counters/histogram. route is the MATCHED TEMPLATE (never the
   raw URI), so item names and key ids never become label values."
  [route method status t0]
  (metrics/inc! :http_requests_total
                {:route (or route "unmatched")
                 :method (str/upper-case (name method))
                 :status (long status)})
  (metrics/observe! :http_request_duration_seconds
                    {:route (or route "unmatched")}
                    (/ (- (System/currentTimeMillis) t0) 1000.0)))

;; --- validation (Q6, Q9, Q32-Q35) -------------------------------------------

(def ^:private mime-essence-re
  #"[!#$%&'*+\-.^_`|~0-9a-zA-Z]+/[!#$%&'*+\-.^_`|~0-9a-zA-Z]+")

(defn mime-essence
  "Lowercase type/subtype essence per Q6, parameters stripped; nil when the
   syntax is not a valid RFC token/token."
  [s]
  (when (string? s)
    (let [e (-> s (str/split #";") first str/trim str/lower-case)]
      (when (re-matches mime-essence-re e)
        e))))

(def ^:private max-description-code-points 1000)

(defn check-description
  "Q9: at most 1000 Unicode CODE POINTS (jolt strings count by code point).
   Returns {:value s|nil} or {:error detail}."
  [v]
  (cond
    (nil? v) {:value nil}
    (not (string? v)) {:error "description must be a string"}
    (> (count v) max-description-code-points)
    {:error (str "description must be at most " max-description-code-points
                 " Unicode code points")}
    :else {:value v}))

(defn check-ttl
  "Q32-Q35: integer within [min_ttl_seconds, max_ttl_seconds]; 0 = never only
   when allow_never_expire; absent -> default_ttl_seconds. Out-of-bounds values
   are rejected, never re-clamped. Numeric strings (multipart fields) accepted.
   Returns {:value n} or {:error detail}."
  [cfg v]
  (let [n (cond
            (nil? v) (:default_ttl_seconds cfg)
            (integer? v) (long v)
            (and (string? v) (re-matches #"-?\d+" v)) (Long/parseLong v)
            :else ::invalid)]
    (cond
      (= ::invalid n) {:error "ttl_seconds must be an integer"}
      (zero? n) (if (:allow_never_expire cfg)
                  {:value 0}
                  {:error "ttl_seconds 0 requires allow_never_expire=true"})
      (< n (:min_ttl_seconds cfg))
      {:error (str "ttl_seconds below minimum of " (:min_ttl_seconds cfg))}
      (> n (:max_ttl_seconds cfg))
      {:error (str "ttl_seconds above maximum of " (:max_ttl_seconds cfg))}
      :else {:value n})))

;; --- request body consumption (counted cap, one read pass) -------------------

(defn content-length-gate
  "nil when Content-Length is present and within cap; else [status slug
   detail]. Absent (incl. chunked) -> 411 before any parsing (§3). The adapter
   validates Content-Length as identical digit strings, so the comma-joined
   repeat form (RFC 7230) parses on its first segment."
  [request cap]
  (let [cl (get-in request [:headers "content-length"])]
    (cond
      (nil? cl) [411 "length-required" "Content-Length is required"]
      :else
      (let [n (try
                (Long/parseLong (str/trim (first (str/split cl #","))))
                (catch Throwable _ ::invalid))]
        (cond
          (= ::invalid n)
          [400 "validation-failed" "invalid Content-Length header"]
          (> n cap)
          [413 "payload-too-large"
           (str "request body exceeds the limit of " cap " bytes")])))))

(defn- read-capped
  "Consumes the body InputStream through ONE counted-cap read — never slurp,
   never unbounded concat. Returns bytes, or ::too-large past the cap."
  [^InputStream in cap]
  (if (nil? in)
    (byte-array 0)
    (let [out (ByteArrayOutputStream.) buf (byte-array 8192)]
      (loop []
        (let [n (.read in buf)]
          (cond
            (neg? n) (.toByteArray out)
            (> (+ (.size out) n) cap) ::too-large
            :else (do (.write out buf 0 n) (recur))))))))

(defn- utf8-strict
  "String from bytes; ::invalid when the bytes are not well-formed UTF-8 (Q7).
   .decode yields a CharBuffer on jolt — str it to get the string."
  [^bytes bs]
  (try
    (str (.decode (doto (.newDecoder (Charset/forName "UTF-8"))
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))
                  (ByteBuffer/wrap bs)))
    (catch Throwable _ ::invalid)))

(defn- json-body
  "Gate + counted-cap read + strict UTF-8 + JSON parse of a request body.
   Returns {:value map} or {:status n :slug s :detail d}."
  [request cap]
  (if-let [[st slug detail] (content-length-gate request cap)]
    {:status st :slug slug :detail detail}
    (let [raw (read-capped (:body request) cap)]
      (if (= ::too-large raw)
        {:status 413 :slug "payload-too-large"
         :detail (str "request body exceeds the limit of " cap " bytes")}
        (let [s (utf8-strict raw)]
          (if (= ::invalid s)
            {:status 400 :slug "validation-failed"
             :detail "request body is not valid UTF-8"}
            (try
              {:value (json/read-str s :key-fn keyword)}
              (catch Throwable _
                {:status 400 :slug "validation-failed"
                 :detail "request body is not valid JSON"}))))))))

;; --- conditional GET / serving posture (Q21, Q24) ---------------------------

(defn if-none-match?
  "True when the If-None-Match header matches the item's sha256 ETag."
  [header sha]
  (boolean
    (when header
      (some (fn [t]
              (let [t (str/trim t)]
                (or (= t "*") (= t (str "\"" sha "\"")) (= t sha))))
            (str/split header #",")))))

(defn serving-headers
  "Q21: binary kinds, text/html and image/svg+xml are attachments; other
   text-ish kinds serve inline with a sandboxing CSP."
  [mime kind]
  (if (or (= "binary" kind)
          (= "text/html" mime)
          (= "image/svg+xml" mime))
    {"Content-Disposition" "attachment"}
    {"Content-Security-Policy" "sandbox"}))

;; --- item metadata (exact ItemMetadata field set) ---------------------------

(defn- api-base [cfg]
  (str (:path_prefix cfg) "/api/v1"))

(defn item-metadata [cfg row]
  {:name (:name row)
   :url (str (api-base cfg) "/items/" (:name row))
   :content_url (str (api-base cfg) "/items/" (:name row) "/content")
   :mime_type (:mime_type row)
   :description (:description row)
   :size_bytes (:size_bytes row)
   :created_at (:created_at row)
   :ttl_seconds (:ttl_seconds row)
   :expires_at (:expires_at row)
   :checksum_sha256 (:checksum_sha256 row)
   :content_kind (:content_kind row)})

;; --- list query (§5.6) -------------------------------------------------------

(defn- hex-val [b]
  (let [c (bit-and b 0xff)]
    (cond
      (<= 48 c 57) (- c 48)
      (<= 65 c 70) (- c 55)
      (<= 97 c 102) (- c 87)
      :else -1)))

(defn- url-decode
  "Percent-decode (+ = space) byte-wise, then strict UTF-8. Returns ::bad when
   the decoded bytes are not valid UTF-8."
  [^String s]
  (let [src (.getBytes s "UTF-8")
        n (alength src)
        out (ByteArrayOutputStream.)]
    (loop [i 0]
      (if (>= i n)
        (utf8-strict (.toByteArray out))
        (let [b (aget src i)]
          (cond
            (= 0x2b (bit-and b 0xff))
            (do (.write out 0x20) (recur (inc i)))
            (and (= 0x25 (bit-and b 0xff)) (< (+ i 2) n))
            (let [hi (hex-val (aget src (inc i)))
                  lo (hex-val (aget src (+ i 2)))]
              (if (and (>= hi 0) (>= lo 0))
                (do (.write out (+ (* 16 hi) lo)) (recur (+ i 3)))
                (do (.write out (bit-and b 0xff)) (recur (inc i)))))
            :else
            (do (.write out (bit-and b 0xff)) (recur (inc i)))))))))

(defn- parse-query
  "Query string -> map of first occurrence of each key; ::bad on undecodable."
  [qs]
  (if (str/blank? qs)
    {}
    (reduce (fn [m pair]
              (if (= ::bad m)
                ::bad
                (let [i (str/index-of pair "=")
                      k (url-decode (if i (subs pair 0 i) pair))
                      v (url-decode (if i (subs pair (inc i)) ""))]
                  (if (or (= ::bad k) (= ::bad v))
                    ::bad
                    (if (contains? m k) m (assoc m k v))))))
            {}
            (str/split qs #"&"))))

(defn- parse-limit [s]
  (if (nil? s)
    {:value 50}
    (let [n (try (Long/parseLong s) (catch Throwable _ ::invalid))]
      (cond
        (= ::invalid n) {:error "limit must be an integer"}
        (or (< n 1) (> n 500)) {:error "limit must be between 1 and 500"}
        :else {:value n}))))

(defn- parse-bool-param [s default]
  (cond
    (nil? s) {:value default}
    (= "true" s) {:value true}
    (= "false" s) {:value false}
    :else {:error (str "expected true or false, got " (pr-str s))}))

(defn- parse-instant-param [s label]
  (if (nil? s)
    {:value nil}
    (let [i (try (Instant/parse s) (catch Throwable _ ::invalid))]
      (if (= ::invalid i)
        {:error (str label " must be an ISO-8601 instant")}
        {:value (storage/iso-utc i)}))))

(def ^:private stored-timestamp-re #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{9}Z")

(defn encode-cursor
  "Opaque page cursor over (created_at, name); url-safe base64 of the pair."
  [{:keys [created-at name]}]
  (.encodeToString (Base64/getUrlEncoder)
                   (.getBytes (str created-at "|" name) "UTF-8")))

(defn decode-cursor
  "Inverse of encode-cursor; nil unless the pair decodes to a stored-format
   timestamp and a valid item name."
  [s]
  (try
    (let [raw (String. (.decode (Base64/getUrlDecoder) ^String s) "UTF-8")
          i (str/index-of raw "|")
          created (when i (subs raw 0 i))
          nm (when i (subs raw (inc i)))]
      (when (and created
                 (re-matches stored-timestamp-re created)
                 (re-matches (re-pattern (str "[" names/alphabet "]{12}")) nm))
        {:created-at created :name nm}))
    (catch Throwable _ nil)))

(defn parse-list-query
  "List query string -> {:value storage-opts} or {:error detail} (all invalid
   parameters are 400)."
  [qs]
  (let [params (parse-query qs)]
    (if (= ::bad params)
      {:error "invalid query string"}
      (let [limit (parse-limit (get params "limit"))
            include (parse-bool-param (get params "include_expired") false)
            after (parse-instant-param (get params "created_after") "created_after")
            before (parse-instant-param (get params "created_before") "created_before")
            cursor (if-let [c (get params "cursor")]
                     (if-let [decoded (decode-cursor c)]
                       {:value decoded}
                       {:error "invalid cursor"})
                     {:value nil})]
        (if-let [e (some :error [limit include after before cursor])]
          {:error e}
          {:value (cond-> {:limit (:value limit)
                           :include-expired? (:value include)}
                    (contains? params "mime_prefix")
                    (assoc :mime-prefix (get params "mime_prefix"))
                    (:value after) (assoc :created-after (:value after))
                    (:value before) (assoc :created-before (:value before))
                    (:value cursor) (assoc :cursor (:value cursor)))})))))

;; --- request id (Q50) --------------------------------------------------------

(defn- request-id [request]
  (let [h (get-in request [:headers "x-request-id"])]
    (if (and h (re-matches #"[ -~]{1,128}" h))
      h
      (str (UUID/randomUUID)))))

;; --- auth (M5): 401 -> 403 -> 429 before the handler --------------------------

(defn- authorize
  "M5 auth chain on a matched route: bearer auth (401), scope check (403,
   write implies read), per-key rate limit (429 + Retry-After: 1).
   Returns {:request req'} with :key-id/:scopes attached when allowed, else
   {:response problem :key-id k-or-nil} (key-id feeds request logging)."
  [ctx request method]
  (let [cfg (:config ctx)
        result (auth/check-bearer (:store ctx)
                                  (get-in request [:headers "authorization"]))]
    (cond
      (:error result)
      {:response (problem 401 "unauthorized" (:error result)) :key-id nil}

      (not (auth/scope-allows? (:scopes result) method))
      {:response (problem 403 "forbidden" "insufficient scope for this request")
       :key-id (:key-id result)}

      (not (ratelimit/allow? (:limiter ctx) (:key-id result)
                             {:rps (:rate_limit_rps cfg)
                              :burst (:rate_limit_burst cfg)}))
      {:response (problem 429 "rate-limit-exceeded" "rate limit exceeded"
                           {"Retry-After" "1"})
       :key-id (:key-id result)}

      :else
      {:request (-> request
                    (assoc :key-id (:key-id result))
                    (assoc :scopes (:scopes result)))
       :key-id (:key-id result)})))

(defn- ownership-gate
  "Q17/Q18: PATCH/DELETE are owner-only — non-owner gets 403 (admin?
   bypasses, wired by M6). Unknown/expired rows fall through so the handler
   answers 404."
  [ctx request]
  (when (#{:patch :delete} (:request-method request))
    (when-let [row (storage/get-item (:store ctx)
                                     (get-in request [:path-params :name]))]
      (when-not (auth/may-modify? request row)
        (problem 403 "forbidden" "item is not owned by this key")))))

;; --- handlers ----------------------------------------------------------------

(defn- media-type [ct]
  (some-> ct (str/split #";") first str/trim str/lower-case))

(defn- json-content-type? [request]
  (= "application/json" (media-type (get-in request [:headers "content-type"]))))

(defn- not-json-415 []
  (problem 415 "unsupported-media-type"
           "Content-Type must be application/json"))

(defn- create-item!
  [ctx cfg row-opts]
  (let [row (storage/create-item! (:store ctx) row-opts)]
    (-> (json-response 201 (item-metadata cfg row))
        ;; request-log fields + uploaded_bytes_total: what was accepted (Q48)
        (assoc ::log-fields {:name (:name row) :size (:size_bytes row)}))))

(defn- create-from-json [ctx request]
  (let [cfg (:config ctx)
        cap (request-body-cap (:max_item_bytes cfg))
        parsed (json-body request cap)]
    (if-not (contains? parsed :value)
      (problem (:status parsed) (:slug parsed) (:detail parsed))
      (let [m (:value parsed)]
        (if-not (map? m)
          (problem 400 "validation-failed" "request body must be a JSON object")
          (let [content (:content m)
                mime* (cond
                        (nil? (:mime_type m)) {:value "application/octet-stream"}
                        (not (string? (:mime_type m)))
                        {:error "mime_type must be a string"}
                        :else (if-let [e (mime-essence (:mime_type m))]
                                {:value e}
                                {:error "mime_type is not a valid type/subtype"}))
                desc* (check-description (:description m))
                ttl* (check-ttl cfg (:ttl_seconds m))]
            (cond
              (not (and (contains? m :content) (string? content)))
              (problem 400 "validation-failed"
                       "content is required and must be a string")
              (:error mime*) (problem 400 "validation-failed" (:error mime*))
              (:error desc*) (problem 400 "validation-failed" (:error desc*))
              (:error ttl*) (problem 400 "validation-failed" (:error ttl*))
              :else
              (let [content-bytes (.getBytes ^String content "UTF-8")]
                (if (> (alength content-bytes) (:max_item_bytes cfg))
                  (problem 413 "payload-too-large"
                           (str "content exceeds the limit of "
                                (:max_item_bytes cfg) " bytes"))
                  ;; the body itself was strict-UTF-8-checked above; string
                  ;; content from JSON is unicode by construction (Q7)
                  (create-item! ctx cfg
                                {:content content-bytes
                                 :mime_type (:value mime*)
                                 :description (:value desc*)
                                 :ttl_seconds (:value ttl*)
                                 :owner_key_id (:key-id request)}))))))))))

(defn- create-from-multipart [ctx request]
  (let [cfg (:config ctx)
        item-cap (:max_item_bytes cfg)
        cap (request-body-cap item-cap)
        [st slug detail :as gate] (content-length-gate request cap)]
    (if gate
      (problem st slug detail)
      (let [raw (read-capped (:body request) cap)]
        (if (= ::too-large raw)
          (problem 413 "payload-too-large"
                   (str "request body exceeds the limit of " cap " bytes"))
          (let [parsed ((:multipart-parser ctx)
                         (assoc request :body (ByteArrayInputStream. raw)))]
            (if-let [e (:error parsed)]
              (problem (:status e) (:slug e) (:detail e))
              (let [f (get-in parsed [:files "file"])
                    f (if (vector? f) (first f) f)
                    params (:params parsed)]
                (if-not f
                  (problem 400 "validation-failed"
                           "multipart body must contain a file part")
                  (let [content (:bytes f)
                        mime* (if-let [mt (get params "mime_type")]
                                (if-let [e (mime-essence mt)]
                                  {:value e}
                                  {:error "mime_type is not a valid type/subtype"})
                                {:value "application/octet-stream"})
                        desc* (check-description (get params "description"))
                        ttl* (check-ttl cfg (get params "ttl_seconds"))]
                    (cond
                      (:error mime*) (problem 400 "validation-failed" (:error mime*))
                      (:error desc*) (problem 400 "validation-failed" (:error desc*))
                      (:error ttl*) (problem 400 "validation-failed" (:error ttl*))
                      (> (alength content) item-cap)
                      (problem 413 "payload-too-large"
                               (str "content exceeds the limit of " item-cap
                                    " bytes"))
                      (and (= "text" (storage/content-kind (:value mime*)))
                           (= ::invalid (utf8-strict content)))
                      (problem 400 "validation-failed"
                               "file content is not valid UTF-8")
                      :else
                      (create-item! ctx cfg
                                    {:content content
                                     :mime_type (:value mime*)
                                     :description (:value desc*)
                                     :ttl_seconds (:value ttl*)
                                     :owner_key_id (:key-id request)}))))))))))))

(defn create-item [ctx request]
  (let [ct (media-type (get-in request [:headers "content-type"]))]
    (cond
      (= "application/json" ct) (create-from-json ctx request)
      (= "multipart/form-data" ct) (create-from-multipart ctx request)
      :else (problem 415 "unsupported-media-type"
                     "Content-Type must be application/json or multipart/form-data"))))

(defn- count-expired-hit!
  "Read-path expiry attribution (Q47 items_expired_total): only when the name
   really is an expired-but-uncollected row, never for unknown names."
  [ctx nm]
  (when (storage/expired-item? (:store ctx) nm)
    (metrics/inc! :items_expired_total {})))

(defn get-item [ctx request]
  (let [cfg (:config ctx)
        nm (get-in request [:path-params :name])]
    (if-let [row (storage/get-item (:store ctx) nm)]
      (let [sha (:checksum_sha256 row)
            etag (str "\"" sha "\"")]
        (if (if-none-match? (get-in request [:headers "if-none-match"]) sha)
          {:status 304 :headers {"ETag" etag}}
          (assoc-in (json-response 200 (item-metadata cfg row))
                    [:headers "ETag"] etag)))
      (do (count-expired-hit! ctx nm)
          (problem 404 "not-found" "item not found or expired")))))

(defn get-content [ctx request]
  (let [nm (get-in request [:path-params :name])
        store (:store ctx)]
    (if-let [row (storage/get-item store nm)]
      (let [sha (:checksum_sha256 row)
            etag (str "\"" sha "\"")]
        (if (if-none-match? (get-in request [:headers "if-none-match"]) sha)
          {:status 304 :headers {"ETag" etag}}
          (if-let [blob (storage/get-blob store (:storage_ref row))]
            (-> {:status 200
                 :headers (merge {"Content-Type" (:mime_type row)
                                  "X-Content-Type-Options" "nosniff"
                                  "ETag" etag}
                                 (serving-headers (:mime_type row)
                                                  (:content_kind row)))
                 :body blob}
                ;; request-log fields + downloaded_bytes_total (Q48)
                (assoc ::log-fields {:name nm :size (alength ^bytes blob)}))
            (problem 500 "internal-error" "content missing from storage"))))
      (do (count-expired-hit! ctx nm)
          (problem 404 "not-found" "item not found or expired")))))

(defn patch-item [ctx request]
  (let [cfg (:config ctx)
        store (:store ctx)
        nm (get-in request [:path-params :name])]
    (if-not (json-content-type? request)
      (not-json-415)
      (if-not (storage/get-item store nm)
        (problem 404 "not-found" "item not found or expired")
        (let [parsed (json-body request (request-body-cap (:max_item_bytes cfg)))]
          (if-not (contains? parsed :value)
            (problem (:status parsed) (:slug parsed) (:detail parsed))
            (let [m (:value parsed)]
              (if-not (and (map? m) (seq m))
                (problem 400 "validation-failed"
                         "body must be a non-empty JSON object")
                (let [unknown (remove #{:description :ttl_seconds} (keys m))]
                  (if (seq unknown)
                    (problem 400 "validation-failed"
                             (str "unsupported fields: "
                                  (str/join ", " (map name (sort unknown)))
                                  " (content and mime_type are immutable)"))
                    (let [desc* (when (contains? m :description)
                                  (check-description (:description m)))
                          ttl* (when (contains? m :ttl_seconds)
                                 (check-ttl cfg (:ttl_seconds m)))]
                      (cond
                        (and (contains? m :description) (nil? (:description m)))
                        (problem 400 "validation-failed" "description must not be null")
                        (and (contains? m :ttl_seconds) (nil? (:ttl_seconds m)))
                        (problem 400 "validation-failed" "ttl_seconds must not be null")
                        (:error desc*) (problem 400 "validation-failed" (:error desc*))
                        (:error ttl*) (problem 400 "validation-failed" (:error ttl*))
                        :else
                        (let [patch (cond-> {}
                                      (contains? m :description)
                                      (assoc :description (:value desc*))
                                      (contains? m :ttl_seconds)
                                      (assoc :ttl_seconds (:value ttl*)))
                              updated (storage/update-item! store nm patch)]
                          (if updated
                            (json-response 200 (item-metadata cfg updated))
                            (problem 404 "not-found"
                                     "item not found or expired")))))))))))))))

(defn delete-item [ctx request]
  (let [nm (get-in request [:path-params :name])]
    (if (storage/delete-item! (:store ctx) nm)
      {:status 204 :headers {}}
      (problem 404 "not-found" "item not found or expired"))))

(defn list-items [ctx request]
  (let [cfg (:config ctx)
        parsed (parse-list-query (:query-string request))]
    (if (:error parsed)
      (problem 400 "validation-failed" (:error parsed))
      (let [{:keys [limit] :as opts} (:value parsed)
            ;; fetch one past the page to know whether a cursor remains
            rows (storage/list-items (:store ctx) (assoc opts :limit (inc limit)))
            page (vec (take limit rows))
            next-cursor (when (> (count rows) limit)
                          (encode-cursor {:created-at (:created_at (peek page))
                                          :name (:name (peek page))}))]
        (json-response 200 {:items (mapv #(item-metadata cfg %) page)
                            :next_cursor next-cursor})))))

;; --- health (Q51): /healthz + /readyz, no auth --------------------------------

(defn healthz [ctx request]
  (json-response 200 {:status "ok"}))

(def ^:private probe-cache-ttl-ms 5000)

(defonce ^:private probe-cache (atom {:at 0 :ok false :detail nil}))

(defn- cached-write-probe
  "Storage write probe result, cached for probe-cache-ttl-ms either way (Q51:
   the readiness probe runs at most every 5 s)."
  [ctx]
  (let [{:keys [at ok detail]} @probe-cache
        now (System/currentTimeMillis)]
    (if (< (- now at) probe-cache-ttl-ms)
      [ok detail]
      (let [result (try
                     (storage/probe-writable! (:store ctx))
                     [true nil]
                     (catch Throwable t [false (ex-message t)]))]
        (reset! probe-cache {:at now :ok (first result) :detail (second result)})
        result))))

(defn- readiness-detail
  "nil when ready (migrations current AND storage writable), else the reason."
  [ctx]
  (or (when-not (try
                  (storage/migrations-current? (:store ctx))
                  (catch Throwable _ false))
        "database migrations are not current")
      (let [[ok detail] (cached-write-probe ctx)]
        (when-not ok
          (or detail "storage is not writable")))))

(defn readyz [ctx request]
  (if-let [detail (readiness-detail ctx)]
    (problem 503 "not-ready" detail)
    (json-response 200 {:status "ok"})))

;; --- OpenAPI document (served spec) ------------------------------------------
;; resources/openapi.json is docs/openapi.yaml (FROZEN) converted by
;; scripts/gen-openapi-json.py and baked into the binary via :jolt/build
;; {:embed ["resources"]} — same embedding path as the migration DDL.

(def ^:private openapi-json
  (delay
    (let [url (io/resource "openapi.json")]
      (when-not url
        (throw (ex-info "openapi.json resource missing" {:resource "openapi.json"})))
      (slurp url))))

(defn openapi-doc
  "GET {path_prefix}/openapi.json: the API contract as OpenAPI 3 JSON —
   public, unauthenticated, constant content (a static document)."
  [ctx request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body @openapi-json})

;; --- management /manage/v1 (admin token, Q27/Q52) ----------------------------

(defn manage-stats
  "GET /manage/v1/stats: live item count, expired-but-uncollected count, total
   bytes, oldest/newest created_at, active key count (snake_case JSON keys)."
  [ctx request]
  (let [s (storage/stats (:store ctx))]
    (json-response 200 {:items_live (:items-live s)
                        :items_expired (:items-expired s)
                        :bytes_total (:bytes-total s)
                        :oldest_created (:oldest-created s)
                        :newest_created (:newest-created s)
                        :api_keys_active (:api-keys-active s)})))

(defn manage-cleanup
  "POST /manage/v1/cleanup: synchronous run-cleanup-once!, returns its result
   map {:deleted :orphans :duration-ms}."
  [ctx request]
  (json-response 200 (cleanup/run-cleanup-once! (:store ctx))))

(defn manage-list-keys
  "GET /manage/v1/keys: public key fields only — never secrets or hashes."
  [ctx request]
  (json-response 200 {:keys (storage/list-api-keys (:store ctx))}))

(defn manage-create-key
  "POST /manage/v1/keys: create an API key. The response carries the plaintext
   secret exactly once (never logged, never listed afterwards)."
  [ctx request]
  (if-not (json-content-type? request)
    (not-json-415)
    (let [parsed (json-body request (request-body-cap
                                      (:max_item_bytes (:config ctx))))]
      (if-let [st (:status parsed)]
        (problem st (:slug parsed) (:detail parsed))
        (let [m (:value parsed)]
          (cond
            (not (map? m))
            (problem 400 "validation-failed" "request body must be a JSON object")

            (or (not (string? (:label m))) (str/blank? (:label m)))
            (problem 400 "validation-failed" "label is required")

            :else
            (try
              (json-response 201
                             (auth/create-key! (:store ctx)
                                               {:label (:label m)
                                                :scopes (:scopes m)}))
              (catch clojure.lang.ExceptionInfo e
                (problem 400 "validation-failed" (ex-message e))))))))))

(defn manage-revoke-key
  "DELETE /manage/v1/keys/:key_id: revoke (204 once, then 404; unknown 404)."
  [ctx request]
  (if (auth/revoke-key! (:store ctx) (get-in request [:path-params :key_id]))
    (no-content)
    (problem 404 "not-found" "key not found or already revoked")))

(defn manage-purge-item
  "DELETE /manage/v1/items/:name: admin purge — bypasses ownership, deletes any
   live item; 404 for unknown or expired names."
  [ctx request]
  (if (storage/delete-item! (:store ctx) (get-in request [:path-params :name]))
    (no-content)
    (problem 404 "not-found" "item not found or expired")))

(defn manage-config
  "GET /manage/v1/config: effective config (schema defaults merged over the
   startup config) with secrets redacted via M1's redact-config. Read-only."
  [ctx request]
  (json-response 200 (config/redact-config
                       (merge config/defaults (:config ctx)))))

;; --- request side-effect metrics (Q47) ---------------------------------------

(defn- record-side-effects!
  "Item lifecycle counters derived from one dispatched response. Byte counts
   come from the handler's ::log-fields (stored content bytes for uploads,
   served bytes for downloads — JSON and multipart alike), so no request body
   is re-measured here and no name/key id ever becomes a label value."
  [request route resp]
  (let [fields (::log-fields resp)]
    (when-let [n (:size fields)]
      (if (= :get (:request-method request))
        (metrics/inc! :downloaded_bytes_total {} (long n))
        (metrics/inc! :uploaded_bytes_total {} (long n))))
    (when (and (= 201 (:status resp)) (= :post (:request-method request))
               (str/includes? (str route) "/items"))
      (metrics/inc! :items_created_total {}))
    (when (and (= 204 (:status resp)) (= :delete (:request-method request))
               (str/includes? (str route) "/items"))
      (metrics/inc! :items_deleted_total {}))))

(defn- call-handler
  "Run one handler, catching failures to increment storage_errors_total and
   answer 500 problem+json (never leak internals). Returns the response."
  [ctx request handler]
  (try
    (handler ctx request)
    (catch Throwable _
      (metrics/inc! :storage_errors_total {})
      (problem 500 "internal-error" "internal error"))))

;; --- routing / dispatch ------------------------------------------------------

(defn- route-table [cfg]
  (let [items (str (api-base cfg) "/items")
        m (str (:path_prefix cfg) "/manage/v1")
        p (:path_prefix cfg)]
    [[(str p "/healthz") {:kind :public :get #'healthz}]
     [(str p "/readyz") {:kind :public :get #'readyz}]
     [(str p "/openapi.json") {:kind :public :get #'openapi-doc}]
     [items {:get #'list-items :post #'create-item}]
     [(str items "/:name") {:get #'get-item
                            :patch #'patch-item
                            :delete #'delete-item}]
     [(str items "/:name/content") {:get #'get-content}]
     [(str m "/stats") {:kind :manage :get #'manage-stats}]
     [(str m "/cleanup") {:kind :manage :post #'manage-cleanup}]
     [(str m "/keys") {:kind :manage :get #'manage-list-keys
                       :post #'manage-create-key}]
     [(str m "/keys/:key_id") {:kind :manage :delete #'manage-revoke-key}]
     [(str m "/items/:name") {:kind :manage :delete #'manage-purge-item}]
     [(str m "/config") {:kind :manage :get #'manage-config}]]))

(def ^:private http-methods #{:get :post :put :patch :delete :head :options})

(defn- allow-header [data]
  (str/join ", " (map (comp str/upper-case name)
                      (filter http-methods (keys data)))))

(defn- dispatch
  "Route match -> 404 -> method 405 -> kind: :public handlers run unguarded;
   :manage requires the admin token (401 problem+json otherwise) and marks
   the request :admin; :api runs the auth chain (401/403/429) then the
   ownership gate (403) then the handler. Returns [response key-id route]:
   key-id is the authenticated key (nil when the request never got that far),
   route is the matched template (nil when no route matched) — both feed the
   request log line and request metrics."
  [ctx request]
  (let [router (:router ctx)]
    (if-let [match (reitit/match-by-path router (:uri request))]
      (let [data (:data match)
            method (:request-method request)
            route (:template match)
            request (assoc request
                           :path-params (:path-params match)
                           :route route)]
         (if (and (= :options method)
                  (cors/preflight? request)
                  (cors/allowed? (:config ctx) request))
           ;; Q55 preflight: answered only for allowlisted origins; anything
           ;; else falls through to the 405 below (no CORS headers)
           [(cors/preflight-response (get-in request [:headers "origin"])
                                     (allow-header data))
            nil route]
           (if-let [handler (get data method)]
             (case (:kind data)
               :public
               [(call-handler ctx request handler) nil route]

               :manage
               (if (accepted-key ctx request)
                 [(call-handler ctx (assoc request :admin true) handler) nil route]
                 [(problem 401 "unauthorized" "admin token required") nil route])

               (let [authz (authorize ctx request method)]
                 (if-let [resp (:response authz)]
                   [resp (:key-id authz) route]
                   (let [req (:request authz)
                         forbidden (ownership-gate ctx req)]
                     (if forbidden
                       [forbidden (:key-id authz) route]
                       [(call-handler ctx req handler) (:key-id authz) route])))))
             [(problem 405 "method-not-allowed"
                       (str (str/upper-case (name method)) " not allowed on this path")
                       {"Allow" (allow-header data)})
              nil route])))
      [(problem 404 "not-found" (str "no route for " (:uri request))) nil nil])))

(defn make-handler
  "Ring-style handler over plain request maps. ctx:
   {:store :config :multipart-parser} — :multipart-parser is the ONE
   replaceable multipart seam (metapaste.http supplies the ring-chez based
   implementation); it returns {:params :files} or {:error {:status :slug :detail}}."
  [{:keys [store config] :as ctx}]
  (let [ctx (assoc ctx
                   :router (reitit/router (route-table config))
                   ;; per-server token buckets (Q31), one atom per handler
                   :limiter (atom {}))]
    (fn [request]
      (let [rid (request-id request)
            t0 (System/currentTimeMillis)]
        (log/with-request-id rid
          (let [[resp* key-id route] (dispatch ctx request)
                fields (::log-fields resp*)
                resp (-> resp*
                         (dissoc ::log-fields)
                         (assoc-in [:headers "X-Request-Id"] rid)
                         (cors/attach (:config ctx) request))]
            ;; every dispatch outcome is counted (Q47), from the final status
            (record-http! route (:request-method request) (:status resp) t0)
            (record-side-effects! request route resp*)
            (log/info "request"
                      (cond-> {:method (str/upper-case (name (:request-method request)))
                               :route (or route (:uri request))
                               :status (:status resp)
                               :latency_ms (- (System/currentTimeMillis) t0)}
                        key-id (assoc :key_id key-id)
                        (or (:name fields) (get-in request [:path-params :name]))
                        (assoc :name (or (:name fields)
                                         (get-in request [:path-params :name])))
                        (:size fields) (assoc :size (:size fields))))
            resp))))))

;; --- metrics endpoint (Q46): only on the internal listener --------------------

(defn metrics-handler
  "Ring handler for the INTERNAL metrics listener (never mounted on the public
   route table): GET /metrics -> Prometheus text exposition 0.0.4, anything
   else -> problem+json. Scrapes are logged like requests (request_id per Q50)
   but never counted in http_requests_total — they are not API traffic."
  [request]
  (let [rid (request-id request)
        t0 (System/currentTimeMillis)]
    (log/with-request-id rid
      (let [resp (cond
                   (not= "/metrics" (:uri request))
                   (problem 404 "not-found"
                            "the metrics listener serves only GET /metrics")

                   (not= :get (:request-method request))
                   (problem 405 "method-not-allowed" "only GET is served here"
                            {"Allow" "GET"})

                   :else
                   {:status 200
                    :headers {"Content-Type"
                              "text/plain; version=0.0.4; charset=utf-8"}
                    :body (metrics/render)})]
        (log/info "request" {:method (str/upper-case (name (:request-method request)))
                             :route (if (= "/metrics" (:uri request))
                                      "/metrics"
                                      (:uri request))
                             :status (:status resp)
                             :latency_ms (- (System/currentTimeMillis) t0)})
        (assoc-in resp [:headers "X-Request-Id"] rid)))))
