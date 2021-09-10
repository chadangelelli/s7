(ns s7.db
  (:require
   [xtdb.api :as xt]
   [clojure.java.io :as io]
   [malli.core :as m]

   [s7.config :as config]
   [s7.model :as model]
   [s7.time :as time]
   [s7.utils :as util]
   [s7.errors :refer (err)]
   [s7.validation :as v]))

(declare node_ q)

;; __________________________________________________________________ DATA

;;TODO: optimize get-user (maybe cache all users?)
;;TODO: clean up pull syntax (maybe just desired fields like [:user/email])
(defn get-user
  "Returns user for UUID or email. Optionally takes find pull syntax for
  filtering which fields to return. Default returns all fields.

  ```clojure
  (get-user \"chad.angelelli@gmail.com\")

  (get-user \"c28c3279-7def-4ca4-ae41-bae7bd595e57\")

  (get-user \"chad.angelelli@gmail.com\"
            '(pull ?user [:user/first_name :user/last_name :user/email]))
  ```"
  {:added "0.1"}
  [x & [pull-syntax]]
  (let [k (if (v/valid-email? x) :user/email :xt/id)]
    (-> (xt/q (xt/db node_)
                {:find [(or pull-syntax '(pull ?user [*]))]
                 :where [['?user k x]]})
        first
        first)))

;; __________________________________________________________________ EVENT

(def Event
  {:event/id nil
   :event/start-time nil
   :event/end-time nil
   :event/logged? false
   :event/log-id nil
   :event/result nil})

(defn make-event
  ""
  {:added "0.1"}
  [& [m]]
  (merge Event m {:event/id (util/uuid)
                  :event/start-time (System/nanoTime)}))

(defn make-event-response
  ""
  {:added "0.1"}
  [{:keys [:event/start-time] :as event} & [m]]
  (let [end-time      (System/nanoTime)
        total-time    (- end-time start-time)
        total-time-ms (/ (double total-time) 1000000.0)
        total-time-s  (/ (double total-time-ms) 1000.0)
        event         (merge
                       event
                       m
                       {:event/end-time                end-time
                        :event/total-time              total-time
                        :event/total-time-milliseconds total-time-ms
                        :event/total-time-seconds      total-time-s})]

    ;;TODO: implement logging

    event))

;; __________________________________________________________________ PERMS

(def permissions #{:read :update :state :owner :all})

(defn make-valid-perms
  ""
  {:added "0.1"}
  [msg]
  [:set [:fn {:error/message msg} #(boolean (some permissions [%]))]])

(def valid-user-perms
  (m/schema (make-valid-perms "Invalid user permissions")))

(def valid-team-perms
  (m/schema (make-valid-perms "Invalid team permissions")))

;; __________________________________________________________________ API

(def valid-put-args
  (m/schema
   [:map {:closed true}
    [:s7/user [:fn v/valid-user-target?]]
    [:s7/schema keyword?]
    [:s7/data map?]
    [:s7/grant {:optional true}
     [:map {:closed true}
      [:user {:optional true}
       [:map-of [:fn v/valid-user-target?] valid-user-perms]]
      [:team {:optional true}
       [:map-of integer? valid-team-perms]]]]
    [:s7/revoke {:optional true}
     [:map {:closed true}
      [:user {:optional true}
       [:map-of [:fn v/valid-user-target?] valid-user-perms]]
      [:team {:optional true}
       [:map-of integer? valid-team-perms]]]]
    [:s7/debug? {:optional true} boolean?]]))

(defn invalidate-put-args
  "Returns nil on success or error event"
  {:added "0.1"}
  [event
   user
   {schema-name :s7/schema :keys [:s7/data :s7/revoke] :as args}]
  (let [{:keys [form] :as schema} (model/get-schema schema-name)

        new?       (not (get-in args [:s7/data :xt/id]))
        args-error (or (v/validate valid-put-args args)
                       (and new?
                            revoke
                            "Cannot call revoke when creating new data"))
        data*      (dissoc data :xt/id)
        data-error (v/validate form data*)
        user?      (= schema-name :User)]

    (cond
      ;; ..................... invalid args?
      args-error
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message "Invalid put arguments"
             :error/data {:args args :validation-error args-error}}))

      ;; ..................... invalid schema?
      (not schema)
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message (str "Unknown schema '" schema-name "'")
             :error/data {:args args}}))

      ;; ..................... invalid data?
      data-error
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message (str "Invalid data")
             :error/data {:args args :validation-error data-error}}))

      ;; ..................... duplicate user?
      (and new? user? user)
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message
             (str "Cannot put User '" (:user/email user) "', already exists")
             :error/data {:args args}}))

      ;; ..................... invalid user?
      (and (not user?) (not user))
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message
             (str "Unknown user '" (:s7/user args) "'")
             :error/data {:args args}}))

      ::else-validated
      nil)))

(defn user-perm-key
  [id]
  (keyword "s7.user-perm" (str "u" id)))

(defn make-doc-perms
  [new?
   {user-id :xt/id user-email :user/email}
   schema-name
   doc-id
   doc]
  (let [doc (assoc doc
                   :s7/owner user-id
                   (user-perm-key user-id) #{:all})]
    doc))

(defn put
  ""
  {:added "0.1"}
  [{schema-name :s7/schema
    s7-user :s7/user
    :keys [s7/debug?]
    {:keys [xt/id] :as data} :s7/data
    :as args}]

  (let [event (make-event)
        user (get-user s7-user '(pull ?user [:xt/id :user/email]))]

    (or (invalidate-put-args event user args)
        (let [new? (not id)
              id   (if new? (util/uuid) id)
              user (or user {:xt/id id :user/email (:user/email data)})
              doc  (if-not new?
                     data
                     (assoc data
                            :xt/id id
                            :s7/schema schema-name))
              doc  (make-doc-perms new? user schema-name id doc)

              {:keys [query-error] :as r
               } (if debug?
                   {:debug? true :doc doc}
                   (try
                     (xt/submit-tx node_ [[::xt/put doc]])
                     (catch Throwable t
                       {:query-error (.getMessage t)})))]

          (make-event-response
           event
           (if query-error
             (err {:error/type :s7/query-error
                   :error/fatal? false
                   :error/message "Query error"
                   :error/data {:args args :query-error query-error}})
             {:event/result r}))))))

(defn delete!
  ""
  {:added "0.1"}
  [{:keys [permanently-evict?]}]
  )

(def valid-q-args
  (m/schema
   [:map {:closed true}
    [:s7/user [:fn {:error/message "Invalid user target"} v/valid-user-target?]]
    [:s7/q any?]]))

(defn q
  ""
  {:added "0.1"}
  [{user-id :s7/user :keys [s7/q] :as args}]
  (let [event    (make-event)
        args-err (v/validate valid-q-args args)
        user     (and (not args-err)
                      (get-user user-id
                                '(pull ?user [:xt/id :user/email])))]

    (cond
      args-err
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message "Invalid q arguments"
             :error/data {:args args :validation-error args-err}}))

      (not user)
      (make-event-response
       event
       (err {:error/type :s7/validation-error
             :error/fatal? false
             :error/message (str "Unknown user '" user-id "'")
             :error/data {:args args}}))

      ::else-run-query
      (let [{:keys [query-error] :as r
             } (try
                 (xt/q (xt/db node_) q)

                 (catch java.lang.NullPointerException e
                   {:query-error (str "Null pointer. Check that the "
                                      "database is started and "
                                      "accepting connections.")})

                 (catch Throwable t
                   {:query-error (.getMessage t)}))]

        (make-event-response
         event
         (if query-error
           (err {:error/type :s7/query-error
                 :error/fatal? false
                 :error/message "Query error"
                 :error/data {:args args
                              :query-error query-error}})
           {:event/result r}))))))

;; __________________________________________________________________ CONTROL

;;TODO: add config for starting DB
(defn start-db
  ""
  {:added "0.1"}
  []
  (defonce node_
    (xt/start-node {:s7/rocksdb {:xtdb/module 'xtdb.rocksdb/->kv-store
                                   :db-dir (io/file "__data")}
                      :xtdb/tx-log {:kv-store :s7/rocksdb}
                      :xtdb/document-store {:kv-store :s7/rocksdb}})))

(defn stop-db
  ""
  {:added "0.1"}
  []
  (try
    (.close node_)
    (catch Throwable t)))

(comment

  (start-db)

  (let [test-data
        [{:s7/user "chad@shorttrack.io"
          :s7/schema :User
          :s7/data {:user/first_name "Chad"
                    :user/last_name "Angelelli"
                    :user/email "chad@shorttrack.io"}}]]

    (model/set-schema!
     {:name :Transaction
      :description "Transaction"
      :validation :malli
      :form [:map
             [:address/street string?]
             [:address/city string?]
             [:address/state string?]
             [:address/zip string?]]})

    (doseq [doc test-data]
      (put doc)))


  (clojure.pprint/pprint
   (put {:s7/user "chad@shorttrack.io"
         :s7/schema :User
         :s7/data {:user/first_name "Chad"
                   :user/last_name "Angelelli"
                   :user/email "chad@shorttrack.io"}}))
  )
