#!/usr/bin/env bash
# Installe WildFly et applique toute la configuration MedImage-Trust par jboss-cli (Infrastructure as Code).
#
# Usage : scripts/install-wildfly.sh <dev|prod>
#
# Étapes :
#   1. télécharge la version de WildFly fixée dans scripts/wildfly/versions.properties et vérifie sa somme SHA-256
#      (arrêt si elle ne correspond pas) ;
#   2. certificat : en dev, autosigné pour *.medimage.localhost (keytool, rôle de mkcert sur un poste) ; en prod,
#      certificat Let's Encrypt *.${MIT_DOMAIN} déjà obtenu (voir scripts/README.md), converti en PKCS#12 ;
#   3. démarre WildFly, puis applique dans l'ordre tls.cli, limits.cli, default-host.cli, add-host.cli (admin),
#      add-api.cli (api), add-api.cli (iam), api-properties.cli, avec --properties=<env>.properties.
#
# Variables : MIT_BASE (défaut /opt/medimage), MIT_DOMAIN (prod), MIT_BIND (adresse publique ; défaut 127.0.0.1 en
# dev, 0.0.0.0 en prod), MIT_FORCE=1 pour remplacer une installation existante de la même version.
# Les ports 80 et 443 exigent la capacité CAP_NET_BIND_SERVICE pour java (voir scripts/README.md).
set -euo pipefail

MIT_SCRIPT="install-wildfly"
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

[[ $# -eq 1 ]] || die "usage : $0 <dev|prod>"
ENV_NAME="$1"
check_env "$ENV_NAME"
DOMAIN="$(domain_for "$ENV_NAME")"
if [[ "$ENV_NAME" == "prod" ]]; then
    export MIT_BIND="${MIT_BIND:-0.0.0.0}"
fi

VERSIONS="${MIT_CLI_DIR}/versions.properties"
WILDFLY_VERSION="$(read_prop "$VERSIONS" WILDFLY_VERSION)"
WILDFLY_URL="$(read_prop "$VERSIONS" WILDFLY_URL)"
WILDFLY_SHA256="$(read_prop "$VERSIONS" WILDFLY_SHA256)"

# Prépare le magasin TLS PKCS#12 de l'environnement et son mot de passe (fichier 600, hors dépôt).
prepare_tls() {
    mkdir -p "$MIT_TLS_DIR"
    chmod 700 "$MIT_TLS_DIR"
    local ks="${MIT_TLS_DIR}/medimage.p12" pass_file="${MIT_TLS_DIR}/keystore.pass"
    if [[ ! -s "$pass_file" ]]; then
        # Mot de passe aléatoire hexadécimal : sans caractère spécial qui gênerait jboss-cli.
        ( umask 077 && openssl rand -hex 24 > "$pass_file" )
    fi
    local pass
    pass="$(cat "$pass_file")"

    if [[ "$ENV_NAME" == "dev" ]]; then
        if [[ ! -f "$ks" ]]; then
            log "dev : certificat autosigné pour *.${DOMAIN} (keytool)"
            keytool -genkeypair -alias medimage -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
                -validity 365 -dname "CN=*.${DOMAIN}, O=MedImage-Trust (dev)" \
                -ext "SAN=dns:*.${DOMAIN},dns:${DOMAIN}" \
                -keystore "$ks" -storetype PKCS12 -storepass "$pass" -keypass "$pass" -noprompt
        fi
        # Certificat public (PEM) pour curl --cacert, et magasin de confiance Java pour LoadBench.
        keytool -exportcert -rfc -alias medimage -keystore "$ks" -storetype PKCS12 -storepass "$pass" \
            -file "${MIT_TLS_DIR}/medimage-dev.crt"
        rm -f "${MIT_TLS_DIR}/truststore.p12"
        # Le magasin de confiance ne contient qu'un certificat public : son mot de passe n'est pas un secret.
        keytool -importcert -noprompt -alias medimage-dev -file "${MIT_TLS_DIR}/medimage-dev.crt" \
            -keystore "${MIT_TLS_DIR}/truststore.p12" -storetype PKCS12 -storepass changeit
    else
        local live="${MIT_LETSENCRYPT_DIR:-/etc/letsencrypt/live/${DOMAIN}}"
        [[ -f "${live}/fullchain.pem" && -f "${live}/privkey.pem" ]] \
            || die "certificat Let's Encrypt absent dans $live (à obtenir d'abord : voir scripts/README.md)"
        log "prod : conversion du certificat Let's Encrypt *.${DOMAIN} en PKCS#12"
        openssl pkcs12 -export -in "${live}/fullchain.pem" -inkey "${live}/privkey.pem" -name medimage \
            -out "$ks" -passout "file:${pass_file}"
    fi
    chmod 600 "$ks" "$pass_file"
}

main() {
    mkdir -p "$MIT_BASE" "$MIT_LOG_DIR"
    log "installation de WildFly ${WILDFLY_VERSION} pour l'environnement ${ENV_NAME} (domaine ${DOMAIN})"
    local home
    home="$(install_version "$WILDFLY_VERSION" "$WILDFLY_URL" "$WILDFLY_SHA256")"
    prepare_tls
    prepare_sites
    start_wildfly "$home"
    configure_wildfly "$home" "$ENV_NAME"
    # L'instance active est désignée par un lien symbolique (bascule de update-wildfly.sh).
    ln -sfn "$home" "${MIT_BASE}/wildfly"
    log "WildFly ${WILDFLY_VERSION} installé et configuré ($home, lien ${MIT_BASE}/wildfly)"
}

main
