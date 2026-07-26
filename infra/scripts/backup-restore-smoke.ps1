$ErrorActionPreference = 'Stop'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$composeFile = Join-Path $repoRoot 'infra\compose.yaml'
$backupId = [guid]::NewGuid().ToString('N')
$dumpPath = Join-Path ([System.IO.Path]::GetTempPath()) "erp-backup-$backupId.sql"
$containerDumpPath = "/tmp/erp-backup-$backupId.sql"
$sourceDatabase = if ($env:ERP_DB_NAME) { $env:ERP_DB_NAME } else { 'erp' }
$restoreDatabase = 'erp_restore_smoke'
$user = if ($env:ERP_DB_USERNAME) { $env:ERP_DB_USERNAME } else { 'erp' }
$password = if ($env:ERP_DB_PASSWORD) { $env:ERP_DB_PASSWORD } else { 'erp_local' }
$rootPassword = if ($env:ERP_DB_ROOT_PASSWORD) { $env:ERP_DB_ROOT_PASSWORD } else { 'root_local' }

try {
  $ready = $false
  foreach ($attempt in 1..30) {
    & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql `
      mysql "--user=root" --batch --skip-column-names `
      -e 'SELECT 1;' *> $null
    if ($LASTEXITCODE -eq 0) {
      $ready = $true
      break
    }
    Start-Sleep -Seconds 2
  }
  if (-not $ready) {
    throw 'MySQL did not become ready within 60 seconds'
  }

  $dumpLines = & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$password" mysql `
    mysqldump "--user=$user" `
    --single-transaction --skip-lock-tables --no-tablespaces $sourceDatabase
  if ($LASTEXITCODE -ne 0) {
    throw 'database backup failed'
  }
  [System.IO.File]::WriteAllLines(
    $dumpPath,
    [string[]]$dumpLines,
    [System.Text.UTF8Encoding]::new($false))

  & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql mysql "--user=root" `
    -e "DROP DATABASE IF EXISTS $restoreDatabase; CREATE DATABASE $restoreDatabase;"
  if ($LASTEXITCODE -ne 0) {
    throw 'could not create isolated restore database'
  }

  & docker compose -f $composeFile cp $dumpPath "mysql:$containerDumpPath"
  if ($LASTEXITCODE -ne 0) {
    throw 'could not copy backup into MySQL container'
  }

  & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql `
    sh -c "mysql --user=root $restoreDatabase < $containerDumpPath"
  if ($LASTEXITCODE -ne 0) {
    throw 'database restore failed'
  }

  $tableCount = & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql `
    mysql "--user=root" --batch --skip-column-names `
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$restoreDatabase';"
  if ($LASTEXITCODE -ne 0 -or [int]$tableCount -lt 1) {
    throw 'restored database contains no tables'
  }

  $currentSchemaTableCount = & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql `
    mysql "--user=root" --batch --skip-column-names `
    -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$restoreDatabase' AND table_name IN ('md_spu', 'md_import_job', 'md_export_job', 'audit_log');"
  if ($LASTEXITCODE -ne 0 -or [int]$currentSchemaTableCount -ne 4) {
    throw "restored database is missing current product catalog tables (expected 4, found $currentSchemaTableCount)"
  }

  Write-Host "Backup/restore smoke test passed with $tableCount restored tables, including all current product catalog tables."
}
finally {
  & docker compose -f $composeFile exec -T mysql rm -f $containerDumpPath 2>$null
  & docker compose -f $composeFile exec -T -e "MYSQL_PWD=$rootPassword" mysql `
    mysql "--user=root" `
    -e "DROP DATABASE IF EXISTS $restoreDatabase;" 2>$null
  if (Test-Path -LiteralPath $dumpPath) {
    Remove-Item -LiteralPath $dumpPath -Force
  }
}
