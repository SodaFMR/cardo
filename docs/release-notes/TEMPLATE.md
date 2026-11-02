# Cardo X.Y.Z

Copy this file to `docs/release-notes/X.Y.Z.md` and fill it in before running `scripts/release.sh X.Y.Z --publish`.

## Highlights

One to three sentences on what matters most in this release.

## What's new

- 

## Known limitations

- 

## Verify

Check the download before installing it (see "Download & updates" in the README):

- APK SHA-256: `<paste from dist/SHA256SUMS>`
- Signing certificate SHA-256: `<paste the "certificate SHA-256 digest" printed by scripts/release.sh>`

```
sha256sum -c SHA256SUMS
apksigner verify --print-certs cardo-X.Y.Z.apk
```

The certificate fingerprint must be the same in every release. If it changes, do not install.

## Install / update

- First install: download `cardo-X.Y.Z.apk`, allow installs from your browser or file manager, open it, then choose Cardo as your Digital assistant app.
- Update: install the new APK over the old one (same signing key, your data is kept), or let [Obtainium](https://github.com/ImranR98/Obtainium) do it: add `https://github.com/SodaFMR/cardo` and it follows every new release.
