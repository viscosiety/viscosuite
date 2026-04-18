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
COPY --chown=tomcat conf/context.xml /usr/local/tomcat/conf/context.xml

# JDBC drivers — shared between both WARs via common.loader in catalina.properties
COPY --chown=tomcat target/drivers/ /opt/frank/drivers/

# Deploy both WARs — Tomcat derives the context path from the filename:
#   viscolink.war  →  /viscolink
#   viscostore.war →  /viscostore
COPY --chown=tomcat target/viscolink.war  /usr/local/tomcat/webapps/viscolink.war
COPY --chown=tomcat target/viscostore.war /usr/local/tomcat/webapps/viscostore.war

COPY --chown=tomcat src/scripts/entrypoint.sh /scripts/entrypoint.sh

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=3 \
	CMD curl --fail --silent http://localhost:8080/viscolink/iaf/api/server/health || exit 1

ENTRYPOINT ["/scripts/entrypoint.sh"]
CMD ["catalina.sh", "run"]
