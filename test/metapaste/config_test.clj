(ns metapaste.config-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [metapaste.config :as config]))

(defn- with-config-file
  "Writes content to a fixed EDN file under target/, calls f with its path."
  [name content f]
  (let [dir (java.io.File. "target/test-config")]
    (.mkdirs dir)
    (let [file (java.io.File. dir name)]
      (spit file content)
      (try (f (str file))
           (finally (.delete file))))))

(deftest defaults-match-spec
  (let [cfg (config/load-config {:env {}})]
    (is (= 259200 (:default_ttl_seconds cfg)))
    (is (= false (:allow_never_expire cfg)))
    (is (= 10485760 (:max_item_bytes cfg)))
    (is (= 60 (:min_ttl_seconds cfg)))
    (is (= 31536000 (:max_ttl_seconds cfg)))
    (is (= 10 (:rate_limit_rps cfg)))
    (is (= 20 (:rate_limit_burst cfg)))
    (is (= "./data" (:data_dir cfg)))
    (is (nil? (:admin_token cfg)))
    (is (= 8080 (:port cfg)))
    (is (= "" (:path_prefix cfg)))
    (is (= "mp_dev_devkey" (:dev_api_key cfg)))
    (is (= 3600 (:cleanup_interval_s cfg)))))

(deftest metrics-port-key
  (testing "default is main port + 1"
    (is (= 8081 (:metrics_port (config/load-config {:env {}}))))
    (is (= 9001 (:metrics_port (config/load-config
                                 {:env {"METAPASTE_PORT" "9000"}})))))
  (testing "env override wins"
    (is (= 9100 (:metrics_port (config/load-config
                                 {:env {"METAPASTE_PORT" "9000"
                                        "METAPASTE_METRICS_PORT" "9100"}})))))
  (testing "out-of-range values are rejected"
    (is (thrown? Exception
                 (config/load-config {:env {"METAPASTE_METRICS_PORT" "70000"}})))))

(deftest cors-allowlist-key
  (testing "default is an empty list (CORS disabled)"
    (is (= [] (:cors_allow_origins (config/load-config {:env {}})))))
  (testing "env value is a comma-separated list, trimmed, blanks dropped"
    (is (= ["https://a.example" "https://b.example"]
           (:cors_allow_origins
            (config/load-config
             {:env {"METAPASTE_CORS_ALLOW_ORIGINS"
                    " https://a.example , https://b.example , "}})))))
  (testing "empty env value keeps CORS disabled"
    (is (= [] (:cors_allow_origins
               (config/load-config
                {:env {"METAPASTE_CORS_ALLOW_ORIGINS" ""}})))))
  (testing "config file value is a vector of origins"
    (with-config-file
      "cors.edn" "{:cors_allow_origins [\"https://a.example\" \"http://localhost:3000\"]}"
      (fn [path]
        (is (= ["https://a.example" "http://localhost:3000"]
               (:cors_allow_origins
                (config/load-config {:env {} :config-file path})))))))
  (testing "entries must be origins (scheme://host[:port], no path, no wildcard)"
    (doseq [bad ["app.example" "https://x.example/path" "*"]]
      (is (thrown-with-msg? Exception #"cors_allow_origins"
            (config/load-config
             {:env {"METAPASTE_CORS_ALLOW_ORIGINS" bad}}))
          (str "rejects " (pr-str bad)))))
  (testing "non-list value in the config file is rejected"
    (with-config-file "cors-bad.edn" "{:cors_allow_origins 5}"
      (fn [path]
        (is (thrown-with-msg? Exception #"cors_allow_origins"
              (config/load-config {:env {} :config-file path})))))))

(deftest precedence-env-over-file-over-default
  (with-config-file
    "precedence.edn"
    "{:default_ttl_seconds 111 :rate_limit_rps 5 :data_dir \"./from-file\"}"
    (fn [path]
      (testing "file beats defaults"
        (let [cfg (config/load-config {:env {} :config-file path})]
          (is (= 111 (:default_ttl_seconds cfg)))
          (is (= 5 (:rate_limit_rps cfg)))
          (is (= "./from-file" (:data_dir cfg)))
          ;; untouched keys keep their defaults
          (is (= 10485760 (:max_item_bytes cfg)))))
      (testing "env beats file beats defaults"
        (let [cfg (config/load-config {:env {"METAPASTE_DEFAULT_TTL_SECONDS" "222"
                                              "METAPASTE_DATA_DIR" "./from-env"}
                                        :config-file path})]
          (is (= 222 (:default_ttl_seconds cfg)))
          (is (= "./from-env" (:data_dir cfg)))
          (is (= 5 (:rate_limit_rps cfg))))))))

(deftest validation-fails-fast
  (testing "max_item_bytes over the hard ceiling"
    (is (thrown-with-msg? Exception #"hard ceiling"
          (config/load-config {:env {"METAPASTE_MAX_ITEM_BYTES" "200000000"}}))))
  (testing "default_ttl_seconds outside [min, max]"
    (is (thrown-with-msg? Exception #"default_ttl_seconds"
          (config/load-config {:env {"METAPASTE_MAX_TTL_SECONDS" "1000"}}))))
  (testing "min_ttl_seconds greater than max_ttl_seconds"
    (is (thrown-with-msg? Exception #"min_ttl_seconds"
          (config/load-config {:env {"METAPASTE_MIN_TTL_SECONDS" "5000"
                                     "METAPASTE_MAX_TTL_SECONDS" "1000"}}))))
  (testing "non-numeric TTL"
    (is (thrown-with-msg? Exception #"not an integer"
          (config/load-config {:env {"METAPASTE_RATE_LIMIT_RPS" "ten"}}))))
  (testing "boolean given a non-boolean"
    (is (thrown-with-msg? Exception #"boolean"
          (config/load-config {:env {"METAPASTE_ALLOW_NEVER_EXPIRE" "maybe"}}))))
  (testing "port outside 0-65535"
    (is (thrown-with-msg? Exception #"port"
          (config/load-config {:env {"METAPASTE_PORT" "70000"}})))
    (is (thrown-with-msg? Exception #"port"
          (config/load-config {:env {"METAPASTE_PORT" "-1"}}))))
  (testing "path_prefix shapes"
    (is (thrown-with-msg? Exception #"path_prefix"
          (config/load-config {:env {"METAPASTE_PATH_PREFIX" "no-slash"}})))
    (is (thrown-with-msg? Exception #"path_prefix"
          (config/load-config {:env {"METAPASTE_PATH_PREFIX" "/trailing/"}})))
    (is (= "/mp" (:path_prefix (config/load-config {:env {"METAPASTE_PATH_PREFIX" "/mp"}}))))
    (is (= "" (:path_prefix (config/load-config {:env {"METAPASTE_PATH_PREFIX" ""}})))))
  (testing "config file that is not EDN"
    (with-config-file "bad.edn" "{oops"
      (fn [path]
        (is (thrown-with-msg? Exception #"not valid EDN"
              (config/load-config {:env {} :config-file path}))))))
  (testing "explicit config file path that does not exist"
    (is (thrown-with-msg? Exception #"not found"
          (config/load-config {:env {} :config-file "target/test-config/nope.edn"}))))
  (testing "cleanup_interval_s below 1"
    (is (thrown-with-msg? Exception #"cleanup_interval_s"
          (config/load-config {:env {"METAPASTE_CLEANUP_INTERVAL_S" "0"}})))))

(deftest unknown-keys-warn
  (let [{:keys [warnings]} (config/load-config {:env {"METAPASTE_BOGUS" "1"
                                                       "METAPASTE_WHAT_IS_THIS" "x"}})]
    (is (= 2 (count warnings)))
    (is (some #(str/includes? % "bogus") warnings))
    (is (some #(str/includes? % "what_is_this") warnings)))
  (testing "unknown key in the config file"
    (with-config-file "unknown.edn" "{:mystery 1}"
      (fn [path]
        (let [{:keys [warnings]} (config/load-config {:env {} :config-file path})]
          (is (some #(str/includes? % "mystery") warnings)))))))

(deftest env-coercion
  (let [cfg (config/load-config {:env {"METAPASTE_ALLOW_NEVER_EXPIRE" "yes"
                                       "METAPASTE_MIN_TTL_SECONDS" "120"
                                       "METAPASTE_CLEANUP_INTERVAL_S" "1"}})]
    (is (= true (:allow_never_expire cfg)))
    (is (= 120 (:min_ttl_seconds cfg)))
    (is (= 1 (:cleanup_interval_s cfg)))))

(deftest redact-hides-secrets
  (let [cfg (config/load-config {:env {"METAPASTE_ADMIN_TOKEN" "super-secret-token-xyz"}})
        red (config/redact-config cfg)]
    (is (= "***REDACTED***" (:admin_token red)))
    (is (= 259200 (:default_ttl_seconds red)))
    (is (not (str/includes? (pr-str red) "super-secret-token-xyz"))))
  (testing "any key matching token/secret/password is redacted"
    (let [red (config/redact-config {:admin_token "a"
                                     :api_secret "b"
                                     :db_password "c"
                                     :x_token "d"
                                     :plain "keep"})]
      (is (= {"admin_token" "***REDACTED***"
              "api_secret" "***REDACTED***"
              "db_password" "***REDACTED***"
              "x_token" "***REDACTED***"
              "plain" "keep"}
             (into {} (map (fn [[k v]] [(name k) v]) red))))))
  (testing "dev_api_key is redacted like admin_token"
    (let [red (config/redact-config {:dev_api_key "mp_dev_leak" :port 8080})]
      (is (= "***REDACTED***" (:dev_api_key red)))
      (is (= 8080 (:port red))))))