/*
 * Telegram vault: hidden accounts and a PIN lock for Telegram.
 *
 * Built on the Morphe patcher API (GPLv3), following the structure of the official morphe-patches project.
 *
 * Telegram 13 scrambles almost all of its class and method names, so this patch hooks only names that cannot be
 * scrambled: LaunchActivity (declared in the manifest) and its onResume / onPause (they override Android's own
 * methods). Everything else is done by the extension at runtime by looking at the objects on screen.
 */

package app.morphe.patches.all.telegram

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "Lapp/morphe/extension/all/telegram/HiddenAccounts;"
private const val VAULT_ACTIVITY = "app.morphe.extension.all.telegram.VaultActivity"
private const val UNLOCK_ACTIVITY = "app.morphe.extension.all.telegram.UnlockActivity"

private object LaunchActivityOnResumeFingerprint : Fingerprint(
    definingClass = "Lorg/telegram/ui/LaunchActivity;",
    name = "onResume",
    returnType = "V",
    parameters = listOf()
)

private object LaunchActivityOnPauseFingerprint : Fingerprint(
    definingClass = "Lorg/telegram/ui/LaunchActivity;",
    name = "onPause",
    returnType = "V",
    parameters = listOf()
)

/** Calls the extension right before every `return-void` of the method found by [fingerprint]. */
private fun hookEveryReturn(fingerprint: Fingerprint, extensionMethod: String) {
    val method = fingerprint.method
    val returns = method.implementation!!.instructions
        .withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }
        .map { it.index }

    check(returns.isNotEmpty()) { "$extensionMethod: the hooked method has no return-void" }

    // Insert from the last return to the first so the earlier indices stay valid.
    returns.asReversed().forEach { index ->
        method.addInstruction(
            index,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->$extensionMethod(Landroid/app/Activity;)V"
        )
    }
}

@Suppress("unused")
val telegramVaultPatch = patch(
    name = "Telegram vault",
    description = "Adds a second launcher icon (Vault) protected by a PIN, where Telegram accounts can be hidden " +
        "from the side menu, and an optional PIN lock for Telegram. For the official Telegram APK " +
        "(org.telegram.messenger.web).",
    default = false
) {
    extendWith("extensions/all/telegram-vault/telegram-vault.mpe")

    execute {
        // 1) Hooks on the two lifecycle methods that keep their names.
        hookEveryReturn(LaunchActivityOnResumeFingerprint, "onResume")
        hookEveryReturn(LaunchActivityOnPauseFingerprint, "onPause")

        // 2) The Vault screen (own launcher icon) and the lock screen.
        resourceXml("AndroidManifest.xml").use { manifest ->
            val application = manifest.elements("application").first()
            application.appendChild("activity") {
                setAttribute("android:name", VAULT_ACTIVITY)
                setAttribute("android:label", "Vault")
                setAttribute("android:icon", "@android:drawable/ic_lock_lock")
                setAttribute("android:exported", "true")
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
                setAttribute("android:excludeFromRecents", "true")
                appendChild("intent-filter") {
                    appendChild("action") { setAttribute("android:name", "android.intent.action.MAIN") }
                    appendChild("category") { setAttribute("android:name", "android.intent.category.LAUNCHER") }
                }
            }
            application.appendChild("activity") {
                setAttribute("android:name", UNLOCK_ACTIVITY)
                setAttribute("android:exported", "false")
                setAttribute("android:theme", "@android:style/Theme.DeviceDefault.NoActionBar")
                setAttribute("android:excludeFromRecents", "true")
                setAttribute("android:launchMode", "singleTop")
            }
        }
    }
}
