(in-ns 'csneps.snip)

(load "snip_message")
(load "snip_linear_msg_set")
(load "snip_sindex")
(load "snip_ptree")

;; Tracking inference tasks
(def ^:dynamic taskid 0)

;; For debug:
(def print-intermediate-results false)
(def print-results-on-infer false)
(def debug false)
(def showproofs true)

(declare initiate-node-task create-message-structure get-rule-use-info open-valve cancel-infer-of)

(defn priority-partial
  [priority f & more] 
  (with-meta (apply partial f more) {:priority priority}))

;;;;;;;;;;;;;;;;;;;;;;;;;;; 
;;; Concurrency control ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Incremented whenever a message is submitted, and decremented once inference
;; on a message is complete, this allows us to determine when the graph is in
;; a quiescent state.
(def infer-status (ref {nil (edu.buffalo.csneps.util.CountingLatch.)}))

;; Tasks have :priority metadata. This allows the queue to order them
;; properly for execution. Higher priority is executed first.
(def task-cmpr (proxy [Comparator] []
                 (compare [a b]
                          (let [p_a (:priority (meta a))
                                p_b (:priority (meta b))]
                            (cond
                              (= p_a p_b) 0
                              (> p_a p_b) -1
                              :else 1)))))

;; Priority Blocking Queue to handle the tasks.
(def queue (PriorityBlockingQueue. 50 task-cmpr))

;(def queue (LinkedBlockingQueue.))

(def cpus-to-use (/ (.availableProcessors (Runtime/getRuntime)) 2))

;; Fixed Thread Pool of size 2 * processors, using queue as it's queue.
(def executorService (ThreadPoolExecutor.
                       cpus-to-use
                       cpus-to-use
                       (Long/MAX_VALUE) TimeUnit/NANOSECONDS queue))
(.prestartAllCoreThreads ^ThreadPoolExecutor executorService)

(defn resetExecutor
  []
  (.clear ^PriorityBlockingQueue queue)
  (.shutdown ^ThreadPoolExecutor executorService)
  (def executorService (ThreadPoolExecutor.
                         cpus-to-use
                         cpus-to-use
                         (Long/MAX_VALUE) TimeUnit/NANOSECONDS queue))
  (.prestartAllCoreThreads ^ThreadPoolExecutor executorService)
  (def infer-status (ref {nil (edu.buffalo.csneps.util.CountingLatch.)})))

;;; Experimental attempt at pausing inference.
(let [waiting-queue (LinkedBlockingQueue.)]
  (defn pause-execute 
    []
    (.drainTo queue waiting-queue))
  
  (defn resume-execute
    []
    (.drainTo waiting-queue queue)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Channel Maniupulation ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn submit-to-channel
  [^csneps.core.build.Channel channel ^csneps.snip.Message message]
  (when debug (send screenprinter (fn [_]  (println "MSGTX: " message))))
  
  ;; Filter
  (when ((:filter-fn channel) (:subst message))
    ;; Switch
    (when debug (send screenprinter (fn [_]  (println "SWITCH!: " ((:switch-fn channel) (:subst message))))))
    (let [message (derivative-message message :subst ((:switch-fn channel) (:subst message)))]
      (if (build/pass-message? channel message)
        ;; Process the message immediately. For forward infer, this ammounts to 
        ;; ignoring the status of the valve.
        (do 
          ;(send screenprinter (fn [_]  (println "inc-stc" (:taskid message))))
          (when (:taskid message) (.increment (@infer-status (:taskid message))))
          (when debug (send screenprinter (fn [_]  (println "MSGRX: " message "by" (:destination channel)))))
          (.execute ^ThreadPoolExecutor executorService 
            (priority-partial 1 initiate-node-task (:destination channel) message)))
        ;; Cache in the waiting-msgs
        (dosync (alter (:waiting-msgs channel) conj message))))))

(defn submit-assertion-to-channels
  [term & {:keys [fwd-infer] :or {:fwd-infer false}}]
  ;; Send the information about this assertion forward in the graph.
  ;; Positive instances:
  (let [msg (new-message {:origin term, 
                          :support-set #{#{term}}, 
                          :type 'I-INFER, 
                          :fwd-infer? fwd-infer
                          :pos 1
                          :neg 0
                          :flaggedns {term true}})]
    (doall (map #(submit-to-channel % msg) @(:i-channels term))))
  ;; Conjunctions:
  ;; Conjunctions have no antecedents if they are true, so the U-INFER messages must be passed on here
  (when (= (csneps/type-of term) :csneps.core/Conjunction)
    (let [msg (new-message {:origin term, :support-set #{#{term}}, :type 'U-INFER, :fwd-infer? fwd-infer})]
      (doall (map #(submit-to-channel % msg) @(:u-channels term)))))
  ;; Negations:
  ;; When the assertion makes terms negated, the negated terms need to send out
  ;; on their i-channels that they are now negated.
  (let [dcs-map (cf/dcsRelationTermsetMap term)
        nor-dcs (when dcs-map (dcs-map (slot/find-slot 'nor)))
        msg (when nor-dcs (new-message {:origin term, :support-set #{#{term}}, :type 'I-INFER, :true? false, :fwd-infer? fwd-infer}))]
    (when nor-dcs
      (doseq [negterm nor-dcs]
        (doall (map #(submit-to-channel 
                       % 
                       (new-message {:origin negterm, :support-set #{#{negterm}}, :type 'I-INFER, :true? false, :fwd-infer? fwd-infer})) 
                    @(:i-channels negterm)))))))

(defn open-valve 
  [channel taskid]
  ;; Start by opening the channel. Anything new should go right to the executor pool.
  (dosync (ref-set (:valve-open channel) true))
  ;; Add the waiting messages to the executor pool.
  (doseq [msg @(:waiting-msgs channel)]
    ;(send screenprinter (fn [_]  (println "inc-ov" taskid)))
    (when taskid (.increment (@infer-status taskid)))
    (.execute ^ThreadPoolExecutor executorService (priority-partial 1 initiate-node-task (:destination channel) (derivative-message msg :taskid taskid))))
  ;; Clear the waiting messages list.
  (dosync (alter (:waiting-msgs channel) empty)))

(defn close-valve
  [channel]
  (dosync (ref-set (:valve-open channel) false)))

(defn add-valve-selector
  [channel subst context taskid]
  (let [subst (build/substitution-application-nomerge (merge subst (:filter-binds channel))
                                                      (or (:switch-binds channel) #{}))
        valve-selector [subst context]]
    ;; Only add the selector and check for new matching messages if this is actually a new selector.
    ;; New messages will otherwise be checked upon being submitted to the channel.
    (when (not (@(:valve-selectors channel) valve-selector))
      (dosync (alter (:valve-selectors channel) conj [subst context]))
      (doseq [msg @(:waiting-msgs channel)]
        (when (build/pass-message? channel msg)
          (send screenprinter (fn [_]  (println "Pass")))
          (dosync (alter (:waiting-msgs channel) disj msg))
          (when taskid (.increment (@infer-status taskid)))
          (.execute ^ThreadPoolExecutor executorService 
            (priority-partial 1 initiate-node-task (:destination channel) (derivative-message msg :taskid taskid))))))
    subst))

;;;;;;;;;;;;;;;;;
;;; Inference ;;;
;;;;;;;;;;;;;;;;;

(defn negated?
  ([term]
    (negated? term (ct/currentContext)))
  ([term context]
    (let [negation (get-froms #{term} (slot/find-slot 'nor))]
      (when-not (empty? negation)
        (ct/asserted? (first negation) context)))))

;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Inference Control ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;

(defn backward-infer
  "Spawns tasks recursively to open valves in channels and begin performing
   backward-chaining inference. The network tries to derive term, and uses a
   set to track which nodes it has already visited."
  ([term] 
    (let [taskid (gensym "task")] 
      (dosync (alter infer-status assoc taskid (edu.buffalo.csneps.util.CountingLatch.)))
      (backward-infer term -10 #{} #{term} {} (ct/currentContext) taskid)))
  ([term taskid] 
    (dosync (alter infer-status assoc taskid (edu.buffalo.csneps.util.CountingLatch.)))
    (backward-infer term -10 #{} #{term} {} (ct/currentContext) taskid))
  ([term invoketermset taskid] 
    (backward-infer term -10 #{} invoketermset {} (ct/currentContext) taskid))
  ;; Opens appropriate in-channels, sends messages to their originators.
  ([term depth visited invoketermset subst context taskid] 
    (dosync (alter (:future-bw-infer term) union invoketermset))
    (doseq [ch @(:ant-in-channels term)]
      (when (and (not (visited term)) 
 -                 (not (visited (:originator ch))))
                 ;(not= (union @(:future-bw-infer (:originator ch)) invoketermset) @(:future-bw-infer (:originator ch))))
        (when debug (send screenprinter (fn [_]  (println "BW: Backward Infer -" depth "- opening channel from" (:originator ch) "to" term))))
        ;(send screenprinter (fn [_]  (println "BW: Backward Infer -" depth "- opening channel from" (:originator ch) "to" term)))
        (let [subst (add-valve-selector ch subst context taskid)]
          (open-valve ch taskid)
          ;(send screenprinter (fn [_]  (println "inc-bwi" taskid)))
          (when taskid (.increment (@infer-status taskid)))
          (.execute ^ThreadPoolExecutor executorService 
            (priority-partial depth 
                              (fn [t d v i s c id] 
                                (backward-infer t d v i s c id) 
                                ;(send screenprinter (fn [_]  (println "dec-bwi" id))) 
                                (when id (.decrement (@infer-status id))))
                              (:originator ch)
                              (dec depth)
                              (conj visited term)
                              invoketermset
                              subst
                              context
                              taskid)))))))

(defn cancel-infer
  "Same idea as backward-infer, except it closes valves. Cancelling inference
   has top priority."
  ([term] (cancel-infer term nil nil))
  ([term cancel-for-term] (cancel-infer term cancel-for-term nil))
  ([term cancel-for-term taskid]
    (when (and (every? #(not @(:valve-open %)) @(:i-channels term))
               (every? #(not @(:valve-open %)) @(:u-channels term)))
      (when debug (send screenprinter (fn [_]  (println "CANCEL: Cancel Infer - closing incoming channels to" term))))
      (when cancel-for-term
        (dosync (alter (:future-bw-infer term) disj cancel-for-term)))
      (doseq [ch @(:ant-in-channels term)]
        (when (empty? @(:future-bw-infer term))
          (close-valve ch))
        (.increment (@infer-status taskid))
        (.execute ^ThreadPoolExecutor executorService 
            (priority-partial Integer/MAX_VALUE 
                              (fn [t c id] (cancel-infer t c id) (.decrement (@infer-status id)))
                              (:originator ch) cancel-for-term taskid))))))

(defn forward-infer
  "Begins inference in term. Ignores the state of valves in sending I-INFER and U-INFER messages
   through the graph."
  [term]
  (let [taskid (gensym "task")]
    (dosync (alter infer-status assoc taskid (edu.buffalo.csneps.util.CountingLatch.)))
    ;; We need to pretend that a U-INFER message came in to this node.
    ;(send screenprinter (fn [_]  (println "inc-fwi")))
    (.increment (@infer-status taskid))
    (.execute ^ThreadPoolExecutor executorService 
      (priority-partial 1 initiate-node-task term 
                        (new-message {:origin nil, :support-set #{}, :type 'U-INFER, :fwd-infer? true :invoke-set #{term} :taskid taskid})))))

(defn cancel-forward-infer
  ([term] (cancel-infer term term))
  ([term cancel-for-term]
    (when cancel-for-term
      (dosync (alter (:future-fw-infer term) disj cancel-for-term)))
    (doseq [ch (concat @(:i-channels term) @(:u-channels term) @(:g-channels term))]
      (.increment (@infer-status nil))
      (.execute ^ThreadPoolExecutor executorService 
        (priority-partial Integer/MAX_VALUE 
                          (fn [t c] (cancel-forward-infer t c) (.decrement (@infer-status nil)))
                          (:destination ch) cancel-for-term)))))

(defn unassert
  "Move forward through the graph recursively unasserting terms which depend on this one."
  ([term]
    (build/unassert term)
    (doseq [ch @(:i-channels term)]
      (.increment (@infer-status nil))
      (.execute ^ThreadPoolExecutor executorService 
        (priority-partial Integer/MAX_VALUE 
                          unassert 
                          (:destination ch) (new-message {:origin term :type 'UNASSERT}) false)))
    (doseq [ch @(:u-channels term)]
      (.increment (@infer-status nil))
      (.execute ^ThreadPoolExecutor executorService (priority-partial Integer/MAX_VALUE unassert (:destination ch) (new-message {:origin term :type 'UNASSERT}) true))))
  ([node message uch?]
    (when debug (send screenprinter (fn [_]  (println "Unassert: " message "at" node))))
    (cond 
      (and (not uch?)
           (= (type node) csneps.core.Implication))
      (doseq [ch @(:u-channels node)]
        (.increment (@infer-status nil))
        (.execute ^ThreadPoolExecutor executorService (priority-partial Integer/MAX_VALUE unassert (:destination ch) (new-message {:origin (:origin message) :type 'UNASSERT}) true)))
      (let [oscont (map #(contains? % (:origin message)) @(:support node))]
        (and uch?
             (seq oscont)
             (every? true? oscont)))
      (unassert node))
    (.decrement (@infer-status nil))))

;; How do we ensure no re-derivations?
;; When do we call this? 
(defn derive-otherwise 
  "Attempt derivation using methods other than the IG"
  [p]
  (setOr
    (sort-based-derivable p (ct/currentContext))
    (slot-based-derivable p (ct/currentContext) nil)))

;;;;;;;;;;;;;;;;;;;;;;;
;;; Inference Rules ;;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn negation-elimination
  "Invert the truth of the :true? key in the message, and pass onward."
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        dermsg (derivative-message message
                                   :origin node
                                   :support-set (conj (:support-set message) node)
                                   :true? false
                                   :type 'U-INFER)
        uch @(:u-channels node)]
    (when debug (send screenprinter (fn [_]  (println "NEX" new-ruis))))
    nil
    (when (seq new-ruis)
      (when showproofs 
        (doseq [u uch]
          (send screenprinter (fn [_] (println "Since " node ", I derived: ~" (build/apply-sub-to-term (:destination u) (:subst dermsg)) " by negation-elimination")))))
      (zipmap uch (repeat (count uch) dermsg)))))

(defn negation-introduction
  "Pretty much conjunction-introduction, but with :neg instead of :pos"
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        der-rui (some #(= (:neg %) (count @(:u-channels node))) new-ruis)
        dermsg (derivative-message message
                                   :origin node
                                   :support-set (conj (:support-set message) node)
                                   :true? true
                                   :type 'I-INFER)
        ich @(:i-channels node)]
    (when debug (send screenprinter (fn [_]  (println "N-Int" new-ruis "\n" der-rui))))
    (when der-rui
      (when showproofs 
        (send screenprinter (fn [_] (println "I derived: " node " by negation-introduction"))))
      (dosync (alter (:support node) conj (:support-set message)))
      [true nil (zipmap ich (repeat (count ich) dermsg))]))) ;;TODO: Fix this.
  

(defn numericalentailment-elimination
  "Since the implication is true, send a U-INFER message to each
   of the consequents." 
  [message node]
  ;; If the node only requires 1 antecedent to be true, any incoming positive
  ;; antecedent is enough to fire the rule. In these cases it isn't necessary to
  ;; maintain the RUI structure. 
  (if (= (:min node) 1)
    (when (:true? message)
      (cancel-infer node nil (:taskid message))
      (let [der-msg (derivative-message message 
                                        :origin node 
                                        :type 'U-INFER 
                                        :support-set (os-union (:support-set message) @(:support node))
                                        :true? true
                                        :taskid (:taskid message))]
        
        (when showproofs 
          (doseq [u @(:u-channels node)]
            (when (build/pass-message? u der-msg)
              (send screenprinter (fn [_] (println "Since " node ", I derived: " 
                                                   (build/apply-sub-to-term (:destination u) (:subst message)) 
                                                   " by numericalentailment-elimination"))))))
        
        (apply conj {} (doall (map #(vector % der-msg) @(:u-channels node))))))
    ;; :min > 1
    (let [new-ruis (get-rule-use-info (:msgs node) message)
          match-msg (some #(when (>= (:pos %) (:min node))
                              %)
                           new-ruis)]
      (when match-msg 
        (cancel-infer node nil (:taskid message))
        (let [der-msg (derivative-message match-msg 
                                          :origin node 
                                          :type 'U-INFER 
                                          :support-set (os-union (:support-set message) @(:support node))
                                          :true? true 
                                          :fwd-infer? (:fwd-infer? message)
                                          :taskid (:taskid message))]
          
          (when showproofs 
            (doseq [u @(:u-channels node)]
              (when (build/pass-message? u der-msg)
                (send screenprinter (fn [_] (println "Since " node ", I derived: " 
                                                     (build/apply-sub-to-term (:destination u) (:subst message)) 
                                                     " by numericalentailment-elimination"))))))
        
          (apply conj {} (doall (map #(vector % der-msg) @(:u-channels node)))))))))

(defn numericalentailment-introduction
  ""
  [message node])

(defn conjunction-elimination
  "Since the and is true, send a U-INFER message to each of the
   consequents."
  [message node]
  (let [dermsg (derivative-message message 
                                   :origin node
                                   :support-set @(:support node) ;(conj (:support-set message) node)
                                   :type 'U-INFER)
        uch @(:u-channels node)]
    (when debug (send screenprinter (fn [_]  (println "ELIMINATING"))))
    (when showproofs
      (doseq [u uch]
        (when (or (build/valve-open? u) (build/pass-message? u dermsg))
          (send screenprinter (fn [_] (println "Since " node ", I derived: " (build/apply-sub-to-term (:destination u) (:subst dermsg)) " by conjunction-elimination"))))))
    (zipmap uch (repeat (count uch) dermsg))))

(defn conjunction-introduction
  "We are in an unasserted 'and' node, and would like to know if we now
   can say it is true based on message."
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        der-rui-t (some #(when (= (:pos %) (count @(:u-channels node))) %) new-ruis)
        der-rui-f (some #(when (pos? (:neg %)) %)new-ruis)
        dermsg-t (derivative-message (imessage-from-ymessage message node)
                                     :support-set der-rui-t)
        dermsg-f (derivative-message message 
                                   :origin node
                                   :support-set (:support-set der-rui-f)
                                   :type 'I-INFER
                                   :true? false)
        ich @(:i-channels node)]
    (cond
      der-rui-t (do 
                  (when showproofs
                    (send screenprinter (fn [_] (println "I derived: " node " by conjunction-introduction"))))
                  [true (:support-set der-rui-t) (zipmap ich (repeat (count ich) dermsg-t))])
      der-rui-f (do
                  (when showproofs
                    (send screenprinter (fn [_] (println "I derived: ~" node " by conjunction-introduction"))))
                  [false (:support-set der-rui-f) (zipmap ich (repeat (count ich) dermsg-f))])
      :else nil)))

(defn andor-elimination
  "Since the andor is true, we may have enough information to do elimination
   on it. "
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        totparam (csneps/totparam node)
        pos-match (some #(when (= (:pos %) (:max node))
                           %) 
                        new-ruis)
        neg-match (some #(when (= (- totparam (:neg %)) (:min node)) %) new-ruis)]
    (when debug (send screenprinter (fn [_]  (println "NRUI" new-ruis))))
    (or 
      (when pos-match
        (let [der-msg (derivative-message pos-match 
                                          :origin node 
                                          :type 'U-INFER 
                                          :true? false 
                                          :support-set (os-union (:support-set pos-match) @(:support node))
                                          :fwd-infer? (:fwd-infer? message)
                                          :taskid (:taskid message))]

          (when showproofs 
            (doseq [u @(:u-channels node)]
              (when (and (not ((:flaggedns pos-match) (:destination u)))
                         (not (negated? (:destination u)))
                         (or (build/valve-open? u) (build/pass-message? u der-msg)))
                (send screenprinter (fn [_] (println "Since " node ", I derived: ~" (build/apply-sub-to-term (:destination u) (:subst pos-match)) " by andor-elimination"))))))
          
          (apply conj {} (doall (map #(when (and (not ((:flaggedns pos-match) (:destination %)))
                                                 (not (negated? (:destination %))))
                                        [% der-msg])
                                     @(:u-channels node))))))
      (when neg-match
        (let [der-msg (derivative-message neg-match 
                                          :origin node 
                                          :type 'U-INFER 
                                          :true? true 
                                          :support-set (os-union (:support-set neg-match) @(:support node))
                                          :fwd-infer? (:fwd-infer? message)
                                          :taskid (:taskid message))]
        
          (when showproofs 
              (doseq [u @(:u-channels node)]
                (when (and (nil? ((:flaggedns neg-match) (:destination u)))
                           (not (ct/asserted? (:destination u) (ct/currentContext)))
                           (or (build/valve-open? u) (build/pass-message? u der-msg)))
                  (send screenprinter (fn [_] (println "Since " node ", I derived: " (build/apply-sub-to-term (:destination u) (:subst neg-match)) " by andor-elimination"))))))
          
          (apply conj {} (doall (map #(when (and (nil? ((:flaggedns neg-match) (:destination %)))
                                                 (not (ct/asserted? (:destination %) (ct/currentContext))))
                                        [% der-msg])
                                     @(:u-channels node)))))))))

;     Inference can terminate
;        as soon as one of the following is determined to hold:
;        (1) The number of args asserted/derived is > max
;            or the number of negated args asserted/derived is > (tot-min)
;        (2) The number of args asserted/derived is >= min
;            and the number of negated args asserted/derived is >= (tot-max)
;     If type is andor, in case (1) the derivation fails,
;                       and in case (2) the derivation succeeds.
;     If type is thresh, in case (1) the derivation succeeds,
;                        and in case (2) the derivation fails.
(defn param2op-introduction
  "Check the RUIs to see if I have enough to be true."
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        ;merged-rui (when new-ruis (merge-messages new-ruis)) ;; If they contradict, we have other problems...
        totparam (csneps/totparam node)
        case1 (some #(when (or (> (:pos %) (:max node))
                               (> (:neg %) (- totparam (:min node)))) %) new-ruis)
        case2 (some #(when (and (>= (:pos %) (:min node))
                                (>= (:neg %) (- totparam (:max node)))) %) new-ruis)
        ich @(:i-channels node)]
    (cond
      (isa? (csneps/syntactic-type-of node) :csneps.core/Andor)
      (when case2
        (let [dermsg (derivative-message message 
                                         :origin node
                                         :support-set (:support-set case2)
                                         :type 'I-INFER
                                         :true? true)]
          (when showproofs 
            (doseq [i ich]
              (send screenprinter (fn [_] (println "Derived: " node " by param2op-introduction.")))))
          [true (:support-set case2) (zipmap ich (repeat (count ich) dermsg))]))
      (isa? (csneps/syntactic-type-of node) :csneps.core/Thresh)
      (when case1
        (let [dermsg (derivative-message message 
                                         :origin node
                                         :support-set (:support-set case1)
                                         :type 'I-INFER
                                         :true? true)]
          (when showproofs 
            (doseq [i ich]
              (send screenprinter (fn [_] (println "Derived: " node " by param2op-introduction.")))))
          [true (:support-set case1) (zipmap ich (repeat (count ich) dermsg))])))))
  
(defn thresh-elimination
  "Thesh is true if less than min or more than max."
  [message node]
  (let [new-ruis (get-rule-use-info (:msgs node) message)
        totparam (csneps/totparam node)
        ;; Case 1: There are >= minimum true. Therefore > maximum must be true. 
        ;; If there are totparam - max - 1 false, then we can make the rest true.
        more-than-min-true-match (some #(when (and (>= (:pos %) (:min node))
                                                   (= (:neg %) (- totparam (:max node) 1)))
                                          %)
                                       new-ruis)
        ;; Case 2: There are enough false cases such that maximum could not be true.
        ;; Therefore the minimum must be true. If enough of them are already, the rest
        ;; are false.
        less-than-max-true-match (some #(when (and (>= (:neg %) (- totparam (:max node)))
                                                   (= (:pos %) (dec (:min node))))
                                          %)
                                          new-ruis)]
    (or 
      (when more-than-min-true-match
        (let [der-msg (derivative-message more-than-min-true-match 
                                          :origin node 
                                          :type 'U-INFER 
                                          :true? true 
                                          :fwd-infer? (:fwd-infer? message)
                                          :taskid (:taskid message))]
        
          (when showproofs 
            (doseq [u @(:u-channels node)]
              (when-not ((:flaggedns more-than-min-true-match) (:destination u))
                (when (or (build/valve-open? u) (build/pass-message? u der-msg))
                  (send screenprinter (fn [_] (println "Since" node ", I derived:" 
                                                       (build/apply-sub-to-term (:destination u) (:subst more-than-min-true-match)) 
                                                       "by thresh-elimination")))))))
          
          (apply conj {} (doall (map #(when-not ((:flaggedns more-than-min-true-match) (:destination %))
                                        [% der-msg])
                                     @(:u-channels node))))))
      
      (when less-than-max-true-match
        (let [der-msg (derivative-message less-than-max-true-match 
                                          :origin node 
                                          :type 'U-INFER 
                                          :true? false 
                                          :fwd-infer? (:fwd-infer? message)
                                          :taskid (:taskid message))]
        
          (when showproofs 
            (doseq [u @(:u-channels node)]
              (when (and (nil? ((:flaggedns less-than-max-true-match) (:destination u)))
                         (or (build/valve-open? u) (build/pass-message? u der-msg)))
                 (send screenprinter (fn [_] (println "Since" node ", I derived: " 
                                                      (build/apply-sub-to-term (:destination u) (:subst less-than-max-true-match)) 
                                                      "by thresh-elimination"))))))
          
          (apply conj {} (doall (map #(when (nil? ((:flaggedns less-than-max-true-match) (:destination %)))
                                        [% der-msg])
                                     @(:u-channels node)))))))))

(defn whquestion-infer 
  [message node]
  (when debug (send screenprinter (fn [_]  (println "Deriving answer:" (:subst message)))))
  ;; We don't need RUIs - the received substitutions must be complete since they
  ;; passed unification! Instead, lets use cached-terms.
  (dosync (alter (:instances node) assoc (:origin message) (:subst message))))

(defn policy-instantiation
  ;; Really, a kind of elimination rule.
  [message node]
  (let [new-msgs (get-rule-use-info (:msgs node) message)
        inchct (count @(:ant-in-channels node)) ;; Should work even with sub-policies.
                                                ;; What about shared sub-policies though?
        inst-msgs (filter #(= (:pos %) inchct) new-msgs)
        new-msgs (map #(derivative-message % :origin node :type 'I-INFER :taskid (:taskid message)) inst-msgs) ;; using fwd-infer here is a bit of a hack.
        ich @(:i-channels node)]
    ;(when showproofs
    (when (seq new-msgs)
      (send screenprinter (fn [_] (println "Policy " node " satisfied."))))
    (apply concat 
           (for [nmsg new-msgs] 
             (zipmap ich (repeat (count ich) nmsg))))))

;;; A generic which has had all of the restrictions of its arbitraries
;;; met, should produce an instance (and resulting messages) for those
;;; restrictions. That instance has origin sets which indicate that it 
;;; could be inferred in two ways (in addition to itself being asserted): 
;;; 1) The generic is believed, and all of the restrictions are believed.
;;; 2) All of the restrictions are believed, and the instance is believed.
;;; These cases are not checked by the generic - that is the job of the
;;; origin set, which the resulting instance will have.
(defn generic-infer
  [message node]
  (when (or (csneps/arbitraryTerm? (:origin message))
            (build/generic-term? (:origin message)))
    (let [new-combined-messages (get-rule-use-info (:msgs node) message)
          total-parent-generics (count (filter #(or (csneps/arbitraryTerm? (:originator %))
                                                    (build/generic-term? (:originator %)))
                                               @(:ant-in-channels node)))
          rel-combined-messages (when new-combined-messages
                                  (filter #(= (:pos %) total-parent-generics) new-combined-messages))
          cqch (union @(:i-channels node) @(:g-channels node))]
      (doseq [rcm rel-combined-messages]
        (let [instance (build/apply-sub-to-term node (:subst rcm))
              inst-support (os-concat 
                             (os-union (:support-set rcm) @(:support node))
                             (os-union (:support-set rcm) @(:support instance)))
              der-msg (derivative-message rcm
                                         :origin node
                                         :support-set inst-support
                                         :true? true
                                         :type 'I-INFER
                                         :taskid (:taskid message))]
          (dosync (alter (:support instance) os-concat inst-support))
          (dosync (alter (:instances node) assoc instance (:subst rcm))) ;; idk if we need this.
          
          (doseq [ch cqch]
            (when (build/pass-message? ch der-msg)
              (send screenprinter (fn [_] (println "Since " node ", I derived: " instance " by generic-instantiation"))))
            (submit-to-channel ch der-msg)))))))
              
    
(defn arbitrary-instantiation
  [message node]
  (when debug (send screenprinter (fn [_]  (println "Arbitrary derivation:" (:subst message) "at" node))))
  (when (seq (:subst message)) ;; Lets ignore empty substitutions for now.
    (let [new-ruis (get-rule-use-info (:msgs node) message)
          resct (count @(:restriction-set node))
          der-rui-t (filter #(= (:pos %) resct) new-ruis)
          new-msgs (map #(derivative-message % :origin node :type 'I-INFER :taskid (:taskid message)) der-rui-t)
          gch @(:g-channels node)]
      (when debug (send screenprinter (fn [_]  (println "NEWRUIS:" new-ruis))))
      (when (seq der-rui-t)
        (send screenprinter (fn [_]  (println "NEWMESSAGE:" new-msgs)))
        (when debug (send screenprinter (fn [_]  (println "NEWMESSAGE:" new-msgs))))
        [true (for [msg new-msgs
                    ch gch]
                [ch msg])]))))

(defn instantiate-indefinite
  "Takes an indefinite and substitution, and returns a new
   indefinite which has as its restrictions:
   1) applies subst to every restriction of ind
   2) applies subst to every restriction of each of ind's deps
   and no dependencies."
  [ind subst]
  (let [new-rsts (loop [new-rsts (map #(build/apply-sub-to-term % subst) @(:restriction-set ind))
                        deps @(:dependencies ind)]
                   (if (empty? deps)
                     new-rsts
                     (recur 
                       (concat new-rsts (map #(build/apply-sub-to-term % subst) @(:restriction-set (first deps))))
                       (rest deps))))
        new-ind (csneps/new-indefinite :name (symbol (str "ind" (csneps/ind-counter)))
                                       :var-label (:var-label ind)
                                       :msgs (create-message-structure :csneps.core/Indefinite nil)
                                       :restriction-set (ref (set new-rsts)))]
    (csneps/inc-ind-counter)
    (csneps/instantiate-sem-type (:name new-ind) (csneps/semantic-type-of ind))
    new-ind))

(defn indefinite-instantiation
  [message node]
  ;; Only do something if this came from a dep of the ind.
  (when (and (@(:dependencies node) (:origin message))
             (seq (:subst message)))
    (let [new-ruis (get-rule-use-info (:msgs node) message)
          depct (count @(:dependencies node))
          der-rui-t (filter #(= (:pos %) depct) new-ruis)
          new-msgs (map #(derivative-message 
                           % 
                           :origin node 
                           :taskid (:taskid message)
                           :subst (assoc 
                                    (:subst message) 
                                    node 
                                    (instantiate-indefinite node (:subst message)))) der-rui-t)
          gch @(:g-channels node)]
      (when (seq der-rui-t)
        (when debug (send screenprinter (fn [_]  (println "NEWMESSAGE:" new-msgs))))
        [true (for [msg new-msgs
                    ch gch]
                [ch msg])]))))

(defn elimination-infer
  "Input is a message and node, output is a set of messages derived."
  [message node]
  (when debug (send screenprinter (fn [_]  (println "INFER: (elim) Inferring in:" node))))
  (case (csneps/type-of node)
    :csneps.core/CARule (policy-instantiation message node)
    :csneps.core/Negation (negation-elimination message node)
    :csneps.core/Conjunction (conjunction-elimination message node)
    (:csneps.core/Numericalentailment
     :csneps.core/Implication) (numericalentailment-elimination message node)
    (:csneps.core/Andor 
     :csneps.core/Disjunction 
     :csneps.core/Xor
     :csneps.core/Nand)  (andor-elimination message node)
    (:csneps.core/Thresh
     :csneps.core/Equivalence) (thresh-elimination message node)
    nil ;default
    ))

(defn introduction-infer
  ""
  [message node]
  (when debug (send screenprinter (fn [_]  (println "INFER: (intro) Inferring in:" node))))
  (case (csneps/type-of node)
    :csneps.core/Negation (negation-introduction message node)
    :csneps.core/Conjunction (conjunction-introduction message node)
    (:csneps.core/Numericalentailment
     :csneps.core/Implication) (numericalentailment-introduction message node)
    (:csneps.core/Andor 
     :csneps.core/Disjunction 
     :csneps.core/Xor
     :csneps.core/Nand
     :csneps.core/Thresh
     :csneps.core/Equivalence)  (param2op-introduction message node)
    (:csneps.core/Arbitrary) (arbitrary-instantiation message node)
    (:csneps.core/Indefinite) (indefinite-instantiation message node)
    nil))

(defn initiate-node-task
  [term message]
  (when debug (send screenprinter (fn [_]  (println "INFER: Begin node task on message: " message "at" term))))
  ;(send screenprinter (fn [_]  (println "INFER: Begin node task on message: " message "at" term)))
  
  (when (:fwd-infer? message)
    (dosync (alter (:future-fw-infer term) union (:invoke-set message))))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; ---- Elimination Rules ---- ;;
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  
  ;; If I'm already asserted, and I just received an I-INFER message,
  ;; I should attempt to eliminate myself.
  (when (and (not (isa? (csneps/syntactic-type-of term) :csneps.core/Variable))
             (ct/asserted? term (ct/currentContext))
             (= (:type message) 'I-INFER))
    (when-let [result (elimination-infer message term)]
      (when debug (send screenprinter (fn [_]  (println "INFER: Result Inferred " result))))
      (doseq [[ch msg] result] 
        (submit-to-channel ch msg))))
  
  ;; If I have just received a U-INFER message, I must make myself
  ;; either true or false according to the message, report that
  ;; new belief, and attempt elimination.
  (when (= (:type message) 'U-INFER)
    (send screenprinter (fn [_]  (println message term)))
    ;; Update origin sets of result appropriately.
    (let [result-term (if (:true? message)
                        (build/apply-sub-to-term term (:subst message))
                        (build/build (list 'not (build/apply-sub-to-term term (:subst message))) :Proposition {}))]
      (dosync (alter (:support result-term) os-concat (:support-set message)))
      ;; When this hasn't already been derived otherwise in this ct, let the user know.
      (when (or (and (not (ct/asserted? result-term (ct/currentContext))) print-intermediate-results)
                (:fwd-infer? message))
        (send screenprinter (fn [_]  (println "> " result-term))))
      ;; Send messages onward that this has been derived.
      (let [imsg (derivative-message message
                                   :origin term
                                   :support-set @(:support result-term)
                                   :type 'I-INFER)]
        (doseq [cqch @(:i-channels term)] (submit-to-channel cqch imsg)))
      ;; If I've now derived the goal of a future bw-infer process, it can be cancelled.
      (when (@(:future-bw-infer term) result-term)
          (cancel-infer-of term)))
      (when (:true? message)
        ;; Apply elimination rules and report results
        (when-let [result (elimination-infer message term)]
          (when debug (send screenprinter (fn [_]  (println "INFER: Result Inferred " result))))
          (doseq [[ch msg] result] 
            (submit-to-channel ch msg)))))
    
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; ---- Introduction Rules ---- ;;
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  
  (cond 
    ;; Actions should execute their primative action.
    (and
      (= (:type message) 'I-INFER)
      (= (csneps/semantic-type-of term) :Action))
    ((csneps/primaction term) (:subst message))
    ;; AnalyticGeneric terms need to just forward the messages
    ;; on towards the variable term.
    (and
      (= (:type message) 'I-INFER)
      (csneps/genericAnalyticTerm? term))
    (let [imsg (derivative-message message :origin term)]
      (when debug (send screenprinter (fn [_]  (println "INFER: AnalyticGeneric" term "forwarding message."))))
      (doseq [cqch @(:i-channels term)] 
        (submit-to-channel cqch imsg)))
    ;; "Introduction" of a WhQuestion is really just collecting answers.
    (and
      (= (:type message) 'I-INFER)
      (= (csneps/semantic-type-of term) :WhQuestion))
    (whquestion-infer message term)
    ;; "Introduction of a Generic is the collection of instances, 
    ;;   assertion of the instance, and forwarding the substitution
    ;;   through i-channels. Unlike wh-question, incoming message
    ;;   may be I-INFER or U-INFER. A message with an empty substitution
    ;;   means the generic has been inferred, and should just be treated
    ;;   as usual.
    (and 
      (csneps/genericTerm? term)
      (seq (:subst message)))
    (generic-infer message term)
    ;; Arbs
    (and (csneps/arbitraryTerm? term)
         (= (:type message) 'I-INFER))
    (if-let [[true? result] (introduction-infer message term)]
      (when result 
        (doseq [[ch msg] result] 
          (submit-to-channel ch msg)))
      ;; When introduction fails, try backward-in-forward reasoning. 
      (when (:fwd-infer? message)
        ;; TODO: Not quite finished, I think.
        (backward-infer term #{term} nil)))
    ;; Normal introduction for derivation.
    (and 
      (not (ct/asserted? term (ct/currentContext)))
      (= (:type message) 'I-INFER))
    (if-let [[true? support result] (introduction-infer message term)]
      (when result 
        (when debug (send screenprinter (fn [_]  (println "INFER: Result Inferred " result "," support "," true?))))
        (if true?
          (do 
            (dosync (alter (:support term) union support))
            (when print-intermediate-results (println "> " term)))
          (let [neg (build/variable-parse-and-build (list 'not term) :Propositional)]
            (dosync (alter (:support neg) union support))
            (when print-intermediate-results (println "> " neg))))
        
        
;        (if true?
;          (if print-intermediate-results
;            (send screenprinter (fn [_]  (println "> " (build/assert term (ct/currentContext)))))
;            (build/assert term (ct/currentContext)))
;          (if print-intermediate-results
;            (send screenprinter (fn [_] (println "> " (build/assert (list 'not term) (ct/currentContext)))))
;            (build/assert (list 'not term) (ct/currentContext))))
        (doseq [[ch msg] result] 
          (submit-to-channel ch msg)))
      ;; When introduction fails, try backward-in-forward reasoning. 
      (when (:fwd-infer? message)
        ;; TODO: Not quite finished, I think.
        (backward-infer term #{term} nil))))
  ;(send screenprinter (fn [_]  (println "dec-nt" (:taskid message))))
  (when (:taskid message) (.decrement (@infer-status (:taskid message)))))
          
  
;;; Message Handling ;;;

(defn create-message-structure
  "Create the RUI structure for a rule node. For now,
   we always create an empty set. In the future, we'll create P-Trees
   and S-Indexes as necessary."
  [syntype dcs]
  (make-linear-msg-set))

(build/fix-fn-defs submit-to-channel submit-assertion-to-channels new-message create-message-structure backward-infer forward-infer)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; User-oriented functions ;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn backward-infer-derivable [term context]
  (let [taskid (gensym "task")]
    (backward-infer term taskid)
    (.await (@infer-status taskid))
    (if (ct/asserted? term context)
      #{term}
      #{})))

(defn backward-infer-answer
  "Used for answering WhQuestions"
  [ques context]
  (let [taskid (gensym "task")]
    (backward-infer ques taskid)
    (.await (@infer-status taskid))
    @(:instances ques)))

(defn cancel-infer-of [term]
  (let [term (if (seq? term)
               (build/build term :Proposition #{})
               (csneps/get-term term))]
    (when-not term (error "Term not found: " term))
    (cancel-infer term term)))

(defn cancel-infer-from [term]
  (let [term (if (seq? term)
               (build/build term :Proposition #{})
               (csneps/get-term term))]
    (when-not term (error "Term not found: " term))
    (cancel-forward-infer term term)))

(defn cancel-focused-infer []
  (doseq [t (vals @csneps/TERMS)]
    (dosync 
      (alter (:future-fw-infer t) empty)
      (alter (:future-bw-infer t) empty))))
  
;;;;;;;;;;;;;;;;;;;;;;;
;;; Debug Functions ;;;
;;;;;;;;;;;;;;;;;;;;;;;

(defn- print-valve [ch]
  (let [selectors-this-ct (filter #(subset? @(:hyps (second %)) @(:hyps (ct/currentContext))) @(:valve-selectors ch))]
    (cond
      @(:valve-open ch) "-"
      (seq selectors-this-ct) (str "~" (print-str (map #(first %) selectors-this-ct)) "~")
      :else "/")))

(defn ig-status []
  (doseq [x @csneps.core/TERMS]
    (doseq [i @(:i-channels (second x))]
      (println (:originator i) "-I-" (count @(:waiting-msgs i)) (print-valve i) "->" (:destination i)))
    (doseq [u @(:u-channels (second x))]
      (println (:originator u) "-U-" (count @(:waiting-msgs u)) (print-valve u) "->" (:destination u)))
    (doseq [g @(:g-channels (second x))]
      (println (:originator g) "-G-" (count @(:waiting-msgs g)) (print-valve g) "->" (:destination g)))))

