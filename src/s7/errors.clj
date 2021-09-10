(ns s7.errors)

(defmacro err
  [m]
  (let [{:keys [line column]} (meta &form)]
    (assoc m
           :s7/error? true
           :error/file *file*
           :error/line line
           :error/column column)))
