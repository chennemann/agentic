# Bundled Mermaid renderer

- Version: 12.1.0 (MIT; see LICENSE).
- Source: https://cdn.jsdelivr.net/npm/mermaid@12.1.0/dist/mermaid.min.js
- SHA-256: 6484afc32872a3aa16cac9a76ba1816a1ed4cc870a6593cc2e17757750f518b2

This asset ships inside the Android library. Rendering requires neither a CDN
connection nor the sibling T3 checkout. Upgrade the pinned asset and license
together, update this checksum, and run MermaidRenderingTest on Android.

MermaidBlock loads this asset through an intercepted, synthetic HTTPS origin.
All other resource requests and navigation are denied. Diagram source is encoded
as data, with strict Mermaid security, no native JavaScript bridge, no credentials,
and no file/content access. A content security policy also denies network connections.
The document uses the strict configuration described at
https://mermaid.js.org/config/schema-docs/config-properties-securitylevel.html.
