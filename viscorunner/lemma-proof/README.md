# LemmaPipe local proof

Proves on a real core runner (no platform) that a configuration with a `LemmaPipe` loads, answers,
vetoes, explains, and that a broken rules file aborts the configuration with its location.

Recorded run: 2026-10-14, image built from branch `feat/lemma-pipe` (`latest-core`, architecture aarch64/arm64),
`dtap.stage=LOC`.

## Start (from `viscorunner/`)

```bash
mkdir -p secrets && touch secrets/credentials.properties
mkdir -p configurations && cp -R lemma-proof/configurations/rules configurations/rules   # keep the tracked FrankConfig.xsd
docker compose -f docker-compose.core.yml up --build -d
until curl -fs http://localhost:8180/viscolink/iaf/api/server/health >/dev/null; do sleep 5; done
docker compose -f docker-compose.core.yml logs viscolink | grep -E "Configuration \[rules\]|AdviseDrink|Lemma"
```

Observed: `Configuration [rules] startup in 141 ms`, `Adapter [AdviseDrink] up and running`,
`Receiver [AdviseDrink] has listener on uriPattern: /api/advise; method: POST`, no `ConfigurationException`.
(If an older `viscolink-postgres-data` volume exists, run `down -v` first: a volume made before
`init-ladybug.sql` existed lacks the `ladybug` database and the context never starts.)

## Exercise

```bash
curl -s -w '\n[%{http_code}]\n' -X POST http://localhost:8180/viscolink/api/advise -H 'Content-Type: application/xml' \
  -d '<weather><temperature>27</temperature><group>Clouds</group></weather>'
curl -s -w '\n[%{http_code}]\n' -X POST http://localhost:8180/viscolink/api/advise -H 'Content-Type: application/xml' \
  -d '<weather><temperature>10</temperature><group>Rain</group></weather>'
curl -s http://localhost:8180/viscolink/flow-api/pack
```

Observed (explanations shortened; they are a JSON document per rule, XML-escaped):

* 27 degrees, Clouds: **200**
  `<lemmaResult spec="cocktail_advice" effective="2026-10-14T12:34:51.935953Z"><result rule="drink_profile" type="text">refreshing-citrus<explanation>...</explanation></result><result rule="ingredient" type="text">Tequila<explanation>...</explanation></result></lemmaResult>`
* 10 degrees, Rain: **422** (veto exit): `ingredient` carries `vetoed="true"` and the message
  `No ingredient below 25 degrees in this test spec`; `drink_profile` answers `balanced-classic`.
* `GET /viscolink/flow-api/pack`: 200, `{"id":"core","displayName":"Core",...,"features":["lemma"]}`.
  (`/viscolink/api-service/pack` answers 401 even in LOC: bearer-only.)

## Larva

`larva/AdviseDrink/scenario01/expected.xml` is the real answer above with `effective="IGNORE"` and every
explanation replaced by `IGNORE`. The Larva page only offers `/opt/frank/testtool` and `/opt/frank/testtool-ext`
as scenario roots, so the run copies the larva folder there and posts the page's form:

```bash
docker cp lemma-proof/configurations/rules/larva/. viscorunner-viscolink-1:/opt/frank/testtool-ext/
curl -s -X POST http://localhost:8180/viscolink/iaf/larva/index.jsp \
  --data-urlencode 'scenariosrootdirectory=/opt/frank/testtool-ext/' \
  -d 'waitbeforecleanup=100&timeout=10000&loglevel=wrong pipeline messages&execute=/opt/frank/testtool-ext/&submit=start'
```

Observed: `Scenario 'AdviseDrink/scenario01 - ...' passed in 231ms` and `All scenarios passed (1 scenario executed in 242ms)`.

## A broken rules file aborts the configuration with its location

```bash
printf 'spec cocktail_advice\n\nrule r: # comment\n  1\n' > configurations/rules/AdviseDrink/rules/cocktail_advice.lemma
docker compose -f docker-compose.core.yml restart viscolink
docker compose -f docker-compose.core.yml logs viscolink | grep "cocktail_advice.lemma:3:"
```

Observed: `ConfigurationException: Exception configuring LemmaPipe [Decide]: Lemma rules in
[AdviseDrink/rules/cocktail_advice.lemma] for spec [cocktail_advice]: AdviseDrink/rules/cocktail_advice.lemma:3:9
Unexpected character '#'`; health answers 503 `configuration[rules] is in state[ERROR]`, adapter STOPPED,
`POST /api/advise` 404.

## Clean up

```bash
docker compose -f docker-compose.core.yml down -v
rm -rf configurations/rules secrets/credentials.properties
```
