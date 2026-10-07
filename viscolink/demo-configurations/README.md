# Core demo configurations

`echo/` is the core's one neutral demo: an adapter that echoes the request body and records the
`subjectId` query parameter in the session, so Ladybug shows it in the Subject column and ViscoFlow
can filter on it. Every vertical pack ships its own demo set next to its code (for health:
`packs/health/demo-configurations/`).

Mount this directory over `/opt/frank/configurations` (or copy `echo/` into your own configuration
directory), then try it:

```bash
curl -X POST 'http://localhost:8180/viscolink/api/echo?subjectId=SUBJ-001' -d 'hello'
```
