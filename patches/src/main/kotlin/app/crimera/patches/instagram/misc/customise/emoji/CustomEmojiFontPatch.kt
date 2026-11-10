/*
 * Copyright (C) 2026 piko-plus <https://github.com/g8row/piko-plus>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.customise.emoji

import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.registersUsed
import app.morphe.util.findInstructionIndicesReversed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EMOJI_FONT = "$PATCHES_DESCRIPTOR/customise/emoji/EmojiFont;"
private const val STATIC_LAYOUT_BUILDER = "Landroid/text/StaticLayout\$Builder;"
private const val OBTAIN_SIGNATURE =
    "(Ljava/lang/CharSequence;IILandroid/text/TextPaint;I)$STATIC_LAYOUT_BUILDER"

/** androidx EmojiCompat.process(CharSequence, int, int, int, int): the one every emoji2 caller ends in. */
private object EmojiCompatProcessFingerprint : Fingerprint(
    returnType = "Ljava/lang/CharSequence;",
    filters = listOf(string("end should be < than charSequence length")),
    custom = { method, _ ->
        !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/CharSequence;"
    },
)

/** Every app method that lays text out itself through StaticLayout.Builder.obtain. */
private object StaticLayoutObtainFingerprint : Fingerprint(
    filters = listOf(
        methodCall(definingClass = STATIC_LAYOUT_BUILDER, name = "obtain"),
    ),
    custom = { _, classDef -> !classDef.type.startsWith("Lapp/morphe/") },
)

private fun Instruction.isObtainCall() =
    (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) &&
        getReference<MethodReference>()?.let {
            it.definingClass == STATIC_LAYOUT_BUILDER && it.name == "obtain"
        } == true

@Suppress("unused")
val customEmojiFontPatch =
    patch(
        name = "Custom emoji font",
        description = "Adds an option to download an emoji font (Apple, Noto, Twemoji, Fluent) and draw emoji in it.",
    ) {
        compatibleWith(COMPATIBILITY_INSTAGRAM)
        dependsOn(settingsPatch)

        execute {
            // Text views, text fields and Compose text: EmojiCompat processes them all. The emoji
            // runs get a span with the downloaded font before EmojiCompat looks at the text.
            EmojiCompatProcessFingerprint.method.addInstructions(
                0,
                """
                invoke-static/range {p1 .. p1}, $EMOJI_FONT->process(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;
                move-result-object p1
                """.trimIndent(),
            )

            // Custom text views (captions, comments) lay their text out with StaticLayout. Each
            // obtain call is pointed at a wrapper with the same signature, so no register changes.
            val methods = StaticLayoutObtainFingerprint.matchAll()
            if (methods.isEmpty()) throw PatchException("No StaticLayout.Builder.obtain calls found")
            methods.forEach { match ->
                match.method.apply {
                    findInstructionIndicesReversed { isObtainCall() }.forEach { index ->
                        val registers = instructions.elementAt(index).registersUsed
                        val call = if (instructions.elementAt(index).opcode == Opcode.INVOKE_STATIC_RANGE) {
                            "invoke-static/range {v${registers.first()} .. v${registers.last()}}"
                        } else {
                            "invoke-static {${registers.joinToString { "v$it" }}}"
                        }
                        replaceInstruction(index, "$call, $EMOJI_FONT->obtain$OBTAIN_SIGNATURE")
                    }
                }
            }

            enableSettings("customEmojiFont")
        }
    }
