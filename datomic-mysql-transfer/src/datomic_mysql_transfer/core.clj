(ns datomic-mysql-transfer.core
  (:require [clojure.java.jdbc :as jdbc]
            [environ.core :as environ]
            [datomic.api :as d]
            [onyx.plugin.datomic]
            [onyx.plugin.sql]
            [onyx.api])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]))

;;;;;;;; First, some set up work for SQL ;;;;;;;;;;;;;

;;; Def some top-level constants to use below

(def db-name (or (environ/env :test-db-name) "onyx_input_test"))

(def db-user (or (environ/env :test-db-user) "root"))

(def db-pass "")

(def classname "com.mysql.jdbc.Driver")

(def subprotocol "mysql")

(def subname "//127.0.0.1:3306")

(def db-subname (str subname "/" db-name))

;;; Throughput knob that you can tune
(def batch-size 20)

;;; The table to read out of
(def table :people)

;;; The table we'll later write back to
(def copy-table :people_copy)

;;; A monotonically increasing integer to partition the table by
(def id-column :id)

;;; JDBC spec to connect to MySQL
(def db-spec
  {:classname classname
   :subprotocol subprotocol
   :subname subname
   :user db-user
   :password db-pass})

;;; Create a pool of connections for the virtual peers of Onyx to share
(defn pool [spec]
  {:datasource
   (doto (ComboPooledDataSource.)
     (.setDriverClass (:classname spec))
     (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
     (.setUser (:user spec))
     (.setPassword (:password spec))
     (.setMaxIdleTimeExcessConnections (* 30 60))
     (.setMaxIdleTime (* 3 60 60)))})

;;; Create the pool
(def conn-pool (pool db-spec))

;;; Get rid of the database if it exists, makes the example idempotent
(try
  (jdbc/execute! conn-pool [(format "drop database %s" db-name)])
  (catch Exception e
    (.printStackTrace e)))

;;; Recreate the database from scratch
(jdbc/execute! conn-pool [(format "create database %s" db-name)])

(jdbc/execute! conn-pool [(format "use %s" db-name)])

(def db-spec
  {:classname "com.mysql.jdbc.Driver"
   :subprotocol "mysql"
   :subname db-subname
   :user db-user
   :password db-pass})

(def conn-pool (pool db-spec))

;;; Create the table we'll be reading out of
(jdbc/execute!
 conn-pool
 (vector (jdbc/create-table-ddl
          table
          [:id :int "PRIMARY KEY AUTO_INCREMENT"]
          [:name "VARCHAR(32)"]
          [:age "INTEGER(4)"])))

;;; Data to insert into the table
(def people
  [{:name "Mike" :age 23}
   {:name "Dorrene" :age 24}
   {:name "Bridget" :age 32}
   {:name "Joe" :age 70}
   {:name "Amanda" :age 25}
   {:name "Steven" :age 30}])

(jdbc/execute! conn-pool [(format "use %s" db-name)])

;;; Insert the table into the "people" SQL table
(doseq [person people]
  (jdbc/insert! conn-pool :people person))

;;;;;;;; Next, some set up work for Datomic ;;;;;;;;;;;;;

;;; The URI for the Datomic database that we'll write to
(def db-uri (str "datomic:mem://" (java.util.UUID/randomUUID)))

;;; The schema of the database. A user's name and age, semantic
;;; equivalent of the MySQL schema.
(def schema
  [{:db/id #db/id [:db.part/db]
    :db/ident :com.mdrogalis/people
    :db.install/_partition :db.part/db}
   
   {:db/id #db/id [:db.part/db]
    :db/ident :user/name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}

   {:db/id #db/id [:db.part/db]
    :db/ident :user/age
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one
    :db.install/_attribute :db.part/db}])

;;; Create the DB, connect to it, and transact the schema
(d/create-database db-uri)

(def datomic-conn (d/connect db-uri))

@(d/transact datomic-conn schema)

;;;;;;;;;;;; Next, we run the Onyx job to transfer the data ;;;;;;;;;;;;;;
(def id (java.util.UUID/randomUUID))

(def env-config
  {:zookeeper/address "127.0.0.1:2188"
   :zookeeper/server? true
   :zookeeper.server/port 2188
   :onyx/id id})

(def peer-config
  {:zookeeper/address "127.0.0.1:2188"
   :onyx/id id
   :onyx.peer/job-scheduler :onyx.job-scheduler/balanced
   :onyx.messaging/impl :aeron
   :onyx.messaging/peer-port 40200
   :onyx.messaging/bind-addr "localhost"})

(def env (onyx.api/start-env env-config))

(def peer-group (onyx.api/start-peer-group peer-config))

;;; Partition the MySQL table by ID column, parallel read the rows,
;;; do a semantic transformation, write to Datomic.
(def workflow
  [[:partition-keys :read-rows]
   [:read-rows :prepare-datoms]
   [:prepare-datoms :write-to-datomic]])

(def n-peers (count (set (mapcat identity workflow))))

(def v-peers (onyx.api/start-peers n-peers peer-group))

(def catalog
  [{:onyx/name :partition-keys
    :onyx/plugin :onyx.plugin.sql/partition-keys
    :onyx/type :input
    :onyx/medium :sql
    :sql/classname classname
    :sql/subprotocol subprotocol
    :sql/subname db-subname
    :sql/user db-user
    :sql/password db-pass
    :sql/table table
    :sql/id id-column
    :sql/rows-per-segment 1000
    :onyx/batch-size batch-size
    :onyx/max-peers 1
    :onyx/doc "Partitions a range of primary keys into subranges"}

   {:onyx/name :read-rows
    :onyx/fn :onyx.plugin.sql/read-rows
    :onyx/type :function
    :sql/classname classname
    :sql/subprotocol subprotocol
    :sql/subname db-subname
    :sql/user db-user
    :sql/password db-pass
    :sql/table table
    :sql/id id-column
    :onyx/batch-size batch-size
    :onyx/doc "Reads rows of a SQL table bounded by a key range"}

   {:onyx/name :prepare-datoms
    :onyx/fn :datomic-mysql-transfer.core/prepare-datoms
    :onyx/type :function
    :onyx/batch-size batch-size
    :onyx/doc "Semantically transform the SQL rows to Datomic datoms"}

   {:onyx/name :write-to-datomic
    :onyx/plugin :onyx.plugin.datomic/write-bulk-datoms
    :onyx/type :output
    :onyx/medium :datomic
    :datomic/uri db-uri
    :datomic/partition :com.mdrogalis/people
    :onyx/batch-size batch-size
    :onyx/doc "Transacts segments to storage"}])

;;; We need to prepare the datoms before we send it to the Datomic plugin.
;;; Set the temp ids and batch the segments into the :datoms key.
(defn prepare-datoms [segment]
  {:tx [{:db/id (d/tempid :com.mdrogalis/people)
         :user/name (:name segment)
         :user/age (:age segment)}]})

(def lifecycles
  [{:lifecycle/task :partition-keys
    :lifecycle/calls :onyx.plugin.sql/partition-keys-calls}
   {:lifecycle/task :read-rows
    :lifecycle/calls :onyx.plugin.sql/read-rows-calls}
   {:lifecycle/task :write-to-datomic
    :lifecycle/calls :onyx.plugin.datomic/write-bulk-tx-calls}])

;;; And off we go!
(def job-id
  (:job-id
   (onyx.api/submit-job
    peer-config
    {:catalog catalog :workflow workflow :lifecycles lifecycles
     :task-scheduler :onyx.task-scheduler/balanced})))

;;; Block until the job is done, then check Datomic
(onyx.api/await-job-completion peer-config job-id)

;;; Take the value of the database
(def db (d/db datomic-conn))

;; And take the T value for later
(def t (d/next-t db))

;;; All the names and ages
(prn "Datomic...")
(clojure.pprint/pprint 
 (->> db
      (d/q '[:find ?e :where [?e :user/name]])
      (map first)
      (map (partial d/entity db))
      (map (partial into {}))))

(prn)

;;;;; Now we'll go in the reverse direction. Read from Datomic, write to MySQL ;;;;;;

;;; Create the table we'll write back to.
(jdbc/execute!
 conn-pool
 (vector (jdbc/create-table-ddl
          copy-table
          [:id :int "PRIMARY KEY AUTO_INCREMENT"]
          [:name "VARCHAR(32)"]
          [:age "INTEGER(4)"])))

;;; Partition the datoms index, read datoms in parallel,
;;; semantically transform from datoms to rows, write to MySQL.
(def workflow
  [[:read-datoms :prepare-rows]
   [:prepare-rows :write-to-mysql]])

(def catalog
  [{:onyx/name :read-datoms
    :onyx/plugin :onyx.plugin.datomic/read-datoms
    :onyx/type :input
    :onyx/medium :datomic
    :datomic/uri db-uri
    :datomic/t t
    :datomic/partition :com.mdrogalis/people
    :datomic/datoms-index :eavt
    :datomic/datoms-per-segment 20
    :onyx/max-peers 1
    :onyx/batch-size batch-size
    :onyx/doc "Reads a sequence of datoms from the d/datoms API"}

   {:onyx/name :prepare-rows
    :onyx/fn :datomic-mysql-transfer.core/prepare-rows
    :onyx/type :function
    :datomic/uri db-uri
    :datomic/t t
    :onyx/batch-size batch-size
    :onyx/doc "Semantically transform the Datomic datoms to SQL rows"}

   {:onyx/name :write-to-mysql
    :onyx/plugin :onyx.plugin.sql/write-rows
    :onyx/type :output
    :onyx/medium :sql
    :sql/classname classname
    :sql/subprotocol subprotocol
    :sql/subname db-subname
    :sql/user db-user
    :sql/password db-pass
    :sql/table copy-table
    :onyx/batch-size batch-size
    :onyx/doc "Writes segments from the :rows keys to the SQL database"}])

;;; We're going to need to get a hold of the database for queries down
;;; below. Inject it in as a parameter to the prepare-rows function.
(defn inject-db-value
  [{:keys [onyx.core/task-map onyx.core/fn-params] :as pipeline} lifecycle]
  (let [conn (d/connect (:datomic/uri task-map))
        db (d/as-of (d/db conn) (:datomic/t task-map))]
    {:onyx.core/params [db]}))

(def prepare-rows-calls
  {:lifecycle/before-task-start inject-db-value})

;;; Semantic preparation from datoms to rows for MySQL.
;;; We'll have to query Datomic after we get each age, because
;;; we can only see a stream of datoms. That stream doesn't necessarily
;;; need to contain the corresponding name.
(defn prepare-rows [db {:keys [datoms]}]
  (let [names (map #(nth % 2) (filter (fn [[e a v t op]] (= a :user/name)) datoms))
        ages (map
              (fn [name]
                (->> name
                     (d/q '[:find ?age :in $ ?name :where
                            [?e :user/name ?name]
                            [?e :user/age ?age]] db)
                     (ffirst)))
              names)]
    {:rows (map (fn [name age] {:name name :age age}) names ages)}))

(def lifecycles
  [{:lifecycle/task :read-datoms
    :lifecycle/calls :onyx.plugin.datomic/read-datoms-calls}
   {:lifecycle/task :prepare-rows
    :lifecycle/calls :datomic-mysql-transfer.core/prepare-rows-calls}
   {:lifecycle/task :write-to-mysql
    :lifecycle/calls :onyx.plugin.sql/write-rows-calls}])

;;; Submit the next job
(def job-id
  (:job-id
   (onyx.api/submit-job
    peer-config
    {:catalog catalog :workflow workflow :lifecycles lifecycles
     :task-scheduler :onyx.task-scheduler/balanced})))

;;; Block until the job is done, then check MySQL
(onyx.api/await-job-completion peer-config job-id)

(prn "MySQL...")
(clojure.pprint/pprint (jdbc/query conn-pool [(format "SELECT name, age FROM %s" (name copy-table))]))

;;; Aaaaaand stop!
(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-peer-group peer-group)

(onyx.api/shutdown-env env)
