;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc ""
      :author "Kenneth Leung"}

  czlab.wabbit.plugs.auth.core

  ;;(:gen-class)

  (:require [czlab.basal.format :refer [readEdn readJsonStr writeJsonStr]]
            [czlab.twisty.codec :refer [caesarDecrypt passwd<>]]
            [czlab.convoy.net.util :refer [generateCsrf filterFormFields]]
            [czlab.wabbit.plugs.io.http :refer [scanBasicAuth]]
            [czlab.horde.dbio.connect :refer [dbapi<>]]
            [czlab.basal.resources :refer [rstr]]
            [czlab.convoy.net.wess :as wss]
            [clojure.string :as cs]
            [clojure.java.io :as io]
            [czlab.basal.logging :as log])

  (:use [czlab.wabbit.plugs.auth.model]
        [czlab.wabbit.plugs.auth.core]
        [czlab.wabbit.base.core]
        [czlab.convoy.net.core]
        [czlab.basal.core]
        [czlab.basal.io]
        [czlab.basal.meta]
        [czlab.basal.str]
        [czlab.horde.dbio.core])

  (:import [org.apache.shiro.authc.credential CredentialsMatcher]
           [org.apache.shiro.config IniSecurityManagerFactory]
           [org.apache.shiro.authc UsernamePasswordToken]
           [czlab.jasal XData Muble I18N BadDataError]
           [org.apache.shiro.realm AuthorizingRealm]
           [czlab.wabbit.ctl Pluggable PlugError]
           [org.apache.shiro.subject Subject]
           [java.util Base64 Base64$Decoder]
           [org.apache.shiro SecurityUtils]
           [czlab.wabbit.sys Execvisor]
           [czlab.wabbit.base ConfigError]
           [clojure.lang APersistentMap]
           [java.io File IOException]
           [czlab.wabbit.plugs.io HttpMsg]
           [czlab.flux.wflow Activity Job]
           [org.apache.shiro.authz
            AuthorizationException
            AuthorizationInfo]
           [org.apache.shiro.authc
            SimpleAccount
            AuthenticationException
            AuthenticationToken
            AuthenticationInfo]
           [czlab.twisty IPassword]
           [java.net HttpCookie]
           [java.util Properties]
           [czlab.wabbit.plugs.auth
            AuthPluglet
            UnknownUser
            DuplicateUser]
           [czlab.convoy.net
            HttpSession
            AuthError
            HttpResult
            ULFileItem
            ULFormItems]
           [czlab.horde
            DbApi
            Schema
            SQLr
            JdbcPool
            JdbcSpec]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(def ^:private ^String nonce-param "nonce_token")
(def ^:private ^String csrf-param "csrf_token")
(def ^:private ^String pwd-param "credential")
(def ^:private ^String email-param "email")
(def ^:private ^String user-param "principal")
(def ^:private ^String captcha-param "captcha")

;; hard code the shift position, the encrypt code
;; should match this value.
(def ^:private caesar-shift 13)

(def ^:private props-map
  {email-param [ :email #(normalizeEmail %) ]
   captcha-param [ :captcha #(strim %) ]
   user-param [ :principal #(strim %) ]
   pwd-param [ :credential #(strim %) ]
   csrf-param [ :csrf #(strim %) ]
   nonce-param [ :nonce #(some? %) ]})

(def ^:dynamic *meta-cache* nil)
(def ^:dynamic *jdbc-pool* nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn newSession<>
  "" ^HttpSession [^HttpMsg evt attrs]

  (let
    [s (wss/wsession<> (.. evt source server pkeyBytes)
                       (:session (.. evt source config)))]
    (doseq [[k v] attrs] (.setAttr s k v))
    s))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn csrfToken<> "Create or delete a csrf cookie"

  ;; if maxAge=-1, browser doesnt sent it back!
  [{:keys [domainPath domain]} token]

  (let [ok? (hgl? token)
        c (HttpCookie. wss/csrf-cookie
                       (if ok? token "*"))]
    (if (hgl? domainPath) (.setPath c domainPath))
    (if (hgl? domain) (.setDomain c domain))
    (.setHttpOnly c true)
    (. c setMaxAge (if ok? 3600 0))
    c))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- crackFormFields
  "Parse a standard login-like form with userid,password,email"
  [^HttpMsg evt]
  (if-some
    [itms (cast? ULFormItems
                 (some-> evt
                         .body .content))]
    (preduce<map>
      #(let [fm (.getFieldNameLC ^ULFileItem %2)
             fv (. ^ULFileItem %2 getString)]
         (log/debug "form-field=%s, value=%s" fm fv)
         (if-some [[k v] (get props-map fm)]
           (assoc! %1 k (v fv))
           %1))
      (filterFormFields itms))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- crackBodyContent
  "Parse a JSON body"
  [^HttpMsg evt]
  (let
    [xs (some-> evt .body .getBytes)
     json (-> (if xs
                (strit xs) "{}")
              (readJsonStr #(lcase %)))]
    (preduce<map>
      #(let [[k [a1 a2]] %2]
         (if-some [fv (get json k)]
           (assoc! %1 a1 (a2 fv))
           %1))
      props-map)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- crackParams
  "Parse form fields in the Url"
  [^HttpMsg evt]
  (let [gist (.gist evt)]
    (preduce<map>
      #(let [[k [a1 a2]] props-map]
         (if (gistParam? gist k)
             (assoc! %1
                     a1
                     (a2 (gistParam gist k)))
             %1))
      props-map)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn maybeGetAuthInfo
  "Attempt to parse and get authentication info"
  ^APersistentMap
  [^HttpMsg evt]
  (let [gist (.gist evt)]
    (if-some+
      [ct (gistHeader gist "content-type")]
      (cond
        (or (embeds? ct "form-urlencoded")
            (embeds? ct "form-data"))
        (crackFormFields evt)

        (embeds? ct "/json")
        (crackBodyContent evt)

        :else
        (crackParams evt)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- maybeDecodeField
  ""
  [info fld shiftCount]
  (if (:nonce info)
    (try!
      (let
        [decr (->> (get info fld)
                   (caesarDecrypt shiftCount))
         s (->> decr
                (.decode (Base64/getMimeDecoder))
                strit)]
        (log/debug "info = %s" info)
        (log/debug "decr = %s" decr)
        (log/debug "val = %s" s)
        (assoc info fld s)))
    info))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- getPodKey
  ""
  ^bytes
  [^HttpMsg evt]
  (.. evt source server pkeyBytes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private getXXXInfo
  ""
  [evt]
  `(-> (maybeGetAuthInfo ~evt)
       (maybeDecodeField :principal caesar-shift)
       (maybeDecodeField :credential caesar-shift)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getSignupInfo "" [^HttpMsg evt] (getXXXInfo evt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getLoginInfo "" [^HttpMsg evt] (getXXXInfo evt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- assertPluginOK

  "If the plugin has been initialized,
   by looking into the db"
  [^JdbcPool pool]
  {:pre [(some? pool)]}

  (let [tbl (->> :czlab.wabbit.plugs.auth.model/LoginAccount
                 (.get ^Schema *auth-meta-cache*)
                 dbtable)]
    (when-not (tableExist? pool tbl)
      (applyDDL pool)
      (if-not (tableExist? pool tbl)
        (dberr! (rstr (I18N/base)
                      "auth.no.table" tbl))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- getSQLr
  "" {:tag SQLr}
  ([ctr] (getSQLr ctr false))
  ([^Execvisor ctr tx?]
   {:pre [(some? ctr)]}
   (let [db (-> (.dftDbPool ctr)
                (dbapi<> *auth-meta-cache*))]
     (if tx?
       (.compositeSQLr db)
       (.simpleSQLr db)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn createAuthRole
  "Create a new auth-role in db"
  ^APersistentMap
  [^SQLr sql ^String role ^String desc]
  {:pre [(some? sql)]}
  (let [m (.get (.metas sql)
                :czlab.wabbit.plugs.auth.model/AuthRole)
        rc (-> (dbpojo<> m)
               (dbSetFlds* {:name role
                            :desc desc}))]
    (.insert sql rc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn deleteAuthRole
  "Delete this role"
  [^SQLr sql ^String role]
  {:pre [(some? sql)]}
  (let [m (.get (.metas sql)
                :czlab.wabbit.plugs.auth.model/AuthRole)]
    (.exec sql
           (format
             "delete from %s where %s =?"
             (.fmtId sql (dbtable m))
             (.fmtId sql (dbcol :name m))) [(strim role)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn listAuthRoles
  "List all the roles in db"
  ^Iterable
  [^SQLr sql]
  {:pre [(some? sql)]}
  (.findAll sql :czlab.wabbit.plugs.auth.model/AuthRole))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn createLoginAccount

  "Create a new account
   props : extra properties, such as email address.
   roleObjs : a list of roles to be assigned to the account"
  {:tag APersistentMap}

  ([sql user pwdObj props]
   (createLoginAccount sql user pwdObj props nil))

  ([sql user pwdObj]
   (createLoginAccount sql user pwdObj nil nil))

  ([^SQLr sql ^String user
    ^IPassword pwdObj props roleObjs]
   {:pre [(some? sql)(hgl? user)]}

   (let [m (.get (.metas sql)
                 :czlab.wabbit.plugs.auth.model/LoginAccount)
         ps (if pwdObj
              (:hash (.hashed pwdObj)))
         acc
         (->>
           (dbSetFlds* (dbpojo<> m)
                       (merge props {:acctid (strim user)
                                     :passwd ps}))
           (.insert sql))]
     ;; currently adding roles to the account is not bound to the
     ;; previous insert. That is, if we fail to set a role, it's
     ;; assumed ok for the account to remain inserted
     (doseq [r roleObjs]
       (dbSetM2M {:joined :czlab.wabbit.plugs.auth.model/AccountRoles
                  :with sql} acc r))
     (log/debug "created new account %s%s%s%s"
                "into db: " acc "\nwith meta\n" (meta acc))
     acc)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn findLoginAccountViaEmail

  "Look for account with this email address"
  ^APersistentMap
  [^SQLr sql ^String email]
  {:pre [(some? sql)]}

  (.findOne sql
            :czlab.wabbit.plugs.auth.model/LoginAccount
            {:email (strim email) }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn findLoginAccount

  "Look for account with this user id"
  ^APersistentMap
  [^SQLr sql ^String user]
  {:pre [(some? sql)]}

  (.findOne sql
            :czlab.wabbit.plugs.auth.model/LoginAccount
            {:acctid (strim user) }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn getLoginAccount

  "Get the user account"
  [^SQLr sql ^String user ^String pwd]

  (if-some
    [acct (findLoginAccount sql user)]
    (if (.validateHash (passwd<> pwd)
                       (:passwd acct))
      acct
      (trap! AuthError (rstr (I18N/base) "auth.bad.pwd")))
    (trap! UnknownUser user)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn hasLoginAccount?
  "If this user account exists"
  [^SQLr sql ^String user] (some? (findLoginAccount sql user)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn changeLoginAccount

  "Change the account password"
  ^APersistentMap
  [^SQLr sql userObj ^IPassword pwdObj]
  {:pre [(some? sql)
         (map? userObj)(some? pwdObj)]}

  (let [ps (.hashed pwdObj)
        m {:passwd (:hash ps)
           :salt (:salt ps)}]
    (->> (dbSetFlds*
           (mockPojo<> userObj) m)
         (.update sql))
    (dbSetFlds* userObj m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn updateLoginAccount

  "Update account details
   details: a set of properties such as email address"
  ^APersistentMap
  [^SQLr sql userObj details]
  {:pre [(some? sql)(map? userObj)]}

  (if-not (empty? details)
    (do
      (->> (dbSetFlds*
             (mockPojo<> userObj) details)
           (.update sql))
      (dbSetFlds* userObj details))
    userObj))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn deleteLoginAccountRole

  "Remove a role from this user"
  ^long
  [^SQLr sql user role]
  {:pre [(some? sql)]}

  (dbClrM2M
    {:joined :czlab.wabbit.plugs.auth.model/AccountRoles :with sql} user role))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn addLoginAccountRole

  "Add a role to this user"
  ^APersistentMap
  [^SQLr sql user role]
  {:pre [(some? sql)]}

  (dbSetM2M
    {:joined :czlab.wabbit.plugs.auth.model/AccountRoles :with sql} user role))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn deleteLoginAccount

  "Delete this account"
  ^long
  [^SQLr sql user] {:pre [(some? sql)]} (.delete sql user))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn deleteUser

  "Delete the account with this user id"
  ^long
  [^SQLr sql ^String user]
  {:pre [(some? sql)]}

  (let [m (.get (.metas sql)
                :czlab.wabbit.plugs.auth.model/LoginAccount)]
    (.exec sql
           (format
             "delete from %s where %s =?"
             (.fmtId sql (dbtable m))
             (.fmtId sql (dbcol :acctid m))) [(strim user)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn listLoginAccounts

  "List all user accounts"
  ^Iterable
  [^SQLr sql]
  {:pre [(some? sql)]}

  (.findAll sql :czlab.wabbit.plugs.auth.model/LoginAccount))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- initShiro
  ""
  [^File homeDir ^String podKey]

  (let [f (io/file homeDir "etc/shiro.ini")]
    (if-not (fileRead? f)
      (trap! ConfigError "Missing shiro ini file"))
    (-> (io/as-url f)
        str
        IniSecurityManagerFactory.
        .getInstance
        SecurityUtils/setSecurityManager)
    (log/info "created shiro security manager")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn signupTestExpr<>

  "Test component of a standard sign-up workflow"
  [^String challengeStr ^Job job]

  (let
    [^HttpMsg evt (.origin job)
     ck (.cookie evt
                 wss/csrf-cookie)
     csrf (some-> ck .getValue)
     info (try
            (getSignupInfo evt)
            (catch BadDataError _ {:e _}))
     info (or info {})
     rb (I18N/base)
     ^AuthPluglet
     pa (-> (.. evt source server)
            (.child :$auth))]
    (log/debug "csrf = %s%s%s"
               csrf ", and form parts = " info)
    (test-some "auth-pluglet" pa)
    (cond
      (some? (:e info))
      (do->false
        (->> {:error (exp! AuthError
                           ""
                           (cexp? (:e info)))}
             (.setLastResult job)))

      (and (hgl? challengeStr)
           (not= challengeStr (:captcha info)))
      (do->false
        (->> {:error (exp! AuthError
                           (rstr rb "auth.bad.cha"))}
             (.setLastResult job)))

      (not= csrf (:csrf info))
      (do->false
        (->> {:error (exp! AuthError
                           (rstr rb "auth.bad.tkn"))}
             (.setLastResult job)))

      (and (hgl? (:credential info))
           (hgl? (:principal info))
           (hgl? (:email info)))
      (if (.hasAccount pa info)
        (do->false
          (->> {:error (exp! DuplicateUser
                             (str (:principal info)))}
               (.setLastResult job )))
        (do->true
          (->> {:account (.addAccount pa info)}
               (.setLastResult job))))

      :else
      (do->false
        (->> {:error (exp! AuthError
                           (rstr rb "auth.bad.req"))}
             (.setLastResult job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn loginTestExpr<> "" [^Job job]

  (let
    [^HttpMsg evt (.origin job)
     ck (.cookie evt
                 wss/csrf-cookie)
     csrf (some-> ck .getValue)
     info (try
            (getSignupInfo evt)
            (catch BadDataError _ {:e _}))
     info (or info {})
     rb (I18N/base)
     ^AuthPluglet
     pa (-> (.. evt source server)
            (.child :$auth))]
    (log/debug "csrf = %s%s%s"
               csrf ", and form parts = " info)
    (test-some "auth-pluglet" pa)
    (cond
      (some? (:e info))
      (do->false
        (->> {:error (exp! AuthError
                           ""
                           (cexp? (:e info)))}
             (.setLastResult job)))

      (not= csrf (:csrf info))
      (do->false
        (->> {:error (exp! AuthError
                           (rstr rb "auth.bad.tkn"))}
             (.setLastResult job)))

      (and (hgl? (:credential info))
           (hgl? (:principal info)))
      (do
        (->> {:account (.login pa (:principal info)
                               (:credential info))}
             (.setLastResult job))
        (some? (:account (.lastResult job))))

      :else
      (do->false
        (->> {:error (exp! AuthError
                           (rstr rb "auth.bad.req"))}
             (.setLastResult job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- authPluglet<>

  ""
  ^AuthPluglet
  [^Execvisor ctr pid]

  (let [impl (muble<>)]
    (reify AuthPluglet

      (isEnabled [this]
          (!false? (:enabled? (.config this))))

      (config [_] (.intern impl))
      (spec [_] nil)

      (server [_] ctr)
      (hold [_ _ _])
      (version [_] "")
      (id [_] pid)
      (getx [_] impl)

      (init [_ arg]
        (assertPluginOK (.dftDbPool ctr))
        (prevarCfg arg)
        (initShiro (.homeDir ctr)
                   (.pkey ctr)))

      (start [_ _]
        (log/info "AuthPluglet started"))

      (stop [_]
        (log/info "AuthPluglet stopped"))

      (dispose [_]
        (log/info "AuthPluglet disposed"))

      (checkAction [_ acctObj action] )

      (addAccount [_ arg]
        (let [pkey (.pkey ctr)]
          (createLoginAccount
            (getSQLr ctr)
            (:principal arg)
            (-> (:credential arg)
                (passwd<> pkey))
            (dissoc arg
                    :principal :credential)
            [])))

      (login [_ u p]
        (binding
          [*meta-cache* *auth-meta-cache*
           *jdbc-pool* (.dftDbPool ctr)]
          (let
            [cur (SecurityUtils/getSubject)
             sss (.getSession cur)]
            (log/debug "Current user session %s" sss)
            (log/debug "Current user object %s" cur)
            (when-not (.isAuthenticated cur)
              (try!
                ;;(.setRememberMe token true)
                (.login cur
                        (UsernamePasswordToken. ^String u ^String p))
                (log/debug "User [%s] logged in successfully" u)))
            (if (.isAuthenticated cur)
              (.getPrincipal cur)))))

      (hasAccount [_ arg]
        (let [pkey (.pkey ctr)]
          (hasLoginAccount? (getSQLr ctr)
                            (:principal arg))))

      (account [_ arg]
        (let [pkey (.pkey ctr)
              sql (getSQLr ctr)]
          (cond
            (hgl? (:principal arg))
            (findLoginAccount sql
                              (:principal arg))
            (hgl? (:email arg))
            (findLoginAccountViaEmail sql
                                      (:email arg))
            :else nil)))

      ;;TODO: get roles please
      (roles [_ acct] []))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn WebAuth "" ^AuthPluglet [ctr id] (authPluglet<> ctr id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(deftype PwdMatcher [] CredentialsMatcher

  (doCredentialsMatch [_ token info]
    (let [^AuthenticationToken tkn token
          ^AuthenticationInfo inf info
          pwd (.getCredentials tkn)
          uid (.getPrincipal tkn)
          pc (.getCredentials inf)
          tstPwd (passwd<> pwd)
          acc (-> (.getPrincipals inf)
                  (.getPrimaryPrincipal))]
      (and (= (:acctid acc) uid)
           (.validateHash tstPwd pc)))))

(ns-unmap *ns* '->PwdMatcher)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- doMain "" [& args]
  (let [homeDir (io/file (first args))
        cmd (nth args 1)
        db (nth args 2)
        pod (slurpXXXConf homeDir cfg-pod-cf true)
        pkey (-> (get-in pod
                         [:info :digest])
                 str .toCharArray)
        cfg (get-in pod [:rdbms (keyword db)])]
    (when cfg
      (let [pwd (.text (passwd<> (:passwd cfg) pkey))
            j (dbspec<> (assoc cfg :passwd pwd))
            t (matchUrl (:url cfg))]
        (cond
          (= "init-db" cmd)
          (applyDDL j)

          (= "gen-sql" cmd)
          (if (> (count args) 3)
            (exportAuthPlugletDDL t
                                 (io/file (nth args 3)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; home gen-sql alias outfile
;; home init-db alias
(defn- main "Main Entry" [& args]
  ;; for security, don't just eval stuff
  ;;(alter-var-root #'*read-eval* (constantly false))
  (if-not (< (count args) 3) (apply doMain args)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF


