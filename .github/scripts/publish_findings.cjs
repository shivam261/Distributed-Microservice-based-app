const fs = require('node:fs');

function validateReport(report) {
  const sha = /^[0-9a-f]{40}$/;
  if (report.version !== 1 || !Number.isSafeInteger(report.pr_number) || report.pr_number < 1 ||
      !sha.test(report.head_sha) || !sha.test(report.base_sha) ||
      !['complete', 'error'].includes(report.status) || !Array.isArray(report.findings) ||
      report.findings.length > 50 || !Number.isSafeInteger(report.total_findings) ||
      report.total_findings < report.findings.length || report.total_findings < 0 ||
      !Number.isSafeInteger(report.changed_java_files) || report.changed_java_files < 0 ||
      report.changed_java_files > 300 ||
      report.findings.length !== Math.min(50, report.total_findings)) {
    throw new Error('Invalid PR scan report');
  }
  for (const item of report.findings) {
    if (!['Semgrep', 'PMD'].includes(item.tool) || !['ERROR', 'WARNING', 'INFO'].includes(item.severity) ||
        typeof item.path !== 'string' || item.path.length > 1024 || !item.path.endsWith('.java') ||
        item.path.startsWith('/') || item.path.split('/').includes('..') || /[\x00-\x1f]/.test(item.path) ||
        !Number.isSafeInteger(item.line) || item.line < 1 || item.line > 10_000_000 ||
        !Number.isSafeInteger(item.end_line) || item.end_line < item.line || item.end_line > 10_000_000 ||
        typeof item.rule !== 'string' || !item.rule || item.rule.length > 200 ||
        typeof item.message !== 'string' || item.message.length > 600) {
      throw new Error('Invalid finding in PR scan report');
    }
  }
}

function safeText(value) {
  return value.replace(/[\x00-\x1f]/g, ' ').replace(/@/g, '@\u200b')
    .replace(/[\\`*_{}\[\]()<>#|]/g, '\\$&');
}

function overlapsAddedLines(patch, start, end) {
  if (typeof patch !== 'string') return false;
  let line = 0;
  for (const text of patch.split('\n')) {
    const hunk = text.match(/^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
    if (hunk) {
      line = Number(hunk[1]);
    } else if (text.startsWith('+')) {
      if (line >= start && line <= end) return true;
      line++;
    } else if (text.startsWith(' ')) {
      line++;
    }
  }
  return false;
}

function issueBody(report, repository, runUrl) {
  const marker = `<!-- pr-security-quality:${report.pr_number} -->`;
  const lines = [marker, `Automated security and Java quality checks for PR #${report.pr_number}.`, '',
    `Reviewed commit: \`${report.head_sha}\``, `[Scan run](${runUrl})`, '',
    'Scope: added or modified lines in changed Java files. Findings are static-analysis candidates for review.', ''];
  if (!report.total_findings) {
    lines.push('The latest completed scan found no reportable findings on changed lines.');
  }
  report.findings.forEach((item, index) => {
    const path = item.path.split('/').map(encodeURIComponent).join('/');
    const url = `https://github.com/${repository}/blob/${report.head_sha}/${path}#L${item.line}`;
    lines.push(`${index + 1}. **${item.severity} · ${item.tool} · ${safeText(item.rule)}**`,
      `   [${safeText(item.path)}:${item.line}](${url}) — ${safeText(item.message)}`, '');
  });
  if (report.total_findings > report.findings.length) {
    lines.push(`Showing ${report.findings.length} of ${report.total_findings} findings. Fix these and rerun to surface the remainder.`);
  }
  return lines.join('\n');
}

async function publish({ github, context, core, reportPath }) {
  const run = context.payload.workflow_run;
  if (run.event !== 'pull_request' || run.name !== 'PR security and quality' ||
      run.path !== '.github/workflows/pr-scan.yml' ||
      run.repository.id !== context.payload.repository.id) {
    throw new Error('Unexpected source workflow');
  }
  const stat = fs.lstatSync(reportPath);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size > 100_000) {
    throw new Error('Invalid report file');
  }
  const report = JSON.parse(fs.readFileSync(reportPath, 'utf8'));
  validateReport(report);
  if (report.status !== 'complete') {
    core.warning('Scan was incomplete. Existing issues will be left unchanged.');
    return;
  }
  const repo = context.repo;
  const { data: pr } = await github.rest.pulls.get({ ...repo, pull_number: report.pr_number });
  if (pr.state !== 'open' || report.head_sha !== run.head_sha || pr.head.sha !== report.head_sha ||
      pr.base.sha !== report.base_sha || pr.base.repo.id !== context.payload.repository.id ||
      pr.head.repo?.id !== run.head_repository?.id) {
    core.info('Skipping a stale, closed, or mismatched PR report.');
    return;
  }
  const files = await github.paginate(github.rest.pulls.listFiles, {
    ...repo, pull_number: report.pr_number, per_page: 100,
  });
  const allowed = new Map(files.filter(file => file.status !== 'removed').map(file => [file.filename, file.patch]));
  if (report.findings.some(item => !allowed.has(item.path) ||
      !overlapsAddedLines(allowed.get(item.path), item.line, item.end_line))) {
    throw new Error('Finding is outside the PR diff or its changed lines could not be verified');
  }
  const marker = `<!-- pr-security-quality:${report.pr_number} -->`;
  const issues = await github.paginate(github.rest.issues.listForRepo, {
    ...repo, state: 'all', creator: 'github-actions[bot]', per_page: 100,
  });
  const existing = issues.find(issue => !issue.pull_request && issue.user?.login === 'github-actions[bot]' &&
    issue.body?.startsWith(marker));
  if (!existing && !report.total_findings) {
    core.info('Clean scan; no tracking issue needed.');
    return;
  }
  // Re-check the head before writing, since scans and publication can run concurrently.
  const { data: latest } = await github.rest.pulls.get({ ...repo, pull_number: report.pr_number });
  if (latest.state !== 'open' || latest.head.sha !== report.head_sha || latest.base.sha !== report.base_sha) {
    core.info('PR changed during publication; skipping this report.');
    return;
  }
  const body = issueBody(report, `${repo.owner}/${repo.repo}`,
    `https://github.com/${repo.owner}/${repo.repo}/actions/runs/${run.id}`);
  const title = `[PR scan #${report.pr_number}] Security and code quality findings`;
  if (existing) {
    await github.rest.issues.update({ ...repo, issue_number: existing.number, title, body,
      state: report.total_findings ? 'open' : 'closed',
      state_reason: report.total_findings ? 'reopened' : 'completed' });
  } else {
    await github.rest.issues.create({ ...repo, title, body });
  }
}

module.exports = publish;
module.exports.validateReport = validateReport;
module.exports.issueBody = issueBody;
module.exports.overlapsAddedLines = overlapsAddedLines;
