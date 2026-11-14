/*
 * Telegram vault: hidden accounts behind a PIN.
 *
 * Built on the Morphe patcher API (GPLv3), following the structure of the official morphe-patches project.
 */

package app.morphe.patches.all.telegram

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "Lapp/morphe/extension/all/telegram/HiddenAccounts;"
private const val VAULT_ACTIVITY = "app.morphe.extension.all.telegram.VaultActivity"

/**
 * The side menu adapter of Telegram. resetItems() fills the list of accounts that the side menu shows.
 * Telegram's own class names are not obfuscated, so the class and method name are enough to find it.
 */
private object DrawerResetItemsFingerprint : Fingerprint(
    definingClass = "Lorg/telegram/ui/Adapters/DrawerLayoutAdapter;",
    name = "resetItems"
)

@Suppress("unused")
val telegramVaultPatch = patch(
    name = "Telegram vault",
    description = "Adds a second launcher icon (Vault) protected by a PIN, where Telegram accounts can be hidden " +
        "from the side menu and opened again. For the official Telegram APK (org.telegram.messenger.web).",
    default = false
) {
    extendWith("extensions/all/telegram-vault/telegram-vault.mpe")

    execute {
        // 1) Hide hidden accounts from the side menu: call the extension at every return of resetItems().
        val method = DrawerResetItemsFingerprint.method
        val returns = method.implementation!!.instructions
            .withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }

        check(returns.isNotEmpty()) { "DrawerLayoutAdapter.resetItems() has no return-void" }

        // Insert from the last return to the first so the earlier indices stay valid.
        returns.asReversed().forEach { index ->
            method.addInstruction(
                index,
                "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->filterDrawer(Ljava/lang/Object;)V"
            )
        }

        // 2) The Vault screen: an activity with its own launcher icon.
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
        }
    }
}
