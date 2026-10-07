# Same base image as Frank!Framework's own production Docker image
FROM tomcat:11.0.14-jre21

ARG GID=2000
ARG UID=2000

# Harden file permissions (CIS-DI-0008)
RUN chmod -R 751 /usr/bin /usr/sbin

# Create runtime directories and a non-root tomcat user
RUN set -eux && \
	mkdir -p /opt/frank/secrets && \
	mkdir -p /opt/frank/configurations && \
	mkdir -p /opt/frank/demo-configurations && \
	mkdir -p /opt/frank/drivers && \
	mkdir -p /opt/frank/lib && \
	mkdir -p /opt/frank/plugins && \
	mkdir -p /opt/frank/resources && \
	mkdir -p /opt/frank/testtool && \
	mkdir -p /opt/frank/testtool-ext && \
	mkdir -p /opt/frank/webapp-overlay/viscolink && \
	groupadd -g ${GID} tomcat && \
	useradd -u ${UID} -g tomcat -s /usr/sbin/nologin -l tomcat -d ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat ${CATALINA_HOME} && \
	chown -hR tomcat:tomcat /opt/frank

USER tomcat

# The one runner image. Two build arguments choose what goes into it:
#   PACK  = health | core        the vertical pack: its libraries, console patches, demo
#                                configurations and identity (the core is no pack: empty overlay)
#   STORE = viscostore | none    whether the ViscoStore WAR (and its JNDI datasource) is part of it
# These are plain ARG lines: a trailing comment would be read as more ARG names.
ARG PACK=health
ARG STORE=viscostore

# Append Frank!Framework Tomcat settings
COPY --chown=tomcat src/scripts/catalinaAdditional.properties /tmp/catalinaAdditional.properties
RUN cat /tmp/catalinaAdditional.properties >> /usr/local/tomcat/conf/catalina.properties && \
	rm /tmp/catalinaAdditional.properties

# JNDI datasource definitions (H2 for local dev; override in production via mounted context.xml).
# context-viscostore.xml carries jdbc/viscostore as well, context-none.xml does not.
COPY --chown=tomcat conf/context-${STORE}.xml /usr/local/tomcat/conf/context.xml

# Tomcat server.xml with the ContextFailureEventPublisher <Server> listener (emits a Kubernetes
# Warning Event when a WAR context fails to start). Pinned copy of the tomcat:11.0.14 default —
# re-sync on a base-image Tomcat bump.
COPY --chown=tomcat conf/server.xml /usr/local/tomcat/conf/server.xml

# ContextFailureEventPublisher: the shaded viscorunner jar (fabric8 Kubernetes client + transitives,
# with Jackson/SnakeYAML relocated so they don't leak onto the shared classloader) on common.loader
# (/opt/frank/lib/*.jar in catalinaAdditional.properties).
COPY --chown=tomcat target/viscorunner-*.jar /opt/frank/lib/

# Per-context config for viscolink: a PreResources overlay, so the pack's WEB-INF/lib and
# WEB-INF/classes (and any demo file injected at run time) are served from
# /opt/frank/webapp-overlay/viscolink/ without pre-creating webapps/viscolink/ (which would cause
# Tomcat to skip WAR extraction and deploy an empty context in ~6 ms).
COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/viscolink.xml

# JDBC drivers — shared between the WARs via common.loader in catalina.properties
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/

# The pack (the core's overlay is an empty WEB-INF/lib plus its identity), its demo configurations
# (never loaded unless mounted over /opt/frank/configurations) and its identity.
COPY --chown=tomcat target/packs/${PACK}/ /opt/frank/webapp-overlay/viscolink/
COPY --chown=tomcat target/demo/${PACK}/  /opt/frank/demo-configurations/

# Landing page, stamped with the pack's words and the build. The values come from the pack's
# WEB-INF/pack.properties; they are free of '|', '&' and backslash, so '|' is a safe sed delimiter.
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
ARG BUILD_TIMESTAMP=""
RUN set -e; P=/opt/frank/webapp-overlay/viscolink/WEB-INF/pack.properties; \
    val() { grep "^$1=" "$P" | cut -d= -f2- ; }; \
    BUILD_TS="${BUILD_TIMESTAMP:-$(date -u '+%Y-%m-%d %H:%M UTC')}"; \
    sed -i "s|%%PACK_TAGLINE%%|$(val pack.tagline)|; s|%%PACK_LINK_BLURB%%|$(val pack.linkBlurb)|; \
            s|%%PACK_STAMP%%| \&middot; pack $(val pack.id) $(val pack.version)|; \
            s|%%BUILD_TIMESTAMP%%| \&middot; Built ${BUILD_TS}|" /usr/local/tomcat/webapps/ROOT/index.html

# Deploy the WARs — Tomcat derives the context path from the filename:
#   viscolink.war  →  /viscolink   (always)
#   viscostore.war →  /viscostore  (STORE=viscostore only; target/store/none/ is empty, so the COPY copies nothing)
COPY --chown=tomcat target/viscolink.war /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/store/${STORE}/ /usr/local/tomcat/webapps/

# Fail fast if a WAR is a stale empty stub from .m2/ (< 1 MB); the store only when it is part of the image.
# copy-dependencies resolves from the local Maven repo, not the reactor; if
# `mvn install -pl viscolink,packs/health,viscostore` was not run first the 1-2 KB placeholder
# artifact ends up here and Tomcat deploys it silently in ~17 ms with no routes.
RUN set -e; \
    vl=$(stat -c%s /usr/local/tomcat/webapps/viscolink.war); \
    [ "$vl" -gt 1000000 ] || { echo ""; echo "ERROR: viscolink.war is ${vl} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,packs/health,viscostore -DskipTests && ./mvnw package -pl viscorunner -DskipTests"; echo ""; exit 1; }; \
    if [ "${STORE}" != "none" ]; then \
        vs=$(stat -c%s /usr/local/tomcat/webapps/viscostore.war); \
        [ "$vs" -gt 1000000 ] || { echo ""; echo "ERROR: viscostore.war is ${vs} bytes — stale stub (same command)."; exit 1; }; \
    fi

COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/viscolink/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]
