const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const publish = require('../publish_findings.cjs');

const head = 'a'.repeat(40), base = 'b'.repeat(40);
function report(overrides = {}) {
  return { version: 1, pr_number: 7, head_sha: head, base_sha: base, status: 'complete',
    changed_java_files: 1, total_findings: 1, findings: [{ tool: 'Semgrep', path: 'src/A.java',
      line: 3, end_line: 3, severity: 'ERROR', rule: 'unsafe', message: 'Unsafe operation' }], ...overrides };
}

async function fixture(t, data = report(), overrides = {}) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'pr-publisher-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const reportPath = path.join(directory, 'report.json');
  fs.writeFileSync(reportPath, JSON.stringify(data));
  const writes = [];
  const issues = overrides.issues || [];
  const pr = { state: 'open', head: { sha: head, repo: { id: 2 } }, base: { sha: base, repo: { id: 1 } }, ...overrides.pr };
  let reads = 0;
  const github = { rest: { pulls: {
    get: async () => ({ data: ++reads > 1 && overrides.latest ? overrides.latest : pr }),
    listFiles: 'files',
  }, issues: {
    listForRepo: 'issues',
    create: async input => writes.push({ action: 'create', ...input }),
    update: async input => writes.push({ action: 'update', ...input }),
  } }, paginate: async method => method === 'files' ? [{ filename: 'src/A.java', status: 'modified',
    patch: '@@ -1,3 +1,4 @@\n class A {\n int existing;\n+ dangerous();\n }' }] : issues };
  const context = { eventName: 'pull_request_target', runId: 123, repo: { owner: 'owner', repo: 'repo' },
    payload: { repository: { id: 1 }, pull_request: { number: 7, head: { sha: head, repo: { id: 2 } },
      base: { sha: base, repo: { id: 1 }, ref: 'main' } } } };
  const execute = () => publish({ github, context, core: { info() {}, warning() {} }, reportPath });
  return { execute, writes, context };
}

test('new findings create one tracking issue with an immutable source link', async t => {
  const fixtureData = await fixture(t);
  await fixtureData.execute();
  assert.equal(fixtureData.writes[0].action, 'create');
  assert.match(fixtureData.writes[0].body, /<!-- pr-security-quality:7 -->/);
  assert.ok(fixtureData.writes[0].body.includes(`/blob/${head}/src/A.java#L3`));
});

test('reruns update the bot-owned issue instead of creating duplicates', async t => {
  const f = await fixture(t, report(), { issues: [{ number: 9, user: { login: 'github-actions[bot]' },
    body: '<!-- pr-security-quality:7 -->\nold findings' }] });
  await f.execute();
  assert.equal(f.writes[0].action, 'update');
  assert.equal(f.writes[0].issue_number, 9);
  assert.equal(f.writes[0].state, 'open');
});

test('a completed clean scan closes the existing tracking issue', async t => {
  const f = await fixture(t, report({ findings: [], total_findings: 0 }), { issues: [{
    number: 9, user: { login: 'github-actions[bot]' }, body: '<!-- pr-security-quality:7 -->',
  }] });
  await f.execute();
  assert.equal(f.writes[0].state, 'closed');
  assert.equal(f.writes[0].state_reason, 'completed');
});

test('clean scans without an issue do not create one', async t => {
  const f = await fixture(t, report({ findings: [], total_findings: 0 }));
  await f.execute();
  assert.deepEqual(f.writes, []);
});

test('incomplete scans leave issues unchanged', async t => {
  const f = await fixture(t, report({ status: 'error', findings: [], total_findings: 0 }));
  await f.execute();
  assert.deepEqual(f.writes, []);
});

test('stale head, advanced base, closed PR, and mismatched source repository cannot publish', async t => {
  for (const pr of [{ head: { sha: 'c'.repeat(40), repo: { id: 2 } } },
    { base: { sha: 'c'.repeat(40), repo: { id: 1 } } }, { state: 'closed' },
    { head: { sha: head, repo: { id: 99 } } }]) {
    const f = await fixture(t, report(), { pr });
    await f.execute();
    assert.deepEqual(f.writes, []);
  }
});

test('a head change during publication also prevents writes', async t => {
  const f = await fixture(t, report(), { latest: { state: 'open', head: { sha: 'c'.repeat(40) }, base: { sha: base } } });
  await f.execute();
  assert.deepEqual(f.writes, []);
});

test('invalid or out-of-PR findings are rejected before any issue write', async t => {
  for (const item of [{ path: '../../secret.java' }, { path: 'src/Other.java' }, { line: -1 },
    { line: 1, end_line: 1 }, { tool: 'shell' }]) {
    const data = report();
    Object.assign(data.findings[0], item);
    const f = await fixture(t, data);
    await assert.rejects(f.execute);
    assert.deepEqual(f.writes, []);
  }
});

test('a forged report count cannot close an issue', () => {
  assert.throws(() => publish.validateReport(report({ total_findings: 0 })));
});

test('unexpected workflow cannot publish', async t => {
  const f = await fixture(t);
  f.context.eventName = 'pull_request';
  await assert.rejects(f.execute, /Unexpected source workflow/);
  assert.deepEqual(f.writes, []);
});

test('report text cannot inject mentions, HTML or Markdown links', () => {
  const data = report();
  data.findings[0].message = '@someone <script> [click](https://example.com)\n# heading';
  const body = publish.issueBody(data, 'owner/repo', 'https://github.com/owner/repo/actions/runs/123');
  assert.ok(!body.includes('@someone'));
  assert.ok(!body.includes('<script>'));
  assert.ok(!body.includes('[click]('));
});

test('a user-authored issue with a copied marker is not modified', async t => {
  const f = await fixture(t, report(), { issues: [{ number: 9, user: { login: 'someone' },
    body: '<!-- pr-security-quality:7 -->' }] });
  await f.execute();
  assert.equal(f.writes[0].action, 'create');
});

test('diff verification counts contextual lines and deletion hunks correctly', () => {
  const diff = '@@ -1,4 +1,4 @@\n class A {\n-old();\n+newCall();\n unchanged();\n }\n@@ -9,1 +9,0 @@\n-deleted();';
  assert.equal(publish.overlapsAddedLines(diff, 2, 2), true);
  assert.equal(publish.overlapsAddedLines(diff, 3, 3), false);
  assert.equal(publish.overlapsAddedLines(diff, 9, 9), false);
  assert.equal(publish.overlapsAddedLines(undefined, 1, 100), false);
  assert.equal(publish.overlapsAddedLines('@@ -1 +1,2 @@\n old();\n+++i;', 2, 2), true);
});
