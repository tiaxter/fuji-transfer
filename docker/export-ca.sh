#!/bin/bash
# Se la tua rete aziendale intercetta il TLS (errore "self-signed certificate in certificate chain"),
# salva in docker/certs/ la CA che firma i certificati visti dal tuo Mac, così il container si fida.
# Uso: docker/export-ca.sh [host]      (default: dl.google.com)
# Per provare con un file PEM già pronto invece di una connessione: CA_PROBE_FILE=chain.pem docker/export-ca.sh
set -euo pipefail
cd "$(dirname "$0")"
HOST="${1:-dl.google.com}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

if [ -n "${CA_PROBE_FILE:-}" ]; then
    cat "$CA_PROBE_FILE" > "$tmp/chain.txt"
else
    openssl s_client -showcerts -servername "$HOST" -connect "$HOST:443" < /dev/null 2> /dev/null > "$tmp/chain.txt" || true
fi

# separa i certificati della catena in c01.pem, c02.pem, ...
awk -v d="$tmp" '
    /BEGIN CERTIFICATE/ { n++; f = sprintf("%s/c%02d.pem", d, n); inside = 1 }
    inside { print > f }
    /END CERTIFICATE/ { inside = 0; close(f) }
' "$tmp/chain.txt"

count=$(ls "$tmp"/c*.pem 2> /dev/null | wc -l | tr -d ' ')
if [ "$count" -eq 0 ]; then
    echo "Nessun certificato ricevuto da $HOST: controlla la connessione." >&2
    exit 1
fi

# il primo è il certificato del sito: se è emesso da Google, la rete NON intercetta e non serve nulla
leaf_issuer="$(openssl x509 -in "$tmp/c01.pem" -noout -issuer)"
if [ -z "${CA_PROBE_FILE:-}" ] && echo "$leaf_issuer" | grep -qi "google"; then
    echo "Il certificato di $HOST è emesso da Google: nessuna intercettazione, non serve la CA."
    exit 0
fi

# preferisci la radice (soggetto == emittente); se il server non la invia usa l'ultimo certificato della catena
chosen=""
for f in "$tmp"/c*.pem; do
    subj="$(openssl x509 -in "$f" -noout -subject | sed 's/^subject= *//; s/^subject=//')"
    iss="$(openssl x509 -in "$f" -noout -issuer | sed 's/^issuer= *//; s/^issuer=//')"
    if [ "$subj" = "$iss" ]; then chosen="$f"; fi
    last="$f"
done
if [ -z "$chosen" ]; then
    chosen="$last"
    echo "Attenzione: la radice non è nella catena, uso l'ultimo certificato ricevuto." >&2
fi

mkdir -p certs
cp "$chosen" certs/corporate-ca.crt
echo "Salvata la CA in docker/certs/corporate-ca.crt:"
openssl x509 -in certs/corporate-ca.crt -noout -subject -issuer -enddate
