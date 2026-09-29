. "$PSScriptRoot/../tools/idlefish_env.ps1"
Import-IdlefishEnv
node "$PSScriptRoot/idlefish-ingest-server.js"
