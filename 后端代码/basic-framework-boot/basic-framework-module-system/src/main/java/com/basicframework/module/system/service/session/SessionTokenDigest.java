package com.basicframework.module.system.service.session;

import cn.hutool.crypto.digest.DigestUtil;
import java.util.Objects;

/** 会话令牌摘要工具。 */
final class SessionTokenDigest {

    private SessionTokenDigest() {}

    static String digest(String token) {
        return DigestUtil.sha256Hex(Objects.requireNonNull(token, "token must not be null"));
    }
}
