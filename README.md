# Duct Simple ClojureScript Compiler [![Build Status](https://github.com/duct-framework/compiler.cljs.simple/actions/workflows/test.yml/badge.svg)](https://github.com/duct-framework/compiler.cljs.simple/actions/workflows/test.yml)

[Integrant][] methods for compiling ClojureScript using functions from
`cljs.build.api`. Designed for the [Duct][] framework, but can be used
in any Integrant configuration.

This compiler supports hot reloading when resumed from a suspended
state. When using the `:output-to` option, it will generate a script
that loads dependencies via `eval` with the `sourceURL` pragma, which
is more efficient than the default `document.write` method.

[integrant]: https://github.com/weavejester/integrant
[duct]: https://github.com/duct-framework/duct

## Installation

Add the following dependency to your deps.edn file:

    org.duct-framework/compiler.cljs.simple {:mvn/version "0.1.0-SNAPSHOT"}

Or to your Leiningen project file:

    [org.duct-framework/compiler.cljs.simple "0.1.0-SNAPSHOT"]

## Usage

The `:duct.compiler.cljs.simple/build` key takes the same options as
the `cljs.build.api` function, detailed in the [ClojureScript Compiler
Options][compiler-opts] document. It also takes an extra `:logger`
option, which can be connected to a [Duct logger][].

Here's an example of a typical development build configuration:

```edn
{:duct.compiler.cljs.simple/build
 {:asset-path "/js"
  :logger #ig/ref :duct/logger
  :output-dir "target/cljs/js"
  :output-to "target/cljs/js/client.js"
  :optimizations :none
  :preloads [duct.client.repl.simple.preload]
  :main test-project.client}}
```

The preload adds support for hot reloading and REPL evaluation. This
needs to be paired with the `:duct.compiler.cljs.simple/server` key,
which sets up a websocket server for handling reloading on the server
side. This is only used while developing.

The `:duct.compiler.cljs.simple/server` key takes the following
options:

- `:build`  - a ref linking to a `:duct.compiler.cljs.simple/build` key
- `:logger` - a Duct logger
- `:port`   - the port to run the server on (defaults to 9000)

For example:

```edn
{:duct.compiler.cljs.simple/build
 {...}
 :duct.compiler.cljs.simple/server
 {:build #ig/ref :duct.compiler.cljs.simple/build
  :logger #ig/ref :duct/logger}}}
```

When the system is suspended then resumed, typically via a `(reset)`,
any changed files will be sent to the browser to be reloaded in place.
Arbitrary ClojureScript may also be sent to the browser using the
`duct.compiler.cljs.simple/eval-cljs` function.

```clojure
(require '[duct.compiler.cljs.simple :as simple])

(simple/eval-cljs
 (-> system ::simple/server :sessions deref first)
 '(js/console.log "Hello World"))
```

[compiler-opts]: https://clojurescript.org/reference/compiler-options
[duct logger]: https://github.com/duct-framework/logger

## License

Copyright © 2026 James Reeves

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
