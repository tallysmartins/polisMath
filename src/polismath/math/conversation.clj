;; Copyright (C) 2012-present, Polis Technology Inc. This program is free software: you can redistribute it and/or  modify it under the terms of the GNU Affero General Public License, version 3, as published by the Free Software Foundation. This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details. You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.

(ns polismath.math.conversation
  (:refer-clojure :exclude [* -  + == /])
  (:require [polismath.utils :as utils]
            [polismath.math.pca :as pca]
            [polismath.math.clusters :as clusters]
            [polismath.math.repness :as repness]
            [polismath.math.named-matrix :as nm]
            [clojure.core.matrix :as matrix]
            [clojure.tools.reader.edn :as edn]
            [clojure.tools.trace :as tr]
            [clojure.math.numeric-tower :as math]
            [clojure.core.matrix :as matrix]
            [clojure.core.matrix.operators :refer :all]
            [plumbing.core :as plmb]
            [plumbing.graph :as graph]
            [monger.collection :as mc]
            [bigml.sampling.simple :as sampling]
            ;[alex-and-georges.debug-repl :as dbr]
            [clojure.tools.logging :as log]))


(defn new-conv []
  "Minimal structure upon which to perform conversation updates"
  {:rating-mat (nm/named-matrix)})


(defn choose-group-k [base-clusters]
  (let [len (count base-clusters)]
    (cond
      (< len 99) 3
      :else 4)))


(defn agg-bucket-votes-for-tid [bid-to-pid rating-mat filter-cond tid]
  (if-let [idx (nm/index (nm/get-col-index rating-mat) tid)]
    ; If we have data for the given comment...
    (let [pid-to-row (zipmap (nm/rownames rating-mat) (range (count (nm/rownames rating-mat))))
          person-rows (nm/get-matrix rating-mat)]
      (mapv ; for each bucket
        (fn [pids]
          (->> pids
            ; get votes for the tid from each ptpt in group
            (map (fn [pid] (get (get person-rows (pid-to-row pid)) idx)))
            ; filter votes you don't want to count
            (filter filter-cond)
            ; count
            (count)))
        bid-to-pid))
    ; Otherwise return an empty vector
    []))


; conv - should have
;   * last-updated
;   * pca
;     * center
;     * pcs
;   * base-clusters
;   * group-clusters
;   * repness
; [hidden]
;   * rating matrix
;   * base-cluster-full [ptpt to base mpa]

(def base-conv-update-graph
  "Base of all conversation updates; handles default  opts and does named matrix updating"
  {:opts'       (plmb/fnk [opts]
                  "Merge in opts with the following defaults"
                  ;; TODO Answer and resolve this question:
                  ;; QUESTION Does it make senes to have the defaults here or in the config.edn or both duplicated?
                  (merge {:n-comps 2 ; does our code even generalize to others?
                          :pca-iters 10
                          :base-iters 10
                          :base-k 50
                          :max-k 5
                          :group-iters 10
                          ;; These three in particular we should be able to tune quickly
                          :max-ptpts 80000
                          :max-cmts 800
                          :group-k-buffer 4}
                    opts))

   :zid         (plmb/fnk [conv votes]
                  (or (:zid conv)
                      (:zid (first votes))))

   :last-vote-timestamp
                (plmb/fnk [conv votes]
                  (apply max
                         (or (:last-vote-timestamp conv) 0)
                         (map :created votes)))

   :customs     (plmb/fnk [conv votes opts']
                  ; Decides whether there is room for new ptpts/cmts, and which votes should be allowed in
                  ; based on which ptpts/cmts have already been seen. This is a simple prevention against
                  ; conversations that get too large. Returns {:pids :tids :votes}, where the first two kv
                  ; pairs are persisted and built upon and persisted; :votes is used downstream and tossed
                  (reduce
                    (fn [{:keys [pids tids] :as result}
                         {:keys [pid  tid]  :as vote}]
                      (let [pid-room (< (count pids) (:max-ptpts opts'))
                            tid-room (< (count tids) (:max-cmts opts'))
                            pid-in   (pids pid)
                            tid-in   (tids tid)]
                        (if (and (or pid-room pid-in)
                                 (or tid-room tid-in))
                          (assoc result
                                 :pids  (conj (:pids result)  pid)
                                 :tids  (conj (:tids result)  tid)
                                 :votes (conj (:votes result) vote))
                          result)))
                    ; Customs collection off which to base reduction; note that votes get cleared out
                    (assoc (or (:customs conv) {:pids #{} :tids #{}})
                      :votes [])
                    votes))

   :keep-votes  (plmb/fnk [customs]
                  (:votes customs))

   :rating-mat  (plmb/fnk [conv keep-votes]
                  (nm/update-nmat (:rating-mat conv)
                                  (map (fn [v] (vector (:pid v) (:tid v) (:vote v))) keep-votes)))

   :n           (plmb/fnk [rating-mat]
                  (count (nm/rownames rating-mat)))

   :n-cmts      (plmb/fnk [rating-mat]
                  (count (nm/colnames rating-mat)))

   :user-vote-counts
                (plmb/fnk [rating-mat]
                  ; For deciding in-conv below; filter ptpts based on how much they've voted
                  (->> (mapv
                         (fn [rowname row] [rowname (count (remove nil? row))])
                         (nm/rownames rating-mat)
                         (nm/get-matrix rating-mat))
                       (into {})))

   :in-conv     (plmb/fnk [conv user-vote-counts n-cmts]
                  ; This keeps track of which ptpts are in the conversation (to be considered
                  ; for base-clustering) based on home many votes they have. Once a ptpt is in,
                  ; they will remain in.
                  (as-> (or (:in-conv conv) #{}) in-conv
                    ; Start with whatever you have, and join it with anything that meets the criteria
                    (into in-conv
                      (map first
                        (filter
                          (fn [[rowname cnt]]
                            ; We only start looking at a ptpt if they have rated either all the comments or at
                            ; least 7 if there are more than 7
                            (>= cnt (min 7 n-cmts)))
                          user-vote-counts)))
                    ; If you are left with fewer than 15 participants, take the top most contributing
                    ; participants
                    (let [greedy-n 15
                          n-in-conv (count in-conv)]
                      (if (< n-in-conv greedy-n)
                        (->> user-vote-counts
                          (remove
                            (fn [[k v]] (in-conv k)))
                          (sort-by (comp - second))
                          (map first)
                          (take (- greedy-n n-in-conv))
                          (into in-conv))
                        in-conv))))})
  ; End of base conv update



(defn max-k-fn
  [data max-max-k]
  (min
    max-max-k
    (+ 2
       (int (/ (count (nm/rownames data)) 12)))))


(def small-conv-update-graph
  "For computing small conversation updates (those without need for base clustering)"
  (merge
     base-conv-update-graph
     {:mat (plmb/fnk [rating-mat]
             ; swap nils for zeros - most things need the 0s, but repness needs the nils
             (mapv (fn [row] (map #(if (nil? %) 0 %) row))
               (nm/get-matrix rating-mat)))

      :pca (plmb/fnk [conv mat opts']
             (pca/wrapped-pca mat
                              (:n-comps opts')
                              :start-vectors (get-in conv [:pca :comps])
                              :iters (:pca-iters opts')))

      :proj (plmb/fnk [rating-mat pca]
              (pca/sparsity-aware-project-ptpts (nm/get-matrix rating-mat) pca))

      ;; QUESTION Just have proj return an nmat?
      :proj-nmat
      (plmb/fnk [rating-mat proj]
        (nm/named-matrix (nm/rownames rating-mat) ["x" "y"] proj))

      :base-clusters
            (plmb/fnk [conv proj-nmat in-conv opts']
              (let [in-conv-mat (nm/rowname-subset proj-nmat in-conv)]
                (sort-by :id
                  (clusters/kmeans in-conv-mat
                    (:base-k opts')
                    :last-clusters (:base-clusters conv)
                    :cluster-iters (:base-iters opts')))))

      :base-clusters-proj
            (plmb/fnk [base-clusters]
              (clusters/xy-clusters-to-nmat2 base-clusters))
      
      :bucket-dists
            (plmb/fnk [base-clusters-proj]
              (clusters/named-dist-matrix base-clusters-proj))

      :base-clusters-weights
            (plmb/fnk [base-clusters]
              (into {}
                    (map
                      (fn [clst]
                        [(:id clst) (count (:members clst))])
                      base-clusters)))

      ; Compute group-clusters for multiple k values
      :group-clusterings
            (plmb/fnk [conv base-clusters-weights base-clusters-proj opts']
                (plmb/map-from-keys
                  (fn [k]
                    (sort-by :id
                      (clusters/kmeans base-clusters-proj k
                        :last-clusters
                          ; A little pedantic here in case no clustering yet for this k
                          (let [last-clusterings (:group-clusterings conv)]
                            (if last-clusterings
                              (last-clusterings k)
                              last-clusterings))
                        :cluster-iters (:group-iters opts')
                        :weights base-clusters-weights)))
                  (range 2 (inc (max-k-fn base-clusters-proj (:max-k opts'))))))

      ; Compute silhouette values for the various clusterings
      :group-clusterings-silhouettes
            (plmb/fnk [group-clusterings bucket-dists]
              (plmb/map-vals (partial clusters/silhouette bucket-dists) group-clusterings))

      ; This smooths changes in cluster counts (K-vals) by remembering what the last K was, and only changing
      ; after (:group-k-buffer opts') many times on a new K value
      :group-k-smoother
            (plmb/fnk
              [conv group-clusterings group-clusterings-silhouettes opts']
              (let [{:keys [last-k last-k-count smoothed-k] :or {last-k-count 0}}
                    (:group-k-smoother conv)
                    count-buffer (:group-k-buffer opts')
                                 ; Find best K value for current data, given silhouette
                    this-k       (apply max-key group-clusterings-silhouettes (keys group-clusterings))
                                 ; If this and last K values are the same, increment counter
                    same         (if last-k (= this-k last-k) false)
                    this-k-count (if same (+ last-k-count 1) 1)
                                 ; if seen > buffer many times, switch, OW, take last smoothed
                    smoothed-k   (if (>= this-k-count count-buffer)
                                   this-k
                                   (if smoothed-k smoothed-k this-k))]
                {:last-k       this-k
                 :last-k-count this-k-count
                 :smoothed-k   smoothed-k}))

      ; Pick the cluster corresponding to smoothed K value from group-k-smoother
      :group-clusters
            (plmb/fnk [group-clusterings group-k-smoother]
              (get group-clusterings
                (:smoothed-k group-k-smoother)))

      ;; a vector of member vectors, sorted by base cluster id
      :bid-to-pid (plmb/fnk [base-clusters]
                    (mapv :members (sort-by :id base-clusters)))

      ;; returns {tid {
      ;;           :agree [0 4 2 0 6 0 0 1]
      ;;           :disagree [3 0 0 1 0 23 0 ]}
      ;; where the indices in the arrays correspond NOT directly to the bid, but to the index of the
      ;; corresponding bid in a hypothetically sorted list of the base cluster ids
      :votes-base (plmb/fnk [bid-to-pid rating-mat]
                    (->> rating-mat
                      nm/colnames
                      (plmb/map-from-keys
                        (fn [tid]
                          {:A (agg-bucket-votes-for-tid bid-to-pid rating-mat utils/agree? tid)
                           :D (agg-bucket-votes-for-tid bid-to-pid rating-mat utils/disagree? tid)
                           :S (agg-bucket-votes-for-tid bid-to-pid rating-mat number? tid)}))))

      ; {tid {gid {A _ D _ S}}}
      :group-votes (plmb/fnk [group-clusters base-clusters votes-base]
                     (let [bid-to-index (zipmap (map :id base-clusters)
                                                (range))]
                       (into {}
                         (map
                           (fn [{:keys [id members] :as group-cluster}]
                             (letfn [(count-fn [tid vote]
                                       (->>
                                         members
                                         (mapv bid-to-index)
                                         (mapv #(((votes-base tid) vote) %))
                                         (apply +)))]
                               [id
                                {:n-members (let [bids (set members)]
                                              ; Add up the count of members in each base-cluster in this group-cluster
                                              (->> base-clusters
                                                   (filter #(bids (:id %)))
                                                   (map #(count (:members %)))
                                                   (reduce + 0)))
                                 :votes (plmb/map-from-keys
                                          (fn [tid]
                                            {:A (count-fn tid :A)
                                             :D (count-fn tid :D)
                                             :S (count-fn tid :S)})
                                          (keys votes-base))}]))
                           group-clusters))))


      :repness    (plmb/fnk [conv rating-mat group-clusters base-clusters]
                    (-> (repness/conv-repness rating-mat group-clusters base-clusters)
                        (repness/select-rep-comments (:mod-out conv))))

      :ptpt-stats
      (plmb/fnk [group-clusters base-clusters proj-nmat user-vote-counts]
        (repness/participant-stats group-clusters base-clusters proj-nmat user-vote-counts))


      :consensus  (plmb/fnk [conv rating-mat]
                    (-> (repness/consensus-stats rating-mat)
                        (repness/select-consensus-comments (:mod-out conv))))}))

     ; End of large-update



(defn partial-pca
  "This function takes in the rating matrix, the current pca and a set of row indices and
  computes the partial pca off of those, returning a lambda that will take the latest PCA 
  and make the update on that in case there have been other mini batch updates since started"
  [mat pca indices & {:keys [n-comps iters learning-rate]
                      :or {n-comps 2 iters 10 learning-rate 0.01}}]
  (let [rating-subset (utils/filter-by-index mat indices)
        part-pca (pca/powerit-pca rating-subset n-comps
                     :start-vectors (:comps pca)
                     :iters iters)
        forget-rate (- 1 learning-rate)
        learn (fn [old-val new-val]
                (let [old-val (matrix/join old-val (repeat (- (matrix/dimension-count new-val 0)
                                                              (matrix/dimension-count old-val 0)) 0))]
                  (+ (* forget-rate old-val) (* learning-rate new-val))))]
    (fn [pca']
      ; Actual updater lambda
      {:center (learn (:center pca') (:center part-pca))
       :comps  (mapv #(learn %1 %2) (:comps pca') (:comps part-pca))})))


(defn sample-size-fn
  "Return a function which decides how many ptpts to sample for mini-batch updates; the input
  parameters correspond to a line of sample sizes to interpolate. Beyon the bounds of these
  points, the sample sizes flatten out so all sample sizes lie in [start-y stop-y]"
  [start-y stop-y start-x stop-x]
  (let [slope (/ (- stop-y start-y) (- stop-x start-x))
        start (- (* slope start-x) start-y)]
    (fn [size]
      (max 
        (long (min (+ start (* slope size)) stop-y))
        start-y))))
; For now... Will want this constructed with opts eventually XXX
(def sample-size (sample-size-fn 100 1500 1500 150000))


(def large-conv-update-graph
  "Same as small-conv-update-graph, but uses mini-batch PCA"
  (merge small-conv-update-graph
    {:pca (plmb/fnk [conv mat opts']
            (let [n-ptpts (matrix/dimension-count mat 0)
                  sample-size (sample-size n-ptpts)]
              (loop [pca (:pca conv) iter (:pca-iters opts')]
                (let [rand-indices (take sample-size (sampling/sample (range n-ptpts) :generator :twister))
                      pca          ((partial-pca mat pca rand-indices) pca)]
                  (if (= iter 0)
                    (recur pca (dec iter))
                    pca)))))}))


(def eager-profiled-compiler
  (comp graph/eager-compile (partial graph/profiled :profile-data)))

(def small-conv-update (eager-profiled-compiler small-conv-update-graph))
(def large-conv-update (eager-profiled-compiler large-conv-update-graph))


(defn conv-update
  "This function dispatches to either small- or large-conv-update, depending on the number
  of participants (as decided by call to sample-size-fn)."
  ([conv votes]
   (conv-update conv votes {}))
  ;; TODO Need to pass through these options from all the various places where we call this function...
  ;; XXX Also need to set the max globally and by conversation for plan throttling
  ([conv votes {:keys [med-cutoff large-cutoff]
                :or {med-cutoff 100 large-cutoff 10000}
                :as opts}]
   (let [zid     (or (:zid conv) (:zid (first votes)))
         ptpts   (nm/rownames (:rating-mat conv))
         n-ptpts (count (distinct (into ptpts (map :pid votes))))
         n-cmts  (count (distinct (into (nm/rownames (:rating-mat conv)) (map :tid votes))))]
     ; This is a safety measure so we can call conv-update on an empty conversation after adding mod-out
     (if (and (= 0 n-ptpts n-cmts)
              (empty? votes))
       conv
       (do
         (log/info (str "============================================================================="))
         (log/info (str "Starting conv-update for zid " zid ": N-ptpts=" n-ptpts ", n-cmts=" n-cmts ", VotesCount=" (count votes)))
         (log/info (str "conv = " conv "options = " opts))
         (->
           ; dispatch to the appropriate function
           ((cond
              (> n-ptpts large-cutoff)  large-conv-update
              :else                     small-conv-update)
            {:conv conv :votes votes :opts opts})
           ;; This seems hackish... XXX
           ; Remove the :votes key from customs; not needed for persistence
           (assoc-in [:customs :votes] [])
           (dissoc :keep-votes)))))))


(defn mod-update
  "Take a conversation record and a seq of moderation data and updates the conversation's mod-out attr"
  [conv mods]
  ; Hmm... really need to make sure that if someone quickly mods and unmods on a long running comp, we
  ; consider order or :updated XXX
  (try
    (let [mod-sep (fn [mod] (->> mods
                                 (filter (comp #{mod} :mod))
                                 (map :tid)
                                 (set)))
          mod-out (mod-sep -1)
          mod-in  (mod-sep 1)]
      (-> conv
          (update-in [:mod-out]
                     (plmb/fn->
                       (set)
                       (clojure.set/union mod-out)
                       (clojure.set/difference mod-in)
                       (set)))
          (update-in [:last-mod-timestamp]
                     (fn [last-mod-timestamp]
                       (apply max (or last-mod-timestamp 0) (map :modified mods))))))
    (catch Exception e
      (log/error "Problem running mod-update with mod-out:" (:mod-out conv) "and mods:" mods ":" e)
      (.printStackTrace e)
      conv)))


;; Creating some overrides for how core.matrix instances are printed, so that we can read them back via our
;; edn reader

(def ^:private ipv-print-method (get (methods print-method) clojure.lang.IPersistentVector))

(defmethod print-method mikera.matrixx.Matrix
  [o ^java.io.Writer w]
  (.write w "#mikera.matrixx.Matrix ")
  (ipv-print-method
    (mapv #(into [] %) o)
    w))

(defmethod print-method mikera.vectorz.Vector
  [o ^java.io.Writer w]
  (.write w "#mikera.vectorz.Vector ")
  (ipv-print-method o w))

(defmethod print-method mikera.arrayz.NDArray
  [o ^java.io.Writer w]
  (.write w "#mikera.arrayz.NDArray ")
  (ipv-print-method o w))


; a reader that uses these custom printing formats
(defn read-vectorz-edn [text]
  (edn/read-string
    {:readers {'mikera.vectorz.Vector matrix/matrix
               'mikera.arrayz.NDArray matrix/matrix
               'mikera.matrixx.Matrix matrix/matrix
               'polismath.named-matrix.NamedMatrix nm/named-matrix-reader}}
    text))


(defn conv-update-dump
  "Write out conversation state, votes, computational opts and error for debugging purposes."
  [conv votes & [opts error]]
  (spit (str "errorconv." (. System (nanoTime)) ".edn")
    (prn-str
      {:conv  (into {}
                (assoc-in conv [:pca :center] (matrix/matrix (into [] (:center (:pca conv))))))
       :votes votes
       :opts  opts
       :error (str error)})))


(defn load-conv-update [filename]
  (read-vectorz-edn (slurp filename)))


:ok

