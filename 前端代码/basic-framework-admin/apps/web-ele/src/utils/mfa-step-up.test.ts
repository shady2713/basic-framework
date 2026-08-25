import { afterEach, describe, expect, it, vi } from 'vitest';

import {
  isMfaStepUpRequired,
  registerMfaStepUpHandler,
  requestMfaStepUp,
  retryAfterMfaStepUp,
} from './mfa-step-up';

function createStepUpError() {
  return {
    config: { url: '/system/user/delete?id=1' },
    response: { data: { code: 1_002_000_016 }, status: 403 },
  };
}

describe('mfa step-up coordinator', () => {
  let unregister: (() => void) | undefined;

  afterEach(() => {
    unregister?.();
    unregister = undefined;
  });

  it('recognizes only an untried step-up response', () => {
    const stepUpError = createStepUpError();
    expect(isMfaStepUpRequired(stepUpError)).toBe(true);
    expect(
      isMfaStepUpRequired({
        ...stepUpError,
        config: {
          ...stepUpError.config,
          __isMfaStepUpRetryRequest: true,
        },
      }),
    ).toBe(false);
    expect(
      isMfaStepUpRequired({
        ...stepUpError,
        response: { data: { code: 403 }, status: 403 },
      }),
    ).toBe(false);
  });

  it('shares one prompt between concurrent protected requests', async () => {
    let finishPrompt: (() => void) | undefined;
    const handler = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          finishPrompt = resolve;
        }),
    );
    unregister = registerMfaStepUpHandler(handler);

    const first = requestMfaStepUp();
    const second = requestMfaStepUp();
    expect(handler).toHaveBeenCalledTimes(1);

    finishPrompt?.();
    await Promise.all([first, second]);
  });

  it('retries the original request once after successful verification', async () => {
    unregister = registerMfaStepUpHandler(() => Promise.resolve());
    const retry = vi.fn().mockResolvedValue('done');

    await expect(retryAfterMfaStepUp(createStepUpError(), retry)).resolves.toBe(
      'done',
    );
    expect(retry).toHaveBeenCalledOnce();
    expect(retry).toHaveBeenCalledWith(
      expect.objectContaining({
        __isMfaStepUpRetryRequest: true,
        url: '/system/user/delete?id=1',
      }),
    );
  });

  it('recognizes a protected download response encoded as a blob', async () => {
    unregister = registerMfaStepUpHandler(() => Promise.resolve());
    const retry = vi.fn().mockResolvedValue('downloaded');
    const downloadError = {
      config: { url: '/system/user/export-excel' },
      response: {
        data: new Blob([JSON.stringify({ code: 1_002_000_016 })], {
          type: 'application/json',
        }),
        status: 403,
      },
    };

    await expect(retryAfterMfaStepUp(downloadError, retry)).resolves.toBe(
      'downloaded',
    );
    expect(retry).toHaveBeenCalledOnce();
  });

  it('preserves unrelated errors without prompting', async () => {
    const handler = vi.fn().mockResolvedValue(undefined);
    unregister = registerMfaStepUpHandler(handler);
    const unrelated = {
      config: { url: '/system/user/delete?id=1' },
      response: { data: { code: 403 }, status: 403 },
    };

    await expect(retryAfterMfaStepUp(unrelated, vi.fn())).rejects.toBe(
      unrelated,
    );
    expect(handler).not.toHaveBeenCalled();
  });
});
