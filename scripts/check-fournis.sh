#!/usr/bin/env bash
# Vérifie que les trois scripts de base jboss-cli fournis (scripts/wildfly/fournis/) n'ont pas changé.
# Les sommes SHA-256 attendues sont figées dans scripts/wildfly/fournis.sha256. Échec si un fichier diffère ou manque,
# ou si un fichier inattendu apparaît dans fournis/.
set -euo pipefail

FOURNIS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/wildfly/fournis" && pwd)"
SUMS="${FOURNIS_DIR}/../fournis.sha256"

cd "$FOURNIS_DIR"
sha256sum --check --strict "$SUMS"

# Aucun fichier ajouté dans fournis/ (la liste doit être exactement celle des sommes).
expected="$(awk '{print $2}' "$SUMS" | sort)"
actual="$(find . -maxdepth 1 -type f -printf '%f\n' | sort)"
if [[ "$expected" != "$actual" ]]; then
    echo "ERREUR : le contenu de scripts/wildfly/fournis/ ne correspond pas à fournis.sha256" >&2
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
    exit 1
fi
echo "scripts de base fournis intacts"
