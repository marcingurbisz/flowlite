import fs from 'node:fs/promises';
import path from 'node:path';
import istanbulCoverage from 'istanbul-lib-coverage';
import istanbulReport from 'istanbul-lib-report';
import reports from 'istanbul-reports';

const { createCoverageMap } = istanbulCoverage;
const { createContext } = istanbulReport;

function toPosixPath(value) {
  return value.split(path.sep).join('/');
}

function normalizeSourcePath(sourcePath, repoRoot, cockpitUiDir) {
  const absolutePath = path.isAbsolute(sourcePath)
    ? sourcePath
    : path.resolve(cockpitUiDir, sourcePath);
  const relativeToRepoRoot = path.relative(repoRoot, absolutePath);
  return toPosixPath(relativeToRepoRoot);
}

function normalizeInputSourceMap(inputSourceMap, repoRoot, cockpitUiDir) {
  if (!inputSourceMap || !Array.isArray(inputSourceMap.sources)) {
    return inputSourceMap;
  }

  return {
    ...inputSourceMap,
    sources: inputSourceMap.sources.map((source) => normalizeSourcePath(source, repoRoot, cockpitUiDir)),
  };
}

function normalizeCoverageEntries(rawCoverage, repoRoot, cockpitUiDir) {
  return Object.fromEntries(
    Object.entries(rawCoverage).map(([filePath, fileCoverage]) => {
      const normalizedPath = normalizeSourcePath(fileCoverage.path ?? filePath, repoRoot, cockpitUiDir);
      return [normalizedPath, {
        ...fileCoverage,
        path: normalizedPath,
        inputSourceMap: normalizeInputSourceMap(fileCoverage.inputSourceMap, repoRoot, cockpitUiDir),
      }];
    }),
  );
}

async function main() {
  const [rawDirArg, outputDirArg] = process.argv.slice(2);
  const cockpitUiDir = process.cwd();
  const repoRoot = path.resolve(cockpitUiDir, '..');
  const rawDir = path.resolve(cockpitUiDir, rawDirArg ?? '../build/reports/playwright/frontend-coverage/raw');
  const outputDir = path.resolve(cockpitUiDir, outputDirArg ?? '../build/reports/playwright/frontend-coverage');

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
    coverageMap.merge(normalizeCoverageEntries(JSON.parse(content), repoRoot, cockpitUiDir));
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
