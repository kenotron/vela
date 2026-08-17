package com.vela.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method
import java.lang.reflect.Type
import java.lang.reflect.ParameterizedType

/**
 * Compile-time-adjacent enforcement of the item-1 requirement: no LiveKit SDK
 * type may cross the [com.vela.core.domain.VoiceTransport] boundary. This test
 * reflects over the public API surface of [LiveKitVoiceTransport] - its public
 * methods' parameter types, return types, and (recursively, one level) generic
 * type arguments - and fails if any type's package name starts with
 * "io.livekit".
 *
 * This is a stronger guarantee than code review: it will fail the build the
 * moment any public method signature on this class references a vendor type,
 * regardless of whether a human reviewer notices.
 */
class LiveKitVoiceTransportVendorLeakageTest {

    @Test
    fun `public API surface of LiveKitVoiceTransport contains zero LiveKit vendor types`() {
        val clazz = LiveKitVoiceTransport::class.java
        val offendingDescriptions = mutableListOf<String>()

        val publicMethods: List<Method> = clazz.methods.filter { it.declaringClass == clazz }

        for (method in publicMethods) {
            val typesToCheck = mutableListOf<Pair<String, Type>>()
            typesToCheck.add("return type of ${method.name}" to method.genericReturnType)
            method.genericParameterTypes.forEachIndexed { index, type ->
                typesToCheck.add("parameter $index of ${method.name}" to type)
            }

            for ((description, type) in typesToCheck) {
                collectVendorTypeNames(type).forEach { vendorTypeName ->
                    offendingDescriptions.add("$description: $vendorTypeName")
                }
            }
        }

        // Also check public fields/properties (Kotlin properties compile to
        // getter methods, already covered above, but check declared fields too
        // in case of public vals backed directly by fields).
        clazz.fields.filter { it.declaringClass == clazz }.forEach { field ->
            collectVendorTypeNames(field.genericType).forEach { vendorTypeName ->
                offendingDescriptions.add("field ${field.name}: $vendorTypeName")
            }
        }

        assertTrue(
            "LiveKit vendor types leaked across the public API surface of " +
                "LiveKitVoiceTransport, violating the VoiceTransport interface " +
                "boundary contract:\n" + offendingDescriptions.joinToString("\n"),
            offendingDescriptions.isEmpty(),
        )
    }

    private fun collectVendorTypeNames(type: Type): List<String> {
        val found = mutableListOf<String>()
        val rawClassName = rawClassNameOf(type)
        if (rawClassName != null && rawClassName.startsWith("io.livekit")) {
            found.add(rawClassName)
        }
        if (type is ParameterizedType) {
            type.actualTypeArguments.forEach { arg ->
                found.addAll(collectVendorTypeNames(arg))
            }
        }
        return found
    }

    private fun rawClassNameOf(type: Type): String? = when (type) {
        is Class<*> -> type.name
        is ParameterizedType -> (type.rawType as? Class<*>)?.name
        else -> null
    }
}
