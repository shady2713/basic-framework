import { afterEach, describe, expect, it, vi } from 'vitest';

import { createWebAuthnCredential, getWebAuthnCredential } from './webauthn';

describe('webauthn browser adapter', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('parses creation options and serializes the credential', async () => {
    const publicKey = { challenge: new Uint8Array([1]) };
    const create = vi.fn().mockResolvedValue({
      toJSON: () => ({ id: 'created-credential' }),
    });
    vi.stubGlobal('PublicKeyCredential', {
      parseCreationOptionsFromJSON: vi.fn().mockReturnValue(publicKey),
      parseRequestOptionsFromJSON: vi.fn(),
    });
    vi.stubGlobal('navigator', { credentials: { create, get: vi.fn() } });

    const result = await createWebAuthnCredential(
      JSON.stringify({ publicKey: { challenge: 'AQ' } }),
    );

    expect(create).toHaveBeenCalledWith({ publicKey });
    expect(JSON.parse(result)).toEqual({ id: 'created-credential' });
  });

  it('parses request options and serializes the assertion', async () => {
    const publicKey = { challenge: new Uint8Array([2]) };
    const get = vi.fn().mockResolvedValue({
      toJSON: () => ({ id: 'asserted-credential' }),
    });
    vi.stubGlobal('PublicKeyCredential', {
      parseCreationOptionsFromJSON: vi.fn(),
      parseRequestOptionsFromJSON: vi.fn().mockReturnValue(publicKey),
    });
    vi.stubGlobal('navigator', { credentials: { create: vi.fn(), get } });

    const result = await getWebAuthnCredential(
      JSON.stringify({ publicKey: { challenge: 'Ag' } }),
    );

    expect(get).toHaveBeenCalledWith({ publicKey });
    expect(JSON.parse(result)).toEqual({ id: 'asserted-credential' });
  });

  it('rejects unsupported browsers before invoking credentials', async () => {
    vi.stubGlobal('PublicKeyCredential', undefined);

    await expect(
      createWebAuthnCredential(
        JSON.stringify({ publicKey: { challenge: 'AQ' } }),
      ),
    ).rejects.toThrow('当前浏览器版本不支持 WebAuthn');
  });
});
