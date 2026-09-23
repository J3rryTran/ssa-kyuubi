# Notebook API Documentation

## Base URL

```
/api/v1
```

---

## Folders

|  Method  |            Endpoint            |     Description     |
|----------|--------------------------------|---------------------|
| `GET`    | `/notebook-folders`            | List all folders    |
| `POST`   | `/notebook-folders`            | Create a new folder |
| `PATCH`  | `/notebook-folders/{folderId}` | Rename a folder     |
| `DELETE` | `/notebook-folders/{folderId}` | Delete a folder     |

### Create folder

```typescript
POST /notebook-folders
Body: { name: string, parentId: string | null }
```

### Rename folder

```typescript
PATCH /notebook-folders/{folderId}
Body: { name: string, version: number }
```

---

## Notebooks

|  Method  |             Endpoint             |      Description      |
|----------|----------------------------------|-----------------------|
| `GET`    | `/notebooks`                     | List notebooks        |
| `GET`    | `/notebooks:search`              | Search notebooks      |
| `GET`    | `/notebooks/{notebookId}`        | Get notebook details  |
| `POST`   | `/notebooks`                     | Create a new notebook |
| `PATCH`  | `/notebooks/{notebookId}`        | Update notebook       |
| `DELETE` | `/notebooks/{notebookId}`        | Delete notebook       |
| `POST`   | `/notebooks/{notebookId}:clone`  | Clone notebook        |
| `GET`    | `/notebooks/{notebookId}:export` | Export notebook       |
| `POST`   | `/notebooks:import`              | Import notebook       |

### Create notebook

```typescript
POST /notebooks
Body: { name: string, folderId: string | null, language: 'SQL' | 'PYTHON' }
```

### Clone notebook

```typescript
POST /notebooks/{notebookId}:clone
Body: { name: string }
```

### Export notebook

```typescript
GET /notebooks/{notebookId}:export?format={format}
```

### Import notebook

```typescript
POST /notebooks:import
Body: { name: string, content: string, format?: string }
```

---

## Cells

|  Method  |                 Endpoint                 |    Description    |
|----------|------------------------------------------|-------------------|
| `POST`   | `/notebooks/{notebookId}/cells`          | Create a new cell |
| `PATCH`  | `/notebooks/{notebookId}/cells/{cellId}` | Update cell       |
| `DELETE` | `/notebooks/{notebookId}/cells/{cellId}` | Delete cell       |
| `PUT`    | `/notebooks/{notebookId}/cells:reorder`  | Reorder cells     |

### Create cell

```typescript
POST /notebooks/{notebookId}/cells
Body: { position: number, cellType: 'CODE' | 'MARKDOWN', language: CellLanguage, source: string }
```

### Update cell

```typescript
PATCH /notebooks/{notebookId}/cells/{cellId}
Body: { source?: string, metadata?: Record<string, string> }
```

### Reorder cells

```typescript
PUT /notebooks/{notebookId}/cells:reorder
Body: { cellIds: string[] }
```

---

## Sessions

| Method |                 Endpoint                 |     Description      |
|--------|------------------------------------------|----------------------|
| `POST` | `/notebooks/{notebookId}/sessions`       | Create a new session |
| `GET`  | `/notebooks/{notebookId}/sessions`       | List sessions        |
| `POST` | `/notebook-sessions/{sessionId}:restart` | Restart session      |
| `POST` | `/notebook-sessions/{sessionId}:stop`    | Stop session         |

---

## Executions

| Method |                  Endpoint                   |     Description      |
|--------|---------------------------------------------|----------------------|
| `POST` | `/notebook-sessions/{sessionId}/executions` | Submit execution     |
| `GET`  | `/executions/{executionId}`                 | Get execution status |
| `POST` | `/executions/{executionId}:cancel`          | Cancel execution     |
| `POST` | `/executions/{executionId}:close`           | Close execution      |
| `GET`  | `/executions/{executionId}/logs`            | Get execution logs   |
| `GET`  | `/executions/{executionId}/schema`          | Get result schema    |
| `GET`  | `/executions/{executionId}/results`         | Get results          |
| `GET`  | `/executions/{executionId}/outputs`         | Get outputs (stream) |

### Submit execution

```typescript
POST /notebook-sessions/{sessionId}/executions
Body: { cellId: string, source: string }
```

### Get results

```typescript
GET /executions/{executionId}/results?cursor={cursor}&maxRows={maxRows}
```

### Get logs

```typescript
GET /executions/{executionId}/logs?offset={offset}&maxLines=200
```

---

## Permissions & Revisions

| Method |                           Endpoint                           |   Description    |
|--------|--------------------------------------------------------------|------------------|
| `GET`  | `/notebooks/{notebookId}/permissions`                        | List permissions |
| `PUT`  | `/notebooks/{notebookId}/permissions`                        | Set permissions  |
| `GET`  | `/notebooks/{notebookId}/revisions`                          | Revision history |
| `POST` | `/notebooks/{notebookId}/revisions`                          | Create revision  |
| `POST` | `/notebooks/{notebookId}/revisions/{revisionNumber}:restore` | Restore revision |

### Set permissions

```typescript
PUT /notebooks/{notebookId}/permissions
Body: { permissions: [{ principalType: string, principalId: string, role: string }] }
```

### Create revision

```typescript
POST /notebooks/{notebookId}/revisions
Body: { reason: string }
```

---

## Others

| Method |               Endpoint               |        Description         |
|--------|--------------------------------------|----------------------------|
| `GET`  | `/runtime-specs`                     | List runtime specs         |
| `GET`  | `/me`                                | Get current user info      |
| `GET`  | `/notebooks/{notebookId}/executions` | Notebook execution history |

---

---

*Source: `kyuubi-server/web-ui/src/api/notebook/index.ts` & `types.ts`*

---

## Additional document endpoints

The tables above describe the most common document operations. The following endpoints are also
part of the current contract.

|  Method  |                           Endpoint                            |               Description                |
|----------|---------------------------------------------------------------|------------------------------------------|
| `GET`    | `/notebook-folders/{folderId}`                                | Get one folder.                          |
| `DELETE` | `/notebook-folders/{folderId}?version=`                       | Delete a folder with optimistic locking. |
| `POST`   | `/notebooks/{notebookId}:move`                                | Move a notebook to another folder.       |
| `GET`    | `/notebooks/{notebookId}/cells`                               | List cells in document order.            |
| `GET`    | `/notebooks/{notebookId}/cells/{cellId}`                      | Get one cell.                            |
| `GET`    | `/notebooks/{notebookId}/cells/{cellId}/config`               | Get cell-specific configuration.         |
| `PATCH`  | `/notebooks/{notebookId}/cells/{cellId}/config`               | Update cell-specific configuration.      |
| `GET`    | `/notebooks/{notebookId}/revisions/{revisionNumber}`          | Get one revision.                        |
| `DELETE` | `/notebooks/{notebookId}/revisions/{revisionNumber}?version=` | Delete an unprotected revision.          |
| `GET`    | `/notebooks/{notebookId}/schedule`                            | Get the schedule.                        |
| `DELETE` | `/notebooks/{notebookId}/schedule`                            | Remove the schedule.                     |

## Complete session, runtime, and execution APIs

### Sessions

|  Method  |                      Endpoint                      |             Description             |
|----------|----------------------------------------------------|-------------------------------------|
| `GET`    | `/notebook-sessions/{sessionId}`                   | Get a notebook session.             |
| `DELETE` | `/notebook-sessions/{sessionId}`                   | Stop and delete a notebook session. |
| `POST`   | `/notebook-sessions/{sessionId}:reset`             | Reset the session's runtime state.  |
| `GET`    | `/notebook-sessions/{sessionId}/runtimes`          | List session runtimes.              |
| `POST`   | `/notebook-sessions/{sessionId}/runtimes`          | Create a runtime for the session.   |
| `GET`    | `/notebook-sessions/{sessionId}/executions?limit=` | List session executions.            |

### Runtimes

|  Method  |                  Endpoint                  |          Description           |
|----------|--------------------------------------------|--------------------------------|
| `GET`    | `/runtime-specs/{runtimeSpecId}`           | Get one runtime specification. |
| `GET`    | `/notebook-runtimes/{runtimeId}`           | Get and refresh runtime state. |
| `POST`   | `/notebook-runtimes/{runtimeId}:interrupt` | Interrupt current work.        |
| `POST`   | `/notebook-runtimes/{runtimeId}:restart`   | Restart a runtime.             |
| `POST`   | `/notebook-runtimes/{runtimeId}:stop`      | Stop a runtime.                |
| `DELETE` | `/notebook-runtimes/{runtimeId}`           | Stop and delete a runtime.     |

### Executions

| Method |                               Endpoint                               |              Description               |
|--------|----------------------------------------------------------------------|----------------------------------------|
| `GET`  | `/executions/{executionId}/events?afterSequence=&waitMillis=&limit=` | Long-poll replayable execution events. |
| `GET`  | `/executions/{executionId}/outputs?afterSequence=&limit=`            | Read text and rich output events.      |

`/executions/{executionId}/results` accepts `cursor` and `maxRows`; `/logs` accepts `offset` and
`maxLines`. A browser should use events or outputs to reconnect after a refresh instead of relying
on one long-lived HTTP request.

## Engine Profiles and Python environments

An Engine Profile is a user-owned resource policy. Its immutable revision controls Spark resource
configuration, generated engine subdomain, timeout policy, and optional persistent Python
environment. The server derives the owner from the authenticated principal; callers never provide a
user-controlled Kyuubi subdomain.

|  Method  |                           Endpoint                            |                          Description                          |
|----------|---------------------------------------------------------------|---------------------------------------------------------------|
| `GET`    | `/engine-profiles`                                            | List profiles owned by the caller.                            |
| `POST`   | `/engine-profiles`                                            | Create a profile and its initial revision.                    |
| `GET`    | `/engine-profiles/{profileId}`                                | Get a profile.                                                |
| `PATCH`  | `/engine-profiles/{profileId}`                                | Create a new immutable profile revision.                      |
| `DELETE` | `/engine-profiles/{profileId}`                                | Delete an unused profile.                                     |
| `GET`    | `/engine-profiles/{profileId}/engine`                         | Get active revision engine status.                            |
| `POST`   | `/engine-profiles/{profileId}:start`                          | Warm or start an engine.                                      |
| `POST`   | `/engine-profiles/{profileId}:stop`                           | Stop the active engine.                                       |
| `GET`    | `/engine-profiles/{profileId}/revisions`                      | List immutable profile revisions.                             |
| `GET`    | `/engine-profiles/{profileId}/revisions/{revision}/engine`    | Get engine status for one revision.                           |
| `POST`   | `/engine-profiles/{profileId}/revisions/{revision}:terminate` | Terminate a draining revision; body requires `confirm: true`. |
| `GET`    | `/engine-profiles/{profileId}/python-environments`            | List persistent Python environment revisions.                 |
| `POST`   | `/engine-profiles/{profileId}/python-packages:install`        | Request package installation.                                 |
| `POST`   | `/engine-profiles/{profileId}/python-packages:uninstall`      | Request package removal.                                      |
| `GET`    | `/engine-profiles/{profileId}/environment-builds/{requestId}` | Get asynchronous environment-build state.                     |
| `PUT`    | `/engine-profiles/{legacyProfileId}`                          | Legacy upsert endpoint retained for compatibility.            |

Create or update profile request:

```json
{
  "name": "analytics-heavy",
  "sparkConfig": {
    "spark.driver.memory": "4g",
    "spark.executor.memory": "8g"
  },
  "notebookRuntimeIdleTimeout": "PT15M",
  "engineIdleTimeout": "PT30M"
}
```

Package-change requests accept package names only:

```json
{
  "packages": ["pandas==2.2.3", "seaborn"]
}
```

See [Engine Profile Python Environment Lifecycle](ENGINE_PROFILE_PYTHON_ENVIRONMENT.md) for the
PVC, hot-install, build, and promotion lifecycle.

## SQL Editor session

| Method |      Endpoint      |                             Description                             |
|--------|--------------------|---------------------------------------------------------------------|
| `POST` | `/editor-sessions` | Open a SQL Editor Kyuubi session with an authorized Engine Profile. |

```json
{
  "engineProfileId": "profile-id"
}
```

## Supporting notebook endpoints

| Method |      Endpoint      |                   Description                   |
|--------|--------------------|-------------------------------------------------|
| `GET`  | `/me`              | Get current authenticated user information.     |
| `GET`  | `/notebook-status` | Get Notebook subsystem status and capabilities. |

The implementation sources are the REST resources under
`kyuubi-server/src/main/scala/org/apache/kyuubi/server/api/v1/`: `NotebooksResource`,
`NotebookFoldersResource`, `NotebookCollectionResources`, `NotebookRuntimeResources`,
`NotebookEngineProfilesResource`, `EditorSessionsResource`, and `DbtResources`.
