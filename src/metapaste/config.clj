(ns metapaste.config
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(def hard-max-item-bytes 104857600)

(def defaults
  {:default_ttl_seconds 259200
   :allow_never_expire false
   :max_item_bytes 10485760
   :min_ttl_seconds 60
   :max_ttl_seconds 31536000
   :rate_limit_rps 10
   :rate_limit_burst 20
   :data_dir "./data"
   :admin_token nil
   :port 8080
   :path_prefix ""
   :dev_api_key "mp_dev_devkey"
   :cleanup_interval_s 3600})

(def ^:private schema
  {:default_ttl_seconds :int
   :allow_never_expire :bool
   :max_item_bytes :int
   :min_ttl_seconds :int
   :max_ttl_seconds :int
   :rate_limit_rps :int
   :rate_limit_burst :int
   :data_dir :string
   :admin_token :string
   :port :int
   :path_prefix :string
   :dev_api_key :string
   :cleanup_interval_s :int
   :metrics_port :int})

(def ^:private env-prefix "METAPASTE_")

(def ^:private env-key->key
  (into {} (for [k (keys schema)]
             [(str env-prefix (str/upper-case (name k))) k])))

(def ^:private reserved-env-keys
  ;; env vars consumed outside the config schema (Q54)
  #{"METAPASTE_CONFIG_FILE"})

(def ^:private secret-key-re #"(?i)token|secret|password|api_key")

(defn- fail [msg]
  (throw (ex-info (str "config: " msg) {})))

(defn- coerce [k type raw]
  (try
    (case type
      :int (if (integer? raw)
             raw
             (if (and (string? raw) (re-matches #"-?\d+" raw))
               (Long/parseLong raw)
               (throw (Exception. (str (name k) "=" (pr-str raw) " is not an integer")))))
      :string (if (or (nil? raw) (string? raw)) raw (str raw)))
    (catch Exception e
      (if (str/includes? (ex-message e) "is not a")
        (fail (ex-message e))
        (throw e)))))

(defn- coerce-bool [k raw]
  (cond
    (boolean? raw) raw
    (nil? raw) nil
    (string? raw) (let [s (str/lower-case raw)]
                    (cond
                      (contains? #{"true" "yes"} s) true
                      (contains? #{"false" "no"} s) false
                      :else (fail (str (name k) "=" (pr-str raw) " is not a boolean (expected true/false/yes/no)"))))
    :else (fail (str (name k) "=" (pr-str raw) " is not a boolean (expected true/false/yes/no)"))))

(defn- coerce-value [k type raw]
  (if (= type :bool)
    (coerce-bool k raw)
    (coerce k type raw)))

(defn- read-config-file
  "Reads and parses the EDN config file; nil path means no file."
  [path]
  (when path
    (let [f (java.io.File. path)]
      (when-not (.exists f)
        (fail (str "config file not found: " path)))
      (let [parsed (try
                     (edn/read-string (slurp f))
                     (catch Exception _ nil))]
        (when-not (map? parsed)
          (fail (str "config file is not valid EDN (or not a map): " path)))
        parsed))))

(defn- env-layer
  "Splits the env map into known config values and unknown-key warnings."
  [env]
  (reduce-kv
    (fn [acc env-key raw]
      (if (and (str/starts-with? env-key env-prefix)
               (not (contains? reserved-env-keys env-key)))
        (if-let [k (get env-key->key env-key)]
          (update acc :values assoc k (coerce-value k (get schema k) raw))
          (let [snake (str/lower-case (subs env-key (count env-prefix)))]
            (update acc :warnings conj
                    (str "unknown environment variable " env-key
                         " (unknown config key \"" snake "\", ignored)"))))
        acc))
    {:values {} :warnings []}
    env))

(defn- file-layer
  "Validates the file map against the schema; unknown keys warn."
  [file-map path]
  (reduce-kv
    (fn [acc k raw]
      (if (contains? schema k)
        (update acc :values assoc k (coerce-value k (get schema k) raw))
        (update acc :warnings conj
                (str "unknown key " (name k) " in " path " (ignored)"))))
    {:values {} :warnings []}
    (or file-map {})))

(defn- validate! [cfg]
  (let [max-b (:max_item_bytes cfg)
        mn (:min_ttl_seconds cfg)
        mx (:max_ttl_seconds cfg)
        dt (:default_ttl_seconds cfg)]
    (when (neg? max-b)
      (fail (str "max_item_bytes must not be negative, got " max-b)))
    (when (> max-b hard-max-item-bytes)
      (fail (str "max_item_bytes " max-b " exceeds the hard ceiling of "
                 hard-max-item-bytes)))
    (when (< mn 60)
      (fail (str "min_ttl_seconds " mn " is below the floor of 60")))
    (when (> mx 31536000)
      (fail (str "max_ttl_seconds " mx " exceeds the ceiling of 31536000")))
    (when (> mn mx)
      (fail (str "min_ttl_seconds " mn " exceeds max_ttl_seconds " mx)))
    (when (or (< dt mn) (> dt mx))
      (fail (str "default_ttl_seconds " dt " is outside [min_ttl_seconds " mn
                 ", max_ttl_seconds " mx "]")))
    (when (< (:rate_limit_rps cfg) 1)
      (fail (str "rate_limit_rps must be at least 1, got " (:rate_limit_rps cfg))))
    (when (< (:cleanup_interval_s cfg) 1)
      (fail (str "cleanup_interval_s must be at least 1, got " (:cleanup_interval_s cfg))))
    (when (< (:rate_limit_burst cfg) 1)
      (fail (str "rate_limit_burst must be at least 1, got " (:rate_limit_burst cfg))))
    (when (or (< (:port cfg) 0) (> (:port cfg) 65535))
      (fail (str "port " (:port cfg) " is outside 0-65535")))
    (when (or (< (:metrics_port cfg) 0) (> (:metrics_port cfg) 65535))
      (fail (str "metrics_port " (:metrics_port cfg) " is outside 0-65535")))
    (let [p (:path_prefix cfg)]
      (when-not (or (= "" p)
                    (and (str/starts-with? p "/") (not (str/ends-with? p "/"))))
        (fail (str "path_prefix " (pr-str p)
                   " must be empty or start with \"/\" and not end with \"/\""))))
    cfg))

(defn- resolve-config-file
  "METAPASTE_CONFIG_FILE wins and is required to exist; otherwise the default
  path is used only when it exists (the file is optional)."
  []
  (let [e (System/getenv "METAPASTE_CONFIG_FILE")]
    (if (and e (not (str/blank? e)))
      e
      (let [p (str (or (System/getenv "HOME") "") "/.config/metapaste/config.edn")]
        (when (.exists (java.io.File. p)) p)))))

(defn load-config
  "Loads effective config: env > file > defaults.
  Options: {:env map-of-string->string, :config-file path-or-nil}.
  Unknown keys produce :warnings; out-of-range values throw."
  ([] (load-config {:env (System/getenv) :config-file (resolve-config-file)}))
  ([{:keys [env config-file]}]
   (let [file-data (read-config-file config-file)
         fl (file-layer file-data (or config-file "(config file)"))
         el (env-layer (or env {}))
         base (-> defaults
                  (merge (:values fl))
                  (merge (:values el)))
         ;; metrics listener default: main port + 1 (Q46), explicit key wins
         merged (if (contains? base :metrics_port)
                  base
                  (assoc base :metrics_port (inc (long (:port base)))))
         cfg (validate! merged)
         warnings (vec (concat (:warnings fl) (:warnings el)))]
     (cond-> cfg
       (seq warnings) (assoc :warnings warnings)))))

(defn redact-config
  "Returns a copy with the value of every secret-looking key replaced."
  [m]
  (into {}
        (map (fn [[k v]]
               [k (if (re-find secret-key-re (name k)) "***REDACTED***" v)]))
        m))