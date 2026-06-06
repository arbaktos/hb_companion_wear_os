#!/usr/bin/env bash
#
# One-shot: generate the self-signed TLS cert that uvicorn terminates and the
# client apps pin. 10-year validity; SAN carries both the nip.io hostname
# (derived from the IP) and the raw IP (fallback if nip.io's resolver is ever
# down).
#
# Run as the babysvc user:  bash gen-cert.sh <vm-ip> [out-dir]
# Re-run only if the VM's IP changes — and then rebuild the watch/phone apps
# with the new baby-svc.crt.
set -euo pipefail

IP="${1:?usage: gen-cert.sh <vm-ip> [out-dir]}"
DIR="${2:-$HOME/certs}"
NIP_HOST="${IP//./-}.nip.io"

mkdir -p "$DIR"
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
  -keyout "$DIR/baby-svc.key" -out "$DIR/baby-svc.crt" \
  -subj "/CN=baby-svc" \
  -addext "subjectAltName=DNS:${NIP_HOST},IP:${IP}"
chmod 600 "$DIR/baby-svc.key"

echo "cert:  $DIR/baby-svc.crt   (public — bake into clients)"
echo "key:   $DIR/baby-svc.key   (stays on the VM)"
echo
echo "smoke test once the service is up:"
echo "  curl --cacert baby-svc.crt https://${NIP_HOST}:8001/healthz"
