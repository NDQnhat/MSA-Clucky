import assert from 'node:assert/strict';
import { test } from 'node:test';
import { consentForPublished, isValidLegalStatus } from '../lib/legal-consent.ts';

const terms = { type: 'TERMS_OF_SERVICE', version: 7 };
const privacy = { type: 'PRIVACY_POLICY', version: 4 };

test('no published document needs no checkbox or consent payload', () => {
  assert.equal(consentForPublished(null, null, false), null);
  assert.equal(consentForPublished(null, null, true), null);
  assert.equal(isValidLegalStatus({ consentRequired: false, termsPublished: false, privacyPublished: false }), true);
});

test('one published document requires explicit acceptance of exactly its revision', () => {
  assert.equal(consentForPublished(terms, privacy, false), null);
  assert.equal(consentForPublished(terms, null, false), null);
  assert.equal(consentForPublished(null, privacy, false), null);
  assert.deepEqual(consentForPublished(terms, null, true), { accepted: true, termsVersion: 7 });
  assert.deepEqual(consentForPublished(null, privacy, true), { accepted: true, privacyVersion: 4 });
});

test('both published documents require explicit acceptance of both revisions', () => {
  assert.deepEqual(consentForPublished(terms, privacy, true),
    { accepted: true, termsVersion: 7, privacyVersion: 4 });
});

test('consent widget rejects wrong type, invalid or nonpositive revision numbers', () => {
  assert.equal(consentForPublished(privacy, terms, true), null);
  assert.equal(consentForPublished({ ...terms, version: 0 }, privacy, true), null);
  assert.equal(consentForPublished(terms, { ...privacy, version: 1.5 }, true), null);
});

test('inconsistent or legacy-only API status cannot authorize registration', () => {
  assert.equal(isValidLegalStatus({ consentRequired: false, termsPublished: true, privacyPublished: false }), false);
  assert.equal(isValidLegalStatus({ consentRequired: true, termsPublished: false, privacyPublished: false }), false);
  assert.equal(isValidLegalStatus({ consentRequired: false, documentsPublished: false }), false);
  assert.equal(isValidLegalStatus({ consentRequired: true, termsPublished: true, privacyPublished: false }), true);
  assert.equal(isValidLegalStatus({ consentRequired: true, termsPublished: false, privacyPublished: true }), true);
  assert.equal(isValidLegalStatus({ consentRequired: true, termsPublished: true, privacyPublished: true }), true);
});
