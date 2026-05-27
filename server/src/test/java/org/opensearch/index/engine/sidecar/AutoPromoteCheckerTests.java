/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.test.OpenSearchTestCase;

public class AutoPromoteCheckerTests extends OpenSearchTestCase {

    public void testBelowThresholdDoesNotPromote() {
        AutoPromoteChecker checker = new AutoPromoteChecker(0.30f);
        assertFalse(checker.shouldPromote(100, 1000)); // 10%
        assertFalse(checker.shouldPromote(299, 1000)); // 29.9%
        assertFalse(checker.shouldPromote(300, 1000)); // 30% — not exceeded (> not >=)
    }

    public void testAboveThresholdPromotes() {
        AutoPromoteChecker checker = new AutoPromoteChecker(0.30f);
        assertTrue(checker.shouldPromote(301, 1000)); // 30.1%
        assertTrue(checker.shouldPromote(500, 1000)); // 50%
        assertTrue(checker.shouldPromote(1000, 1000)); // 100%
    }

    public void testEmptySegmentDoesNotPromote() {
        AutoPromoteChecker checker = new AutoPromoteChecker(0.30f);
        assertFalse(checker.shouldPromote(100, 0));
    }

    public void testCumulativeCheck() {
        AutoPromoteChecker checker = new AutoPromoteChecker(0.30f);
        // Existing 200 docs in sidecar + 150 new = 350/1000 = 35% > 30%
        assertTrue(checker.shouldPromoteCumulative(200, 150, 1000));
        // Existing 100 docs + 100 new = 200/1000 = 20% < 30%
        assertFalse(checker.shouldPromoteCumulative(100, 100, 1000));
    }

    public void testCustomThreshold() {
        AutoPromoteChecker checker = new AutoPromoteChecker(0.50f);
        assertFalse(checker.shouldPromote(400, 1000)); // 40% < 50%
        assertTrue(checker.shouldPromote(501, 1000)); // 50.1% > 50%
    }

    public void testDefaultThreshold() {
        AutoPromoteChecker checker = new AutoPromoteChecker();
        assertEquals(0.30f, checker.threshold(), 0.001f);
    }

    public void testInvalidThresholdThrows() {
        expectThrows(IllegalArgumentException.class, () -> new AutoPromoteChecker(0));
        expectThrows(IllegalArgumentException.class, () -> new AutoPromoteChecker(-0.1f));
        expectThrows(IllegalArgumentException.class, () -> new AutoPromoteChecker(1.1f));
    }

    public void testExactlyOneIsValid() {
        AutoPromoteChecker checker = new AutoPromoteChecker(1.0f);
        assertFalse(checker.shouldPromote(999, 1000)); // 99.9% — not > 100%
        assertFalse(checker.shouldPromote(1000, 1000)); // 100% — not > 100%
    }
}
