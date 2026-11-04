#!/bin/sh
# smoke-image.sh <image> [component id ...]: boot a just-built runner image and check it serves.
#   - the Frank!Framework answers GET /runtime/iaf/api/server/health 200 within $SMOKE_TIMEOUT s (default 240; H2 in memory)
#   - /opt/frank/components/MANIFEST lists exactly the given component ids (none = viscobase)
#   - with "store": a postgres:16 container is started first and GET /fhir-store/fhir/metadata answers 200
# Runs inside the docker jobs on the per-arch image just pushed. Everything it starts is removed.
set -eu
IMAGE="${1:?usage: smoke-image.sh <image> [component id ...]}"; shift
expected="$*"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-240}"
net="smoke-$$"
cleanup() { docker rm -f smoke-runner smoke-postgres >/dev/null 2>&1 || true; docker network rm "$net" >/dev/null 2>&1 || true; }
trap cleanup EXIT
docker network create "$net" >/dev/null

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
docker run -d --name smoke-runner --network "$net" $env_args "$IMAGE" >/dev/null

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

case " $expected " in *" store "*) wait_200 /fhir-store/fhir/metadata "$SMOKE_TIMEOUT" ;; esac
echo "smoke: $IMAGE OK (components: ${expected:-none})"
