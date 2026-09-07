#!/usr/bin/env bash

# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# This script builds docker images when run from a release of Kyuubi
# with Kubernetes support.

function error {
  echo "$@" 1>&2
  exit 1
}

if [ -z "${KYUUBI_HOME}" ]; then
  KYUUBI_HOME="$(cd "`dirname "$0"`"/..; pwd)"
fi
KYUUBI_IMAGE_NAME="kyuubi"

function is_dev_build {
  [ ! -f "$KYUUBI_HOME/RELEASE" ]
}

if is_dev_build; then
  cat <<EOF
  Current docker-image-tool.sh only support build docker image from binary package.
  You can download Kyuubi binary package from kyuubi web-sit https://kyuubi.apache.org/releases.html.
  Or you can build binary from source code with $KYUUBI_HOME/build/dist, find more detail about build binary in that script

EOF
  exit 1
fi

function image_ref {
  local image="$1"
  local add_repo="${2:-1}"
  if [ $add_repo = 1 ] && [ -n "$REPO" ]; then
    image="$REPO/$image"
  fi
  if [ -n "$TAG" ]; then
    image="$image:$TAG"
  else
    image="$image:$KYUUBI_VERSION"
  fi
  echo "$image"
}

function img_ctx_dir {
  echo "$KYUUBI_HOME"
}

function build {
  local BUILD_ARGS
  local KYUUBI_ROOT="$KYUUBI_HOME"
  local BUILD_ARGS=(${BUILD_PARAMS})

  # Verify that the Docker image content directory is present
  if [ ! -d "$KYUUBI_ROOT/docker" ]; then
    error "Can't find Kyuubi docker context $KYUUBI_ROOT/docker, please check whether the binary package is complete."
  fi

  # Verify that that Kyuubi need JARS is present
  local TOTAL_JARS=$(ls $KYUUBI_ROOT/jars/kyuubi-* | wc -l)
  TOTAL_JARS=$(( $TOTAL_JARS ))
  if [ "${TOTAL_JARS}" -eq 0 ]; then
    error "Cannot find Kyuubi JARs. Please check whether the binary package is complete."
  fi

  if [ -n "$KYUUBI_UID" ]; then
    BUILD_ARGS+=(--build-arg kyuubi_uid=$KYUUBI_UID)
  fi

  local BASEDOCKERFILE=${BASEDOCKERFILE:-"docker/Dockerfile"}

  (cd $(img_ctx_dir base) && docker build $NOCACHEARG "${BUILD_ARGS[@]}" \
    -t $(image_ref $KYUUBI_IMAGE_NAME) \
    -f "$BASEDOCKERFILE" .)
  if [ $? -ne 0 ]; then
    error "Failed to build Kyuubi JVM Docker image, please refer to Docker build output for details."
  fi

  echo "Build complete: $(image_ref $KYUUBI_IMAGE_NAME)"
}

function usage {
  cat <<EOF
Usage: $0 [options]

Builds the built-in Kyuubi Docker image.

Options:
  -f                    Dockerfile to build for JVM based Jobs. By default builds the Dockerfile shipped with Kyuubi.
  -r                    Repository address.
  -i                    Image name. Defaults to "kyuubi".
  -t                    Tag to apply to the built image.
  -n                    Build docker image with --no-cache
  -u                    UID to use in the USER directive to set the user the main Kyuubi process runs as inside the
                        resulting container
  -b                    Build arg to build or push the image. For multiple build args, this option needs to
                        be used separately for each build arg.

Examples:

  - Build image with tag "v1.8.1" to docker.io/myrepo
    $0 -r docker.io/myrepo -t v1.8.1

  - Build with custom base image
    $0 -r docker.io/myrepo -t v1.8.1 -b BASE_IMAGE=eclipse-temurin:17-jdk-focal

EOF
}

if [[ "$*" = *--help ]] || [[ "$*" = *-h ]]; then
  usage
  exit 0
fi

REPO=
TAG=
BASEDOCKERFILE=
NOCACHEARG=
BUILD_PARAMS=
KYUUBI_UID=
while getopts f:r:t:i:nb:u: option
do
 case "${option}"
 in
 f) BASEDOCKERFILE=$(resolve_file ${OPTARG});;
 r) REPO=${OPTARG};;
 t) TAG=${OPTARG};;
 i) KYUUBI_IMAGE_NAME=${OPTARG};;
 n) NOCACHEARG="--no-cache";;
 b) BUILD_PARAMS=${BUILD_PARAMS}" --build-arg "${OPTARG};;
 u) KYUUBI_UID=${OPTARG};;
 esac
done

. "${KYUUBI_HOME}/bin/load-kyuubi-env.sh"
build
