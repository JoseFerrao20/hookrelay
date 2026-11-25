# 0005. Signing and security

## Status

Accepted

## Date

2026-11-25

## Context

Until E3, hookrelay sends unsigned webhooks, stores endpoint secrets in plain
text, accepts any endpoint URL and has an open API. The threats we address:

- **Forged or replayed webhooks.** An attacker who knows a receiver URL can post
  fake events, or resend a captured genuine request.
- **Leaked database dump.** Plain-text secrets would let the attacker forge
  signatures for every endpoint.
- **SSRF.** A user can register an endpoint URL that points at internal
  services or the cloud metadata address (`169.254.169.254`), turning hookrelay
  into a proxy into the private network.
- **Unauthenticated API use.** Anyone who reaches the API can read events or
  create endpoints.

## Decision

**Signature scheme.** Each webhook carries
`Hookrelay-Signature: v1=<sig>`, where `sig` is standard padded Base64 of
`HMAC-SHA256(key, "<deliveryId>.<timestamp>.<body>")`. The key is the UTF-8
bytes of the full secret string, including the `whsec_` prefix. The body is the
exact byte array sent, serialized once. The delivery id and timestamp are part of
the signed content so that an attacker cannot move a valid body under another
delivery id or timestamp, and so that receivers can reject old requests (a
5-minute tolerance) and deduplicate by id. Without the timestamp in the MAC, the
tolerance check would be meaningless. The `v1=` prefix leaves room for new
schemes. `WebhookSignature` offers `sign` and a constant-time `verify`.

**Rotation with dual signatures.** `POST /api/v1/endpoints/{id}/secret/rotate`
creates a new secret and keeps the old one as `previous_secret` until
`now + graceSeconds` (default 24 h, `0` clears it at once). While it is valid,
the header holds two space-separated entries, `v1=<new> v1=<old>`. Receivers
accept any matching entry, so they can switch secrets without downtime.

**Encryption at rest.** `secret` and `previous_secret` are stored as
`v1:<base64(nonce12 || ciphertext || tag16)>` with AES-256-GCM, a random nonce
per value, and the endpoint id as AAD, so a ciphertext cannot be copied to
another row. The 32-byte key comes from
`hookrelay.security.secret-encryption-key` (Base64); the app refuses to start if
it is missing or malformed. The key is never in `application.yml`, and a database
dump alone is not enough. A startup runner encrypts rows from E1 to E3
idempotently. The `v1:` prefix leaves room for key rotation, which is out of scope.

**SSRF protection, twice.** On save, we reject URLs whose literal IP or resolved
addresses fall in loopback, private, link-local, CGNAT, multicast or unspecified
ranges (including IPv4-mapped IPv6) and `localhost` names. A check on save alone
is not enough, because DNS can answer differently later (DNS rebinding) and a
hostname may not resolve yet. So at send time a custom `DnsResolver` resolves
the host and throws if any address is blocked, and the connection uses only the
addresses it checked. The JDK `HttpClient` offers no such hook, so `WebhookSender`
moved to Apache HttpClient 5, which lets us plug in the resolver while keeping
timeouts and no redirects. A blocked send makes the delivery `DEAD` at once, with
no retries. `allow-private-networks` and `allowed-hosts` exist for local
development (the compose sink is `webhook-sink`).

**API keys from configuration.** The API requires `Authorization: Bearer <key>`.
Keys (at least 32 characters) come from `hookrelay.security.api-keys`, are held as
SHA-256 digests and compared with `MessageDigest.isEqual`. Health, info and the
OpenAPI and Swagger UI paths stay public. Failures return `401` with
`WWW-Authenticate: Bearer` and a Problem Details body that never echoes the key.

## Alternatives

- **Sign only the body** (as some providers do). Simpler, but allows replay and
  re-labelling. Rejected.
- **Hex instead of Base64, or asymmetric signatures.** Hex is longer for no gain.
  Asymmetric keys let receivers verify without a shared secret, but add key
  management and slower verification; HMAC is the common webhook choice.
- **Hard cut-over on rotation.** Simple, but receivers would drop events between
  deploying the new secret and the first webhook. Dual signatures avoid that.
- **Hash the secrets instead of encrypting.** Impossible: we need the plaintext
  to sign.
- **Pre-flight DNS check only, or a proxy/egress firewall.** A check on save is
  bypassable by rebinding. An egress firewall is good defence in depth, but it
  depends on the deployment; we may add it in E7.
- **OAuth2/JWT or database-managed API keys.** More capable, but heavier than
  this stage needs.

## Consequences

- Receivers can verify origin and integrity, and reject replays; they must
  keep their clocks roughly in sync (5 minutes) and verify over the raw bytes.
- A leaked database dump no longer exposes usable secrets, but losing or
  changing the encryption key makes stored secrets unreadable. Key rotation needs
  a follow-up.
- Switching the HTTP client touches error texts and tests, and adds a dependency.
- The SSRF resolver costs one extra DNS lookup per connection, and hosts in
  `allowed-hosts` skip the check, so keep that list short.
- API keys have no scopes, and revoking one needs a redeploy. Database-managed
  keys, scopes and a key management API are follow-ups (E6/E7), as are rate
  limiting and payload size limits (E7).
