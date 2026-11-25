# Releasing

One version for the server image, the Helm chart and the Android APK. Pipeline details: `docs/DESIGN.md` ("Delivery") and the header
of `.github/workflows/release.yml`.

## One-time setup (GitHub: Settings -> Secrets and variables -> Actions)

| Name | Kind | Value |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | secret | `base64 -w0 ~/android/keys/audiobooks.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | secret | store password (`~/android/keys/audiobooks.properties`) |
| `ANDROID_KEY_ALIAS` | secret | key alias |
| `ANDROID_KEY_PASSWORD` | secret | key password |
| `ARGOCD_APPS_DEPLOY_KEY` | secret | private half of a write deploy key (below) |
| `ANDROID_CERT_SHA256` | variable (optional) | SHA-256 of the signing certificate, 64 hex digits; the `apk` job fails when the built APK is signed by another certificate. `keytool -list -v -keystore audiobooks.jks \| grep SHA256` |

```bash
base64 -w0 ~/android/keys/audiobooks.jks | gh secret set ANDROID_KEYSTORE_BASE64
```

The workflow maps these to the Gradle inputs `AUDIOBOOKS_KEYSTORE_FILE/_PASSWORD`, `AUDIOBOOKS_KEY_ALIAS/_PASSWORD` (see `app/README.md`).

**ARGOCD deploy key**: generate a key pair (`ssh-keygen -t ed25519 -N '' -f key`), add `key.pub` as a deploy key **with write access**,
titled `earmark-release`, on `AresiusXP/argocd-apps` only, and store `key` as the secret `ARGOCD_APPS_DEPLOY_KEY` here.
`register` uses it to move `targetRevision` of the `earmark` entry in `bootstrap/values.yaml` forward. If it fails,
Renovate in the cluster is the fallback.

**Back up the keystore and its passwords** (`audiobooks.jks`, `audiobooks.properties`) in a password manager or encrypted drive. The
GitHub secret cannot be read back. Without the same key a new APK cannot update an installed app: it must be uninstalled first, losing
the app's local data, and nothing can restore the old key.

## Cut a release

1. Merge conventional commits to `main`. `release-please` keeps a release PR open (changelog, `Chart.yaml`, `.release-please-manifest.json`).
2. Merge it: `gh pr merge N --squash --admin`. This creates the tag `vX.Y.Z` and the GitHub release.
3. The same workflow run then: builds and signs the APK (`apk`), builds `ghcr.io/aresiusxp/earmark:X.Y.Z` with the APK baked
   in (`images`), pushes the chart (`chart`) and bumps `argocd-apps` (`register`). ArgoCD deploys; the app's update check then offers the new APK.
4. Check the run summary: **"In-app update APK: included"**. "NOT included" means the image has no APK (see recovery).

Version components must stay within 0..99 (`versionCode = X*10000 + Y*100 + Z`); the `apk` job refuses anything else.

## Recovery

Actions -> Release -> Run workflow, `tag` = the existing tag (e.g. `v0.4.0`):

* Missing image/chart/registration: re-run with the tag. An image tag that already exists is never rebuilt.
* Image published without the APK (signing secret missing, Gradle failed, ...): fix the cause, run with the tag and **`force_images`**
  ticked. It rebuilds the APK of that tag, overwrites the image tag and re-attaches the APK to the release (`--clobber`).
* Only a failed `register`: re-run with the tag (forward-only, a no-op when already at or past that version).

The `apk` job is allowed to fail without blocking the image, chart and deploy; always check the summary of `images`.
