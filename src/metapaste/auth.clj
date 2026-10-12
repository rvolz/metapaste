(ns metapaste.auth
  "API-key auth (Q26/Q27): key creation, lookup+verify, scope checks, and the
   ownership/admin predicates M5 exposes (M6 wires /manage/v1 on top of them).
   Plain maps in and out — no framework imports.

   Crypto surface (RQ12): secrets come from java.security.SecureRandom (jolt
   crypto provider, OpenSSL RAND_bytes), hashes from metapaste.storage's
   sha256-hex (java.security.MessageDigest via jolt-lang/crypto), and every
   secret/hash comparison goes through crypto.equality/eq? — never =.
   A secret exists in plaintext ONLY in create-key!'s return value."
  (:require [clojure.string :as str]
            [crypto.equality :as crypto-eq]
            [metapaste.names :as names]
            [metapaste.storage :as storage])
  (:import [java.security SecureRandom]
           [java.time Instant]
           [java.util Base64]))

(def valid-scopes #{"read" "write"})

(def ^:private write-methods #{:post :put :patch :delete})
(def ^:private key-id-length 8)
(def ^:private secret-bytes 32)
(def ^:private key-id-re
  (re-pattern (str "[" names/alphabet "]{" key-id-length "}")))
(def ^:private secret-re #"[A-Za-z0-9_-]{32,}")
(def ^:private rng (SecureRandom.))

;; --- generation --------------------------------------------------------------

(defn- random-key-id []
  (let [n (count names/alphabet)
        sb (StringBuilder. key-id-length)]
    (dotimes [_ key-id-length]
      (.append sb (.charAt names/alphabet (.nextInt rng n))))
    (str sb)))

(defn- random-secret []
  ;; 32 CSPRNG bytes as base64url without padding -> 43 chars (Q26)
  (let [ba (byte-array secret-bytes)]
    (.nextBytes rng ba)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) ba)))

(defn- normalize-scopes
  "nil -> \"read\" (least privilege); \"read,write\" string or a coll of
   read/write names -> canonical comma-joined string; throws on anything else."
  [scopes]
  (if (nil? scopes)
    "read"
    (let [parts (if (string? scopes)
                  (str/split scopes #",")
                  (map #(if (keyword? %) (name %) (str %)) scopes))
          cleaned (->> parts (map str/trim) (remove str/blank?) distinct vec)]
      (when (or (empty? cleaned) (seq (remove valid-scopes cleaned)))
        (throw (ex-info "scopes must be a non-empty subset of read, write"
                        {:scopes cleaned})))
      (str/join "," (sort cleaned)))))

;; --- lookup + verify ---------------------------------------------------------

(defn- parse-key
  "mp_<keyid>_<secret> -> {:key-id :secret}; nil unless well-formed."
  [token]
  (when (and (string? token) (str/starts-with? token "mp_"))
    (let [body (subs token 3)
          i (str/index-of body "_")]
      (when i
        (let [key-id (subs body 0 i)
              secret (subs body (inc i))]
          (when (and (re-matches key-id-re key-id)
                     (re-matches secret-re secret))
            {:key-id key-id :secret secret}))))))

(defn- verify [store token]
  (if-let [{:keys [key-id secret]} (parse-key token)]
    (if-let [row (storage/get-api-key store key-id)]
      (if (crypto-eq/eq? (:key_hash row)
                         (storage/sha256-hex (.getBytes ^String secret "UTF-8")))
        (if (:revoked_at row)
          {:error "key revoked"}
          {:key-id key-id
           :scopes (set (remove str/blank? (str/split (:scopes row) #",")))})
        {:error "unknown key"})
      {:error "unknown key"})
    {:error "malformed bearer key"}))

(defn check-bearer
  "Authorization header value -> {:key-id :scopes #{...}} on a live key, or
   {:error detail} with a distinct detail per 401 cause (Q23): missing bearer
   key / malformed bearer key / unknown key / key revoked."
  [store header]
  (if (nil? header)
    {:error "missing bearer key"}
    (if (str/starts-with? (str/lower-case header) "bearer ")
      (verify store (subs header 7))
      {:error "malformed bearer key"})))

(defn authenticate
  "Raw mp_... token -> {:key-id :scopes #{...}} on a live key; nil for
   malformed, unknown, wrong-secret, or revoked tokens."
  [store token]
  (let [result (verify store token)]
    (when-not (:error result) result)))

;; --- scopes (Q27) ------------------------------------------------------------

(defn scope-allows?
  "True when scopes permit this request method; write implies read."
  [scopes method]
  (if (contains? write-methods method)
    (contains? scopes "write")
    (or (contains? scopes "read") (contains? scopes "write"))))

;; --- key lifecycle -----------------------------------------------------------

(defn create-key!
  "Create an API key (Q26). Opts: :label (required, non-blank), :scopes
   (nil -> \"read\"; a \"read,write\" string or a coll of read/write names).
   Returns {:key_id :secret :label :scopes :created_at} where :secret is the
   FULL plaintext mp_<keyid>_<secret> key — the only place it exists; only
   sha256(secret) is stored. Never log the return value: the management route
   (M6) hands it to the client exactly once."
  [store {:keys [label scopes]}]
  (when (or (nil? label) (str/blank? label))
    (throw (ex-info "label is required" {:label label})))
  (let [scopes-str (normalize-scopes scopes)
        key-id (random-key-id)
        secret (random-secret)
        stored (storage/create-api-key!
                 store
                 {:key_id key-id
                  :key_hash (storage/sha256-hex (.getBytes ^String secret "UTF-8"))
                  :label label
                  :scopes scopes-str
                  :created_at (storage/iso-utc (Instant/now))})]
    {:key_id (:key_id stored)
     :secret (str "mp_" key-id "_" secret)
     :label (:label stored)
     :scopes (:scopes stored)
     :created_at (:created_at stored)}))

(defn revoke-key!
  "Stamp revoked_at on the key; subsequent auth fails with 401. True only
   when a live row was revoked — false for unknown or already-revoked keys."
  [store key-id]
  (storage/revoke-api-key! store key-id (storage/iso-utc (Instant/now))))

;; --- admin + ownership -------------------------------------------------------

(defn admin?
  "Predicate over a request: true only when M6's admin middleware has
   authenticated it by setting :admin true. Defaults to false here — M5 ships
   the seam, M6 wires the middleware."
  [req]
  (true? (:admin req)))

(defn admin-token-ok?
  "Constant-time (crypto.equality/eq?) comparison of a presented token against
   the static :admin_token from config; false when either side is absent."
  [cfg presented]
  (let [expected (:admin_token cfg)]
    (boolean (and (string? expected)
                  (string? presented)
                  (crypto-eq/eq? expected presented)))))

(defn may-modify?
  "Owner check for PATCH/DELETE (Q17/Q18): the caller's key_id owns the row,
   or the request is admin. Callers 404 unknown/expired rows before asking."
  [req row]
  (or (admin? req)
      (= (:key-id req) (:owner_key_id row))))
