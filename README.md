# fire

A lightweight clojure client for Firebase based using the REST API. Basically [Charmander](https://github.com/alekcz/charmander) 2.0  

# Status

![master](https://github.com/alekcz/fire/workflows/master/badge.svg) [![codecov](https://codecov.io/gh/alekcz/fire/branch/master/graph/badge.svg?token=ahELyNhNVg)](https://codecov.io/gh/alekcz/fire) [![Dependencies Status](https://versions.deps.co/alekcz/fire/status.svg)](https://versions.deps.co/alekcz/fire) [![Clojars Project](https://img.shields.io/clojars/v/alekcz/fire.svg)](https://clojars.org/alekcz/fire)      

## Prerequisites

For fire you will need to create a Realtime Database on Firebase and retrieve the service account credentials.

1. Get the json file containing your service account creditials by following the instruction here https://cloud.google.com/docs/authentication/getting-started
2. Copy the contents of your .json into the `GOOGLE_APPLICATION_CREDENTIALS` environment variable. In your `~/.bash_profile` and in Travis CI you should escape your credentials using singe quotes (').

## Usage

`[alekcz/fire "0.5.0"]`

### Interacting with Realtime Database

Creating your auth token

```clojure
(require  '[fire.core :as fire]
          '[fire.auth :as auth])
(def auth (auth/create-token "GOOGLE_APPLICATION_CREDENTIALS"))
```

Write to the specified location (will overwrite any existing data):

```clojure
    (fire/write! "protected-db-name" "/path" {:map "with data"} auth)
    (fire/write! "public-db-name" "/path" {:map "with data"} nil)
    ; => {:map "with data"}
```

Read data from the specified location:

```clojure
    (fire/read "protected-db-name" "/path" auth)
    (fire/read "public-db-name" "/path" nil)
    ; => {:map "with data"}
```
 
 Update data at the specified location (only updates the specified fields):
 
```clojure
     (fire/update! "protected-db-name" "/path" {:more "data"} auth)
     (fire/update! "public-db-name" "/path" {:more "data"} nil)
     ; => {:map "with data" :more "data"}
```
 
Add data at the specified location with an automatically generated key:

```clojure
     (fire/push! "protected-db-name" "/path" {:map "with data"} auth)
     (fire/push! "public-db-name" "/path" {:map "with data"} nil)
     ; => {"name" "-IoZ3DZlTTQIkR0c7iVK"}
```
      
Delete at the specified locations:

```clojure
    (fire/delete! "protected-db-name" "/path" auth)
    (fire/delete! "public-db-name" "/path" nil)
    ; => nil
```

Query data at the specified locations:
Note that if the child key is not indexed firebase will respond with error 400. Also `:orderBy` is required for all queries. 
See the Firebase [query docs](https://firebase.google.com/docs/database/rest/retrieve-data#section-rest-filtering) for more info.
```clojure
    (fire/read "protected-db-name" "/path" auth {:query {:orderBy "child-key" :startAt 10 :endAt 50}})
    (fire/read "protected-db-name" "/path" auth {:query {:orderBy "child-key" :equalTo 10}})
    (fire/read "public-db-name" "/path" nil {:query {:orderBy "child-key" :limitToFirst 10}})
    (fire/read "public-db-name" "/path" nil {:query {:orderBy "child-key" :limitToLast 3}})
    
    ; => nil
```

### Interacting with Firebase Storage

Creating your auth token

```clojure
(require  '[fire.storage :as storage]
          '[fire.auth :as auth])
(def auth (auth/create-token "GOOGLE_APPLICATION_CREDENTIALS"))
```

Upload data or a file to Firebase Storage

```clojure
(spit "path/on/firebase.txt" "this is fire")
(storage/upload! "path/on/firebase.txt" "path/on/disk/storage.txt" "text/plain" auth)
(storage/upload! "path/on/firebase.txt" non-string-data-in-memory  "text/plain" auth)
```
 
Download data to memory or a file from Firebase Storage
 
```clojure
(store/download "path/on/firebase.txt" auth) ;=> "this is fire"
(store/download-to-file "path/on/firebase.txt" "downloads/storage.txt" auth)
(slurp "downloads/storage.txt") ;=> "this is fire"

```
 
Add data at the specified location with an automatically generated key:

```clojure
(store/delete! "path/on/firebase.txt" auth) 
```


## Thanks 
Special thanks to: 
- [@sgrove](https://github.com/sgrove)

## License

Copyright © 2020 Alexander Oloo

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
