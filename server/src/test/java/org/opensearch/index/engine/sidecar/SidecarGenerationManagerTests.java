/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index.engine.sidecar;

import org.opensearch.core.concurrency.OpenSearchRejectedExecutionException;
import org.opensearch.test.OpenSearchTestCase;

public class SidecarGenerationManagerTests extends OpenSearchTestCase {

    public void testAcquireGenerationIncrementsCount() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(5);
        assertEquals(1, mgr.acquireGeneration("_0", "embedding"));
        assertEquals(2, mgr.acquireGeneration("_0", "embedding"));
        assertEquals(2, mgr.getGenerationCount("_0", "embedding"));
    }

    public void testBackpressureAtLimit() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(3);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");

        expectThrows(OpenSearchRejectedExecutionException.class, () -> mgr.acquireGeneration("_0", "embedding"));
    }

    public void testDifferentFieldsIndependent() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(2);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(1, mgr.acquireGeneration("_0", "tags"));
    }

    public void testDifferentSegmentsIndependent() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(2);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(1, mgr.acquireGeneration("_1", "embedding"));
    }

    public void testMergeResetsCount() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(3);
        mgr.acquireGeneration("_0", "embedding");
        mgr.acquireGeneration("_0", "embedding");
        assertEquals(2, mgr.getGenerationCount("_0", "embedding"));

        mgr.onMergeCompleted("_0", "embedding");
        assertEquals(0, mgr.getGenerationCount("_0", "embedding"));
        assertEquals(1, mgr.acquireGeneration("_0", "embedding"));
    }

    public void testZeroLimitRejectsImmediately() {
        SidecarGenerationManager mgr = new SidecarGenerationManager(0);
        expectThrows(OpenSearchRejectedExecutionException.class, () -> mgr.acquireGeneration("_0", "embedding"));
    }
}
