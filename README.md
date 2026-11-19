# Pushbox for Android

Pushbox sends a ZIP or a set of files to a GitHub repository from your phone, without Git.
This repository is the Android app: a thin wrapper around the Pushbox web page in
`app/src/main/assets/index.html`. GitHub builds the APK for you.

## Get the APK

1. Open the **Actions** tab. The "Build APK" run starts on every push to `main` and takes about 3 minutes.
   To start one by hand, open "Build APK" and tap **Run workflow**.
2. When it finishes, open **Releases**, download `app-release.apk` from "Pushbox (latest build)" and install it.
   Android asks you to allow installs from your browser the first time.

## Updating the app

Without a saved signing key, each build is signed with a new throwaway key, so uninstall the old
app before installing a new APK. To install over the old one instead, add three repository
secrets (Settings, Secrets and variables, Actions):

- `KEYSTORE_B64`: your keystore file, base64 encoded
- `KEYSTORE_PASSWORD`: its password
- `KEY_ALIAS`: the key alias

## Changing the app

Edit `app/src/main/assets/index.html` and push. Android WebView cannot pick folders, so the app
shows ZIP and Files only.
