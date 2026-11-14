# Telegram + Vault (Morphe patch)

A Morphe patch that adds a **PIN-protected Vault** to the official Telegram app:

* **Hide an account**: it disappears from Telegram's side-menu account list.
* **Open a hidden account** from the Vault (a second launcher icon called **Vault**) after entering your PIN.
* The Vault locks **the moment you leave it**, blocks screenshots, and blocks PIN guessing for 30 seconds after 5 wrong tries.

This is a first version. Read the limits below before you rely on it.

## How it is built

* `custom-patches/extensions/all/telegram-vault/`: the Java code that runs inside Telegram (PIN, hidden list, Vault screen).
* `custom-patches/patches/.../TelegramVaultPatch.kt`: the Morphe patch. It hooks `DrawerLayoutAdapter.resetItems()` (the
  side menu) and adds the Vault activity to the manifest.
* The workflow builds the official [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) at tag
  `v2.1.0` **with these files added**, then patches the official Telegram APK (`telegram.org/dl/android/apk`) with the result.

## Set up (works from a phone browser)

1. Create a new GitHub repository (**private is recommended**) and upload everything here, keeping the folders.
2. **Actions → Build Telegram with Vault (Morphe) → Run workflow.** It takes about 10 to 25 minutes.
3. Download the APK from **Releases** (or the artifact, which is a `.zip`: extract it).
4. **The patched app has the same package name as the official Telegram.** Android won't install it over the official one
   (different signing key), so you have to **uninstall the official Telegram first**. Your chats are on Telegram's servers,
   but secret chats and local-only data are lost. Log in again afterwards.
5. Open the new **Vault** icon, set a PIN, then hide an account.

If publishing fails with a 403 error: **Settings → Actions → General → Workflow permissions → Read and write**.

## Limits of this first version

* **Notifications** from a hidden account still appear. Mute that account in Telegram if you need silence.
* Accounts are hidden in the **side-menu list**. Other places that list accounts may still show them.
* You can't hide the account that is currently selected (switch to another account first), and you can't hide the last visible one.
* The PIN is a lock against casual snooping. It does not protect against someone who can read your phone's storage.
* **Not included yet** (compared with Graph Messenger): pattern lock, fingerprint, locking single chats, instant lock for
  the whole app. Telegram's own **Settings → Privacy and Security → Passcode Lock** still works as the app lock.
* Because the app is re-signed with your key, Google's push service may not deliver notifications. In Telegram, check
  **Settings → Notifications** for the background-connection ("keep-alive") option.

## Please test carefully

* **Use a spare account first.** The patch only changes what the side menu shows, but this is untested code in a messaging app.
* This was written without being able to run it on a phone. The workflow's first run may fail while building. Send the
  failing step's log lines and it can be fixed.
* Modified Telegram clients may break Telegram's terms. Keep the official app for anything important.

## Troubleshooting

* **Build fails in "Build the patches"**: the Kotlin or Java code did not compile. Send the red lines.
* **"The 'Telegram vault' patch is not in the built bundle"**: the patch did not register. Send the log.
* **Patching fails with a fingerprint error** (for example `DrawerLayoutAdapter`): Telegram renamed that class or method
  in the version you downloaded. Send the log; use `apk_url` to pin an older Telegram APK meanwhile.
* **Install fails**: uninstall the official Telegram first (see step 4 above), and see the Instagram notes about
  "package parsing" errors: this workflow also repairs, aligns and re-signs the APK.
* **The hidden account is still in the side menu**: the hook ran but could not find the list. Send the logcat or tell me
  the Telegram version (the log shows it).
