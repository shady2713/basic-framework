export interface ApiSelectOption {
  label: unknown;
  value: unknown;
}

interface ParseOptionsConfig {
  labelField: string;
  returnType: string;
  valueField: string;
}

type UnknownRecord = Record<string, unknown>;

const TEMPLATE_FIELD_PATTERN = /\$\{([^{}]+)\}/g;
const SAFE_PARAMETER_NAME_PATTERN = /^[a-z_]\w*$/i;
const RESERVED_PARAMETER_NAMES = new Set([
  '__proto__',
  'constructor',
  'prototype',
]);

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readOwnField(item: UnknownRecord, field: string): unknown {
  const normalizedField = field.trim();
  return Object.hasOwn(item, normalizedField)
    ? item[normalizedField]
    : undefined;
}

function parseField(item: UnknownRecord, template: string): unknown {
  if (!template.includes('${')) {
    return readOwnField(item, template);
  }
  return template.replaceAll(TEMPLATE_FIELD_PATTERN, (_, field: string) => {
    const value = readOwnField(item, field);
    return value === null || value === undefined ? '' : String(value);
  });
}

function extractItems(data: unknown): null | UnknownRecord[] {
  let items: unknown[];
  if (Array.isArray(data)) {
    items = data;
  } else if (isRecord(data) && Array.isArray(data.list)) {
    items = data.list;
  } else {
    return null;
  }
  return items.every((item) => isRecord(item)) ? items : null;
}

export function parseApiSelectOptions(
  data: unknown,
  config: ParseOptionsConfig,
): ApiSelectOption[] | null {
  const items = extractItems(data);
  if (items === null) {
    return null;
  }
  return items.map((item) => {
    const label = parseField(item, config.labelField);
    return {
      label,
      value:
        config.returnType === 'name'
          ? label
          : parseField(item, config.valueField),
    };
  });
}

export function parseApiRequestData(data: string): UnknownRecord {
  if (!data.trim()) {
    return {};
  }
  const parsed: unknown = JSON.parse(data);
  if (!isRecord(parsed)) {
    throw new TypeError('请求参数必须是 JSON 对象');
  }
  return parsed;
}

export function isSafeApiPath(path: string): boolean {
  return /^\/(?!\/)[^\s\\]*$/.test(path);
}

export function isSafeParameterName(name: string): boolean {
  return (
    SAFE_PARAMETER_NAME_PATTERN.test(name) &&
    !RESERVED_PARAMETER_NAMES.has(name)
  );
}
