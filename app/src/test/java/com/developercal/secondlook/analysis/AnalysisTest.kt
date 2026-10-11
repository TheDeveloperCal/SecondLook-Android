package com.developercal.secondlook.analysis

import org.junit.Assert.*
import org.junit.Test

class AnalysisTest {
    private fun text(value: String) = MessageAnalyzer.analyze(value)
    private fun has(result: AnalysisResult, id: String) = assertTrue("Expected finding $id in ${result.findings.map { it.id }}", result.findings.any { it.id == id })

    @Test fun fakeParcelDeliveryFeeIsExplained() {
        val r = text("Your delivery package is held. Pay a small fee today to release your parcel.")
        has(r, "payment_demand")
        assertTrue(r.level != ResultLevel.NONE)
    }

    @Test fun bankAlertWithCodeAndPressureIsHighRisk() {
        val r = text("We are your bank. Your account will be closed today. Reply with the verification code immediately.")
        has(r, "authority_impersonation"); has(r, "credential_code"); has(r, "threat_or_consequence")
        assertEquals(ResultLevel.HIGH, r.level)
    }

    @Test fun codeRequestAloneIsNotAutomaticallyHighRisk() {
        val r = text("Please send the one-time verification code to confirm the login.")
        has(r, "credential_code")
        assertEquals(ResultLevel.CAUTION, r.level)
    }

    @Test fun governmentImpersonationAndArrestThreatAreDetected() {
        val r = text("This is the tax office. Pay with gift cards today or the police will arrest you.")
        has(r, "authority_impersonation"); has(r, "payment_method"); has(r, "threat_or_consequence")
        assertTrue(r.level == ResultLevel.HIGH || r.level == ResultLevel.SEVERAL)
    }

    @Test fun fakeTechnicalSupportRemoteAccessIsDetected() {
        val r = text("This is Microsoft Support. Download AnyDesk so we can repair your computer immediately.")
        has(r, "authority_impersonation"); has(r, "remote_access")
    }

    @Test fun cryptoAndGiftCardPaymentMethodsAreDetected() {
        has(text("Transfer your investment funds in bitcoin now."), "payment_method")
        has(text("Pay the tax bill with gift cards."), "payment_method")
    }

    @Test fun suspiciousJobFeeIsDetected() { has(text("Congratulations on the work from home job. Pay a deposit to receive your starter kit."), "job_fee") }
    @Test fun fakePrizeIsDetected() { has(text("You have won a prize. Claim your prize today."), "unrealistic_reward") }
    @Test fun romanceAndFinancialRequestIsDetected() { has(text("My online partner says our romance is real but needs an emergency loan."), "romance_financial_request") }
    @Test fun familyEmergencyIsDetected() { has(text("Your grandchild was in an accident and needs money for bail. Don't tell anyone."), "family_emergency") }

    @Test fun normalDeliveryUpdateHasNoFindings() {
        val r = text("Your parcel is scheduled to arrive Tuesday. No action is needed.")
        assertEquals(ResultLevel.NONE, r.level)
    }

    @Test fun ordinaryAppointmentReminderHasNoFindings() {
        assertEquals(ResultLevel.NONE, text("Your appointment is confirmed for Tuesday at 2 PM. Call the clinic using your usual number if you need to reschedule.").level)
    }

    @Test fun ordinaryMoneyAndDeadlineDiscussionIsNotHighRisk() {
        val r = text("The rent payment is due Friday. Please send your share when convenient.")
        assertTrue(r.level != ResultLevel.HIGH)
    }

    @Test fun legitimateSecurityReminderDoesNotRequestCode() {
        val r = text("We noticed a new sign-in. Open the official app to review your account. We will never ask you for a code.")
        assertFalse(r.findings.any { it.id == "credential_code" })
        assertFalse(r.findings.any { it.id == "threat_or_consequence" })
    }

    @Test fun urgentOrVerifyAloneDoesNotBecomeHighRisk() {
        assertNotEquals(ResultLevel.HIGH, text("Please verify your appointment urgently before tomorrow's deadline.").level)
    }

    @Test fun overlappingPressurePhrasesCountOnce() {
        val r = text("Urgent, act now, immediately, right away, final notice. Your meeting starts soon.")
        assertEquals(1, r.findings.count { it.group == FindingGroup.PRESSURE })
        assertEquals(ResultLevel.CAUTION, r.level)
    }

    @Test fun emptyInputGetsUsefulPrompt() { assertTrue(text(" \n ").summary.contains("Enter a message")) }

    @Test fun unusuallyLongTextDoesNotThrow() {
        val r = text("ordinary text ".repeat(15_000))
        assertNotNull(r)
    }

    @Test fun ordinaryUrlHasNoWarningCategory() {
        val r = UrlInspector.inspect("https://example.com/account/login")
        assertEquals(ResultLevel.NONE, r.level)
        assertTrue(r.findings.first().explanation.contains("does not open"))
    }

    @Test fun invalidAndEmptyUrlsAreHandled() {
        assertTrue(UrlInspector.inspect("").summary.contains("Enter a web address"))
        assertTrue(UrlInspector.inspect("http://[broken").findings.any { it.id == "url_malformed" })
    }

    @Test fun userInfoLinkWarnsAboutActualDestination() { has(UrlInspector.inspect("https://example.com@evil.invalid/path"), "url_userinfo") }
    @Test fun numericHostIsAWarningNotProof() {
        val r = UrlInspector.inspect("http://192.0.2.10/path")
        has(r, "url_ip_host")
        assertTrue(r.findings.single().explanation.contains("can be legitimate"))
    }

    @Test fun punycodeIsExplainedWithoutCallingItMalicious() { has(UrlInspector.inspect("https://xn--e1afmkfd.example/path"), "url_punycode") }
    @Test fun shortenedLinkExplainsHiddenDestination() { has(UrlInspector.inspect("https://bit.ly/abc123"), "url_shortened") }
    @Test fun complexHostGetsStructuralWarning() { has(UrlInspector.inspect("https://login.verify.account.secure.example.com/path"), "url_complex_host") }
    @Test fun malformedUnicodeInputDoesNotCrash() { assertNotNull(UrlInspector.inspect("https://💌.invalid/🔐")) }
    @Test fun extremelyLongUrlDoesNotCrash() { assertNotNull(UrlInspector.inspect("https://example.com/" + "x".repeat(20_000))) }

    @Test fun normalizeInternationalPhoneFormattingExactly() {
        assertEquals("+442079460000", CallDecisionEngine.normalize("+44 (20) 7946-0000"))
        assertEquals("+442079460000", CallDecisionEngine.normalize("+44 20 7946 0000"))
        assertNotEquals(CallDecisionEngine.normalize("+12025550123"), CallDecisionEngine.normalize("+442079460000"))
        assertNull(CallDecisionEngine.normalize("+44 20 7946 0000 ext 2"))
        assertNull(CallDecisionEngine.normalize("++442079460000"))
    }

    @Test fun exactUserBlocklistEntryIsBlocked() {
        assertEquals(CallAction.BLOCK, CallDecisionEngine.decide("+44 (20) 7946-0000", setOf("+442079460000"), false, CallSettings(true, true, false)))
    }

    @Test fun ordinaryUnknownNumberIsAllowed() {
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("+12025550123", setOf("+442079460000"), false, CallSettings(true, true, true)))
    }

    @Test fun unverifiedConsumerReportsNeverBlockAnUnlistedCaller() {
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide(
            number = "+12025550123",
            blocklist = emptySet(),
            privateCaller = false,
            settings = CallSettings(protectionEnabled = true, blocklistEnabled = true, privateNumbersEnabled = false),
            unverifiedReportCount = 250
        ))
    }

    @Test fun privateCallerSilencingIsOffByDefaultAndOptIn() {
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide(null, emptySet(), true, CallSettings(true, true, false)))
        assertEquals(CallAction.SILENCE, CallDecisionEngine.decide(null, emptySet(), true, CallSettings(true, true, true)))
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("withheld", emptySet(), false, CallSettings(true, true, false)))
    }

    @Test fun malformedAndEmptyNumbersFailOpen() {
        val settings = CallSettings(true, true, true)
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("123", emptySet(), false, settings))
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("", emptySet(), false, settings))
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("+442079460000", emptySet(), false, settings, failed = true))
    }

    @Test fun protectionOffAndBlocklistOffAlwaysAllow() {
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("+442079460000", setOf("+442079460000"), false, CallSettings(false, true, true)))
        assertEquals(CallAction.ALLOW, CallDecisionEngine.decide("+442079460000", setOf("+442079460000"), false, CallSettings(true, false, true)))
    }
}
