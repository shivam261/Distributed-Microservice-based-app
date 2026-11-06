# DemoSpring Notes

Requires Java 21 and MongoDB. Set `MONGODB_URI` in the environment or an ignored
`.env` file, then run `./mvnw spring-boot:run`. MongoDB must allow creation of the
unique username and entry ownership indexes. Automatic index creation is enabled.

Open `http://localhost:8080/` to register or sign in, create and edit notes, search
by title or content, filter by tag, and manage trash. The app keeps credentials in
page memory. Reloading or signing out clears them from the app. Use HTTPS when
serving the app outside localhost because it sends HTTP Basic credentials with
authenticated requests.

## API

The browser page, its assets, `GET /health-check`, and registration are public.
Other routes require HTTP Basic credentials on each request.

| Method and path | Behavior |
| --- | --- |
| `POST /user` | Register with `{"username":"alice","password":"secret"}`. Returns only `id`, `username`, and server-assigned `roles`. Duplicate usernames return 409. |
| `GET /user` | Return the authenticated user's profile, containing only `id`, `username`, and `roles`. |
| `PUT /user/password` | Change the caller's password with `{"currentPassword":"secret","newPassword":"replacement"}`. An incorrect current password returns 403. |
| `DELETE /user/id` | Permanently delete the caller's active and trashed entries, then delete the account. Returns 204. |
| `GET /demo` | List the caller's active entries. Supports search, tag filtering, sorting, and pagination. |
| `POST /demo` | Create an entry with `{"title":"Example","content":"Private content","tags":["work"]}`. Only `title` is required. Returns 201 with the new entry. |
| `GET /demo/id/{entryId}` | Read an owned active entry. |
| `PATCH /demo/id/{entryId}` | Update the title, content, or tags of an owned active entry. Returns the updated entry. |
| `DELETE /demo/id/{entryId}` | Move an owned active entry to trash. Returns 204. |
| `GET /demo/trash` | List the caller's trashed entries, newest deletion first. Supports pagination. |
| `PATCH /demo/trash/{entryId}` | Restore an owned trashed entry. Returns the restored entry. |
| `DELETE /demo/trash/{entryId}` | Permanently delete an owned trashed entry. Returns 204. |

Entry operations return 404 when the entry is missing, belongs to another account,
or is in the wrong state for the requested operation. Entry responses include
`tags`, `updatedAt`, and `deletedAt`. Entries created before this change start
with an empty tag list and a missing update timestamp until their next edit or
restore. The API records `updatedAt` on create, edit, and restore, and `deletedAt`
when a note enters trash.

For a partial update, send at least one non-null field. Omitted and null fields
retain their current values. Use `{"content":""}` to clear content or
`{"tags":[]}` to clear tags. Requests can supply up to 20 nonblank tags of up to
40 characters each. The server trims, lowercases, and deduplicates tags.

### Listing entries

- `q` matches a case-insensitive substring of the title or content.
- `tag` matches one exact tag, ignoring case and surrounding whitespace.
- `sort=updated` returns the most recently updated notes first, followed by
  legacy notes without a timestamp. Equal timestamps are ordered by entry ID.
- `page` and `size` work for active and trash lists. Supplying either enables
  pagination, with a default page of `0` and default size of `20`. Sizes range
  from `1` through `100`. Paginated responses include `X-Total-Count` with the
  number of matching entries before pagination. Both modes return a JSON array.

For example, `GET /demo?q=planning&tag=work&sort=updated&page=0&size=10`
returns the first ten matching notes. A plain `GET /demo` returns all active
entries in their existing reference order. The API currently filters and pages
the caller's resolved MongoDB references in memory.

### Compatibility and account cleanup

Registration, entry creation, and entry editing reject unknown fields, including
persistence IDs, roles, and entry references, with 400. Usernames and entry titles
must be nonblank. Passwords must be nonblank and at most 72 UTF-8 bytes (the BCrypt limit).

The existing `PUT /user/id/{userId}` and `GET`/`POST /demo/{userId}` routes remain
available only when the path ID belongs to the authenticated caller. Password
changes through either route require both `currentPassword` and `newPassword`.
`GET /user` now returns one profile instead of a list of all accounts.

Account deletion marks the account as deleting before removing entries, which
blocks new entry references. If cleanup fails, repeat `DELETE /user/id` with the
same credentials to finish it. The username stays reserved and other authenticated
operations are blocked until cleanup completes. The deletion only covers entries
referenced by that account. Orphan documents left by older versions require a
separate review.

A unique partial index prevents two accounts from referencing the same MongoDB entry.
Empty entry lists are excluded from the index. If index creation fails with duplicate
keys during an upgrade, keep the API offline. Back up the database, identify shared
references with the query below and use trusted records to establish the owner.
Remove the reference from the other accounts before restarting. Where ownership
cannot be established, quarantine the entry and detach it from all affected accounts.

```javascript
db.users.aggregate([
  { $unwind: "$demoEntries" },
  { $group: { _id: "$demoEntries", owners: { $addToSet: "$_id" } } },
  { $match: { "owners.1": { $exists: true } } }
])
```

The API verifies credentials using a projection that excludes entries. Authenticated
requests use the database ID of the account that passed authentication.

## Verification

With Java 21 selected, run `./mvnw verify` (or `mvn verify`). The regression suite
exercises the real HTTP Basic security chain, controllers, and services using
detached in-memory repository fixtures; no live MongoDB connection is required.
It covers two-user isolation, registration injection and duplicates, password
verification, credential preservation after entry creation, MongoDB query and
DBRef update mapping, and the edit, search, trash, and deletion workflows.

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

The workflow uses `pull_request_target` and takes its scripts, tests and ruleset
from the base commit. It fetches the PR as Git objects and copies only changed
Java files from the merge base and PR head into an isolated two-commit repository
for comparison. Symlink and binary Java inputs fail the scan. The scan job has
read-only repository permissions.

A separate job in the same run validates the report against the triggering PR
and checks that its head and base are still current before writing the tracking
issue. Findings open or update one public issue per PR. A completed clean scan
closes it. An incomplete scan leaves the issue open. The job publishes a commit
status named **Trusted Java PR scan** on the reviewed head. Select this status in
branch protection to require the trusted scan for merges after rollout.

The JSON report is limited to 100 KB, retained for one day and contains at most
50 findings plus the total count. Scans reject more than 300 changed Java files
or an individual Java input larger than 1 MB. The trusted workflow takes effect
for subsequent PR events once it is present on the base branch.

To verify the workflow helpers locally:

```sh
python3 -m unittest discover -s .github/scripts/tests -p 'test_*.py'
node --test .github/scripts/tests/publish-findings.test.cjs
```

References: [Semgrep CLI](https://docs.semgrep.dev/cli-reference),
[PMD CLI](https://pmd.github.io/pmd/pmd_userdocs_cli_reference.html),
[GitHub workflow events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_run).
