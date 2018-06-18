(ns crux.kafka-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [crux.db :as db]
            [crux.doc :as doc]
            [crux.tx :as tx]
            [crux.rdf :as rdf]
            [crux.kafka :as k]
            [crux.query :as q]
            [crux.fixtures :as f]
            [crux.embedded-kafka :as ek])
  (:import [java.util Date]
           [org.apache.kafka.clients.producer
            ProducerRecord]
           [org.apache.kafka.common TopicPartition]))

(t/use-fixtures :once ek/with-embedded-kafka-cluster)
(t/use-fixtures :each ek/with-kafka-client f/with-kv-store)

(t/deftest test-can-produce-and-consume-message-using-embedded-kafka
  (let [topic "test-can-produce-and-consume-message-using-embedded-kafka-topic"
        person (f/random-person)
        partitions [(TopicPartition. topic 0)]]

    (k/create-topic ek/*admin-client* topic 1 1 {})

    @(.send ek/*producer* (ProducerRecord. topic person))

    (.assign ek/*consumer* partitions)
    (let [records (.poll ek/*consumer* 10000)]
      (t/is (= 1 (count (seq records))))
      (t/is (= person (first (map k/consumer-record->value records)))))))

(defn load-ntriples-example [resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (vec (for [entity (->> (rdf/ntriples-seq in)
                           (rdf/statements->maps)
                           (map rdf/use-iri-as-id))]
           [:crux.tx/put (:crux.rdf/iri entity) entity]))))

(t/deftest test-can-transact-entities
  (let [tx-topic "test-can-transact-entities-tx"
        doc-topic "test-can-transact-entities-doc"
        tx-ops (load-ntriples-example  "crux/example-data-artists.nt")
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        indexer (tx/->DocIndexer f/*kv* tx-log (doc/->DocObjectStore f/*kv*))]

    (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer ek/*consumer* [doc-topic])

    (db/submit-tx tx-log tx-ops)

    (let [docs (map k/consumer-record->value (.poll ek/*consumer* 10000))]
      (t/is (= 7 (count docs)))
      (t/is (= {:http://xmlns.com/foaf/0.1/firstName "Pablo"
                :http://xmlns.com/foaf/0.1/surname "Picasso"}
               (select-keys (first docs)
                            [:http://xmlns.com/foaf/0.1/firstName
                             :http://xmlns.com/foaf/0.1/surname]))))))

(t/deftest test-can-transact-and-query-entities
  (let [tx-topic "test-can-transact-and-query-entities-tx"
        doc-topic "test-can-transact-and-query-entities-doc"
        tx-ops (load-ntriples-example  "crux/picasso.nt")
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        indexer (tx/->DocIndexer f/*kv* tx-log (doc/->DocObjectStore f/*kv*))]

    (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer ek/*consumer* [tx-topic doc-topic])

    (t/testing "transacting and indexing"
      (db/submit-tx tx-log tx-ops)

      (t/is (= 20 (count (k/consume-and-index-entities indexer ek/*consumer*))))
      (t/is (empty? (.poll ek/*consumer* 1000))))

    (t/testing "restoring to stored offsets"
      (.seekToBeginning ek/*consumer* (.assignment ek/*consumer*))
      (k/seek-to-stored-offsets indexer ek/*consumer* (.assignment ek/*consumer*))
      (t/is (empty? (.poll ek/*consumer* 1000))))

    (t/testing "querying transacted data"
      (t/is (= #{[:http://example.org/Picasso]}
               (q/q (doc/db f/*kv*)
                    '{:find [iri]
                      :where [[e :http://xmlns.com/foaf/0.1/firstName "Pablo"]
                              [e :crux.rdf/iri iri]]}))))))

(t/deftest test-can-transact-and-query-dbpedia-entities
  (let [tx-topic "test-can-transact-and-query-dbpedia-entities-tx"
        doc-topic "test-can-transact-and-query-dbpedia-entities-doc"
        tx-ops (->> (concat (load-ntriples-example "crux/Pablo_Picasso.ntriples")
                            (load-ntriples-example "crux/Guernica_(Picasso).ntriples"))
                    (map #(rdf/use-default-language % :en))
                    (vec))
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        indexer (tx/->DocIndexer f/*kv* tx-log (doc/->DocObjectStore f/*kv*))]

    (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer ek/*consumer* [tx-topic doc-topic])

    (t/testing "transacting and indexing"
      (db/submit-tx tx-log tx-ops)
      (t/is (= 82 (count (k/consume-and-index-entities indexer ek/*consumer*)))))

    (t/testing "querying transacted data"
      (t/is (= #{[:http://dbpedia.org/resource/Pablo_Picasso]}
               (q/q (doc/db f/*kv*)
                    '{:find [iri]
                      :where [[e :http://xmlns.com/foaf/0.1/givenName "Pablo"]
                              [e :crux.rdf/iri iri]]})))

      (t/is (= #{[(keyword "http://dbpedia.org/resource/Guernica_(Picasso)")]}
               (q/q (doc/db f/*kv*)
                    '{:find [g-iri]
                      :where [[p :http://xmlns.com/foaf/0.1/givenName "Pablo"]
                              [p :crux.rdf/iri p-iri]
                              [g :http://dbpedia.org/ontology/author p-iri]
                              [g :crux.rdf/iri g-iri]]}))))))

;; TODO: not passing the LUBM query yet, takesCourse returns nothing.
#_(t/deftest test-can-transact-and-query-lubm-entities
  (let [tx-topic "test-can-transact-and-query-lubm-entities-tx"
        doc-topic "test-can-transact-and-query-lubm-entities-doc"
        tx-ops (->> (concat (load-ntriples-example "lubm/univ-bench.ntriples")
                            (load-ntriples-example "lubm/University0_0.ntriples"))
                    (map #(rdf/use-default-language % :en))
                    (vec))
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        indexer (tx/->DocIndexer f/*kv* tx-log (doc/->DocObjectStore f/*kv*))]

    (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer ek/*consumer* [tx-topic doc-topic])

    (t/testing "ensure data is indexed"
      (let [{:keys [transact-time]} @(db/submit-tx tx-log tx-ops)]
        (while (pos? (count (k/consume-and-index-entities indexer ek/*consumer* 10000))))
        (t/testing "querying transacted data"
          (t/is (= #{[:http://www.University0.edu]}
                   (q/q (doc/db f/*kv* transact-time transact-time)
                        {:find ['u-iri]
                         :where [['u :http://swat.cse.lehigh.edu/onto/univ-bench.owl#name "University0"]
                                 ['u :crux.rdf/iri 'u-iri]]}))))))

    ;; SPARQL
    ;; PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
    ;; PREFIX ub: <http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#>
    ;; SELECT ?X
    ;; WHERE
    ;; {?X rdf:type ub:GraduateStudent .
    ;;   ?X ub:takesCourse
    ;; http://www.Department0.University0.edu/GraduateCourse0}

    ;; EmptyHeaded Datalog
    ;; lubm1(a) :- b='http://www.Department0.University0.edu/GraduateCourse0',
    ;;   c='http://www.lehigh.edu/~zhp2/2004/0401/univ-bench.owl#GraduateStudent',
    ;;   takesCourse(a,b),rdftype(a,c).
    (t/testing "LUBM query 1"
      (t/is (= #{[:http://www.Department0.University0.edu/GraduateStudent101]
                 [:http://www.Department0.University0.edu/GraduateStudent124]
                 [:http://www.Department0.University0.edu/GraduateStudent142]
                 [:http://www.Department0.University0.edu/GraduateStudent44]}
               (q/q (doc/db f/*kv*)
                    {:find ['a-iri]
                     :where [['a
                              :http://swat.cse.lehigh.edu/onto/univ-bench.owl#takesCourse
                              :http://www.Department0.University0.edu/GraduateCourse0]
                             ['a
                              (keyword "http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
                              :http://swat.cse.lehigh.edu/onto/univ-bench.owl#GraduateStudent]
                             ['a :crux.rdf/iri 'a-iri]]}))))))

;; Download from http://wiki.dbpedia.org/services-resources/ontology
;; mappingbased_properties_en.nt is the main data.
;; instance_types_en.nt contains type definitions only.
;; specific_mappingbased_properties_en.nt contains extra literals.
;; dbpedia_2014.owl is the OWL schema, not dealt with.

;; Test assumes these files are living under ../dbpedia related to the
;; crux project (to change).

;; There are 5053979 entities across 33449633 triplets in
;; mappingbased_properties_en.nt.

;; RocksDB:
;; 1.6G    /tmp/kafka-log1572248494326726941
;; 2.0G    /tmp/kv-store8136342842355297151
;; 583800ms ~9.7mins transact
;; 861799ms ~14.40mins index

;; LMDB:
;; 1.6G    /tmp/kafka-log17904986480319416547
;; 9.3G    /tmp/kv-store4104462813030460112
;; 640528ms ~10.7mins transact
;; 2940230ms 49mins index

;; Could use test selectors.
(def run-dbpedia-tests? false)

(t/deftest test-can-transact-all-dbpedia-entities
  (let [tx-topic "test-can-transact-all-dbpedia-entities-tx"
        doc-topic "test-can-transact-all-dbpedia-entities-doc"
        tx-size 1000
        max-limit Long/MAX_VALUE
        print-size 100000
        add-and-print-progress (fn [n step message]
                                 (let [next-n (+ n step)]
                                   (when-not (= (quot n print-size)
                                                (quot next-n print-size))
                                     (log/warn message next-n))
                                   next-n))
        n-transacted (atom -1)
        mappingbased-properties-file (io/file "../dbpedia/mappingbased_properties_en.nt")
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        indexer (tx/->DocIndexer f/*kv* tx-log (doc/->DocObjectStore f/*kv*))]

    (if (and run-dbpedia-tests? (.exists mappingbased-properties-file))
      (do (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
          (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
          (k/subscribe-from-stored-offsets indexer ek/*consumer* [tx-topic doc-topic])

          (t/testing "transacting and indexing"
            (future
              (time
               (with-open [in (io/input-stream mappingbased-properties-file)]
                 (reset! n-transacted (rdf/submit-ntriples tx-log in tx-size)))))
            (time
             (loop [entities (k/consume-and-index-entities indexer ek/*consumer* 100)
                    n 0]
               (let [n (add-and-print-progress n (count entities) "indexed")]
                 (when-not (= n @n-transacted)
                   (recur (k/consume-and-index-entities indexer ek/*consumer* 100)
                          (long n)))))))

          (t/testing "querying transacted data"
            (t/is (= #{[:http://dbpedia.org/resource/Aristotle]
                       [(keyword "http://dbpedia.org/resource/Aristotle_(painting)")]
                       [(keyword "http://dbpedia.org/resource/Aristotle_(book)")]}
                     (q/q (doc/db f/*kv*)
                          '{:find [iri]
                            :where [[e :http://xmlns.com/foaf/0.1/name "Aristotle"]
                                    [e :crux.rdf/iri iri]]})))))
      (t/is true "skipping"))))
