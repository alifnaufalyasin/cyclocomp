#!/usr/bin/env bash
# Proves the access rules on a running server. Creates two throw-away accounts, checks that one
# can never see, change, delete or download the other's files and that strangers get nothing,
# then deletes both accounts (their files go with them).
#
#   PB_URL=https://your-server PB_SUPERUSER_EMAIL=you@example.com PB_SUPERUSER_PASSWORD=... ./smoke-test.sh
#
# Needs curl and jq. The superuser password is only used to create and delete the test accounts.
set -uo pipefail

: "${PB_URL:?set PB_URL, e.g. https://fit.example.org}"
: "${PB_SUPERUSER_EMAIL:?set PB_SUPERUSER_EMAIL}"
: "${PB_SUPERUSER_PASSWORD:?set PB_SUPERUSER_PASSWORD}"
PB_URL="${PB_URL%/}"

for tool in curl jq; do
  command -v "$tool" > /dev/null || { echo "missing tool: $tool"; exit 2; }
done

PASS=0
FAIL=0
TMP="$(mktemp -d)"
A_ID=""; B_ID=""; SU_TOKEN=""

ok()   { PASS=$((PASS + 1)); echo "  ok    $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
# expect_status <description> <wanted: 2xx|non2xx> <actual>
expect_status() {
  local desc="$1" want="$2" got="$3"
  if [ "$want" = "2xx" ] && [[ "$got" =~ ^2[0-9][0-9]$ ]]; then ok "$desc ($got)"
  elif [ "$want" = "non2xx" ] && ! [[ "$got" =~ ^2[0-9][0-9]$ ]]; then ok "$desc (refused with $got)"
  else bad "$desc (got $got, wanted $want)"; fi
}

# status of a request; the body goes to $TMP/body
call() { curl -s -o "$TMP/body" -w "%{http_code}" "$@"; }

cleanup() {
  if [ -n "$SU_TOKEN" ]; then
    for id in "$A_ID" "$B_ID"; do
      [ -n "$id" ] && curl -s -o /dev/null -X DELETE -H "Authorization: $SU_TOKEN" "$PB_URL/api/collections/users/records/$id"
    done
  fi
  rm -rf "$TMP"
}
trap cleanup EXIT

echo "Server: $PB_URL"
code=$(call "$PB_URL/api/health"); expect_status "server is up" 2xx "$code"
[ "$code" = "200" ] || { echo "Cannot reach the server; stopping."; exit 1; }

echo "Setup"
code=$(call -X POST "$PB_URL/api/collections/_superusers/auth-with-password" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg e "$PB_SUPERUSER_EMAIL" --arg p "$PB_SUPERUSER_PASSWORD" '{identity:$e,password:$p}')")
expect_status "superuser sign-in" 2xx "$code"
SU_TOKEN="$(jq -r '.token // empty' "$TMP/body")"
[ -n "$SU_TOKEN" ] || { echo "No superuser token; stopping."; exit 1; }

SUFFIX="$(date +%s)$RANDOM"
mkuser() { # <label> -> prints id; password in $TMP/<label>.pw
  local label="$1" pw
  pw="Tt$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)9"
  echo -n "$pw" > "$TMP/$label.pw"
  call -X POST "$PB_URL/api/collections/users/records" -H "Authorization: $SU_TOKEN" -H "Content-Type: application/json" \
    -d "$(jq -n --arg e "smoketest-$label-$SUFFIX@example.invalid" --arg p "$pw" '{email:$e,password:$p,passwordConfirm:$p}')" > "$TMP/code"
  jq -r '.id // empty' "$TMP/body"
}
A_ID="$(mkuser a)"; [ -n "$A_ID" ] && ok "account A created" || bad "account A created"
B_ID="$(mkuser b)"; [ -n "$B_ID" ] && ok "account B created" || bad "account B created"
[ -n "$A_ID" ] && [ -n "$B_ID" ] || { echo "Could not create test accounts; stopping."; exit 1; }

login() { # <label> -> token
  call -X POST "$PB_URL/api/collections/users/auth-with-password" -H "Content-Type: application/json" \
    -d "$(jq -n --arg e "smoketest-$1-$SUFFIX@example.invalid" --arg p "$(cat "$TMP/$1.pw")" '{identity:$e,password:$p}')" > /dev/null
  jq -r '.token // empty' "$TMP/body"
}
A_TOKEN="$(login a)"; B_TOKEN="$(login b)"
[ -n "$A_TOKEN" ] && ok "A signs in" || bad "A signs in"
[ -n "$B_TOKEN" ] && ok "B signs in" || bad "B signs in"

echo "Strangers"
code=$(call -X POST "$PB_URL/api/collections/users/records" -H "Content-Type: application/json" \
  -d '{"email":"stranger@example.invalid","password":"Stranger12345","passwordConfirm":"Stranger12345"}')
expect_status "open sign-up is refused" non2xx "$code"
code=$(call "$PB_URL/api/collections/files/records");
if [ "$(jq -r '.totalItems // 0' "$TMP/body" 2> /dev/null)" = "0" ] || ! [[ "$code" =~ ^2 ]]; then ok "anonymous list shows nothing ($code)"; else bad "anonymous list shows data"; fi

echo "Account A stores a file"
printf 'secret ride of A\n' > "$TMP/a.gpx"
code=$(call -X POST "$PB_URL/api/collections/files/records" -H "Authorization: $A_TOKEN" \
  -F "owner=$A_ID" -F "path=rides/2026/smoke.gpx" -F "sha=abc123" -F "content=@$TMP/a.gpx;filename=smoke.gpx")
expect_status "A creates a file" 2xx "$code"
REC_ID="$(jq -r '.id // empty' "$TMP/body")"; FILE_NAME="$(jq -r '.content // empty' "$TMP/body")"
code=$(call -X POST "$PB_URL/api/collections/files/records" -H "Authorization: $A_TOKEN" \
  -F "owner=$A_ID" -F "path=rides/2026/smoke.gpx" -F "sha=zzz" -F "content=@$TMP/a.gpx;filename=smoke.gpx")
expect_status "same path twice is refused (no silent overwrite)" non2xx "$code"
code=$(call -G "$PB_URL/api/collections/files/records" -H "Authorization: $A_TOKEN" --data-urlencode "filter=path~'rides/'")
[ "$(jq -r '.totalItems // -1' "$TMP/body")" = "1" ] && ok "A lists its file" || bad "A lists its file"

echo "A downloads its own file"
code=$(call -X POST "$PB_URL/api/files/token" -H "Authorization: $A_TOKEN"); A_FT="$(jq -r '.token // empty' "$TMP/body")"
code=$(call "$PB_URL/api/files/files/$REC_ID/$FILE_NAME?token=$A_FT")
if [ "$code" = "200" ] && grep -q "secret ride of A" "$TMP/body"; then ok "A downloads with a file token"; else bad "A downloads with a file token ($code)"; fi
code=$(call "$PB_URL/api/files/files/$REC_ID/$FILE_NAME")
expect_status "download without a token is refused" non2xx "$code"

echo "Account B tries to reach A's data"
code=$(call -G "$PB_URL/api/collections/files/records" -H "Authorization: $B_TOKEN")
[ "$(jq -r '.totalItems // -1' "$TMP/body")" = "0" ] && ok "B's list is empty" || bad "B's list is not empty"
code=$(call "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $B_TOKEN"); expect_status "B cannot view A's record" non2xx "$code"
code=$(call -X PATCH "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $B_TOKEN" -F "sha=hacked"); expect_status "B cannot change A's record" non2xx "$code"
code=$(call -X DELETE "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $B_TOKEN"); expect_status "B cannot delete A's record" non2xx "$code"
code=$(call -X POST "$PB_URL/api/collections/files/records" -H "Authorization: $B_TOKEN" \
  -F "owner=$A_ID" -F "path=rides/2026/planted.gpx" -F "content=@$TMP/a.gpx;filename=planted.gpx")
expect_status "B cannot plant a file in A's account" non2xx "$code"
code=$(call -X POST "$PB_URL/api/files/token" -H "Authorization: $B_TOKEN"); B_FT="$(jq -r '.token // empty' "$TMP/body")"
code=$(call "$PB_URL/api/files/files/$REC_ID/$FILE_NAME?token=$B_FT"); expect_status "B's file token cannot download A's file" non2xx "$code"
code=$(call -X PATCH "$PB_URL/api/collections/users/records/$A_ID" -H "Authorization: $B_TOKEN" -F "name=owned"); expect_status "B cannot edit A's account" non2xx "$code"

echo "A keeps control of its own data"
code=$(call -X PATCH "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $A_TOKEN" -F "sha=def456" -F "content=@$TMP/a.gpx;filename=smoke.gpx")
expect_status "A can update its own file" 2xx "$code"
code=$(call -X PATCH "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $A_TOKEN" -F "owner=$B_ID")
expect_status "A cannot hand its file to B" non2xx "$code"

echo "Deleting an account removes its files"
call -X DELETE "$PB_URL/api/collections/users/records/$A_ID" -H "Authorization: $A_TOKEN" > /dev/null
code=$(call "$PB_URL/api/collections/files/records/$REC_ID" -H "Authorization: $SU_TOKEN")
expect_status "A's file is gone with the account" non2xx "$code"
A_ID=""   # already removed

echo
echo "Passed: $PASS   Failed: $FAIL"
[ "$FAIL" -eq 0 ] && echo "All checks passed." || echo "Some checks failed: do NOT invite friends until this is fixed."
[ "$FAIL" -eq 0 ]
