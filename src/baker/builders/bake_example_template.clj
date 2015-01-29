(ns baker.builders.bake-example-template
  (:require [baker
             [amis :as amis]
             [bake-common :refer :all]
             [common :as common]
             [onix :as onix]
             [packer :as packer]]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-time
             [core :as time-core]
             [format :as time-format]]
            [clojure.string :as str]
            [radix.error :refer [error-response]]))

(defn service-ami-name
  "Returns the ami name for the service with date/time now"
  [service-name service-version virt-type]
  (str "ent-" service-name "-"
       service-version "-"
       (name virt-type) "-"
       (time-format/unparse
        (time-format/formatter "YYYY-MM-dd_HH-mm-ss")
        (time-core/now))))

;; TODO - need to make an example service to bake here
;; ideally lets still accept version and name
(defn install-rpm
  "Install the service rpm on to the machine"
  [name version]
  (let [rpm-full-name "some download path TODO"]
    (cshell (str "wget -nv http://yumrepo.brislabs.com/ovimusic/" rpm-full-name)
            (str "yum -y install " rpm-full-name)
            (str "rm -fv " rpm-full-name))))

(defn provisioners
  "Returns a list of provisioners for the bake."
  [name version]
  [(install-rpm name version)])

;; TODO - rename
(defn packer-template
  "Generates a new ami template for chroot bake of the service"
  [name version source-ami virt-type embargo]
  (let [builder (maybe-with-keys
                 {:ami_name (service-ami-name name version virt-type)
                  :tags {:name (format "%s AMI" name)
                         :service name}
                  :source_ami source-ami
                  :ami_virtualization_type (virtualisation-type-long virt-type)
                  :type "amazon-chroot"})]
    {:builders [builder]
     :provisioners (provisioners name version)}))

(defn bake-chroot-service-ami
  "Bake a new ami for the service name and version based on the latest base ent ami.
   If dry-run then only return the packer template, don't run it."
  [name version dry-run virt-type]
  {:pre [#{:para :hvm} virt-type]}
  (if (not (onix/service-exists? name))
    (error-response (str "The service '" name "' doesn't exist.") 404)
    (let [template (packer-template name version
                                    (amis/parent-ami virt-type)
                                    virt-type)]
      (if dry-run
        (common/response (json/generate-string template))
        (common/response (packer/build template name))))))