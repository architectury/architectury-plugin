package dev.architectury.plugin.utils

import org.gradle.api.Action

internal object EmptyAction : Action<Any?> {
    override fun execute(value: Any?) {
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> cast(): Action<T> = this as Action<T>
}
