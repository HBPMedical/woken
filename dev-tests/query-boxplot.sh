#!/bin/bash

http -v --timeout 180 POST localhost:8087/mining \
         variables:='[{"code":"LeftAmygdala"}]' \
         grouping:='[{"code":"COLPROT"}]' \
         covariables:='[{"code":"AGE"}]' \
         filters:='[]' \
         algorithm:='{"code":"statisticsSummary", "name": "Statistics Summary", "parameters": []}'

# boxplot is an alias for statisticsSummary
