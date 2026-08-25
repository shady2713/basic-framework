import { describe, expect, it } from 'vitest';

import {
  isSafeApiPath,
  isSafeParameterName,
  parseApiRequestData,
  parseApiSelectOptions,
} from './api-select-options';

const config = {
  labelField: ['nickname', 'enabled', 'count']
    .map((field) => '$' + `{${field}}`)
    .join('-'),
  returnType: 'id',
  valueField: 'id',
};

describe('api select options', () => {
  it('解析数组和分页列表，并保留 false 与零值', () => {
    const item = { count: 0, enabled: false, id: 1, nickname: 'admin' };

    expect(parseApiSelectOptions([item], config)).toEqual([
      { label: 'admin-false-0', value: 1 },
    ]);
    expect(parseApiSelectOptions({ list: [item] }, config)).toEqual([
      { label: 'admin-false-0', value: 1 },
    ]);
  });

  it('只读取响应对象的自有属性', () => {
    const inherited = Object.create({ nickname: 'prototype-value' }) as {
      id: number;
    };
    inherited.id = 1;

    expect(
      parseApiSelectOptions([inherited], {
        labelField: 'nickname',
        returnType: 'id',
        valueField: 'id',
      }),
    ).toEqual([{ label: undefined, value: 1 }]);
  });

  it('拒绝非列表响应和非对象请求参数', () => {
    expect(parseApiSelectOptions({ data: [] }, config)).toBeNull();
    expect(() => parseApiRequestData('[]')).toThrow('请求参数必须是 JSON 对象');
  });

  it('仅允许站内绝对路径和安全参数名', () => {
    expect(isSafeApiPath('/system/user/simple-list')).toBe(true);
    expect(isSafeApiPath('https://evil.example/api')).toBe(false);
    expect(isSafeApiPath('//evil.example/api')).toBe(false);
    expect(isSafeApiPath(String.raw`/\evil.example/api`)).toBe(false);
    expect(isSafeParameterName('nickname')).toBe(true);
    expect(isSafeParameterName('__proto__')).toBe(false);
    expect(isSafeParameterName('name&admin=true')).toBe(false);
  });
});
