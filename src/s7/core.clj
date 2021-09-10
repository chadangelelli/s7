(ns s7.core
  (:require
   [crux.api :as crux]

   [s7.config :as config]
   [s7.model :as model]
   [s7.db :as db]
   ))

(model/set-schema!
   {:name :Transaction
    :description "Transaction"
    :validation :malli
    :form [:map
           [:address/street string?]
           [:address/city string?]
           [:address/state string?]
           [:address/zip string?]]})

(def test-data
  [{:s7/user "chris@shorttrack.io"
    :s7/schema :User
    :s7/data {:user/first_name "Chris"
              :user/last_name "Hacker"
              :user/email "chris@shorttrack.io"}}

   {:s7/user "steve@shorttrack.io"
    :s7/schema :User
    :s7/data {:user/first_name "Steve"
              :user/last_name "Hargraves"
              :user/email "steve@shorttrack.io"}}

   {:s7/user "chad@shorttrack.io"
    :s7/schema :Transaction
    :s7/data {:address/street "1 Chad Test Ave."
              :address/city "Pittsburgh"
              :address/state "PA"
              :address/zip "16023"}}

   {:s7/user "chris@shorttrack.io"
    :s7/schema :Transaction
    :s7/data {:address/street "2 Chris Test St."
              :address/city "Chicago"
              :address/state "IL"
              :address/zip "60612"}}

   {:s7/user "steve@shorttrack.io"
    :s7/schema :Transaction
    :s7/data {:address/street "3 Steve Test St."
              :address/city "Chicago"
              :address/state "IL"
              :address/zip "60600"}
    :s7/grant {}}
   ])

(defn add-test-data
  []
  (db/start-db)
  (doseq [doc test-data]
    (db/put doc)))
