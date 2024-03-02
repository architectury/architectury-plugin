package dev.architectury.plugin.utils

import org.gradle.api.Action

internal object EmptyAction : Action<Nothing> {
    override fun execute(value: Nothing) {
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> cast(): Action<T> = this as Action<T>
}
