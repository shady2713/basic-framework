import { describe, expect, it } from 'vitest';

import {
  buildOptionalPercentSchema,
  buildRequiredUsernameSchema,
  isEmailValue,
  isMobileValue,
  isPasswordValue,
  isPercentValue,
  isQuantityValue,
  isUsernameValue,
} from './field-rules';

describe('field rules', () => {
  it('validates username', () => {
    expect(isUsernameValue('User123')).toBe(true);
    expect(isUsernameValue('abc')).toBe(false);
    expect(isUsernameValue('user_123')).toBe(true);
    expect(isUsernameValue('1user')).toBe(false);
    expect(buildRequiredUsernameSchema().parse('  User_123  ')).toBe(
      'user_123',
    );
  });

  it('validates password', () => {
    expect(isPasswordValue('Abcd12')).toBe(true);
    expect(isPasswordValue('abcdef12')).toBe(false);
    expect(isPasswordValue('ABCDEF12')).toBe(false);
    expect(isPasswordValue('Abcdefgh')).toBe(false);
  });

  it('validates mobile', () => {
    expect(isMobileValue('13812345678')).toBe(true);
    expect(isMobileValue('+8613812345678')).toBe(false);
    expect(isMobileValue('23812345678')).toBe(false);
  });

  it('validates email', () => {
    expect(isEmailValue('user@example.com')).toBe(true);
    expect(isEmailValue('user@invalid')).toBe(false);
  });

  it('validates percent', () => {
    expect(isPercentValue('100')).toBe(true);
    expect(isPercentValue('12.34')).toBe(true);
    expect(isPercentValue('100.001')).toBe(false);
  });

  it('allows blank optional percent values', () => {
    const schema = buildOptionalPercentSchema();

    expect(schema.safeParse(undefined).success).toBe(true);
    expect(schema.safeParse('').success).toBe(true);
    expect(schema.safeParse('12.34').success).toBe(true);
    expect(schema.safeParse('100.001').success).toBe(false);
  });
  it('validates quantity', () => {
    expect(isQuantityValue(0)).toBe(true);
    expect(isQuantityValue(12)).toBe(true);
    expect(isQuantityValue(-1)).toBe(false);
    expect(isQuantityValue(1.2)).toBe(false);
  });
});
