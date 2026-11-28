#!/usr/bin/env bash
# Télécharge le corpus d'images réelles (fantômes et patientes virtuelles, aucune donnée de patient) depuis l'API
# publique NBIA v1 de TCIA, dans le dossier $MEDIMAGE_CORPUS_DIR, hors du dépôt. Aucun fichier .dcm n'est versionné :
# le dépôt ne contient que le manifeste docs/CORPUS.md (tableau des fichiers et de leurs SHA-256) et ce script.
#
# Usage :
#   MEDIMAGE_CORPUS_DIR=/chemin scripts/corpus/fetch-corpus.sh              # télécharge et vérifie (échec fermé)
#   MEDIMAGE_CORPUS_DIR=/chemin scripts/corpus/fetch-corpus.sh --allow-new  # premier téléchargement : SHA-256 calculé
#                                                                           # pour les lignes « à calculer » et affiché
#   scripts/corpus/fetch-corpus.sh --select <SeriesInstanceUID>             # coupes à 20, 35, 65 et 80 % de la pile
#   scripts/corpus/fetch-corpus.sh --doi <SeriesInstanceUID>                # collection et DOI de la série
#
# Règles : toute valeur lue dans le manifeste ou renvoyée par l'API est validée par une expression stricte avant
# usage (UID : chiffres et points ; nom de fichier : [a-z0-9-]+.dcm ; SHA-256 : 64 hexadécimaux ; collection :
# lettres, chiffres, espaces, tirets) ; les paramètres de requête sont encodés ; un fichier dont la somme diffère est
# supprimé et le script échoue. Rien n'est jamais exécuté à partir d'un fichier téléchargé.
set -euo pipefail

readonly API="https://services.cancerimagingarchive.net/nbia-api/services/v1"
readonly MAX_BYTES=$((80 * 1024 * 1024))
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT
readonly MANIFEST="${REPO_ROOT}/docs/CORPUS.md"
readonly PENDING="à calculer"

die() {
    echo "ERREUR : $*" >&2
    exit 1
}

# Encodage URL (RFC 3986) d'un paramètre de requête, caractère par caractère.
urlencode() {
    local s="$1" out="" c i
    for ((i = 0; i < ${#s}; i++)); do
        c="${s:i:1}"
        case "$c" in
            [a-zA-Z0-9.~_-]) out+="$c" ;;
            *) out+="$(printf '%%%02X' "'$c")" ;;
        esac
    done
    printf '%s' "$out"
}

valid_uid() { [[ "$1" =~ ^[0-9]+(\.[0-9]+)*$ && ${#1} -le 64 ]]; }
valid_file() { [[ "$1" =~ ^[a-z0-9-]+\.dcm$ ]]; }
valid_sha() { [[ "$1" =~ ^[0-9a-f]{64}$ ]]; }
valid_collection() { [[ "$1" =~ ^[A-Za-z0-9][A-Za-z0-9\ _-]*$ && ${#1} -le 64 ]]; }

# Requête GET vers l'API publique, HTTPS seulement, taille et durée bornées.
fetch() {
    local url="$1" out="$2"
    curl --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --tlsv1.2 \
        --retry 4 --retry-delay 5 --connect-timeout 20 --max-time 600 --max-filesize "$MAX_BYTES" \
        --output "$out" "$url"
}

trim() {
    local s="$1"
    s="${s#"${s%%[![:space:]]*}"}"
    s="${s%"${s##*[![:space:]]}"}"
    printf '%s' "$s"
}

# Lignes du manifeste : | fichier | collection | fabricant | série | instance | licence | SHA-256 |
read_manifest() {
    [[ -f "$MANIFEST" ]] || die "manifeste absent : docs/CORPUS.md"
    local line file collection series sop sha
    local -a cols
    while IFS= read -r line; do
        [[ "$line" =~ ^\|\ [a-z0-9-]+\.dcm\ \| ]] || continue
        IFS='|' read -r -a cols <<<"$line"
        [[ ${#cols[@]} -ge 8 ]] || die "ligne de manifeste invalide"
        file="$(trim "${cols[1]}")"
        collection="$(trim "${cols[2]}")"
        series="$(trim "${cols[4]}")"
        sop="$(trim "${cols[5]}")"
        sha="$(trim "${cols[7]}")"
        valid_file "$file" || die "nom de fichier invalide dans le manifeste"
        valid_collection "$collection" || die "collection invalide dans le manifeste"
        valid_uid "$series" || die "SeriesInstanceUID invalide dans le manifeste"
        valid_uid "$sop" || die "SOPInstanceUID invalide dans le manifeste"
        [[ "$sha" == "$PENDING" ]] || valid_sha "$sha" || die "SHA-256 invalide dans le manifeste"
        printf '%s\t%s\t%s\t%s\t%s\n' "$file" "$collection" "$series" "$sop" "$sha"
    done <"$MANIFEST"
}

# Chaque série du manifeste doit appartenir à la collection déclarée (getSeries?Collection=…).
check_collections() {
    local entries="$1" tmp collection series
    tmp="$(mktemp)"
    while IFS=$'\t' read -r collection series; do
        fetch "${API}/getSeries?Collection=$(urlencode "$collection")" "$tmp"
        grep -qF "\"SeriesInstanceUID\":\"${series}\"" "$tmp" ||
            { rm -f "$tmp"; die "une série du manifeste n'appartient pas à la collection déclarée"; }
    done < <(cut -f2,3 <<<"$entries" | sort -u)
    rm -f "$tmp"
}

select_slices() {
    local series="$1" tmp n p idx
    valid_uid "$series" || die "SeriesInstanceUID invalide"
    tmp="$(mktemp)"
    fetch "${API}/getSOPInstanceUIDs?SeriesInstanceUID=$(urlencode "$series")" "$tmp"
    # UID validés un par un, puis triés sur leur dernier composant (complété à gauche par des espaces : ordre
    # numérique exact, même au-delà de 20 chiffres) ; c'est le numéro d'instance chez la plupart
    # des fabricants : heuristique, la position réelle est vérifiée après lecture par les tests de corpus).
    local -a uids
    mapfile -t uids < <(grep -oE '"SOPInstanceUID":"[0-9.]{1,64}"' "$tmp" | cut -d'"' -f4 |
        awk -F. '{ printf "%64s\t%s\n", $NF, $0 }' | LC_ALL=C sort | cut -f2)
    rm -f "$tmp"
    n=${#uids[@]}
    [[ $n -ge 5 ]] || die "série trop courte"
    for p in 20 35 65 80; do
        idx=$(((n - 1) * p / 100))
        valid_uid "${uids[$idx]}" || die "UID invalide renvoyé par l'API"
        printf '%s %%\t%d/%d\t%s\n' "$p" "$((idx + 1))" "$n" "${uids[$idx]}"
    done
}

# Collection et DOI de description d'une série (getSeriesMetaData), pour compléter docs/CORPUS.md.
series_doi() {
    local series="$1" tmp collection doi
    valid_uid "$series" || die "SeriesInstanceUID invalide"
    tmp="$(mktemp)"
    fetch "${API}/getSeriesMetaData?SeriesInstanceUID=$(urlencode "$series")" "$tmp"
    # JSON : les « / » sont échappés en « \/ ».
    collection="$(grep -oE '"Collection":"[A-Za-z0-9 _-]{1,64}"' "$tmp" | head -n1 | cut -d'"' -f4 || true)"
    doi="$(sed 's#\\/#/#g' "$tmp" |
        grep -oE '"Data Description URI":"https://doi\.org/10\.[0-9]{4,9}/[A-Za-z0-9._/-]{1,64}"' |
        head -n1 | cut -d'"' -f4 || true)"
    rm -f "$tmp"
    [[ -n "$collection" && -n "$doi" ]] || die "métadonnées absentes ou hors format"
    printf '%s\t%s\n' "$collection" "$doi"
}

main() {
    local allow_new=0
    case "${1:-}" in
        --doi)
            [[ $# -eq 2 ]] || die "usage : fetch-corpus.sh --doi <SeriesInstanceUID>"
            series_doi "$2"
            return
            ;;
        --select)
            [[ $# -eq 2 ]] || die "usage : fetch-corpus.sh --select <SeriesInstanceUID>"
            select_slices "$2"
            return
            ;;
        --allow-new) allow_new=1 ;;
        "") ;;
        *) die "option inconnue" ;;
    esac

    [[ -n "${MEDIMAGE_CORPUS_DIR:-}" ]] || die "MEDIMAGE_CORPUS_DIR non défini"
    # Chemin absolu vérifié avant toute création : rien n'est jamais écrit dans le dépôt.
    local dir parent
    if [[ -d "$MEDIMAGE_CORPUS_DIR" ]]; then
        dir="$(cd "$MEDIMAGE_CORPUS_DIR" && pwd)"
    else
        parent="$(cd "$(dirname "$MEDIMAGE_CORPUS_DIR")" && pwd)" || die "dossier parent de MEDIMAGE_CORPUS_DIR absent"
        dir="${parent%/}/$(basename "$MEDIMAGE_CORPUS_DIR")"
    fi
    case "$dir/" in
        "$REPO_ROOT"/*) die "MEDIMAGE_CORPUS_DIR doit être hors du dépôt" ;;
    esac
    mkdir -p "$dir"

    local entries
    entries="$(read_manifest)"
    [[ -n "$entries" ]] || die "manifeste vide"
    check_collections "$entries"

    local file collection series sop sha actual tmp downloaded=0 present=0 failed=0
    local -a new=()
    while IFS=$'\t' read -r file collection series sop sha; do
        if [[ -f "$dir/$file" && "$sha" != "$PENDING" ]]; then
            actual="$(sha256sum "$dir/$file" | cut -d' ' -f1)"
            if [[ "$actual" == "$sha" ]]; then
                present=$((present + 1))
                continue
            fi
            rm -f "$dir/$file"
        fi
        tmp="$dir/.$file.part"
        fetch "${API}/getSingleImage?SeriesInstanceUID=$(urlencode "$series")&SOPInstanceUID=$(urlencode "$sop")" "$tmp"
        actual="$(sha256sum "$tmp" | cut -d' ' -f1)"
        if [[ "$sha" == "$PENDING" ]]; then
            new+=("$file $actual")
            if [[ $allow_new -eq 1 ]]; then
                mv -f "$tmp" "$dir/$file"
                downloaded=$((downloaded + 1))
            else
                rm -f "$tmp"
                failed=$((failed + 1))
            fi
        elif [[ "$actual" == "$sha" ]]; then
            mv -f "$tmp" "$dir/$file"
            downloaded=$((downloaded + 1))
        else
            rm -f "$tmp"
            echo "ERREUR : SHA-256 différent pour $file : fichier supprimé" >&2
            failed=$((failed + 1))
        fi
    done <<<"$entries"

    echo "corpus : $present déjà présents, $downloaded téléchargés, $failed en échec, dans $dir"
    if [[ ${#new[@]} -gt 0 ]]; then
        echo "nouveaux fichiers (SHA-256 « à calculer » dans docs/CORPUS.md) :"
        printf '  %s\n' "${new[@]}"
        [[ $allow_new -eq 1 ]] || echo "relancer avec --allow-new pour les conserver, puis reporter les sommes" >&2
    fi
    [[ $failed -eq 0 ]] || exit 1
}

main "$@"
