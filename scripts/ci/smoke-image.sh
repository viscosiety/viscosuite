#!/bin/sh
# smoke-image.sh <image> [component id ...]: boot a just-built runner image and check it serves.
#   - the Frank!Framework answers GET /runtime/iaf/api/server/health 200 within $SMOKE_TIMEOUT s (default 240; H2 in memory)
#   - /opt/frank/components/MANIFEST lists exactly the given component ids (none = viscobase)
#   - an HTTPS tunnel port (8443, own throwaway certificate) answers: /api/... 404 or 401 from the tunnel servlet, anything else 404
#   - with "store": a postgres:16 container is started first and GET /fhir-store/fhir/metadata answers 200
# Runs inside the docker jobs on the per-arch image just pushed. Everything it starts is removed.
set -eu
IMAGE="${1:?usage: smoke-image.sh <image> [component id ...]}"; shift
expected="$*"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-240}"
net="smoke-$$"
tunnel_vol="smoke-tunnel-$$"
cleanup() {
  docker rm -f smoke-runner smoke-postgres >/dev/null 2>&1 || true
  docker network rm "$net" >/dev/null 2>&1 || true
  docker volume rm "$tunnel_vol" >/dev/null 2>&1 || true
}
trap cleanup EXIT
docker network create "$net" >/dev/null

# The tunnel port's settings: a throwaway PKCS12 certificate made with the keytool of the image under test, in a named
# volume (a bind mount of a host path is not visible under docker-in-docker). The runner mounts it read-only.
docker volume create "$tunnel_vol" >/dev/null
docker run --rm --user root -v "$tunnel_vol:/t" --entrypoint sh "$IMAGE" -c '
  set -eu
  pw=$(head -c 12 /dev/urandom | od -An -tx1 | tr -d " \n")
  printf %s "$pw" > /t/tunnel.pw
  keytool -genkeypair -alias smoke -keyalg RSA -keysize 2048 -validity 2 -dname CN=localhost \
    -storetype PKCS12 -keystore /t/tunnel.p12 -storepass "$pw" -keypass "$pw" >/dev/null 2>&1
  printf "ports=8443\n8443.keystoreFile=/opt/frank/tunnel-api/tunnel.p12\n8443.keystorePasswordFile=/opt/frank/tunnel-api/tunnel.pw\n" > /t/connectors.properties
  chmod -R a+rX /t'

env_args="-e dtap.stage=LOC -e configurations.directory.autoLoad=true -e classloader.type=DirectoryClassLoader -e ladybug.jdbc.datasource=jdbc/ladybug \
 -e VISCOLINK_JDBC_DRIVER=org.h2.Driver -e VISCOLINK_JDBC_URL=jdbc:h2:mem:viscolink;DB_CLOSE_DELAY=-1 -e VISCOLINK_JDBC_USERNAME=sa -e VISCOLINK_JDBC_PASSWORD= \
 -e LADYBUG_JDBC_DRIVER=org.h2.Driver -e LADYBUG_JDBC_URL=jdbc:h2:mem:ladybug;DB_CLOSE_DELAY=-1 -e LADYBUG_JDBC_USERNAME=sa -e LADYBUG_JDBC_PASSWORD= \
 -e JAVA_OPTS=-Dspring.profiles.active=ladybug-database"
case " $expected " in *" store "*)
  docker run -d --name smoke-postgres --network "$net" -e POSTGRES_USER=visco -e POSTGRES_PASSWORD=visco -e POSTGRES_DB=viscostore postgres:16 >/dev/null
  env_args="$env_args -e VISCOSTORE_JDBC_DRIVER=org.postgresql.Driver -e VISCOSTORE_JDBC_URL=jdbc:postgresql://smoke-postgres:5432/viscostore \
    -e VISCOSTORE_JDBC_USERNAME=visco -e VISCOSTORE_JDBC_PASSWORD=visco -e VISCOSTORE_JDBC_DIALECT=ca.uhn.fhir.jpa.model.dialect.HapiFhirPostgresDialect \
    -e VISCOSTORE_USERNAME=viscolink -e VISCOSTORE_PASSWORD=viscolink"
  ;;
esac
# shellcheck disable=SC2086
docker run -d --name smoke-runner --network "$net" -v "$tunnel_vol:/opt/frank/tunnel-api:ro" $env_args "$IMAGE" >/dev/null

wait_200() { # <path> <seconds>
  i=0
  while [ "$i" -lt "$2" ]; do
    if docker exec smoke-runner curl --fail --silent --output /dev/null "http://localhost:8080$1"; then return 0; fi
    if [ "$(docker inspect -f '{{.State.Running}}' smoke-runner 2>/dev/null)" = false ]; then
      echo "smoke: the container stopped while waiting for $1; last log lines:" >&2
      docker logs --tail 80 smoke-runner >&2
      return 1
    fi
    i=$((i + 5)); sleep 5
  done
  echo "smoke: $1 did not answer 200 within $2 s; last log lines:" >&2
  docker logs --tail 80 smoke-runner >&2
  return 1
}
wait_200 /runtime/iaf/api/server/health "$SMOKE_TIMEOUT"

actual=$(docker exec smoke-runner sh -c 'test -f /opt/frank/components/MANIFEST && sed -n "s/^component=\([^ ]*\) .*/\1/p" /opt/frank/components/MANIFEST | tr "\n" " "' || true)
actual=$(echo "$actual" | sed 's/ *$//')
[ "$actual" = "$expected" ] || { echo "smoke: MANIFEST lists '$actual', expected '$expected'" >&2; exit 1; }

# The tunnel port answers over TLS: the tunnel servlet says 404 or 401 to an unknown /api path, and nothing but /api answers.
tunnel_status() { # <path>
  docker exec smoke-runner curl --silent --insecure --output /dev/null --write-out '%{http_code}' "https://localhost:8443$1" || true
}
api=$(tunnel_status /api/smoke-nothing)
case "$api" in 404|401) ;; *)
  echo "smoke: https://localhost:8443/api/smoke-nothing answered '$api', expected 404 or 401; last log lines:" >&2
  docker logs --tail 80 smoke-runner >&2; exit 1 ;;
esac
gui=$(tunnel_status /runtime/iaf/gui/)
[ "$gui" = 404 ] || { echo "smoke: https://localhost:8443/runtime/iaf/gui/ answered '$gui', expected 404; last log lines:" >&2; docker logs --tail 80 smoke-runner >&2; exit 1; }

case " $expected " in *" store "*) wait_200 /fhir-store/fhir/metadata "$SMOKE_TIMEOUT" ;; esac
echo "smoke: $IMAGE OK (components: ${expected:-none})"
