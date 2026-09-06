package com.basicframework.server.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.hutool.core.codec.Base32;
import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.dal.dataobject.user.AdminUserDO;
import com.basicframework.module.system.dal.redis.RedisKeyConstants;
import com.basicframework.module.system.dal.redis.auth.MfaStepUpRedisDAO;
import com.basicframework.module.system.enums.logger.LoginLogTypeEnum;
import com.basicframework.module.system.service.auth.MfaFactorManagementService;
import com.basicframework.module.system.service.auth.MfaService;
import com.basicframework.module.system.service.auth.dto.AuthLoginResultDTO;
import com.basicframework.module.system.service.auth.dto.MfaTotpSetupDTO;
import com.basicframework.module.system.service.auth.dto.MfaVerifiedPrincipalDTO;
import com.basicframework.module.system.service.user.AdminUserService;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实 MySQL/Redis 验证 MFA 注册、恢复码、step-up 与因子管理持久化。 */
class PersistenceMfaIT extends AbstractPersistenceIntegrationTest {

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private MfaService mfaService;

    @Autowired
    private MfaFactorManagementService mfaFactorManagementService;

    @Autowired
    private MfaStepUpRedisDAO mfaStepUpRedisDAO;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void mfaPersistenceAndShortLivedState_succeedAgainstRealServices() throws GeneralSecurityException {
        verifyMfaEnrollmentEncryptionAndRecoveryCodeConsumption();
        verifyMfaStepUpState();
        verifyMfaFactorManagement();
    }

    private void verifyMfaEnrollmentEncryptionAndRecoveryCodeConsumption() throws GeneralSecurityException {
        AdminUserDO user = adminUserService.getUser(1L);
        AuthLoginResultDTO enrollment =
                mfaService.beginAuthentication(user, user.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
        assertThat(enrollment.getMfaRequired()).isTrue();
        assertThat(enrollment.getMfaEnrollmentRequired()).isTrue();
        assertThat(enrollment.getAccessToken()).isNull();

        MfaTotpSetupDTO setup = mfaService.beginRequiredTotpEnrollment(enrollment.getMfaToken());
        String currentCode = generateTotpCode(setup.getSecret(), System.currentTimeMillis() / 1000 / 30);
        MfaVerifiedPrincipalDTO enrolled =
                mfaService.completeRequiredTotpEnrollment(setup.getEnrollmentToken(), currentCode);
        assertThat(enrolled.getRecoveryCodes()).hasSize(10);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT secret_ciphertext FROM system_user_mfa_factor WHERE user_id = 1", String.class))
                .doesNotContain(setup.getSecret());
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_user_mfa_recovery_code WHERE user_id = 1", Integer.class))
                .isEqualTo(10);

        String recoveryCode = enrolled.getRecoveryCodes().get(0);
        AuthLoginResultDTO recoveryLogin =
                mfaService.beginAuthentication(user, user.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
        assertThat(mfaService
                        .verifyRecoveryCode(recoveryLogin.getMfaToken(), recoveryCode)
                        .getUserId())
                .isEqualTo(1L);

        AuthLoginResultDTO replay =
                mfaService.beginAuthentication(user, user.getUsername(), LoginLogTypeEnum.LOGIN_USERNAME);
        assertThatThrownBy(() -> mfaService.verifyRecoveryCode(replay.getMfaToken(), recoveryCode))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("MFA 验证码不正确或已使用");
    }

    private void verifyMfaStepUpState() {
        String accessToken = "integration-access-token-secret";
        String tokenHash = MfaStepUpRedisDAO.tokenHash(accessToken);
        String redisKey = RedisKeyConstants.MFA_STEP_UP.formatted(tokenHash);
        try {
            mfaStepUpRedisDAO.set(accessToken, 1L);

            assertThat(mfaStepUpRedisDAO.matches(accessToken, 1L)).isTrue();
            assertThat(mfaStepUpRedisDAO.matches(accessToken, 2L)).isFalse();
            assertThat(mfaStepUpRedisDAO.matches("another-access-token", 1L)).isFalse();
            assertThat(stringRedisTemplate.getExpire(redisKey)).isPositive();
            assertThat(stringRedisTemplate.hasKey(RedisKeyConstants.MFA_STEP_UP.formatted(accessToken)))
                    .isFalse();
        } finally {
            stringRedisTemplate.delete(redisKey);
        }
    }

    private void verifyMfaFactorManagement() throws GeneralSecurityException {
        String oldCiphertext = jdbcTemplate.queryForObject(
                "SELECT secret_ciphertext FROM system_user_mfa_factor WHERE user_id = 1 AND factor_type = 2",
                String.class);
        MfaTotpSetupDTO rotation = mfaFactorManagementService.beginTotpEnrollment(1L, "admin");
        String currentCode = generateTotpCode(rotation.getSecret(), System.currentTimeMillis() / 1000 / 30);

        assertThat(mfaFactorManagementService.completeTotpEnrollment(1L, rotation.getEnrollmentToken(), currentCode))
                .hasSize(10);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT secret_ciphertext FROM system_user_mfa_factor WHERE user_id = 1 AND factor_type = 2",
                        String.class))
                .isNotEqualTo(oldCiphertext)
                .doesNotContain(rotation.getSecret());

        assertThat(mfaFactorManagementService.resetRecoveryCodes(1L)).hasSize(10);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM system_user_mfa_recovery_code WHERE user_id = 1", Integer.class))
                .isEqualTo(10);
    }

    private static String generateTotpCode(String base32Secret, long step) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(Base32.decode(base32Secret), "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 0x0f;
        int binary = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        return String.format("%06d", binary % 1_000_000);
    }
}
