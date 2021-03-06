(ns boxy.core
  "This namespace provides an implementation of the Jargon classes for interacting with a mock up of 
   an iRODS repository. The function mk-mock-proxy should normally be the entry point."
  (:require [clojure-commons.file-utils :as file]
            [boxy.jargon-if :as jargon]
            [boxy.repo :as repo])
  (:import [java.util Date
                      List]
           [org.irods.jargon.core.exception DataNotFoundException
                                            FileNotFoundException]
           [org.irods.jargon.core.protovalues FilePermissionEnum
                                              UserTypeEnum]
           [org.irods.jargon.core.pub CollectionAO
                                      CollectionAndDataObjectListAndSearchAO
                                      DataObjectAO
                                      IRODSAccessObjectFactory
                                      IRODSFileSystemAO
                                      QuotaAO
                                      UserAO
                                      UserGroupAO]
           [org.irods.jargon.core.pub.domain ObjStat
                                             ObjStat$SpecColType
                                             User
                                             UserFilePermission
                                             UserGroup]
           [org.irods.jargon.core.pub.io FileIOOperations
                                         IRODSFile
                                         IRODSFileFactory
                                         IRODSFileOutputStream]
           [org.irods.jargon.core.query CollectionAndDataObjectListingEntry
                                        CollectionAndDataObjectListingEntry$ObjectType
                                        MetaDataAndDomainData
                                        MetaDataAndDomainData$MetadataDomain]))


(defn- map-perm
  [repo-perm]
  (condp = repo-perm
    :own   FilePermissionEnum/OWN
    :read  FilePermissionEnum/READ
    :write FilePermissionEnum/WRITE
    nil    FilePermissionEnum/NONE))


(defn- get-acl
  [repo path]
  (letfn [(mk-perm [[[user zone] perm]] (UserFilePermission. user 
                                                             "dbid"
                                                             (map-perm perm) 
                                                             UserTypeEnum/RODS_UNKNOWN 
                                                             zone))]
    (map mk-perm (repo/get-acl repo path))))


(defn- directory?
  [repo path]
   (let [type (repo/get-type repo (file/rm-last-slash path))]
     (or (= :normal-dir type) (= :linked-dir type))))
  
  
(defn- file?
  [repo path]
  (= :file (repo/get-type repo path)))


(defrecord MockFile [repo-ref account path]
  ^{:doc
    "This is description of a file or directory in a mutable boxy repository.

     Parameters:
       repo-ref - An atom containing the repository content.
       account - The IRODSAccount identifying the connected user.
       path - The path to file or directory.

     NOTE:  This has been implemented only enough to support current testing."}
  IRODSFile
  
  (close [_])
  
  (delete [_]
    "TODO: implement this method"
    false)
  
  (createNewFile [_]
    (if (repo/contains-entry? @repo-ref path)
      false
      (do
        (reset! repo-ref (repo/add-file @repo-ref path))
        true)))
  
  (createNewFileCheckNoResourceFound [_]
    "NOTE:  The check is not performed."
    (.createNewFile _))
  
  (exists [_]
    (repo/contains-entry? @repo-ref (file/rm-last-slash path)))
  
  (getAbsolutePath [_]
    (file/rm-last-slash path))
  
  (getFileDescriptor [_]
    3)
  
  (getParent [_]
    (file/rm-last-slash (file/dirname path)))
  
  (initializeObjStatForFile [_]
    "NOTE:  The returned ObjStat instace only indentifes the SpecColType of the
     entry the path points to."
    (doto (ObjStat.)
      (.setSpecColType (condp = 
                              (repo/get-type @repo-ref (file/rm-last-slash path))
                         :normal-dir  ObjStat$SpecColType/NORMAL
                         :linked-dir  ObjStat$SpecColType/LINKED_COLL
                                      nil))))
        
  (isDirectory [_]
    (directory? @repo-ref path))
  
  (isFile [_]
    (file? @repo-ref path))
  
  (mkdirs [_]
    "TODO: implement this method"
    false)
  
  (reset [_]
    #_"TODO implement this method"))


(defrecord MockFileIOOperations [repo-ref account file]
  ^{:doc
    "An implementation of file I/O operations for a given file in a mutable 
     boxy repository.

     Parameters:
       repo-ref - An atom containing the repository content.
       account - The IRODSAccount identifying the connected user.
       file - The IRODSFile describing the file of interest..

     NOTE:  This assumes that the String is an 8 bit character set.
     NOTE:  This has been implemented only enough to support current testing."}
  FileIOOperations
  
  (write [_ fd buffer offset length]
    ^{:doc "This assures that buffer is a character array with 8 bit characters."}
    (let [path        (.getAbsolutePath file)
          add-content (String. buffer offset length)]
      (reset! repo-ref (repo/write-to-file @repo-ref path add-content offset))
      length)))


(defrecord MockFileFactory [file-ctor io-ops-ctor]
  ^{:doc
    "This is factory for producing java.io-like objects for manipulating fake 
     iRODS data.  It should normally be constructed by the mk-mock-file-factory 
     function.  If custom implementations of IRODSFile or FileIOOperations are 
     needed, they can be provided by passing the relevant constructors directly 
     to the MockFileFactory constructor.

     Parameters:
       file-ctor - This is a function that constructs an IRODSFile for a given
         path.  It takes an absolute path as its only argument.
       io-ops-ctor - This is a function that constructs a FileIOOperations for a 
         given IRODSFile object.  It takes an IRODSFile as its only argument.

     NOTE:  This has been implemented only enough to support current testing."}
  IRODSFileFactory

  (^IRODSFile instanceIRODSFile [_ ^String path]
    (file-ctor path))

  (^IRODSFileOutputStream instanceIRODSFileOutputStream [_ ^IRODSFile file]
    (proxy [IRODSFileOutputStream] [file (io-ops-ctor file)])))


(defn mk-mock-file-factory
  "Constructs a MockFileFactory backed by a mutable boxy repository.
 
   Parameters:
     repo-ref - An atom containing the content.
     acnt - The IRODSAccount object representing the connected user.
  
   Returns:
     It returns a MockFileFactory instance."
  [repo-ref acnt]
  (->MockFileFactory (partial ->MockFile repo-ref acnt)
                     (partial ->MockFileIOOperations repo-ref acnt)))
  
  
(defrecord MockFileSystemAO [repo-ref account oom?]
  ^{:doc
    "This is an iRODS file system accesser that is backed by mutable repository content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user.
       oom? - Should the mock AO object pretend to be out of memory?"}
  IRODSFileSystemAO
  
  (getListInDir [_ file]
    (when oom? (throw (OutOfMemoryError.)))
    (when-not (.exists file)
      (throw (FileNotFoundException. (str (.getAbsolutePath file) " not found"))))
    (map file/basename 
         (repo/get-members @repo-ref 
                           (if (.isDirectory file)
                             (.getAbsolutePath file)
                             (.getParent file))))))


(defn- ->entry 
  [repo entry-path page-idx last?]
  (let [entry-type (repo/get-type repo entry-path)
        creator    (repo/get-creator repo entry-path)]
    (doto (CollectionAndDataObjectListingEntry.)
      (.setParentPath (file/dirname entry-path))
      (.setPathOrName (if (= :file entry-type)
                        (file/basename entry-path)
                        entry-path))
      (.setObjectType (if (= :file entry-type) 
                        CollectionAndDataObjectListingEntry$ObjectType/DATA_OBJECT
                        CollectionAndDataObjectListingEntry$ObjectType/COLLECTION))
      (.setSpecColType (condp = entry-type
                         :file       nil
                         :linked-dir ObjStat$SpecColType/LINKED_COLL
                         :normal-dir ObjStat$SpecColType/NORMAL))
      (.setOwnerName (first creator))
      (.setOwnerZone (second creator))
      (.setCreatedAt (Date. (repo/get-create-time repo entry-path)))
      (.setModifiedAt (Date. (repo/get-modify-time repo entry-path)))
      (.setUserFilePermission (get-acl repo entry-path))
      (.setCount (+ 1 page-idx))
      (.setLastResult last?))))


(defn- ->member-entry-page
  [repo parent-path filtered-types start-index]
  (let [max-page-length 5
        paths           (filter #(contains? filtered-types (repo/get-type repo %)) 
                                (repo/get-members repo parent-path))
        page-paths      (->> paths (drop start-index) (take max-page-length))
        last-page?      (<= (count paths) (+ start-index max-page-length))
        page-length     (count page-paths)]
    (for [page-idx (range page-length)]
      (->entry repo
               (nth page-paths page-idx) 
               page-idx 
               (and (= page-length (inc page-idx)) last-page?)))))


(defrecord MockEntryListAO [repo-ref account]
  ^{:doc
    "This is an iRODS entry lister and searcher that is backed by a mutable repository content. This
     version will page in groups of at most five.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user."}
  CollectionAndDataObjectListAndSearchAO
  
  (getCollectionAndDataObjectListingEntryAtGivenAbsolutePath [_ path]
    (when-not (repo/contains-entry? @repo-ref path) (throw (FileNotFoundException. "")))
    (->entry @repo-ref path 0 true))
    
  (listCollectionsUnderPath [_ path start-index]
    (.listCollectionsUnderPathWithPermissions _ path start-index))

  (listCollectionsUnderPathWithPermissions [_ path start-index]
    (->member-entry-page @repo-ref path #{:linked-dir :normal-dir} start-index))
  
  (listDataObjectsUnderPath [_ path start-index]
    (.listDataObjectsUnderPathWithPermissions _ path start-index))
  
  (listDataObjectsUnderPathWithPermissions [_ path start-index]
    (->member-entry-page @repo-ref path #{:file} start-index)))
  

(defrecord MockCollectionAO [repo-ref account]
  ^{:doc
    "This is a collections accesser that is backed by mutable repository content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user."}
  CollectionAO
  
  (getPermissionForCollection [_ path user zone]
    "FIXME:  This doesn't check to see if the path points to a data object."
    (map-perm (repo/get-permission @repo-ref (file/rm-last-slash path) user zone)))
  
  (listPermissionsForCollection [_ path]
    (when (= \/ (last path)) (throw (FileNotFoundException. "")))
    (when-not (directory? @repo-ref path) (throw (FileNotFoundException. "")))
    (get-acl @repo-ref path)))


(defrecord MockDataObjectAO [repo-ref account]
  ^{:doc
    "This is a data objects accesser that is backed by mutable repository 
     content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user.
    
     NOTE:  This has been implemented only enough to support current testing."}
  DataObjectAO
  
  (addAVUMetadata [_ path avu]
    (reset! repo-ref 
            (repo/add-avu @repo-ref 
                          (file/rm-last-slash path) 
                          (.getAttribute avu) 
                          (.getValue avu) 
                          (.getUnit avu))))
    
  (^List findMetadataValuesForDataObject [_ ^String path]
    "NOTE:  I don't know what a domain object Id or unique name are, so I'm 
            using the path for both."
    "FIXME:  This doesn't check to see if the path points to a collection."
    (let [path' (file/rm-last-slash path)]
      (map #(MetaDataAndDomainData/instance 
              MetaDataAndDomainData$MetadataDomain/DATA
              path'
              path'
              (first %)
              (second %)
              (last %))
           (repo/get-avus @repo-ref path'))))
  
  (getPermissionForDataObject [_ path user zone]
    "FIXME:  This doesn't check to see if the path points to a collection."
    (map-perm (repo/get-permission @repo-ref (file/rm-last-slash path) user zone)))
 
  (listPermissionsForDataObject [_ path]
    (when-not (file? @repo-ref path) (throw (FileNotFoundException. "")))
    (get-acl @repo-ref path))
    
  (setAccessPermissionOwn [_ zone path user])
    #_"TODO: implement")

  
(defrecord MockGroupAO [repo-ref account]
  ^{:doc
    "This is a groups accesser that is backed by mutable repository content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user.

     NOTE: This has been implemented only enough to support current testing."}  
  UserGroupAO
  
  (findUserGroupsForUser [_ user]
    "NOTE: userGroupId has not been set."
    (letfn [(mk-UserGroup [[group zone]] 
                          (doto (UserGroup.)
                            (.setUserGroupName group)
                            (.setZone zone)))]
      (map mk-UserGroup (repo/get-user-groups @repo-ref user (.getZone account))))))

  
(defrecord MockUserAO [repo-ref account]
  ^{:doc
    "This is a users accesser that is backed by mutable repository content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user.

     NOTE:  This has been implemented only enough to support current testing."}  
  UserAO
  
  (findByName [_ name]
    "NOTE: This function does not set the comment, createTime, Id, Info, modifyTime, userDN or 
           userType fields in the returned User object."
    (if-not (repo/user-exists? @repo-ref name (.getZone account))
      (throw (DataNotFoundException. "unknown user"))
      (doto (User.)
        (.setName name)
        (.setZone (.getZone account))))))


(defrecord MockQuotaAO [repo-ref account]
  ^{:doc
    "This is a quotas accesser that is backed by mutable repository content.

     Parameters:
       repo-ref - An atom containing the content.
       account - The IRODSAccount identifying the connected user."}
  QuotaAO)


(defrecord MockAOFactory [fs-ctor 
                          entry-list-ctor
                          collection-ctor 
                          data-obj-ctor 
                          group-ctor
                          user-ctor
                          quota-ctor]
  ^{:doc 
    "This is an accesser factory that creates account dependent accessors from 
     provided constructors.  Though not required, it is intended that created 
     accessers are backed by a mutable content map.  

     Factory objects should normally be constructed by calling 
     mk-mock-ao-factory.  If custom implementations of one or more of the 
     underlying accessers are needed,they can be provided by passing the 
     relevant constructors directly to the MockAOFactory constructor.

     Parameters:
       fs-ctor - The constructor for IRODSFileSystemAO objects.  As its only 
         argument, it should accept an IRODSAccount identifying the connected 
         user.
       entry-list-ctor - The constructor for 
         CollectionAndDataObjectListAndSearchAO objects.  As its only argument, 
         it should accept an IRODSAccount identifying the connected user.
       collection-ctor - The constructor for CollectionAO objects.  As its only 
         argument, it should accept an IRODSAccount identifying the connected 
         user.
       data-obj-ctor - The constructor for DataObjectAO objects.  As its only 
         argument, it should accept an IRODSAccount identifying the connected 
         user.
       group-ctor - The constructor for UserGroupAO objects.  As its only 
         argument, it should accept an IRODSAccount identifying the connected 
         user.
       user-ctor - The constructor for UserGAO objects.  As its only argument, 
         it should accept an IRODSAccount identifying the connected user.
       quota-ctor - The constructor for QuotaAO objects.  As its only argument, 
         it should accept an IRODSAccount identifying the connected user.

     NOTE:  This has been implemented only enough to support current testing."}
  IRODSAccessObjectFactory

  (getCollectionAO [_ acnt] 
    (collection-ctor acnt))

  (getCollectionAndDataObjectListAndSearchAO [_ acnt] 
    (entry-list-ctor acnt))

  (getDataObjectAO [_ acnt] 
    (data-obj-ctor acnt))

  (getIRODSFileSystemAO [_ acnt] 
    (fs-ctor acnt))

  (getIRODSGenQueryExecutor [_ acnt])
    ;; NOT IMPLEMENTED
  
  (getQuotaAO [_ acnt] 
    (quota-ctor acnt))

  (getUserAO [_ acnt] 
    (user-ctor acnt))
  
  (getUserGroupAO [_ acnt] 
    (group-ctor acnt)))


(defn mk-mock-ao-factory
  "Constructs a MockAOFactory with mutable repository content.
 
   Parameters:
     repo-ref - An atom containing the content.
  
   Returns:
     It returns a MockProxy instance."
  [repo-ref]
  (->MockAOFactory #(->MockFileSystemAO repo-ref % false)
                   (partial ->MockEntryListAO repo-ref)
                   (partial ->MockCollectionAO repo-ref)
                   (partial ->MockDataObjectAO repo-ref)
                   (partial ->MockGroupAO repo-ref)
                   (partial ->MockUserAO repo-ref)
                   (partial ->MockQuotaAO repo-ref)))
  

(defrecord MockProxy [ao-factory-ctor file-factory-ctor]
  ^{:doc
    "This is an iRODS proxy that provides access to fake iRODS data.  It should
     normally be constructed by the mk-mock-proxy function.  If custom 
     implementations of IRODSAccessObjectFactory or IRODSFileFactory are needed,
     they can be provided by passing the relevant constructors directly to the
     MockProxy constructor.

     Parameters:
       ao-factory-ctor - This is a zero argument function that constructs an
         IRODSAccessObjectFactory.
       file-factory-ctor - This is a function that constructs an 
         IRODSFileFactory for a given iRODS account.  It takes an IRODSAccount 
         as its only argument.

     NOTE:  This has been implemented only enough to support current testing."}
  jargon/IRODSProxy
  
  (close [_])

  (getIRODSAccessObjectFactory [_] 
    (ao-factory-ctor))

  (getIRODSFileFactory [_ acnt] 
    (file-factory-ctor acnt)))
            

(defn mk-mock-proxy
  "Constructs a MockProxy with mutable repository content.
 
   Parameters:
     repo-ref - An atom containing the content.
  
   Returns:
     It returns a MockProxy instance."
  [repo-ref]
  (->MockProxy #(mk-mock-ao-factory repo-ref) 
               (partial mk-mock-file-factory repo-ref)))
