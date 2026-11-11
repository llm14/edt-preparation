#!/bin/sh
# Creates the Temporal namespace the EDT uses, if it does not exist yet.
# A namespace isolates workflows: the EDT's workflows, search attributes and retention live in "edt".
set -eu

ADDRESS=temporal:7233
NAMESPACE=edt

until temporal operator cluster health --address "$ADDRESS" >/dev/null 2>&1; do
  echo "Waiting for the Temporal server..."
  sleep 2
done

if temporal operator namespace describe -n "$NAMESPACE" --address "$ADDRESS" >/dev/null 2>&1; then
  echo "Namespace $NAMESPACE already exists"
else
  temporal operator namespace create -n "$NAMESPACE" --address "$ADDRESS"
  echo "Namespace $NAMESPACE created"
fi
