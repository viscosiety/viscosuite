#!/bin/sh
# Uploads the staged artifacts (stage-publish.sh) to this project's Maven registry with
# deploy:deploy-file, then writes the components lists the docker jobs fetch from the registry:
#   viscorunner/target/components-registry-suite.list      store + pack-health
#   viscorunner/target/components-registry-viscolink.list  pack-health
# Needs CI_API_V4_URL, CI_PROJECT_ID, CI_JOB_TOKEN (the settings.xml server reads it) and MAVEN_CLI_OPTS.
set -eu
pub=viscorunner/target/publish
VERSION=$(cat "$pub/VERSION")
REPO_URL="${CI_API_V4_URL:?}/projects/${CI_PROJECT_ID:?}/packages/maven"
deploy() { # <main file> <packaging> <pom file> [extra mvn args]: one deploy-file call
  f="$1"; packaging="$2"; pom="$3"; shift 3
  ./mvnw ${MAVEN_CLI_OPTS:-} -q org.apache.maven.plugins:maven-deploy-plugin:3.1.3:deploy-file \
    -DrepositoryId=gitlab-maven -Durl="$REPO_URL" \
    -Dfile="$f" -Dpackaging="$packaging" -DpomFile="$pub/$pom" -Dversion="$VERSION" "$@"
}
# the core: the WAR, with its classes jar attached (packs compile against it)
deploy "$pub/viscolink.war" war viscolink.pom \
  -Dfiles="$pub/viscolink-classes.jar" -Dclassifiers=classes -Dtypes=jar
# the health pack: its jar is the main artifact (Maven consumers resolve the pom through it), the
# overlay zip is attached as classifier "overlay" — the file the fetcher downloads
deploy "packs/health/target/viscolink-pack-health-${VERSION}.jar" jar viscolink-pack-health.pom \
  -Dfiles="$pub/viscolink-pack-health-overlay.zip" -Dclassifiers=overlay -Dtypes=zip
# the store
deploy "$pub/viscostore.war" war viscostore.pom

sha() { # <staged file name> -> its sha256 from SHA256SUMS; exits 1 when there is none
  s=$(grep -F " $1" "$pub/SHA256SUMS" | awk -v f="$1" '$2 == f { print $1 }')
  [ -n "$s" ] || { echo "publish: no sha256 for $1 in $pub/SHA256SUMS" >&2; exit 1; }
  echo "$s"
}
url() { echo "$REPO_URL/com/visco/$1/$VERSION/$2"; }
# computed before the list blocks: a failure here stops the script (an echo argument would not)
sha_store=$(sha viscostore.war) || exit 1
sha_pack=$(sha viscolink-pack-health-overlay.zip) || exit 1
{
  echo "release $VERSION"
  echo "store war $VERSION $(url viscostore viscostore-$VERSION.war) $sha_store fhir-store"
  echo "pack-health overlay $VERSION $(url viscolink-pack-health viscolink-pack-health-$VERSION-overlay.zip) $sha_pack overlay/runtime"
} > viscorunner/target/components-registry-suite.list
{
  echo "release $VERSION"
  echo "pack-health overlay $VERSION $(url viscolink-pack-health viscolink-pack-health-$VERSION-overlay.zip) $sha_pack overlay/runtime"
} > viscorunner/target/components-registry-viscolink.list
cat viscorunner/target/components-registry-suite.list
