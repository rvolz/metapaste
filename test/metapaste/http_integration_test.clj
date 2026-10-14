(ns metapaste.http-integration-test
  "Real HTTP against a server on an ephemeral port (jolt -M:test alias pulls
   jolt.http-client). jolt.http-client throws on non-2xx unless
   :throw-exceptions false — we always pass it and assert on :status directly.
   java.net.Socket is used where a client cannot omit Content-Length (411)."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [jdbc.core :as jdbc]
            [jolt.http-client :as http]
            [metapaste.auth :as auth]
            [metapaste.config :as config]
            [metapaste.http :as mhttp]
            [metapaste.storage :as storage])
  (:import [java.io BufferedReader File]
           [java.net Socket]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.time Instant]))

;; --- server fixture ----------------------------------------------------------

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "mp3-int-" (make-array FileAttribute 0))))

(defn- delete-recursively! [^File f]
  (doseq [c (reverse (file-seq f))]
    (.delete ^File c)))

(def ^:private live (atom nil))

(defn- base [] (:base @live))
(defn- store* [] (:store @live))
(defn- data-dir [] (:dir @live))

;; plaintext secret of a real key created by the fixture — the M3 fixed dev
;; key grants nothing since M5
(def ^:private bearer (atom nil))

(defn- auth-headers []
  {"authorization" (str "Bearer " @bearer)})

(defn- once-fixture [f]
  (let [dir (temp-dir)
        cfg (assoc (config/load-config {:env {}})
                   :port 0
                   ;; the suite fires hundreds of requests in seconds; give it
                   ;; rate-limit headroom so integration traffic never 429s
                   ;; (Q31 enforcement itself is tested in auth-test)
                   :rate_limit_rps 100000
                   :rate_limit_burst 1000000)
        store (storage/open! dir)
        _ (reset! bearer (:secret (auth/create-key! store
                                                     {:label "integration"
                                                      :scopes "read,write"})))
        handle (mhttp/start-server! store cfg {:port 0})]
    (try
      (reset! live {:base (str "http://127.0.0.1:" (:port handle))
                    :store store :dir dir :cfg cfg})
      (f)
      (finally
        (reset! live nil)
        (mhttp/stop-server! handle)
        (storage/close-storage store)
        (delete-recursively! dir)))))

(use-fixtures :once once-fixture)

;; --- M7 packaging: bind_host drives the listener address ---------------------

(deftest bind-host-config-drives-listener
  ;; no explicit :host opt => start-server! takes the address from cfg; this is
  ;; what METAPASTE_BIND_HOST=0.0.0.0 exercises in the container image.
  (let [dir (temp-dir)
        store (storage/open! dir)]
    (try
      (let [cfg (assoc (config/load-config {:env {}})
                       :port 0
                       :bind_host "0.0.0.0")
            handle (mhttp/start-server! store cfg)]
        (try
          (is (= "0.0.0.0" (:host handle)))
          (finally (mhttp/stop-server! handle))))
      (finally
        (storage/close-storage store)
        (delete-recursively! dir)))))

;; --- request helpers ---------------------------------------------------------

(defn- call
  "http/request that never throws on status. Opts: :headers merged over the
   bearer auth header, :no-auth true sends no Authorization at all."
  [method path & [opts]]
  (let [opts (merge {:request-method method
                     :url (str (base) path)
                     :throw-exceptions false}
                    opts)
        opts (cond-> (dissoc opts :no-auth)
               (not (:no-auth opts)) (update :headers #(merge (auth-headers) %)))]
    (http/request opts)))

(defn- call-json [method path body & [opts]]
  (call method path
        (merge {:headers {"content-type" "application/json"}
                :body (json/write-str body :escape-slash false :escape-unicode false)}
               opts)))

(defn- problem? [resp status slug]
  (and (= status (:status resp))
       (= "application/problem+json"
          (some-> (get-in resp [:headers "content-type"]) str/lower-case))
       (str/includes? (str (:body resp)) (str "urn:metapaste:problem:" slug))))

(defn- multipart-body
  "Hand-rolled multipart/form-data (clj-http-lite has no multipart support).
   fields: ordered [name value] string pairs; file-field: [name filename bytes]."
  [boundary fields [file-name filename content]]
  (let [crlf "\r\n"
        out (java.io.ByteArrayOutputStream.)
        w (fn [^String s] (.write out (.getBytes s "UTF-8")))]
    (doseq [[n v] fields]
      (w (str "--" boundary crlf
              "Content-Disposition: form-data; name=\"" n "\"" crlf crlf
              v crlf)))
    (w (str "--" boundary crlf
            "Content-Disposition: form-data; name=\"" file-name
            "\"; filename=\"" filename "\"" crlf
            "Content-Type: application/octet-stream" crlf crlf))
    (.write out ^bytes content)
    (w (str crlf "--" boundary "--" crlf))
    (.toByteArray out)))

(defn- post-multipart [fields file-field & [opts]]
  (let [boundary "mp3testboundary"]
    (call :post "/api/v1/items"
          (merge {:headers {"content-type" (str "multipart/form-data; boundary="
                                                boundary)}
                  :body (multipart-body boundary fields file-field)}
                 opts))))

(defn- create-json [body]
  (let [r (call-json :post "/api/v1/items" body)]
    (is (= 201 (:status r)) (str "create failed: " (:status r) " " (:body r)))
    (json/read-str (:body r) :key-fn keyword)))

(defn- content-bytes [nm]
  (:body (call :get (str "/api/v1/items/" nm "/content") {:as :byte-array})))

(defn- plant-expired! [nm]
  ;; direct parameterized SQL — no job ever runs in this suite (Q38)
  (let [c (jdbc/connection (str "sqlite:" (.getAbsolutePath
                                            (File. ^File (data-dir) "metapaste.db"))))]
    (try
      (jdbc/execute! c
        ["UPDATE items SET expires_at = ? WHERE name = ?"
         "2020-01-01T00:00:00.000000000Z" nm])
      (finally (.close c)))))

;; --- happy paths -------------------------------------------------------------

(deftest create-json-text-item
  (let [item (create-json {:content "print('hello')" :mime_type "TEXT/plain; charset=utf-8"
                           :description "greeting" :ttl_seconds 3600})
        sha (storage/sha256-hex (.getBytes "print('hello')" "UTF-8"))]
    (is (= (count (:name item)) 12))
    (is (re-matches #"[0-9a-z]{12}" (:name item)))
    (is (= (str "/api/v1/items/" (:name item)) (:url item)))
    (is (= (str "/api/v1/items/" (:name item) "/content") (:content_url item)))
    (is (= "text/plain" (:mime_type item)) "Q6: lowercase essence, params stripped")
    (is (= "greeting" (:description item)))
    (is (= 14 (:size_bytes item)))
    (is (= 3600 (:ttl_seconds item)))
    (is (string? (:created_at item)))
    (is (string? (:expires_at item)))
    (is (= sha (:checksum_sha256 item)))
    (is (= "text" (:content_kind item)))))

(deftest create-multipart-file-defaults-to-octet-stream
  (let [r (post-multipart [] ["file" "note.txt" (.getBytes "plain file\n" "UTF-8")])]
    (is (= 201 (:status r)) (str (:status r) " " (:body r)))
    (let [item (json/read-str (:body r) :key-fn keyword)]
      (is (= "application/octet-stream" (:mime_type item)))
      (is (= "binary" (:content_kind item)))
      (is (= 11 (:size_bytes item))))))

(deftest create-multipart-4mib-binary-byte-identical-readback
  (let [content (byte-array (* 4 1024 1024) (byte 7))
        sha (storage/sha256-hex content)
        r (post-multipart [["mime_type" "application/octet-stream"]]
                          ["file" "blob.bin" content])]
    (is (= 201 (:status r)) (str (:status r)))
    (let [item (json/read-str (:body r) :key-fn keyword)
          back (content-bytes (:name item))]
      (is (= (* 4 1024 1024) (alength ^bytes back)))
      (is (= sha (storage/sha256-hex back)) "readback is byte-identical")
      (is (= sha (:checksum_sha256 item))))))

(deftest read-metadata-all-fields
  (let [item (create-json {:content "meta" :mime_type "application/json"
                           :description "d" :ttl_seconds 120})
        r (call :get (str "/api/v1/items/" (:name item)))]
    (is (= 200 (:status r)))
    (is (= "application/json" (get-in r [:headers "content-type"])))
    (let [m (json/read-str (:body r) :key-fn keyword)]
      (is (= #{:name :url :content_url :mime_type :description :size_bytes
               :created_at :ttl_seconds :expires_at :checksum_sha256
               :content_kind}
             (set (keys m))))
      (is (= (:name item) (:name m)))
      (is (= (:checksum_sha256 item) (:checksum_sha256 m)))
      (is (= "text" (:content_kind m)))
      (is (= "application/json" (:mime_type m)))
      (is (= 120 (:ttl_seconds m))))))

(deftest read-content-posture
  (testing "text-ish kind serves inline with CSP sandbox + nosniff + quoted ETag"
    (let [item (create-json {:content "hi text" :mime_type "text/plain"})
          r (call :get (str "/api/v1/items/" (:name item) "/content"))]
      (is (= 200 (:status r)))
      (is (= "text/plain" (get-in r [:headers "content-type"])))
      (is (= "7" (get-in r [:headers "content-length"])) "Content-Length present")
      (is (= (str "\"" (:checksum_sha256 item) "\"")
             (get-in r [:headers "etag"])))
      (is (= "nosniff" (get-in r [:headers "x-content-type-options"])))
      (is (= "sandbox" (get-in r [:headers "content-security-policy"])))
      (is (nil? (get-in r [:headers "content-disposition"])))
      (is (= "hi text" (:body r)))))
  (testing "text/html is an attachment, no CSP"
    (let [item (create-json {:content "<html>x</html>" :mime_type "text/html"})
          r (call :get (str "/api/v1/items/" (:name item) "/content"))]
      (is (= 200 (:status r)))
      (is (= "attachment" (get-in r [:headers "content-disposition"])))
      (is (nil? (get-in r [:headers "content-security-policy"])))))
  (testing "image/svg+xml is an attachment"
    (let [item (create-json {:content "<svg/>" :mime_type "image/svg+xml"})
          r (call :get (str "/api/v1/items/" (:name item) "/content"))]
      (is (= 200 (:status r)))
      (is (= "attachment" (get-in r [:headers "content-disposition"])))))
  (testing "binary kinds are attachments with nosniff"
    (let [item (create-json {:content "bin" :mime_type "application/zip"})
          r (call :get (str "/api/v1/items/" (:name item) "/content"))]
      (is (= 200 (:status r)))
      (is (= "application/zip" (get-in r [:headers "content-type"])))
      (is (= "attachment" (get-in r [:headers "content-disposition"])))
      (is (= "nosniff" (get-in r [:headers "x-content-type-options"]))))))

(deftest conditional-get-304-on-metadata-and-content
  (let [item (create-json {:content "etag me" :mime_type "text/plain"})
        etag (str "\"" (:checksum_sha256 item) "\"")]
    (testing "metadata route"
      (let [r (call :get (str "/api/v1/items/" (:name item))
                    {:headers {"if-none-match" etag}})]
        (is (= 304 (:status r)))
        (is (= etag (get-in r [:headers "etag"])))))
    (testing "content route"
      (let [r (call :get (str "/api/v1/items/" (:name item) "/content")
                    {:headers {"if-none-match" etag}})]
        (is (= 304 (:status r)))
        (is (= etag (get-in r [:headers "etag"])))))
    (testing "non-matching If-None-Match still returns 200"
      (let [r (call :get (str "/api/v1/items/" (:name item))
                    {:headers {"if-none-match" "\"deadbeef\""}})]
        (is (= 200 (:status r)))))))

(deftest patch-description-and-ttl
  (let [item (create-json {:content "immutable" :mime_type "text/plain"
                           :description "before" :ttl_seconds 3600})
        before (Instant/now)
        r (call-json :patch (str "/api/v1/items/" (:name item))
                     {:description "after" :ttl_seconds 7200})
        after (Instant/now)]
    (is (= 200 (:status r)) (str (:body r)))
    (let [m (json/read-str (:body r) :key-fn keyword)]
      (is (= "after" (:description m)))
      (is (= 7200 (:ttl_seconds m)))
      (is (= (:checksum_sha256 item) (:checksum_sha256 m)) "content immutable")
      (is (= (:mime_type item) (:mime_type m)))
      (is (= (:size_bytes item) (:size_bytes m)))
      (testing "expires_at recomputed FROM NOW, not from creation"
        (let [exp (Instant/parse (:expires_at m))
              delta (- (.getEpochSecond exp) (.getEpochSecond before))
              delta2 (- (.getEpochSecond exp) (.getEpochSecond after))]
          (is (<= 7190 delta 7210))
          (is (<= 7190 delta2 7210)))))
    (testing "shortening below 60s is 400, never re-clamped (plan M3/M4 acceptance beats Q35)"
      (is (problem? (call-json :patch (str "/api/v1/items/" (:name item))
                               {:ttl_seconds 59})
                    400 "validation-failed")))
    (testing "ttl above max is 400"
      (is (problem? (call-json :patch (str "/api/v1/items/" (:name item))
                               {:ttl_seconds 31536001})
                    400 "validation-failed")))
    (testing "ttl 0 with allow_never_expire=false is 400"
      (is (problem? (call-json :patch (str "/api/v1/items/" (:name item))
                               {:ttl_seconds 0})
                    400 "validation-failed")))
    (testing "any other field (content/mime_type) is 400 — immutable (Q8)"
      (is (problem? (call-json :patch (str "/api/v1/items/" (:name item))
                               {:content "changed"})
                    400 "validation-failed"))
      (is (problem? (call-json :patch (str "/api/v1/items/" (:name item))
                               {:mime_type "text/html"})
                    400 "validation-failed")))))

(deftest delete-then-404
  (let [item (create-json {:content "doomed" :mime_type "text/plain"})
        nm (:name item)]
    (is (= 204 (:status (call :delete (str "/api/v1/items/" nm)))))
    (is (= 404 (:status (call :get (str "/api/v1/items/" nm)))))
    (is (= 404 (:status (call :get (str "/api/v1/items/" nm "/content")))))
    (is (problem? (call :delete (str "/api/v1/items/" nm)) 404 "not-found"))))

;; --- list: pagination + filters ----------------------------------------------

(deftest list-pagination-walk-equals-full-listing
  (dotimes [i 120]
    (create-json {:content (str "item " i) :mime_type "text/plain"}))
  (let [one-shot (:body (call :get "/api/v1/items?limit=500"))
        all (json/read-str one-shot :key-fn keyword)
        walk (loop [cursor nil acc []]
               (let [path (str "/api/v1/items?limit=50"
                               (when cursor (str "&cursor=" cursor)))
                     page (json/read-str (:body (call :get path)) :key-fn keyword)
                     acc (into acc (:items page))]
                 (if-let [c (:next_cursor page)]
                   (recur c acc)
                   acc)))]
    (is (>= (count (:items all)) 120))
    (is (= (mapv :name (:items all)) (mapv :name walk))
        "page concatenation equals the one-shot listing")
    (is (nil? (:next_cursor (json/read-str
                              (:body (call :get "/api/v1/items?limit=500"))
                              :key-fn keyword)))
        "next_cursor is null on the last page")
    (testing "query validation is 400"
      (is (problem? (call :get "/api/v1/items?limit=501") 400 "validation-failed"))
      (is (problem? (call :get "/api/v1/items?limit=0") 400 "validation-failed"))
      (is (problem? (call :get "/api/v1/items?limit=abc") 400 "validation-failed"))
      (is (problem? (call :get "/api/v1/items?cursor=not-base64!!")
                    400 "validation-failed"))
      (is (problem? (call :get "/api/v1/items?created_after=yesterday")
                    400 "validation-failed"))
      (is (problem? (call :get "/api/v1/items?include_expired=maybe")
                    400 "validation-failed")))))

(deftest list-filters
  (let [text-item (create-json {:content "t" :mime_type "text/plain"})
        zip-item (create-json {:content "z" :mime_type "application/zip"})
        names-of (fn [path]
                   (set (map :name (:items (json/read-str
                                             (:body (call :get path))
                                             :key-fn keyword)))))]
    (testing "mime_prefix"
      (let [text-names (names-of "/api/v1/items?limit=500&mime_prefix=text%2F")
            zip-names (names-of "/api/v1/items?limit=500&mime_prefix=application%2Fzip")]
        (is (contains? text-names (:name text-item)))
        (is (not (contains? text-names (:name zip-item))))
        (is (contains? zip-names (:name zip-item)))))
    (testing "created_after/before are inclusive windows on now"
      (let [now (str (Instant/now))
            wide (names-of (str "/api/v1/items?limit=500&created_after="
                                (Instant/ofEpochSecond
                                  (- (.getEpochSecond (Instant/now)) 300))))
            future (names-of (str "/api/v1/items?limit=500&created_after="
                                  (Instant/ofEpochSecond
                                    (+ (.getEpochSecond (Instant/now)) 300))))
            past (names-of (str "/api/v1/items?limit=500&created_before="
                                (Instant/ofEpochSecond
                                  (- (.getEpochSecond (Instant/now)) 300))))]
        (is (string? now))
        (is (contains? wide (:name text-item)))
        (is (not (contains? future (:name text-item))))
        (is (not (contains? past (:name text-item))))))
    (testing "include_expired=true shows planted expired rows"
      (let [expired (create-json {:content "e" :mime_type "text/plain"})]
        (plant-expired! (:name expired))
        (let [default-names (names-of "/api/v1/items?limit=500")
              with-expired (names-of "/api/v1/items?limit=500&include_expired=true")]
          (is (not (contains? default-names (:name expired))))
          (is (contains? with-expired (:name expired))))))))

;; --- errors ------------------------------------------------------------------

(deftest errors-validation-400
  (testing "invalid mime syntax"
    (is (problem? (call-json :post "/api/v1/items"
                             {:content "x" :mime_type "not-a-mime"})
                  400 "validation-failed"))
    (is (problem? (call-json :post "/api/v1/items"
                             {:content "x" :mime_type "a/b/c"})
                  400 "validation-failed")))
  (testing "ttl below min / above max / zero without allow_never_expire"
    (is (problem? (call-json :post "/api/v1/items" {:content "x" :ttl_seconds 59})
                  400 "validation-failed"))
    (is (problem? (call-json :post "/api/v1/items" {:content "x" :ttl_seconds 31536001})
                  400 "validation-failed"))
    (is (problem? (call-json :post "/api/v1/items" {:content "x" :ttl_seconds 0})
                  400 "validation-failed")))
  (testing "multipart text file with invalid UTF-8 bytes"
    (let [r (post-multipart [["mime_type" "text/plain"]]
                            ["file" "bad.txt" (byte-array (map unchecked-byte
                                                               [0xff 0xfe 0x41]))])]
      (is (problem? r 400 "validation-failed"))))
  (testing "description counted in code points: 1001 rejected, multi-byte included"
    (is (problem? (call-json :post "/api/v1/items"
                             {:content "x"
                              :description (apply str (repeat 1001 "😀"))})
                  400 "validation-failed"))
    (is (problem? (call-json :post "/api/v1/items"
                             {:content "x"
                              :description (apply str (repeat 1001 "a"))})
                  400 "validation-failed"))
    (is (= 201 (:status (call-json :post "/api/v1/items"
                                   {:content "x"
                                    :description (apply str (repeat 1000 "😀"))})))
        "1000 code points (4000 bytes) accepted — code-point counting, not bytes"))
  (testing "bad and absent JSON bodies"
    (let [r (call :post "/api/v1/items"
                  {:headers {"content-type" "application/json"}
                   :body "{\"content\":"})]
      (is (problem? r 400 "validation-failed")))
    (let [r (call :post "/api/v1/items"
                  {:headers {"content-type" "application/json"}
                   :body ""})]
      (is (problem? r 400 "validation-failed"))))
  (testing "missing content field"
    (is (problem? (call-json :post "/api/v1/items" {:mime_type "text/plain"})
                  400 "validation-failed"))))

(deftest errors-not-found-404-unknown-and-expired
  (is (problem? (call :get "/api/v1/items/zzzzzzzzzzzz") 404 "not-found"))
  (is (problem? (call :get "/api/v1/items/zzzzzzzzzzzz/content") 404 "not-found"))
  (testing "expired-but-uncollected reads 404 before any job runs (Q38)"
    (let [item (create-json {:content "will expire" :mime_type "text/plain"})]
      (plant-expired! (:name item))
      (is (problem? (call :get (str "/api/v1/items/" (:name item)))
                    404 "not-found"))
      (is (problem? (call :get (str "/api/v1/items/" (:name item) "/content"))
                    404 "not-found"))
      (is (problem? (call :delete (str "/api/v1/items/" (:name item)))
                    404 "not-found")))))

(deftest error-411-missing-content-length-raw-socket
  (let [port (Integer/parseInt (second (re-find #":(\d+)$" (base))))
        s (Socket. "127.0.0.1" (int port))
        out (.getOutputStream s)
        in (BufferedReader. (InputStreamReader. (.getInputStream s)))]
    (try
      ;; HTTP/1.1 with neither Content-Length nor Transfer-Encoding
      ;; (PrintWriter hits a jolt shim cast bug — write raw bytes instead)
      (.write out (.getBytes (str "POST /api/v1/items HTTP/1.1\r\n"
                                  "Host: 127.0.0.1\r\n"
                                  "Authorization: Bearer " @bearer "\r\n"
                                  "Content-Type: application/json\r\n"
                                  "Connection: close\r\n\r\n")
                             "UTF-8"))
      (.flush out)
      (let [status (.readLine in)
            headers (loop [acc {}]
                      (let [line (.readLine in)]
                        (if (or (nil? line) (= line ""))
                          acc
                          (let [i (str/index-of line ":")]
                            (recur (assoc acc
                                          (str/lower-case (subs line 0 i))
                                          (str/trim (subs line (inc i)))))))))
            body (slurp in)]
        (is (re-find #" 411 " status))
        (is (= "application/problem+json" (get headers "content-type")))
        (is (some? (get headers "x-request-id")))
        (is (str/includes? body "urn:metapaste:problem:length-required")))
      (finally (.close s)))))

(deftest errors-413-oversize
  (testing "Content-Length over the request-body cap (2x cap + 64 KiB)"
    (let [cap (+ (* 2 10485760) 65536)
          r (call :post "/api/v1/items"
                  {:headers {"content-type" "application/json"}
                   :body (byte-array (+ cap 1024))})]
      (is (problem? r 413 "payload-too-large"))))
  (testing "multipart file part over max_item_bytes -> 413 mid-parse"
    (let [r (post-multipart []
                            ["file" "big.bin" (byte-array (* 11 1024 1024)
                                                          (byte 3))])]
      (is (problem? r 413 "payload-too-large")))))

(deftest error-415-wrong-content-type-on-create
  (is (problem? (call :post "/api/v1/items"
                      {:headers {"content-type" "text/plain"}
                       :body "hello"})
                415 "unsupported-media-type")))

(deftest errors-401-and-405
  (testing "401 missing or wrong bearer (M5 API-key auth)"
    (is (problem? (call :get "/api/v1/items" {:no-auth true})
                  401 "unauthorized"))
    (is (problem? (call :get "/api/v1/items"
                        {:headers {"authorization" "Bearer wrong-key"}})
                  401 "unauthorized")))
  (testing "405 wrong method on a known path, with Allow"
    (let [item (create-json {:content "m" :mime_type "text/plain"})
          r (call :put (str "/api/v1/items/" (:name item)))]
      (is (problem? r 405 "method-not-allowed"))
      (is (= "GET, PATCH, DELETE" (get-in r [:headers "allow"]))))
    (let [r (call :put "/api/v1/items")]
      (is (problem? r 405 "method-not-allowed"))
      (is (= "GET, POST" (get-in r [:headers "allow"])))))
  (testing "healthz is public (Q51); unknown paths still 404"
    (is (= 200 (:status (call :get "/healthz" {:no-auth true}))))
    (is (= 404 (:status (call :get "/api/v1/nope"))))))

(deftest request-id-echo-and-generation
  (testing "inbound X-Request-Id is echoed"
    (let [r (call :get "/api/v1/items" {:headers {"x-request-id" "trace-abc-1"}})]
      (is (= 200 (:status r)))
      (is (= "trace-abc-1" (get-in r [:headers "x-request-id"]))))
    (let [r (call :get "/api/v1/items/zzzzzzzzzzzz")]
      (is (= 404 (:status r)))
      (is (some? (get-in r [:headers "x-request-id"]))))
    (let [r (call :get "/api/v1/items" {:headers {"x-request-id" "trace-err-9"}
                                         :no-auth true})]
      (is (= 401 (:status r)))
      (is (= "trace-err-9" (get-in r [:headers "x-request-id"])))))
  (testing "absent inbound id -> server generates one, on success and error"
    (let [ok (call :get "/api/v1/items")
          err (call :get "/api/v1/items/zzzzzzzzzzzz")]
      (is (re-matches #"[0-9a-f-]{36}" (get-in ok [:headers "x-request-id"])))
      (is (re-matches #"[0-9a-f-]{36}" (get-in err [:headers "x-request-id"])))
      (is (not= (get-in ok [:headers "x-request-id"])
                (get-in err [:headers "x-request-id"]))))))
