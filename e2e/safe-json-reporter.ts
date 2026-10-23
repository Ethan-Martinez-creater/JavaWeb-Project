import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import type { FullResult, Reporter, TestCase, TestError, TestResult } from '@playwright/test/reporter';

const outputDirectory = process.env.PLAYWRIGHT_OUTPUT_DIR ?? 'artifacts';

interface SafeTestResult {
  title: string;
  status: TestResult['status'];
  expectedStatus: TestCase['expectedStatus'];
  durationMs: number;
  retry: number;
  errorLocations: Array<{ file: string; line: number; column: number }>;
}

export default class SafeJsonReporter implements Reporter {
  private readonly tests: SafeTestResult[] = [];
  private readonly globalErrors: Array<{ category: string; locations: SafeTestResult['errorLocations'] }> = [];

  onError(error: TestError): void {
    // Collection errors must remain visible without copying arbitrary messages or form values.
    const category = /First argument must use/.test(error.message ?? '') ? 'FIXTURE_DECLARATION'
      : /Cannot find module|ERR_MODULE_NOT_FOUND/.test(error.message ?? '') ? 'MODULE_IMPORT'
      : /No tests found/.test(error.message ?? '') ? 'NO_TESTS' : 'GLOBAL_ERROR';
    this.globalErrors.push({ category, locations: error.location
      ? [{ file: path.basename(error.location.file), line: error.location.line, column: error.location.column }] : [] });
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    this.tests.push({
      title: test.title,
      status: result.status,
      expectedStatus: test.expectedStatus,
      durationMs: result.duration,
      retry: result.retry,
      errorLocations: result.errors.flatMap((error) =>
        error.location
          ? [{ file: path.basename(error.location.file), line: error.location.line, column: error.location.column }]
          : [],
      ),
    });
  }

  async onEnd(result: FullResult): Promise<void> {
    await mkdir(outputDirectory, { recursive: true });
    const report = {
      schemaVersion: 1,
      status: result.status,
      passed: this.tests.filter((test) => test.status === 'passed').length,
      failed: this.tests.filter((test) => test.status === 'failed' || test.status === 'timedOut').length,
      skipped: this.tests.filter((test) => test.status === 'skipped').length,
      interrupted: this.tests.filter((test) => test.status === 'interrupted').length,
      globalErrors: this.globalErrors,
      tests: this.tests,
    };
    await writeFile(path.join(outputDirectory, 'playwright-report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  }
}
