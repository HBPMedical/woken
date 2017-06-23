#!/bin/bash

http -v --timeout 180 POST localhost:8087/mining/experiment \
         variables:='[{"code":"cognitive_task2"}]' \
         grouping:='[]' \
         covariables:='[{"code":"score_math_course1"}]' \
         filters:='[]' \
         algorithms:='[{"code":"tpot", "name": "python-mip-interactive", "parameters": []}]' \
         validations:='[{"code":"kfold", "name": "kfold", "parameters": [{"code": "k", "value": "2"}]}]'
