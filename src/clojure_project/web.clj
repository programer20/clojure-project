(ns clojure-project.web
  (:require [compojure.route :as route]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.util.http-response :as http])
  (:use compojure.core
        ring.adapter.jetty
        ring.middleware.json
        ring.util.response))

(defn allow-cross-origin
  ([handler]
   (allow-cross-origin handler "*"))
  ([handler allowed-origins]
   (fn [request]
     (if (= (request :request-method) :options)
       (-> (http/ok)
           (assoc-in [:headers "Access-Control-Allow-Origin"] allowed-origins)
           (assoc-in [:headers "Access-Control-Allow-Methods"] "GET,POST,DELETE")
           (assoc-in [:headers "Access-Control-Allow-Headers"] "X-Requested-With,Content-Type,Cache-Control,Origin,Accept,Authorization")
           (assoc :status 200)
           )
       (-> (handler request)
           (assoc-in [:headers "Access-Control-Allow-Origin"] allowed-origins))))
   )
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

(defn construct-url
  [req]
  (str "https://www.nekretnine.rs/stambeni-objekti/stanovi/izdavanje-prodaja/izdavanje"
       (construct-city-part req) (construct-city req) (construct-price req) "/lista/po-stranici/10/")
  )

(defroutes my_routes
           (GET "/" [] (response "Pocetna"))
           (GET "/hello" [] {:status  200
                             :headers {"Content-Type" "application/json"}
                             :body    "Hello"})
           (GET "/rest" [] (response [{:ime "Pera" :prezime "Peric"} {:ime "Mika" :prezime "Mikic"}]))
           (POST "/vue" [] (fn [req] (construct-url (:body req))))
           (route/resources "/"))

(def app (wrap-json-body (wrap-cors (wrap-json-response (allow-cross-origin my_routes)) :access-control-allow-methods #{:get :post :delete :options}
                                    :access-control-allow-headers #{:accept :content-type}
                                    :access-control-allow-origin [#"http://localhost:8080"]
                                    ) {:keywords? true}))

(defn -main
  [& args]
  (run-jetty app {:port 3000}))