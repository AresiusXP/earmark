# Releasing

One version for the server image, the Helm chart and the Android APK. Pipeline details: `docs/DESIGN.md` ("Delivery") and the header
of `.github/workflows/release.yml`. The pipeline runs in your fork or copy of the repository; nothing here is needed just to build
or run Earmark.

## One-time setup (GitHub: Settings -> Secrets and variables -> Actions)

| Name | Kind | Value |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | secret | your release keystore, base64 (below) |
| `ANDROID_KEYSTORE_PASSWORD` | secret | store password |
| `ANDROID_KEY_ALIAS` | secret | key alias |
| `ANDROID_KEY_PASSWORD` | secret | key password |
| `ANDROID_CERT_SHA256` | variable (recommended) | SHA-256 of the signing certificate, 64 hex digits; the `apk` job fails when the built APK is signed by another certificate. `keytool -list -v -keystore release.jks \| grep SHA256` |

**Create a keystore** (once, keep it forever):

```bash
keytool -genkeypair -v -keystore release.jks -alias earmark -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD      # prompts; same for ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD
```

The workflow maps these to the Gradle inputs `AUDIOBOOKS_KEYSTORE_FILE/_PASSWORD`, `AUDIOBOOKS_KEY_ALIAS/_PASSWORD` (see `app/README.md`).
Never commit a keystore or its passwords (`*.jks`, `*.keystore` are git-ignored).

**Back up the keystore and its passwords** in a password manager or encrypted drive. The GitHub secret cannot be read back. Without
the same key a new APK cannot update an installed app: it must be uninstalled first, losing the app's local data, and nothing can
restore the old key.

**Signing is isolated.** Only the `apk` job sees the signing secrets and it has no write token; the `publish-apk` job writes to the
release and has no secrets. For defence in depth, consider moving the signing secrets to a GitHub *environment* restricted to the
`main` branch (and add `environment:` to the `apk` job).

Release assets (APK and `.sha256`) are attached to the GitHub release; if the repository is public they are public too. The app does
not need them: it asks your server, which has the APK baked into its image.

## Cut a release

1. Merge conventional commits to `main`. `release-please` keeps a release PR up to date (changelog, `Chart.yaml`, `.release-please-manifest.json`).
2. Merge it. This creates the tag `vX.Y.Z` and the GitHub release.
3. The same workflow run then: checks the tag (`tag-check`), builds and signs the APK (`apk`), attaches it to the release (`publish-apk`),
   builds `ghcr.io/<owner>/earmark:X.Y.Z` with the APK baked in (`images`) and pushes the chart (`chart`).
4. Check the run summary: **"In-app update APK: included"**. "NOT included" means the image has no APK (see recovery).

Version components must stay within 0..99 (`versionCode = X*10000 + Y*100 + Z`); the `apk` job refuses anything else.

Images and the chart are published to GHCR under the repository owner: `ghcr.io/<owner>/earmark` and `oci://ghcr.io/<owner>/charts/earmark`.
Install with `helm install earmark oci://ghcr.io/<owner>/charts/earmark --version X.Y.Z -f values.yaml`; see `helm/earmark/values.yaml`.

## Recovery

Actions -> Release -> Run workflow **on `main`**, `tag` = the existing tag (e.g. `v0.4.0`). The run is refused unless it comes from
`main` and the tag exists and is reachable from `main`.

* Missing image/chart: re-run with the tag. An image tag that already exists is never rebuilt.
* Image published without the APK (signing secret missing, Gradle failed, ...): fix the cause, run with the tag and **`force_images`**
  ticked. It rebuilds the APK of that tag, overwrites the image tag and re-attaches the APK to the release (`--clobber`).

The `apk` job is allowed to fail without blocking the image and chart; always check the summary of `images`.

## Optional: GitOps auto-deploy

If you deploy with a GitOps tool (for example Argo CD) from a separate repository that has a values file with a `targetRevision` for the
`earmark` chart, the `register` job can move it forward after each release. It is optional: **when the secret `ARGOCD_APPS_DEPLOY_KEY`
does not exist, `register` skips with a notice** and the run still succeeds.

To enable it:

1. Generate a key pair (`ssh-keygen -t ed25519 -N '' -f key`), add `key.pub` as a deploy key **with write access** to the GitOps repository
   only, and store `key` as the secret `ARGOCD_APPS_DEPLOY_KEY`.
2. Set repository variables if the defaults do not fit: `GITOPS_REPO` (default `<owner>/argocd-apps`), `GITOPS_VALUES_FILE` (default
   `bootstrap/values.yaml`) and `GITOPS_APP_NAME` (default `earmark`; the entry is `- name: earmark` followed by `targetRevision:` and `namespace:` lines).

`register` only ever moves the version forward (re-running an old tag is a no-op) and skips pre-releases. To redo only a failed `register`,
re-run the workflow with the tag.
