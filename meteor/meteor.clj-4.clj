;; The Computer Language Benchmarks Game
;; http://shootout.alioth.debian.org/
;;
;; contributed by Andy Fingerhut
;; Based upon ideas from GCC version by Christian Vosteen (good comments!)

(ns meteor
  (:gen-class)
  (:require [clojure.string :as str])
  (:require [clojure.pprint :as pprint]))

(set! *warn-on-reflection* true)


;; The board is a 50 cell hexagonal pattern.  For    . . . . .
;; maximum speed the board will be implemented as     . . . . .
;; 50 bits, which will fit into a 64-bit long int.   . . . . .
;;                                                    . . . . .
;;                                                   . . . . .
;; I will represent 0's as empty cells and 1's        . . . . .
;; as full cells.                                    . . . . .
;;                                                    . . . . .
;;                                                   . . . . .
;;                                                    . . . . .

;; Here are the numerical indices for each position on the board, also
;; later called board indices.
;;
;;  0   1   2   3   4
;;    5   6   7   8   9
;; 10  11  12  13  14
;;   15  16  17  18  19
;; 20  21  22  23  24
;;   25  26  27  28  29
;; 30  31  32  33  34
;;   35  36  37  38  39
;; 40  41  42  43  44
;;   45  46  47  48  49


;; The puzzle pieces are each specified as a tree.  Every piece
;; consists of 5 'nodes' that occupy one board index.  Each piece has
;; a root node numbered 0, and every other node (numbered 1 through 4)
;; specifies its parent node, and the direction to take to get from
;; the parent to the child (in a default orientation).

;; In the pictures below, pieces are shown graphically in their
;; default orientation, with nodes numbered 0 through 4.

;;   Piece 0   Piece 1   Piece 2   Piece 3   Piece 4
;;                   
;;  0 1 2 3    0   3 4   0 1 2     0 1 2     0   3
;;         4    1 2           3       3       1 2
;;                           4         4         4
;;
(def piece-defs [ [[0 :E ] [1 :E ] [2 :E ] [3 :SE]]  ; piece 0
;;                 ^^^^^^^ node 1 is :E of its parent node 0
;;                         ^^^^^^^ node 2 is :E of its parent node 1
                  [[0 :SE] [1 :E ] [2 :NE] [3 :E ]]  ; piece 1
                  [[0 :E ] [1 :E ] [2 :SE] [3 :SW]]  ; piece 2
                  [[0 :E ] [1 :E ] [2 :SW] [3 :SE]]  ; piece 3
                  [[0 :SE] [1 :E ] [2 :NE] [2 :SE]]  ; piece 4
;;                                          ^ node 4's parent is 2, not 3
;;
;;   Piece 5   Piece 6   Piece 7   Piece 8   Piece 9
;;
;;    0 1 2     0 1       0 1     0 1        0 1 2 3
;;       3 4       2 4       2       2 3 4        4
;;                  3       4 3
;;
                  [[0 :E ] [1 :E ] [2 :SW] [3 :E ]]  ; piece 5
                  [[0 :E ] [1 :SE] [2 :SE] [3 :NE]]  ; piece 6
                  [[0 :E ] [1 :SE] [2 :SE] [3 :W ]]  ; piece 7
                  [[0 :E ] [1 :SE] [2 :E ] [3 :E ]]  ; piece 8
                  [[0 :E ] [1 :E ] [2 :E ] [3 :SW]]  ; piece 9
                  ])

;; Unlike Christian Vosteen's C program, I will only use 6 directions:
;;
;; E SE SW W NW NE
;;
;; I will use a different representation for piece shapes so that I
;; won't need 12 directions for the reason that he introduced them
;; (i.e. pieces whose shapes are like a tree, and cannot be
;; represented only with a sequence of directions from one starting
;; point).


;; To minimize the amount of work done in the recursive solve function
;; below, I'm going to precalculate all legal rotations of each piece
;; at each position on the board. That's 10 pieces x 50 board
;; positions x 6 rotations x 2 'flip positions' ('top side up' or 'top
;; side down').  However, not all 6x2=12 rotations will fit on every
;; cell, so I'll have to keep count of the actual number that do.  The
;; pieces are going to be longs just like the board so they can be
;; bitwise-anded with the board to determine if they fit.  I'm also
;; going to record the next possible open cell for each piece and
;; location to reduce the burden on the solve function.


;; Returns the direction rotated 60 degrees clockwise
(defn rotate [dir]
  (case dir
        :E  :SE
        :SE :SW
        :SW :W
        :W  :NW
        :NW :NE
        :NE :E))

;; Returns the direction flipped on the horizontal axis
(defn flip [dir]
  (case dir
        :E  :E
        :SE :NE
        :SW :NW
        :W  :W
        :NW :SW
        :NE :SE))


;; Returns the new cell index from the specified cell in the specified
;; direction.  The index is only valid if the starting cell and
;; direction have been checked by the out-of-bounds function first.

(defn shift [cell dir]
  (case dir
        :E  (inc cell)
        :SE (if (odd? (quot cell 5))
              (+ cell 6)
              (+ cell 5))
        :SW (if (odd? (quot cell 5))
              (+ cell 5)
              (+ cell 4))
        :W  (dec cell)
        :NW (if (odd? (quot cell 5))
              (- cell 5)
              (- cell 6))
        :NE (if (odd? (quot cell 5))
              (- cell 4)
              (- cell 5))))


;; Returns wether the specified cell and direction will land outside
;; of the board.  Used to determine if a piece is at a legal board
;; location or not.

(defn out-of-bounds [cell dir]
  (case dir
        :E (== (rem cell 5) 4)        ; cell is on the right side
        :SE (or (== (rem cell 10) 9)  ; cell is on "extreme" right side
                (>= cell 45))         ; or the bottom row
        :SW (or (== (rem cell 10) 0)  ; cell is on "extreme" left side
                (>= cell 45))         ; or the bottom row
        :W (== (rem cell 5) 0)        ; cell is on the left side
        :NW (or (== (rem cell 10) 0)  ; cell is on "extreme" left side
                (< cell 5))           ; or the top row
        :NE (or (== (rem cell 10) 9)  ; cell is on "extreme" right side
                (< cell 5))))         ; or the top row


;; Return a piece that is the the same as the one given as argument,
;; except rotated 60 degrees clockwise.

(defn rotate-piece [piece]
  (vec (map (fn [[parent dir]] [parent (rotate dir)]) piece)))


;; Return a piece that is the the same as the one given as argument,
;; except flipped along the horizontal axis.

(defn flip-piece [piece]
  (vec (map (fn [[parent dir]] [parent (flip dir)]) piece)))


;; Convenience function to calculate and return a vector of all of the
;; board indices that a piece's nodes will be in, if that piece's root
;; node is at root-index.

;; Note that no check is made to see whether the piece actually fits
;; on the board or not, so some of the returned index values may be
;; nonsense.  See cells-fit-on-board for a way to check this.

(defn calc-cell-indices [piece root-index]
  (loop [indices [root-index]
         node (int 0)]
    (if (== node 4)
      indices
      ;; else
      ;; Note that information about node n of a piece is in (piece
      ;; (dec n)) We're intentionally iterating the value 'node' 0
      ;; through 3 rather than 1 through 4 here just to avoid
      ;; calculating (dec node) here.
      (let [[parent dir] (piece node)]
        (recur (conj indices (shift (indices parent) dir))
               (inc node))))))


;; Convenience function to calculate if a piece fits on the board.
;; Node 0 of the piece, at board index (indices 0), is assumed to be
;; on the board, but the other nodes may be off.

(defn cells-fit-on-board [piece indices]
  (and
   (let [[parent dir] (piece 0)]  ;; check node 1 of the piece
     (not (out-of-bounds (indices parent) dir)))
   (let [[parent dir] (piece 1)]  ;; node 2, etc.
     (not (out-of-bounds (indices parent) dir)))
   (let [[parent dir] (piece 2)]
     (not (out-of-bounds (indices parent) dir)))
   (let [[parent dir] (piece 3)]
     (not (out-of-bounds (indices parent) dir)))))


;; Fill the entire board going cell by cell, starting from index i.
;; If any cells are "trapped" they will be left alone.

(defn fill-contiguous-space! [^ints board i]
  (let [i (int i)]
    (when (zero? (aget board i))
      (aset board i (int 1))
      (if (not (out-of-bounds i :E))
        (fill-contiguous-space! board (shift i :E)))
      (if (not (out-of-bounds i :SE))
        (fill-contiguous-space! board (shift i :SE)))
      (if (not (out-of-bounds i :SW))
        (fill-contiguous-space! board (shift i :SW)))
      (if (not (out-of-bounds i :W))
        (fill-contiguous-space! board (shift i :W)))
      (if (not (out-of-bounds i :NW))
        (fill-contiguous-space! board (shift i :NW)))
      (if (not (out-of-bounds i :NE))
        (fill-contiguous-space! board (shift i :NE))))))


(defn empty-cells [^ints board-arr]
  (areduce board-arr i c 0 (+ c (if (zero? (aget board-arr i)) 1 0))))


;; Warning: Modifies its argument board-arr

(defn board-empty-region-sizes! [^ints board-arr]
  (loop [sizes []
         num-empty (empty-cells board-arr)]
    (if (zero? num-empty)
      sizes
      ;; else
      (let [first-empty-cell (loop [i (int 0)]
                               (if (zero? (aget board-arr i))
                                 i
                                 (recur (inc i))))]
        (fill-contiguous-space! board-arr first-empty-cell)
        (let [next-num-empty (empty-cells board-arr)]
          (recur (conj sizes (- num-empty next-num-empty))
                 next-num-empty))))))


;; Generate the long that will later be anded with the board to
;; determine if it fits.

(defn bitmask-from-indices [indices]
  (reduce bit-or (map #(bit-shift-left 1 %) indices)))


(defn print-board [soln]
  (doseq [[line-num v] (map (fn [& args] (vec args)) (range 10) (partition 5 soln))]
    (if (odd? line-num)
      (print " "))
    (print (str/join " " v))
    (println " ")))


;; Solutions are encoded as vectors of 50 integers, one for each board
;; index, where each integer is in the range [0,9], representing one
;; of the 5 parts of a piece that is in that board index.

(defn encode-solution [piece-num-vec mask-vec]
  (let [soln (int-array 50 -1)]
    (loop [i 0]
      (when (< i 50)
        ;; Note: If you use dotimes, the loop variable is cast to an
        ;; int, and this causes bit-shift-left to do a shift by (i %
        ;; 32).
        (let [idx-mask (bit-shift-left 1 i)]
          (loop [p (int 0)]
            (if (< p (count mask-vec))
              (if (zero? (bit-and (mask-vec p) idx-mask))
                (recur (inc p))
                (aset soln i (int (piece-num-vec p)))))))
        (recur (inc i))))
    (vec (map (fn [i] (if (neg? i) "." i))
              (seq soln)))))


;; To thin the number of pieces, I calculate if any of them trap any
;; empty cells at the edges, such that the number of trapped empty
;; cells is not a multiple of 5.  All pieces have 5 cells, so any such
;; trapped regions cannot possibly be filled with any pieces.

(defn one-piece-has-island [indices]
  (let [temp-board (int-array 50)]
    ;; Mark the piece board positions as filled in both boards.
    (doseq [idx indices]
      (aset temp-board idx (int 1)))
    (let [empty-region-sizes (board-empty-region-sizes! temp-board)]
      (not (every? #(zero? (rem % 5)) empty-region-sizes)))))


;; Calculate the lowest possible open cell if the piece is placed on
;; the board.  Used to later reduce the amount of time searching for
;; open cells in the solve function.

(defn first-empty-cell-after [minimum indices]
  (let [idx-set (set indices)]
    (loop [i (int minimum)]
      (if (idx-set i)
        (recur (inc i))
        i))))


;; We calculate only half of piece 3's rotations.  This is because any
;; solution found has an identical solution rotated 180 degrees.  Thus
;; we can reduce the number of attempted pieces in the solve algorithm
;; by not including the 180- degree-rotated pieces of ONE of the
;; pieces.  I chose piece 3 because it gave me the best time ;)

(def *piece-num-to-do-only-3-rotations* 3)

;; Calculate every legal rotation for each piece at each board
;; location.

(defn calc-pieces [pieces]
  (let [all (apply concat
             (for [p (range (count pieces))
                   index (range 0 50)]
               (let [num-rots (if (= p *piece-num-to-do-only-3-rotations*) 3 6)]
                 (map (fn [piece] [p piece index])
                      (concat
                       (take num-rots (iterate rotate-piece  ; top side up
                                               (pieces p)))
                       (take num-rots (iterate rotate-piece  ; flipped
                                               (flip-piece (pieces p)))))))))
        with-indices (map (fn [[piece-num piece root-index]]
                            [piece-num piece
                             (calc-cell-indices piece root-index)])
                          all)
        keepers (filter (fn [[piece-num piece indices]]
                          (and (cells-fit-on-board piece indices)
                               (not (one-piece-has-island indices))))
                        with-indices)
        processed (map (fn [[piece-num piece indices]]
                         (let [minimum (apply min indices)]
                           {:piece-num piece-num
                            :minimum minimum
                            :next-index (first-empty-cell-after minimum indices)
                            :piece-mask (bitmask-from-indices indices)}))
                       keepers)]
    ;; Create a "2d array" (actually a vector of vectors) indexed by
    ;; [piece number, minimum index occupied by the piece], where each
    ;; table entry contains a vector of the following sets of
    ;; information (stored in maps):
    ;;
    ;; (1) the bit mask of board indices occupied by the piece in a
    ;;     particular flip/rotation, and
    ;;
    ;; (2) the smallest index that is not filled by any part of the
    ;;     piece, that is larger than the piece's minimum occupied
    ;;     index.
    (reduce (fn [tbl p]
              (let [{piece-num :piece-num, minimum :minimum} p]
                (update-in tbl [piece-num minimum]
                           (fn [old-vec new-entry]
                             (conj old-vec new-entry))
                           {:piece-mask (:piece-mask p)
                            :next-index (:next-index p)})))
            ;; Start with a vector of 10 elements, each of which is a
            ;; vector of 50 elements, all [].  The reduce call above
            ;; will then fill in some of these entries with more
            ;; interesting things.
            (vec (repeatedly 10 (constantly
                                 (vec (repeatedly 50 (constantly []))))))
            processed)))


(defn first-empty-index [idx board]
  (loop [i idx
         board (bit-shift-right board i)]
    (if (zero? (bit-and board (int 1)))
      i
      (recur (inc i) (bit-shift-right board 1)))))


(defn create-triples []
  (let [bad-even-triples (int-array (/ (bit-shift-left 1 15) 32))
        bad-odd-triples (int-array (/ (bit-shift-left 1 15) 32))
        temp-arr (int-array 50)]
    (dotimes [row0 32]
      (dotimes [row1 32]
        (dotimes [row2 32]
          (let [board (bit-or (bit-or row0 (bit-shift-left row1 5))
                              (bit-shift-left row2 10))]
            (dotimes [i 15]
              (aset temp-arr i (int (bit-and 1 (bit-shift-right board i)))))
            (dotimes [i 35]
              (aset temp-arr (+ 15 i) (int 0)))
            (let [empty-region-sizes (board-empty-region-sizes! temp-arr)
                  empty-sizes-except-largest (rest (sort #(compare %2 %1)
                                                         empty-region-sizes))
                  j (int (bit-shift-right board 5))
                  i (int (rem board 32))]
              (when-not (every? #(zero? (rem % 5)) empty-sizes-except-largest)
                ;; then it is possible for pieces to fill the empty
                ;; regions
                (aset bad-even-triples j
                      (bit-or (aget bad-even-triples j)
                              (bit-shift-left (int 1) i)))))))))
    (dotimes [i 5]
      (aset temp-arr i (int 1)))
    (dotimes [row1 32]
      (dotimes [row2 32]
        (dotimes [row3 32]
          (let [board-rows-1-3 (bit-or (bit-or row1 (bit-shift-left row2 5))
                                       (bit-shift-left row3 10))
                board (bit-or 0x1F (bit-shift-left board-rows-1-3 5))]
            (dotimes [i 15]
              (aset temp-arr (+ 5 i) (int (bit-and 1 (bit-shift-right board-rows-1-3 i)))))
            (dotimes [i 30]
              (aset temp-arr (+ 20 i) (int 0)))
            (let [empty-region-sizes (board-empty-region-sizes! temp-arr)
                  empty-sizes-except-largest (rest (sort #(compare %2 %1)
                                                         empty-region-sizes))
                  j (int (bit-shift-right board-rows-1-3 5))
                  i (int (rem board-rows-1-3 32))]
              (when-not (every? #(zero? (rem % 5)) empty-sizes-except-largest)
                (aset bad-odd-triples j
                      (bit-or (aget bad-odd-triples j)
                              (bit-shift-left 1 i)))
                ))))))
    [bad-even-triples bad-odd-triples]))


(def num-solutions (int-array 1))
(def all-solutions (object-array 2200))

;; See comments above *piece-num-to-do-only-3-rotations*.  Each
;; solution is thus recorded twice.  Reversing the solution has the
;; effect of rotating it 180 degrees.

(defn record-solution! [soln]
  (let [^ints num-solutions num-solutions
        ^objects all-solutions all-solutions
        n (int (aget num-solutions (int 0)))]
    (aset all-solutions n soln)
    (aset all-solutions (inc n) (vec (reverse soln)))
    (aset num-solutions (int 0) (+ n (int 2)))))


;; Arguments to solve-helper:

;; depth is 0 on the first call, and is 1 more for each level of
;; nested recursive call.  It is equal to the number of pieces placed
;; on the board in the partial solution so far.

;; board is a long representing which board cells are occupied (bit
;; value 1) or empty (bit value 0), based upon the pieces placed so
;; far.

;; cell is the board index in [0,49] that should be checked first to
;; see if it is empty.

;; unplaced-piece-num-set is a set of the piece numbers, each in the
;; range [0,9], that have not been placed so far in the current
;; configuration.

;; piece-num-vec is a vector of the piece-nums placed so far, in the
;; order they were placed, i.e. depth order.  (piece-vec 0) was placed
;; at depth 0, etc.  [] in root call.  (named sol_nums in GCC program)

;; mask-vec is a vector of the bitmasks of the pieces placed so far,
;; in the order they were placed.  [] in root call.  (named sol_masks
;; in GCC program)

(defn solve! [tbl ^ints bad-even-triples ^ints bad-odd-triples]
  (letfn
      [(board-has-islands [board index]
         (if (>= index 40)
          false
          (let [row-num (int (quot index 5))
                row-begin-idx (* 5 row-num)
                current-three-rows (bit-and (bit-shift-right board row-begin-idx)
                                            0x7FFF)
                int-num (int (bit-shift-right current-three-rows 5))
                bit-num (int (rem current-three-rows 32))
                even-row (zero? (rem row-num 2))]
            (if even-row
              (not (zero? (bit-and 1 (bit-shift-right
                                      (aget bad-even-triples int-num) bit-num))))
              (not (zero? (bit-and 1 (bit-shift-right
                                      (aget bad-odd-triples int-num) bit-num))))))))
       (solve-helper [depth board cell unplaced-piece-num-set
                      piece-num-vec mask-vec]
         (let [cell (int (first-empty-index cell board))]
           (doseq [piece-num (seq unplaced-piece-num-set)]
             (doseq [{piece-mask :piece-mask, next-index :next-index}
                     ((tbl piece-num) cell)]
               (when (zero? (bit-and board piece-mask))
                 (if (== depth 9)
                   ;; Solution found!
                   (let [sol1 (encode-solution (conj piece-num-vec piece-num)
                                               (conj mask-vec piece-mask))]
                     ;; TBD: After I pick one piece to only use 3
                     ;; rotations of, replace the following line
                     ;; with: [sol1 (reverse sol1)].  (reverse sol1)
                     ;; is sol1 rotated 180 degrees.
                     (record-solution! sol1))
                    ;; else
                   (let [next-board (bit-or board piece-mask)]
                     (when-not (board-has-islands next-board next-index)
                       (solve-helper
                        (inc depth)
                        (bit-or board piece-mask)
                        next-index
                        (disj unplaced-piece-num-set piece-num)
                        (conj piece-num-vec piece-num)
                        (conj mask-vec piece-mask))))))))))]
    (solve-helper 0 (long 0) 0 (set (range 10)) [] [])))


(defn -main [& args]
(time
  (let [tbl (calc-pieces piece-defs)
        [bad-even-triples bad-odd-triples] (create-triples)]
    (solve! tbl bad-even-triples bad-odd-triples)
    (let [^ints num-solutions num-solutions
          n (int (aget num-solutions (int 0)))
          sorted-solns (sort (take n (seq all-solutions)))]
      (println (format "%d solutions found" n))
      (println)
      (print-board (first sorted-solns))
      (println)
      (print-board (nth sorted-solns (dec n)))
      ;; Here just to match the output of the other programs exactly
      (println))))
)
