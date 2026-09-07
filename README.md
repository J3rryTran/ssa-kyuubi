# kyuubi-custom
fork from https://github.com/apache/kyuubi.git

version 1.10.3

## Build and publish

Run the following steps from the project root.

### 1. Create a binary package

Create a runnable binary package that includes the Kyuubi Web UI and uses external Spark, Flink, and Hive installations:

```bash
./build/dist --tgz --web-ui --spark-provided --flink-provided --hive-provided
```
If specfic version spark, flink, and hive use:
```bash
./build/dist --tgz --web-ui --spark-provided --flink-provided --hive-provided -Dspark.version=3.5.5 -Dhive.version=3.1.3
```

### 2. Build the Docker image

The following command creates:

```bash
./bin/docker-image-tool.sh -r <name_repo_docker> -i vtnet-custom -t <tag> -S /opt/spark -b BASE_IMAGE=eclipse-temurin:17-jdk-focal build
```

### 3. Test 
Localhost:

```bash
docker run --rm -p 10099:10099 IMAGES
```

Bash in images:
```bash
docker run -it --rm <image>:<tag> /bin/bash
```

Options:

- `-r`: Docker repository or registry namespace. For Docker Hub, use `docker.io/<username>`.
- `-i`: Docker image name. Defaults to `kyuubi` if omitted.
- `-t`: Docker image tag.
- `-b KEY=VALUE`: Docker build argument. This option can be specified multiple times.
- `-s <path>`: Copy a local Spark installation into the image and use it as `SPARK_HOME`.
- `-S <path>`: Declare the Spark installation path inside the image without copying Spark. The base image must already contain Spark at this path.
- `-n`: Build the image without using the Docker build cache.
- `-X`: Build and push a multi-platform image using Docker Buildx.

## Build spark with packages python 

Add python packages into ***vtnet-kyuubi/docker/python-for-notebook/requirement.txt*** and then auto build in spark.

``` bash
bin/build-spark-image.sh -r docker.io/<name_user> -i <name_repo> -t <tag> -s /path/to/vtnet-kyuubi/spark/spark-3.5.5-bin-hadoop3 -d /path/to/vtnet-kyuubi/hadoop/3.3.4/lib/native
```

## Push the Docker image

```bash
docker push <images>:<tag>
```
### The list of module added and contributed:
- OIDC Authentication - Trungtm8
- Ranger Authorization - Hieunm29
- Notebook and Python for notebook - Trungtm8
