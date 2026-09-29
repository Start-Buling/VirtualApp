# Idlefish deployment configuration

Copy `.env.example` to `.env` in the deployment directory and fill in
`IDLEFISH_SERVER_BASE_URL`. Real `.env` files are ignored by Git.

The Device Agent reads `.env` next to the JSON configuration selected by
`--config` (by default, the current working directory). PowerShell launchers
read the repository root `.env`. Set `IDLEFISH_ENV_FILE` to select a different
file. Existing process environment variables override file values; explicit
Agent CLI arguments and PowerShell parameters take precedence over both.

Supported variables:

| Variable | Purpose |
| --- | --- |
| `IDLEFISH_SERVER_BASE_URL` | Required remote collector base URL |
| `IDLEFISH_AGENT_TOKEN` | Agent authentication token |
| `IDLEFISH_DEVICE_SECRET` | Authentication secret for collection scripts |
| `PORT` | Local ingest server port |
| `INGEST_DEVICE_SECRET` | Local ingest server authentication secret |

Use one `KEY=value` entry per line. Blank lines and comment lines beginning
with `#` are supported, as are single or double quoted values. Variable
interpolation, multiline values and inline comments are not supported.

Start the Agent with `IdlefishDeviceAgent.exe`, or the collection scripts
without URL arguments. They report a configuration error if the remote URL
is missing or invalid. Start the local ingest server with
`server/start-idlefish-server.ps1` or `tools/start_idlefish_ingest_server.ps1`.

Android receives its upload settings at runtime from the Agent or the sync
dialog. The loopback relay and official Idlefish page URLs are infrastructure
and product URLs, not deployment server addresses. No real remote collector
address is embedded in source code or configuration examples.

Existing Git commits still contain previous configuration values. This change
does not rewrite repository history.
