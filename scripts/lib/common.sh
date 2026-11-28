#!/usr/bin/env bash
# Fonctions communes à install-wildfly.sh, deploy.sh et update-wildfly.sh.
# Ce fichier est chargé par « source » : il ne s'exécute pas seul.
# shellcheck shell=bash

# Racine du dépôt et dossier des scripts jboss-cli.
MIT_REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MIT_CLI_DIR="${MIT_REPO}/scripts/wildfly"

# Racine d'installation sur la machine (dev : runner ou poste ; prod : serveur). Les chemins des .properties
# (sites, magasin TLS) supposent /opt/medimage.
MIT_BASE="${MIT_BASE:-/opt/medimage}"
MIT_LOG_DIR="${MIT_BASE}/logs"
MIT_TLS_DIR="${MIT_BASE}/tls"
MIT_SITES_DIR="${MIT_BASE}/sites"

# Contrôleur de gestion : toujours l'interface « management » locale (127.0.0.1:9990), jamais l'interface publique.
MIT_CONTROLLER="${MIT_CONTROLLER:-127.0.0.1:9990}"

# Journalisation horodatée sur la sortie d'erreur (la sortie standard reste libre pour les résultats).
log() {
    printf '%s [%s] %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "${MIT_SCRIPT:-medimage}" "$*" >&2
}

# Arrêt immédiat avec un message d'erreur.
die() {
    log "ERREUR : $*"
    exit 1
}

# Vérifie l'environnement demandé (dev ou prod) ; en prod, MIT_DOMAIN doit être un nom deSEC en *.dedyn.io.
check_env() {
    local env="$1"
    case "$env" in
        dev) ;;
        prod)
            [[ -n "${MIT_DOMAIN:-}" ]] || die "MIT_DOMAIN est obligatoire en prod (nom deSEC en *.dedyn.io)"
            [[ "$MIT_DOMAIN" =~ ^[a-z0-9]([a-z0-9-]*[a-z0-9])?\.dedyn\.io$ ]] \
                || die "MIT_DOMAIN doit être un nom deSEC de la forme <nom>.dedyn.io"
            ;;
        *) die "environnement inconnu : « $env » (attendu : dev ou prod)" ;;
    esac
}

# Domaine public de l'environnement.
domain_for() {
    if [[ "$1" == "dev" ]]; then
        printf 'medimage.localhost\n'
    else
        printf '%s\n' "$MIT_DOMAIN"
    fi
}

# Lit la valeur d'une clé dans un fichier clé=valeur (sans évaluer son contenu).
read_prop() {
    local file="$1" key="$2" line
    line="$(grep -m1 -E "^${key//./\\.}=" "$file" || true)"
    [[ -n "$line" ]] || die "clé « $key » absente de $file"
    printf '%s\n' "${line#*=}"
}

# Écrit dans $2 les propriétés effectives de l'environnement $1 (@MIT_DOMAIN@ remplacé en prod).
effective_properties() {
    local env="$1" out="$2" src="${MIT_CLI_DIR}/${1}.properties"
    [[ -f "$src" ]] || die "fichier de propriétés introuvable : $src"
    if [[ "$env" == "prod" ]]; then
        sed "s/@MIT_DOMAIN@/${MIT_DOMAIN}/g" "$src" > "$out"
    else
        cp "$src" "$out"
    fi
}

# Écrit dans $2 les propriétés pour la seconde application (iam) : les clés iam.api.* remplacent api.*.
# java.util.Properties garde la dernière valeur d'une clé répétée.
iam_properties() {
    local base="$1" out="$2"
    cp "$base" "$out"
    grep -E '^iam\.api\.' "$base" | sed 's/^iam\.//' >> "$out"
}

# Appelle jboss-cli sur l'instance $1, connecté au contrôleur de gestion local.
jboss_cli() {
    local home="$1"
    shift
    "${home}/bin/jboss-cli.sh" --connect --controller="${MIT_CONTROLLER}" "$@"
}

# Attend que l'instance $1 soit dans l'état « running » (délai $2 secondes, 180 par défaut).
wait_running() {
    local home="$1" timeout="${2:-180}" waited=0
    while (( waited < timeout )); do
        if jboss_cli "$home" --command=":read-attribute(name=server-state)" 2>/dev/null | grep -q '"running"'; then
            return 0
        fi
        sleep 3
        waited=$(( waited + 3 ))
    done
    die "WildFly ($home) n'est pas « running » après ${timeout} s"
}

# Démarre l'instance $1 en arrière-plan ; l'adresse publique vient de MIT_BIND (127.0.0.1 en dev, 0.0.0.0 en prod).
# Les ports 80 et 443 (jboss.http.port, jboss.https.port) sont posés dans bin/standalone.conf par install-wildfly.sh.
start_wildfly() {
    local home="$1" bind="${MIT_BIND:-127.0.0.1}"
    mkdir -p "$MIT_LOG_DIR"
    log "démarrage de WildFly ($home), adresse publique $bind, gestion sur l'interface locale"
    nohup "${home}/bin/standalone.sh" -b "$bind" > "${MIT_LOG_DIR}/console.log" 2>&1 &
    printf '%s\n' "$!" > "${MIT_BASE}/wildfly.pid"
    wait_running "$home"
}

# Arrête proprement l'instance $1 si elle répond ; sans effet sinon.
stop_wildfly() {
    local home="$1"
    if jboss_cli "$home" --command=":read-attribute(name=server-state)" >/dev/null 2>&1; then
        log "arrêt de WildFly ($home)"
        jboss_cli "$home" --command=":shutdown" >/dev/null 2>&1 || true
        sleep 5
    fi
}

# Recharge l'instance $1 et attend qu'elle soit de nouveau prête.
reload_wildfly() {
    local home="$1"
    log "rechargement de la configuration"
    jboss_cli "$home" --command=":reload" >/dev/null
    sleep 3
    wait_running "$home"
}

# Télécharge l'archive $1 dans $2 et vérifie sa somme SHA-256 $3 ; arrêt si elle ne correspond pas.
download_verified() {
    local url="$1" dest="$2" sha256="$3"
    log "téléchargement de $url"
    curl -fsSL --retry 3 -o "$dest" "$url"
    log "vérification de la somme SHA-256"
    if ! printf '%s  %s\n' "$sha256" "$dest" | sha256sum -c --status; then
        rm -f "$dest"
        die "somme SHA-256 incorrecte pour $(basename "$dest") : archive supprimée, installation arrêtée"
    fi
}

# Installe la version $1 (archive $2, somme SHA-256 $3) dans $MIT_BASE/wildfly-<version> et écrit son chemin sur la
# sortie standard. Utilisée par install-wildfly.sh et update-wildfly.sh.
install_version() {
    local version="$1" url="$2" sha256="$3" home="${MIT_BASE}/wildfly-$1" archive
    if [[ -d "$home" ]]; then
        [[ "${MIT_FORCE:-0}" == "1" ]] || die "$home existe déjà (MIT_FORCE=1 pour le remplacer)"
        log "suppression de l'installation existante $home (MIT_FORCE=1)"
        rm -rf "$home"
    fi
    mkdir -p "${MIT_BASE}/downloads"
    archive="${MIT_BASE}/downloads/wildfly-${version}.tar.gz"
    download_verified "$url" "$archive" "$sha256"
    log "extraction dans $MIT_BASE"
    tar -xzf "$archive" -C "$MIT_BASE"
    [[ -x "${home}/bin/standalone.sh" ]] || die "archive inattendue : ${home}/bin/standalone.sh absent"
    tune_standalone_conf "$home"
    printf '%s\n' "$home"
}

# Applique toute la configuration jboss-cli (Infrastructure as Code) à l'instance démarrée $1, pour l'environnement $2.
# Ordre : tls.cli, limits.cli, default-host.cli, add-host.cli (admin), add-api.cli (api), add-api.cli (iam),
# api-properties.cli, puis rechargement.
configure_wildfly() {
    local home="$1" env="$2" props iam_props tmpdir
    tmpdir="$(mktemp -d)"
    # Fichiers temporaires : ils contiennent le mot de passe du magasin TLS, effacés à la fin de la fonction.
    props="${tmpdir}/${env}.properties"
    iam_props="${tmpdir}/${env}-iam.properties"
    effective_properties "$env" "$props"
    [[ -s "${MIT_TLS_DIR}/keystore.pass" ]] || die "mot de passe du magasin TLS absent (${MIT_TLS_DIR}/keystore.pass)"
    printf 'tls.keystore.password=%s\n' "$(cat "${MIT_TLS_DIR}/keystore.pass")" >> "$props"
    iam_properties "$props" "$iam_props"

    local step
    for step in tls.cli limits.cli default-host.cli add-host.cli; do
        log "jboss-cli : $step"
        jboss_cli "$home" --file="${MIT_CLI_DIR}/${step}" --properties="$props" \
            || { rm -rf "$tmpdir"; die "échec de $step"; }
    done
    log "jboss-cli : add-api.cli (api)"
    jboss_cli "$home" --file="${MIT_CLI_DIR}/add-api.cli" --properties="$props" \
        || { rm -rf "$tmpdir"; die "échec de add-api.cli (api)"; }
    log "jboss-cli : add-api.cli (iam)"
    jboss_cli "$home" --file="${MIT_CLI_DIR}/add-api.cli" --properties="$iam_props" \
        || { rm -rf "$tmpdir"; die "échec de add-api.cli (iam)"; }
    log "jboss-cli : api-properties.cli"
    jboss_cli "$home" --file="${MIT_CLI_DIR}/api-properties.cli" --properties="$props" \
        || { rm -rf "$tmpdir"; die "échec de api-properties.cli"; }
    rm -rf "$tmpdir"
    reload_wildfly "$home"
}

# Ajoute à bin/standalone.conf de l'instance $1 les options JVM du projet (une seule fois).
# - jdk.tls.server.enableStatusRequestExtension=true : OCSP stapling ;
# - jboss.http.port=80, jboss.https.port=443 : les prédicats des .cli testent %p = 80 ou 443.
#   MIT_HTTP_PORT et MIT_HTTPS_PORT ne servent qu'aux essais sur un poste où 80 ou 443 est déjà pris : sur un autre
#   port, les prédicats %p ne s'appliquent plus (pas de redirection HTTP → HTTPS, ou pas de HSTS).
tune_standalone_conf() {
    local conf="$1/bin/standalone.conf" http_port="${MIT_HTTP_PORT:-80}" https_port="${MIT_HTTPS_PORT:-443}"
    [[ -f "$conf" ]] || die "fichier introuvable : $conf"
    [[ "$http_port" =~ ^[0-9]+$ && "$https_port" =~ ^[0-9]+$ ]] || die "MIT_HTTP_PORT et MIT_HTTPS_PORT doivent être des nombres"
    if ! grep -q '# >>> MedImage-Trust' "$conf"; then
        # $JAVA_OPTS est écrit tel quel : standalone.sh le développe au démarrage.
        # shellcheck disable=SC2016
        {
            echo
            echo '# >>> MedImage-Trust (scripts/install-wildfly.sh)'
            echo 'JAVA_OPTS="$JAVA_OPTS -Djdk.tls.server.enableStatusRequestExtension=true"'
            printf 'JAVA_OPTS="$JAVA_OPTS -Djboss.http.port=%s -Djboss.https.port=%s"\n' "$http_port" "$https_port"
            echo '# <<< MedImage-Trust'
        } >> "$conf"
        if [[ "$http_port" != "80" || "$https_port" != "443" ]]; then
            log "attention : ports ${http_port}/${https_port} au lieu de 80/443 (essai local) ; redirection ou HSTS inactifs"
        fi
    fi
}

# Crée les dossiers des sites (www, admin) avec une page de remplacement si besoin : les gestionnaires de fichiers et
# le routeur SPA des .cli exigent que <dossier>/index.html existe.
prepare_sites() {
    local site
    for site in www admin; do
        mkdir -p "${MIT_SITES_DIR}/${site}"
        if [[ ! -f "${MIT_SITES_DIR}/${site}/index.html" ]]; then
            placeholder_page "${MIT_SITES_DIR}/${site}/index.html" "$site"
        fi
    done
}

# Écrit une page de remplacement, signalée comme telle (le build de la PWA n'est pas encore disponible).
placeholder_page() {
    local file="$1" site="$2"
    cat > "$file" <<EOF
<!doctype html>
<html lang="fr">
<head><meta charset="utf-8"><title>MedImage-Trust (${site}) : page de remplacement</title></head>
<body><p>Page de remplacement : le build de la PWA (frontend/dist/) n'était pas disponible au déploiement.</p></body>
</html>
EOF
}
