interface WebAuthnJsonParsers {
  parseCreationOptionsFromJSON(
    options: Record<string, unknown>,
  ): PublicKeyCredentialCreationOptions;
  parseRequestOptionsFromJSON(
    options: Record<string, unknown>,
  ): PublicKeyCredentialRequestOptions;
}

interface SerializablePublicKeyCredential extends Credential {
  toJSON(): unknown;
}

/** 使用浏览器原生 WebAuthn Level 3 JSON API 创建凭据。 */
export async function createWebAuthnCredential(
  optionsJson: string,
): Promise<string> {
  const parsers = getParsers();
  const publicKey = parsers.parseCreationOptionsFromJSON(
    parsePublicKeyOptions(optionsJson),
  );
  const credential = await requireCredentialsContainer().create({ publicKey });
  return serializeCredential(credential);
}

/** 使用浏览器原生 WebAuthn Level 3 JSON API 获取认证断言。 */
export async function getWebAuthnCredential(
  optionsJson: string,
): Promise<string> {
  const parsers = getParsers();
  const publicKey = parsers.parseRequestOptionsFromJSON(
    parsePublicKeyOptions(optionsJson),
  );
  const credential = await requireCredentialsContainer().get({ publicKey });
  return serializeCredential(credential);
}

function getParsers(): WebAuthnJsonParsers {
  const credentialType = globalThis.PublicKeyCredential as unknown as
    | Partial<WebAuthnJsonParsers>
    | undefined;
  if (
    typeof credentialType?.parseCreationOptionsFromJSON !== 'function' ||
    typeof credentialType.parseRequestOptionsFromJSON !== 'function'
  ) {
    throw new TypeError(
      '当前浏览器版本不支持 WebAuthn，请升级浏览器或使用动态验证码',
    );
  }
  return credentialType as WebAuthnJsonParsers;
}

function requireCredentialsContainer(): CredentialsContainer {
  if (!globalThis.navigator?.credentials) {
    throw new Error('当前浏览器环境不支持 WebAuthn，请使用动态验证码');
  }
  return globalThis.navigator.credentials;
}

function parsePublicKeyOptions(optionsJson: string): Record<string, unknown> {
  let options: unknown;
  try {
    options = JSON.parse(optionsJson);
  } catch {
    throw new Error('WebAuthn 选项格式无效，请重新登录');
  }
  if (!isRecord(options) || !isRecord(options.publicKey)) {
    throw new Error('WebAuthn 选项格式无效，请重新登录');
  }
  return options.publicKey;
}

function serializeCredential(credential: Credential | null): string {
  if (
    !credential ||
    typeof (credential as Partial<SerializablePublicKeyCredential>).toJSON !==
      'function'
  ) {
    throw new Error('浏览器未返回有效的 WebAuthn 凭据');
  }
  return JSON.stringify(
    (credential as SerializablePublicKeyCredential).toJSON(),
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
