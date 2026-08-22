package com.comicatlas.api.shared.crypto;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** API 内部摘要基础能力，不承载具体业务语义。 */
@Service
public class DigestService {

    /** 计算 UTF-8 字符串的 SHA-256 十六进制摘要。 */
    public String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    /** 计算字节数组的 SHA-256 十六进制摘要。 */
    public String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256 算法", exception);
        }
    }

    /** 以固定缓冲区流式计算 SHA-256，适用于快照和文件内容。 */
    public String sha256(InputStream inputStream) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JDK 未提供 SHA-256 算法", exception);
        }
    }
}
