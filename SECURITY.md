# Security Policy

## Supported Versions

Security updates and bug fixes are actively provided for the following versions of **Maekom Mongo Lens**:

| Version | Supported          |
| ------- | ------------------ |
| `0.2.x` | :white_check_mark: Supported |
| `< 0.2.0` | :x: Unsupported  |

## Handling Sensitive Information

> **WARNING:** Never post real MongoDB / AWS DocumentDB connection URIs, passwords, API tokens, AES decryption keys, TLS/SSL CA bundles, or client private keys (`.pem`, `.p12`, `.jks`) in public GitHub issues, pull requests, or discussions. Always mask credentials (`mongodb://user:***@host:27017/db`) before sharing logs or screenshots.

## Reporting a Vulnerability

If you discover a security vulnerability in **Maekom Mongo Lens**, please **do not** open a public issue.

Instead, report it confidentially via **GitHub Private Vulnerability Reporting**:

- https://github.com/maekom-hub/maekom-mongo-lens/security/advisories/new

Please include:

1. Affected plugin version and IntelliJ IDEA version.
2. Target database engine (`MongoDB` / `AWS DocumentDB`) if relevant.
3. Clear reproduction steps or proof-of-concept (with all real credentials redacted).
4. Potential impact and any suggested mitigation.

We will acknowledge receipt promptly and coordinate a fix and disclosure timeline with you.
