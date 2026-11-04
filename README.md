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
