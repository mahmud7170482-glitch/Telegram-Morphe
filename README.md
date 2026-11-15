# Telegram + Vault (Morphe patch)

A Morphe patch for the official Telegram app that adds a **PIN-protected Vault**:

* **Hide accounts**: a hidden account disappears from Telegram's side-menu account list.
* **Show them again** for 5 minutes from the Vault when you want to switch to a hidden account.
* **Lock Telegram** with your own PIN when it opens and after it has been in the background for 0 seconds, 30 seconds,
  1, 5 or 15 minutes. The Telegram window is also hidden from screenshots and the recent-apps preview while the lock is on.
* The Vault is a second launcher icon. It locks the moment you leave it, and blocks PIN guessing for 30 seconds after 5 wrong tries.

This is an early version. Read the limits below.

## Why it is built this way

Telegram 13 scrambles almost every class and method name, so a patch that hooks a name like `DrawerLayoutAdapter` finds nothing
(that is what failed in the first version). This patch hooks only names that cannot be scrambled:

* `LaunchActivity.onResume()` and `onPause()` (Android's own methods, so they keep their names).
* Telegram's copy of `androidx.recyclerview.widget.RecyclerView`, which keeps its name together with `getAdapter()` and `setAdapter()`.

The extension then finds the side menu **by looking at what is on screen**: a list narrower than the screen whose adapter keeps a
list of account numbers. If it cannot find that, hiding switches itself off quietly and nothing crashes.

## Files

* `custom-patches/extensions/all/telegram-vault/`: Java code that runs inside Telegram (PIN, side-menu watcher, Vault and lock screens).
* `custom-patches/patches/.../TelegramVaultPatch.kt`: the Morphe patch (two hooks + two manifest entries).
* The workflow builds the official [MorpheApp/morphe-patches](https://github.com/MorpheApp/morphe-patches) at tag `v2.1.0`
  **with these files added**, then patches the official Telegram APK (`telegram.org/dl/android/apk`).

## Set up (works from a phone browser)

1. Create a new GitHub repository (**private is recommended**) and upload everything here, keeping the folders.
2. **Actions → Build Telegram with Vault (Morphe) → Run workflow.** It takes about 10 to 25 minutes.
3. Download the APK from **Releases** (or the artifact, which is a `.zip`: extract it).
4. **The patched app has the same package name as the official Telegram**, so Android won't install it over the official one.
   **Uninstall the official Telegram first.** Chats are on Telegram's servers, but secret chats and local-only data are lost. Log in again.
5. Open Telegram once and wait a moment (so the Vault can see your accounts), then open the new **Vault** icon, set a PIN and hide an account.

If publishing fails with a 403 error: **Settings → Actions → General → Workflow permissions → Read and write**.

## How to use it

* **Hide**: Vault → unlock → tap **Hide** on an account. Open Telegram's side menu to see which one disappeared. **Unhide** to undo.
  The Vault shows "Account 1, 2, 3 ..." because Telegram's real names are not reachable; try it and see.
* **Switch to a hidden account**: Vault → **Show hidden accounts for 5 minutes**, then use Telegram's normal side menu.
  After 5 minutes (or **Hide them again now**) they disappear again.
* **Lock**: Vault → **Lock is OFF** (tap to turn on) → choose **Lock after**. Telegram then asks for the PIN as set.

## Limits

* **Notifications** from a hidden account still appear. Mute that account in Telegram if you need silence.
* Accounts are hidden in the **side-menu list**. Other places that list accounts may still show them.
* **Don't hide the account you are currently using** (the side-menu header still shows it).
* When Telegram rebuilds its side menu, the list refreshes on the next screen update and a hidden row may flash for a moment.
* The PIN is a lock against casual snooping, not against someone who can read the phone's storage. If you forget it, clearing the app's data
  resets it and logs out every account.
* Not included: pattern lock, fingerprint, locking single chats.
* The app is re-signed with your key, so Google's push service may not deliver notifications. Check Telegram's notification
  settings for the background-connection ("keep-alive") option.

## Please test carefully

* **Use a spare account first.** This is untested code in a messaging app. It only changes what the side menu shows and adds a lock screen,
  but treat it as experimental.
* Telegram changes its internals often. If the patch stops finding what it needs after a Telegram update, the workflow log says so.
* Modified Telegram clients may break Telegram's terms. Keep the official app for anything important.

## Troubleshooting

* **Build fails in "Build the patches"**: the Kotlin or Java code did not compile. Send the red lines.
* **Patching fails with a fingerprint error** (for `LaunchActivity`, `onResume` or `onPause`): Telegram changed even those. Send the log.
* **The Vault shows "No accounts seen yet"**: open Telegram, wait a few seconds with the app in front, and try again. If it stays empty, the
  side menu could not be found; send the Telegram version (shown in the build log).
* **Hidden account still in the menu**: same cause as above.
* **Install fails**: uninstall the official Telegram first. The workflow also repairs, aligns and re-signs the APK.
