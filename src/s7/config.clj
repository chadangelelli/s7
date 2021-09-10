(ns s7.config
  (:require [cprop.core :refer (load-config)]
            [cprop.source :as source]
            [environ.core :as environ]
            [buddy.core.nonce :as nonce]
            [s7.errors :refer (err)]
            [clojure.pprint :refer (pprint)]))

(defonce env_ (atom nil))

(defn get-config
  "Returns current config if no k provided.
  Returns value at k if provided."
  {:added "0.1"}
  ([] @env_)
  ([x]
   (if (instance? java.util.regex.Pattern x)
     (into {} (filter (fn [[k v]] (re-find x (name k))) @env_))
     (get @env_ x))))

(defn set-config!
  "Resets config atom if given a map.
  Sets k to v in config atom if given [k v].
  Returns updated config."
  {:added "0.1"}
  ([m]   (reset! env_ m))
  ([k v] (swap! env_ assoc k v)))

(defn init-config!
  ([] (init-config! {}))
  ([args]
   (let [
         env (load-config
              :file "config.edn"
              :merge [environ/env
                      (source/from-system-props)
                      (source/from-env)
                      args])]
     (reset! env_ env))))
