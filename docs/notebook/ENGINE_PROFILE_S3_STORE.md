# S3-only Engine Profile store

Engine Profile configuration is stored in one JSON object per owner beneath the configured root.
The JSON object contains the owner and every current profile of that owner. ZooKeeper coordinates
writers from multiple Kyuubi replicas; it does not contain profile configuration.

## Required server configuration

```properties
kyuubi.engine.profile.store.class=org.apache.kyuubi.server.engineprofile.S3EngineProfileStore
kyuubi.engine.profile.store.fs.root=s3a://warehouse/kyuubi-test/engine-profiles
kyuubi.engine.profile.store.fs.probe.enabled=true
kyuubi.engine.profile.store.zk.lock.timeout=30s
kyuubi.ha.addresses=<zookeeper-hosts>
```

The Kyuubi pod also needs a mounted `core-site.xml`, `HADOOP_CONF_DIR`, `hadoop-aws` and the AWS
SDK bundle. Inject `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` from a Kubernetes Secret; do
not put them in Helm values or ConfigMaps. The credential needs ListBucket, GetObject, PutObject
and DeleteObject permission on the configured prefix. ZooKeeper ACLs must allow Kyuubi to create
and remove lock nodes below `<ha namespace>/engine-profile-store/owners`.

## Layout

```text
s3a://warehouse/kyuubi-test/engine-profiles/
├── 2bd806c9....json  # one document whose owner field is alice
└── 81b637d8....json  # one document whose owner field is bob
```

Each filename is the SHA-256 hash of the UTF-8 owner name. The embedded owner is checked on every
read. Deleting a profile rewrites its owner's document; deleting the final profile deletes that
one JSON document.

## Clean test cutover

This implementation never drops existing tables or S3 objects automatically. For a disposable
test environment, stop all Engine Profile engines, clear the configured Engine Profile S3 prefix,
then remove only these legacy JDBC tables manually if they exist:

```text
notebook_engine_profile
notebook_engine_profile_v2
notebook_engine_profile_revision
```

Do not remove `notebook_engine_profile_run_snapshot`,
`notebook_python_environment_revision` or `notebook_python_environment_change_request`; Python
environment lifecycle and DBT run leases still use them. After Helm rollout, verify every Kyuubi
pod starts successfully, create/list/update/delete a profile through two replicas, then run a
Python environment and DBT smoke test.

