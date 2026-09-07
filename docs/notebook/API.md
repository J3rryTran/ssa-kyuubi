# Notebook API Documentation

## Base URL
```
/api/v1
```

---

## Folders

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/notebook-folders` | List all folders |
| `POST` | `/notebook-folders` | Create a new folder |
| `PATCH` | `/notebook-folders/{folderId}` | Rename a folder |
| `DELETE` | `/notebook-folders/{folderId}` | Delete a folder |

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

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/notebooks` | List notebooks |
| `GET` | `/notebooks:search` | Search notebooks |
| `GET` | `/notebooks/{notebookId}` | Get notebook details |
| `POST` | `/notebooks` | Create a new notebook |
| `PATCH` | `/notebooks/{notebookId}` | Update notebook |
| `DELETE` | `/notebooks/{notebookId}` | Delete notebook |
| `POST` | `/notebooks/{notebookId}:clone` | Clone notebook |
| `GET` | `/notebooks/{notebookId}:export` | Export notebook |
| `POST` | `/notebooks:import` | Import notebook |

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

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/notebooks/{notebookId}/cells` | Create a new cell |
| `PATCH` | `/notebooks/{notebookId}/cells/{cellId}` | Update cell |
| `DELETE` | `/notebooks/{notebookId}/cells/{cellId}` | Delete cell |
| `PUT` | `/notebooks/{notebookId}/cells:reorder` | Reorder cells |

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

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/notebooks/{notebookId}/sessions` | Create a new session |
| `GET` | `/notebooks/{notebookId}/sessions` | List sessions |
| `POST` | `/notebook-sessions/{sessionId}:restart` | Restart session |
| `POST` | `/notebook-sessions/{sessionId}:stop` | Stop session |

---

## Executions

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/notebook-sessions/{sessionId}/executions` | Submit execution |
| `GET` | `/executions/{executionId}` | Get execution status |
| `POST` | `/executions/{executionId}:cancel` | Cancel execution |
| `POST` | `/executions/{executionId}:close` | Close execution |
| `GET` | `/executions/{executionId}/logs` | Get execution logs |
| `GET` | `/executions/{executionId}/schema` | Get result schema |
| `GET` | `/executions/{executionId}/results` | Get results |
| `GET` | `/executions/{executionId}/outputs` | Get outputs (stream) |

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

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/notebooks/{notebookId}/permissions` | List permissions |
| `PUT` | `/notebooks/{notebookId}/permissions` | Set permissions |
| `GET` | `/notebooks/{notebookId}/revisions` | Revision history |
| `POST` | `/notebooks/{notebookId}/revisions` | Create revision |
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

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/runtime-specs` | List runtime specs |
| `GET` | `/me` | Get current user info |
| `GET` | `/notebooks/{notebookId}/executions` | Notebook execution history |

---

---

*Source: `kyuubi-server/web-ui/src/api/notebook/index.ts` & `types.ts`*
