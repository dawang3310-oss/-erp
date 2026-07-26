import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { createServer, type Server } from 'node:http'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { e2ePublicJwk } from './e2eAuth'

const currentDirectory = path.dirname(fileURLToPath(import.meta.url))
const repositoryRoot = path.resolve(currentDirectory, '../../../../..')
const mavenCommand = 'mvn'
const useCommandShell = process.platform === 'win32'
const composeFile = path.join(repositoryRoot, 'infra', 'compose.yaml')
const databaseName = `erp_product_e2e_${process.pid}`
const productBucket = 'erp-products-e2e'

function dockerCompose(arguments_: string[], stdio: 'ignore' | 'inherit' = 'inherit') {
  return execFileSync('docker', ['compose', '-f', composeFile, ...arguments_], {
    cwd: repositoryRoot,
    stdio,
  })
}

async function prepareDatabase() {
  const deadline = Date.now() + 60_000
  while (Date.now() < deadline) {
    try {
      dockerCompose(
        ['exec', '-T', 'mysql', 'mysqladmin', 'ping', '-uroot', '-proot_local', '--silent'],
        'ignore',
      )
      break
    } catch {
      await new Promise((resolve) => setTimeout(resolve, 500))
    }
  }
  if (Date.now() >= deadline) {
    throw new Error('Local E2E MySQL did not become ready')
  }
  dockerCompose([
    'exec',
    '-T',
    'mysql',
    'mysql',
    '-uroot',
    '-proot_local',
    '-e',
    `CREATE DATABASE \`${databaseName}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; `
      + `GRANT ALL PRIVILEGES ON \`${databaseName}\`.* TO 'erp'@'%'; FLUSH PRIVILEGES`,
  ])
}

function removeDatabase() {
  dockerCompose([
    'exec',
    '-T',
    'mysql',
    'mysql',
    '-uroot',
    '-proot_local',
    '-e',
    `DROP DATABASE IF EXISTS \`${databaseName}\``,
  ], 'ignore')
}

async function waitForBackend(process: ChildProcess, output: string[]) {
  const deadline = Date.now() + 120_000
  while (Date.now() < deadline) {
    if (process.exitCode !== null) {
      throw new Error(`ERP backend exited before becoming healthy:\n${output.slice(-40).join('')}`)
    }
    try {
      const response = await fetch('http://127.0.0.1:8080/actuator/health')
      if (response.ok) return
    } catch {
      // Backend is still starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 500))
  }
  throw new Error(`ERP backend did not become healthy:\n${output.slice(-40).join('')}`)
}

function stopProcessTree(child: ChildProcess) {
  if (!child.pid || child.exitCode !== null) return
  if (process.platform === 'win32') {
    execFileSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
  } else {
    child.kill('SIGTERM')
  }
}

export default async function startProductEnvironment() {
  try {
    const response = await fetch('http://127.0.0.1:8080/actuator/health')
    if (response.ok) {
      throw new Error('Port 8080 already has a healthy service; stop it before running product E2E')
    }
  } catch (error) {
    if (error instanceof Error && error.message.includes('already has a healthy service')) {
      throw error
    }
  }

  dockerCompose(['up', '-d', 'mysql', 'minio'])
  await prepareDatabase()

  const jwksServer: Server = createServer((request, response) => {
    if (request.url === '/.well-known/jwks.json') {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({ keys: [e2ePublicJwk] }))
      return
    }
    response.writeHead(404)
    response.end()
  })
  await new Promise<void>((resolve) => jwksServer.listen(0, '127.0.0.1', resolve))
  const address = jwksServer.address()
  if (!address || typeof address === 'string') throw new Error('Cannot bind E2E JWK server')

  let backend: ChildProcess | undefined
  try {
    execFileSync(mavenCommand, [
      '-f',
      path.join(repositoryRoot, 'backend', 'pom.xml'),
      '-pl',
      'erp-boot',
      '-am',
      '-DskipTests',
      'install',
    ], {
      cwd: repositoryRoot,
      stdio: 'inherit',
      shell: useCommandShell,
    })

    const output: string[] = []
    backend = spawn(mavenCommand, [
      '-f',
      path.join(repositoryRoot, 'backend', 'erp-boot', 'pom.xml'),
      'spring-boot:run',
    ], {
      cwd: repositoryRoot,
      env: {
      ...process.env,
        ERP_DB_URL: `jdbc:mysql://127.0.0.1:3306/${databaseName}`,
        ERP_DB_USERNAME: 'erp',
        ERP_DB_PASSWORD: 'erp_local',
        ERP_JWT_JWK_SET_URI: `http://127.0.0.1:${address.port}/.well-known/jwks.json`,
        ERP_STORAGE_ENDPOINT: 'http://127.0.0.1:9000',
        ERP_STORAGE_ACCESS_KEY: 'erp_local',
        ERP_STORAGE_SECRET_KEY: 'erp_local_secret',
        ERP_PRODUCT_BUCKET: productBucket,
      },
      shell: useCommandShell,
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe'],
    })
    backend.stdout?.on('data', (chunk) => output.push(String(chunk)))
    backend.stderr?.on('data', (chunk) => output.push(String(chunk)))
    await waitForBackend(backend, output)
  } catch (error) {
    if (backend) stopProcessTree(backend)
    await new Promise<void>((resolve) => jwksServer.close(() => resolve()))
    removeDatabase()
    throw error
  }

  return async () => {
    if (backend) stopProcessTree(backend)
    await new Promise<void>((resolve) => jwksServer.close(() => resolve()))
    removeDatabase()
  }
}
