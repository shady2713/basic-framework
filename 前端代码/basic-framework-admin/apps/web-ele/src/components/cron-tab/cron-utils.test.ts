import { describe, expect, it } from 'vitest';

import { formatCronExpression, parseCronExpression } from './cron-utils';
import { createDefaultCronValue } from './types';

describe('cron 表达式转换', () => {
  it('为每个组件实例创建独立状态', () => {
    const first = createDefaultCronValue();
    const second = createDefaultCronValue();

    first.second.type = '3';
    first.second.appoint.push('10');

    expect(second.second.type).toBe('0');
    expect(second.second.appoint).toEqual([]);
  });

  it.each([
    '0 15 3 ? * 2',
    '0 0 0 ? * 2#3',
    '0 0 0 ? * 2L',
    '0 0 0 1 1 ? 2026/2',
    '0 0 0 L * ? 2026-2030',
  ])('无损往返受支持的表达式：%s', (expression) => {
    const parsed = parseCronExpression(expression);

    expect(parsed).toBeDefined();
    if (!parsed) {
      throw new Error(`表达式解析失败：${expression}`);
    }
    expect(formatCronExpression(parsed)).toBe(expression);
  });

  it('按开始年份和间隔解析年份字段', () => {
    const parsed = parseCronExpression('0 0 0 1 1 ? 2026/2');

    expect(parsed?.year.type).toBe('2');
    expect(parsed?.year.loop).toEqual({ start: 2026, end: 2 });
  });

  it.each([
    '',
    '* * * * *',
    '* * * * * ? * extra',
    'invalid * * * * ?',
    '* * * * * 2#x',
  ])('拒绝不完整或不受支持的表达式：%s', (expression) => {
    expect(parseCronExpression(expression)).toBeUndefined();
  });
});
