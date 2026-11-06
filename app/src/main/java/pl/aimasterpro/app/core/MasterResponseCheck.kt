package pl.aimasterpro.app.core

import java.util.Locale

/** Cheap structural checks only; flags uncertainty instead of silently rewriting factual content. */
object MasterResponseCheck {
    data class Result(val passed: Boolean, val warnings: List<String>)
    fun evaluate(
        prompt: String,
        answer: String,
        verificationAssessment: VerificationAssessment? = null
    ): Result {
        val q = prompt.lowercase(Locale.ROOT)
        val a = answer.trim()
        val warnings = mutableListOf<String>()
        if (a.isBlank()) warnings += "Odpowiedź jest pusta."
        if (a.length < 40 && q.length > 100) warnings += "Odpowiedź może być zbyt krótka względem złożoności pytania."
        val asksForSteps = listOf("krok po kroku", "instrukcj", "jak zrobić", "jak zrobic", "wdroż", "wdroz").any(q::contains)
        if (asksForSteps && !listOf("1.", "1)", "krok", "najpierw", "następnie", "nastepnie").any(a.lowercase(Locale.ROOT)::contains)) warnings += "Użytkownik prosi o instrukcję, ale odpowiedź nie ma wyraźnej sekwencji kroków."
        val asksForComparison = listOf("porównaj", "porownaj", "różnic", "roznic", "lepszy", "najlepszy").any(q::contains)
        if (asksForComparison && !listOf(" vs ", "różnic", "roznic", "porówn", "porown", "zalet", "wad").any(a.lowercase(Locale.ROOT)::contains)) warnings += "Możliwy brak właściwego porównania."
        if (a.contains("[TODO]", true) || a.contains("<uzupełnij>", true)) warnings += "Odpowiedź zawiera niewypełniony placeholder."
        val answerLower = a.lowercase(Locale.ROOT)
        val claimsTestSuccess = listOf(
            "testy przeszły", "wszystkie testy przeszły", "zweryfikowano testami", "tests passed"
        ).any(answerLower::contains)
        val claimsCompilationSuccess = listOf(
            "kompilacja zakończyła się powodzeniem", "compiled successfully"
        ).any(answerLower::contains)
        val claimsAndroidBuildSuccess = listOf(
            "build zakończył się powodzeniem", "build pass", "apk został zbudowany", "build succeeded"
        ).any(answerLower::contains)
        val testKinds = setOf(
            VerificationEvidenceKind.UNIT_TESTS,
            VerificationEvidenceKind.INTEGRATION_TESTS,
            VerificationEvidenceKind.ROBOLECTRIC
        )
        val compilationEvidencePassed = verificationAssessment?.status == VerificationAssessmentStatus.PASS &&
            verificationAssessment.requiredKinds.contains(VerificationEvidenceKind.COMPILATION)
        val androidBuildEvidencePassed = verificationAssessment?.status == VerificationAssessmentStatus.PASS &&
            verificationAssessment.requiredKinds.contains(VerificationEvidenceKind.ANDROID_BUILD)
        val testEvidencePassed = verificationAssessment?.status == VerificationAssessmentStatus.PASS &&
            verificationAssessment.requiredKinds.any { it in testKinds }
        val unsupportedTestClaim = claimsTestSuccess && !testEvidencePassed
        val unsupportedCompilationClaim = claimsCompilationSuccess && !compilationEvidencePassed
        val unsupportedAndroidBuildClaim = claimsAndroidBuildSuccess && !androidBuildEvidencePassed
        if (unsupportedTestClaim || unsupportedCompilationClaim || unsupportedAndroidBuildClaim) {
            val reason = when (verificationAssessment?.status) {
                VerificationAssessmentStatus.FAIL -> "dostępne dowody zawierają FAIL"
                VerificationAssessmentStatus.INCOMPLETE -> "zestaw dowodów jest niekompletny"
                VerificationAssessmentStatus.NOT_RUN -> "weryfikacja ma status NOT_RUN"
                null -> "nie przekazano dowodu wykonania"
                VerificationAssessmentStatus.PASS -> "PASS dotyczy innego rodzaju kontroli niż deklarowany test/build"
            }
            val claimType = listOfNotNull(
                "testów".takeIf { unsupportedTestClaim },
                "kompilacji".takeIf { unsupportedCompilationClaim },
                "builda Android".takeIf { unsupportedAndroidBuildClaim }
            ).joinToString(" i ")
            warnings += "Odpowiedź deklaruje wykonanie $claimType, ale $reason. Nie przedstawiaj tej deklaracji jako potwierdzonego wyniku."
        }
        return Result(warnings.isEmpty(), warnings)
    }
}
