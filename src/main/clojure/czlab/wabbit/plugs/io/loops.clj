;; Copyright (c) 2013-2017, Kenneth Leung. All rights reserved.
;; The use and distribution terms for this software are covered by the
;; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;; which can be found in the file epl-v10.html at the root of this distribution.
;; By using this software in any fashion, you are agreeing to be bound by
;; the terms of this license.
;; You must not remove this notice, or any other, from this software.

(ns ^{:doc "Basic functions for loopable services."
      :author "Kenneth Leung"}

  czlab.wabbit.plugs.io.loops

  (:require [czlab.basal.dates :refer [parseDate]]
            [czlab.basal.process :refer [async!]]
            [czlab.basal.meta :refer [getCldr]]
            [czlab.basal.logging :as log])

  (:use [czlab.wabbit.base]
        [czlab.basal.core]
        [czlab.basal.str]
        [czlab.wabbit.plugs.io.core])

  (:import [java.util Date Timer TimerTask]
           [czlab.jasal LifeCycle Idable]
           [clojure.lang APersistentMap]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;(set! *warn-on-reflection* true)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- configRepeat
  ""
  ^TimerTask
  [^Timer timer [dw ds] ^long intv func]

  (log/info "Scheduling a *repeating* timer: %dms" intv)
  (let [tt (tmtask<> func)]
    (if (spos? intv)
      (if
        (ist? Date dw)
        (.schedule timer tt ^Date dw intv)
        ;else
        (.schedule timer
                   tt
                   (long (if (spos? ds) ds 1000)) intv)))
    tt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- configOnce
  ""
  ^TimerTask
  [^Timer timer [dw ds] func]

  (log/info "Scheduling a *one-shot* timer at %s" [dw ds])
  (let [tt (tmtask<> func)]
    (if
      (ist? Date dw)
      (.schedule timer tt ^Date dw)
      ;else
      (.schedule timer
                 tt
                 (long (if (spos? ds) ds 1000))))
    tt))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn configTimer
  ""
  ^TimerTask
  [timer wakeup {:keys [intervalSecs
                        delayWhen
                        delaySecs] :as cfg} repeat?]
  (let [d [delayWhen (s2ms delaySecs)]]
    (if (and repeat?
             (spos? intervalSecs))
      (configRepeat timer
                    d
                    (s2ms intervalSecs) wakeup)
      (configOnce timer d wakeup))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn scheduleThreadedLoop "" [plug waker]
  (let [{:keys [intervalSecs] :as cfg}
        (:conf @plug)
        loopy (volatile! true)
        ms (s2ms intervalSecs)
        w #(async!
             (fn [_]
               (while @loopy
                 (waker me) (pause ms))))]
    (configTimer (:timer @plug) w cfg false)
    loopy))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn stopThreadedLoop "" [loopy] (vreset! loopy false))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(decl-mutable TimerPluglet
  Pluglet
  (hold-event [_ t millis] (scheduleTrigger _ t millis))
  (get-server [me] (:parent @me))
  Idable
  (id [me] (:emAlias @me))
  LifeCycle
  (init [me arg]
    (let [c (get-in @me [:pspec :conf])]
      (doto->> (prevarCfg (merge c arg))
               (setf! me :conf))))
  (start [me] (.start me nil))
  (start [me arg]
    (let [r? (:repeatable? @me)]
      (->> (configTimer (:timer @me)
                        #(dispatch! (evt<> me r?))
                        (:conf @me) r?)
           (setf! me :ttask))))
  (stop [me]
    (cancelTimer (:timer @me)))
  (dispose [_]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(decl-object TimerMsg)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- evt<> [co repeat?]
  (object<> TimerMsg
            {:id (str "TimerMsg." (seqint2))
             :tstamp (now<>)
             :source co
             :isRepeating? repeat?} ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  manyspecdef
  {:info {:name "Repeating Timer"
          :version "1.0.0"}
   :conf {:$pluggable ::RepeatingTimer
          :intervalSecs 300
          :delaySecs 0
          :handler nil}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(def
  ^:private
  onespecdef
  {:info {:name "One Shot Timer"
          :version "1.0.0"}
   :conf {:$pluggable ::OnceTimer
          :delaySecs 0
          :handler nil}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- cancelTimer "" [^TimerTask tt] (try! (some-> tt .cancel)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn- xxxTimer<> "" [_ id spec repeat?]
  (mutable<> TimerPluglet
             {:pspec (update-in spec
                                [:conf] expandVarsInForm)
              :timer (Timer. true)
              :parent _
              :emAlias id
              :repeatable? repeat?}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn RepeatingTimerSpec "" ^APersistentMap [] manyspecdef)
(defn OnceTimerSpec "" ^APersistentMap [] onespecdef)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn RepeatingTimer ""
  ([_ id] (RepeatingTimer _ id (RepeatingTimerSpec)))
  ([_ id spec] (xxxTimer<> _ id spec true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
(defn OnceTimer ""
  ([_ id] (OnceTimer _ id (OnceTimerSpec)))
  ([_ id spec] (xxxTimer<> _ id spec false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;EOF

