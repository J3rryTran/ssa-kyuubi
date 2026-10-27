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


function resolve_file {
  local FILE=$1
  if [ -n "$FILE" ]; then
    local DIR=$(dirname $FILE)
    DIR=$(cd $DIR && pwd)
    FILE="${DIR}/$(basename $FILE)"
  fi
  echo $FILE
}

function img_ctx_dir {
  echo "$KYUUBI_HOME"
}

function build {
  local BUILD_ARGS
  local KYUUBI_ROOT="$KYUUBI_HOME"
  local BUILD_ARGS=(${BUILD_PARAMS})

  # Hadoop native
  rm -rf "$KYUUBI_ROOT/hadoop-native"
  mkdir -p "$KYUUBI_ROOT/hadoop-native"
  if [[ -n "${HADOOP_NATIVE_DIR}" ]]; then
    local HADOOP_NATIVE_SRC="${HADOOP_NATIVE_DIR}"
    # Accept either a Hadoop home (e.g. /path/hadoop-3.3.4) or the lib/native dir itself.
    if [[ -d "${HADOOP_NATIVE_SRC}/lib/native" ]]; then
      HADOOP_NATIVE_SRC="${HADOOP_NATIVE_SRC}/lib/native"
    fi
    if [[ ! -d "${HADOOP_NATIVE_SRC}" ]] || ! ls "${HADOOP_NATIVE_SRC}"/* &>/dev/null; then
      error "Cannot find Hadoop native libraries under ${HADOOP_NATIVE_DIR}"
    fi
    cp -r "${HADOOP_NATIVE_SRC}"/* "$KYUUBI_ROOT/hadoop-native/"
  fi

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

  # BASE_IMAGE is either a JDK image or a local JDK directory. A local JDK is staged under
  # local-jdk/, copied to /opt/java (JAVA_HOME) by the Dockerfile, on top of LOCAL_JDK_OS_IMAGE.
  local BASE_IMAGE="eclipse-temurin:8-jdk-focal (Dockerfile default)"
  local BASE_IMAGE_IDX=
  local i
  for i in "${!BUILD_ARGS[@]}"; do
    if [[ "${BUILD_ARGS[$i]}" = BASE_IMAGE=* ]]; then
      BASE_IMAGE="${BUILD_ARGS[$i]#BASE_IMAGE=}"
      BASE_IMAGE_IDX=$i
    fi
  done
  if [ -z "$BASE_IMAGE" ]; then
    error "Empty value for -b BASE_IMAGE=, please specify an image or a local JDK directory, e.g. -b BASE_IMAGE=eclipse-temurin:17-jdk."
  fi

  rm -rf "$KYUUBI_ROOT/local-jdk"
  mkdir -p "$KYUUBI_ROOT/local-jdk"
  if [[ "$BASE_IMAGE" = /* ]] || [[ "$BASE_IMAGE" = .* ]] || [ -d "$BASE_IMAGE" ]; then
    local LOCAL_JDK_DIR="$BASE_IMAGE"
    if [ ! -d "$LOCAL_JDK_DIR" ]; then
      error "Cannot find local JDK directory '$LOCAL_JDK_DIR' given by -b BASE_IMAGE=."
    fi
    if [ ! -f "$LOCAL_JDK_DIR/bin/java" ]; then
      error "'$LOCAL_JDK_DIR' is not a JDK home: missing bin/java. Point -b BASE_IMAGE= at the folder containing bin/, lib/, ..."
    fi
    cp -a "$LOCAL_JDK_DIR"/. "$KYUUBI_ROOT/local-jdk/" || error "Failed to copy local JDK '$LOCAL_JDK_DIR'."
    local LOCAL_JDK_OS_IMAGE=${LOCAL_JDK_OS_IMAGE:-"ubuntu:24.04"}
    BUILD_ARGS[$BASE_IMAGE_IDX]="BASE_IMAGE=$LOCAL_JDK_OS_IMAGE"
    BUILD_ARGS+=(--build-arg LOCAL_JDK_HOME=/opt/java)
    BASE_IMAGE="$LOCAL_JDK_OS_IMAGE + local JDK '$LOCAL_JDK_DIR' at /opt/java (JAVA_HOME)"
  fi
  echo "Building $(image_ref $KYUUBI_IMAGE_NAME) on base image: $BASE_IMAGE"

  local BASEDOCKERFILE=${BASEDOCKERFILE:-"docker/Dockerfile"}

  (cd $(img_ctx_dir base) && docker build $NOCACHEARG "${BUILD_ARGS[@]}" \
    -t $(image_ref $KYUUBI_IMAGE_NAME) \
    -f "$BASEDOCKERFILE" .)
  if [ $? -ne 0 ]; then
    error "Failed to build Kyuubi JVM Docker image, please refer to Docker build output for details."
  fi
}


function usage {
  cat <<EOF
Usage: $0 [options] [command]
Builds the built-in Kyuubi Docker image.

Commands:
  build       Build image. Use -r to prefix the image name with a repository address.

Options:
  -f                    Dockerfile to build for JVM based Jobs. By default builds the Dockerfile shipped with Kyuubi.
  -r                    Repository address.
  -i                    Image name. Defaults to "kyuubi".
  -t                    Tag to apply to the built image.
  -n                    Build docker image with --no-cache
  -u                    UID to use in the USER directive to set the user the main Kyuubi process runs as inside the
                        resulting container
  -b                    Build arg to build the image. For multiple build args, this option needs to
                        be used separately for each build arg. BASE_IMAGE may also be a local JDK
                        directory: its contents are copied to /opt/java and JAVA_HOME=/opt/java is set,
                        on top of \$LOCAL_JDK_OS_IMAGE (default ubuntu:24.04).
  -H                    Copy Hadoop native libraries into the image at /opt/hadoop/lib/native and set
                        LD_LIBRARY_PATH accordingly. Accepts a Hadoop home (containing lib/native) or
                        the native directory itself.

Examples:

  - Build image with tag "v1.8.1" for docker.io/myrepo
    $0 -r docker.io/myrepo -t v1.8.1 build

  - Build with tag "v1.8.1" and Spark-3.5.2 as base image for docker.io/myrepo
    $0 -r docker.io/myrepo -t v1.8.1 -b BASE_IMAGE=repo/spark:3.5.2 build

  - Build with tag "v1.8.1" on a local JDK (copied to /opt/java) for docker.io/myrepo
    $0 -r docker.io/myrepo -t v1.8.1 -b BASE_IMAGE=/path/to/jdk-17 build

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
HADOOP_NATIVE_DIR=
while getopts f:r:t:i:nb:u:H: option
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
 H) HADOOP_NATIVE_DIR=$(resolve_file ${OPTARG});;
 *) usage; exit 1;;
 esac
done
shift $((OPTIND - 1))

# getopts stops at the first non-option argument, so anything left besides the command means
# some options were silently skipped (e.g. an unsupported "-S /opt/spark" before "-b ...").
if [ $# -ne 1 ]; then
  error "Unexpected arguments: '$*'. Expected exactly one command (build) after the options, see $0 --help."
fi

. "${KYUUBI_HOME}/bin/load-kyuubi-env.sh"
case "${@: -1}" in
  build)
    build
    ;;
  *)
    usage
    exit 1
    ;;
esac
