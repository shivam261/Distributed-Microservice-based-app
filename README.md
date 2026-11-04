# Demo Spring API

Requires Java 21 and MongoDB. Set `MONGODB_URI` in the environment or an ignored
`.env` file, then run `./mvnw spring-boot:run`. MongoDB must allow creation of the
unique `users.username` index; automatic index creation is enabled.

Except for `GET /health-check` and registration, requests require HTTP Basic
authentication. Send credentials with each request; authentication is stateless.

| Method and path | Behavior |
| --- | --- |
| `POST /user` | Register with `{"username":"alice","password":"secret"}`. Returns only `id`, `username`, and server-assigned `roles`. Duplicate usernames return 409. |
| `GET /user` | Return the authenticated user's profile, containing only `id`, `username`, and `roles`. |
| `PUT /user/password` | Change the caller's password with `{"currentPassword":"secret","newPassword":"replacement"}`. An incorrect current password returns 403. |
| `DELETE /user/id` | Delete the authenticated user's account. |
| `GET /demo` | List the caller's entries. |
| `POST /demo` | Create an entry with `{"title":"Example","content":"Private content"}`. Returns the created entry with a server-generated ID. |
| `GET /demo/id/{entryId}` | Read an owned entry. Missing or unowned entries return 404. |
| `DELETE /demo/id/{entryId}` | Delete an owned entry and remove its account reference. Missing or unowned entries return 404. |

Registration and entry creation reject unknown fields, including persistence IDs,
roles, and entry references, with 400. Usernames and entry titles must be nonblank;
passwords must be nonblank and at most 72 UTF-8 bytes (the BCrypt limit).

The existing `PUT /user/id/{userId}` and `GET`/`POST /demo/{userId}` routes remain
available only when the path ID belongs to the authenticated caller. Password
changes through either route require both `currentPassword` and `newPassword`.
`GET /user` now returns one profile instead of a list of all accounts.

Ownership continues to use the account's existing MongoDB entry references, so
existing entries need no schema migration. Reference updates use atomic field
updates and never rewrite or re-encode password hashes.

## Verification

With Java 21 selected, run `./mvnw verify` (or `mvn verify`). The regression suite
exercises the real HTTP Basic security chain, controllers, and services using
detached in-memory repository fixtures; no live MongoDB connection is required.
It covers two-user isolation, registration injection and duplicates, password
verification, credential preservation after entry creation, and MongoDB query and
DBRef update mapping. It does not replace integration testing against a running
MongoDB server.

## Automatic PR security and quality checks

The **PR security and quality** GitHub Actions workflow runs when a PR targets
`main` and is opened, updated, reopened, or marked ready for review. It runs
Semgrep Community Edition 1.183.0 with the community `p/java` rules and PMD 7.29.0
with the checks in [the PMD ruleset](.github/pmd-ruleset.xml).

Only added or modified Java files are scanner targets. Findings must overlap
added or modified lines relative to the PR's merge base; unchanged files and
unchanged lines are excluded from reporting. Semgrep additionally compares
against that baseline to suppress existing findings. Scanners read complete
changed files for context. Pure deletions, non-Java files, dependency CVEs, and
general application authorization logic are not comprehensively covered. Keep
the application's security regression tests and human review.

Any reportable security or quality finding fails the scan check. The separate
**Publish PR scan findings** workflow creates one GitHub tracking issue per PR,
updates it on later scans, reopens it if findings return, and closes it after a
completed clean scan. The issue links to the exact reviewed commit. Reports for
closed PRs, superseded commits, or an advanced base branch are not published.
An incomplete scan fails visibly and never closes an existing tracking issue.

The scan has read-only repository permissions and needs no secrets. Only the
default-branch publisher has issue-write permission; it validates the report and
its source PR and never runs code from the PR or downloaded report. Findings in
GitHub issues have this repository's public visibility.

This setup uses standard Ubuntu GitHub-hosted runners and no paid scanner
features or AI APIs. There is no dependency cache. A JSON report of at most
100 KB is retained for one day to pass results between workflows; at most 50
findings are included in an issue, with the full finding count shown. This keeps
report storage small, but GitHub's account storage allowances and billing rules
still apply. PRs exceeding 300 changed Java files or a 1 MB Java file fail rather
than silently leaving files unreviewed.

Both workflows must be merged into `main` before automatic issue publishing is
active. The scan workflow can run on its setup PR, but the `workflow_run`
publisher is loaded from the default branch. Existing PRs can be scanned by
pushing a new commit or reopening them. No API keys, external accounts, or Codex
settings changes are needed.

To verify the workflow helpers locally:

```sh
python3 -m unittest discover -s .github/scripts/tests -p 'test_*.py'
node --test .github/scripts/tests/publish-findings.test.cjs
```

References: [Semgrep CLI](https://docs.semgrep.dev/cli-reference),
[PMD CLI](https://pmd.github.io/pmd/pmd_userdocs_cli_reference.html),
[GitHub workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run).
