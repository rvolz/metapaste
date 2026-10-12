(ns metapaste.auth-test
  "M5 auth: API-key service layer (Q26/Q27), the per-key token bucket (Q31),
   and the /api/v1 auth/ownership/rate-limit behavior through api/make-handler."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [jdbc.core :as jdbc]
            [metapaste.api :as api]
            [metapaste.auth :as auth]
            [metapaste.config :as config]
            [metapaste.ratelimit :as ratelimit]
            [metapaste.storage :as storage])
  (:import [java.io ByteArrayInputStream File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;; --- fixtures ----------------------------------------------------------------

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp5-auth-" (make-array FileAttribute 0))))

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

(defn- db-url [dir]
  (str "sqlite:" (.getAbsolutePath (File. ^File dir "metapaste.db"))))

(defn- with-db-conn [dir f]
  (let [c (jdbc/connection (db-url dir))]
    (try
      (f c)
      (finally (.close c)))))

(defn- make-handler [store cfg]
  (api/make-handler {:store store :config cfg :multipart-parser nil}))

(defn- cfg* []
  (config/load-config {:env {}}))

;; --- plain Ring request maps (handler-level tests) ---------------------------

(defn- json-str [m]
  (json/write-str m :escape-slash false :escape-unicode false))

(defn- bearer [token]
  (when token {"authorization" (str "Bearer " token)}))

(defn- get-req [uri token]
  {:request-method :get
   :uri uri
   :headers (or (bearer token) {})})

(defn- delete-req [uri token]
  {:request-method :delete
   :uri uri
   :headers (or (bearer token) {})})

(defn- json-req [method uri token body-map]
  (let [body (json-str body-map)]
    {:request-method method
     :uri uri
     :headers (merge {"content-type" "application/json"
                      "content-length" (str (alength (.getBytes body "UTF-8")))}
                     (or (bearer token) {}))
     :body (ByteArrayInputStream. (.getBytes body "UTF-8"))}))

(defn- problem? [resp status slug]
  (and (= status (:status resp))
       (= "application/problem+json"
          (get-in resp [:headers "Content-Type"]))
       (str/includes? (str (:body resp)) (str "urn:metapaste:problem:" slug))))

(defn- problem-detail [resp]
  (:detail (json/read-str (:body resp) :key-fn keyword)))

(defn- secret-part
  "The base64url half of mp_<keyid>_<secret>."
  [full]
  (subs full (+ 3 8 1)))

;; --- service layer: create / lookup / verify ---------------------------------

(deftest create-key-shape-and-format
  (with-store [store dir]
    (let [k (auth/create-key! store {:label "m5-key" :scopes "read,write"})]
      (testing "returns {:key_id :secret ...}; secret is the full plaintext key"
        (is (string? (:key_id k)))
        (is (= 8 (count (:key_id k))))
        (is (re-matches #"[0-9abcdefghjkmnpqrstvwxyz]{8}" (:key_id k)))
        (is (re-matches #"mp_[0-9abcdefghjkmnpqrstvwxyz]{8}_[A-Za-z0-9_-]{43}"
                        (:secret k)))
        (is (str/starts-with? (:secret k) (str "mp_" (:key_id k) "_")))
        (is (= "m5-key" (:label k)))
        (is (= "read,write" (:scopes k)))
        (is (re-matches #"\d{4}-\d{2}-\d{2}T[0-9:.]+Z" (:created_at k))))
      (testing "key_hash is 64 lowercase hex and equals sha256(secret part)"
        (let [row (storage/get-api-key store (:key_id k))]
          (is (= #{:key_id :key_hash :label :scopes :created_at :revoked_at}
                 (set (keys row))))
          (is (re-matches #"[0-9a-f]{64}" (:key_hash row)))
          (is (= (storage/sha256-hex (.getBytes (secret-part (:secret k)) "UTF-8"))
                 (:key_hash row)))
          (is (nil? (:revoked_at row)))
          (testing "a second lookup never returns the secret"
            (is (not (str/includes? (pr-str row) (secret-part (:secret k)))))
            (is (not (str/includes? (pr-str row) (:secret k))))))))
    (testing "scopes default to read"
      (let [k (auth/create-key! store {:label "ro"})]
        (is (= "read" (:scopes k)))
        (is (= #{"read"}
               (:scopes (auth/authenticate store (:secret k)))))))
    (testing "invalid scopes and blank labels are rejected"
      (is (thrown? Exception (auth/create-key! store {:label "x" :scopes "admin"})))
      (is (thrown? Exception (auth/create-key! store {:label "x" :scopes "read, admin"})))
      (is (thrown? Exception (auth/create-key! store {:label nil})))
      (is (thrown? Exception (auth/create-key! store {:label "  "}))))))

(deftest no-plaintext-secret-in-db
  (with-store [store dir]
    (let [k (auth/create-key! store {:label "leakcheck" :scopes "read,write"})
          part (secret-part (:secret k))]
      (with-db-conn dir
        (fn [c]
          (let [rows (vec (jdbc/fetch c ["SELECT * FROM api_keys"]))
                schema (vec (jdbc/fetch c
                             ["SELECT sql FROM sqlite_master WHERE sql IS NOT NULL"]))]
            (is (= 1 (count rows)))
            (is (not (str/includes? (pr-str rows) part))
                "plaintext secret must appear in no api_keys column")
            (is (not (str/includes? (pr-str rows) (:secret k))))
            (is (not (str/includes? (pr-str schema) part))
                "plaintext secret must appear nowhere in the schema either")))))))

(deftest authenticate-roundtrip-and-failures
  (with-store [store dir]
    (let [k (auth/create-key! store {:label "auth" :scopes "read,write"})
          secret (:secret k)
          part (secret-part secret)
          wrong (str (subs secret 0 (dec (count secret)))
                     (if (= \a (nth secret (dec (count secret)))) "b" "a"))]
      (testing "valid key authenticates: {:key-id :scopes}"
        (is (= {:key-id (:key_id k) :scopes #{"read" "write"}}
               (auth/authenticate store secret))))
      (testing "same-length wrong secret fails (constant-time compare result)"
        (is (= (count part) (count (secret-part wrong))))
        (is (nil? (auth/authenticate store wrong))))
      (testing "malformed tokens fail"
        (is (nil? (auth/authenticate store nil)))
        (is (nil? (auth/authenticate store "")))
        (is (nil? (auth/authenticate store "garbage")))
        (is (nil? (auth/authenticate store "mp_short_x")))
        (is (nil? (auth/authenticate store (str "mp_" (:key_id k)))))
        (is (nil? (auth/authenticate store (str "mp_" (:key_id k) "_"))))
        (is (nil? (auth/authenticate store (str "mp_" (:key_id k) "_not-b64!!")))))
      (testing "revoked -> nil; revoke is idempotent"
        (is (true? (auth/revoke-key! store (:key_id k))))
        (is (nil? (auth/authenticate store secret)))
        (is (false? (auth/revoke-key! store (:key_id k))))
        (is (false? (auth/revoke-key! store "zzzzzzzz")))))))

(deftest check-bearer-distinct-401-details
  (with-store [store dir]
    (let [live (auth/create-key! store {:label "live" :scopes "read"})
          dead (auth/create-key! store {:label "dead" :scopes "read"})
          _ (auth/revoke-key! store (:key_id dead))]
      (testing "each 401 cause carries its own detail"
        (is (= {:error "missing bearer key"} (auth/check-bearer store nil)))
        (is (= {:error "malformed bearer key"}
               (auth/check-bearer store "garbage")))
        (is (= {:error "malformed bearer key"}
               (auth/check-bearer store "Basic dXNlcg==")))
        (is (= {:error "unknown key"}
               (auth/check-bearer store "Bearer mp_zzzzzzzz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")))
        (is (= {:error "unknown key"}
               (auth/check-bearer store
                 (let [p (secret-part (:secret live))]
                   (str "Bearer mp_" (:key_id live) "_"
                        (subs p 0 42) (if (= \b (nth p 42)) "c" "b"))))))
        (is (= {:error "key revoked"}
               (auth/check-bearer store (str "Bearer " (:secret dead))))))
      (testing "valid key -> key-id and scopes"
        (is (= {:key-id (:key_id live) :scopes #{"read"}}
               (auth/check-bearer store (str "Bearer " (:secret live)))))
        (testing "scheme match is case-insensitive"
          (is (= {:key-id (:key_id live) :scopes #{"read"}}
                 (auth/check-bearer store (str "bearer " (:secret live))))))))))

(deftest scope-matrix-write-implies-read
  (testing "read key: reads yes, writes no"
    (is (auth/scope-allows? #{"read"} :get))
    (is (auth/scope-allows? #{"read"} :head))
    (is (not (auth/scope-allows? #{"read"} :post)))
    (is (not (auth/scope-allows? #{"read"} :patch)))
    (is (not (auth/scope-allows? #{"read"} :delete))))
  (testing "write implies read"
    (doseq [m [:get :head :post :patch :delete]]
      (is (auth/scope-allows? #{"write"} m)))
    (doseq [m [:get :head :post :patch :delete]]
      (is (auth/scope-allows? #{"read" "write"} m))))
  (testing "no scopes allows nothing"
    (is (not (auth/scope-allows? #{} :get)))
    (is (not (auth/scope-allows? #{} :post)))))

(deftest admin-predicate-and-token-compare
  (testing "admin? defaults to false; only an explicit true passes (M6 wires it)"
    (is (false? (auth/admin? {})))
    (is (false? (auth/admin? {:admin false})))
    (is (false? (auth/admin? {:admin "true"})))
    (is (true? (auth/admin? {:admin true}))))
  (testing "admin token compare is constant-time via crypto.equality/eq?"
    (is (true? (auth/admin-token-ok? {:admin_token "s3cret"} "s3cret")))
    (is (false? (auth/admin-token-ok? {:admin_token "s3cret"} "s3cret ")))
    (is (false? (auth/admin-token-ok? {:admin_token "s3cret"} "other")))
    (is (false? (auth/admin-token-ok? {:admin_token nil} "anything")))
    (is (false? (auth/admin-token-ok? {} "anything")))
    (is (false? (auth/admin-token-ok? {:admin_token "s3cret"} nil)))))

(deftest may-modify-ownership
  (let [row {:owner_key_id "aaaaaaaa"}]
    (is (true? (auth/may-modify? {:key-id "aaaaaaaa"} row)))
    (is (false? (auth/may-modify? {:key-id "bbbbbbbb"} row)))
    (is (false? (auth/may-modify? {:key-id "aaaaaaaa"} {:owner_key_id nil})))
    (is (true? (auth/may-modify? {:admin true :key-id "bbbbbbbb"} row))
        "admin bypasses ownership")))

;; --- rate limiting (Q31) -----------------------------------------------------

(deftest limiter-burst-then-refill
  (let [st (atom {})
        opts {:rps 100 :burst 3 :idle-ms 600000}]
    (testing "burst consumed, then denied, then refills"
      (dotimes [_ 3]
        (is (true? (ratelimit/allow? st "kkkkkkkk" opts))))
      (is (false? (ratelimit/allow? st "kkkkkkkk" opts))
          "4th immediate request over burst is denied")
      (Thread/sleep 50) ;; 100 rps -> 1 token in 10 ms; 50 ms is a safe margin
      (is (true? (ratelimit/allow? st "kkkkkkkk" opts))
          "allowed again after refill"))))

(deftest limiter-evicts-idle-buckets
  (let [st (atom {})
        opts {:rps 1 :burst 1 :idle-ms 30}]
    (is (true? (ratelimit/allow? st "aaaaaaaa" opts)))
    (is (false? (ratelimit/allow? st "aaaaaaaa" opts)))
    (Thread/sleep 60)
    (is (true? (ratelimit/allow? st "bbbbbbbb" opts))
        "new key triggers the lazy sweep")
    (is (= ["bbbbbbbb"] (vec (keys @st)))
        "bucket idle past :idle-ms is evicted (cardinality bound)")))

;; --- handler: 401 / 403 / 404 / 429 through api/make-handler -----------------

(deftest handler-valid-key-2xx-and-dev-key-removed
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          k (auth/create-key! store {:label "ok" :scopes "read,write"})]
      (is (= 200 (:status (h (get-req "/api/v1/items" (:secret k))))))
      (is (= 201 (:status (h (json-req :post "/api/v1/items" (:secret k)
                                       {:content "m5" :mime_type "text/plain"})))))
      (testing "the M3 :dev_api_key grants nothing anymore"
        (is (problem? (h (get-req "/api/v1/items" "mp_dev_devkey"))
                      401 "unauthorized"))))))

(deftest handler-401-variants-distinct-problems
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          dead (auth/create-key! store {:label "dead" :scopes "read"})
          _ (auth/revoke-key! store (:key_id dead))
          missing (h (get-req "/api/v1/items" nil))
          malformed (h (get-req "/api/v1/items" "garbage"))
          unknown (h (get-req "/api/v1/items"
                              "mp_zzzzzzzz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
          revoked (h (get-req "/api/v1/items" (:secret dead)))]
      (doseq [r [missing malformed unknown revoked]]
        (is (problem? r 401 "unauthorized")))
      (is (= "missing bearer key" (problem-detail missing)))
      (is (= "malformed bearer key" (problem-detail malformed)))
      (is (= "unknown key" (problem-detail unknown)))
      (is (= "key revoked" (problem-detail revoked)))
      (is (= 4 (count (set (map problem-detail
                                 [missing malformed unknown revoked]))))
          "the four 401s are distinct problem+json documents"))))

(deftest handler-read-scope-key-403-on-writes
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          rw (auth/create-key! store {:label "rw" :scopes "read,write"})
          ro (auth/create-key! store {:label "ro" :scopes "read"})
          item (:body (h (json-req :post "/api/v1/items" (:secret rw)
                                   {:content "own" :mime_type "text/plain"})))
          nm (:name (json/read-str item :key-fn keyword))]
      (testing "read key can read"
        (is (= 200 (:status (h (get-req "/api/v1/items" (:secret ro)))))))
      (testing "read key cannot POST/PATCH/DELETE (403 problem+json)"
        (let [post (h (json-req :post "/api/v1/items" (:secret ro)
                                {:content "x" :mime_type "text/plain"}))
              patch (h (json-req :patch (str "/api/v1/items/" nm) (:secret ro)
                                 {:description "d"}))
              del (h (delete-req (str "/api/v1/items/" nm) (:secret ro)))]
          (is (problem? post 403 "forbidden"))
          (is (problem? patch 403 "forbidden"))
          (is (problem? del 403 "forbidden"))
          (is (str/includes? (problem-detail post) "scope"))
          (is (str/includes? (problem-detail patch) "scope")))))))

(deftest handler-ownership-patch-delete
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          owner (auth/create-key! store {:label "owner" :scopes "read,write"})
          other (auth/create-key! store {:label "other" :scopes "read,write"})
          create (fn [token content]
                   (json/read-str
                     (:body (h (json-req :post "/api/v1/items" token
                                         {:content content :mime_type "text/plain"})))
                     :key-fn keyword))
          owned (create (:secret owner) "mine")
          stranger-item (create (:secret other) "theirs")]
      (testing "owner PATCH 200 / DELETE 204"
        (let [p (h (json-req :patch (str "/api/v1/items/" (:name owned))
                             (:secret owner) {:description "updated"}))]
          (is (= 200 (:status p)))
          (is (= "updated"
                 (:description (json/read-str (:body p) :key-fn keyword)))))
        (is (= 204 (:status (h (delete-req (str "/api/v1/items/" (:name stranger-item))
                                           (:secret other)))))))
      (testing "non-owner PATCH/DELETE -> 403, item untouched"
        (let [p (h (json-req :patch (str "/api/v1/items/" (:name owned))
                             (:secret other) {:description "hax"}))
              d (h (delete-req (str "/api/v1/items/" (:name owned))
                               (:secret other)))]
          (is (problem? p 403 "forbidden"))
          (is (str/includes? (problem-detail p) "not owned"))
          (is (problem? d 403 "forbidden"))
          (is (= "updated" (:description (storage/get-item store (:name owned))))
              "non-owner PATCH left the owner's description untouched")))
      (testing "unknown item PATCH/DELETE stays 404 (even for a write key)"
        (is (problem? (h (json-req :patch "/api/v1/items/zzzzzzzzzzzz"
                                   (:secret owner) {:description "x"}))
                      404 "not-found"))
        (is (problem? (h (delete-req "/api/v1/items/zzzzzzzzzzzz" (:secret owner)))
                      404 "not-found")))
      (testing "expired item PATCH/DELETE stays 404"
        (let [expired (create (:secret owner) "soon gone")]
          (with-db-conn dir
            (fn [c]
              (jdbc/execute! c
                ["UPDATE items SET expires_at = ? WHERE name = ?"
                 "2020-01-01T00:00:00.000000000Z" (:name expired)])))
          (is (problem? (h (json-req :patch (str "/api/v1/items/" (:name expired))
                                     (:secret owner) {:description "x"}))
                        404 "not-found"))
          (is (problem? (h (delete-req (str "/api/v1/items/" (:name expired))
                                       (:secret owner)))
                        404 "not-found")))))))

(deftest handler-create-records-owner-and-request-context
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          a (auth/create-key! store {:label "a" :scopes "read,write"})
          b (auth/create-key! store {:label "b" :scopes "read,write"})]
      (let [nm (:name (json/read-str
                        (:body (h (json-req :post "/api/v1/items" (:secret a)
                                            {:content "x" :mime_type "text/plain"})))
                        :key-fn keyword))
            nm2 (:name (json/read-str
                         (:body (h (json-req :post "/api/v1/items" (:secret b)
                                             {:content "y" :mime_type "text/plain"})))
                         :key-fn keyword))]
        (is (= (:key_id a) (:owner_key_id (storage/get-item store nm))))
        (is (= (:key_id b) (:owner_key_id (storage/get-item store nm2)))))
      (testing "handlers receive :key-id and :scopes on the request"
        (with-redefs [api/list-items
                      (fn [_ req]
                        {:status 200 :headers {}
                         :body (pr-str [(:key-id req) (:scopes req)])})]
          (is (= [(:key_id a) #{"read" "write"}]
                 (edn/read-string
                   (:body (h (get-req "/api/v1/items" (:secret a))))))
              "key-id and scope set are attached to the request map"))))))

(deftest handler-rate-limit-burst-then-429
  (with-store [store dir]
    ;; rps 1: a refill of one token per second cannot race this loop
    (let [cfg (assoc (cfg*) :rate_limit_rps 1 :rate_limit_burst 20)
          h (make-handler store cfg)
          k (auth/create-key! store {:label "rl" :scopes "read"})]
      (dotimes [_ 20]
        (is (= 200 (:status (h (get-req "/api/v1/items" (:secret k)))))))
      (let [r (h (get-req "/api/v1/items" (:secret k)))]
        (is (= 429 (:status r)))
        (is (= "1" (get-in r [:headers "Retry-After"])))
        (is (problem? r 429 "rate-limit-exceeded"))
        (is (= "application/problem+json"
               (get-in r [:headers "Content-Type"])))))))

(deftest handler-rate-limit-recovery-after-refill
  (with-store [store dir]
    (let [cfg (assoc (cfg*) :rate_limit_rps 100 :rate_limit_burst 2)
          h (make-handler store cfg)
          k (auth/create-key! store {:label "rl2" :scopes "read"})]
      (is (= 200 (:status (h (get-req "/api/v1/items" (:secret k))))))
      (is (= 200 (:status (h (get-req "/api/v1/items" (:secret k))))))
      (is (= 429 (:status (h (get-req "/api/v1/items" (:secret k))))))
      (Thread/sleep 200) ;; 100 rps -> >= 1 token (bucket caps at burst 2)
      (is (= 200 (:status (h (get-req "/api/v1/items" (:secret k)))))
          "allowed again after the bucket refills"))))

(deftest no-secret-in-captured-logs
  (with-store [store dir]
    (let [h (make-handler store (cfg*))
          k (auth/create-key! store {:label "logtest" :scopes "read,write"})
          row (storage/get-api-key store (:key_id k))
          part (secret-part (:secret k))
          out (with-out-str
                (h (get-req "/api/v1/items" (:secret k)))
                (h (json-req :post "/api/v1/items" (:secret k)
                             {:content "logged?" :mime_type "text/plain"}))
                (h (get-req "/api/v1/items" nil))                     ;; 401
                (h (get-req "/api/v1/items" (str (:secret k) "!")))   ;; 401
                (h (get-req "/api/v1/items"
                            "mp_zzzzzzzz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
                (auth/create-key! store {:label "second" :scopes "read"}))]
      (is (str/includes? out "\"key_id\"") "requests are logged at all")
      (is (not (str/includes? out (:secret k))) "full key never logged")
      (is (not (str/includes? out part)) "base64url secret never logged")
      (is (not (str/includes? out (:key_hash row))) "key hash never logged"))))
