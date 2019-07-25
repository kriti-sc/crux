(ns juxt.crux-ui.frontend.views.output.tx-history
  (:require [re-frame.core :as rf]
            [garden.core :as garden]
            [juxt.crux-ui.frontend.views.charts.core :as charts]))

(def ^:private -sub-tx-history (rf/subscribe [:subs.output/tx-history-plot-data]))

(def ^:private root-styles
  [:style
   (garden/css
     [:.tx-history
      {}])])

(def tx-layout
  {:title "Queried entities transactions"
   :xaxis {:title "Valid Time"}
   :yaxis {:title "Transaction time"}})

(defn root []
  [:div.tx-history
    [charts/plotly-wrapper @-sub-tx-history tx-layout]])