#!/bin/bash
# Wait for the newest Actions run for HEAD of /home/claude/ferry; print result and failed-job log tail.
cd /home/claude/ferry
sha=$(git rev-parse HEAD)
wf=${1:-}
for i in $(seq 1 120); do
  q="repos/DrSaurabh/ferry/actions/runs?head_sha=$sha&per_page=20"
  runs=$(gh api "$q" --jq '.workflow_runs[] | "\(.id) \(.name) \(.status) \(.conclusion)"')
  [ -n "$wf" ] && runs=$(echo "$runs" | grep " $wf ")
  if [ -n "$runs" ] && ! echo "$runs" | grep -qvE " completed "; then break; fi
  sleep 20
done
echo "$runs"
echo "$runs" | while read id name st concl; do
  if [ "$concl" != "success" ]; then
    gh api "repos/DrSaurabh/ferry/actions/runs/$id/jobs" --jq '.jobs[] | select(.conclusion!="success") | .id' | while read jid; do
      echo "=== failed job $jid ($name) ==="
      gh api "repos/DrSaurabh/ferry/actions/jobs/$jid/logs" 2>/dev/null | grep -vE "^\s*$" | grep -iE "error|fail|exception|what went wrong|e: |w: " | head -40
    done
  fi
done
