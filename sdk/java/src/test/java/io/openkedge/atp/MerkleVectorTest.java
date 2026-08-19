package io.openkedge.atp;

import io.openkedge.atp.internal.Merkle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * RFC 6962 Merkle vectors including odd leaf counts (MK-001/003/005), from
 * conformance-vectors.json (ATP-0001 §7.3).
 */
public final class MerkleVectorTest {
    private MerkleVectorTest() {}

    public static void merkleVectors() {
        Map<String, Object> cv = Vectors.load("conformance-vectors.json");
        for (Object o : Json.arr(cv.get("merkle_vectors"))) {
            Map<String, Object> mv = Json.obj(o);
            List<byte[]> records = new ArrayList<>();
            for (Object r : Json.arr(mv.get("records_hex"))) {
                records.add(Check.unhex(Json.str(r)));
            }
            Check.eqHex(Merkle.merkleRoot(records), Json.str(mv.get("merkle_root")),
                    Json.str(mv.get("id")) + " Merkle root");
        }
    }
}
