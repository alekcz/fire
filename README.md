# fire

A lightweight clojure client for Firebase based using the REST API. Basically [Charmander](https://github.com/alekcz/charmander) 2.0  

# Status

![master](https://github.com/alekcz/fire/workflows/master/badge.svg) [![codecov](https://codecov.io/gh/alekcz/fire/branch/master/graph/badge.svg?token=ahELyNhNVg)](https://codecov.io/gh/alekcz/fire)      

_Pre-alpha_

## Prerequisites

For konserve-fire you will need to create a Realtime Database on Firebase and store the service account credentials in the an environment variable. The default variable is `GOOGLE_APPLICATION_CREDENTIALS`

## Usage

`[alekcz/fire "0.1.0"]`

Write to the specified location (will overwrite any existing data):

```clojure
    (fire/write! "protected-db-name" "/path" {:map "with data"} auth)
    (fire/write! "public-db-name" "/path" {:map "with data"})
    ; => {:map "with data"}
```

Read data from the specified location:

```clojure
    (fire/read "protected-db-name" "/path" auth)
    (fire/read "public-db-name" "/path")
    ; => {:map "with data"}
```
 
 Update data at the specified location (only updates the specified fields):
 
```clojure
     (fire/update! "protected-db-name" "/path" {:more "data"} auth)
     (fire/update! "public-db-name" "/path" {:more "data"})
     ; => {:map "with data" :more "data"}
```
 
Add data at the specified location with an automatically generated key:

```clojure
     (fire/push! "protected-db-name" "/path" {:map "with data"} auth)
     (fire/push! "public-db-name" "/path" {:map "with data"})
     ; => {"name" "-IoZ3DZlTTQIkR0c7iVK"}
```
      
Delete at the specified locations:

```clojure
    (fire/delete! "protected-db-name" "/path" auth)
    (fire/delete! "public-db-name" "/path")
    ; => nil
```

Query data at the specified locations:
Note that if the child key is not indexed firebase will respond with error 400. Also `:orderBy` is required for all queries. 
See the Firebase [query docs](https://firebase.google.com/docs/database/rest/retrieve-data#section-rest-filtering) for more info.
```clojure
    (fire/read "protected-db-name" "/path" auth {:orderBy "child-key" :startAt 10 :endAt 50})
    (fire/read "protected-db-name" "/path" auth {:orderBy "child-key" :equalTo 10})
    (fire/read "public-db-name" "/path" nil {:orderBy "child-key" :limitToFirst 10})
    (fire/read "public-db-name" "/path" nil {:orderBy "child-key" :limitToLast 3})
    
    ; => nil
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
