package anomaly.hex.android.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import anomaly.hex.android.DebugLog

/**
 * M2's text delivery. The accessibility node is the only handle Hex has on
 * another app's field, so insertion is a splice through ACTION_SET_TEXT.
 * Anything that cannot be read or written that way falls back to the
 * clipboard: the transcript stays on it and the bubble says so.
 */
object Inserter {
    data class Before(
        val node: AccessibilityNodeInfo,
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    sealed interface Outcome {
        data class Inserted(val before: Before) : Outcome
        data object Pasted : Outcome
        data object Copied : Outcome
        data class Failed(val reason: String) : Outcome
    }

    fun focusedEditable(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (root == null) return null
        for (mode in intArrayOf(
            AccessibilityNodeInfo.FOCUS_INPUT,
            AccessibilityNodeInfo.FOCUS_ACCESSIBILITY,
        )) {
            val node = try {
                root.findFocus(mode)
            } catch (_: Exception) {
                null
            } ?: continue
            if (node.isEditable && node.isEnabled && node.isVisibleToUser && !isSensitive(node)) {
                return node
            }
        }
        return null
    }

    fun isSensitive(node: AccessibilityNodeInfo): Boolean {
        if (node.isPassword) return true
        val type = node.inputType
        val klass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        return when (klass) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

    fun insert(context: Context, node: AccessibilityNodeInfo, transcript: String): Outcome {
        val clean = transcript.trim()
        if (clean.isEmpty()) return Outcome.Failed("No speech detected")
        if (isSensitive(node)) return Outcome.Failed("Sensitive field")
        if (!node.refresh()) return Outcome.Failed("Field is gone")

        // A field whose text cannot be read cannot be spliced safely; paste.
        val existing = node.text?.toString() ?: return paste(context, node, clean)

        val start = node.textSelectionStart.coerceIn(0, existing.length)
        val end = node.textSelectionEnd.coerceIn(start, existing.length)
        val insertion = spaced(existing, start, end, clean)
        val merged = existing.substring(0, start) + insertion + existing.substring(end)
        val cursor = start + insertion.length
        val before = Before(node, existing, start, end)

        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                merged,
            )
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        }
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
        return if (ok) Outcome.Inserted(before) else paste(context, node, clean)
    }

    fun restore(before: Before): Boolean {
        if (!before.node.refresh()) return false
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                before.text,
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                before.selectionStart,
            )
            putInt(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                before.selectionEnd,
            )
        }
        return try {
            before.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) {
            false
        }
    }

    private fun paste(context: Context, node: AccessibilityNodeInfo, text: String): Outcome {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Hex", text))
        val focused = try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (_: Exception) {
            false
        }
        val pasted = focused && try {
            node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (_: Exception) {
            false
        }
        DebugLog.log("Insert", if (pasted) "pasted via clipboard" else "copied only")
        return if (pasted) Outcome.Pasted else Outcome.Copied
    }

    private fun spaced(existing: String, start: Int, end: Int, text: String): String {
        if (start != end || text.isEmpty()) return text
        val before = existing.getOrNull(start - 1)
        val first = text.first()
        val needsSpace = before != null &&
            !before.isWhitespace() &&
            !first.isWhitespace() &&
            first !in ".,!?;:)]}\"'"
        return if (needsSpace) " $text" else text
    }
}
