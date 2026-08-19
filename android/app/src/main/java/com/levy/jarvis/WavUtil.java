package com.levy.jarvis;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class WavUtil {
    private WavUtil() {}

    public static byte[] fromPcm16(byte[] pcm, int sampleRate, int channels) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + pcm.length);
        int byteRate = sampleRate * channels * 2;
        int dataSize = pcm.length;

        out.write(new byte[]{'R','I','F','F'});
        writeInt(out, 36 + dataSize);
        out.write(new byte[]{'W','A','V','E','f','m','t',' '});
        writeInt(out, 16);
        writeShort(out, (short)1);
        writeShort(out, (short)channels);
        writeInt(out, sampleRate);
        writeInt(out, byteRate);
        writeShort(out, (short)(channels * 2));
        writeShort(out, (short)16);
        out.write(new byte[]{'d','a','t','a'});
        writeInt(out, dataSize);
        out.write(pcm);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) throws Exception {
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array());
    }

    private static void writeShort(ByteArrayOutputStream out, short v) throws Exception {
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v).array());
    }
}
