;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Implementation for email services."
      :author "Kenneth Leung"}

  czlab.wabbit.plugs.mails

  (:require [czlab.wabbit.plugs.loops :as pl]
            [czlab.wabbit.plugs.core :as pc]
            [czlab.twisty.codec :as co]
            [czlab.wabbit.base :as b]
            [czlab.wabbit.xpis :as xp]
            [czlab.basal.log :as log]
            [czlab.basal.core :as c]
            [czlab.basal.str :as s])

  (:import [czlab.jasal Hierarchical LifeCycle Idable]
           [javax.mail.internet MimeMessage]
           [clojure.lang APersistentMap]
           [javax.mail
            Flags$Flag
            Flags
            Store
            Folder
            Session
            Provider
            Provider$Type]
           [java.util Properties]
           [java.io IOException]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)
(def
  ^:dynamic
  *mock-mail-provider*
  {:pop3s "czlab.proto.mock.mail.MockPop3SSLStore"
   :imaps "czlab.proto.mock.mail.MockIMapSSLStore"
   :pop3 "czlab.proto.mock.mail.MockPop3Store"
   :imap "czlab.proto.mock.mail.MockIMapStore"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POP3
(def ^:private cz-pop3s  "com.sun.mail.pop3.POP3SSLStore")
(def ^:private cz-pop3  "com.sun.mail.pop3.POP3Store")
(def ^:private pop3s "pop3s")
(def ^:private pop3 "pop3")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; IMAP
(def ^:private cz-imaps "com.sun.mail.imap.IMAPSSLStore")
(def ^:private cz-imap "com.sun.mail.imap.IMAPStore")
(def ^:private imaps "imaps")
(def ^:private imap "imap")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- closeFolder "" [^Folder fd]
  (if fd (c/trye!! nil (if (.isOpen fd) (.close fd true)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- closeStore "" [co]
  (let [{:keys [store folder]} @co]
    (closeFolder folder)
    (c/trye!! nil (some-> ^Store store .close))
    (c/unsetf! co :store)
    (c/unsetf! co :folder)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- resolveProvider
  ""
  [co [^String cz ^String proto]]

  (let
    [mockp (c/sysProp "wabbit.mock.mail.proto")
     demo? (s/hgl? mockp)
     proto (if demo? mockp proto)
     demop (*mock-mail-provider* (keyword proto))
     ss (-> (doto (Properties.)
              (.put  "mail.store.protocol" proto))
            (Session/getInstance nil))
     [^Provider sun ^String pz]
     (if demo?
       [(Provider. Provider$Type/STORE
                   proto demop "czlab" "1.1.7") demop]
       [(some #(if (= cz (.getClassName ^Provider %)) %)
              (.getProviders ss)) cz])]
    (if (nil? sun)
      (c/throwIOE (str "Failed to find store: " pz)))
    (log/info "mail store impl = %s" sun)
    (.setProvider ss sun)
    (c/copy* co {:proto proto :pz pz :session ss})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-object MailMsg
               xp/PlugletMsg
               (get-pluglet [me] (:$source me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private evt<> "" [co msg]
  `(c/object<> MailMsg
               {:id (str "MailMsg." (c/seqint2))
                :$source ~co
                :message ~msg}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- connectPop3 "" [co]

  (let [{:keys [^Session session
                ^String proto]}
        @co
        {:keys [^String host
                ^String user
                port
                passwd]}
        (:conf @co)
        s (.getStore session proto)]
    (if (nil? s)
      (log/warn "failed to get session store for %s" proto)
      (do
        (log/debug "connecting to session store for %s" proto)
        (.connect s
                  host
                  ^long port
                  user
                  (s/stror (c/strit passwd) nil))
        (c/copy* co
                 {:store s
                  :folder (some-> (.getDefaultFolder s)
                                  (.getFolder "INBOX"))})
        (let [fd (:folder @co)]
          (when (or (nil? fd)
                    (not (.exists ^Folder fd)))
            (log/warn "mail store folder not-good for proto %s" proto)
            (c/unsetf! co :store)
            (c/trye!! nil (.close s))
            (c/throwIOE "cannot find inbox")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- readPop3 "" [co msgs]

  (let [d? (get-in @co [:conf :deleteMsg?])]
    (doseq [^MimeMessage mm msgs]
      (doto mm
        (.getAllHeaders)
        (.getContent))
      (pc/dispatch! (evt<> co mm))
      (when d? (.setFlag mm Flags$Flag/DELETED true)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- scanPop3 "" [co]

  (let [{:keys [^Folder folder ^Store store]} @co]
    (if (and folder
             (not (.isOpen folder)))
      (.open folder Folder/READ_WRITE))
    (when (and folder
               (.isOpen folder))
      (try
        (let [cnt (.getMessageCount folder)]
          (log/debug "count of new mail-messages: %d" cnt)
          (if (c/spos? cnt)
            (readPop3 co (.getMessages folder))))
        (finally
          (c/trye!! nil (.close folder true)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- sanitize
  ""
  [pkey {:keys [port deleteMsg?
                host user ssl? passwd] :as cfg0}]

  (-> (assoc cfg0 :ssl? (c/!false? ssl?))
      (assoc :deleteMsg? (true? deleteMsg?))
      (assoc :host (str host))
      (assoc :port (if (c/spos? port) port 995))
      (assoc :user (str user ))
      (assoc :passwd (co/p-text (co/pwd<> passwd pkey)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(c/decl-mutable EmailPluglet
  Hierarchical
  (parent [me] (:parent @me))
  Idable
  (id [me] (:emAlias @me))
  LifeCycle
  (init [me arg]
    (let [k (-> me xp/get-server xp/pkey-chars)
          {:keys [sslvars vars]} @me
          c (get-in @me [:pspec :conf])
          c2 (b/prevarCfg (sanitize k (merge c arg)))]
      (c/setf! me :conf c2)
      (resolveProvider me
                       (if (:ssl? c2) sslvars vars))))
  (start [me] (.start me nil))
  (start [me arg]
    (->> (:waker @me)
         (pl/scheduleThreadedLoop me ) (c/setf! me :loopy )))
  (stop [me]
    (pl/stopThreadedLoop (:loopy @me)))
  (dispose [me] (.stop me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- emailXXX "" [_ id spec sslvars vars wakerFunc]
  (c/mutable<> EmailPluglet
               {:pspec (update-in spec
                                  [:conf]
                                  b/expandVarsInForm)
                :sslvars sslvars
                :vars vars
                :parent _
                :emAlias id
                :waker wakerFunc}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  popspecdef
  {:info {:name "POP3 Client"
          :version "1.0.0"}
   :conf {:$pluggable ::POP3
          :host "pop.gmail.com"
          :port 995
          :deleteMsg? false
          :username "joe"
          :passwd "secret"
          :intervalSecs 300
          :delaySecs 0
          :ssl? true
          :handler nil}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn POP3Spec "" ^APersistentMap [] popspecdef)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private connectIMAP "" [co] `(connectPop3 ~co))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private scanIMAP "" [co] `(scanPop3 ~co))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wakePOP3 "" [co]

  (try
    (connectPop3 co)
    (scanPop3 co)
    (catch Throwable _
      (log/exception _))
    (finally
      (closeStore co))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- wakeIMAP "" [co]

  (try
    (connectIMAP co)
    (scanIMAP co)
    (catch Throwable _
      (log/exception _))
    (finally
      (closeStore co))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  imapspecdef
  {:info {:name "IMAP Client"
          :version "1.0.0"}
   :conf {:$pluggable ::IMAP
          :host "imap.gmail.com"
          :port 993
          :deleteMsg? false
          :ssl? true
          :username "joe"
          :passwd "secret"
          :intervalSecs 300
          :delaySecs 0
          :handler nil}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn IMAPSpec "" ^APersistentMap [] imapspecdef)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn IMAP ""
  ([_ id] (IMAP _ id (IMAPSpec)))
  ([_ id spec]
   (emailXXX _ id spec [cz-imaps imaps] [cz-imap imap] wakeIMAP)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn POP3 ""
  ([_ id] (POP3 _ id (POP3Spec)))
  ([_ id spec]
   (emailXXX _ id spec [cz-pop3s pop3s] [cz-pop3 pop3] wakePOP3)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

