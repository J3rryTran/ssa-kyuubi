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

# The base image must be pinned to the same Python ABI/image family used by Spark driver and
# executor pods. The Kyuubi server compares the configured identity before it mounts a revision.
ARG BASE_IMAGE=python:3.11-slim
FROM ${BASE_IMAGE}

RUN python -m pip install --no-cache-dir --upgrade pip setuptools wheel

COPY build-environment.sh /opt/kyuubi/python-env-builder/build-environment.sh
RUN chmod 0555 /opt/kyuubi/python-env-builder/build-environment.sh

ENTRYPOINT ["/opt/kyuubi/python-env-builder/build-environment.sh"]
