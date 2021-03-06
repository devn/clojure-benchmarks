;;   The Computer Language Benchmarks Game
;;   http://shootout.alioth.debian.org/

;; contributed by Andy Fingerhut

(ns revcomp
  (:gen-class))

(set! *warn-on-reflection* true)


(def complement-dna-char-map
     {\w \W, \W \W,
      \s \S, \S \S,
      \a \T, \A \T,
      \t \A, \T \A,
      \u \A, \U \A,
      \g \C, \G \C,
      \c \G, \C \G,
      \y \R, \Y \R,
      \r \Y, \R \Y,
      \k \M, \K \M,
      \m \K, \M \K,
      \b \V, \B \V,
      \d \H, \D \H,
      \h \D, \H \D,
      \v \B, \V \B,
      \n \N, \N \N })


(defn make-array-char-mapper [cmap]
  (int-array 256 (map (fn [i]
                        (if (contains? cmap (char i))
                          (int (cmap (char i)))
                          i))
                      (range 256))))


(defn revcomp-buf-and-write [#^java.lang.StringBuilder buf
			     #^java.io.BufferedWriter wrtr
			     #^ints comp]
  (let [len (.length buf)
        nl (int \newline)]
    (when (> len 0)
      (loop [begin (int 0)
	     end (int (unchecked-dec len))]
	(when (<= begin end)
	  ;; then reverse and complement two more characters, working
	  ;; from beginning and end towards the middle
	  (let [b (int (if (== (int (.charAt buf begin)) nl)
                         (unchecked-inc begin)
                         begin))
		e (int (if (== (int (.charAt buf end)) nl)
                         (unchecked-dec end)
                         end))]
	    (when (<= b e)
	      (let [cb (char (aget comp (int (.charAt buf b))))
		    ce (char (aget comp (int (.charAt buf e))))]
		(.setCharAt buf b ce)
		(.setCharAt buf e cb)
		(recur (unchecked-inc b) (unchecked-dec e)))))))
      (.write wrtr (.toString buf) 0 len))))


(defn -main [& args]
  (let [rdr (java.io.BufferedReader. *in*)
	wrtr (java.io.BufferedWriter. *out*)
        complement-dna-char-array (make-array-char-mapper
				   complement-dna-char-map)]
    (loop [line (.readLine rdr)
	   buf (new java.lang.StringBuilder)]
      (if line
	(if (= (get line 0) \>)
	  ;; then print out revcomp of any string in buf, and after
	  ;; that, the line just read
	  (do
	    (revcomp-buf-and-write buf wrtr complement-dna-char-array)
	    (.write wrtr line 0 (count line))
	    (.newLine wrtr)
	    (recur (.readLine rdr) (new java.lang.StringBuilder)))
	  ;; else add the line to buf
	  (do
	    (.append buf line)
	    (.append buf \newline)
	    (recur (.readLine rdr) buf)))
	;; else print out revcomp of any string in buf
	(revcomp-buf-and-write buf wrtr complement-dna-char-array)))
    (.flush wrtr)))
