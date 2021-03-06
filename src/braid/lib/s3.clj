(ns braid.lib.s3
  (:require
    [clojure.data.json :as json]
    [clojure.string :as string]
    [org.httpkit.client :as http]
    [braid.base.conf :refer [config]]
    [braid.lib.aws :as aws])
  (:import
    (java.time.temporal ChronoUnit)
    (org.apache.commons.codec.binary Base64 Hex)))

(defn s3-host
  [config]
  (let [{:keys [bucket region]} config]
    (str bucket ".s3." region ".amazonaws.com")))

(defn generate-s3-upload-policy
  [config {:keys [starts-with]}]
  (let [{:keys [api-secret api-key region bucket]} config]
    (when api-secret
      (let [utc-now (aws/utc-now)
            day (.format utc-now aws/basic-date-format)
            date (.format utc-now aws/basic-date-time-format)
            credential (->> [api-key day region "s3" "aws4_request"]
                            (string/join "/"))
            policy (-> {:expiration (-> (.plus utc-now 5 ChronoUnit/MINUTES)
                                        (.format aws/date-time-format))
                        :conditions
                        [{:bucket bucket}
                         ["starts-with" "$key" starts-with]
                         {:acl "private"}
                         ["starts-with" "$Content-Type" ""]
                         ["content-length-range" 0 (* 500 1024 1024)]

                         {"x-amz-algorithm" "AWS4-HMAC-SHA256"}
                         {"x-amz-credential" credential}
                         {"x-amz-date" date}]}
                       json/write-str
                       aws/str->bytes
                       Base64/encodeBase64String)]
        {:bucket bucket
         :region region
         :auth {:policy policy
                :key api-key
                :signature (->>
                            (aws/str->bytes policy)
                            (aws/hmac-sha256
                             (aws/signing-key
                              {:aws-api-secret api-secret
                               :aws-region region
                               :day day
                               :service "s3"}))
                            Hex/encodeHexString)
                :credential credential
                :date date}}))))

(defn get-signature
  [config utc-now path query-str]
  (let [{:keys [region api-secret bucket]} config]
    (->> ["AWS4-HMAC-SHA256"
          (.format utc-now aws/basic-date-time-format)
          (string/join "/" [(.format utc-now aws/basic-date-format) region "s3"
                            "aws4_request"])
          (aws/canonical-request
           {:method "GET"
            :path (str "/" path)
            :query-string query-str
            :headers {"host" (str bucket ".s3." region ".amazonaws.com")}
            :body nil})]
         (string/join "\n")
         aws/str->bytes
         (aws/hmac-sha256
          (aws/signing-key {:aws-api-secret api-secret
                            :aws-region region
                            :day (.format utc-now aws/basic-date-format)
                            :service "s3"}))
         aws/bytes->hex)))

(defn readable-s3-url
  [config expires-seconds path]
  (let [{:keys [api-key region]} config
        utc-now (aws/utc-now)
        query-str (str "X-Amz-Algorithm=AWS4-HMAC-SHA256"
                       "&X-Amz-Credential=" api-key "/" (.format utc-now aws/basic-date-format) "/" region "/s3/aws4_request"
                       "&X-Amz-Date=" (.format utc-now aws/basic-date-time-format)
                       (str "&X-Amz-Expires=" expires-seconds)
                       "&X-Amz-SignedHeaders=host")]
    (str "https://" (s3-host config)
         "/" path
         "?" query-str
         "&X-Amz-Signature=" (get-signature config utc-now path query-str))))

(defn- make-request
  [config {:keys [body method path] :as request}]
  (let [utc-now (aws/utc-now)
        {:keys [region api-secret api-key bucket]} config
        req (update request :headers
                    assoc
                    "x-amz-date" (.format utc-now aws/basic-date-time-format)
                    "x-amz-content-sha256" (aws/hex-hash body)
                    "Host" (str (s3-host config) ":443"))]
    (-> req
        (dissoc :method :path :query-string)
        (assoc-in [:headers "Authorization"]
                  (aws/auth-header {:now utc-now
                                    :service "s3"
                                    :request req
                                    :aws-api-secret api-secret
                                    :aws-api-key api-key
                                    :aws-region region})))))

(defn delete-file!
  [config path]
  (let [{:keys [api-secret api-key region bucket]} config]
    ;; always returns 204, even if file does not exist
    @(http/delete (str "https://" (s3-host config) path)
                  (make-request config {:method "DELETE"
                                        :path path
                                        :body ""}))))

(defn upload-url-path
  [url]
  (when url
    (or ;; old style, with bucket after domain
      (second (re-matches #"^https://s3\.amazonaws\.com/[^/]+(/.*)$" url))
      ;; new style, with bucket in domain
      (second (re-matches #"^https://.+\.amazonaws\.com(/.*)$" url)))))
