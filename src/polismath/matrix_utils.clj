(ns polismath.matrix-utils


(defrecord RatingMatrix [ptpts cmts matrix])


; This shouldn't have to take an existing rating-matrix for fresh data
(defn update-rating-matrix [rating-matrix new-reactions]
  "This is a recursive function for taking new reactions and inserting them into the rating matrix."
  (let [rating-matrix (or rating-matrix (->RatingMatrix [] [] []))]
    ; be careful about first v last wrt spout order; want to make sure the last thing in overrides
    ; anything earlier... If there are new reaactions, proceed
    (if-let [[ptpt cmt rating] (first new-reactions)]
      ; Long series of let assignments leading up to the recursive call
      (let [{:keys [ptpts cmts matrix]} rating-matrix
            [ptpt-index cmt-index] (map 
                                     #(.indexOf %1 %2)
                                     [ptpts cmts] [ptpt cmt])
            [ptpts cmts] (map (fn [xs x i] (if (= -1 i) (conj xs x) xs))
                              [ptpts cmts] [ptpt cmt] [ptpt-index cmt-index])
            ; If necessary, insert new columns and/or rows of zeros
            matrix (if (= -1 cmt-index) (mapv #(conj % 0) matrix) matrix)
            matrix (if (> (count ptpts) (count matrix)) (conj matrix (into [] (repeat (count cmts) 0))) matrix)
            ; Get the indices of the matrix position to be updated (note that .indexOf above gives
            ; us -1 if the item is not in the collection)
            indices (mapv (fn [xs i] (if (= -1 i) (- (count xs) 1) i))
                          [ptpts cmts] [ptpt-index cmt-index])
            matrix (assoc-in matrix indices rating)]
        (recur (assoc rating-matrix :ptpts ptpts :cmts cmts :matrix matrix) (rest new-reactions)))
      ; else nothing to add; return matrix as is
      rating-matrix)))


(defn row-subset [rating-matrix row-indices]
  (->RatingMatrix
    (filter-by-index (:ptpts rating-matrix) row-indices)
    (:cmts rating-matrix)
    (filter-by-index (:matrix rating-matrix) row-indices)))


(defn rowname-subset [rating-matrix row-names]
  (->> row-names
    (map #(.indexOf (:ptpts rating-matrix) %))
    (row-subset rating-matrix)))


(defn inv-rowname-subset [rating-matrix row-names]
  (row-subset rating-matrix
    (remove (set (map #(.indexOf (:ptpts rating-matrix) %) row-names))
      (range (count (:ptpts rating-matrix))))))


