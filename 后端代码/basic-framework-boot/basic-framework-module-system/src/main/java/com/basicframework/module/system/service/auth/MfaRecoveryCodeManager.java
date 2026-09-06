package com.basicframework.module.system.service.auth;

import cn.hutool.core.codec.Base32;
import com.basicframework.module.system.dal.dataobject.auth.MfaRecoveryCodeDO;
import com.basicframework.module.system.dal.mysql.auth.MfaRecoveryCodeMapper;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 统一生成、摘要并替换用户的一次性 MFA 恢复码。 */
@Component
public class MfaRecoveryCodeManager {

    private static final int RECOVERY_CODE_BYTES = 10;
    private static final int RECOVERY_CODE_COUNT = 10;

    private final SecureRandom secureRandom = new SecureRandom();
    private final MfaRecoveryCodeMapper recoveryCodeMapper;
    private final MfaSecretCrypto secretCrypto;

    public MfaRecoveryCodeManager(MfaRecoveryCodeMapper recoveryCodeMapper, MfaSecretCrypto secretCrypto) {
        this.recoveryCodeMapper = recoveryCodeMapper;
        this.secretCrypto = secretCrypto;
    }

    /**
     * 删除旧恢复码并生成一组仅返回一次的新恢复码。
     *
     * @param userId 用户编号
     * @param now 统一写入时间
     * @return 带分隔符的明文恢复码，只允许交付给当前用户一次
     */
    @Transactional(rollbackFor = Exception.class)
    public List<String> replace(Long userId, LocalDateTime now) {
        recoveryCodeMapper.deleteByUserId(userId);
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int index = 0; index < RECOVERY_CODE_COUNT; index++) {
            String normalizedCode = Base32.encode(randomBytes()).toUpperCase(Locale.ROOT);
            String displayCode = normalizedCode.replaceAll("(.{4})(?=.)", "$1-");
            recoveryCodeMapper.insert(MfaRecoveryCodeDO.builder()
                    .userId(userId)
                    .codeHash(secretCrypto.recoveryCodeHash(normalizedCode))
                    .createTime(now)
                    .build());
            codes.add(displayCode);
        }
        return List.copyOf(codes);
    }

    /**
     * 删除用户的全部恢复码。
     *
     * @param userId 用户编号
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteByUserId(Long userId) {
        recoveryCodeMapper.deleteByUserId(userId);
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[RECOVERY_CODE_BYTES];
        secureRandom.nextBytes(bytes);
        return bytes;
    }
}
