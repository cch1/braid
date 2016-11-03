(ns braid.client.register.subs
  (:require
    [clojure.string :as string]
    [re-frame.core :refer [reg-sub]]
    [braid.client.register.user-auth.subs]
    [braid.client.register.create-group.subs]))

(reg-sub
  :register/field-valid?
  (fn [state [_ field]]
    (empty? (get-in state [:fields field :errors]))))

(reg-sub
  :register/field-value
  (fn [state [_ field]]
    (get-in state [:fields field :value])))

(reg-sub
  :register/field-errors
  (fn [state [_ field]]
    (get-in state [:fields field :errors])))

(reg-sub
  :register/field-status
  (fn [state [_ field]]
    (cond
      (get-in state [:fields field :typing?]) :typing
      (get-in state [:fields field :untouched?]) :untouched
      (not (empty? (get-in state [:fields field :errors]))) :invalid
      (< 0 (get-in state [:fields field :validations-left])) :loading
      :else :valid)))

(reg-sub
  :register/sending?
  (fn [state _]
    (state :sending?)))

(reg-sub
  :register/action-mode
  (fn [state _]
    (state :action-mode)))
