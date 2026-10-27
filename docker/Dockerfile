#
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


ARG BASE_IMAGE=eclipse-temurin:8-jdk-focal
# SPARK_HOME is deliberately not set here: the Helm chart provides it. JAVA_HOME comes from
# BASE_IMAGE, or is /opt/java when docker-image-tool.sh is given a local JDK directory.
FROM ${BASE_IMAGE}

# Filled by docker-image-tool.sh when -b BASE_IMAGE=<local JDK dir>; empty directory otherwise.
ARG LOCAL_JDK_HOME=
COPY local-jdk /opt/java
ENV JAVA_HOME=${LOCAL_JDK_HOME:-${JAVA_HOME}}
ENV PATH=${LOCAL_JDK_HOME:+${LOCAL_JDK_HOME}/bin:}${PATH}

ARG kyuubi_uid=10009
USER root

ENV KYUUBI_HOME=/opt/kyuubi
ENV KYUUBI_LOG_DIR=${KYUUBI_HOME}/logs
ENV KYUUBI_PID_DIR=${KYUUBI_HOME}/pid
ENV KYUUBI_WORK_DIR_ROOT=${KYUUBI_HOME}/work

RUN set -ex && \
    if ! command -v java >/dev/null 2>&1; then \
      echo "BASE_IMAGE has no 'java' on PATH, please use a JDK image such as eclipse-temurin:17-jdk or a local JDK directory" >&2; \
      exit 1; \
    fi && \
    java -version && \
    for f in /etc/apt/sources.list /etc/apt/sources.list.d/*.list /etc/apt/sources.list.d/*.sources; do \
      if [ -f "$f" ]; then sed -i 's/http:\/\/deb.\(.*\)/https:\/\/deb.\1/g' "$f"; fi; \
    done && \
    apt update && \
    DEBIAN_FRONTEND=noninteractive apt install -y bash tini libc6 libpam-modules krb5-user libnss3 procps curl ca-certificates && \
    ln -snf /bin/bash /bin/sh && \
    useradd -u ${kyuubi_uid} -g root kyuubi -d /home/kyuubi -m && \
    mkdir -p ${KYUUBI_HOME} ${KYUUBI_LOG_DIR} ${KYUUBI_PID_DIR} ${KYUUBI_WORK_DIR_ROOT} && \
    rm -rf /var/cache/apt/*

COPY LICENSE ${KYUUBI_HOME}/
COPY NOTICE ${KYUUBI_HOME}/
COPY RELEASE ${KYUUBI_HOME}/
COPY bin ${KYUUBI_HOME}/bin
COPY jars ${KYUUBI_HOME}/jars
COPY beeline-jars ${KYUUBI_HOME}/beeline-jars
COPY externals/engines/spark ${KYUUBI_HOME}/externals/engines/spark
COPY externals/engines/jdbc ${KYUUBI_HOME}/externals/engines/jdbc
RUN curl -fL -o ${KYUUBI_HOME}/jars/mysql-connector-j-8.3.0.jar \
      https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar && \
    ln -s ../../../jars/mysql-connector-j-8.3.0.jar \
      ${KYUUBI_HOME}/externals/engines/jdbc/mysql-connector-j-8.3.0.jar

# Filled by docker-image-tool.sh -H; empty directory otherwise.
ENV HADOOP_HOME=/opt/hadoop
COPY hadoop-native ${HADOOP_HOME}/lib/native
ENV LD_LIBRARY_PATH=${HADOOP_HOME}/lib/native${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}

WORKDIR ${KYUUBI_HOME}
RUN chown -R kyuubi:root ${KYUUBI_HOME} && \
    chmod ug+rw -R ${KYUUBI_HOME} && \
    chmod a+rwx -R ${KYUUBI_WORK_DIR_ROOT}

CMD [ "./bin/kyuubi", "run" ]

USER ${kyuubi_uid}
