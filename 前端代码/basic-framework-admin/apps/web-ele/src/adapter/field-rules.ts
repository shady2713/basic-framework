import { z } from '@vben/common-ui';
import { MOBILE_REGEX } from '@vben/utils';

const USERNAME_REGEX = /^[a-z][a-z0-9_]{3,29}$/;
const PASSWORD_REGEX = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[A-Za-z\d]{6,16}$/;
const EMAIL_REGEX = /^[\w.%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i;
const PERCENT_REGEX = /^(?:100(?:\.0{1,2})?|\d{1,2}(?:\.\d{1,2})?)$/;

function trimValue(value: unknown) {
  return String(value ?? '').trim();
}

function isBlank(value: unknown) {
  return trimValue(value).length === 0;
}

function isPresent(value: unknown) {
  if (value === undefined || value === null) {
    return false;
  }
  if (typeof value === 'string') {
    return !isBlank(value);
  }
  return true;
}

export function isUsernameValue(value: string) {
  return USERNAME_REGEX.test(normalizeUsernameValue(value));
}

function normalizeUsernameValue(value: string) {
  return value.normalize('NFC').trim().toLowerCase();
}

export function isPasswordValue(value: string) {
  return PASSWORD_REGEX.test(value);
}

export function isMobileValue(value: string) {
  return MOBILE_REGEX.test(value);
}

export function isEmailValue(value: string) {
  return EMAIL_REGEX.test(value);
}

export function isPercentValue(value: number | string) {
  return PERCENT_REGEX.test(String(value));
}

export function isQuantityValue(value: number) {
  return Number.isInteger(value) && value >= 0;
}

export function buildRequiredUsernameSchema(label = '用户名') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .transform(normalizeUsernameValue)
    .refine(isUsernameValue, {
      message: `${label}必须以字母开头，并使用 4-30 位小写字母、数字或下划线`,
    });
}

export function buildRequiredPasswordSchema(label = '密码') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .refine(isPasswordValue, {
      message: `${label}必须为 6-16 位，且同时包含大写字母、小写字母和数字`,
    });
}

export function buildLoginPasswordSchema(label = '密码') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` });
}

export function buildOptionalMobileSchema(label = '手机号') {
  return z
    .string()
    .trim()
    .optional()
    .refine(
      (value) => value === undefined || isBlank(value) || isMobileValue(value),
      {
        message: `${label}格式不正确`,
      },
    );
}

export function buildRequiredMobileSchema(label = '手机号') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .refine(isMobileValue, {
      message: `${label}格式不正确`,
    });
}

export function buildOptionalEmailSchema(label = '邮箱') {
  return z
    .string()
    .trim()
    .optional()
    .refine(
      (value) => value === undefined || isBlank(value) || isEmailValue(value),
      {
        message: `${label}格式不正确`,
      },
    );
}

export function buildRequiredEmailSchema(label = '邮箱') {
  return z
    .string({ required_error: `请输入${label}` })
    .trim()
    .min(1, { message: `请输入${label}` })
    .refine(isEmailValue, {
      message: `${label}格式不正确`,
    });
}

export function buildOptionalPercentSchema(label = '百分比') {
  return z
    .union([z.string(), z.number()])
    .optional()
    .refine(
      (value) => {
        return (
          value === undefined ||
          value === null ||
          isBlank(value) ||
          isPercentValue(value)
        );
      },
      {
        message: `${label}必须在 0-100 之间，最多保留两位小数`,
      },
    );
}

export function buildRequiredPercentSchema(label = '百分比') {
  return z.union([z.string(), z.number()]).refine(
    (value) => {
      return isPresent(value) && isPercentValue(value);
    },
    {
      message: `${label}必须在 0-100 之间，最多保留两位小数`,
    },
  );
}

export function buildOptionalQuantitySchema(label = '数量') {
  return z
    .number()
    .optional()
    .refine((value) => value === undefined || isQuantityValue(value), {
      message: `${label}必须为非负整数`,
    });
}

export function buildRequiredQuantitySchema(label = '数量') {
  return z
    .number({ required_error: `请输入${label}` })
    .int(`${label}必须为非负整数`)
    .min(0, `${label}必须为非负整数`);
}
