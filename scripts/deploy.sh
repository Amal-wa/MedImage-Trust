#!/usr/bin/env bash
# Déploie les deux WAR et la PWA sur l'instance WildFly active, recharge, puis lance le test de fumée.
#
# Usage : scripts/deploy.sh <dev|prod>
#
# Variables : MIT_BASE (défaut /opt/medimage), MIT_WILDFLY_HOME (défaut $MIT_BASE/wildfly), MIT_DOMAIN (prod),
# MIT_API_WAR et MIT_IAM_WAR (défaut : middleware/api/target/api-1.0.war et middleware/iam/target/iam-1.0.war),
# MIT_FRONTEND_DIST (défaut : frontend/dist).
# Le test de fumée échoue au premier écart (code de sortie 1).
set -euo pipefail

MIT_SCRIPT="deploy"
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

[[ $# -eq 1 ]] || die "usage : $0 <dev|prod>"
ENV_NAME="$1"
check_env "$ENV_NAME"
DOMAIN="$(domain_for "$ENV_NAME")"
HOME_WF="${MIT_WILDFLY_HOME:-${MIT_BASE}/wildfly}"
API_WAR="${MIT_API_WAR:-${MIT_REPO}/middleware/api/target/api-1.0.war}"
IAM_WAR="${MIT_IAM_WAR:-${MIT_REPO}/middleware/iam/target/iam-1.0.war}"
DIST="${MIT_FRONTEND_DIST:-${MIT_REPO}/frontend/dist}"

# Options curl : en dev, on fait confiance au seul certificat autosigné ; en prod, aux autorités du système.
CURL_TLS=()
if [[ "$ENV_NAME" == "dev" ]]; then
    CURL_TLS=(--cacert "${MIT_TLS_DIR}/medimage-dev.crt")
fi

# Échec du test de fumée : message précis, arrêt immédiat.
fail() {
    die "test de fumée : $*"
}

# --- Déploiement ---------------------------------------------------------------------------------------------------

deploy_wars() {
    local war
    for war in "$API_WAR" "$IAM_WAR"; do
        [[ -f "$war" ]] || die "WAR introuvable : $war (construire d'abord : mvn -B -f middleware/pom.xml package)"
        log "déploiement de $(basename "$war")"
        jboss_cli "$HOME_WF" --command="deploy --force ${war}" >/dev/null
    done
}

deploy_frontend() {
    local target="${MIT_SITES_DIR}/www"
    mkdir -p "$target"
    if [[ -d "$DIST" && -n "$(ls -A "$DIST" 2>/dev/null)" ]]; then
        log "copie de la PWA ($DIST) dans $target"
        find "$target" -mindepth 1 -delete
        cp -R "${DIST}/." "$target/"
    else
        log "AVERTISSEMENT : $DIST absent ou vide ; page de remplacement publiée (signalée comme telle)"
        find "$target" -mindepth 1 -delete
        placeholder_page "${target}/index.html" "www"
    fi
    [[ -f "${MIT_SITES_DIR}/admin/index.html" ]] || placeholder_page "${MIT_SITES_DIR}/admin/index.html" "admin"
}

# --- Test de fumée -------------------------------------------------------------------------------------------------

# En-têtes de réponse (minuscules) d'une requête GET sur l'URL $1.
headers_of() {
    curl -sS "${CURL_TLS[@]}" --max-time 10 -o /dev/null -D - "$1" | tr -d '\r' | tr '[:upper:]' '[:lower:]'
}

check_health() {
    local url="$1" body
    body="$(curl -fsS "${CURL_TLS[@]}" --max-time 10 "$url")" || fail "$url ne répond pas"
    [[ "$body" == '{"status":"UP"}' ]] || fail "$url répond « $body » au lieu de {\"status\":\"UP\"}"
    log "OK : $url → {\"status\":\"UP\"}"
}

# En-têtes communs aux quatre hôtes ($1 : nom de l'hôte, $2 : ses en-têtes) : nosniff (durcissement de add-api.cli
# pour api et iam), et aucun en-tête qui révèle le serveur ou sa version (durcissement de default-host.cli).
check_common_headers() {
    local host="$1" headers="$2"
    grep -Eq '^x-content-type-options: *nosniff' <<<"$headers" || fail "X-Content-Type-Options: nosniff absent sur $host"
    if grep -q '^x-powered-by:' <<<"$headers"; then
        fail "X-Powered-By présent sur $host : $(grep '^x-powered-by:' <<<"$headers")"
    fi
    if grep -Eq '^server: *[^ ]' <<<"$headers"; then
        fail "en-tête Server présent sur $host : $(grep '^server:' <<<"$headers")"
    fi
}

check_headers() {
    local api_headers iam_headers www_headers admin_headers
    api_headers="$(headers_of "https://api.${DOMAIN}/api/health")"
    iam_headers="$(headers_of "https://iam.${DOMAIN}/iam/health")"
    www_headers="$(headers_of "https://www.${DOMAIN}/")"
    admin_headers="$(headers_of "https://admin.${DOMAIN}/")"
    grep -q '^strict-transport-security:' <<<"$api_headers" || fail "Strict-Transport-Security absent sur api"
    grep -Eq '^cache-control:.*no-store' <<<"$api_headers" || fail "Cache-Control: no-store absent sur /api/health"
    # NoStoreFilter (medimage-core) doit aussi être découvert dans iam-1.0.war.
    grep -Eq '^cache-control:.*no-store' <<<"$iam_headers" || fail "Cache-Control: no-store absent sur /iam/health"
    grep -q '^strict-transport-security:' <<<"$www_headers" || fail "Strict-Transport-Security absent sur www"
    check_common_headers api "$api_headers"
    check_common_headers iam "$iam_headers"
    check_common_headers www "$www_headers"
    check_common_headers admin "$admin_headers"
    log "OK : HSTS, Cache-Control: no-store (api, iam) ; nosniff, ni X-Powered-By ni Server (api, iam, www, admin)"
}

check_redirect() {
    local result code location
    result="$(curl -sS --max-time 10 -o /dev/null -w '%{http_code} %{redirect_url}' "http://api.${DOMAIN}/api/health")"
    code="${result%% *}"
    location="${result#* }"
    [[ "$code" =~ ^30[1278]$ ]] || fail "HTTP ne redirige pas (code $code)"
    [[ "$location" == https://* ]] || fail "la redirection HTTP ne mène pas vers HTTPS ($location)"
    log "OK : HTTP → HTTPS ($code vers $location)"
}

check_tls() {
    # TLS 1.2 doit être refusé, TLS 1.3 accepté.
    if curl -sS "${CURL_TLS[@]}" --max-time 10 --tlsv1.2 --tls-max 1.2 -o /dev/null \
        "https://api.${DOMAIN}/api/health" 2>/dev/null; then
        fail "TLS 1.2 est accepté"
    fi
    local target="api.${DOMAIN}:443"
    [[ "$ENV_NAME" == "dev" ]] && target="127.0.0.1:443"
    if openssl s_client -connect "$target" -servername "api.${DOMAIN}" -tls1_2 </dev/null >/dev/null 2>&1; then
        fail "TLS 1.2 est accepté (openssl s_client -tls1_2)"
    fi
    openssl s_client -connect "$target" -servername "api.${DOMAIN}" -tls1_3 </dev/null 2>/dev/null \
        | grep -q 'TLSv1.3' || fail "TLS 1.3 n'est pas négocié"
    log "OK : TLS 1.2 refusé, TLS 1.3 négocié"
}

check_whoami() {
    local body_file result code type
    body_file="$(mktemp)"
    result="$(curl -sS "${CURL_TLS[@]}" --max-time 10 -o "$body_file" -w '%{http_code} %{content_type}' \
        "https://api.${DOMAIN}/api/whoami")"
    code="${result%% *}"
    type="${result#* }"
    # TODO(prompt-12) : rendre ce contrôle strict (supprimer la tolérance du 404 une fois /api/whoami livré).
    if [[ "$code" == "404" ]]; then
        rm -f "$body_file"
        echo "::warning::endpoint whoami absent (prompt 12)"
        log "avertissement : /api/whoami répond 404, endpoint whoami absent (prompt 12) ; contrôle non bloquant"
        return 0
    fi
    if [[ "$code" != "401" ]]; then
        rm -f "$body_file"
        fail "/api/whoami sans jeton répond $code au lieu de 401 (socle du prompt 12)"
    fi
    [[ "$type" == application/problem+json* ]] || { rm -f "$body_file"; fail "/api/whoami : type $type au lieu de application/problem+json"; }
    if grep -Eq 'Exception|	at |\.java:[0-9]+' "$body_file"; then
        rm -f "$body_file"
        fail "/api/whoami : la réponse contient une trace d'appel"
    fi
    rm -f "$body_file"
    log "OK : /api/whoami sans jeton → 401 application/problem+json, sans trace"
}

check_prod_only() {
    command -v dig >/dev/null || fail "dig est requis en prod (paquet dnsutils / bind9-dnsutils)"
    dig +short CAA "$DOMAIN" | grep -q 'letsencrypt.org' || fail "dig CAA $DOMAIN ne renvoie pas letsencrypt.org"
    openssl s_client -connect "api.${DOMAIN}:443" -servername "api.${DOMAIN}" -status </dev/null 2>/dev/null \
        | grep -q 'OCSP Response Status: successful' || fail "aucune réponse OCSP agrafée (openssl s_client -status)"
    log "OK : CAA letsencrypt.org et réponse OCSP agrafée"
}

main() {
    [[ -x "${HOME_WF}/bin/jboss-cli.sh" ]] || die "WildFly introuvable : $HOME_WF (lancer d'abord install-wildfly.sh)"
    log "déploiement ${ENV_NAME} sur $HOME_WF (domaine ${DOMAIN})"
    deploy_wars
    deploy_frontend
    reload_wildfly "$HOME_WF"

    log "test de fumée"
    check_health "https://api.${DOMAIN}/api/health"
    check_health "https://iam.${DOMAIN}/iam/health"
    check_headers
    check_redirect
    check_tls
    check_whoami
    if [[ "$ENV_NAME" == "prod" ]]; then
        check_prod_only
    fi
    log "déploiement ${ENV_NAME} réussi : test de fumée au vert"
}

main
