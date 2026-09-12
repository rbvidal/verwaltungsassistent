# UI Pattern Library — Verwaltungsassistent

**Version**: 1.0.0
**Status**: Canonical implementation guide
**Source**: Semantic extraction from `municipal-decision-assistant` (July 2025) and `enterprise-ai-platform` (June 2024)
**Design system**: application.css custom properties + semantic HTML (no Bootstrap, no Tailwind)

---

## Purpose

This document is the single source of truth for every reusable UI pattern in the Verwaltungsassistent application. It captures the semantic structure, component hierarchy, and implementation conventions extracted from the legacy projects, rewritten for the current design system.

**Rule**: Before building any new screen, check this library. If a pattern exists, reuse it. If a pattern is missing, add it here first.

---

## 1. Pattern Inventory — Complete Catalog

### 1.1 Navigation Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| N1 | Top navigation bar with brand, links, user menu | `fragments/layout.html` (both projects) | ✅ Implemented — `fragments/header.html` |
| N2 | Active page highlighting via URI prefix | `fragments/layout.html` (both) | ✅ Implemented — `currentUri` model attribute via `WebModelAdvice` |
| N3 | Breadcrumb trail | Current project spec | ✅ Implemented — `fragments/breadcrumbs.html` |
| N4 | Language switcher dropdown | `fragments/layout.html` (municipal) | 🟡 Deferred — `LocaleChangeInterceptor` ready, no UI widget yet |
| N5 | Mobile hamburger menu | Current project spec | 🟡 Missing — Alpine.js toggle exists in `header.html` but `topnav__links` hidden at mobile |

### 1.2 Dashboard Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| D1 | Rich metric card — header with title + status badge, 2xN grid of label-value pairs, footer | `dashboard.html` (municipal) | ✅ Implemented — `.rich-metric` CSS, `dashboard/index.html` |
| D2 | System health bar — row of status dots with labels | `dashboard.html`, `analytics.html` (municipal) | 🟡 Partial — status dots exist (`.status-dot`) but health bar widget not built |
| D3 | Domain/workspace card — emoji icon, name, description, meta row | `dashboard.html`, `home.html`, `cases.html` (municipal) | 🟡 Missing — `.domain-card` CSS exists, no template fragment yet |
| D4 | Stat card — large count number, small label | `analytics.html`, `corpus-health.html` (municipal) | ✅ Implemented — `.stat-card` CSS |
| D5 | Quick action panel — icon + label links in a grid | `home.html` (municipal) | ✅ Implemented — `.quick-actions` CSS, `dashboard/index.html` |
| D6 | Activity table — timestamp, type badge, actor, correlation columns | `dashboard.html`, `home.html` (municipal) | ✅ Implemented — `.activity-table` CSS, `dashboard/index.html` |

### 1.3 Data Display Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| T1 | Filter bar — status dropdown, type dropdown, text inputs, filter button | `documents/list.html`, `audit/index.html`, `jobs/list.html` (municipal) | 🟡 Missing — needed for Phase 2 (Case List), Phase 3 (Document List) |
| T2 | Paginated data table — sticky header, striped rows, per-row action buttons | `documents/list.html`, `audit/index.html`, `jobs/list.html` (municipal) | 🟡 Missing — needed for Phase 2-5 list screens |
| T3 | Pagination control — Previous, "Page X of Y", Next | All list pages (municipal) | 🟡 Missing — needed for Phase 2 |
| T4 | Status badge — colored pill (OK/FAIL/WARN/ACTIVE/DRAFT) | Across all pages (municipal) | ✅ Implemented — `.status-dot--green/amber/red`, needs `.badge` CSS |
| T5 | Type badge — neutral gray tag for entity types | Activity tables (municipal) | 🟡 Partial — `.activity-table__type` CSS exists |
| T6 | Priority badge — P1/P2/P3 with color coding | `corpus-inventory.html` (municipal) | 🔴 Future — Phase 5 (Corpus) |
| T7 | Monospaced ID display — truncated UUID with tooltip | All tables (municipal) | 🟡 Missing — `.activity-table__correlation` CSS exists as starting point |
| T8 | Progress bar — horizontal fill bar with label | `analytics.html`, `documents/upload.html` (municipal) | 🔴 Future — Phase 3 (Batch Import) |
| T9 | Key-value metadata table — two-column label:value rows | `regulations.html`, `admin.html` (municipal) | 🔴 Future — Phase 3 (Document Detail) |
| T10 | Authority classification badge — FACTUAL/REFERENCE/MIXED with color | `workspaces/wizard.html` (municipal) | 🔴 Future — Phase 2 (Case Analysis) |

### 1.4 Form Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| F1 | Centered auth form — max-width card with title, fields, submit, footer link | `auth/login.html`, `auth/register.html` (both) | ✅ Implemented — `.auth-page`, `.auth-card`, `auth/login.html` |
| F2 | Inline filter form — horizontal row of dropdowns + text inputs + button | `documents/list.html`, `audit/index.html` (municipal) | 🟡 Missing — needed for Phase 2 |
| F3 | Search form — query input, mode selector, type filter, submit button | `search/index.html` (municipal) | 🟡 Missing — needed for Phase 3 (Knowledge Search) |
| F4 | Quick input form — single textarea + submit button | `home.html` (municipal) | 🔴 Future — Phase 4 (AI Assistant) |
| F5 | Multi-field upload form — drop zone, file info, metadata fields, submit | `documents/upload.html` (municipal) | 🟡 Missing — needed for Phase 3 (Document Upload) |
| F6 | Wizard progress bar — N segments, current/complete/remaining states | `workspaces/wizard.html` (municipal) | 🔴 Future — Phase 2 (Case Create wizard) |
| F7 | Stage badge — "Stage N of M" indicator | `workspaces/wizard-create.html` (municipal) | 🔴 Future — Phase 2 |

### 1.5 Feedback Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| FB1 | Alert message — colored banner with icon and text | `auth/login.html` (error/success/logout alerts) | ✅ Implemented — `.message--error/success/warning` CSS |
| FB2 | Toast notification — auto-dismissing corner message | Current project spec | ✅ Implemented — `fragments/toast.html`, `app.js showToast()` |
| FB3 | Empty state — icon, title, description | `dashboard.html`, `regulations.html`, `cases.html` | ✅ Implemented — `.empty-state`, `.empty-state__title`, `.empty-state__desc` CSS |
| FB4 | Empty state with CTA — icon, title, description, action button | `cases.html` (municipal) | 🟡 Partial — `.empty-state` exists, CTA variant not yet built |
| FB5 | Loading state — spinner with text | `documents/view.html`, `regulations.html` | 🟡 Missing — `.htmx-indicator` CSS for HTMX, no skeleton yet |
| FB6 | Error state — danger alert with message and back-link | `documents/view.html`, `search/chunks.html` | 🟡 Partial — error pages exist, inline error states not yet built |
| FB7 | Warning box — left-bordered alert with WARNING prefix | `corpus-health.html` (municipal) | 🔴 Future — Phase 5 (Corpus) |
| FB8 | Info alert — blue information box with descriptive text | `documents/list.html`, `jobs/list.html` (municipal) | 🟡 Missing — `.message--info` variant needed |

### 1.6 Layout Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| L1 | Full-page shell — header, main content, footer | `layout/default.html` (current) | ✅ Implemented — `layout/default.html` |
| L2 | Auth layout — centered card, no navigation | `auth/login.html` (both) | ✅ Implemented — `activeSection='none'` triggers minimal layout |
| L3 | Dashboard layout — full-width content, no sidebar | `dashboard.html` (municipal) | ✅ Implemented — `activeSection='dashboard'` |
| L4 | Section layout — sidebar + main content | Current project spec | 🟡 Missing — needed for Phase 2 |
| L5 | Two-column panel layout — left/right cards | `home.html`, `admin.html`, `workspaces/view.html` (municipal) | 🔴 Future — Phase 2+ |
| L6 | Three-column explorer — tree + preview + metadata | `regulations.html` (municipal) | 🔴 Future — Phase 3 (Knowledge Browse) |
| L7 | Wizard layout — progress bar + stage content + nav buttons | `workspaces/wizard.html` (municipal) | 🔴 Future — Phase 2 |
| L8 | Tabbed detail — tab bar + content panels | Current project spec (Screen 7: Case Detail) | 🟡 Missing — needed for Phase 2 |

### 1.7 Interactive Patterns

| # | Pattern | Where Found (Legacy) | Current Status |
|---|---|---|---|
| I1 | Dropdown menu — click toggle, click-away dismiss | `fragments/layout.html` (user menu, language) | ✅ Implemented — Alpine.js `x-data` + `@click.away` in `header.html` |
| I2 | Modal dialog — overlay, backdrop click to close, escape key | Current project spec | 🟡 Missing — `components/modal.html` spec exists, not yet built |
| I3 | Confirmation dialog — modal with confirm/cancel | Current project spec | 🟡 Missing — `components/modal :: confirmDelete` spec exists |
| I4 | Tab switcher — click tab, show panel, URL update | Current project spec (Screen 7) | 🟡 Missing — needed for Phase 2 |
| I5 | Collapsible section — toggle visibility of content block | Current project spec | 🟡 Missing — Alpine.js `x-show` |
| I6 | Drag-and-drop zone — file drop target with visual feedback | `documents/upload.html` (municipal) | 🔴 Future — Phase 3 |
| I7 | Citation hover popup — floating card on hover over document reference | `documents/view.html`, `ai/index.html` (municipal) | 🔴 Future — Phase 3-4 |
| I8 | Live polling — auto-refresh table every N seconds | `jobs/list.html` (4s), `dashboard.html` (provider status) | 🟡 Partial — HTMX polling configured, not yet used |
| I9 | SSE streaming — token-by-token answer display | Current project spec (Screen 29: Assistant) | 🔴 Future — Phase 4 |
| I10 | Graph visualization — force-directed network with node inspector | `graph.html` (municipal) | 🔴 Future — Phase 3 (Knowledge Graph) |

---

## 2. Component Catalog — Implementation Specifications

### 2.1 Rich Metric Card

**Purpose**: Display a grouped set of related statistics with a status summary.

**Where used**: Dashboard.

**Semantic structure**:
- **Header row**: Title text (left), status badge pill (right)
- **Grid body**: 2-column grid of label-value pairs. Label = uppercase, muted, small. Value = bold, body-sized.
- **Footer**: One-line summary text, muted, small. Optional status dot prefix.

**Recommended Thymeleaf fragment**:
```html
<!-- fragments/components :: richMetric(title, badgeText, badgeStyle, items, footer) -->
<!-- items = List<{label, value}> -->
<!-- badgeStyle = 'ok' | 'warn' -->
```

**CSS classes**: `.rich-metric`, `.rich-metric__header`, `.rich-metric__title`, `.rich-metric__badge`, `.rich-metric__badge--ok`, `.rich-metric__badge--warn`, `.rich-metric__grid`, `.rich-metric__item`, `.rich-metric__label`, `.rich-metric__value`, `.rich-metric__footer`

**Accessibility**: Each rich metric card is a `<section>` with `aria-label`. The status badge has `aria-label` describing the status. Values use `<data>` elements where appropriate.

**Responsive behavior**: Grid collapses from 3 columns (desktop) to 2 (tablet) to 1 (mobile). Cards stack vertically, full-width.

**Future reuse**: Phase 5 Corpus dashboard (document health metrics), Phase 5 Admin dashboard (system metrics).

---

### 2.2 Status Dot

**Purpose**: Small colored circle indicating operational state.

**Where used**: Dashboard system card, metric card footers, domain cards. Future: provider status, corpus health.

**Semantic structure**: An 8px circle in one of four colors: green (operational), amber (degraded), red (down), gray (unknown).

**CSS classes**: `.status-dot`, `.status-dot--green`, `.status-dot--amber`, `.status-dot--red`

**Accessibility**: Each dot has `aria-label` ("Betriebsbereit", "Eingeschränkt", "Ausgefallen"). For screen readers, the dot is accompanied by visible or sr-only text.

**Responsive behavior**: Fixed 8px, no changes needed at any viewport.

**Future reuse**: Phase 5 Admin (provider connectivity), Phase 5 Corpus (document processing status per category).

---

### 2.3 Stat Card

**Purpose**: Display a single highlighted number with a context label.

**Where used**: Dashboard stat row. Future: Analytics, Corpus health, Admin.

**Semantic structure**:
- **Count**: Large number (2rem, bold)
- **Label**: Small text below the count (0.8125rem, muted)
- Optional: Color-coded count (green for ready, amber for processing)

**CSS classes**: `.stat-card`, `.stat-card__count`, `.stat-card__label`, `.stat-card--ready`, `.stat-card--cases`, `.stat-card--processing`

**Accessibility**: The count and label form a single `<div>` with `aria-label` combining both (e.g., "10 Dokumente bereit").

**Responsive behavior**: In a `.dashboard__stats` grid — `auto-fit` with `minmax(220px, 1fr)`. 3 per row at desktop, 2 at tablet, 1 at mobile.

---

### 2.4 Domain Card

**Purpose**: Navigational card representing a department, workspace, or knowledge category with descriptive text.

**Where used**: Dashboard domain grid. Future: Case list (compact variant), Knowledge categories.

**Semantic structure**:
- **Header**: Emoji icon in a rounded square + name (bold)
- **Body**: Description paragraph (small, muted, 2-3 lines)
- **Footer meta**: 2-3 items (document count, primary regulation, readiness dot)

**Recommended Thymeleaf fragment**:
```html
<!-- fragments/components :: domainCard(icon, name, description, metaItems, url) -->
<!-- metaItems = List<{text, isBold}> -->
```

**CSS classes**: `.domain-card`, `.domain-card__header`, `.domain-card__icon`, `.domain-card__name`, `.domain-card__desc`, `.domain-card__meta`

**Accessibility**: The entire card is an `<a>` element for keyboard navigation. `aria-label` describes the destination (e.g., "Fachbereich Bauen & Stadtplanung — 8 Dokumente, bereit").

**Responsive behavior**: Grid `auto-fit` with `minmax(280px, 1fr)`. 3 per row desktop, 2 tablet, 1 mobile.

**Future reuse**: Phase 2 (Case list — compact variant with case name, phase, document count), Phase 3 (Knowledge categories).

---

### 2.5 Filter Bar

**Purpose**: Horizontal form for filtering a data list by multiple criteria.

**Where used**: Case list, Document list, Audit log, Ingestion jobs. All Phase 2-5 list screens.

**Semantic structure**:
- **Row 1**: Configurable set of filter controls: status dropdown, type dropdown, category dropdown, search text input
- **Row 2 (optional)**: Date range, actor filter, correlation ID filter
- **Submit**: "Filtern" button (or automatic via HTMX on change)

**Recommended Thymeleaf fragment**:
```html
<!-- fragments/components :: filterBar(filters, actionUrl) -->
<!-- filters = List<{name, type, options, value}> -->
<!-- type = 'select' | 'text' | 'date' -->
```

**CSS classes**: `.filter-bar`, `.filter-bar__row`, `.filter-bar__field`, `.filter-bar__label`, `.filter-bar__input`, `.filter-bar__submit`

**HTMX interaction**: Each filter control triggers `hx-get` on change with 300ms debounce for text inputs. Target is the adjacent table fragment. `hx-push-url="true"` to preserve filter state in URL.

**Accessibility**: Each filter control has a `<label>`. The form has `role="search"` (for search variants) or `aria-label="Filter"`.

**Responsive behavior**: Filter controls wrap on mobile. Stacked vertically instead of horizontal row.

---

### 2.6 Paginated Data Table

**Purpose**: Sortable, filterable table with pagination and per-row actions.

**Where used**: Case list, Document list, Knowledge search results, Audit log, Ingestion jobs. Six screens across Phases 2-5.

**Semantic structure**:
- **Header row**: Column labels, left-aligned. Uppercase, small, muted, semi-bold. Sortable columns have a sort direction indicator.
- **Body rows**: One row per record. Striped background. Last column typically holds action buttons.
- **Per-row elements**: Status badge (colored pill), type badge (gray pill), monospaced UUID (truncated with tooltip), action buttons (edit, delete, reindex, etc.)
- **Pagination footer**: Previous link, "Page X of Y" counter, Next link.

**Recommended Thymeleaf fragment**:
```html
<!-- fragments/components :: dataTable(headers, rows, page, totalPages, baseUrl) -->
<!-- headers = List<{key, label, sortable}> -->
<!-- rows = List<Map<String, Object>> -->
```

**CSS classes**: `.data-table`, `.data-table__header`, `.data-table__row`, `.data-table__cell`, `.data-table__actions`, `.data-table__pagination`

**HTMX interaction**: Filter triggers update table body. Pagination links trigger `hx-get` with page parameter. Sort headers trigger `hx-get` with sort parameter. All with `hx-push-url="true"` and `hx-target="#table-container"`.

**Accessibility**: `<table>` with `<thead>` and `<tbody>`. Sortable columns are `<th>` with `aria-sort`. Action buttons have `aria-label`. Pagination has `aria-label="Pagination"`.

**Responsive behavior**: On mobile, table becomes a card list — each row transforms into a stacked card with label-value pairs. Column headers become labels.

---

### 2.7 Empty State

**Purpose**: Placeholder shown when a list, table, or search produces no results.

**Where used**: Every list/search screen. Three variants.

**Variant A — Simple** (dashboard activity, audit):
- Icon (optional)
- Title: "Keine Aktivität" / "Keine Ergebnisse"
- Description: Explains when data will appear

**Variant B — With CTA** (cases, workspaces):
- Icon
- Title: "Keine Fälle" / "Keine Dokumente"
- Description: Helpful context
- Primary action button: "Ersten Fall erstellen" / "Dokument hochladen"

**Variant C — Search empty** (knowledge search, document search):
- Icon: search/magnifier
- Title: "Keine Ergebnisse für '[query]'"
- Description: "Überprüfen Sie die Schreibweise oder versuchen Sie einen anderen Begriff."
- Link: "Filter zurücksetzen"

**Recommended Thymeleaf fragment**:
```html
<!-- fragments/components :: emptyState(variant, title, description, actionUrl, actionLabel) -->
```

**CSS classes**: `.empty-state`, `.empty-state__icon`, `.empty-state__title`, `.empty-state__desc`, `.empty-state__action`

**Accessibility**: Has `role="status"` or `aria-live="polite"` when dynamically inserted via HTMX.

---

### 2.8 Alert / Message Banner

**Purpose**: Contextual feedback message — error, success, warning, or info.

**Where used**: Login form (error/success/logout messages), upload form (validation errors), future: all HTMX form submissions.

**Semantic variants**:
- `error` — red background, red border. For: invalid credentials, server errors, validation failures.
- `success` — green background, green border. For: registration complete, logout successful, document saved.
- `warning` — amber background, amber border. For: session expired, degraded service.
- `info` — blue background, blue border. For: lifecycle explanation, helpful hints.

**CSS classes**: `.message`, `.message--error`, `.message--success`, `.message--warning`, `.message--info`

**Accessibility**: `role="alert"` for error messages. `aria-live="polite"` for dynamically inserted messages.

**Responsive behavior**: Full width within container. Padding increases on mobile for touch-friendly dismiss.

---

### 2.9 Loading State

**Purpose**: Visual feedback during asynchronous operations.

**Where used**: HTMX requests (global), document content loading, search results loading, analysis running.

**Variants**:
- **Inline spinner** (HTMX): Shown via `htmx-indicator` class. Small spinner inline with the triggering element.
- **Content skeleton**: Gray animated placeholder bars matching the expected content shape.
- **Full overlay**: Semi-transparent overlay with centered spinner. For: document analysis, batch import.
- **Polling indicator**: Small pulsing dot with label text. For: analysis status, import progress.

**CSS classes**: `.htmx-indicator` (global HTMX), `.loading-spinner`, `.loading-skeleton`, `.loading-overlay`, `.loading-pulse`

**Accessibility**: `aria-busy="true"` on the loading container. `aria-label="Lade..."` on the spinner. Screen readers announce loading state changes.

---

### 2.10 Tab Bar

**Purpose**: Switch between multiple content panels within a single page.

**Where used**: Case detail (Overview, Documents, Timeline, Checklist, Notes), Document detail (Metadata, Content, Chunks, Versions).

**Semantic structure**:
- **Tab list**: Horizontal row of tab buttons. Active tab has bottom border and primary color.
- **Tab panels**: Content area showing one panel at a time. Panel visibility controlled by Alpine.js.

**CSS classes**: `.tab-bar`, `.tab-bar__tab`, `.tab-bar__tab--active`, `.tab-panel`

**HTMX interaction**: Pre-loaded tabs use Alpine.js `x-show`. Lazy-loaded tabs use HTMX `hx-get` on first click to fetch panel content. Tab state reflected in URL via `hx-push-url="true"` with `?tab=` parameter.

**Accessibility**: Uses `role="tablist"`, `role="tab"`, `role="tabpanel"` ARIA attributes. Arrow keys navigate between tabs. Active tab has `aria-selected="true"`.

---

### 2.11 Pagination Control

**Purpose**: Navigate between pages of a data set.

**Where used**: Every list screen with >1 page of results.

**Semantic structure**:
- **Previous button**: Link/button, disabled on first page. `<` or "Zurück".
- **Page indicator**: "Seite X von Y". Current page bold.
- **Next button**: Link/button, disabled on last page. `>` or "Weiter".

**CSS classes**: `.pagination`, `.pagination__prev`, `.pagination__indicator`, `.pagination__next`, `.pagination--disabled`

**HTMX interaction**: Previous/Next trigger `hx-get` with `?page=N`. Target is the table/list container. `hx-push-url="true"`.

**Accessibility**: `<nav aria-label="Pagination">`. Current page has `aria-current="page"`. Disabled buttons have `aria-disabled="true"`.

---

### 2.12 Confirmation Dialog

**Purpose**: Require explicit user confirmation before a destructive action.

**Where used**: Delete case, delete document, purge document, archive document.

**Semantic structure**:
- **Overlay**: Semi-transparent backdrop, covers viewport.
- **Dialog**: Centered card with title ("Löschen bestätigen"), message, two buttons.
- **Cancel button**: Secondary/outline style. Closes dialog.
- **Confirm button**: Primary/danger style. Executes the action via HTMX.

**CSS classes**: `.modal-overlay`, `.modal-dialog`, `.modal__title`, `.modal__message`, `.modal__actions`

**Alpine.js**: `x-show` on overlay, `x-trap` for focus trapping, `@keydown.escape` to close, `@click.outside` to close.

**HTMX**: Confirm button triggers `hx-delete` or `hx-post` with the appropriate endpoint. On success, the deleted row is removed from the DOM and a success toast appears.

**Accessibility**: `role="dialog"`, `aria-modal="true"`, `aria-labelledby` pointing to title. Focus trapped inside dialog. Focus returns to trigger element on close.

---

### 2.13 Dropdown Menu

**Purpose**: Reveal a list of actions or options on click.

**Where used**: User menu (Profile, Logout), language selector (future), row action menus (future).

**Semantic structure**:
- **Trigger**: Button or text, with caret or ellipsis icon.
- **Menu**: Absolute-positioned list below trigger. White background, border, shadow. Items are links or buttons.

**CSS classes**: `.dropdown`, `.dropdown__trigger`, `.dropdown__menu`, `.dropdown__item`

**Alpine.js**: `x-data="{ open: false }"`, `@click="open = !open"`, `@click.away="open = false"`, `@keydown.escape="open = false"`, `x-show="open"`, `x-transition`

**Accessibility**: Trigger has `aria-haspopup="true"` and `aria-expanded`. Menu has `role="menu"`. Items have `role="menuitem"`. Arrow keys navigate between items.

---

### 2.14 Wizard Progress Indicator

**Purpose**: Show progress through a multi-step workflow.

**Where used**: Case creation wizard (future Phase 2+).

**Semantic structure**:
- **Progress bar**: Horizontal bar divided into N segments. Each segment colored: gray (not yet reached), blue (current), green (completed).
- **Step labels**: Below each segment, the phase name. Current step is bold.
- **Stage indicator**: "Schritt N von M: Phasenname" text above the bar.

**CSS classes**: `.wizard-progress`, `.wizard-progress__segment`, `.wizard-progress__segment--complete`, `.wizard-progress__segment--current`, `.wizard-progress__label`

**Accessibility**: `role="progressbar"` with `aria-valuenow`, `aria-valuemin`, `aria-valuemax`.

---

### 2.15 Classification Badge

**Purpose**: Display a document or evidence classification category.

**Where used**: Case analysis (FACTUAL/REFERENCE/MIXED), future decision workspace.

**Semantic variants**:
- FACTUAL (green): Document contains factual evidence.
- REFERENCE (blue): Document is a reference/regulation.
- MIXED (amber): Contains both factual and reference content.

**CSS classes**: `.classification-badge`, `.classification-badge--factual`, `.classification-badge--reference`, `.classification-badge--mixed`

**Accessibility**: `aria-label` describes the classification (e.g., "Klassifizierung: Faktisch").

---

## 3. Mapping to Current Project

### 3.1 Patterns Already Implemented (15)

| Pattern | Implementation | File(s) |
|---|---|---|
| Top navigation bar | `fragments/header.html` — Alpine.js dropdown, `sec:authorize` role visibility | `header.html`, `application.css` |
| Active nav highlighting | `currentUri` via `WebModelAdvice`, `#strings.startsWith()` in header | `WebModelAdvice.java`, `header.html` |
| Breadcrumb trail | `fragments/breadcrumbs.html` — `th:fragment="breadcrumbs(items)"` | `breadcrumbs.html` |
| Rich metric card | `.rich-metric` CSS, three cards in `dashboard/index.html` | `application.css`, `dashboard/index.html` |
| Stat card | `.stat-card` CSS | `application.css` |
| Quick action panel | `.quick-actions`, `.quick-action` CSS | `application.css`, `dashboard/index.html` |
| Activity table | `.activity-table` CSS | `application.css`, `dashboard/index.html` |
| Centered auth form | `.auth-page`, `.auth-card` CSS | `application.css`, `auth/login.html` |
| Alert messages | `.message--error/success/warning` CSS | `application.css`, `fragments/messages.html` |
| Toast notification | `fragments/toast.html`, `showToast()` JS | `toast.html`, `app.js` |
| Empty state | `.empty-state`, `.empty-state__title`, `.empty-state__desc` CSS | `application.css`, `dashboard/index.html` |
| Status dot | `.status-dot`, `.status-dot--green/amber/red` CSS | `application.css` |
| Dropdown menu | Alpine.js `x-data` + `@click.away` in `header.html` | `header.html` |
| Type badge | `.activity-table__type` CSS | `application.css` |
| Full-page layout shell | `layout/default.html` — `th:fragment="layout"` | `default.html` |

### 3.2 Patterns Needing Refinement (8)

| Pattern | What's Missing | Priority |
|---|---|---|
| System health bar | D2 — row of status dots with labels; CSS exists, no template fragment | Phase 5 |
| Domain/workspace card | D3 — `.domain-card` CSS exists, no reusable fragment yet | Phase 2 |
| Filter bar | T1 — needed for Case List (P2) and Document List (P3) | Phase 2 |
| Paginated data table | T2 — needed for 6 screens across Phases 2-5 | Phase 2 |
| Pagination control | T3 — needed for Phase 2 list screens | Phase 2 |
| Info alert variant | FB8 — `.message--info` CSS variant | Phase 2 |
| Empty state with CTA | FB4 — CTA button variant | Phase 2 |
| Loading skeleton | FB5 — CSS skeleton animation | Phase 2 |
| Confirmation dialog | I3 — modal with confirm/cancel, spec exists | Phase 2 |
| Modal dialog | I2 — Alpine.js modal, spec exists | Phase 2 |

### 3.3 Patterns Missing Entirely (Future Phases)

| Pattern | Needed For | Phase |
|---|---|---|
| Upload drop zone (I6) | Document Upload | Phase 3 |
| Metadata key-value table (T9) | Document Detail, Knowledge Detail | Phase 3 |
| Search form (F3) | Knowledge Search | Phase 3 |
| Authority classification badge (T10) | Case Analysis, Decision Workspace | Phase 2, 4 |
| Three-column explorer (L6) | Knowledge Browse | Phase 3 |
| Graph visualization (I10) | Knowledge Graph | Phase 3 |
| Citation hover popup (I7) | Document View, AI Assistant | Phase 3-4 |
| Progress bar (T8) | Batch Import, Analysis | Phase 3 |
| Priority badge (T6) | Corpus | Phase 5 |
| Wizard components (F6, F7, L7) | Case Creation Wizard | Phase 2 |
| Tab bar (I4) | Case Detail, Document Detail | Phase 2 |
| Collapsible section (I5) | Accordion sections | Phase 2 |
| Two-column panel (L5) | Case Detail, Decision Workspace | Phase 2, 4 |
| SSE streaming (I9) | AI Assistant | Phase 4 |

---

## 4. Layout Pattern Reference

### 4.1 Full-Page Shell

```
┌──────────────────────────────────────────────┐
│ HEADER: brand, nav links, user menu           │
├──────────────────────────────────────────────┤
│ BREADCRUMB (optional)                         │
├──────────────────────────────────────────────┤
│ MESSAGES (flash/alert)                        │
├──────────────┬───────────────────────────────┤
│ SIDEBAR      │ MAIN CONTENT                  │
│ (optional)   │                               │
│              │                               │
│ filters      │ page-specific content         │
│ actions      │                               │
│ sub-nav      │                               │
│              │                               │
├──────────────┴───────────────────────────────┤
│ FOOTER: version, copyright                    │
└──────────────────────────────────────────────┘
```

**Thymeleaf**: `layout/default.html` — `th:fragment="layout(content, pageTitle, activeSection, breadcrumbs)"`
**Sidebar**: Omitted when `activeSection='none'` (login, register) or `activeSection='dashboard'`.
**CSS**: `.app-layout`, `.app-layout--no-sidebar`, `.topnav`, `.breadcrumbs`, `.footer`

### 4.2 Centered Auth / Minimal Layout

```
┌──────────────────────────────────────────────┐
│                                              │
│         ┌──────────────────────┐              │
│         │    Centered Card     │              │
│         │    max-width 480px   │              │
│         └──────────────────────┘              │
│                                              │
└──────────────────────────────────────────────┘
```

**When used**: Login, Register. Future: standalone forms, error pages.
**Thymeleaf**: `th:replace="~{layout/default :: layout(~{::content}, 'Title', 'none', null)}"`
**CSS**: `.app-layout--no-sidebar` centers content vertically. `.auth-card` constrains width.

### 4.3 Dashboard Layout

```
┌──────────────────────────────────────────────┐
│ HEADER: full nav                              │
├──────────────────────────────────────────────┤
│ BREADCRUMB: Home                              │
├──────────────────────────────────────────────┤
│ GREETING                                      │
├──────────────────────────────────────────────┤
│ RICH METRIC CARDS (3 columns)                 │
├──────────────────────────────────────────────┤
│ QUICK ACTIONS                                 │
├──────────────────────────────────────────────┤
│ ACTIVITY TABLE                                │
├──────────────────────────────────────────────┤
│ FOOTER                                        │
└──────────────────────────────────────────────┘
```

**When used**: Dashboard.
**Thymeleaf**: `th:replace="~{layout/default :: layout(~{::content}, 'Dashboard', 'dashboard', ${breadcrumbs})}"`
**CSS**: `.dashboard` — max-width 960px. `.rich-metrics` — auto-fit grid. `.activity-table` — full-width.

### 4.4 Section Layout (with Sidebar)

```
┌──────────────────────────────────────────────┐
│ HEADER                                        │
├──────────────┬───────────────────────────────┤
│ SIDEBAR      │ TOOLBAR (filter, search, new)  │
│              ├───────────────────────────────┤
│ filters      │ DATA TABLE / CARD GRID         │
│ status       │                               │
│ categories   │                               │
│              ├───────────────────────────────┤
│ actions      │ PAGINATION                     │
│              │                               │
└──────────────┴───────────────────────────────┘
```

**When used**: Case List, Document List, Knowledge Search, Audit Log, Corpus. All Phase 2-5 list pages.
**Thymeleaf**: `th:replace="~{layout/default :: layout(~{::content}, 'Title', 'cases', ${breadcrumbs})}"`
**CSS**: `.app-layout` uses CSS Grid with sidebar column (260px) + main column (1fr). On tablet: sidebar collapses. On mobile: sidebar hidden, toggleable.

---

## 5. Pattern → Fragment Mapping

Every pattern that appears on 2+ screens must be extracted into a reusable Thymeleaf fragment in `templates/fragments/components.html`.

| Pattern | Fragment Signature | Status |
|---|---|---|
| Rich metric card | `components :: richMetric(title, badge, items, footer)` | ✅ Exists in `dashboard/index.html` inline — extract when reused |
| Stat card | `components :: statCard(count, label, variant)` | 🟡 Needed Phase 5 (Corpus, Admin) |
| Domain card | `components :: domainCard(icon, name, desc, meta, url)` | 🟡 Needed Phase 2 (Case List compact variant) |
| Status dot | `components :: statusDot(state)` | 🟡 Inline — extract if reused outside dashboard |
| Empty state | `components :: emptyState(variant, title, desc, actionUrl, actionLabel)` | 🟡 Needed Phase 2 |
| Alert message | `components :: alert(type, message)` | ✅ `fragments/messages.html` — extend for info variant |
| Pagination | `components :: pagination(page, totalPages, baseUrl)` | 🟡 Needed Phase 2 |
| Data table | `components :: dataTable(headers, rows, page, totalPages)` | 🟡 Needed Phase 2 |
| Filter bar | `components :: filterBar(filters, actionUrl)` | 🟡 Needed Phase 2 |
| Modal dialog | `components :: modal(id, title)` | 🟡 Needed Phase 2 |
| Confirmation dialog | `components :: confirmDelete(id, message, url)` | 🟡 Needed Phase 2 |
| Loading spinner | `components :: spinner(text)` | 🟡 Needed Phase 2 |
| Loading skeleton | `components :: skeleton(lines)` | 🟡 Needed Phase 2 |
| Tab bar | `components :: tabBar(tabs)` | 🟡 Needed Phase 2 |
| Badge | `components :: badge(text, variant)` | 🟡 Partial — type badge exists |

---

## 6. Duplicated Patterns — Consolidation Required

These patterns currently exist in multiple forms across the application or risk duplication in future phases.

### 6.1 Activity Display

**Currently**: `.activity-table` in `dashboard/index.html` displays audit events as a table.
**Future risk**: Phase 2 (Case Timeline) will display timeline events differently. Phase 5 (Audit Log) will use a full-featured table.
**Resolution**: One activity component with two variants:
- `activity :: table` — tabular display with timestamp, type badge, entity, correlation columns
- `activity :: timeline` — vertical timeline with dots, dates, descriptions

### 6.2 Stat Display

**Currently**: `.stat-card` and `.rich-metric` are separate implementations.
**Future risk**: Phase 5 (Corpus Health, Admin) will add 5-stat and 4-stat card rows.
**Resolution**: `.stat-card` for simple count+label pairs. `.rich-metric` for grouped multi-field displays. Do not merge — they serve different information densities.

### 6.3 Empty States

**Currently**: `.empty-state` with title + description in `dashboard/index.html`.
**Future risk**: Every list screen needs an empty state. Three variants (simple, CTA, search) would be copy-pasted.
**Resolution**: Single `components :: emptyState` fragment with variant parameter controlling icon, action button, and description text.

### 6.4 Filter Forms

**Currently**: Not yet implemented.
**Future risk**: Case List, Document List, Audit Log, and Ingestion Jobs all need filters. If built independently, they'll have inconsistent markup.
**Resolution**: Build `components :: filterBar` once, use everywhere. Configurable via `filters` parameter.

---

## 7. Missing Reusable Fragments — Build Queue

Ordered by dependency (what blocks the most future screens):

### Queue 1 — Blocks Phase 2 (Cases)

1. **`components :: dataTable`** — Needed by Case List, Document List, Knowledge Search, Audit Log, Ingestion Jobs
2. **`components :: pagination`** — Companion to dataTable
3. **`components :: filterBar`** — Companion to dataTable
4. **`components :: emptyState`** — Needed by every list screen
5. **`components :: modal`** + **`components :: confirmDelete`** — Needed for delete operations
6. **`components :: tabBar`** — Needed for Case Detail tabs, Document Detail tabs

### Queue 2 — Blocks Phase 3 (Documents & Knowledge)

7. **`components :: searchBox`** — HTMX search-as-you-type with debounce
8. **`components :: uploadZone`** — Drag-and-drop file upload with Alpine.js
9. **`components :: metadataTable`** — Key-value display for document metadata
10. **`components :: domainCard`** — Reusable domain/category card

### Queue 3 — Blocks Phase 4-5

11. **`components :: wizard`** — Multi-step progress + navigation
12. **`components :: progressBar`** — Batch import / analysis progress
13. **`components :: badge`** — Unified badge (status, type, priority, classification variants)
14. **`components :: citationPopup`** — Hover popup for document citations
15. **`components :: loadingSkeleton`** — Content placeholder animation

---

## 8. Accessibility Standards

Every pattern must meet these minimums:

| Requirement | Standard |
|---|---|
| Color contrast | WCAG AA (4.5:1 for text, 3:1 for large text) |
| Focus indicators | Visible focus ring on all interactive elements (`--focus-ring: 0 0 0 3px var(--color-primary-100)`) |
| Keyboard navigation | All interactive elements reachable via Tab. Dropdowns/menus via arrow keys. Modals via focus trap. |
| Screen reader | All icons have `aria-label`. Status changes use `aria-live`. Dynamic content uses `role="status"` or `role="alert"`. |
| Reduced motion | `@media (prefers-reduced-motion: reduce)` disables animations |
| Touch targets | Minimum 36px × 36px interactive area (`--size-touch-min`) |

---

## 9. Responsive Breakpoints

| Breakpoint | Width | Layout Change |
|---|---|---|
| Desktop | ≥ 1024px | Full layout: sidebar (260px) + main content. Cards in 3 columns. Tables show all columns. |
| Tablet | 768px – 1023px | Sidebar collapses to icon-only or hidden. Cards in 2 columns. Tables hide secondary columns. |
| Mobile | < 768px | Sidebar hidden (hamburger toggle). Cards in 1 column. Tables become card lists. Navigation links collapsed. |

---

## 10. Implementation Conventions

### CSS Class Naming

BEM-like with double-underscore for elements, double-dash for modifiers:
```
.block__element--modifier
```

Examples: `.rich-metric__badge--ok`, `.status-dot--green`, `.data-table__row--selected`

### Fragment Naming

```
{category}/{file} :: {fragmentName}
```

Examples: `components/table :: dataTable`, `cases/fragments :: caseTable`

### HTMX Attribute Pattern

Every HTMX-powered element specifies: trigger, target, swap, push-url, indicator.
```html
<form hx-get="/cases"
      hx-trigger="change"
      hx-target="#case-table"
      hx-swap="outerHTML"
      hx-push-url="true"
      hx-indicator="#spinner">
```

### Controller → Fragment Convention

Controllers detect HTMX via `HX-Request` header and return fragment templates:
```java
if (hxRequest != null) return "cases/fragments :: caseTable";
return "cases/list";
```

---

## 11. Migration Completion Status

| Category | Total Patterns | Implemented | Refinement Needed | Missing |
|---|---|---|---|---|
| Navigation | 5 | 3 | 2 | 0 |
| Dashboard | 6 | 5 | 1 | 0 |
| Data Display | 10 | 2 | 5 | 3 |
| Forms | 7 | 1 | 3 | 3 |
| Feedback | 8 | 4 | 4 | 0 |
| Layout | 8 | 3 | 1 | 4 |
| Interactive | 10 | 1 | 3 | 6 |
| **Total** | **54** | **19 (35%)** | **19 (35%)** | **16 (30%)** |

**Phase 1 complete (Login + Dashboard)**: 19 patterns implemented.
**Phase 2 target**: Add 10 patterns (dataTable, pagination, filterBar, emptyState, modal, confirmDelete, tabBar, domainCard, info alert, skeleton).
**Phase 3 target**: Add 7 patterns (searchBox, uploadZone, metadataTable, graph, progressBar, citationPopup).
**Phase 4-5 target**: Add remaining 6 patterns.

---

END OF UI PATTERN LIBRARY
