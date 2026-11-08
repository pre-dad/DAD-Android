package org.dad.foundation

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources

/**
 * Text a model wants shown, kept as a resource id plus arguments so models never hold a Context and
 * stay unit-testable on the JVM. The UI edge turns it into a string with [asString]; tests compare
 * the values. An argument that is itself a [UiText] is resolved first, so a sentence can embed another.
 */
sealed interface UiText {
    /** Text that is already final, such as a name or a message the server wrote. */
    data class Plain(val value: String) : UiText

    class Res internal constructor(@StringRes val id: Int, val args: List<Any>) : UiText {
        override fun equals(other: Any?) = other is Res && id == other.id && args == other.args
        override fun hashCode() = 31 * id + args.hashCode()
        override fun toString() = "Res(id=$id, args=$args)"
    }

    class Plural internal constructor(@PluralsRes val id: Int, val quantity: Int, val args: List<Any>) : UiText {
        override fun equals(other: Any?) = other is Plural && id == other.id && quantity == other.quantity && args == other.args
        override fun hashCode() = 31 * (31 * id + quantity) + args.hashCode()
        override fun toString() = "Plural(id=$id, quantity=$quantity, args=$args)"
    }

    fun resolve(resources: Resources): String = when (this) {
        is Plain -> value
        is Res -> resources.getString(id, *resolveArgs(args, resources))
        is Plural -> resources.getQuantityString(id, quantity, *resolveArgs(args, resources))
    }

    companion object {
        fun res(@StringRes id: Int, vararg args: Any): UiText = Res(id, args.toList())

        /** [args] default to the quantity itself, so `plural(R.plurals.days, 3)` fills `%1$d` with 3. */
        fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): UiText =
            Plural(id, quantity, if (args.isEmpty()) listOf(quantity) else args.toList())

        fun plain(value: String): UiText = Plain(value)
    }
}

private fun resolveArgs(args: List<Any>, resources: Resources): Array<Any> =
    Array(args.size) { (args[it] as? UiText)?.resolve(resources) ?: args[it] }

/** Resolves for display; [LocalResources] makes the text update when the locale or configuration changes. */
@Composable
fun UiText.asString(): String {
    return resolve(LocalResources.current)
}

fun UiText.resolve(context: android.content.Context): String = resolve(context.resources)

/** A count as the `Int` that plural resources select by, saturating instead of wrapping. */
fun Long.toQuantity(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
