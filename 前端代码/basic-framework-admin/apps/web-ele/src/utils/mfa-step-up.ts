import type { RequestClientConfig } from '@vben/request';

const MFA_STEP_UP_REQUIRED_CODE = 1_002_000_016;
const MFA_STEP_UP_ERROR_BODY_MAX_BYTES = 4096;

type MfaStepUpHandler = () => Promise<void>;

interface MfaStepUpRequestConfig extends RequestClientConfig {
  __isMfaStepUpRetryRequest?: boolean;
}

interface MfaStepUpHttpError {
  config?: MfaStepUpRequestConfig;
  response?: {
    data?: unknown;
    status?: number;
  };
}

let activePrompt: Promise<void> | undefined;
let promptHandler: MfaStepUpHandler | undefined;

export class MfaStepUpCancelledError extends Error {
  readonly __CANCEL__ = true;

  constructor(message = 'MFA step-up cancelled') {
    super(message);
    this.name = 'MfaStepUpCancelledError';
  }
}

export function isMfaStepUpCancelled(error: unknown) {
  return error instanceof MfaStepUpCancelledError;
}

export function registerMfaStepUpHandler(handler: MfaStepUpHandler) {
  promptHandler = handler;
  return () => {
    if (promptHandler === handler) {
      promptHandler = undefined;
    }
  };
}

export function requestMfaStepUp() {
  if (!promptHandler) {
    return Promise.reject(
      new Error('MFA step-up handler has not been registered'),
    );
  }
  if (!activePrompt) {
    activePrompt = promptHandler().finally(() => {
      activePrompt = undefined;
    });
  }
  return activePrompt;
}

export function isMfaStepUpRequired(error: unknown) {
  const httpError = error as MfaStepUpHttpError;
  return (
    httpError.response?.status === 403 &&
    (httpError.response.data as undefined | { code?: number })?.code ===
      MFA_STEP_UP_REQUIRED_CODE &&
    !httpError.config?.__isMfaStepUpRetryRequest
  );
}

async function isMfaStepUpRequiredBlob(error: unknown) {
  const httpError = error as MfaStepUpHttpError;
  const body = httpError.response?.data;
  if (
    httpError.response?.status !== 403 ||
    httpError.config?.__isMfaStepUpRetryRequest ||
    !(body instanceof Blob) ||
    body.size > MFA_STEP_UP_ERROR_BODY_MAX_BYTES
  ) {
    return false;
  }
  try {
    const parsed = JSON.parse(await body.text()) as { code?: number };
    return parsed.code === MFA_STEP_UP_REQUIRED_CODE;
  } catch {
    return false;
  }
}

export async function retryAfterMfaStepUp<T>(
  error: unknown,
  retry: (config: MfaStepUpRequestConfig) => Promise<T>,
) {
  if (!isMfaStepUpRequired(error) && !(await isMfaStepUpRequiredBlob(error))) {
    throw error;
  }
  const httpError = error as MfaStepUpHttpError;
  const config = httpError.config;
  if (!config?.url) {
    throw error;
  }
  await requestMfaStepUp();
  return retry({ ...config, __isMfaStepUpRetryRequest: true });
}
