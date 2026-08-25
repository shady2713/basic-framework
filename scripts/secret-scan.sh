#!/bin/sh
# Blocks commits that stage likely hardcoded secrets.
# A "secret" here: password/secret/token/api-key assigned an 8+ char literal
# that is not a placeholder or env reference. Docs and .example files are exempt.
pattern='(password|secret|token|api[_-]?key)["'"'"']?[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"'{$[:space:]]{8,}'
files=$(git -c core.quotepath=false diff --cached --name-only --diff-filter=ACMR | grep -vE '(\.example$|\.md$|^docs/|scripts/secret-scan\.sh)' || true)
[ -z "$files" ] && exit 0

violations=$(printf '%s\n' "$files" | while IFS= read -r file; do
  if git -c core.quotepath=false diff --cached --unified=0 -- "$file" \
    | grep '^+' \
    | grep -v '^+++' \
    | grep -qIE "$pattern"; then
    printf '%s\n' "$file"
  fi
done)

if [ -n "$violations" ]; then
  printf '%s\n' "$violations"
  echo "secret-scan: possible hardcoded secret in the files above" >&2
  exit 1
fi
exit 0
