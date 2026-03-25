import fs from 'node:fs/promises';
import path from 'node:path';
import istanbulCoverage from 'istanbul-lib-coverage';
import istanbulReport from 'istanbul-lib-report';
import reports from 'istanbul-reports';

const { createCoverageMap } = istanbulCoverage;
const { createContext } = istanbulReport;

async function main() {
  const [rawDirArg, outputDirArg] = process.argv.slice(2);
  const rawDir = path.resolve(process.cwd(), rawDirArg ?? '../build/reports/playwright/frontend-coverage/raw');
  const outputDir = path.resolve(process.cwd(), outputDirArg ?? '../build/reports/playwright/frontend-coverage');

  let entries = [];
  try {
    entries = await fs.readdir(rawDir, { withFileTypes: true });
  } catch (error) {
    if (error && typeof error === 'object' && 'code' in error && error.code === 'ENOENT') {
      console.log(`No frontend coverage raw directory found at ${rawDir}`);
      return;
    }
    throw error;
  }

  const rawFiles = entries
    .filter((entry) => entry.isFile() && entry.name.endsWith('.json'))
    .map((entry) => path.join(rawDir, entry.name))
    .sort();

  if (rawFiles.length === 0) {
    console.log(`No frontend coverage raw files found at ${rawDir}`);
    return;
  }

  const coverageMap = createCoverageMap({});
  for (const rawFile of rawFiles) {
    const content = await fs.readFile(rawFile, 'utf8');
    if (!content.trim()) {
      continue;
    }
    coverageMap.merge(JSON.parse(content));
  }

  await fs.mkdir(outputDir, { recursive: true });
  await fs.writeFile(
    path.join(outputDir, 'coverage-final.json'),
    JSON.stringify(coverageMap.toJSON(), null, 2),
    'utf8',
  );

  const context = createContext({
    dir: outputDir,
    coverageMap,
  });

  reports.create('html').execute(context);
  reports.create('lcovonly', { file: 'lcov.info' }).execute(context);
  reports.create('json-summary').execute(context);
  reports.create('text-summary').execute(context);

  console.log(`Merged ${rawFiles.length} frontend coverage snapshot(s) into ${outputDir}`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
