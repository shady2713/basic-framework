#!/bin/sh
# Single executable verification topology for local development and CI.
set -eu

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)

usage() {
  cat <<'EOF'
Usage: sh .harness/verify.sh <gate>

Gates:
  contracts    field catalog, data lifecycle, and exception expiry
  lockfile     frozen frontend dependency graph
  backend      backend compile, unit tests, formatting, coverage, architecture
  frontend     dependency/type/spelling checks, lint, and coverage tests
  integration  Docker-backed MySQL/Redis/Flyway and packaged-jar boot smoke
  verify       contracts + backend + frontend
  all          lockfile + verify + integration
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
  node scripts/check-field-catalog.mjs
  node scripts/check-data-lifecycle.mjs
  node scripts/check-exceptions.mjs
}

gate_lockfile() {
  cd "$repo_root/前端代码/basic-framework-admin"
  pnpm install --frozen-lockfile --ignore-scripts
}

gate_backend() {
  cd "$repo_root/后端代码/basic-framework-boot"
  run_maven -q verify
}

gate_frontend() {
  cd "$repo_root/前端代码/basic-framework-admin"
  pnpm check
  pnpm lint
  pnpm test:coverage
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
  run_maven -q -Pintegration verify
}

run_gate() {
  gate=$1
  echo "== harness:${gate} =="
  case "$gate" in
    contracts) gate_contracts ;;
    lockfile) gate_lockfile ;;
    backend) gate_backend ;;
    frontend) gate_frontend ;;
    integration) gate_integration ;;
    verify)
      run_gate contracts
      run_gate backend
      run_gate frontend
      ;;
    all)
      run_gate lockfile
      run_gate verify
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
