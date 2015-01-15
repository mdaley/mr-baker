(ns baker.web
  (:require [baker
             [scheduler :as scheduler]
             [entertainment-ami :as base]
             [bake-service-ami :as service-ami]
             [public-ami :as public-ami]
             [packer :as packer]
             [awsclient :as awsclient]
             [pokemon :as pokemon]
             [onix :as onix]
             [yum :as yum]
             [amis :as amis]]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes context GET PUT POST DELETE]]
            [compojure.route :as route]
            [compojure.handler :as handler]
            [ring.middleware.json :refer [wrap-json-body]]
            [ring.middleware.format-response :refer [wrap-json-response]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [clojure.string :refer [split replace-first]]
            [clojure.tools.logging :refer [info warn error]]
            [environ.core :refer [env]]
            [radix
             [error :refer [wrap-error-handling error-response]]
             [ignore-trailing-slash :refer [wrap-ignore-trailing-slash]]
             [setup :as setup]
             [reload :refer [wrap-reload]]]
            [metrics.ring.expose :refer [expose-metrics-as-json]]
            [metrics.ring.instrument :refer [instrument]]
            [overtone.at-at :refer [show-schedule]]))

(def version
  (setup/version "onix"))

(defn response
  "Accepts a body an optionally a content type and status. Returns a response object."
  [body & [content-type status]]
  {:status (or status 200)
   :headers {"Content-Type" (or content-type "application/json")}
   :body body})

;; TODO - check onix status in deps
;; TODO - check eu-west-1 status in deps
(defn status
  "Returns the service status"
  []
  (let [baking-scheduled (scheduler/job-is-scheduled? "baker")
        ami-killing-scheduled (scheduler/job-is-scheduled? "killer")
        success (and baking-scheduled ami-killing-scheduled)]
    (response
     {:name "baker"
      :version version
      :success success
      :dependencies [{:name "baking-schedule" :success baking-scheduled}
                     {:name "ami-killing-schedule" :success ami-killing-scheduled}]}
     "application/json"
     (if success 200 500))))

(defn latest-amis
  "Returns the latest amis that we know about"
  []
  {:status 200 :body {:parent-hvm (amis/parent-ami :hvm)
                      :parent-para (amis/parent-ami :para)
                      :ent-base-hvm (amis/entertainment-base-ami-id :hvm)
                      :ent-base-para (amis/entertainment-base-ami-id :para)
                      :ent-public-hvm (amis/entertainment-public-ami-id :hvm)
                      :ent-public-para (amis/entertainment-public-ami-id :para)}})

(defn latest-service-amis
  "Returns the list of amis for the supplied service name"
  [service-name]
  (->> (awsclient/service-amis service-name)
       (map #(select-keys % [:name :image-id]))
       (reverse)
       (take 10)))

(defn bake-entertainment-base-ami
  "Create a pair of new local base amis from the latest parent ami.
   Takes a param of virt-type, either hvm or para.
   If dry-run then only return the packer template, don't run it."
  [virt-type dry-run]
  {:pre [(#{:hvm :para} virt-type)]}
  (let [template (base/create-base-ami virt-type)]
    (if-not dry-run
      (-> template
          (packer/build)
          (response))
      (response (json/generate-string template)))))

(defn bake-entertainment-public-ami
  "Create a new public entertainment ami from the latest ent base ami.
   If dry-run then only return the packer template, don't run it."
  [virt-type dry-run]
  {:pre [(#{:hvm :para} virt-type)]}
  (let [template (public-ami/create-public-ami virt-type)]
    (if-not dry-run
      (-> template
          (packer/build)
          (response))
      (response (json/generate-string template)))))

(defn bake-chroot-service-ami
  "Bake a new ami for the service name and version based on the latest base ent ami.
   If dry-run then only return the packer template, don't run it."
  [name version dry-run virt-type embargo]
  {:pre [#{:para :hvm} virt-type]}
  (if (not (onix/service-exists? name))
    (error-response (str "The service '" name "' doesn't exist.") 404)
    (let [rpm-name (onix/rpm-name name)]
      (if-let [version (yum/get-latest-iteration name version rpm-name)]
        (let [template (service-ami/create-chroot-service-ami name version rpm-name virt-type embargo)]
          (if dry-run
            (response (json/generate-string template))
            (response (packer/build template name))))
        (error-response (format "Are you baking too soon? No RPM for '%s' '%s'." name version) 404)))))

(def lock (atom false))

(defn lockable-bake
  "Bake the ami if the service isn't locked"
  [bake]
  (if-not @lock
    (bake)
    {:status 503
     :headers {"Content-Type" "text/plain"}
     :body (str "Service is temporarily locked with message: " @lock)}))

(defn remove-ami
  [service ami]
  "Reregister the supplied ami"
  (if (awsclient/deregister-ami service ami)
      (response (format "%s deleted successfully" ami) "application/json" 204)
      (response (format "Failed to remove %s" ami) "application/json" 500)))

(defroutes routes

  (GET "/healthcheck" []
       (status))

   (GET "/ping" []
        "pong")

   (GET "/status" []
        (status))

   (GET "/amis" []
        (latest-amis))

   (POST "/lock" req
         (let [message (get-in req [:body "message"])]
           (reset! lock (or message "Baker is locked, no reason was supplied."))
           (str "Baker is locked and won't accept new builds: " @lock)))

   (DELETE "/lock" []
           (reset! lock false)
           {:status 204})

   (GET "/inprogress" []
        (response (with-out-str (show-schedule packer/timeout-pool)) "text/plain"))

   (POST "/clean/:service" [service]
         (if (= service "all")
           (scheduler/kill-amis)
           (scheduler/kill-amis-for-application service)))

   (GET "/amis/active/:service/:environment/:region" [service environment region]
        (response (awsclient/active-amis-for-service service (keyword environment) region)))

   (GET "/amis/:service" [service]
        (latest-service-amis service))

   (POST "/bake/entertainment-ami/:virt-type" [virt-type dryrun]
         (lockable-bake #(bake-entertainment-base-ami (keyword virt-type) dryrun)))

   (POST "/bake/public-ami/:virt-type" [virt-type dryrun]
         (lockable-bake #(bake-entertainment-public-ami (keyword virt-type) dryrun)))

   (POST "/bake/base-amis" []
         (lockable-bake #(scheduler/bake-amis))
         "OK")

   (POST "/bake/:service-name/:service-version" [service-name service-version dryrun virt-type embargo]
         (lockable-bake
          #(bake-chroot-service-ami service-name service-version dryrun (or (keyword virt-type) :para) embargo)))

   (POST "/make-public/:service" [service]
         (awsclient/allow-prod-access-to-service service))

   (DELETE "/:service-name/amis/:ami" [service-name ami]
           (remove-ami service-name ami))

  (route/not-found (error-response "Resource not found" 404)))

(defn remove-legacy-path
  "Temporarily redirect anything with /1.x in the path to somewhere without /1.x"
  [handler]
  (fn [request]
    (handler (update-in request [:uri] (fn [uri] (replace-first uri "/1.x" ""))))))

(def app
  (-> routes
      (wrap-reload)
      (remove-legacy-path)
      (instrument)
      (wrap-error-handling)
      (wrap-ignore-trailing-slash)
      (wrap-keyword-params)
      (wrap-params)
      (wrap-json-response)
      (wrap-json-body)
      (expose-metrics-as-json)))
