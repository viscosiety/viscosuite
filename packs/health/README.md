# ViscoLink healthcare pack

The healthcare pack (`viscolink-pack-health`) adds FHIR facades, MLLP, the HL7v2 and FHIR pipes, the `patientId` subject identifier, the FHIR block of the Frank!Console and the reference demo configurations (`demo-configurations/`) to the market-neutral ViscoLink core. Design: [`docs/design/2026-10-07-vertical-packs-design.md`](../../docs/design/2026-10-07-vertical-packs-design.md).

It reaches an image as an overlay zip (`viscolink-pack-health:zip:overlay`): `viscorunner` unpacks it and the Dockerfile copies it to `/opt/frank/webapp-overlay/viscolink/` (`WEB-INF/lib` with the pack jar and the libraries the core WAR lacks, `WEB-INF/classes/console/index.html`, `WEB-INF/pack.properties`).

- **Properties are add-only.** `DeploymentSpecifics.properties` here only adds keys the core's file does not define; on a clash Tomcat's resource-set order decides, and that is not a contract.
- **The Ladybug column is the pack's.** `ladybug/DatabaseChangelog_Pack.xml` adds `patientid` (the core owns `subjectid`), under a precondition so existing databases are left alone.
- **Never a `compile` dependency the core WAR ships.** Mark it `provided`; `HealthClasspathIT` in `viscorunner` fails on a jar that is in both the WAR and the overlay.
- **The core is the only dependency, through its SPI.** Use `com.viscosiety.pack.{PackDescriptor,SubjectIdentifier,ConsoleView}` and nothing else of the core; pack classes live in `com.viscosiety.pack.health`, never in `com.viscosiety.pack`.
