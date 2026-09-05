package com.basicframework.module.system.service.auth;

import static com.basicframework.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_DISABLED;
import static com.basicframework.module.system.enums.ErrorCodeConstants.AUTH_MFA_WEBAUTHN_INVALID;

import com.basicframework.framework.common.exception.ServiceException;
import com.basicframework.module.system.config.MfaProperties;
import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.AuthenticatorSelectionCriteria;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.ResidentKeyRequirement;
import com.yubico.webauthn.data.UserIdentity;
import com.yubico.webauthn.data.UserVerificationRequirement;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Yubico WebAuthn ceremony 适配层，不承担挑战存储或业务会话签发。
 *
 * <p>部署未启用或 Relying Party 未就绪时保留 {@code AUTH_MFA_DISABLED}；协议解析与验签失败统一映射为无效响应，避免泄露细节。
 */
@Service
@Slf4j
public class WebAuthnService {

    private static final int MAX_CREDENTIAL_JSON_LENGTH = 65_536;

    private final MfaProperties properties;
    private final ObjectProvider<RelyingParty> relyingPartyProvider;

    public WebAuthnService(MfaProperties properties, ObjectProvider<RelyingParty> relyingPartyProvider) {
        this.properties = properties;
        this.relyingPartyProvider = relyingPartyProvider;
    }

    public CeremonyOptions startRegistration(Long userId, String displayName, byte[] userHandle) {
        try {
            UserIdentity user = UserIdentity.builder()
                    .name(String.valueOf(userId))
                    .displayName(displayName)
                    .id(new ByteArray(userHandle))
                    .build();
            PublicKeyCredentialCreationOptions request = relyingParty()
                    .startRegistration(StartRegistrationOptions.builder()
                            .user(user)
                            .authenticatorSelection(AuthenticatorSelectionCriteria.builder()
                                    .residentKey(ResidentKeyRequirement.PREFERRED)
                                    .userVerification(UserVerificationRequirement.REQUIRED)
                                    .build())
                            .timeout(ceremonyTimeoutMillis())
                            .build());
            return new CeremonyOptions(request.toCredentialsCreateJson(), request.toJson());
        } catch (ServiceException businessException) {
            throw businessException;
        } catch (Exception startFailure) {
            log.warn(
                    "[startRegistration][WebAuthn 注册 ceremony 创建失败，exception({})]",
                    startFailure.getClass().getSimpleName());
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    public RegistrationOutcome finishRegistration(String requestJson, String credentialJson) {
        validateCredentialJson(credentialJson);
        RelyingParty relyingParty = relyingParty();
        try {
            PublicKeyCredentialCreationOptions request = PublicKeyCredentialCreationOptions.fromJson(requestJson);
            RegistrationResult result = relyingParty.finishRegistration(FinishRegistrationOptions.builder()
                    .request(request)
                    .response(PublicKeyCredential.parseRegistrationResponseJson(credentialJson))
                    .build());
            return toRegistrationOutcome(result);
        } catch (Exception verificationFailure) {
            log.warn(
                    "[finishRegistration][WebAuthn 注册响应验证失败，exception({})]",
                    verificationFailure.getClass().getSimpleName());
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    public CeremonyOptions startAssertion(Long userId) {
        try {
            AssertionRequest request = relyingParty()
                    .startAssertion(StartAssertionOptions.builder()
                            .username(String.valueOf(userId))
                            .userVerification(UserVerificationRequirement.REQUIRED)
                            .timeout(ceremonyTimeoutMillis())
                            .build());
            return new CeremonyOptions(request.toCredentialsGetJson(), request.toJson());
        } catch (ServiceException businessException) {
            throw businessException;
        } catch (Exception startFailure) {
            log.warn(
                    "[startAssertion][WebAuthn 认证 ceremony 创建失败，exception({})]",
                    startFailure.getClass().getSimpleName());
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    public AssertionOutcome finishAssertion(String requestJson, String credentialJson) {
        validateCredentialJson(credentialJson);
        RelyingParty relyingParty = relyingParty();
        try {
            AssertionRequest request = AssertionRequest.fromJson(requestJson);
            AssertionResult result = relyingParty.finishAssertion(FinishAssertionOptions.builder()
                    .request(request)
                    .response(PublicKeyCredential.parseAssertionResponseJson(credentialJson))
                    .build());
            return toAssertionOutcome(result);
        } catch (Exception verificationFailure) {
            log.warn(
                    "[finishAssertion][WebAuthn 认证响应验证失败，exception({})]",
                    verificationFailure.getClass().getSimpleName());
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    private RelyingParty relyingParty() {
        if (!properties.getWebauthn().isEnabled()) {
            throw exception(AUTH_MFA_DISABLED);
        }
        RelyingParty relyingParty = relyingPartyProvider.getIfAvailable();
        if (relyingParty == null) {
            throw exception(AUTH_MFA_DISABLED);
        }
        return relyingParty;
    }

    private long ceremonyTimeoutMillis() {
        return Math.max(1L, properties.getChallengeTtl().toMillis());
    }

    private static void validateCredentialJson(String credentialJson) {
        if (credentialJson == null
                || credentialJson.isBlank()
                || credentialJson.length() > MAX_CREDENTIAL_JSON_LENGTH) {
            throw exception(AUTH_MFA_WEBAUTHN_INVALID);
        }
    }

    /** 将 Yubico 的实验性备份标志收口在供应商适配层，业务服务只消费内部结果。 */
    @SuppressWarnings("deprecation")
    private static RegistrationOutcome toRegistrationOutcome(RegistrationResult result) {
        return new RegistrationOutcome(
                result.isUserVerified(),
                result.getKeyId().getId().getBytes(),
                result.getPublicKeyCose().getBytes(),
                result.getSignatureCount(),
                result.isBackupEligible(),
                result.isBackedUp(),
                result.getKeyId().getTransports().orElseGet(java.util.Collections::emptySortedSet).stream()
                        .map(AuthenticatorTransport::getId)
                        .sorted()
                        .toList());
    }

    @SuppressWarnings("deprecation")
    private static AssertionOutcome toAssertionOutcome(AssertionResult result) {
        return new AssertionOutcome(
                result.isSuccess(),
                result.isUserVerified(),
                result.isSignatureCounterValid(),
                result.getUsername(),
                result.getCredential().getCredentialId().getBytes(),
                result.getSignatureCount(),
                result.isBackedUp());
    }

    /** 浏览器可消费的 options 与服务端后续验证所需的原始请求。 */
    public record CeremonyOptions(String browserOptionsJson, String requestJson) {}

    public record RegistrationOutcome(
            boolean userVerified,
            byte[] credentialId,
            byte[] publicKeyCose,
            long signatureCount,
            boolean backupEligible,
            boolean backupState,
            List<String> transports) {

        public RegistrationOutcome {
            credentialId = credentialId.clone();
            publicKeyCose = publicKeyCose.clone();
            transports = List.copyOf(transports);
        }

        @Override
        public byte[] credentialId() {
            return credentialId.clone();
        }

        @Override
        public byte[] publicKeyCose() {
            return publicKeyCose.clone();
        }
    }

    public record AssertionOutcome(
            boolean success,
            boolean userVerified,
            boolean signatureCounterValid,
            String username,
            byte[] credentialId,
            long signatureCount,
            boolean backupState) {

        public AssertionOutcome {
            credentialId = credentialId.clone();
        }

        @Override
        public byte[] credentialId() {
            return credentialId.clone();
        }
    }
}
