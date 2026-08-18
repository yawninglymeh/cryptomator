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

For a directory, `ciphertextPath` identifies only that directory's encrypted content container. Cryptomator stores the contents of nested directories at separate encrypted locations. A caller implementing a recursive folder action must therefore enumerate the cleartext descendants and resolve each of them separately. Recursive traversal is intentionally outside the scope of this proof of concept.

## Prototype limitations

This configuration is deliberately manual. A production-ready version would need an explicit pairing flow, secret storage, discovery, lifecycle/version negotiation, and platform-specific integration testing before it is enabled for end users.
