# nova-stream
NOVA Stream is a Self Hosted Streaming Service for the NOVA Codec.

https://raw.githubusercontent.com/AngelGonePro/nova-stream/refs/heads/main/nova-server.zip

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
