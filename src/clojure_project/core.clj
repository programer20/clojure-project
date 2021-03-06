(ns clojure-project.core
  (:require [clojure-project.database :as db]
            [clojure-project.email :as email]
            [net.cgrand.enlive-html :as enlive]
            [clojure.string :as str])
  (:use overtone.at-at))

(defn html-data
  [url]
  (-> url
   java.net.URL.
   enlive/html-resource))

(defn get-result-count
  [html-data]
  (Integer/parseInt (first (clojure.string/split (first (get (first
   (enlive/select html-data [:div.d-flex.justify-content-between.col-12.pb-2 :span])) :content)) #" "))))

(defn get-page-count
  [result-count]
  (if (< result-count 20)
    1
    (if (= (quot result-count 20) 0)
      (quot result-count 20)
      (+ (quot result-count 20) 1)))
  )

(defn get-rows
  [html-data]
  (enlive/select html-data [:div.offer-body]))

(defn get-name
  [row]
  (clojure.string/trim (first (get (first (enlive/select row [:h2 :a])) :content))))

(defn get-price
  [row]
  (first (get (first (enlive/select row [:p.offer-price :span])) :content)))

(defn get-surface
  [row]
  (first (get (first (enlive/select row [:p.offer-price.offer-price--invert :span])) :content)))

(defn get-location
  [row]
  (clojure.string/trim (first (get (first (enlive/select row [:p.offer-location])) :content))))

(defn get-href
  [row]
  (str "https://www.nekretnine.rs" (get-in (first (enlive/select row [:h2 :a])) [:attrs :href])))

(defn get-advertiser
  [row]
  (let [result-page (html-data (:href row))]
    (get (first (enlive/select result-page [:figcaption.d-flex.flex-column :div])) :content))
  )

(defn construct-city-part
  [req]
  (if-not (clojure.string/blank? (:cityPart req))
    (str "/deo-grada/" (clojure.string/replace (:cityPart req) " " "-"))))

(defn construct-city
  [req]
  (if-not (clojure.string/blank? (:city req))
    (str "/grad/" (clojure.string/replace (:city req) " " "-"))))

(defn construct-price
  [req]
  (if-not (and (= 0 (:minPrice req)) (= 0 (:maxPrice req)))
    (str "/cena/" (:minPrice req) "_" (:maxPrice req))))

(defn construct-base-url
  [req]
  (str "https://www.nekretnine.rs/stambeni-objekti/stanovi/izdavanje-prodaja/izdavanje"
       (construct-city-part req) (construct-city req) (construct-price req) "/lista/po-stranici/10/")
  )

(defn construct-url-list
  [url page-count]
  (map #(str url "stranica/" %) (range 1 (inc page-count)))
  )

(defn get-results
  [url-list]
  (let [rows (reduce concat '() (map #(get-rows (html-data %)) url-list))]
    (map (fn [row] {:name     (get-name row) :price (get-price row) :surface (get-surface row)
                    :location (get-location row) :href (get-href row)}) rows)))

(defn is-number
  [s]
  (every? #(Character/isDigit %) s))

(defn filter-by-surface
  [results req]
  (if (and (= (:minSurface req) 0) (= (:maxSurface req) 0))
    results
    (if (= (:maxSurface req) 0)
      (filter #(if (is-number (first (str/split (:surface %) #" ")))
                 (>= (Integer/parseInt (first (str/split (:surface %) #" "))) (:minSurface req))) results)
      (filter #(if (is-number (first (str/split (:surface %) #" ")))
                 (and (>= (Integer/parseInt (first (str/split (:surface %) #" "))) (:minSurface req))
                      (<= (Integer/parseInt (first (str/split (:surface %) #" "))) (:maxSurface req)))
                 ) results))
    )
  )

(defn agency-ad?
  [apartment]
  (if (nil? (get-advertiser apartment))
    false
    true)
  )

(defn owner-ad?
  [apartment]
  (if (nil? (get-advertiser apartment))
    true
    false)
  )

(defn filter-by-advertiser
  [results req]
  (if (or (= 2 (count (:advertiser req))) (= 0 (count (:advertiser req))))
    results
    (if (= "agencija" (first (:advertiser req)))
      (filter #(agency-ad? %) results)
      (filter #(owner-ad? %) results)))
  )

(defn in?
  "true if coll contains el"
  [coll el]
  (if (= nil (some #(= el %) coll))
    false
    true))

(defn search
  [req]
  (let [base-url (construct-base-url req)]
    (let [page-count (-> base-url
                         html-data
                         get-result-count
                         get-page-count)]
      (let [url-list (construct-url-list base-url page-count)]
        (let [results (get-results url-list)]
          (filter-by-advertiser (filter-by-surface results req) req)))))
  )

(defn get-new-apartments
  [db-apartments web-apartments subscription-id]
  (map #(assoc % :subscription_id subscription-id) (filter #(= nil (some (fn [web-aparment] (= (:href web-aparment) (:href %))) db-apartments)) web-apartments))
  )

(defn start-subscription
  [req]
  (let [db-apartments (db/get-subscription-apartments (:subscription_id req))]
    (let [web-apartments (search req)]
      (let [new-apartments (get-new-apartments db-apartments web-apartments (:subscription_id req))]
        (db/insert-apartments new-apartments)
        (email/send-email (:email (first (db/get-user (:user_id req)))) new-apartments))))
  )

(defn subscribe
  [req]
  (let [subscription-id (get (first (db/subscribe (dissoc (assoc req :agency (in? (:advertiser req) "agencija") :owner (in? (:advertiser req) "vlasnik")) :advertiser))) :generated_key)]
    (start-subscription {:user_id    (:user_id req) :city (:city req) :cityPart (:city_part req) :minPrice (:min_price req) :maxPrice (:max_price req)
                         :minSurface (:min_surface req) :maxSurface (:max_surface req) :subscription_id subscription-id :advertiser (:advertiser req)})))

(defn db-to-web-transformation
  [req]
  {:user_id (:user_id req) :city (:city req) :cityPart (:city_part req) :minPrice (:min_price req) :maxPrice (:max_price req)
   :minSurface (:min_surface req) :maxSurface (:max_surface req) :subscription_id (:id req) :advertiser (if (= (:agency req) (:owner req))
                                                                                                    ["agencija" "vlasnik"]
                                                                                                    (if (= true (:agency req))
                                                                                                      ["agencija"]
                                                                                                      ["vlasnik"]))})

(def my-pool (mk-pool))

(defn on-start-subscriptions
  []
  (every 3600000 (fn [] (let [db-subs (db/get-all-subscriptions)]
                          (let [formated-subs (map #(db-to-web-transformation %) db-subs)]
                            (doseq [i formated-subs] (start-subscription i))))) my-pool)
  )

(defn delete-subscription
  [sub-id]
  (db/delete-subscription-apartments sub-id)
  (db/delete-subscription sub-id))