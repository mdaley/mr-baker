(ns ditto.yum
  "Functions pertaining to our intergration with yum repo"
  (:require [ditto
             [bake-service-ami :as bake-service]]
            [environ.core :refer [env]]
            [clj-http.client :as client]))

(defn rpm-url
  "Returns the rpm url for a given service-name, version and iteration."
  [name version]
  (str (env :service-yum-url) "/" (bake-service/rpm-name name version)))

(defn rpm-exists?
  "Returns true if the ami exists in the brislabs yumrepo; otherwise returns false."
  [url]
  (= 200 (:status (client/head url {:throw-exceptions false}))))

(defn rpm-version
  "Returns the combined rpm version and iteration"
  [version iteration]
  (str version "-" iteration))

(defn get-latest-iteration
  "Gets the latest iteration of the rpm version or nil if the rpm does not exist."
  [name version]
  (let [iversion (map (partial rpm-version version) (range 1 100))]
    (last (take-while (partial (comp rpm-exists? rpm-url) name) iversion))))