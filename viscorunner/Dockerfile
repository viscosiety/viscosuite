# ONE Dockerfile, two stages (viscoFoundry design 2026-11-04, the component model):
#   viscobase  Tomcat 11 / JRE 21 + the Frank!Framework WAR at /runtime + the component fetcher.
#              Nothing variant-specific: no pack, no store. The commercial runner IS this image
#              (components fetched at pod start) and viscolink:<tag>-core is this image under another name.
#   fat        FROM viscobase; runs the fetcher at BUILD time against a components list (file:// URLs
#              into target/components/ from the Maven staging for local builds; registry URLs in CI)
#              and places the result: the published viscorunner (health + store) and viscolink (health).
FROM tomcat:11.0.14-jre21 AS viscobase

ARG GID=2000
ARG UID=2000

# Harden file permissions (CIS-DI-0008)
RUN chmod -R 751 /usr/bin /usr/sbin

# unzip for the fetcher's overlay zips; curl and ca-certificates come with the base image.
RUN apt-get update && apt-get install -y --no-install-recommends unzip && rm -rf /var/lib/apt/lists/*

# Create runtime directories and a non-root tomcat user
RUN set -eux && \
	mkdir -p /opt/frank/secrets && \
	mkdir -p /opt/frank/configurations && \
	mkdir -p /opt/frank/demo-configurations && \
	mkdir -p /opt/frank/drivers && \
	mkdir -p /opt/frank/lib && \
	mkdir -p /opt/frank/bin && \
	mkdir -p /opt/frank/components && \
	mkdir -p /opt/frank/plugins && \
	mkdir -p /opt/frank/resources && \
	mkdir -p /opt/frank/testtool && \
	mkdir -p /opt/frank/testtool-ext && \
	mkdir -p /opt/frank/webapp-overlay/viscolink && \
	mkdir -p /opt/frank/webapp-overlay/console && \
	groupadd -g ${GID} tomcat && \
	useradd -u ${UID} -g tomcat -s /usr/sbin/nologin -l tomcat -d ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat /opt/frank

USER tomcat

# Append Frank!Framework Tomcat settings
COPY --chown=tomcat src/scripts/catalinaAdditional.properties /tmp/catalinaAdditional.properties
RUN cat /tmp/catalinaAdditional.properties >> /usr/local/tomcat/conf/catalina.properties && \
	rm /tmp/catalinaAdditional.properties

# The one global context: jdbc/viscolink, jdbc/ladybug AND jdbc/viscostore, each from env and created
# lazily at its first JNDI lookup (see the comment in the file). Override in production via a mounted context.xml.
COPY --chown=tomcat conf/context.xml /usr/local/tomcat/conf/context.xml

# Tomcat server.xml with the ContextFailureEventPublisher <Server> listener (emits a Kubernetes
# Warning Event when a WAR context fails to start). Pinned copy of the tomcat:11.0.14 default —
# re-sync on a base-image Tomcat bump.
COPY --chown=tomcat conf/server.xml /usr/local/tomcat/conf/server.xml

# Neutral web paths (2026-10-28): the webapps are served at /runtime (Frank!Framework), /fhir-store
# (the store) and, on a ViscoForge composition, /console. The old /viscolink, /viscostore and /viscoforge
# keep working through the Host's RewriteValve (server.xml) with these rules: a browser page load (GET,
# Accept text/html) is sent to the new address with a 302, because the session cookie is scoped to the new
# path; every other request (API calls, POSTs, scripts, probes) is served in place. A rule to a context
# that is not deployed answers 404, as before. The rules file must not contain comment lines: with them
# Tomcat 11 silently skipped the conditional rules (found in the 2026-10-28 spike).
COPY --chown=tomcat conf/Catalina/localhost/rewrite.config /usr/local/tomcat/conf/Catalina/localhost/rewrite.config

# Log retention (2026-10-23): the third file of log4j.configurationFile (catalinaAdditional.properties).
COPY --chown=tomcat src/scripts/log4j4retention.xml /opt/frank/log4j/log4j4retention.xml
# Tomcat's own daily files (catalina.*, localhost.*, ...): 30 days instead of the base image's 90 (2026-10-23).
RUN set -e; f=/usr/local/tomcat/conf/logging.properties; sed -i 's/\.maxDays = 90$/.maxDays = 30/' "$f"; if grep -q 'maxDays = 90' "$f" || ! grep -q '^1catalina\.org\.apache\.juli\.AsyncFileHandler\.maxDays = 30$' "$f"; then echo "ERROR: $f does not have the expected maxDays lines; re-check it after a base-image bump"; exit 1; fi

# ContextFailureEventPublisher: the shaded viscorunner jar (fabric8 Kubernetes client + transitives,
# ALL relocated under com.viscosiety.shaded, so nothing leaks onto the shared classloader) on common.loader
# (/opt/frank/lib/*.jar in catalinaAdditional.properties).
COPY --chown=tomcat target/viscorunner-*.jar /opt/frank/lib/

# Per-context config for the Frank!Framework webapp (/runtime): a PreResources overlay, so a pack's
# WEB-INF/lib and WEB-INF/classes (and any demo file injected at run time) are served from
# /opt/frank/webapp-overlay/viscolink/ without pre-creating webapps/runtime/ (which would cause
# Tomcat to skip WAR extraction and deploy an empty context in ~6 ms). The overlay folder keeps its
# name until M4 of the component model (viscoForge and the portal write into it by that name).
COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/runtime.xml

# JDBC drivers — shared between the WARs via common.loader in catalina.properties
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/
# Lemma (com.viscosiety.pipes.LemmaPipe): the JNI library for THIS image's architecture, prebuilt so
# nothing is extracted at run time. The images are built on native runners per architecture
# (see .gitlab-ci.yml), so uname -m is the target; any other architecture fails the build.
COPY --chown=tomcat target/native/ /tmp/lemma-native/
RUN set -e; case "$(uname -m)" in x86_64) triple=x86_64-unknown-linux-gnu ;; aarch64) triple=aarch64-unknown-linux-gnu ;; *) echo "ERROR: no Lemma native for $(uname -m)"; exit 1 ;; esac; mkdir -p /opt/frank/native; cp "/tmp/lemma-native/${triple}/liblemma_jni.so" /opt/frank/native/liblemma_jni.so; rm -rf /tmp/lemma-native
ENV LEMMA_JNI_LIBRARY=/opt/frank/native/liblemma_jni.so

# Both packs' demo configurations (never loaded unless mounted over /opt/frank/configurations).
COPY --chown=tomcat target/demo/health/ /opt/frank/demo-configurations/health/
COPY --chown=tomcat target/demo/core/ /opt/frank/demo-configurations/core/

# Landing page. The pack's words and the release come from components.json, which the fetcher writes
# next to the page (place-components); only the build stamp is baked here.
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
ARG BUILD_TIMESTAMP=""
RUN set -e; BUILD_TS="${BUILD_TIMESTAMP:-$(date -u '+%Y-%m-%d %H:%M UTC')}"; \
    sed -i "s|%%BUILD_TIMESTAMP%%| \&middot; Built ${BUILD_TS}|" /usr/local/tomcat/webapps/ROOT/index.html

# The Frank!Framework WAR — Tomcat derives the context path from the filename: runtime.war → /runtime.
COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/runtime.war

# Fail fast if the WAR is a stale empty stub from .m2/ (< 1 MB). copy-dependencies resolves from the
# local Maven repo, not the reactor; if `mvn install -pl viscolink,packs/health,viscostore` was not run
# first the 1-2 KB placeholder artifact ends up here and Tomcat deploys it silently with no routes.
# (Fetched components need no such check: the fetcher verifies their sha256.)
RUN set -e; \
    vl=$(stat -c%s /usr/local/tomcat/webapps/runtime.war); \
    [ "$vl" -gt 1000000 ] || { echo ""; echo "ERROR: runtime.war (viscolink.war) is ${vl} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw package -pl viscorunner -DskipTests"; echo ""; exit 1; }

# The component fetcher and placer (see the scripts' headers) and the entrypoint, which places at every start.
COPY --chown=tomcat src/scripts/fetch-components src/scripts/place-components /opt/frank/bin/
COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/runtime/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]

# ---------------------------------------------------------------------------------------------------
# fat: viscobase plus the components a list names, fetched and placed at build time.
#   docker build --target fat --build-arg COMPONENTS_LIST=target/components-suite.list .
# A list with file:// URLs reads target/components/ (copied below); a list with registry URLs is fetched
# with the job token, handed in as a BuildKit secret (docker build --secret id=job_token,env=CI_JOB_TOKEN):
# never a build argument, never a layer.
# ---------------------------------------------------------------------------------------------------
FROM viscobase AS fat
ARG COMPONENTS_LIST=target/components-suite.list
COPY --chown=tomcat target/components/ /opt/frank/component-files/
COPY --chown=tomcat ${COMPONENTS_LIST} /opt/frank/components.list
RUN --mount=type=secret,id=job_token,uid=2000 set -e; \
    if [ -f /run/secrets/job_token ]; then CI_JOB_TOKEN=$(cat /run/secrets/job_token); export CI_JOB_TOKEN; fi; \
    /opt/frank/bin/fetch-components /opt/frank/components.list && /opt/frank/bin/place-components
