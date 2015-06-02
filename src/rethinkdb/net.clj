(ns rethinkdb.net
  (:require [clojure.data.json :as json]
            [clojure.core.async :as async]
            [rethinkdb.query-builder :refer [parse-query]]
            [rethinkdb.response :refer [parse-response]]
            [rethinkdb.utils :refer [str->bytes int->bytes bytes->int pp-bytes]])
  (:import [java.io Closeable]))

(declare send-continue-query send-stop-query)

(defn close
  "Clojure proxy for java.io.Closeable's close."
  [^Closeable x]
  (.close x))

(deftype Cursor [conn token coll]
  Closeable
  (close [this] (and (send-stop-query conn token) :closed))
  clojure.lang.Seqable
  (seq [this] (do
                (Thread/sleep 250)
                (lazy-seq (concat coll (send-continue-query conn token))))))

(defn send-int [out i n]
  (.write out (int->bytes i n) 0 n))

(defn send-str [out s]
  (let [n (count s)]
    (.write out (str->bytes s) 0 n)))

(defn read-str [in n]
  (let [resp (byte-array n)]
    (.readFully in resp 0 n)
    (String. resp)))

(defn read-init-response [in]
  (let [resp (byte-array 4096)]
    (.read in resp 0 4096)
    (clojure.string/replace (String. resp) #"\W*$" "")))

(defn read-response [in token]
  (let [recvd-token (byte-array 8)
        length (byte-array 4)]
    (.read in recvd-token 0 8)
    (let [recvd-token (bytes->int recvd-token 8)]
      (assert (= token recvd-token)))
    (.read in length 0 4)
    (let [length (bytes->int length 4)
          json (read-str in length)]
      (json/read-str json :key-fn keyword))))

(defn send-query-sync [conn token query]
  (let [json (json/write-str query)
        {:keys [in out]} @conn
        n (count json)]
    (send-int out token 8)
    (send-int out n 4)
    (send-str out json)
    (let [{type :t resp :r} (read-response in token)
          resp (parse-response resp)]
      (condp get type
        #{1} (first resp)
        #{2} (do
               (swap! (:conn conn) update-in [:waiting] #(disj % token))
               resp)
        #{3 5} (if (get (:waiting @conn) token)
                 (lazy-seq (concat resp (send-continue-query conn token)))
                 (do
                   (swap! (:conn conn) update-in [:waiting] #(conj % token))
                   (Cursor. conn token resp)))
        (throw (Exception. (first resp)))))))


(defn read-response* [in]
  (let [recvd-token (byte-array 8)
        length (byte-array 4)]
    (.read in recvd-token 0 8)
    (.read in length 0 4)
    (let [recvd-token (bytes->int recvd-token 8)
          length (bytes->int length 4)
          json (read-str in length)]
      [recvd-token json])))


(defn send-query* [out [token json]]
  (send-int out token 8)
  (send-int out (count json) 4)
  (send-str out json))


(defn make-connection-loops [in out]
  (let [recv-chan (async/chan)
        send-chan (async/chan)
        pub       (async/pub recv-chan first)
        ;; Receive loop
        recv-loop (async/go-loop []
                    (when (try
                            (let [resp (read-response* in)]
                              (async/>! recv-chan resp))
                            (catch java.net.SocketException e
                              false))
                      (recur)))
        ;; Send loop
        send-loop (async/go-loop []
                    (when-let [query (async/<! send-chan)]
                      (send-query* out query)
                      (recur)))]
    ;; Return as map to merge into connection
    {:pub pub
     :loops [recv-loop send-loop]
     :r-ch recv-chan
     :ch send-chan}))

(defn close-connection-loops [conn]
  (let [{:keys [pub ch r-ch] [recv-loop send-loop] :loops} @conn]
    (async/unsub-all pub)
    ;; Close send channel and wait for loop to complete
    (async/close! ch)
    (async/<!! send-loop)  
    ;; Close recv channel 
    (async/close! r-ch)))


(defn send-query-async* [conn cbch token query]
  (let [{:keys [pub ch]} @conn
        chan (async/chan)]
    (async/sub pub token chan)
    (async/>!! ch [token query])
    (println "Waiting" cbch)
    (cond
      (fn? cbch)
        (do ;async/go
            (println "Callback")
          (when-let [[recvd-token result] (async/<!! chan)]
            (println "Got" recvd-token)
            (assert (= recvd-token token))
            (when (= recvd-token token)
              (cbch (json/read-str :key-fn keyword)))))
      (nil? cbch)
        (let [[recvd-token json] (async/<!! chan)]
          (assert (= recvd-token token))
          (async/unsub pub token chan)
          (json/read-str json :key-fn keyword))
      :else
        (async/go
          (when-let [[recvd-token result] (async/<! chan)]
            (when (= recvd-token token)
              (async/>! cbch (json/read-str :key-fn keyword))))))))


(defn send-query-async [conn token chan query]
  (let [json (json/write-str query)
        {type :t resp :r} (send-query-async* conn chan token json) 
        resp (parse-response resp)]
    (condp get type
      #{1} (first resp)
      #{2} (do
             (swap! (:conn conn) update-in [:waiting] #(disj % token))
             resp)
      #{3 5} (if (get (:waiting @conn) token)
               (lazy-seq (concat resp (send-continue-query conn token)))
               (do
                 (swap! (:conn conn) update-in [:waiting] #(conj % token))
                 (Cursor. conn token resp)))
      (throw (Exception. (first resp))))))


(def send-query send-query-async)


(defn send-start-query [conn token chan query]
  (send-query conn token chan (parse-query :START query)))

(defn send-continue-query [conn token chan]
  (send-query conn token chan (parse-query :CONTINUE)))

(defn send-stop-query [conn token chan]
  (send-query conn token chan (parse-query :STOP)))
