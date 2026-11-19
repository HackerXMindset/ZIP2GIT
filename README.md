# Pushbox for Android

Pushbox sends a ZIP, a folder or a set of files to a GitHub repository from your phone, without Git.
The app is a thin Android wrapper around the Pushbox web page in `app/src/main/assets/index.html`.
GitHub builds the APK for you.

## Get the APK

1. Open the **Actions** tab. "Build APK" runs on every push to `main` and takes about 3 minutes.
   To start one by hand, open "Build APK" and tap **Run workflow**.
2. When it finishes, open **Releases**, download `app-release.apk` from "Pushbox (latest build)" and install it.
   Android asks you to allow installs from your browser the first time.

Pull requests build the APK too (as a downloadable artifact) but do not change the release.

## What it does

- Compares your files with the repository and shows added, changed and deleted files, grouped by folder, with search and filters.
- Shows a line diff for changed text files.
- Warns about files that look like secrets (keys, tokens, `.env`) and leaves them unticked in public repositories.
- Spots files that differ only in line endings and leaves them unticked.
- Builds the commit one folder at a time, so large pushes do not time out. Retrying skips files that already uploaded.
- Writes the commit message for you if you leave it empty.
- Can push to a new branch and give you a link to open a pull request.
- Undo for the last pushes, and settings remembered per repository.

In the Android app you can also pick a whole folder, share a ZIP or files into Pushbox from any app,
keep the saved token encrypted (optionally behind your screen lock), keep the screen on during a push,
and see a notice when a newer build is available.

## Install updates over the old app

Without a saved signing key, each build is signed with a new throwaway key, so Android makes you uninstall
the old app first. To install over it instead, add three repository secrets
(Settings, Secrets and variables, Actions):

- `KEYSTORE_B64`: your keystore file, base64 encoded
- `KEYSTORE_PASSWORD`: its password
- `KEY_ALIAS`: the key alias

Keep using the same key from then on.

## Changing the app

Edit `app/src/main/assets/index.html` for the page and `app/src/main/java/app/pushbox/` for the Android side, then push.
