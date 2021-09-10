#!/bin/bash

script_dir=$(dirname "$0")
cd $script_dir

clj -M:cider-clj
