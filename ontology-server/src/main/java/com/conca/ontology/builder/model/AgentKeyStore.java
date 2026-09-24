package com.conca.ontology.builder.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.conca.ontology.shared.web.ApiException;

/**
 * 智能体模型密钥库：data/agent-keys/{id}.key（AES-256-GCM 加密落盘）。
 * 主密钥首次使用时自动生成于 data/agent-keys/.master-key（Base64 的 32 字节）。
 * 三重防线之一：明文密钥绝不进入 AgentDefinition（接口只回掩码，导出包只带占位符）。
 */
@Service
public class AgentKeyStore {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String MASTER_FILE = ".master-key";

    private final Path dir;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKeySpec masterKey;

    public AgentKeyStore(@Value("${app.agents.keys-dir:data/agent-keys}") String dir) {
        this.dir = Path.of(dir);
    }

    /** 保存（覆盖式）：成功后明文立即出栈 */
    public void store(String agentId, String plainKey) {
        try {
            Files.createDirectories(dir);
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, master(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainKey.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            Files.write(dir.resolve(file(agentId)), Base64.getEncoder().encode(out));
        } catch (Exception e) {
            throw new ApiException(500, "密钥加密存储失败：" + e.getMessage());
        }
    }

    /** 读取；未保存过返回 null（调用方给确定性报错） */
    public String find(String agentId) {
        Path file = dir.resolve(file(agentId));
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] data = Base64.getDecoder().decode(Files.readAllBytes(file));
            byte[] iv = new byte[GCM_IV_BYTES];
            System.arraycopy(data, 0, iv, 0, GCM_IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, master(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] plain = cipher.doFinal(data, GCM_IV_BYTES, data.length - GCM_IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(500, "密钥解密失败（" + agentId + "），可能主密钥已变更");
        }
    }

    /** 读取；未保存过抛 503（装配前统一语义） */
    public String require(String agentId) {
        String key = find(agentId);
        if (key == null || key.isBlank()) {
            throw new ApiException(503, "该智能体的模型密钥未配置，请编辑并重新填写 API Key");
        }
        return key;
    }

    public void delete(String agentId) {
        try {
            Files.deleteIfExists(dir.resolve(file(agentId)));
        } catch (IOException e) {
            // 删除失败不阻断智能体删除（残留为密文，主密钥同目录）
        }
    }

    /** 掩码回显：sk-abc...xyz → sk-ab***yz（不足 8 位全 *） */
    public static String mask(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        if (plainKey.length() < 8) {
            return "*".repeat(plainKey.length());
        }
        return plainKey.substring(0, Math.min(5, plainKey.length() - 3)) + "***"
                + plainKey.substring(plainKey.length() - 2);
    }

    /** 是否已配置密钥（详情页展示用，不回明文） */
    public boolean exists(String agentId) {
        return Files.isRegularFile(dir.resolve(file(agentId)));
    }

    // ---------------- 主密钥 ----------------

    private SecretKeySpec master() throws IOException {
        SecretKeySpec key = masterKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (masterKey == null) {
                Files.createDirectories(dir);
                Path file = dir.resolve(MASTER_FILE);
                byte[] raw;
                if (Files.isRegularFile(file)) {
                    raw = Base64.getDecoder().decode(Files.readAllBytes(file));
                } else {
                    raw = new byte[32];
                    random.nextBytes(raw);
                    Files.write(file, Base64.getEncoder().encode(raw));
                }
                if (raw.length != 32) {
                    throw new IllegalStateException("主密钥文件损坏（" + file + "），请删除后重启自动重建（需重新录入各智能体密钥）");
                }
                masterKey = new SecretKeySpec(raw, "AES");
            }
            return masterKey;
        }
    }

    private static String file(String agentId) {
        return agentId + ".key";
    }
}
