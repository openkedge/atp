package io.openkedge.atp;

import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free test runner. Each registered check maps to a PDD invariant via
 * validators/validation-plan.yaml. Prints a per-check PASS/FAIL line and a
 * machine-readable discovery-log summary (evidence-requirements.yaml), exiting
 * non-zero on any failure.
 */
public final class TestMain {
    @FunctionalInterface
    interface Case {
        void run() throws Exception;
    }

    private static final List<Object[]> CASES = new ArrayList<>();

    private static void add(String name, Case c) {
        CASES.add(new Object[] {name, c});
    }

    private static void register() {
        // --- Structural: golden bytes (CV-CORE-001, schema/merkle vectors) ---
        add("ConformanceVectorTest.cvCore001ManifestCbor", ConformanceVectorTest::cvCore001ManifestCbor);
        add("ConformanceVectorTest.cvCore001SchemaDigest", ConformanceVectorTest::cvCore001SchemaDigest);
        add("ConformanceVectorTest.digestMatchesIndependentStacks", ConformanceVectorTest::digestMatchesIndependentStacks);
        add("ConformanceVectorTest.cvCore001Records", ConformanceVectorTest::cvCore001Records);
        add("ConformanceVectorTest.cvCore001Merkle", ConformanceVectorTest::cvCore001Merkle);
        add("ConformanceVectorTest.cvCore001BatchRoot", ConformanceVectorTest::cvCore001BatchRoot);
        add("ConformanceVectorTest.cvCore001Signature", ConformanceVectorTest::cvCore001Signature);
        add("ConformanceVectorTest.cvCore001BatchWire", ConformanceVectorTest::cvCore001BatchWire);
        add("SchemaVectorTest.canonicalCbor", SchemaVectorTest::canonicalCbor);
        add("SchemaVectorTest.schemaDigest", SchemaVectorTest::schemaDigest);
        add("MerkleVectorTest.merkleVectors", MerkleVectorTest::merkleVectors);
        add("RecordCodecPropertyTest.minimalVarints", RecordCodecPropertyTest::minimalVarints);
        add("RecordCodecPropertyTest.nanCanonicalization", RecordCodecPropertyTest::nanCanonicalization);
        add("RecordCodecPropertyTest.presenceBitmap", RecordCodecPropertyTest::presenceBitmap);
        add("EntityIdTest.acceptsCanonical", EntityIdTest::acceptsCanonical);
        add("EntityIdTest.rejectsMalformed", EntityIdTest::rejectsMalformed);
        add("OpaqueRefTest.structureMatchesVectors", OpaqueRefTest::structureMatchesVectors);
        add("OpaqueRefTest.rejectsMalformed", OpaqueRefTest::rejectsMalformed);
        add("SchemaBinderTest.bindsPodTransition", SchemaBinderTest::bindsPodTransition);
        add("SchemaBinderTest.rejectsUnmappableComponent", SchemaBinderTest::rejectsUnmappableComponent);
        add("SchemaBinderTest.rejectsIntentOnObservation", SchemaBinderTest::rejectsIntentOnObservation);
        add("ErrorEnvelopeTest.rejectionsCarryAtpErrCode", ErrorEnvelopeTest::rejectionsCarryAtpErrCode);

        // --- Behavioral ---
        add("DeterminismTest.repeatedEncodeIsByteIdentical", DeterminismTest::repeatedEncodeIsByteIdentical);
        add("DualPathTest.emitterEqualsCore", DualPathTest::emitterEqualsCore);
        add("ApiShapeTest.noFreeFormCanonicalEmit", ApiShapeTest::noFreeFormCanonicalEmit);
        add("ProducerStateTest.bootEpochStrictlyIncreasesAcrossRestart", ProducerStateTest::bootEpochStrictlyIncreasesAcrossRestart);
        add("ProducerStateTest.sequenceContiguousNoGaps", ProducerStateTest::sequenceContiguousNoGaps);
        add("ProducerStateTest.tripleUniqueForAllTime", ProducerStateTest::tripleUniqueForAllTime);
        add("ProducerStateTest.previousRootChainsAcceptedRoots", ProducerStateTest::previousRootChainsAcceptedRoots);
        add("ProducerStateTest.retransmitIsByteIdentical", ProducerStateTest::retransmitIsByteIdentical);
        add("BackpressureTest.fullBufferBlocksOrFailsClosedNeverDrops", BackpressureTest::fullBufferBlocksOrFailsClosedNeverDrops);
        add("CryptoTest.signVerifyRoundtrip", CryptoTest::signVerifyRoundtrip);
        add("OpaqueRefTest.slf4jBridgeRoutesProseToOpaque", OpaqueRefTest::slf4jBridgeRoutesProseToOpaque);

        // --- Operational ---
        add("ApiShapeTest.trustPathNotAppSettable", ApiShapeTest::trustPathNotAppSettable);
        add("CallPathTest.emitDoesNoNetworkIo", CallPathTest::emitDoesNoNetworkIo);
        add("DependencyScanTest.noThirdPartyImports", DependencyScanTest::noThirdPartyImports);
        add("KeyRegistryTest.conflictingBindingFailsClosed", KeyRegistryTest::conflictingBindingFailsClosed);
    }

    public static void main(String[] args) {
        register();
        int pass = 0;
        int fail = 0;
        List<String> failures = new ArrayList<>();
        for (Object[] entry : CASES) {
            String name = (String) entry[0];
            Case c = (Case) entry[1];
            try {
                c.run();
                System.out.println("[PASS] " + name);
                pass++;
            } catch (Throwable t) {
                System.out.println("[FAIL] " + name + " :: " + t.getMessage());
                failures.add(name);
                fail++;
            }
        }
        System.out.println();
        System.out.println("discovery-log: " + pass + " passed, " + fail + " failed, "
                + CASES.size() + " total");
        if (fail != 0) {
            System.out.println("failed: " + failures);
            System.exit(1);
        }
    }
}
