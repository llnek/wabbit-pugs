;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Implementation for JMS service."
      :author "Kenneth Leung" }

  czlab.wabbit.plugs.jms

  (:require [czlab.basal.logging :as log])

  (:use [czlab.twisty.codec]
        [czlab.wabbit.base]
        [czlab.wabbit.xpis]
        [czlab.basal.core]
        [czlab.basal.io]
        [czlab.basal.str]
        [czlab.wabbit.plugs.core])

  (:import [java.util Hashtable Properties ResourceBundle]
           [czlab.jasal Hierarchical LifeCycle Idable]
           [javax.jms
            ConnectionFactory
            Connection
            Destination
            Connection
            Message
            MessageConsumer
            MessageListener
            Queue
            QueueConnection
            QueueConnectionFactory
            QueueReceiver
            QueueSession
            Session
            Topic
            TopicConnection
            TopicConnectionFactory
            TopicSession
            TopicSubscriber]
           [java.io IOException]
           [clojure.lang APersistentMap]
           [javax.naming Context InitialContext]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

(decl-object JmsMsg
             PlugletMsg
             (get-pluglet [me] (:$source me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private evt<>
  "" [co msg]

  `(object<> JmsMsg
             {:id (str "JmsMsg." (seqint2))
              :$source ~co
              :message ~msg }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defmacro ^:private onMsg "" [co msg]
  ;;if (msg!=null) block { () => msg.acknowledge() }
  `(dispatch! (evt<> ~co ~msg)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- inizFac
  "" ^Connection
  [co ^InitialContext ctx ^ConnectionFactory cf]

  (let
    [{:keys [destination jmsPwd jmsUser]}
     (:conf @co)
     pwd (->> co get-server
              pkey-chars (pwd<> jmsPwd) p-text)
     c (.lookup ctx ^String destination)
     ^Connection
     conn (if (hgl? jmsUser)
            (.createConnection
              cf
              ^String jmsUser
              (stror (strit pwd) nil))
            (.createConnection cf))]
    (if (ist? Destination c)
      ;;TODO ? ack always ?
      (-> (.createSession conn false Session/CLIENT_ACKNOWLEDGE)
          (.createConsumer c)
          (.setMessageListener
            (reify MessageListener
              (onMessage [_ m] (onMsg co m)))))
      (throwIOE "Object not of Destination type"))
    conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- inizTopic
  "" ^Connection
  [co ^InitialContext ctx ^TopicConnectionFactory cf]

  (let
    [{:keys [destination jmsUser durable? jmsPwd]}
     (:conf @co)
     pwd (->> co get-server
              pkey-chars (pwd<> jmsPwd) p-text)
     conn (if (hgl? jmsUser)
            (.createTopicConnection
              cf
              ^String jmsUser
              (stror (strit pwd) nil))
            (.createTopicConnection cf))
     s (.createTopicSession
         conn false Session/CLIENT_ACKNOWLEDGE)
     t (.lookup ctx ^String destination)]
    (if-not (ist? Topic t)
      (throwIOE "Object not of Topic type"))
    (-> (if durable?
          (.createDurableSubscriber s t (jid<>))
          (.createSubscriber s t))
        (.setMessageListener
          (reify MessageListener
            (onMessage [_ m] (onMsg co m)))))
    conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- inizQueue
  "" ^Connection
  [co ^InitialContext ctx ^QueueConnectionFactory cf]

  (let
    [{:keys [destination jmsUser jmsPwd]}
     (:conf @co)
     pwd (->> co get-server
              pkey-chars (pwd<> jmsPwd) p-text)
     conn (if (hgl? jmsUser)
            (.createQueueConnection
              cf
              ^String jmsUser
              (stror (strit pwd) nil))
            (.createQueueConnection cf))
     s (.createQueueSession conn
                            false Session/CLIENT_ACKNOWLEDGE)
     q (.lookup ctx ^String destination)]
    (if-not (ist? Queue q)
      (throwIOE "Object not of Queue type"))
    (-> (.createReceiver s ^Queue q)
        (.setMessageListener
          (reify MessageListener
            (onMessage [_ m] (onMsg co m)))))
    conn))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- sanitize
  ""
  [pkey {:keys [jndiPwd jmsPwd] :as cfg}]

  (-> (assoc cfg :jndiPwd (p-text (pwd<> jndiPwd pkey)))
      (assoc :jmsPwd (p-text (pwd<> jmsPwd pkey)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- start2

  ""
  ^Connection
  [co {:keys [contextFactory
              providerUrl
              jndiUser jndiPwd connFactory]}]

  (let
    [vars (Hashtable.)]
    (if (hgl? providerUrl)
      (.put vars Context/PROVIDER_URL providerUrl))
    (if (hgl? contextFactory)
      (.put vars
            Context/INITIAL_CONTEXT_FACTORY
            contextFactory))
    (when (hgl? jndiUser)
      (.put vars "jndi.user" jndiUser)
      (if (hgl? jndiPwd)
        (.put vars "jndi.password" jndiPwd)))
    (let
      [ctx (InitialContext. vars)
       obj (->> (str connFactory)
                (.lookup ctx))
       ^Connection
       c (condp instance? obj
           QueueConnectionFactory
           (inizQueue co ctx obj)
           TopicConnectionFactory
           (inizTopic co ctx obj)
           ConnectionFactory
           (inizFac co ctx obj))]
      (if (nil? c)
        (throwIOE "Unsupported JMS Connection Factory"))
      (.start c)
      c)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(decl-mutable JmsPluglet
  Hierarchical
  (parent [me] (:parent @me))
  Idable
  (id [me] (:emAlias @me))
  LifeCycle
  (init [me arg]
    (let [k (-> me get-server pkey-chars)
          c (get-in @me [:pspec :conf])]
      (->> (prevarCfg (sanitize k
                                (merge c arg)))
           (setf! me :conf))))
  (start [me] (.start me nil))
  (start [me arg]
    (->> (:conf @me) (start2 me) (setf! me :$conn)))
  (stop [me]
    (when-some [c (:$conn @me)]
      (try! (closeQ c))))
  (dispose [me] (.stop me)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  specdef
  {:info {:name "JMS Client"
          :version "1.0.0"}
   :conf {:$pluggable ::JMS
          :contextFactory "czlab.proto.mock.jms.MockContextFactory"
          :providerUrl "java://aaa"
          :connFactory "tcf"
          :destination "topic.abc"
          :jndiUser "root"
          :jndiPwd "root"
          :jmsUser "anonymous"
          :jmsPwd "anonymous"
          :handler nil}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn JMSSpec "" ^APersistentMap [] specdef)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn JMS ""
  ([_ id] (JMS _ id (JMSSpec)))
  ([_ id spec]
   (mutable<> JmsPluglet
              {:pspec (update-in spec [:conf] expandVarsInForm)
               :parent _
               :emAlias id })))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF
