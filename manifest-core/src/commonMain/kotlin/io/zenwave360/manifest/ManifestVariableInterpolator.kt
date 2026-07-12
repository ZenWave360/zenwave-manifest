package io.zenwave360.manifest

data class ManifestInterpolationResult(
    val value: String,
    val unresolvedVariables: List<String> = emptyList(),
)

object ManifestVariableInterpolator {
    private val placeholderPattern = Regex("""[${'$'}][{]([^}]*)[}]""")

    fun interpolate(value: String, properties: Map<String, String>): ManifestInterpolationResult {
        val unresolved = linkedSetOf<String>()
        var resolved = value
        placeholderPattern.findAll(value).toList().forEach { match ->
            val name = match.groupValues[1]
            val replacement = properties[name]
            if (replacement == null) {
                unresolved += name
            } else {
                resolved = resolved.replace(match.value, replacement)
            }
        }
        return ManifestInterpolationResult(
            value = resolved,
            unresolvedVariables = unresolved.toList()
        )
    }
}
