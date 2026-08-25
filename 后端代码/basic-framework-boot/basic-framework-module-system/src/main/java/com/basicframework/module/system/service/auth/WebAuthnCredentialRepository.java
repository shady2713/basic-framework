package com.basicframework.module.system.service.auth;

import com.basicframework.framework.common.util.json.JsonUtils;
import com.basicframework.module.system.dal.dataobject.auth.MfaFactorDO;
import com.basicframework.module.system.dal.mysql.auth.MfaFactorMapper;
import com.basicframework.module.system.enums.auth.MfaFactorTypeEnum;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import jakarta.annotation.Resource;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

/** 将 MFA 因子表适配为 Yubico 只读凭据仓储。 */
@Repository
public class WebAuthnCredentialRepository implements CredentialRepository {

    @Resource
    private MfaFactorMapper factorMapper;

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return parseUserId(username)
                .map(userId ->
                        factorMapper.selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType()))
                .orElseGet(Collections::emptyList)
                .stream()
                .map(this::toDescriptor)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return parseUserId(username)
                .flatMap(userId ->
                        factorMapper
                                .selectEnabledByUserIdAndTypeList(userId, MfaFactorTypeEnum.WEBAUTHN.getType())
                                .stream()
                                .findFirst())
                .map(MfaFactorDO::getUserHandle)
                .map(ByteArray::new);
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return Optional.ofNullable(factorMapper.selectFirstEnabledByUserHandle(
                        userHandle.getBytes(), MfaFactorTypeEnum.WEBAUTHN.getType()))
                .map(MfaFactorDO::getUserId)
                .map(String::valueOf);
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        return Optional.ofNullable(factorMapper.selectEnabledByCredentialId(
                        credentialId.getBytes(), MfaFactorTypeEnum.WEBAUTHN.getType()))
                .filter(factor -> Arrays.equals(factor.getUserHandle(), userHandle.getBytes()))
                .map(this::toRegisteredCredential);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return Optional.ofNullable(factorMapper.selectEnabledByCredentialId(
                        credentialId.getBytes(), MfaFactorTypeEnum.WEBAUTHN.getType()))
                .map(this::toRegisteredCredential)
                .map(Set::of)
                .orElseGet(Collections::emptySet);
    }

    private PublicKeyCredentialDescriptor toDescriptor(MfaFactorDO factor) {
        return PublicKeyCredentialDescriptor.builder()
                .id(new ByteArray(factor.getCredentialId()))
                .transports(parseTransports(factor.getTransports()))
                .build();
    }

    /** Yubico 2.9 仍要求仓库回填已知备份状态；弃用仅表示对应标准尚处实验阶段。 */
    @SuppressWarnings("deprecation")
    private RegisteredCredential toRegisteredCredential(MfaFactorDO factor) {
        return RegisteredCredential.builder()
                .credentialId(new ByteArray(factor.getCredentialId()))
                .userHandle(new ByteArray(factor.getUserHandle()))
                .publicKeyCose(new ByteArray(factor.getPublicKeyCose()))
                .signatureCount(factor.getSignatureCount())
                .backupEligible(factor.getBackupEligible())
                .backupState(factor.getBackupState())
                .build();
    }

    private static Set<AuthenticatorTransport> parseTransports(String transports) {
        List<String> values = JsonUtils.parseArray(transports, String.class);
        return values.stream().map(AuthenticatorTransport::of).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<Long> parseUserId(String username) {
        try {
            return Optional.of(Long.valueOf(username));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }
}
