(ns boxy.repo-test
  (:use clojure.test
        boxy.repo))


(def ^{:private true} repo 
  {:users                  #{["user" "zone"]}
   :groups                 {["group" "zone"] #{["user" "zone"]}} 
   "/zone"                 {:type        :normal-dir
                            :creator     ["user" "zone"]
                            :create-time 0
                            :modify-time 0
                            :acl         {}
                            :avus        {}}
   "/zone/home"            {:type        :normal-dir
                            :creator     ["user" "zone"]
                            :create-time 0
                            :modify-time 0
                            :acl         {["group" "zone"] :read}
                            :avus        {}}
   "/zone/home/user"       {:type        :normal-dir
                            :creator     ["user" "zone"]
                            :create-time 0
                            :modify-time 0
                            :acl         {["user" "zone"] :write}
                            :avus        {}}
   "/zone/home/user/empty" {:type        :file
                            :creator     ["user" "zone"]
                            :create-time 1
                            :modify-time 2
                            :acl         {}
                            :avus        {}
                            :content     ""}
   "/zone/home/user/file"  {:type        :file
                            :creator     ["user" "zone"]
                            :create-time 3
                            :modify-time 3
                            :acl         {["user" "zone"] :own}
                            :avus        {"has-unit" ["value" "unit"] "unitless" ["value" ""]}
                            :content     "content"}
   "/zone/home/user/link"  {:type        :linked-dir
                            :creator     ["user" "zone"]
                            :create-time 4
                            :modify-time 4
                            :acl         {}
                            :avus        {}}})


(deftest test-contains-entry?
  (is (contains-entry? repo "/zone"))
  (is (not (contains-entry? repo "/missing"))))


(deftest test-get-acl
  (is (empty? (get-acl repo "/zone")))
  (is (= {["group" "zone"] :read} (get-acl repo "/zone/home"))))

  
(deftest test-get-avus
  (let [file-avus (set (get-avus repo "/zone/home/user/file"))]
    (is (contains? file-avus ["has-unit" "value" "unit"]))
    (is (contains? file-avus ["unitless" "value" ""])))
  (is (empty? (get-avus repo "/zone")))
  (is (empty? (get-avus repo "/missing"))))


(deftest test-get-create-time
   (is (= 0 (get-create-time repo "/zone"))))

  
(deftest test-get-creator
   (is (= ["user" "zone"] (get-creator repo "/zone"))))


(deftest test-get-members
  (is (= (get-members repo "/zone") ["/zone/home"])))
  
  
(deftest test-get-modify-time
   (is (= 0 (get-modify-time repo "/zone"))))

  
(deftest test-get-permission
  (is (nil? (get-permission repo "/zone" "user" "zone")))
  (is (nil? (get-permission repo "/zone/home" "user" "zone")))
  (is (= :read (get-permission repo "/zone/home" "group" "zone")))
  (is (= :write (get-permission repo "/zone/home/user" "user" "zone")))
  (is (= :own (get-permission repo "/zone/home/user/file" "user" "zone"))))

  
(deftest test-get-type?
  (is (= :normal-dir (get-type repo "/zone")))
  (is (= :linked-dir (get-type repo "/zone/home/user/link")))
  (is (= :file (get-type repo "/zone/home/user/file"))))

  
(deftest test-get-user-groups
  (is (= '(["group" "zone"]) (get-user-groups repo "user" "zone")))
  (is (empty? (get-user-groups repo "unknown" "unknown"))))


(deftest test-user-exists?
  (is (user-exists? repo "user" "zone"))
  (is (not (user-exists? repo "unknown" "unknown"))))


(deftest test-add-avu
  (let [repo' (-> repo 
                (add-avu "/zone/home/user/file" "attr" "val" "")
                (add-avu "/zone/home/user/file" "has-unit" "new-val" "new-unit"))
        avus  (:avus (get repo' "/zone/home/user/file"))]
    (is (= ["val" ""] (get avus "attr")))
    (is (= ["new-val" "new-unit"] (get avus "has-unit")))))


(deftest test-add-file
  (let [repo' (add-file repo "/zone/home/user/new")
        entry (get repo' "/zone/home/user/new")]
    (is (= :file (:type entry)))
    (is (empty? (:acl entry)))
    (is (empty? (:avus entry)))
    (is (empty? (:content entry)))))


(deftest test-write-to-file
  (testing "empty file"
    (let [repo' (write-to-file repo "/zone/home/user/empty" "junk" 0)]
      (is (= "junk" 
             (:content (get repo' "/zone/home/user/empty"))))))
  (testing "append to no empty"
    (let [repo' (write-to-file repo "/zone/home/user/file" "junk" 7)]
      (is (= "contentjunk"
             (:content (get repo' "/zone/home/user/file"))))))
  (testing "overwrite"
    (let [repo' (write-to-file repo "/zone/home/user/file" "junk" 0)]
      (is (= "junkent"
             (:content (get repo' "/zone/home/user/file"))))))             
  (testing "offset past end"
    (let [repo' (write-to-file repo "/zone/home/user/empty" "junk" 1)]
      (is (= " junk"
             (:content (get repo' "/zone/home/user/empty")))))))
