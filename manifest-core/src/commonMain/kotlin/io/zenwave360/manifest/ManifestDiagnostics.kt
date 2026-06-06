package io.zenwave360.manifest

enum class ManifestDiagnosticSeverity {
    ERROR,
    WARNING,
}

data class ManifestDiagnostic(
    val message: String,
    val severity: ManifestDiagnosticSeverity = ManifestDiagnosticSeverity.ERROR,
    val code: String? = null,
    val location: String? = null,
)

