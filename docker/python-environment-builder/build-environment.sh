#!/usr/bin/env sh
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

set -eu

: "${KYUUBI_ENVIRONMENT_PATH:?KYUUBI_ENVIRONMENT_PATH is required}"
root=/python-env
target="$root/$KYUUBI_ENVIRONMENT_PATH"
staging="$target.staging"

case "$KYUUBI_ENVIRONMENT_PATH" in
  environments/env-[0-9]*) ;;
  *) echo "invalid environment path" >&2; exit 64 ;;
esac

test ! -e "$target"
rm -rf "$staging"
mkdir -p "$root/environments"
python -m venv --system-site-packages "$staging"
"$staging/bin/python" -m pip install --disable-pip-version-check --no-input -r /input/requirements.txt
"$staging/bin/python" -m pip check
"$staging/bin/python" -m pip freeze --all | sort > "$staging/requirements.lock"
"$staging/bin/python" -c 'import sys; print(sys.version)' > "$staging/metadata.json"
touch "$staging/COMPLETE"
mv "$staging" "$target"
