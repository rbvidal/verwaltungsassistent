# Third-party notices

The Verwaltungsassistent (Apache License 2.0, see [LICENSE](LICENSE)) uses the
following third-party components, data and services. This file lists the
attribution and license obligations that are relevant when redistributing the
application or running the Docker stack.

---

## Java dependencies

Most dependencies are Apache-2.0, MIT or BSD licensed (Spring Boot, PDFBox,
Apache Tika, jsoup, POI, metadata-extractor, PostgreSQL JDBC, htmx, Alpine.js,
Leaflet). Two require explicit attention:

| Component | License | Note |
|---|---|---|
| **OpenHTMLToPDF 1.0.10** (`com.openhtmltopdf`, PDF export) | **LGPL-2.1-or-later** | Used as an unmodified library. The application depends on it dynamically (Spring Boot fat jar); LGPL requires that users can replace the library and relink — keep OpenHTMLToPDF unmodified and document the version. License text: <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html> |
| DejaVu fonts (`verwaltungsassistent-web/src/main/resources/fonts/`) | Bitstream Vera / DejaVu license (permissive) | License text is bundled next to the font files. |

Self-hosted web fonts (Inter, JetBrains Mono, Public Sans — OFL-1.1;
Material Symbols — Apache-2.0) are attributed in
`verwaltungsassistent-web/src/main/resources/static/fonts/NOTICE.txt`.

## Map data and map tiles

| Item | Source | License | Obligation |
|---|---|---|---|
| District boundaries, addresses, street network (`verwaltungsassistent-web/src/main/resources/geodata/`) | OpenStreetMap (via Overpass API / polygons.openstreetmap.fr, retrieved 2026-08) | **ODbL 1.0** | Attribution "© OpenStreetMap-Mitwirkende (ODbL)" is shown on the geo pages. Keep it there when reusing the data. |
| Map tiles | `https://tile.openstreetmap.org` (loaded at runtime by the browser) | OSM tile usage policy | Only loaded on demand; for production deployments consider your own tile server. See <https://operations.osmfoundation.org/policies/tiles/> |

## Demo content

The demo documents (`deploy/demo-dataset/`) are project-authored summaries of
German statutes (public domain) and project-generated service-description PDFs;
the demo photos are project-owned practice photos. They are provided as demo
material under the same license as the project.

## Docker images referenced by the stack

The `compose.yaml` stack references upstream images; they are **not**
redistributed by this repository, but their licenses matter when packaging
appliances:

| Image | License |
|---|---|
| `pgvector/pgvector:pg16` | PostgreSQL License |
| `qdrant/qdrant:v1.15.5` | Apache-2.0 |
| `neo4j:5.26-community` | **GPLv3** (Community Edition) — redistributing a derived Neo4j image/VM triggers GPLv3 obligations; only referencing the upstream image in a compose file does not |
| `ollama/ollama:0.34.0` | MIT (model weights are licensed separately by their publishers) |
| `eclipse-temurin` / `maven` | GPLv2 with Classpath Exception / Apache-2.0 |

Build reproducibility: the `Dockerfile` pins the Maven builder and JRE runtime
images by digest; `compose.yaml` pins Qdrant, Neo4j and Ollama by version tag.
