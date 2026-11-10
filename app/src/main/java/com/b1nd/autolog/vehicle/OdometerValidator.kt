package com.b1nd.autolog.vehicle

object OdometerValidator {
    fun isValid(newKm: Float?, lastValidKm: Float?): Boolean {
        if (newKm == null || newKm <= 0f || newKm.isNaN()) return false
        if (lastValidKm != null && newKm < lastValidKm) return false
        return true
    }
}
