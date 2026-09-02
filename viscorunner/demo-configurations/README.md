# Demo configurations

The reference-implementation Frank!Framework configurations mounted by the demo
overlay (`docker-compose.demo.yml`). Auto-discovered: every subdirectory is
loaded as one F!F configuration.

## Conventions

These configurations are the examples people copy — keep them exemplary:

- **Never put markup in an XML attribute.** Escaped sequences
  (`&lt;tag&gt;`, `&quot;json&quot;`) in attributes are unreadable and fragile.
  Instead:
  - trivial scalar values stay inline (`message="adt-cardio"`, wrapped in the
    pipeline with `Text2XmlPipe` when a pipe needs XML input);
  - structured or multiline payloads live in their own file next to the
    configuration (`FixedResultPipe filename="responses/x.json"`, stylesheets
    under `xslt/`), loaded by the config and — where needed downstream — put
    into a sessionKey;
  - inside XSLT, build text payloads in element content (`<xsl:text>`), where
    quotes are literal, not in `select` attribute expressions.
- **FHIR format conversion is FHIR-aware.** Flows build and pass FHIR as XML;
  the delivering adapter converts to the destination's configured mimetype with
  `com.viscosiety.pipes.FhirFormatPipe` (see `fhir-delivery/`). Never use the
  generic `JsonPipe` on FHIR: it cannot know that FHIR JSON requires arrays
  (`entry`, `identifier`, …) even for single elements. `JsonPipe` remains fine
  for non-FHIR JSON.
- Entity-composed configurations (`<!ENTITY x SYSTEM "Configuration-x.xml">`)
  keep each adapter in its own file.
- Demo traffic and seeded failures are driven by `demo-traffic/`
  (kill switch: `-Ddemo.traffic.active=false`).
