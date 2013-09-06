(ns ditto.web
  (:require [ditto
             [entertainment-ami :as base]
             [packer :as packer]
             [pokemon :as pokemon]]
            [compojure.core :refer [defroutes context GET PUT POST DELETE]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.middleware.format-response :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [clojure.data.xml :refer [element emit-str]]
            [clojure.string :refer [split]]
            [clojure.tools.logging :refer [info warn error]]
            [environ.core :refer [env]]
            [nokia.ring-utils.error :refer [wrap-error-handling error-response]]
            [nokia.ring-utils.metrics :refer [wrap-per-resource-metrics replace-outside-app
                                              replace-guid replace-mongoid replace-number]]
            [nokia.ring-utils.ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
            [metrics.ring.expose :refer [expose-metrics-as-json]]
            [metrics.ring.instrument :refer [instrument]]))

(def ^:dynamic *version* "none")
(defn set-version! [version]
  (alter-var-root #'*version* (fn [_] version)))

(defn response [data content-type & [status]]
  {:status (or status 200)
   :headers {"Content-Type" content-type}
   :body data})

;; TODO - make this a json status
(defn status
  []
  {:headers {"Content-Type" "application/xml"}
   :body    (emit-str (element :status
                               {:serviceName "ditto"
                                :version *version*
                                :success true}))})

(comment (spit "/tmp/xxx" (base/create-base-ami "ami-098b917d")))

;; TODO - can we schedule a task to create the base ami rather than having to push it?
(defroutes routes
  (context
   "/1.x" []

   (GET "/ping"
        [] "pong")

   (GET "/status"
        [] (status))

   ;; TODO - param to just return the template not build it
   (POST "/entertainment-ami/:parent-ami" [parent-ami]
         (-> (base/create-base-ami "ami-098b917d")
             (packer/build)))

   (GET "/pokemon" [] {:body pokemon/ditto
                       :status 200
                       :headers {"Content-Type" "text/plain; charset=utf-8"}}))

  (route/not-found (error-response "Resource not found" 404)))


(def app
  (-> routes
      (instrument)
      (wrap-error-handling)
      (wrap-ignore-trailing-slash)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-json-response)
      (wrap-per-resource-metrics [replace-guid replace-mongoid replace-number (replace-outside-app "/1.x")])
      (expose-metrics-as-json)))
