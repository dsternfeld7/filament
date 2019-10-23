#!/usr/bin/env bash

set -e

echo "Test action"

# - name: Test Google Cloud upload
#   run: |
#     WORKFLOW_OS=`echo \`uname\` | sed "s/Darwin/mac/" | tr [:upper:] [:lower:]`
#     source build/$WORKFLOW_OS/install_gcloud_sdk.sh
#     gcloud --version
#     gsutil --version
#     echo $GCLOUD_KEY_BASE64 | base64 --decode > "$HOME/gcloud-key.json"
#     gcloud auth activate-service-account --key-file=$HOME/gcloud-key.json
#     echo "Hello, world" > test-${WORKFLOW_OS}.txt
#     gsutil cp test-${WORKFLOW_OS}.txt gs://filament-build/
#   env:
#     GCLOUD_KEY_BASE64: ${{ secrets.GCLOUD_KEY_BASE64 }}
