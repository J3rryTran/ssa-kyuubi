#!/usr/bin/env bash

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTEXT_DIR="$PROJECT_ROOT/docker/python-for-notebook"

# Default values
REGISTRY="docker.io/j3rrytran"
IMAGE_NAME="spark-img"
TAG="3.5.5-py311-r1"
SPARK_HOME=""
HADOOP_HOME=""
NO_CACHE=""

usage() {
    cat <<EOF
Usage: \$0 [options]

Options:
  -r <registry>      Docker registry (default: docker.io/j3rrytran)
  -i <name>          Image name (default: spark-img)
  -t <tag>           Image tag (default: 3.5.5-py311-r1)
  -s <path>          Path to local Spark binary (optional)
  -d <path>          Path to local Hadoop binary (optional)
  -n                 Build with --no-cache
  --help             Show this help

EOF
}

while [[ $# -gt 0 ]]; do
    case $1 in
        -r) REGISTRY="$2"; shift 2 ;;
        -i) IMAGE_NAME="$2"; shift 2 ;;
        -t) TAG="$2"; shift 2 ;;
        -s) SPARK_HOME="$2"; shift 2 ;;
        -d) HADOOP_HOME="$2"; shift 2 ;;
        -n) NO_CACHE="--no-cache"; shift ;;
        --help) usage; exit 0 ;;
        *) echo "Unknown option: $1"; usage; exit 1 ;;
    esac
done

IMAGE="${REGISTRY}/${IMAGE_NAME}:${TAG}"

# Create temporary build context
BUILD_CONTEXT="$(mktemp -d)"
cleanup() {
    rm -rf "$BUILD_CONTEXT"
}
trap cleanup EXIT

# Copy Dockerfile and requirements to build context
cp "$CONTEXT_DIR/Dockerfile.spark-py311" "$BUILD_CONTEXT/"
cp "$CONTEXT_DIR/python-requirements.txt" "$BUILD_CONTEXT/"

# Copy Spark if local path provided
if [[ -n "$SPARK_HOME" ]]; then
    if [[ ! -d "$SPARK_HOME" ]]; then
        echo "Error: Spark path not found: $SPARK_HOME"
        exit 1
    fi
    echo "Using local Spark: $SPARK_HOME"
    cp -r "$SPARK_HOME" "$BUILD_CONTEXT/spark"
fi

# Copy Hadoop if local path provided
if [[ -n "$HADOOP_HOME" ]]; then
    if [[ ! -d "$HADOOP_HOME" ]]; then
        echo "Error: Hadoop path not found: $HADOOP_HOME"
        exit 1
    fi
    echo "Using local Hadoop native: $HADOOP_HOME"
    mkdir -p "$BUILD_CONTEXT/hadoop/lib/native"
    cp -r "$HADOOP_HOME"/* "$BUILD_CONTEXT/hadoop/lib/native/"
fi

# Copy Kyuubi Spark Authz shaded jar if exists
AUTHZ_JAR="$PROJECT_ROOT/extensions/spark/kyuubi-spark-authz-shaded/target/kyuubi-spark-authz-shaded_2.12-1.10.3.jar"
if [[ -f "$AUTHZ_JAR" ]]; then
    echo "Using local Kyuubi Spark Authz: $AUTHZ_JAR"
    mkdir -p "$BUILD_CONTEXT/spark/jars"
    cp "$AUTHZ_JAR" "$BUILD_CONTEXT/spark/jars/"
fi

# Copy Kyuubi Spark SQL Engine jar if exists
SQL_ENGINE_JAR="$PROJECT_ROOT/externals/kyuubi-spark-sql-engine/target/kyuubi-spark-sql-engine_2.12-1.10.3.jar"
if [[ -f "$SQL_ENGINE_JAR" ]]; then
    echo "Using local Kyuubi Spark SQL Engine: $SQL_ENGINE_JAR"
    mkdir -p "$BUILD_CONTEXT/spark/jars"
    cp "$SQL_ENGINE_JAR" "$BUILD_CONTEXT/spark/jars/"
fi

echo "Building Spark image..."
echo "  Image: ${IMAGE}"
echo "  Spark: ${SPARK_HOME:-download during build}"
echo "  Hadoop: ${HADOOP_HOME:-download during build}"
echo ""

HADOOP_ARG=""
if [[ -n "$HADOOP_HOME" ]]; then
    HADOOP_ARG="--build-arg HADOOP_PRESENT=true"
fi

docker build $NO_CACHE \
    --build-arg "PYTHON_IMAGE=python:3.11-slim" \
    $HADOOP_ARG \
    -f "$BUILD_CONTEXT/Dockerfile.spark-py311" \
    -t "$IMAGE" \
    "$BUILD_CONTEXT"

echo ""
echo "Build complete: ${IMAGE}"
