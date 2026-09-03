# NOVA Stream
NOVA Stream is a Self Hosted Streaming Service for the NOVA Codec.
<br>
The Codec: https://github.com/AngelGonePro/nova-next-generation-optimized-versatile-audio-codec

https://raw.githubusercontent.com/AngelGonePro/nova-stream/refs/heads/main/nova-server.zip

Updating from github:

```
cd ~/nova-server && curl -fsSL https://raw.githubusercontent.com/AngelGonePro/nova-stream/refs/heads/main/nova-server.zip -o /tmp/nova-update.zip && rm -rf /tmp/nova-update-extract && mkdir -p /tmp/nova-update-extract && unzip -oq /tmp/nova-update.zip -d /tmp/nova-update-extract && (command -v rsync >/dev/null || apt-get install -y -qq rsync) && rsync -a --delete --exclude='.env' --exclude='node_modules' --exclude='data' /tmp/nova-update-extract/nova-server/ ./ && docker compose up -d --build && rm -rf /tmp/nova-update.zip /tmp/nova-update-extract && echo "Update complete"
```

Installing:

```
mkdir nova-server && \
curl -L -o /tmp/nova-server.zip https://raw.githubusercontent.com/AngelGonePro/nova-stream/refs/heads/main/nova-server.zip && \
python3 - << 'EOF'
import zipfile, os
zip_path = "/tmp/nova-server.zip"
extract_to = "nova-server"

with zipfile.ZipFile(zip_path) as z:
    for member in z.namelist():
        parts = member.split("/", 1)
        if len(parts) > 1:
            target = os.path.join(extract_to, parts[1])
            if not member.endswith("/"):
                os.makedirs(os.path.dirname(target), exist_ok=True)
                with open(target, "wb") as f:
                    f.write(z.read(member))
EOF
rm /tmp/nova-server.zip
```

```
# Copy and edit .env
cp ~/nova-server/.env.example ~/nova-server/.env
nano ~/nova-server/.env
```

```
docker compose up -d --build
```
