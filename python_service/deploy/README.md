# baby-svc — VM deploy

Runs the Huckleberry sleep API on the shared VM (<vm-ip>, same box as
belgrade_flat_notifier's webhook). Address: `https://<vm-ip-dashed>.nip.io:8001`,
TLS via self-signed cert pinned by the clients, auth via static bearer token.

Belgrade owns `127.0.0.1:8000` and its own quick tunnel — nothing here touches
either. Same deploy pattern: unprivileged user, `/etc/<name>.env`, systemd.

---

## 0. Prereqs (as root, once)

```bash
adduser --disabled-password --gecos "" babysvc
apt-get update && apt-get install -y git curl

# open the service port (everything else stays closed)
ufw allow 8001/tcp
```

## 1. Code + venv (as `babysvc`, no sudo)

The library needs Python 3.14+, which Ubuntu doesn't ship — use uv, which
downloads it on demand.

```bash
su - babysvc

# uv (installs to ~/.local/bin)
curl -LsSf https://astral.sh/uv/install.sh | sh
export PATH="$HOME/.local/bin:$PATH"

git clone https://github.com/arbaktos/hb_companion_wear_os.git ~/huckleberry_companion
cd ~/huckleberry_companion/python_service
uv venv --python 3.14
uv pip install -e .

# TLS cert (10-year, hostname + IP in SAN) → ~/certs/
bash deploy/gen-cert.sh

exit   # back to root for §2–§3
```

Copy `~babysvc/certs/baby-svc.crt` off the VM — the watch/phone apps bake it
in as their pinned trust anchor. The `.key` never leaves the VM.

## 2. Secrets file (as root)

```bash
TOKEN=$(openssl rand -hex 32)

cat >/etc/baby-svc.env <<EOF
HB_EMAIL=<huckleberry account email>
HB_PASSWORD=<huckleberry account password>
HB_TIMEZONE=Europe/Belgrade
API_BEARER_TOKEN=${TOKEN}
EOF
chown babysvc:babysvc /etc/baby-svc.env
chmod 600 /etc/baby-svc.env
echo "bearer token (goes into the client apps' build config): ${TOKEN}"
```

Back up this file's contents to a password manager — it's the only copy of
the Huckleberry creds + token pair.

## 3. Install + start the service (as root)

```bash
cp /home/babysvc/huckleberry_companion/python_service/deploy/baby-svc.service \
   /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now baby-svc
journalctl -u baby-svc -f
```

## 4. Smoke test (from anywhere — your laptop, not the VM)

```bash
curl --cacert baby-svc.crt https://<vm-ip-dashed>.nip.io:8001/healthz
# {"ok":true}

curl --cacert baby-svc.crt -H "Authorization: Bearer <token>" \
  https://<vm-ip-dashed>.nip.io:8001/status
# {"state":"awake","session_started_at":null,...}  ← sane numbers from the real account
```

Then the full loop: `POST /sleep/start` → `/status` shows `sleeping` →
check the Huckleberry phone app shows the running timer → `POST /sleep/stop`.

## Updating the code

```bash
su - babysvc
cd ~/huckleberry_companion && git pull
cd python_service && uv pip install -e .
exit
systemctl restart baby-svc
```

## If the VM's IP ever changes

The nip.io hostname and the cert SAN both encode <vm-ip>. New IP means:
update `gen-cert.sh` + `ARCHITECTURE.md`, re-run the cert script, restart the
service, rebuild clients with the new hostname + cert.
