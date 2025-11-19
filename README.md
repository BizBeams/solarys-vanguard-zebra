# Solarys Vanguard – Zebra Bridge

This repo is the dedicated home for the Java bridge that wraps Zebra's RFID Host SDK for the Solarys Vanguard desktop app. The Electron UI continues to live in `/Users/nickolaigarces/Documents/BizBeams/justlikevegas-warehouse`; the artifact produced here ships alongside that app so it can spawn Zebra operations as a child process.

## Layout

- `rfid-bridge/` – Maven module containing the bridge source, `pom.xml`, and the Zebra SDK JAR under `lib/`.
- `scripts/build-bridge.sh` – Convenience script that runs `mvn clean package` and drops `dist/rfid-bridge.jar` ready for Electron bundling.
- `dist/` – Output folder for packaged bridge artifacts; safe to clean.

## Getting the Zebra SDK

1. Download `Zebra-RFID-FXSeries-Host-Java-SDK_V1.8.msi` from the Zebra portal.
2. Extract it with `msiextract` (or run it on Windows) so you can grab `RFID/bin/Symbol.RFID.API3.jar`.
3. Copy that file into `rfid-bridge/lib/`. The repo already contains a copy sourced from `/Users/nickolaigarces/Documents/BizBeams/RFID/bin`, but the steps are here for future updates.

## Building the Bridge

```bash
cd /Users/nickolaigarces/Documents/BizBeams/solarys-vanguard-zebra
./scripts/build-bridge.sh
```

Outputs: `dist/rfid-bridge.jar` (fat jar with Zebra + Jackson dependencies) that the Electron app can bundle under `resources/`.

## Configuration

At runtime the bridge reads environment variables first, then falls back to an optional `bridge-config.json` (path overrideable via `RFID_BRIDGE_CONFIG`). The loader automatically checks both the working directory (`bridge-config.json`) and `config/bridge-config.json`, so keeping the file beside `app-config.local.json` in the Electron repo works without extra flags. This lets us keep reader secrets outside the Electron repo while still supporting per-machine overrides.

| Env / JSON field              | Description                                                                 | Default    |
|-------------------------------|-----------------------------------------------------------------------------|------------|
| `ZEBRA_READER_HOST` / `host`  | IP/hostname of the FX/XR reader                                             | _required_ |
| `ZEBRA_READER_PORT` / `port`  | Reader management port                                                      | `5084`     |
| `ZEBRA_READER_USERNAME`       | Management login (set blank to skip RM login)                               | _unset_    |
| `ZEBRA_READER_PASSWORD`       | Management password                                                         | _unset_    |
| `ZEBRA_READER_FORCE_LOGIN`    | Force login even if already authenticated                                   | `true`     |
| `ZEBRA_READER_AUTO_INVENTORY` | Start inventory automatically after connecting                             | `true`     |
| `ZEBRA_READER_RECONNECT_MS`   | Delay before reconnect attempts after errors                               | `5000`     |
| `ZEBRA_READER_TIMEOUT_MS`     | Connection timeout passed to `RFIDReader#setTimeout`                       | `15000`    |
| `ZEBRA_READER_TYPE`           | `FX`, `XR`, `MC`, etc. (passed to ReaderManagement login)                  | `FX`       |
| `ZEBRA_READER_SECURE_MODE`    | `HTTP` or `HTTPS`                                                           | `HTTP`     |
| `ZEBRA_READER_ANTENNAS`       | Comma-separated short list (e.g. `1,2`)                                     | Reader cap |
| `ZEBRA_READER_TAG_BATCH`      | Max tags pulled per `eventReadNotify`                                       | `64`       |

Example `bridge-config.json`:

```json
{
  "host": "192.168.50.20",
  "port": 5084,
  "username": "admin",
  "password": "secret",
  "autoStartInventory": true,
  "antennas": [1, 2, 3, 4]
}
```

## Electron Integration (example)

```js
import { spawn } from 'node:child_process';
import path from 'node:path';

const resourcesDir = app.isPackaged ? process.resourcesPath : path.join(__dirname, '..', 'dist');
const bridgePath = path.join(resourcesDir, 'rfid-bridge.jar');
const javaBin = app.isPackaged
  ? path.join(process.resourcesPath, 'jre', 'bin', 'java')
  : 'java';

const child = spawn(javaBin, ['-jar', bridgePath]);
child.stdout.on('data', (chunk) => {
  const line = chunk.toString().trim();
  if (!line) return;
  const message = JSON.parse(line);
  mainWindow.webContents.send('rfid:event', message);
});

function send(command) {
  child.stdin.write(`${JSON.stringify(command)}\n`);
}

send({ id: crypto.randomUUID(), cmd: 'ping' });
```

Bundle the jar plus a lightweight JRE with `electron-builder` so macOS and Windows installs are self-contained.

## Command Contract

| Command            | Description                                                                                         | Sample Response                                                                          |
|--------------------|-----------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------|
| `ping`             | Health check; bridge echoes its current status payload                                               | `{"id":"…","ok":true,"event":"pong","data":{"readerConnected":true,…}}`                  |
| `status`           | Returns the latest status snapshot without changing bridge state                                     | `{"id":"…","ok":true,"event":"status","data":{…}}`                                       |
| `mockTag`          | Emits a simulated tag payload (hardware-free smoke test)                                             | `{"ok":true,"event":"tag","data":{"tagId":"SIM-…"}}`                                     |
| `inventory:start`  | Forces inventory start (auto-start can be disabled and rely on this manual command instead)          | `{"id":"…","ok":true,"event":"inventory","data":{"running":true}}`                       |
| `inventory:stop`   | Stops an active inventory session                                                                    | `{"id":"…","ok":true,"event":"inventory","data":{"running":false}}`                      |
| `restart`          | Disconnects and immediately attempts to reconnect/login again                                       | `{"id":"…","ok":true,"event":"restart","data":{…}}`                                      |
| `shutdown`         | Gracefully exits the Java process                                                                    | `{"ok":true,"event":"shutdown"}`                                                             |

Push events emitted on stdout keep the renderer updated without polling:

- `event: "status"` – bridge lifecycle, connection, errors, antenna metadata.
- `event: "tag"` – live RFID reads streamed from Zebra's SDK (or mock tags).
