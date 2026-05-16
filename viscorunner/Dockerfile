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
	mkdir -p /opt/frank/drivers && \
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

# Append Frank!Framework Tomcat settings
COPY --chown=tomcat src/scripts/catalinaAdditional.properties /tmp/catalinaAdditional.properties
RUN cat /tmp/catalinaAdditional.properties >> /usr/local/tomcat/conf/catalina.properties && \
	rm /tmp/catalinaAdditional.properties

# JNDI datasource definitions (H2 for local dev; override in production via mounted context.xml)
COPY --chown=tomcat conf/context-viscosuite.xml /usr/local/tomcat/conf/context.xml

# Per-context config for viscolink: PostResources overlay so demo files can be injected
# into /opt/frank/webapp-overlay/viscolink/ without pre-creating webapps/viscolink/ (which
# would cause Tomcat to skip WAR extraction and deploy an empty context in ~6 ms).
COPY --chown=tomcat conf/Catalina/localhost/viscolink.xml /usr/local/tomcat/conf/Catalina/localhost/viscolink.xml

# JDBC drivers — shared between both WARs via common.loader in catalina.properties
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/

# Deploy both WARs — Tomcat derives the context path from the filename:
#   viscolink.war  →  /viscolink
#   viscostore.war →  /viscostore
COPY --chown=tomcat src/webapp/ROOT/ /usr/local/tomcat/webapps/ROOT/
COPY --chown=tomcat target/viscolink.war  /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/viscostore.war /usr/local/tomcat/webapps/viscostore.war

# Fail fast if either WAR is a stale empty stub from .m2/ (< 1 MB).
# copy-dependencies resolves from the local Maven repo, not the reactor; if
# `mvn install -pl viscolink,viscostore` was not run first the 1-2 KB placeholder
# artifact ends up here and Tomcat deploys it silently in ~17 ms with no routes.
RUN set -e; \
    vl=$(stat -c%s /usr/local/tomcat/webapps/viscolink.war);  \
    vs=$(stat -c%s /usr/local/tomcat/webapps/viscostore.war); \
    [ "$vl" -gt 1000000 ] || { echo ""; echo "ERROR: viscolink.war is ${vl} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner"; echo ""; exit 1; }; \
    [ "$vs" -gt 1000000 ] || { echo ""; echo "ERROR: viscostore.war is ${vs} bytes — stale stub. Run:"; \
        echo "  ./mvnw install -pl viscolink,viscostore && ./mvnw package -pl viscorunner"; echo ""; exit 1; }

COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/viscolink/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]
