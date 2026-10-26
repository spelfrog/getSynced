# getSynced

getSynced keeps a folder on your Android phone (typically an Obsidian vault) in sync with a git remote over
HTTPS. It syncs when the app you choose is opened and when it is closed, when you press **Sync now**, or when an
automation app sends it an intent. Its one promise: **it shows every step, never a silent spinner.** Each run
lists Commit, Fetch, Merge and Push with live progress, timings and the full error text when something fails,
both in the app and in the notification.

## Install

**[Download the latest APK](https://github.com/spelfrog/getSynced/releases/latest/download/getSynced.apk)**
(all releases: [Releases](https://github.com/spelfrog/getSynced/releases)). Open the file on the phone and allow
installing from this source when asked. Updates install over the old version; settings are kept.

The app is not on the Play Store because it needs *All files access* to run git on the vault folder.

## How a sync works

1. **Check**: the repo exists, a remote and a token are configured, and the folder is writable. If not, the run
   fails and names what is missing.
2. **Commit**: if anything changed, everything (including deletions) is committed as
   `sync from <device> <time>`. Skipped when the folder is clean.
3. **Fetch** from `origin`, with progress.
4. **Merge**: skipped if the remote has nothing new, fast-forward if possible, otherwise a real merge.
5. **Push** `HEAD` to `origin`. If the push is rejected because the remote moved on, steps 3 to 5 run once more.

**Conflicts:** if the merge conflicts, your local commit is kept and the remote changes are *not* merged. The
folder is reset to your local commit (nothing is lost, and the vault stays usable) and the run is shown as
**CONFLICT** with the conflicting files listed. Resolve it on a computer for now (pull, fix, push) and the next
sync on the phone fast-forwards.

## Setup on the phone

1. **Install the APK** (from the [Releases](../../releases) page, a CI artifact, or your own build, see below).
2. **Grant the permissions** from the checklist on the setup screen:
   - **All files access**: git needs real file paths, so the scoped storage APIs do not work.
   - **Notifications**: progress and results are shown there.
   - **Accessibility** ("getSynced app watcher"): used only to notice which app is in front. On Android 13+,
     sideloaded apps cannot enable this right away. First open *Settings → Apps → getSynced → ⋮ (top right) →
     Allow restricted settings*, then enable the service under *Settings → Accessibility*. The service receives
     window change events only and never reads screen content.
3. **Authenticate**, one of:
   - **Sign in with GitHub** (default): tap the button, you get a short code, enter it on
     `github.com/login/device`, approve. No password or secret is stored on the phone; the resulting token is
     encrypted on the device. This uses GitHub's *Device Flow* with the public Client ID that is prefilled.
     To use your own app instead, see [Your own GitHub App](#your-own-github-app).
   - **Access token**:
     - GitHub: a *fine-grained personal access token* limited to the vault repository, with
       **Contents: Read and write**. The username is ignored (`x-access-token` or anything works).
     - GitLab: a *project access token* with the **write_repository** scope (role Developer or higher). Use
       `oauth2` as the username.
4. **Fill in the form**: HTTPS remote URL, vault folder, author name and email, branch (empty
   means the remote default), the app to watch (for example Obsidian) and the open/close toggles.
5. **Clone**: *Clone now* for an empty folder, or *Use existing repo* if the folder already contains `.git`.
   Then open that folder as a vault in Obsidian.

### Your own GitHub App

The sign-in needs only a *Client ID*, which is public. Either a GitHub App or an OAuth App works:

- **OAuth App** (simplest; tokens never expire): *GitHub → Settings → Developer settings → OAuth Apps → New*.
  Any name and homepage URL, callback URL can be the homepage, tick **Enable Device Flow**. The app asks for the
  `repo` scope, which is what git push needs.
- **GitHub App** (fine-grained; only the repositories you install it on): *Developer settings → GitHub Apps →
  New*. Untick *Webhook → Active*, set *Repository permissions → Contents: Read and write*, tick **Enable Device
  Flow**. After creating it, under *Optional features* opt out of **User-to-server token expiration**, otherwise
  the token dies after 8 hours and you would have to sign in again (refreshing needs the client secret, which the
  phone must not hold). Then *Install App* on the vault repository.

Copy the app's **Client ID** into the setup screen. Tokens are revoked from *GitHub → Settings → Applications*.

## Triggers

- **App opened / closed**: when the watched app comes to the front, a sync runs (pull the latest changes). When
  you leave it and it has not come back within 3 seconds, a sync runs (push your edits). Keyboards, system UI and
  permission dialogs on top of the app do not count as leaving it.
- **Sync now** button in the app.
- **Tasker, MacroDroid, adb**: send the broadcast
  ```
  am broadcast -a dev.spelfrog.getsynced.SYNC -p dev.spelfrog.getsynced
  ```
  The receiver is exported and unprotected, so any app on the phone can request a sync (and nothing more).

Overlapping triggers do not pile up: only one sync runs or waits at a time. Without a network the run waits and
the app says so.

## Battery

Nothing polls. The accessibility service only reacts to window-change events, and when the toggles are off or the
app is not configured it does nothing. A sync runs only when triggered, as short expedited WorkManager work. No
periodic jobs and no background service.

## Building

The build needs no Android SDK on the host; it runs in a Podman container:

```sh
scripts/build.sh                    # assembleDebug -> app/build/outputs/apk/debug/app-debug.apk
scripts/build.sh testDebugUnitTest  # any gradle tasks
scripts/install.sh                  # adb install -r the debug APK (host needs adb: sudo dnf install android-tools)
```

The first run builds the `getsynced-builder` image (JDK 17, Android SDK 36) and fills the Gradle cache volume
`getsynced-gradle`. Opening the project in Android Studio works as well.

## CI

`.github/workflows/build.yml` runs unit tests and builds the debug APK on every push to `main`, on pull requests,
and on manual dispatch. The APK is uploaded as the artifact `getSynced-debug`, and test reports are uploaded when
the job fails.

Pushing a tag `v*` also runs the `release` job. It builds a signed release APK and attaches it to a GitHub
Release. It needs these repository secrets:

| Secret | Content |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Create a keystore once with
`keytool -genkeypair -v -keystore release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias getsynced`, and
never commit it (`*.jks` is ignored).

### Release signing

The workflow passes the keystore as Gradle properties (`releaseStoreFile`, which is relative to `app/`,
`releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword`). `app/build.gradle.kts` reads them as follows.
Without the properties, release builds stay unsigned and local debug builds are unaffected:

```kotlin
android {
    // ...
    signingConfigs {
        val storePath = providers.gradleProperty("releaseStoreFile").orNull
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = providers.gradleProperty("releaseStorePassword").get()
                keyAlias = providers.gradleProperty("releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("releaseKeyPassword").get()
            }
        }
    }
    buildTypes {
        release {
            // ...
            signingConfig = signingConfigs.findByName("release")
        }
    }
}
```

## Limitations and roadmap

- HTTPS with a token only; no SSH keys yet.
- One repository.
- Conflicts are detected and reported, not resolved automatically. Per-file "mine / theirs" resolution is planned.
- No shallow clone (JGit 5.13 is used for compatibility with Android 8+), so the first clone downloads the full
  history.
- Not on the Play Store: "All files access" (`MANAGE_EXTERNAL_STORAGE`) is not allowed there for this use case.
  Install from Releases or build it yourself.
- Ideas for later: Quick Settings tile, multiple repos, optional periodic sync.

## License

GPL-3.0, see [LICENSE](LICENSE).
