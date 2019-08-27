(defproject crux-console "derived-from-git"
  :dependencies
    [[org.clojure/clojure         "1.10.0"]
     [org.clojure/clojurescript   "1.10.520"]
     [reagent                     "0.8.1"]
     [re-frame                    "0.10.8"]
     [garden                      "1.3.9"]
     [bidi                        "2.1.6"]
     [stylefy                      "1.13.3"]
     [medley                       "1.2.0"]
     [day8.re-frame/re-frame-10x  "0.3.3"]
     [funcool/promesa              "2.0.1"]
     [com.andrewmcveigh/cljs-time "0.5.2"]
     [binaryage/oops              "0.6.4"]
     [hiccup                      "1.0.5"]
     [day8.re-frame/test          "0.1.5"]
     [thheller/shadow-cljs        "2.8.52"]
     [com.google.javascript/closure-compiler-unshaded "v20190819"]
     [org.clojure/google-closure-library "0.0-20190213-2033d5d9"]]

  :min-lein-version "2.9.1"
  :repositories [["clojars" "https://repo.clojars.org"]]
  :plugins [;[lein-shadow "0.1.5"] ; nasty guy, deletes original shadow-cljs config if you run it
            [lein-shell  "0.5.0"]]
  :aliases
  {"build"
   ["do"
    ["clean"]
    ["shell" "yarn" "install"]
    ["shell" "node_modules/.bin/shadow-cljs" "release" "app"]       ; compile
    ["shell" "node_modules/.bin/shadow-cljs" "release" "app-perf"]] ; compile production ready performance charts app

   "ebs"
   ["shell" "sh" "./dev/build-ebs.sh"]

   "build-ebs"
   ["do"
    ["build"]
    ["ebs"]]

   "cljs-dev"
   ["do"
    ["shell" "yarn" "install"]
    ["shell" "node_modules/.bin/shadow-cljs" "watch" "app"]]}

  :clean-targets ^{:protect false} ["target" "resources/static/crux-ui/compiled"]

  :source-paths ["src" "../common/src" "test" "node_modules"]

  :resource-paths ["resources"])