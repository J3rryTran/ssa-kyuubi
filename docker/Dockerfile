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
# JAVA_HOME and SPARK_HOME are deliberately not set here: the Helm chart provides them.
FROM ${BASE_IMAGE}

ARG kyuubi_uid=10009
USER root

ENV KYUUBI_HOME=/opt/kyuubi
ENV KYUUBI_LOG_DIR=${KYUUBI_HOME}/logs
ENV KYUUBI_PID_DIR=${KYUUBI_HOME}/pid
ENV KYUUBI_WORK_DIR_ROOT=${KYUUBI_HOME}/work

RUN set -ex && \
    sed -i 's/http:\/\/deb.\(.*\)/https:\/\/deb.\1/g' /etc/apt/sources.list && \
    apt update && \
    apt install -y bash tini libc6 libpam-modules krb5-user libnss3 procps && \
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

ARG MAVEN_MIRROR=https://repo1.maven.org/maven2
ARG INCLUDE_AWS=true
ARG HADOOP_AWS_VERSION=3.3.4
ARG AWS_SDK_BUNDLE_VERSION=1.12.262
ARG HADOOP_AWS_SHA256=53f9ae03c681a30a50aa17524bd9790ab596b28481858e54efd989a826ed3a4a
ARG AWS_SDK_BUNDLE_SHA256=873fe7cf495126619997bec21c44de5d992544aea7e632fdc77adb1a0915bae5
RUN if [ "${INCLUDE_AWS}" = "true" ]; then \
      curl -fSL -o ${KYUUBI_HOME}/jars/hadoop-aws-${HADOOP_AWS_VERSION}.jar \
        ${MAVEN_MIRROR}/org/apache/hadoop/hadoop-aws/${HADOOP_AWS_VERSION}/hadoop-aws-${HADOOP_AWS_VERSION}.jar && \
      echo "${HADOOP_AWS_SHA256}  ${KYUUBI_HOME}/jars/hadoop-aws-${HADOOP_AWS_VERSION}.jar" | sha256sum -c - && \
      curl -fSL -o ${KYUUBI_HOME}/jars/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar \
        ${MAVEN_MIRROR}/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_BUNDLE_VERSION}/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar && \
      echo "${AWS_SDK_BUNDLE_SHA256}  ${KYUUBI_HOME}/jars/aws-java-sdk-bundle-${AWS_SDK_BUNDLE_VERSION}.jar" | sha256sum -c - ; \
    fi

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
