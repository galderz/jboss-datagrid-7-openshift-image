#!/bin/bash

set -x

delete_stateful_set() {
  local service=$1

  grace=$(kubectl get pods $service-0 --template '{{.spec.terminationGracePeriodSeconds}}')
  kubectl delete statefulset -l application=$service
  sleep $grace
  kubectl delete pvc -l application=$service
}

# This name is hardcoded in Makefile. We need a fixed name to push it to local OpenShift registry
IMAGE_NAME=${image:-jboss-datagrid-7/datagrid72-openshift}

echo "---- Clearing up (any potential) leftovers ----"
oc delete all,secrets,sa,templates,configmaps,daemonsets,clusterroles,rolebindings,serviceaccounts --selector=template=cache-service || true
oc delete all,secrets,sa,templates,configmaps,daemonsets,clusterroles,rolebindings,serviceaccounts --selector=template=datagrid-service || true
oc delete template cache-service || true
oc delete template datagrid-service || true
delete_stateful_set caching-service
delete_stateful_set datagrid-service

echo "---- Creating Caching Service for test ----"
echo "Current dir $PWD"
echo "Using image $IMAGE_NAME"

oc create -f ../cache-service-template.json

oc process cache-service -p IMAGE=${IMAGE_NAME} -p APPLICATION_USER=test -p APPLICATION_USER_PASSWORD=test | oc create -f -

echo "---- Creating Datagrid Service for test ----"
echo "Current dir $PWD"
echo "Using image $IMAGE_NAME"

oc create -f ../datagrid-service-template.json

oc process datagrid-service -p IMAGE=${IMAGE_NAME} -p APPLICATION_USER=test -p APPLICATION_USER_PASSWORD=test | oc create -f -
