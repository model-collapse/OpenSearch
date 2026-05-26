package org.opensearch.index.engine.sidecar;

import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class SidecarVersionBitmapFormat {

    private static final int MAGIC = 0x53564D50; // "SVMP"
    private static final int VERSION = 1;

    public static void write(SidecarVersionBitmap bitmap, OutputStream out) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(MAGIC);
        header.putInt(VERSION);
        header.putInt(bitmap.maxDoc());
        out.write(header.array());

        long[] bits = bitmap.getBits();
        ByteBuffer body = ByteBuffer.allocate(bits.length * Long.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (long word : bits) {
            body.putLong(word);
        }
        out.write(body.array());
    }

    public static SidecarVersionBitmap read(InputStream in) throws IOException {
        byte[] headerBytes = in.readNBytes(12);
        if (headerBytes.length < 12) {
            throw new IOException("Truncated bitmap header");
        }
        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        int magic = header.getInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid bitmap file magic: " + Integer.toHexString(magic));
        }
        int version = header.getInt();
        if (version != VERSION) {
            throw new IOException("Unsupported bitmap version: " + version);
        }
        int maxDoc = header.getInt();

        int numWords = FixedBitSet.bits2words(maxDoc);
        byte[] bodyBytes = in.readNBytes(numWords * Long.BYTES);
        if (bodyBytes.length < numWords * Long.BYTES) {
            throw new IOException("Truncated bitmap body");
        }
        ByteBuffer body = ByteBuffer.wrap(bodyBytes).order(ByteOrder.LITTLE_ENDIAN);
        long[] bits = new long[numWords];
        for (int i = 0; i < numWords; i++) {
            bits[i] = body.getLong();
        }

        FixedBitSet bitSet = new FixedBitSet(bits, maxDoc);
        return new SidecarVersionBitmap(bitSet, maxDoc);
    }
}
