;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.wabbit.auth.realm

  (:gen-class
   :extends org.apache.shiro.realm.AuthorizingRealm
   :name czlab.wabbit.auth.realm.JdbcRealm
   :init myInit
   :constructors {[] []}
   :exposes-methods { }
   :state myState)

  (:require [czlab.twisty.codec :refer [pwd<>]]
            [czlab.basal.logging :as log])

  (:use [czlab.wabbit.auth.core]
        [czlab.horde.connect]
        [czlab.horde.core])

  (:import [org.apache.shiro.realm CachingRealm AuthorizingRealm]
           [org.apache.shiro.subject PrincipalCollection]
           [org.apache.shiro.authz
            AuthorizationInfo
            AuthorizationException]
           [org.apache.shiro.authc
            SimpleAccount
            AuthenticationToken
            AuthenticationException]
           [java.util Collection]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -myInit [] [ [] (atom nil) ])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -doGetAuthenticationInfo
  ""
  [^AuthorizingRealm this ^AuthenticationToken token]
  (let [db (dbapi<> *jdbc-pool* *meta-cache*)
        ;;pwd (.getCredentials token)
        user (.getPrincipal token)
        sql (simple-sqlr db)]
    (when-some [acc (findLoginAccount sql user)]
      (SimpleAccount. acc
                      (:passwd acc)
                      (.getName this)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -doGetAuthorizationInfo
  ""
  [^AuthorizingRealm this ^PrincipalCollection principals]
  (let [db (dbapi<> *jdbc-pool* *meta-cache*)
        acc (.getPrimaryPrincipal principals)
        rc (SimpleAccount. acc
                           (:passwd acc)
                           (.getName this))
        sql (simple-sqlr db)
        j :czlab.wabbit.auth.model/AccountRoles]
    (let [rs (dbGetM2M {:joined j :with sql} acc) ]
      (doseq [r rs]
        (.addRole rc ^String (:name r)))
      rc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn -init [] nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

