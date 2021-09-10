(ns s7.time
  (:require [java-time :as jt]))

(defn now
  []
  (jt/format :iso-instant (jt/zoned-date-time)))
