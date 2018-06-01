(ns crux.memdb
  (:require [crux.byte-utils :as bu]
            [crux.kv-store :as ks]
            [clojure.java.io :as io]
            [taoensso.nippy :as nippy])
  (:import java.io.Closeable
           [java.util SortedMap TreeMap]))

(defn- atom-cursor->next! [cursor]
  (let [[k v :as kv] (first @cursor)]
    (swap! cursor rest)
    (when kv
      [k v])))

(defn- persist-db [dir db]
  (let [file (io/file dir)]
    (.mkdirs file)
    (nippy/freeze-to-file (io/file file "memdb") (into {} db))))

(defn- restore-db [dir]
  (doto (TreeMap. bu/bytes-comparator)
    (.putAll (nippy/thaw-from-file (io/file dir "memdb")))))

(def ^:dynamic ^:private *current-iterator* nil)

(defrecord MemKv [^SortedMap db db-dir persist-on-close?]
  ks/KvStore
  (open [this]
    (if (.isFile (io/file db-dir "memdb"))
      (assoc this :db (restore-db db-dir))
      (assoc this :db (TreeMap. bu/bytes-comparator))))

  (iterate-with [_ f]
    (if *current-iterator*
      (f *current-iterator*)
      (let [c (atom nil)
            i (reify
                ks/KvIterator
                (ks/-seek [this k]
                  (reset! c (.tailMap db k))
                  (atom-cursor->next! c))
                (ks/-next [this]
                  (atom-cursor->next! c)))]
       (binding [*current-iterator* i]
         (f i)))))

  (store [_ kvs]
    (locking db
      (doseq [[k v] kvs]
        (.put db k v))))

  (delete [_ ks]
    (locking db
      (doseq [k ks]
        (.remove db k))))

  (backup [_ dir]
    (let [file (io/file dir)]
      (when (.exists file)
        (throw (IllegalArgumentException. (str "Directory exists: " (.getAbsolutePath file)))))
      (persist-db dir db)))

  Closeable
  (close [_]
    (when (and db-dir persist-on-close?)
      (persist-db db-dir db))))
