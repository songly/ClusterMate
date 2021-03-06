# General

ClusterMate project implements basic components of a multi-node storage system
using single-node storage components provided by
[StoreMate](https://github.com/cowtowncoder/StoreMate) project.
It is designed as a toolkit for building distributed storage systems, either for stand-alone systems or as base for other distributed systems (like message queues).

`ClusterMate` adds hash-based partitioning of content to implement replicated storage.
Although most work is done by clients (base client library implementation included), additional timestamp-based synchronization mechanism is provided for content repair.
Repair mechanism is also used when replacing failed nodes or expanding/shrinking cluster.
Conflict resolution is configurable; details depend on higher-level system (`TransiStore`, for example, has very simple model due to immutable contents).

Project license is [Apache 2.0](http://www.apache.org/licenses/LICENSE-2.0.html).

Check out [Project Wiki](http://github.com/cowtowncoder/ClusterMate/wiki) for more.

# Status

Project is complete to the degree that other systems can be built on it.

Publicly available projects that build on ClusterMate include:

* [TransiStore](https://github.com/FasterXML/TransiStore) is a distributes temporary data storage: useful for things like storing intermediate results for Map/Reduce systems, or for buffering large database result sets.

# Features

Default storage system has following features:

* Data store
 * Immutable: that is, write-once, can not modify
 * No size limitations, storage works for data of any size, although may not be optimal for smallest payloads
 * Metadata/data separation: metadata stored in a database (by StoreMate), data on filesystem or inlined (small entries)
 * Automatic on-the-fly compression of data (not metadata)
* Access
 * Clustered CRUD (PUT, GET/HEAD, DELETE)
 * Streaming (for large data)
 * On-the-fly compression/uncompression
 * Hash-based routing
 * Range queries within specified prefixes (that is, routing can use path prefixes)
* Distributed operation
 * Configurable N-way replication and sharding
 * Automatic server-to-server data repair to handle partial updates and transient outages
 * Clients can choose subset of N for successful operations with lower latency

# Sub-modules

Project is a multi-module Maven project.
Sub-modules are can be grouped in following categories:

* `api`: Basic datatypes shared by client and service modules
* `json`: [Jackson](https://github.com/FasterXML/jackson-databind) converters for core datatypes from 'api' module and StoreMate
or client-server and server-server communication
* `client`:
 * single-node access components ("call")
 * clustered-access ("operation")
 * Specific sub-modules for different HTTP clients:
  * `client-ahc` for implementation using [Async HTTP Client](https://github.com/AsyncHttpClient/async-http-client)
 * `client-jdk` that uses basic JDK-provided `java.net.HttpURLConnection`
* `service`:
 * Configurable implementation of distributed store and matching entry points
* `dropwizard`
 * Deployable container around `service` that exposes CRUD endpoints, entry listing, building on [DropWizard](https://github.com/codahale/dropwizard) framework (which runs on Jersey+Jetty)


