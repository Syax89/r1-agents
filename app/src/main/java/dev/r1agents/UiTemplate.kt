package dev.r1agents

internal val uiPlaceholder = Regex("%([0-9]+)\\\$s")
internal fun uiTemplatePattern(template: String): Regex {
    var offset = 0
    val pattern = StringBuilder()
    for (match in uiPlaceholder.findAll(template)) {
        pattern.append(Regex.escape(template.substring(offset, match.range.first)))
        pattern.append("(.*?)")
        offset = match.range.last + 1
    }
    pattern.append(Regex.escape(template.substring(offset)))
    return Regex(pattern.toString(), RegexOption.DOT_MATCHES_ALL)
}
