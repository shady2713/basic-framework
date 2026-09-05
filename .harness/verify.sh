#!/bin/sh
# Single executable verification topology for local development and CI.
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
trivy_image='ghcr.io/aquasecurity/trivy:0.74.0@sha256:62b1e65e8869bc4b4c6aa4fa2b21595256c7c2f6018a9d9ad61caf87187c1969'
cyclonedx_goal='org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom'

usage() {
  cat <<'EOF'
Usage: sh .harness/verify.sh <gate>

Gates:
  contracts    field/data contracts, engineering ratchets, exception expiry
  lockfile     frozen frontend dependency graph
  backend      backend compile, unit tests, formatting, coverage, architecture
  frontend     dependency/type/spelling checks, lint, and coverage tests
  dependencies resolved backend SBOM and frontend lockfile vulnerability scan
  integration  Docker-backed MySQL/Redis/Flyway and packaged-jar boot smoke
  verify       contracts + backend + frontend
  all          lockfile + verify + dependencies + integration
EOF
}

run_maven() {
  if [ -f ./mvnw ]; then
    ./mvnw "$@"
  elif [ -f ./mvnw.cmd ]; then
    ./mvnw.cmd "$@"
  else
    echo "harness: Maven wrapper is missing" >&2
    exit 1
  fi
}

gate_contracts() {
  cd "$repo_root"
  node --test scripts/check-coverage-ratchet.test.mjs
  node --test scripts/check-harness-topology.test.mjs
  node --test scripts/check-container-images.test.mjs
  node --test scripts/check-database-driver-boundary.test.mjs
  node --test scripts/check-removed-codegen-boundary.test.mjs
  node --test scripts/check-management-route-naming.test.mjs
  node --test scripts/run-frontend-build.test.mjs
  node --test scripts/check-field-injection.test.mjs
  node --test scripts/check-source-quality.test.mjs
  node --test scripts/check-starter-documentation.test.mjs
  node --test scripts/check-sensitive-tostring.test.mjs
  node --test scripts/check-sensitive-diff-log.test.mjs
  node --test scripts/check-safe-exception-handling.test.mjs
  node --test scripts/check-data-lifecycle.test.mjs
  node --test scripts/check-data-permission.test.mjs
  node scripts/check-field-injection.mjs
  node scripts/check-source-quality.mjs
  node scripts/check-starter-documentation.mjs
  node scripts/check-sensitive-tostring.mjs
  node scripts/check-sensitive-diff-log.mjs
  node scripts/check-safe-exception-handling.mjs
  node scripts/check-field-catalog.mjs
  node scripts/check-data-lifecycle.mjs
  node scripts/check-data-permission.mjs
  node scripts/check-exceptions.mjs
}

gate_lockfile() {
  cd "$repo_root/前端代码/basic-framework-admin"
  pnpm install --frozen-lockfile --ignore-scripts
}

gate_backend() {
  cd "$repo_root/后端代码/basic-framework-boot"
  run_maven -q clean verify
}

gate_frontend() {
  cd "$repo_root/前端代码/basic-framework-admin"
  pnpm check
  pnpm lint
  pnpm test:coverage
  node ../../scripts/run-frontend-build.mjs
  cd "$repo_root"
  node scripts/check-coverage-ratchet.mjs frontend
}

gate_dependencies() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "harness: dependencies requires the Docker CLI and daemon" >&2
    exit 1
  fi
  docker info --format 'Docker server {{.ServerVersion}}'

  cd "$repo_root/后端代码/basic-framework-boot"
  run_maven -q -pl basic-framework-server -am package -DskipTests
  run_maven -q "$cyclonedx_goal" \
    -DskipTests \
    -DoutputFormat=json \
    -DincludeTestScope=true \
    -DskipAttach=true

  backend_target="$repo_root/后端代码/basic-framework-boot/target"
  image_archive="$backend_target/basic-framework-server-image.tar"
  image_report="$backend_target/trivy-image-report.txt"
  docker buildx build \
    --pull=false \
    --output "type=docker,dest=$image_archive" \
    "$repo_root/后端代码/basic-framework-boot/basic-framework-server"

  docker run --rm \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --mount "type=bind,source=$repo_root,target=/workspace,readonly" \
    --mount type=volume,source=basic-framework-trivy-cache,target=/root/.cache/trivy \
    "$trivy_image" \
    --quiet sbom \
    --scanners vuln \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --no-progress \
    /workspace/后端代码/basic-framework-boot/target/bom.json
  docker run --rm \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --mount "type=bind,source=$repo_root,target=/workspace,readonly" \
    --mount type=volume,source=basic-framework-trivy-cache,target=/root/.cache/trivy \
    "$trivy_image" \
    --quiet fs \
    --scanners vuln \
    --include-dev-deps \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --no-progress \
    /workspace/前端代码/basic-framework-admin/pnpm-lock.yaml
  docker run --rm \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --mount "type=bind,source=$repo_root,target=/workspace,readonly" \
    --mount type=volume,source=basic-framework-trivy-cache,target=/root/.cache/trivy \
    "$trivy_image" \
    --quiet config \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    /workspace/后端代码/basic-framework-boot
  if ! docker run --rm \
    --cap-drop ALL \
    --security-opt no-new-privileges:true \
    --mount "type=bind,source=$backend_target,target=/scan-output" \
    --mount type=volume,source=basic-framework-trivy-cache,target=/root/.cache/trivy \
    "$trivy_image" \
    --quiet image \
    --input /scan-output/basic-framework-server-image.tar \
    --scanners vuln \
    --severity HIGH,CRITICAL \
    --exit-code 1 \
    --no-progress \
    --output /scan-output/trivy-image-report.txt; then
    cat "$image_report"
    exit 1
  fi
  echo 'harness: application image scan passed'
}

gate_integration() {
  cd "$repo_root"
  if command -v docker >/dev/null 2>&1; then
    docker info --format 'Docker server {{.ServerVersion}}'
  elif [ -z "${DOCKER_HOST:-}" ]; then
    echo "harness: integration requires a Docker daemon" >&2
    exit 1
  else
    echo "harness: Docker CLI unavailable; Testcontainers will verify ${DOCKER_HOST}"
  fi
  cd "$repo_root/后端代码/basic-framework-boot"
  run_maven -q -Pintegration clean verify
  cd "$repo_root"
  # Some file coverage is produced by Testcontainers integration tests.
  node scripts/check-coverage-ratchet.mjs backend
}

run_gate() {
  gate=$1
  echo "== harness:${gate} =="
  case "$gate" in
    contracts) gate_contracts ;;
    lockfile) gate_lockfile ;;
    backend) gate_backend ;;
    frontend) gate_frontend ;;
    dependencies) gate_dependencies ;;
    integration) gate_integration ;;
    verify)
      run_gate contracts
      run_gate backend
      run_gate frontend
      ;;
    all)
      run_gate lockfile
      run_gate verify
      run_gate dependencies
      run_gate integration
      ;;
    --list|-h|--help) usage ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

if [ "$#" -ne 1 ]; then
  usage >&2
  exit 2
fi

run_gate "$1"
