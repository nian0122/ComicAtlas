package com.comicatlas.api.shared.crypto;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
}
