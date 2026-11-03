#!/usr/bin/env bash
set -Eeuo pipefail

# Invoked over the existing SSH connection. No database port is opened to Actions.
name=$1 image=$2 revision=$3 env_file=$4 firebase_file=$5
bind=$6 host_port=$7 app_port=$8 management_host_port=$9
management_port=${10} memory=${11} memory_swap=${12} pids=${13} output_dir=${14}
timeout_seconds=${DEPLOY_HEALTH_TIMEOUT_SECONDS:-180}
poll_seconds=${DEPLOY_HEALTH_POLL_SECONDS:-3}
stable_required=${DEPLOY_HEALTH_STABLE_COUNT:-3}
for tool in docker curl python3; do command -v "$tool" >/dev/null; done
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || exit 1
[[ "$timeout_seconds" =~ ^[1-9][0-9]*$ && "$poll_seconds" =~ ^[1-9][0-9]*$ && "$stable_required" =~ ^[1-9][0-9]*$ ]] || exit 1
test -f "$env_file"
mkdir -p "$output_dir"
check_name="$name-migration-check-${revision:0:12}"
switched=0
had_previous=0

wait_healthy() {
  local expected_image=$1 endpoint=$2 expected_restarts=$3
  local deadline=$((SECONDS + timeout_seconds)) stable=0 body state
  while (( SECONDS < deadline )); do
    state=$(docker inspect --format '{{.State.Running}} {{.RestartCount}} {{.Image}}' "$name") || return 1
    if [[ "$state" != "true $expected_restarts $expected_image" ]]; then
      echo 'Container exited, restarted, or changed image before becoming healthy' >&2
      return 1
    fi
    if body=$(curl --fail --silent --show-error --connect-timeout 2 --max-time 3 \
          "http://127.0.0.1:$management_host_port/actuator/health$endpoint" 2>/dev/null) \
        && printf '%s' "$body" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status") == "UP" else 1)' 2>/dev/null; then
      stable=$((stable + 1))
      if (( stable >= stable_required )); then return 0; fi
    else
      stable=0
    fi
    sleep "$poll_seconds"
  done
  echo 'Application readiness timed out' >&2
  return 1
}

check_migrations() {
  local mode=$1 destination=$2
  # The alternate Java main does not start HTTP, consumers, schedulers, or Flyway migrate.
  docker create --name "$check_name" --memory 192m --pids-limit 128 \
    --env-file "$env_file" --env "DEPLOYMENT_REVISION=$revision" \
    --entrypoint java "$image" -Xmx96m \
    -Dloader.main=com.inninglog.ops.MigrationCheck -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher "$mode" /tmp/migration-state.json >/dev/null
  docker start -a "$check_name"
  test "$(docker inspect --format '{{.State.ExitCode}}' "$check_name")" = 0
  docker cp "$check_name:/tmp/migration-state.json" "$destination"
  docker rm "$check_name" >/dev/null
}

finish() {
  local status=$?
  trap - EXIT
  docker rm -f "$check_name" >/dev/null 2>&1 || true
  if (( status != 0 && switched == 1 )); then
    echo 'Deployment failed; restoring the previous application container' >&2
    if (( had_previous == 1 )); then
      if docker container inspect "$name-rollback" >/dev/null 2>&1; then
        docker rm -f "$name" >/dev/null 2>&1 || true
        docker rename "$name-rollback" "$name" || { echo 'Rollback rename failed' >&2; exit 1; }
      fi
      if docker start "$name" >/dev/null; then
        rollback_image=$(docker inspect --format '{{.Image}}' "$name")
        rollback_restarts=$(docker inspect --format '{{.RestartCount}}' "$name")
        wait_healthy "$rollback_image" '' "$rollback_restarts" || echo 'Previous application did not recover health' >&2
      else
        echo 'Previous application could not be started' >&2
      fi
    else
      docker rm -f "$name" >/dev/null 2>&1 || true
      echo 'No previous container is available for rollback' >&2
    fi
  fi
  # Rollback success must never turn the failed deployment green. Database changes are not undone.
  exit "$status"
}
trap finish EXIT

echo 'Checking candidate against the live migration history before stopping the server'
check_migrations preflight "$output_dir/migration-preflight.json"
expected_image=$(docker image inspect --format '{{.Id}}' "$image")
if docker container inspect "$name" >/dev/null 2>&1; then
  had_previous=1
  if docker container inspect "$name-rollback" >/dev/null 2>&1; then docker rm "$name-rollback" >/dev/null; fi
  switched=1
  docker stop "$name" >/dev/null
  docker rename "$name" "$name-rollback"
fi
switched=1
extra=()
if grep -Eiq '^[[:space:]]*FIREBASE_ENABLED[[:space:]]*=[[:space:]]*"?true"?[[:space:]]*$' "$env_file"; then
  extra+=(--mount "type=bind,src=$firebase_file,dst=/run/secrets/firebase-admin.json,readonly")
fi
docker run -d --name "$name" --restart unless-stopped \
  --memory "$memory" --memory-swap "$memory_swap" --pids-limit "$pids" \
  --env-file "$env_file" --env 'MANAGEMENT_SERVER_ADDRESS=0.0.0.0' \
  --env "MANAGEMENT_SERVER_PORT=$management_port" \
  --env 'MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true' \
  --env 'MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db' \
  "${extra[@]}" -p "$bind:$host_port:$app_port" -p "127.0.0.1:$management_host_port:$management_port" \
  "$image" >/dev/null
wait_healthy "$expected_image" '/readiness' 0
check_migrations snapshot "$output_dir/migration-state.json"
# Ensure the live process survived the post-deployment DB verification too.
wait_healthy "$expected_image" '/readiness' 0
echo 'Application and database are ready; verified migration snapshot saved'
