# Security

## Reporting a vulnerability

Report it privately through GitHub's
[private vulnerability reporting](https://github.com/ensera-ai/taisce-java/security/advisories/new)
for this repository, not in a public issue.

## How a release is published, and what that protects

Every artifact under `ai.ensera.taisce` on Maven Central comes from `.github/workflows/release.yml`,
run on a version tag. The workflow has two jobs:

- **build** runs Maven with no secret available. It stages every file, then records a build
  provenance attestation for each jar and pom.
- **publish** runs in the `maven-central` environment, the only place the signing subkey and the
  Central token exist. The environment admits only `v*.*.*` tags and waits for a maintainer's
  approval. The job runs no Maven and no third-party code: it signs the staged files, uploads them
  and waits for Central to publish them.

Release tags cannot be moved or deleted, and every action the workflows use is referenced by commit
SHA.

**What this protects.** Someone who can push a branch cannot read the signing subkey or the token.
Neither can a Maven plugin, or a dependency of one, that runs during the build.

**What it does not protect.**
- A compromised build step produces a malicious jar that is attested and signed like a real one. A
  signature and an attestation say who published a file and where it was built, not that it is safe.
- Approval is by the only maintainer. It stops accidents and automation, not someone holding that
  maintainer's GitHub credentials.
- Central requires PGP signatures and issues no short-lived publishing credential, so a signing
  subkey and a token are stored. If they are stolen, forged signatures verify until the subkey is
  revoked and the revocation reaches the keyservers.

## Verifying a release yourself

Each check below relies on something other than this project's word.

**Provenance.** This shows the file was built by this repository's release workflow from a tag:

```bash
gh attestation verify taisce-client-X.Y.Z.jar \
  --repo ensera-ai/taisce-java \
  --signer-workflow ensera-ai/taisce-java/.github/workflows/release.yml \
  --source-ref refs/tags/vX.Y.Z
```

This check is anchored in GitHub's OIDC identity and a public transparency log, not in any key this
project holds. A thief with the signing subkey cannot produce it.

**Signature.** This shows the file was signed by the release key:

```bash
gpg --keyserver keyserver.ubuntu.com --recv-keys RELEASE_KEY_FINGERPRINT
gpg --verify taisce-client-X.Y.Z.jar.asc taisce-client-X.Y.Z.jar
```

The release key's primary fingerprint is `RELEASE_KEY_FINGERPRINT`. Signing is done by a subkey
that expires and is rotated under that same primary key, so pin the primary fingerprint.

Version 0.1.1 predates this key. It was signed by
`8FFF8A86A32EDDB5DB6D5E281A4045EEF61DDA87`, which is retired and revoked as superseded, not as
compromised. Its provenance attestation verifies with the command above.
