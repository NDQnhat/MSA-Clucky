package com.clucky.identityservice.dto.auth;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Positive;

/** Client's explicit acknowledgement of whichever legal revisions are published. */
public record LegalConsentRequest(
        @AssertTrue Boolean accepted,
        @Positive Integer termsVersion,
        @Positive Integer privacyVersion
) {}
