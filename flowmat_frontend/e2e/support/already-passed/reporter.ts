import { appendFileSync, mkdirSync } from 'node:fs'
import { dirname, relative } from 'node:path'
import type { FullConfig, FullResult, Reporter, Suite, TestCase, TestResult } from '@playwright/test/reporter'

/**
 * Writes, for E2E_PASSED_LOG, which tests this run planned, when each ran and how it ended
 * (docs/operations/ci-selection.md). `select.mjs record` reads it with the preview server's request log to tell which
 * spec files passed and which source files each one loaded.
 */
export default class AlreadyPassedReporter implements Reporter {
  private readonly out = process.env.E2E_PASSED_LOG
  private readonly run = `${process.pid}-${Date.now()}`
  private root = process.cwd()

  onBegin(config: FullConfig, suite: Suite) {
    if (!this.out) return
    this.root = dirname(config.configFile ?? `${process.cwd()}/playwright.config.ts`)
    mkdirSync(dirname(this.out), { recursive: true })
    this.write({ type: 'begin', run: this.run, tests: suite.allTests().map((test) => ({ file: this.file(test), id: test.id })) })
  }

  onTestEnd(test: TestCase, result: TestResult) {
    if (!this.out) return
    const start = result.startTime.getTime()
    this.write({ type: 'result', run: this.run, file: this.file(test), id: test.id, status: result.status,
      expected: test.expectedStatus, retry: result.retry, start, end: start + result.duration })
  }

  onEnd(result: FullResult) {
    if (!this.out) return
    this.write({ type: 'end', run: this.run, status: result.status })
  }

  private file(test: TestCase) {
    return relative(this.root, test.location.file).split('\\').join('/')
  }

  private write(line: object) {
    appendFileSync(this.out as string, `${JSON.stringify(line)}\n`)
  }
}
