# Technical Debt Register — Verwaltungsassistent

**Created**: 2026-08-03
**Last Updated**: 2026-08-03
**Version**: 1.0.0

This document tracks known architectural limitations, API gaps, and design trade-offs discovered during implementation. Items are ordered by priority (P0 = blocks critical path, P1 = degrades performance, P2 = code quality, P3 = future enhancement).

---

## P1 — N+1 Document Lookup in Attached Documents Tab

**Location**: `CaseDetailController.documentsTab()`

**Issue**: The documents tab calls `documentFacade.getDocument(id)` for each linked document individually. With 50 attached documents, this results in 50 separate service/database calls.

**Impact**: Linear performance degradation as document count grows. Noticeable at ~20+ documents.

**Suggested Solution**: Add `findDocumentsByIds(Set<UUID> ids)` to `DocumentFacade` that returns a `Map<UUID, Document>`. The `DocumentFilter` record already supports filter-based queries; a batch lookup by ID would be a natural extension.

**Workaround**: Fallback to link-level data when `DocumentFacade` calls fail. The UI remains functional but shows limited metadata.

---

## P2 — DocumentFileType vs DocumentCategory Type Mismatch

**Location**: `CaseDetailController.attachDocuments()`, `AttachDocumentCommand`

**Issue**: `AttachDocumentCommand.documentType` expects `DocumentCategory` (logical category: CONTRACT, REPORT, etc.), but `Document.metadata().type()` returns `DocumentFileType` (file format: PDF, DOCX, etc.). The attach workflow defaults to `DocumentCategory.CONTRACT` as a fallback, losing the actual document type information.

**Impact**: Attached documents always show type "CONTRACT" regardless of their actual category. No runtime errors, but incorrect metadata.

**Suggested Solution**: Either:
1. Change `AttachDocumentCommand.documentType` to `DocumentFileType` (breaking change to platform-workspace API)
2. Add a `DocumentCategory` field to `DocumentMetadata` that is populated during document creation
3. Create a mapping from `DocumentFileType` to `DocumentCategory` with a reasonable default

**Workaround**: None. Documents are attached with a default category.

---

## P2 — Missing detachDocument in WorkspaceService (FIXED)

**Location**: `WorkspaceService.java`

**Status**: ✅ Fixed 2026-08-03. `detachDocument(workspaceId, linkId)` added.

**Original Issue**: The `WorkspaceService` had `attachDocument()` but no `detachDocument()`. The MVC controller accessed `JpaWorkspaceDocumentLinkRepository` directly to delete links.

**Resolution**: Added `detachDocument(String workspaceId, String linkId)` to `WorkspaceService`. The method deletes the link and updates the workspace's `updatedAt` timestamp. Controllers now call `WorkspaceService` exclusively.

---

## P2 — Phase Data as JSON Blob

**Location**: `WorkspaceEntity.phaseData` (JSON string column)

**Issue**: Notes and checklist items are stored as serialized JSON in the `phaseData` column. This limits queryability (cannot search notes by text, cannot query checklist completion across workspaces) and makes the data fragile (malformed JSON = lost data).

**Impact**: Acceptable for Phase 2 with small data volumes. Will become problematic when:
- Users have 100+ notes per case
- Cross-case reporting on checklist completion is needed
- Concurrent modifications to phase data cause race conditions

**Suggested Solution**: Create dedicated JPA entities: `CaseNoteEntity` (id, workspaceId, text, createdBy, createdAt) and `ChecklistItemEntity` (id, workspaceId, label, phase, completed, updatedAt). Migrate data when entities are created.

**Workaround**: The current implementation uses `ObjectMapper` with defensive parsing. Malformed JSON is caught and returns empty defaults.

---

## P1 — AiFacade Not Wired at Runtime (Decision Workspace Blocked)

**Location**: `DecisionWorkspaceController`

**Issue**: The `AiFacade` interface (`platform-ai-api`) is on the classpath, but the implementation (`AiService` in `platform-ai-runtime`) cannot be loaded because `platform-ai-runtime` requires `SearchFacade`, `ModelProvider`, `ChatCompletionProvider`, and other beans that are not configured in the Verwaltungsassistent application. The `AiFacade` is marked `@Autowired(required = false)` so the application starts, but the decision workspace shows "KI-Dienst ist nicht verfügbar".

**Impact**: The "Entscheidung vorbereiten" button navigates to the decision page and the UI is fully built, but clicking "Analyse starten" returns an error message that the AI service is not available. The full workflow cannot be demonstrated without wiring the AI runtime.

**Suggested Solution**: Add `platform-ai-runtime` dependency with proper configuration:
1. Provide `SearchFacade` implementation (requires `platform-search-runtime` or mock)
2. Provide `ModelProvider` and `ChatCompletionProvider` (requires Ollama or OpenAI configuration)
3. Provide `PromptRegistry` implementation (already in `platform-ai-runtime`)
4. Configure `AiPipelineProperties` (model names, retrieval settings)
5. Add `reasoning.ai` and `reasoning.search` to component scan

**Workaround**: The Decision Workspace UI is complete and tested. When the AI infrastructure is configured, remove the `required = false` and the null check.

---

## P3 — In-Memory Filtering for Case and Document Lists

**Location**: `CaseController.listCases()`, `CaseDetailController.documentsTab()`

**Issue**: Filtering, sorting, and pagination are performed in-memory after loading all entities. With 1000+ cases, this loads all data into memory before discarding 90% of it via pagination.

**Impact**: Negligible for Phase 2 (typical user has < 50 cases). Will need database-level filtering for production.

**Suggested Solution**: 
- Add `findByOwnerWithFilter(String ownerId, String status, String search, Sort, Pageable)` to `JpaWorkspaceRepository`
- Use Spring Data JPA Specifications for dynamic filtering
- Push pagination to the database layer

---

## P3 — No fileSize in Document or DocumentVersion

**Location**: `Document` and `DocumentVersion` records

**Issue**: The Screen Specifications list "file size" as a display column for attached documents, but neither `Document` nor `DocumentVersion` expose a `fileSize` field.

**Impact**: The "Dateigröße" column is absent from the Attached Documents tab.

**Suggested Solution**: Add `Long fileSize` to `DocumentVersion` record. Populate during document upload/ingestion.

---

## P3 — No workspace-specific document search

**Location**: `DocumentFacade.findDocuments(DocumentFilter)`

**Issue**: The `DocumentFilter` record has no `workspaceId` field, preventing server-side filtering of documents by workspace membership. The attach workflow loads all documents and filters out already-attached ones in memory.

**Impact**: Acceptable for Phase 2. With 1000+ documents in the system, loading all of them to show 10 candidates is wasteful.

**Suggested Solution**: Add `Set<String> excludeDocumentIds` or `String workspaceId` to `DocumentFilter` to enable server-side exclusion of already-attached documents.

---

## Summary

| ID | Priority | Description | Status |
|----|----------|-------------|--------|
| TD-1 | P1 | N+1 Document Lookup | Open |
| TD-2 | P2 | FileType vs Category Mismatch | Open |
| TD-3 | P2 | Missing detachDocument | ✅ Fixed |
| TD-4 | P2 | Phase Data as JSON Blob | Open |
| TD-5 | P3 | In-Memory Filtering | Open |
| TD-6 | P3 | Missing fileSize field | Open |
| TD-7 | P3 | No workspace-specific doc search | Open |
| TD-8 | P1 | AiFacade Not Wired at Runtime | Open |

**Resolution Rate**: 1/8 fixed (13%)
**Recommended Sprint Target**: Address TD-1 and TD-2 in Phase 3 (Documents & Knowledge) before document upload is implemented.
