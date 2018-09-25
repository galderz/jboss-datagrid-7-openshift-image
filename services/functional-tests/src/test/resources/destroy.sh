#!/bin/bash

set -x

delete_stateful_set() {
  local service=$1

  grace=$(oc get pods $service-0 --template '{{.spec.terminationGracePeriodSeconds}}')
  oc delete statefulset -l application=$service
  sleep $grace
  oc delete pvc -l application=$service
}

echo "---- Printing out test resources ----"
oc get all,secrets,sa,templates,configmaps,daemonsets,clusterroles,rolebindings,serviceaccounts

echo "---- Describe Pods ----"
oc describe pods

echo "---- Docker PS ----"
docker ps

echo "---- Caching Service logs ----"
oc logs cache-service-0

echo "---- Test Runner logs ----"
oc logs testrunner
oc logs testrunner -c pem-to-truststore

echo "---- EAP Testrunner logs  ----"
oc logs testrunner

echo "---- Clearing up test resources ---"
oc delete all,secrets,sa,templates,configmaps,daemonsets,clusterroles,rolebindings,serviceaccounts,statefulsets --selector=template=cache-service || true
oc delete all,secrets,sa,templates,configmaps,daemonsets,clusterroles,rolebindings,serviceaccounts,statefulsets --selector=template=datagrid-service || true
oc delete template cache-service || true
oc delete template datagrid-service || true
delete_stateful_set caching-service
delete_stateful_set datagrid-service
oc delete service testrunner-http || true
oc delete route testrunner || true
