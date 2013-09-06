(ns ditto.entertainment-ami
  (:require [cheshire.core :as json]
            [environ.core :refer [env]]))

(defn shell [& cmds]
  {:type "shell"
   :execute_command "chmod +x {{ .Path }}; {{ .Vars }} sudo -E sh '{{ .Path }}'"
   :inline_shebang "/bin/sh -x"
   :inline cmds})

(defn ebs-builder
  "Generate a new ami builder"
  [parent-ami]
  {:type "amazon-ebs"
   :access_key (env :service-aws-access-key)
   :secret_key (env :service-aws-secret-key)
   :region "eu-west-1"
   :source_ami parent-ami
   :instance_type "t1.micro"
   :ssh_username "nokia"
   :ami_name "ditto-ami-testing {{timestamp}}"
   :ssh_timeout "5m"
   :security_group_id "sg-c453b4ab"
   :vpc_id "vpc-7bc88713"
   :subnet_id "subnet-bdc08fd5"
   :iam_instance_profile "baking"
   :ssh_keypair_pattern "nokia-%s"})

(def upload-repo-file
  {:type "file"
   :source "ami-scripts/nokia-internal.repo"
   :destination "/tmp/nokia-internal.repo"})

(def append-repo-file
  (shell "cat /tmp/nokia-internal.repo >> /etc/yum.repos.d/nokia-internal.repo"))

(def enable-nokia-repo
  (shell "yum-config-manager --enable nokia-epel >> /var/log/baking.log 2>&1"))

;; manually start puppet
(def puppet
  (shell "yum install -y puppet >> /var/log/baking.log 2>&1"
         "echo PUPPET_SERVER=puppetaws.brislabs.com >> /etc/sysconfig/puppet"))

;; TODO - need to rm -rf /var/lib/puppet/ssl
  ;; at the end, on shut down?
;; TODO - run puppet as a one off blocking (puppet agent -server -t?)

;; TODO - add the time
(defn motd [parent-ami]
  (shell "echo -e \"\\nEntertainment Base AMI\\n\" >> /etc/motd"
         "echo -e \"\\nBake date : TODO\\n\" >> /etc/motd"
         (format "echo -e \"\\nSource AMI: %s\\n\" >> /etc/motd" parent-ami)))

(defn ami-template
  "Generate a new ami template"
  [parent-ami]
  {:builders [(ebs-builder parent-ami)]
   :provisioners [upload-repo-file
                  append-repo-file
                  enable-nokia-repo
                  puppet
                  (motd parent-ami)]})

(defn create-base-ami
  "Creates a new entertainment base-ami from the parent ami id"
  [parent-ami]
  (json/generate-string (ami-template parent-ami)))
