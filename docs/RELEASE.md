# Releasing gig.log

How a version tag becomes a Play build on the internal testing track and a GitHub Release with the sideload and
debug APKs. The workflows are `.github/workflows/release.yml` (the tag) and `.github/workflows/play-listing.yml`
(the store listing, run by hand). This is a public repo: no key, password, project id or account email goes into
it in any form. The steps below are the developer's; an agent has no signing key.

## The three builds

| Build | Application ID | `isAccessibilityTool` | Screen events offered | Where it goes | Signed with |
|---|---|---|---|---|---|
| `release` (the Play build) | `cloud.trotter.log.gig` | false | no | Play internal testing | the upload key, then Play App Signing |
| `sideload` | `cloud.trotter.log.gig.sideload` | true | yes | GitHub Release, `giglog-sideload-<version>.apk` | the sideload key |
| `debug` | `cloud.trotter.log.gig.debug` | true | yes | GitHub Release, `giglog-debug-<version>.apk` | the debug key (#1469) |

`app/build.gradle.kts` owns this table. The sideload build is a release build (no saved screen text, no census
upload, no verbose logs); it differs from the Play build only where Play's rules force it. The debug build is the
developer's test build: it keeps its application ID, its key and version code 1, so a CI debug APK installs over
the phone's build and keeps its data. It is not a build for other drivers.

**The Play build stays on the internal testing track until #170 (the consent flow Play needs) and #1413 (the
field test with the flag off) are done.** Before #170 the Play build has no Screen events step and no prominent
disclosure before the accessibility step, which is acceptable for internal testers only.

## 1. Keys (one time)

Keep every keystore outside the repo (a password manager attachment, an encrypted volume). `*.jks` and
`*.keystore` are gitignored as a second guard, not as the plan.

- **Upload key.** The keystore already in the `release` environment (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`) becomes the Play upload key; nothing public was ever signed with it. Once Play App Signing is
  accepted (§3), Google holds the app signing key and a lost upload key can be reset through Play support.
- **Sideload key.** A separate key, because the sideload build is signed by us and never by Play. Losing it means
  every sideload install must be uninstalled to take an update, so keep it like a house key:

  ```sh
  keytool -genkeypair -v -keystore ~/keystores/giglog-sideload.jks -alias giglog-sideload \
    -keyalg RSA -keysize 4096 -validity 10000
  ```

- **Debug key.** Already exists (#1469): `DEBUG_KEYSTORE_BASE64` and `DEBUG_KEYSTORE_PASSWORD`, alias
  `giglogdebug`.

## 2. Local signed builds

Unset signing leaves the Play and sideload builds unsigned, which is fine for checking they build. To sign
locally, set the environment variables CI uses (`KEYSTORE_PATH`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`,
and the same four with a `SIDELOAD_` prefix), or add to the gitignored `local.properties`:

```
keystore.path=/path/to/upload.jks
keystore.store.password=...
keystore.key.alias=...
keystore.key.password=...
sideload.keystore.path=/path/to/giglog-sideload.jks
sideload.keystore.store.password=...
sideload.keystore.key.alias=giglog-sideload
sideload.keystore.key.password=...
```

The debug key reads `giglog.debugKeystore.path` and `giglog.debugKeystore.passwordFile` from
`~/.gradle/gradle.properties` (see the `debug` signing config). A tag's version applies only with
`-PgiglogVersionCode=<code> -PgiglogVersionName=<name>`; without them every build has version code 1.

## 3. Play Console (one time, in the browser)

1. **Developer account** at play.google.com/console, and the identity verification Play asks for.
2. **Create the app** `cloud.trotter.log.gig`. The Play API cannot create apps.
3. **First bundle by hand.** Push a tag with `PLAY_PUBLISH` unset (§6): the run builds the AAB and keeps it as
   the `play-bundle` workflow artifact for 7 days. Upload it in the Console to the internal testing track and
   accept **Play App Signing** when asked. Play treats the app as a draft until that first release is published,
   and a draft app's API accepts only draft releases, so keep `PLAY_PUBLISH` off until the release is out of
   draft. Publishing it needs the App content forms done, including the accessibility declaration's video
   (`play/accessibility-declaration.md`); record that video from the run's AAB, built into an APK with `bundletool`
   and installed over USB, because the Play Store cannot serve the app before the release is published. From then
   on the pipeline uploads.
4. **Internal testing track.** Add the testers (the developer at minimum); testers opt in through the link the
   Console shows.
5. **Store listing.** The files live in `play/listing/` and #1416 fills them; the "Play store listing" workflow
   pushes them.
6. **Service account for the pipeline**, with no key: §4.
7. **Privacy policy.** App content → Privacy policy: `https://gig.log.trotter.cloud/privacy/`. The Pages workflow
   (`pages.yml`) builds that page from the in-app Privacy page's `privacy_*` strings and PRIVACY.md's current revision,
   and deploys it from `master`.

## 4. Google Cloud: keyless access for the workflows

Create a service account for this app with no project-level roles and no key. Both workflows authenticate with
Workload Identity Federation, and only from two places: the `release` environment on a `v*` tag (release.yml) and
the `play-listing` environment on `master` (play-listing.yml). Any other job, including one on `master` that can
request an identity token, such as a Pages deploy, gets no credential. The provider admits this repository by its
numeric repository id and owner id, not by name, so renaming the repository never breaks the login.

In Cloud Shell, select the project that owns the service account, then set `PROJECT_NUMBER` and `SA`. Skip the
pool command if a `github` pool already exists in that project. `911857133` is this repository's id and `33046718`
its owner's (`gh api repos/sjtrotter/gig.log --jq '.id, .owner.id'`); a fork uses its own.

```sh
PROJECT_NUMBER='<GCP_PROJECT_NUMBER>'
SA='<SERVICE_ACCOUNT_EMAIL>'
gcloud services enable iamcredentials.googleapis.com sts.googleapis.com androidpublisher.googleapis.com
gcloud iam workload-identity-pools create github --location=global --display-name=GitHub
gcloud iam workload-identity-pools providers create-oidc giglog --location=global --workload-identity-pool=github \
  --issuer-uri=https://token.actions.githubusercontent.com \
  --attribute-mapping=google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.environment=assertion.environment,attribute.ref=assertion.ref \
  --attribute-condition="assertion.repository_id=='911857133' && assertion.repository_owner_id=='33046718' && ((assertion.environment=='release' && assertion.ref.startsWith('refs/tags/v')) || (assertion.environment=='play-listing' && assertion.ref=='refs/heads/master'))"
gcloud iam service-accounts add-iam-policy-binding "$SA" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github/attribute.repository_id/911857133"
```

To check the condition before the first release, run play-listing.yml in `inspect` mode from `master`; it changes
nothing in the Play Console. The signed feeds (#1559) use a separate pool and never this one.

In Play Console → Users and permissions, invite the service account's email and grant, for this app only:
**View app information**, **Release apps to testing tracks** and **Manage store presence**. With only those three,
the listing upload's final commit was refused (403) while the app was a draft. It went through once the account had
every permission for this app and, at account level, **View app information and download bulk reports**; which of
the two was needed is not yet known. Never account-level Admin. Promotion to production stays a hand step in the
Console.

## 5. Secrets and variables

Settings → Environments → `release` (only version tags can deploy to it):

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 <upload keystore>` (exists) |
| `STORE_PASSWORD` | the upload keystore's password (exists) |
| `KEY_ALIAS` | the upload key's alias (exists) |
| `KEY_PASSWORD` | the upload key's password (exists) |
| `SIDELOAD_KEYSTORE_BASE64` | `base64 -w0 ~/keystores/giglog-sideload.jks` |
| `SIDELOAD_STORE_PASSWORD` | the sideload keystore's password |
| `SIDELOAD_KEY_ALIAS` | `giglog-sideload` |
| `SIDELOAD_KEY_PASSWORD` | the sideload key's password |

Settings → Environments → `play-listing`: no secrets; Deployment branches and tags: `master` only. The Play
login admits play-listing.yml only through this environment (§4).

Settings → Secrets and variables → Actions (repository):

| Name | Kind | Value |
|---|---|---|
| `DEBUG_KEYSTORE_BASE64` | secret | exists (#1469), shared with PR CI |
| `DEBUG_KEYSTORE_PASSWORD` | secret | exists (#1469), shared with PR CI |
| `PLAY_PUBLISH` | variable | `true` to upload to Play; anything else skips the upload with a notice |
| `PLAY_WIF_PROVIDER` | variable | `projects/<GCP_PROJECT_NUMBER>/locations/global/workloadIdentityPools/github/providers/giglog` |
| `PLAY_SERVICE_ACCOUNT` | variable | the §4 service account's email |

The release workflow refuses to run, with an error naming each missing value, when the sideload or debug
secrets are absent, and, while `PLAY_PUBLISH` is `true`, when the upload secrets or the two Play variables are
absent. Keystores are decoded into the runner's temp directory, never the workspace, and deleted at the end of
the job, pass or fail. The job that creates the GitHub Release holds no secrets.

**Tag ruleset.** Settings → Rules → a tag ruleset targeting **All tags**, restricting creation, update and
deletion, with repository admins as the bypass. A `v*` pattern would miss a tag such as `v1/x`, which the keyless
login still accepts. Anyone who can push a version tag can publish to the testers.

## 6. Cutting a release

1. In a PR, set `baseVersionName` in `app/build.gradle.kts` to the new version and merge it.
2. Tag that merge on master and push the tag:

   ```sh
   git tag v0.231.0 <commit on master> && git push origin v0.231.0
   ```

The run refuses a tag that is not `vMAJOR.MINOR.PATCH` (no leading zeros), not on master, or not equal to
`baseVersionName`. Minor and patch are at most 999 and major at most 2099. Major 0 releases are marked as
prereleases. Tags only go up: Android never updates to a lower version code.

The version name is the tag without `v`, then `+<sha>`, as every build's is. The version code, for the Play and
sideload builds, is `major*1_000_000 + minor*1_000 + patch`; the debug build keeps code 1.

| Tag | Version code |
|---|---|
| `v0.230.0` | 230000 |
| `v0.230.1` | 230001 |
| `v0.231.0` | 231000 |
| `v1.0.0` | 1000000 |

What the run does: builds the Play APK (and the AAB when the upload key is present), the sideload APK and the
debug APK in one Gradle run; checks with `aapt2` that the Play APK declares `isAccessibilityTool` false, the
other two true, and each has the expected version code; keeps the two APKs and the AAB as workflow artifacts
for 7 days; uploads the AAB to the internal track while `PLAY_PUBLISH` is `true`; then creates the GitHub
Release with generated notes and attaches the two APKs. A Release made by hand in the web UI keeps its notes.

A run that failed before the Play upload can be re-run. Once an upload reached Play its version code is spent,
so fix a bad release with the next patch tag. Re-running only the `release` job does not repeat the upload.

## 7. Android developer verification

Android is rolling out developer verification: on certified devices, an app installed from outside Play must be
registered to a verified developer, region by region. Before it reaches the US, register
`cloud.trotter.log.gig.sideload` and `cloud.trotter.log.gig.debug`, with their signing certificates, in the
Android developer verification console. The Play build is covered by the Play Console account.
