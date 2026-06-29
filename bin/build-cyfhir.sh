#!/usr/bin/env bash

set -e

(
  cd cyfhir
  [ ! -d "./plugins" ] && mkdir ./plugins
)
