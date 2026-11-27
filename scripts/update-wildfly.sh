#!/usr/bin/env bash
# Mise à jour automatique de WildFly, sans perte de configuration (Infrastructure as Code).
#
# Usage : scripts/update-wildfly.sh <dev|prod>
#
# 1. interroge l'API des releases GitHub de wildfly/wildfly (dernière version publiée, hors préversions) ;
# 2. si elle est plus récente que l'instance active : installation à côté de l'ancienne, réapplication de toute la
#    configuration par les .cli, deploy.sh (qui lance le test de fumée) ;
# 3. si tout passe : bascule du lien symbolique $MIT_BASE/wildfly ; sinon : arrêt de la nouvelle instance et
#    redémarrage de l'ancienne.
# Tout est journalisé dans $MIT_BASE/logs/update-wildfly.log.
#
# Somme de contrôle : le projet WildFly ne publie qu'une somme SHA-1 (.sha1) à côté de l'archive ; elle est vérifiée,
# puis la somme SHA-256 est calculée et journalisée pour être reportée dans scripts/wildfly/versions.properties.
set -euo pipefail

MIT_SCRIPT="update-wildfly"
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

[[ $# -eq 1 ]] || die "usage : $0 <dev|prod>"
ENV_NAME="$1"
check_env "$ENV_NAME"
if [[ "$ENV_NAME" == "prod" ]]; then
    export MIT_BIND="${MIT_BIND:-0.0.0.0}"
fi

mkdir -p "$MIT_LOG_DIR"
# Toute la sortie d'erreur (journal) est aussi écrite dans le fichier de suivi.
exec 2> >(tee -a "${MIT_LOG_DIR}/update-wildfly.log" >&2)

CURRENT_LINK="${MIT_BASE}/wildfly"
[[ -L "$CURRENT_LINK" ]] || die "$CURRENT_LINK n'est pas un lien symbolique (lancer d'abord install-wildfly.sh)"
OLD_HOME="$(readlink -f "$CURRENT_LINK")"
OLD_VERSION="$(basename "$OLD_HOME")"
OLD_VERSION="${OLD_VERSION#wildfly-}"

# Dernière version publiée (hors préversions) d'après l'API GitHub.
latest_version() {
    curl -fsSL -H 'Accept: application/vnd.github+json' \
        https://api.github.com/repos/wildfly/wildfly/releases/latest \
        | grep -m1 '"tag_name"' | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/'
}

# Vrai si la version $1 est strictement plus récente que $2 (ordre des versions de sort -V).
is_newer() {
    [[ "$1" != "$2" && "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" == "$1" ]]
}

# Retour à l'ancienne version : arrêt de la nouvelle instance, redémarrage de l'ancienne, lien inchangé.
rollback() {
    local new_home="$1"
    log "échec : retour à WildFly ${OLD_VERSION}"
    stop_wildfly "$new_home"
    start_wildfly "$OLD_HOME"
    die "mise à jour annulée ; WildFly ${OLD_VERSION} est de nouveau actif ($OLD_HOME)"
}

main() {
    local new_version base_url sha1_expected archive sha1_actual sha256 new_home
    new_version="$(latest_version)"
    [[ -n "$new_version" ]] || die "impossible de lire la dernière version sur l'API GitHub"
    log "version active : ${OLD_VERSION} ; dernière version publiée : ${new_version}"
    if ! is_newer "$new_version" "$OLD_VERSION"; then
        log "WildFly est à jour : rien à faire"
        return 0
    fi

    base_url="https://github.com/wildfly/wildfly/releases/download/${new_version}"
    # Archive de vérification, distincte de celle qu'install_version recopie (pas d'écriture sur la source).
    archive="${MIT_BASE}/downloads/update-wildfly-${new_version}.tar.gz"
    mkdir -p "${MIT_BASE}/downloads"
    log "téléchargement de WildFly ${new_version}"
    curl -fsSL --retry 3 -o "$archive" "${base_url}/wildfly-${new_version}.tar.gz"
    sha1_expected="$(curl -fsSL "${base_url}/wildfly-${new_version}.tar.gz.sha1" | cut -c1-40)"
    sha1_actual="$(sha1sum "$archive" | cut -c1-40)"
    if [[ "$sha1_expected" != "$sha1_actual" ]]; then
        rm -f "$archive"
        die "somme SHA-1 incorrecte pour WildFly ${new_version} : mise à jour arrêtée"
    fi
    sha256="$(sha256sum "$archive" | cut -c1-64)"
    log "somme SHA-1 vérifiée ; SHA-256 = ${sha256} (à reporter dans scripts/wildfly/versions.properties)"

    # Installation à côté de l'ancienne (même archive, déjà vérifiée : download_verified la revérifie en SHA-256).
    new_home="$(install_version "$new_version" "file://${archive}" "$sha256")"

    # Les deux instances écoutent les mêmes ports : l'ancienne est arrêtée pendant la bascule.
    # Démarrage et configuration dans des sous-shells : un « die » n'y arrête que le sous-shell, et le retour à
    # l'ancienne version a bien lieu.
    stop_wildfly "$OLD_HOME"
    ( start_wildfly "$new_home" ) || rollback "$new_home"
    ( configure_wildfly "$new_home" "$ENV_NAME" ) || rollback "$new_home"
    if ! MIT_WILDFLY_HOME="$new_home" bash "${MIT_REPO}/scripts/deploy.sh" "$ENV_NAME"; then
        rollback "$new_home"
    fi
    rm -f "$archive"

    ln -sfn "$new_home" "$CURRENT_LINK"
    log "bascule réussie : ${CURRENT_LINK} → ${new_home} (WildFly ${new_version})"
    log "l'ancienne installation reste disponible pour un retour manuel : $OLD_HOME"
}

main
