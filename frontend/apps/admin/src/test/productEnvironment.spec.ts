import { describe, expect, it, vi } from 'vitest'

describe('product E2E environment', () => {
  it('waits until an authenticated MySQL query succeeds', async () => {
    const environment = await import('../../e2e/support/productEnvironment') as {
      waitForAuthenticatedMysql?: (
        execute: (arguments_: string[], stdio?: 'ignore' | 'inherit') => unknown,
        wait: (milliseconds: number) => Promise<void>,
        timeoutMs: number,
      ) => Promise<void>
    }

    expect(environment.waitForAuthenticatedMysql).toBeTypeOf('function')

    const commands: string[][] = []
    let attempts = 0
    const execute = (arguments_: string[]) => {
      commands.push(arguments_)
      attempts += 1
      if (attempts === 1) {
        throw new Error('MySQL authentication is not ready')
      }
    }
    const wait = vi.fn(async () => undefined)

    await environment.waitForAuthenticatedMysql!(execute, wait, 1_000)

    expect(commands).toHaveLength(2)
    expect(commands.every((command) => command.includes('SELECT 1'))).toBe(true)
    expect(commands.every((command) => command.includes('MYSQL_PWD=root_local'))).toBe(true)
    expect(wait).toHaveBeenCalledOnce()
    expect(wait).toHaveBeenCalledWith(500)
  })
})
