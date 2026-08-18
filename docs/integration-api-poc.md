# Experimental local integration API

This proof of concept exposes Cryptomator's existing cleartext-to-ciphertext path mapping to an authenticated local process. It is intended to test integrations that need to associate an item in an unlocked vault with its encrypted backing item.

The API is disabled by default. To enable it, provide both JVM system properties:

- `cryptomator.integrationApi.port`: a fixed TCP port from 1 through 65535
- `cryptomator.integrationApi.token`: a random printable ASCII bearer token containing 32 through 256 characters and no spaces

The server binds only to `127.0.0.1`. It accepts authenticated native requests, rejects requests carrying a browser `Origin` header, and does not enable CORS. The token and requested paths are not written to the log.

## Resolve paths

Send up to 256 absolute cleartext paths in one request:

```http
POST /v1/resolve HTTP/1.1
Host: 127.0.0.1:37821
Authorization: Bearer <random-token>
Content-Type: application/json

{"paths":["/path/to/unlocked-vault/Documents/notes.txt"]}
```

A successful request preserves input order:

```json
{
  "results": [
    {
      "status": "mapped",
      "vaultId": "example-vault-id",
      "ciphertextPath": "/path/to/encrypted-vault/d/ab/cdef.../example.c9r",
      "error": null
    }
  ]
}
```

Individual items can return `invalid_path`, `no_unlocked_vault`, `vault_state_changed`, or `mapping_failed`. URI-based mounts cannot currently be mapped.

This API is read-only. It does not download, evict, pin, or otherwise modify either path. A local integration can use the returned encrypted path with its own storage-provider controls.

## List unlocked vault roots

An authenticated client can discover the path-mounted unlocked vaults that are available for mapping:

```http
GET /v1/vaults HTTP/1.1
Host: 127.0.0.1:37821
Authorization: Bearer <random-token>
Accept: application/json
```

The response contains only stable vault identifiers and absolute roots; it omits display names and locked or URI-mounted vaults:

```json
{
  "vaults": [
    {
      "vaultId": "example-vault-id",
      "mountPath": "/path/to/unlocked-vault",
      "ciphertextRootPath": "/path/to/encrypted-vault"
    }
  ]
}
```

The response is limited to 256 unlocked vaults. If more are available, the endpoint returns HTTP 413 with `too_many_vaults` instead of returning a partial list. Clients should also impose a bounded response-body limit before decoding JSON.

This endpoint supplies the roots required by integrations that monitor unlocked mounts. It does not pair a client, register a filesystem monitor, or grant authority to mutate either root.

For a directory, `ciphertextPath` identifies only that directory's encrypted content container. Cryptomator stores the contents of nested directories at separate encrypted locations. A caller implementing a recursive folder action must therefore enumerate the cleartext descendants and resolve each of them separately. Recursive traversal is intentionally outside the scope of this proof of concept.

## Prototype limitations

This configuration is deliberately manual. A production-ready version would need an explicit pairing flow, secret storage, discovery, lifecycle/version negotiation, and platform-specific integration testing before it is enabled for end users.

## Security and production-hardening TODO

The proof of concept is disabled by default and binds only to IPv4 loopback. Its bearer token, host validation, browser-origin rejection, request-size limit, bounded executor, and privacy-conscious logging reduce accidental and remote exposure. They do not establish the identity of an arbitrary native process running as the same operating-system user.

The API does not expose vault passwords, encryption keys, or cleartext file contents. It does expose sensitive relationship metadata: a caller that supplies a cleartext path receives the corresponding ciphertext path and vault identifier. The current credential is global to every unlocked vault, and a fixed loopback endpoint can be occupied by another local process before the legitimate server starts. The client therefore also needs a way to authenticate the server rather than sending a reusable bearer credential to whichever process owns the configured port.

Required work before considering this a supported integration surface:

- Add explicit user-mediated pairing, with a separately generated high-entropy credential for every approved integration and a visible revoke/rotate control.
- Store long-lived credentials in platform-protected credential storage rather than JVM properties, environment variables, command-line arguments, settings files, logs, or crash diagnostics.
- Provide mutual authentication. Prefer platform IPC with peer code-identity verification where available; otherwise use a pinned, mutually authenticated cryptographic channel. Endpoint randomization is useful defense in depth but is not server authentication.
- Scope authorization to selected vaults and the minimum required operations. Do not let approval for one vault silently reveal mappings for every unlocked vault.
- Add protocol version negotiation, request nonces or equivalent replay protection, deadlines, rate limits, and bounded per-client work. Retain the existing request-body, batch, vault-list, and executor limits, and require clients to keep response bodies bounded.
- Minimize response data and validate that every result belongs to the paired encrypted vault root. Consider opaque handles if a future protocol can avoid returning raw ciphertext paths.
- Keep logs and metrics free of credentials and cleartext/ciphertext paths while recording enough privacy-safe information to detect authentication failures, saturation, and repeated mapping errors.
- Define the attacker boundary explicitly. A fully compromised unsandboxed process running as the user may already be able to read an unlocked mount, but this API should not grant equivalent mapping access to a sandboxed or otherwise restricted application.

Any future client that turns a mapping into storage-provider actions must separately authorize potentially expensive or availability-affecting operations such as recursive download, persistent local retention, unpinning, or eviction. Those mutations remain outside this mapping API.
