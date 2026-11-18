#!/usr/bin/env bash
set -e

# JVM flags required by Frank!Framework (Narayana transaction manager, Hazelcast).
# -XX:-UseBASE64Intrinsics switches off the JIT's hand-written Base64 code: CVE-2026-114938
# ("Java4Shell") is a memory corruption in it on x86-64 CPUs with AVX-512 VBMI, fixed in Java
# 25.0.5.1. The flag is accepted on every CPU and costs some Base64 speed. Remove it once the base
# image runs a fixed Java.
export JAVA_OPTS="\
	-XX:-UseBASE64Intrinsics \
	-XX:HeapDumpPath=/usr/local/tomcat/logs \
	-XX:+HeapDumpOnOutOfMemoryError \
	--add-modules java.se \
	--add-exports java.base/jdk.internal.ref=ALL-UNNAMED \
	--add-opens java.base/java.lang=ALL-UNNAMED \
	--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
	--add-opens java.management/sun.management=ALL-UNNAMED \
	--add-opens jdk.management/com.sun.management.internal=ALL-UNNAMED \
	$JAVA_OPTS"

# Components fetched into /opt/frank/components (at image build for the fat images, by the init
# container on the platform) are copied into Tomcat's dirs at every start. The store-only image has
# no placer: nothing to do there.
if [ -x /opt/frank/bin/place-components ]; then
	/opt/frank/bin/place-components
fi

exec "$@"
