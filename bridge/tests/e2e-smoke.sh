#!/bin/bash
# E2E smoke for the bridge (plan Task 13).
# Writes go to a FIXTURE COPY of today's daily file — never the live vault.
# The live vault is only exercised read-only.
set -e
TMP=$(mktemp -d)
trap 'kill $BRIDGE_PID $LIVE_PID 2>/dev/null; rm -rf "$TMP"' EXIT
ETD=$(TZ=America/New_York date '+%Y-%m-%d')
BRIDGE=$(dirname "$0")/../scout_bridge.py

mkdir -p "$TMP/vault/action-items" "$TMP/inbox"
cp ~/Scout/action-items/action-items-"$ETD".md "$TMP/vault/action-items/"
echo "smoke-secret" > "$TMP/secret"

python3 "$BRIDGE" --port 8788 --vault "$TMP/vault" --inbox "$TMP/inbox" \
  --secret-file "$TMP/secret" >"$TMP/bridge.log" 2>&1 &
BRIDGE_PID=$!
sleep 1

echo "── health:"
curl -s -H "Authorization: Bearer smoke-secret" http://127.0.0.1:8788/api/v1/health
echo
echo "── today (fixture copy):"
curl -s -H "Authorization: Bearer smoke-secret" http://127.0.0.1:8788/api/v1/action-items/today \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('items:', len(d['items']), '| first:', d['items'][0]['short_prefix'], d['items'][0]['priority'])"
echo "── wrong secret => expect 401:"
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer nope" http://127.0.0.1:8788/api/v1/health
echo "── mark DEMO1 done (fixture copy only):"
curl -s -X POST -H "Authorization: Bearer smoke-secret" -H "Content-Type: application/json" \
  -d '{}' http://127.0.0.1:8788/api/v1/action-items/DEMO1/done
echo
echo "── [x] flips in the copy containing DEMO1:"
grep -c 'DEMO1.*🔴\|\[x\] \[#DEMO1\]' "$TMP/vault/action-items/action-items-$ETD.md" || true
grep -o '\[x\] \[#DEMO1\]' "$TMP/vault/action-items/action-items-$ETD.md" || echo "(pattern check below)"
grep -o '\- \[.\] \[#DEMO1\]' "$TMP/vault/action-items/action-items-$ETD.md"
echo "── live vault untouched check:"
grep -o '\- \[.\] \[#DEMO1\]' ~/Scout/action-items/action-items-"$ETD".md
echo "── post synthetic event:"
curl -s -X POST -H "Authorization: Bearer smoke-secret" -H "Content-Type: application/json" \
  -d '{"events":[{"v":1,"key":"smoke","package":"com.test","post_time_ms":1,"title":"smoke","text":"e2e","big_text":null,"category":null,"is_ongoing":false,"removed":false,"captured_at":"2026-06-12T21:00:00Z"}]}' \
  http://127.0.0.1:8788/api/v1/events/notifications
echo
echo "── inbox JSONL:"
cat "$TMP/inbox/phone-notifications-$ETD.jsonl"
kill $BRIDGE_PID 2>/dev/null

echo "── Control Center: run-now on FIXTURE vault with a fake runner (no tokens spent):"
cat > "$TMP/vault/run-scout.sh" <<'FAKE'
#!/bin/bash
exit 0
FAKE
chmod +x "$TMP/vault/run-scout.sh"
python3 "$BRIDGE" --port 8790 --vault "$TMP/vault" --inbox "$TMP/inbox" \
  --secret-file "$TMP/secret" >/dev/null 2>&1 &
FIX_PID=$!
sleep 1
curl -s -X POST -H "Authorization: Bearer smoke-secret" -H "Content-Type: application/json" \
  -d '{"type":"consolidation","bypass_budget":true}' http://127.0.0.1:8790/api/v1/runs
echo
kill $FIX_PID 2>/dev/null

echo "── live-vault READ-ONLY checks (port 8789):"
python3 "$BRIDGE" --port 8789 --vault ~/Scout --inbox "$TMP/inbox" \
  --secret-file "$TMP/secret" >/dev/null 2>&1 &
LIVE_PID=$!
sleep 1
curl -s -H "Authorization: Bearer smoke-secret" http://127.0.0.1:8789/api/v1/action-items/today \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('live vault items:', len(d['items']))"
echo "── live /status (read-only):"
curl -s -H "Authorization: Bearer smoke-secret" http://127.0.0.1:8789/api/v1/status \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('recent_runs:', len(d['recent_runs']), '| today_spend_usd:', d['today_spend_usd'], '| running:', d['running']['active'], '| connectors:', len(d['connectors']))"
echo "E2E_SMOKE_COMPLETE"
