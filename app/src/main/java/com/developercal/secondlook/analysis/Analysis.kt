package com.developercal.secondlook.analysis

import java.net.URI
import java.net.IDN

enum class FindingGroup { PRESSURE, AUTHORITY, CREDENTIALS, PAYMENT, THREATS, PROMISES, CONTEXT, LINK }
enum class ResultLevel { HIGH, SEVERAL, CAUTION, NONE }

data class Finding(
    val id: String,
    val title: String,
    val explanation: String,
    val severity: Int,
    val group: FindingGroup
)

data class AnalysisResult(
    val level: ResultLevel,
    val summary: String,
    val findings: List<Finding>,
    val recommendations: List<String>
)

object ResultCategorizer {
    fun category(findings: List<Finding>): ResultLevel {
        val actionable = findings.filter { it.severity > 0 }
        if (actionable.isEmpty()) return ResultLevel.NONE
        val groups = actionable.map { it.group }.distinct().size
        val score = actionable.sumOf { it.severity }
        return when {
            actionable.any { it.group == FindingGroup.CREDENTIALS && it.severity >= 3 } &&
                (actionable.any { it.group == FindingGroup.PAYMENT } || actionable.any { it.group == FindingGroup.PRESSURE }) -> ResultLevel.HIGH
            score >= 7 && groups >= 3 -> ResultLevel.HIGH
            score >= 4 && groups >= 2 -> ResultLevel.SEVERAL
            else -> ResultLevel.CAUTION
        }
    }

    fun summarize(findings: List<Finding>): AnalysisResult {
        val level = category(findings)
        val summary = when (level) {
            ResultLevel.HIGH -> "Several strong warning signs appear together. Pause and verify through a separate, trusted channel."
            ResultLevel.SEVERAL -> "More than one warning sign was found. Take time to verify the request before responding."
            ResultLevel.CAUTION -> "A warning sign deserves a closer look. Check the details independently before acting."
            ResultLevel.NONE -> "No obvious warning signs were found in this text. That does not verify who sent it or make a link safe."
        }
        return AnalysisResult(level, summary, findings, listOf(
            "Never share a password, one-time code, or recovery code with someone who contacts you.",
            "Do not transfer money or install remote-access software under pressure.",
            "Contact the organization using a number or website you find independently.",
            "For an urgent family request, verify it through a separate communication channel."
        ))
    }
}

/** Deterministic English-first rules. Each group contributes at most one finding to avoid phrase pileups. */
object MessageAnalyzer {
    private data class Rule(val id: String, val group: FindingGroup, val severity: Int, val title: String, val why: String, val patterns: List<Regex>)

    private val rules = listOf(
        Rule("credential_code", FindingGroup.CREDENTIALS, 3, "A code or credential is requested", "One-time codes, passwords, and recovery codes can give someone access to an account. Legitimate support staff should not ask you to read them a sign-in code.", listOf(Regex("\\b(?:send|share|tell|read|reply with|provide|confirm|request(?:ed)?|ask(?:ed)? for)\\b.{0,70}\\b(?:one[- ]time|verification|security|login|sign[- ]in|auth(?:entication)?)\\s*(?:code|passcode|otp)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:password|passphrase|recovery code)\\b.{0,50}\\b(?:send|share|reply|provide|confirm|request|ask)\\b", RegexOption.IGNORE_CASE))),
        Rule("payment_method", FindingGroup.PAYMENT, 3, "An unusual payment method is requested", "Gift cards, cryptocurrency, and wire transfers are difficult to reverse and are not normal ways for an agency or reputable company to collect a surprise fee.", listOf(Regex("\\b(?:gift cards?|crypto(?:currency)?|bitcoin|wire transfer|money transfer|western union|moneygram)\\b", RegexOption.IGNORE_CASE))),
        Rule("payment_demand", FindingGroup.PAYMENT, 2, "A payment is tied to an unexpected request", "An unexpected fee or urgent transfer request should be checked directly with the organization before paying.", listOf(Regex("\\b(?:pay|payment|fee|charge|transfer|deposit|send money|refund)\\b.{0,70}\\b(?:delivery|package|account|release|claim|today|now|urgent|prize|job|investment)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:delivery|package|account|release|claim|prize|job)\\b.{0,70}\\b(?:pay|payment|fee|charge|transfer|deposit)\\b", RegexOption.IGNORE_CASE))),
        Rule("authority_impersonation", FindingGroup.AUTHORITY, 2, "A trusted organization or authority is claimed", "Scammers often borrow an organization's identity to make a request sound official. Verify unexpected requests using contact details you find independently.", listOf(Regex("\\b(?:we are|this is|calling from|message from|on behalf of)\\b.{0,35}\\b(?:bank|revenue service|tax office|police|court|government|customs|immigration|social security|tech(?:nical)? support|microsoft support|apple support|delivery service|postal service)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:bank|police|government|tax office|microsoft support|apple support)\\s+(?:here|speaking|contacting you)\\b", RegexOption.IGNORE_CASE))),
        Rule("threat_or_consequence", FindingGroup.THREATS, 2, "A serious consequence is threatened", "Threats of arrest, account closure, lost benefits, or legal action are often used to stop people from checking a request calmly.", listOf(Regex("\\b(?:arrest|legal action|lawsuit|account (?:will be )?(?:closed|suspended|locked)|lose your benefits|benefits (?:will be )?(?:stopped|suspended)|warrant|prosecution|service will be disconnected)\\b", RegexOption.IGNORE_CASE))),
        Rule("urgency", FindingGroup.PRESSURE, 1, "There is pressure to act quickly", "A deadline by itself does not prove fraud, but pressure that discourages independent verification is a reason to slow down.", listOf(Regex("\\b(?:act now|immediately|within (?:\\d+|one|two|24) hours|final notice|expires? today|urgent|right away|do not delay|last chance)\\b", RegexOption.IGNORE_CASE))),
        Rule("remote_access", FindingGroup.CREDENTIALS, 3, "Remote access or device control is requested", "An unsolicited caller or message asking you to install remote-control software can expose files, accounts, and payment apps.", listOf(Regex("\\b(?:install|download|open)\\b.{0,50}\\b(?:remote access|anydesk|teamviewer|screen sharing|remote desktop)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:remote access|anydesk|teamviewer|screen sharing)\\b", RegexOption.IGNORE_CASE))),
        Rule("unrealistic_reward", FindingGroup.PROMISES, 2, "A prize or unusually large return is promised", "Unexpected prizes and guaranteed high returns are commonly used to draw people into sharing details or paying an advance fee.", listOf(Regex("\\b(?:you (?:have|'ve) won|winner|claim your prize|guaranteed returns?|risk[- ]free (?:profit|investment)|double your money|free (?:gift|iphone|holiday))\\b", RegexOption.IGNORE_CASE))),
        Rule("platform_bypass", FindingGroup.CONTEXT, 2, "The conversation is moved off a trusted platform", "A request to leave a marketplace, payment service, or dating platform can remove protections and make a dispute harder to resolve.", listOf(Regex("\\b(?:move|continue|take) (?:this|the conversation|payment) (?:to|over) (?:whatsapp|telegram|signal|text|email|outside the platform)\\b", RegexOption.IGNORE_CASE))),
        Rule("romance_financial_request", FindingGroup.CONTEXT, 2, "A personal relationship is paired with a money request", "A new online relationship combined with an emergency loan, travel fee, or investment request warrants independent verification.", listOf(Regex("\\b(?:online (?:friend|partner)|dating|romance|love)\\b.{0,90}\\b(?:loan|money|investment|travel fee|emergency)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:loan|money|investment|travel fee|emergency)\\b.{0,90}\\b(?:online (?:friend|partner)|dating|romance|love)\\b", RegexOption.IGNORE_CASE))),
        Rule("family_emergency", FindingGroup.CONTEXT, 2, "A family emergency is used to request secrecy or money", "A claim that a relative is in trouble can be emotionally powerful. Verify by calling them or another family member using a number you already know.", listOf(Regex("\\b(?:grandchild|daughter|son|family member|relative)\\b.{0,80}\\b(?:arrested|accident|emergency|bail|money)\\b", RegexOption.IGNORE_CASE), Regex("\\b(?:don't|do not) tell (?:anyone|your family)\\b", RegexOption.IGNORE_CASE))),
        Rule("job_fee", FindingGroup.CONTEXT, 2, "A job offer is paired with an upfront fee", "A legitimate employer generally does not require a candidate to pay an unexpected fee or deposit to receive a job.", listOf(Regex("\\b(?:job|position|employment|work from home)\\b.{0,80}\\b(?:fee|deposit|pay|payment|buy equipment)\\b", RegexOption.IGNORE_CASE)))
    )

    fun analyze(text: String): AnalysisResult {
        if (text.isBlank()) return AnalysisResult(ResultLevel.NONE, "Enter a message or call details to check for warning signs.", emptyList(), emptyList())
        val bounded = text.take(100_000)
        val findings = rules.mapNotNull { rule ->
            if (rule.patterns.any { it.containsMatchIn(bounded) }) Finding(rule.id, rule.title, rule.why, rule.severity, rule.group) else null
        }.distinctBy { it.group }
        return ResultCategorizer.summarize(findings)
    }
}

object UrlInspector {
    private val shorteners = setOf("bit.ly", "t.co", "tinyurl.com", "is.gd", "lnkd.in", "shorturl.at", "ow.ly", "cutt.ly")
    private val ipV4 = Regex("^(?:\\d{1,3}\\.){3}\\d{1,3}$")

    fun inspect(input: String): AnalysisResult {
        if (input.isBlank()) return AnalysisResult(ResultLevel.NONE, "Enter a web address to inspect its structure.", emptyList(), emptyList())
        val raw = input.trim().take(16_384)
        val findings = mutableListOf<Finding>()
        val uri = try { URI(if (raw.contains("://")) raw else "https://$raw") } catch (_: Exception) { null }
        if (uri == null || uri.scheme !in setOf("http", "https") || uri.rawAuthority.isNullOrBlank()) {
            findings += Finding("url_malformed", "The address could not be parsed as a web URL", "Do not open an address you cannot read or recognize. A malformed address is not proof of fraud, but its destination cannot be checked reliably.", 2, FindingGroup.LINK)
            return ResultCategorizer.summarize(findings)
        }
        if (uri.rawUserInfo != null || uri.rawAuthority?.contains('@') == true) {
            findings += Finding("url_userinfo", "The address contains user information before the host", "Text before an @ symbol in a URL can make a different destination look familiar. Check the actual host that follows it.", 3, FindingGroup.LINK)
        }
        val host = try { uri.host ?: uri.rawAuthority!!.substringAfterLast('@').substringBefore(':') } catch (_: Exception) { "" }
        if (host.isBlank()) findings += Finding("url_host_missing", "The web address has no clear host name", "Without a clearly parsed host name, the destination is difficult to verify.", 2, FindingGroup.LINK)
        val lower = host.lowercase()
        if (ipV4.matches(lower) || lower.startsWith("[")) findings += Finding("url_ip_host", "The address uses a numeric network host", "A numeric host can be legitimate, but it is harder to connect to a recognizable organization. Check the sender through another route.", 2, FindingGroup.LINK)
        if (lower.split('.').count { it.isNotBlank() } >= 5 || lower.count { it == '-' } >= 4) findings += Finding("url_complex_host", "The host name has an unusually complex structure", "Extra subdomain levels or many hyphens can make the destination harder to read. They do not establish who owns the site.", 1, FindingGroup.LINK)
        if (lower.startsWith("xn--") || lower.split('.').any { it.startsWith("xn--") } || host.any { it.code > 127 }) {
            val readable = try { IDN.toUnicode(host) } catch (_: Exception) { host }
            findings += Finding("url_punycode", "The host uses an internationalized domain label", "Punycode represents non-ASCII characters in a domain name. Internationalized names are used by legitimate sites too; compare the spelling with the organization you expect. ($readable)", 1, FindingGroup.LINK)
        }
        if (lower in shorteners) findings += Finding("url_shortened", "This is a shortened link", "A short link hides its final destination. That is not proof it is malicious, but only open it if you trust the person and were expecting it.", 1, FindingGroup.LINK)
        if (raw.length >= 500) findings += Finding("url_long", "The address is unusually long", "Long tracking and redirect parameters can obscure where a link leads. This inspection does not follow redirects.", 1, FindingGroup.LINK)
        if (findings.isEmpty()) {
            findings += Finding("url_no_structure_flags", "No obvious structural warning signs found", "This only checks the address format. It does not open the site, verify its owner, or check its current reputation.", 0, FindingGroup.LINK)
        }
        return ResultCategorizer.summarize(findings.distinctBy { it.id })
    }
}

enum class CallAction { ALLOW, BLOCK, SILENCE }
data class CallSettings(val protectionEnabled: Boolean, val blocklistEnabled: Boolean, val privateNumbersEnabled: Boolean)

object CallDecisionEngine {
    fun normalize(number: String?): String? {
        if (number.isNullOrBlank()) return null
        val trimmed = number.trim()
        if (trimmed.equals("private", true) || trimmed.equals("unknown", true) || trimmed.equals("withheld", true) || trimmed.equals("restricted", true)) return null
        val plus = trimmed.startsWith('+')
        val digits = trimmed.filter(Char::isDigit)
        if (digits.length !in 7..15) return null
        return (if (plus) "+" else "") + digits
    }

    fun decide(number: String?, blocklist: Set<String>, privateCaller: Boolean, settings: CallSettings, failed: Boolean = false): CallAction {
        if (failed || !settings.protectionEnabled) return CallAction.ALLOW
        if (privateCaller || isPrivateLabel(number.orEmpty())) return if (settings.privateNumbersEnabled) CallAction.SILENCE else CallAction.ALLOW
        val normalized = normalize(number) ?: return if (settings.privateNumbersEnabled && isPrivateLabel(number.orEmpty())) CallAction.SILENCE else CallAction.ALLOW
        val normalizedList = blocklist.mapNotNull(::normalize).toSet()
        if (settings.blocklistEnabled && normalized in normalizedList) return CallAction.BLOCK
        return CallAction.ALLOW
    }

    private fun isPrivateLabel(number: String) = number.trim().lowercase() in setOf("private", "unknown", "withheld", "restricted")
}
