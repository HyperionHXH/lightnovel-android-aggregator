package io.github.jiangyuyi.lightnovel.core.reader;

import com.github.khoben.libwoff2dec.Woff2Decoder;

final class ChapterFontDecoder {
    private ChapterFontDecoder() {}

    static byte[] decode(byte[] bytes) {
        return Woff2Decoder.INSTANCE.decodeBytes(bytes);
    }
}
