package dev.architectury.plugin.utils

import groovy.lang.Closure
import org.gradle.api.Action

class ClosureAction<T>(private val closure: Closure<*>) : Action<T> {
    override fun execute(value: T) {
        closure.delegate = value
        closure.call(value)
    }
}
