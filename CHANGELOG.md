# Changelog

All notable changes to Clear Space are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.4.5] - 2026-09-29

### Security
- **Release keystore removed from the repository.** `app/clearspace-release.jks` was
  committed to a public repo. It is now untracked and `*.jks` is git-ignored, but it
  remains in git history, so **rotate the Play upload key** (Play Console → App
  integrity → Request upload key reset).
- **Security-question recovery reworked.** Answers are normalized and the recovery
  payload is stored as `v2:` + Base64(salt ‖ ciphertext) with a random per-user salt.
  Legacy (unsalted) setups are no longer accepted for recovery and do not count as a
  failed attempt; users are prompted to set their questions up again.
- **Content signatures are mandatory on fetch.** `P2PProtocol.fetchContent` now uses
  `ContentSigner.verifyStrict`, so unsigned content from a peer is rejected instead of
  accepted.
- **P2P server uses the pinned TLS context** from `SecureP2PManager.createServerSocket`
  (client certificates requested and collected for TOFU pinning).
- **Firestore rules:** content reports and blacklist report tokens are create-only
  with type and size validation, so nobody can edit or delete another user's report.

### Fixed
- Blacklist sync never worked: `ModerationAgent`'s `collectionGroup("reports")` query
  was always denied because collection-group queries need a `{path=**}` rule. Added
  one, and moved client content reports to `content_reports` so they aren't exposed by it.
- Non-ASCII text in P2P messages was decoded as Latin-1, corrupting content (hash
  mismatch). New `LineIO` reads bytes and decodes UTF-8; writers are UTF-8 too.
- The connection limiter had a check-then-act race; it is now atomic.
- The server socket read one byte per system call; input is now buffered.

### Added
- `LineIOTest` unit tests.
- `CHANGELOG.md`.

### Migration notes
- Deploy the new rules: `firebase deploy --only firestore:rules`.
- Any backend or console tooling that read the top-level `reports` collection must
  read `content_reports` instead. Old documents in `/reports` stay where they are;
  export and delete them, because the new collection-group rule makes them readable
  to signed-in users.
